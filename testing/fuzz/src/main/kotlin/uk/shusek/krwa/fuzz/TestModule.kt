package uk.shusek.krwa.fuzz

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.apache.commons.lang3.RandomStringUtils
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType

open class TestModule {
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun shutdown() {
        executor.shutdownNow()
    }

    fun paramsList(type: FunctionType): List<String> = type.params().map { randomNumber() }

    @Suppress("deprecation") fun randomNumber(): String = RandomStringUtils.randomNumeric(2)

    @Throws(Exception::class)
    fun testModule(
        targetWasm: File,
        module: WasmModule,
        instance: Instance,
        oracle: WasmRunner,
        subject: WasmRunner,
        instructionType: String,
        commitOnFailure: Boolean,
    ): List<TestResult> {
        val results = ArrayList<TestResult>()

        for (i in 0..<module.exportSection().exportCount()) {
            val export = module.exportSection().getExport(i)
            if (export.exportType() != ExternalType.FUNCTION) {
                logger.info("Skipping export " + export.name())
                continue
            }

            logger.info("Going to test export " + export.name())
            val type = instance.exportType(export.name())
            val params = paramsList(type)

            val oracleResult: String
            try {
                logger.info("  Running oracle (interpreter) ...")
                val start = System.currentTimeMillis()
                oracleResult = runWithTimeout(oracle, targetWasm, export.name(), params)
                logger.info("  Oracle finished in " + (System.currentTimeMillis() - start) + " ms")
            } catch (e: TimeoutException) {
                logger.warn(
                    "  Oracle timed out after " +
                        PER_CALL_TIMEOUT_SECONDS +
                        "s -- skipping (expected for random wasm with loops)"
                )
                continue
            } catch (e: RuntimeException) {
                logger.error("Failed to run oracle, skip the check: $e")
                continue
            }

            var subjectResult: String?
            try {
                logger.info("  Running subject (compiler) ...")
                val start = System.currentTimeMillis()
                subjectResult = runWithTimeout(subject, targetWasm, export.name(), params)
                logger.info("  Subject finished in " + (System.currentTimeMillis() - start) + " ms")
            } catch (e: TimeoutException) {
                logger.warn(
                    "  Subject timed out after " +
                        PER_CALL_TIMEOUT_SECONDS +
                        "s but oracle succeeded -- saving reproducer"
                )
                if (commitOnFailure) {
                    try {
                        CrashReproducer.save(
                            targetWasm,
                            instructionType,
                            export.name(),
                            oracleResult,
                            "timeout (subject)",
                        )
                    } catch (ex: IOException) {
                        logger.error("Failed to save crash reproducer: $ex")
                    }
                }
                subjectResult = null
            } catch (e: RuntimeException) {
                logger.warn("Failed to run subject, but oracle succeeded: $e")
                subjectResult = null
            }

            if (commitOnFailure && (subjectResult == null || oracleResult != subjectResult)) {
                try {
                    CrashReproducer.save(
                        targetWasm,
                        instructionType,
                        export.name(),
                        oracleResult,
                        subjectResult,
                    )
                } catch (ex: IOException) {
                    logger.error("Failed to save crash reproducer: $ex")
                }
            }

            results.add(TestResult(oracleResult, subjectResult))
        }

        return results
    }

    @Throws(TimeoutException::class, Exception::class)
    private fun runWithTimeout(
        runner: WasmRunner,
        wasmFile: File,
        functionName: String,
        params: List<String>,
    ): String {
        val future = executor.submit<String> { runner.run(wasmFile, functionName, params) }
        try {
            return future.get(PER_CALL_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            executor.shutdownNow()
            executor = Executors.newSingleThreadExecutor()
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is RuntimeException) {
                throw cause
            }
            if (cause is Exception) {
                throw cause
            }
            throw RuntimeException(cause)
        }
    }

    companion object {
        private val logger: Logger = SystemLogger()
        private const val PER_CALL_TIMEOUT_SECONDS = 120
    }
}
