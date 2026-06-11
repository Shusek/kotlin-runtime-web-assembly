package uk.shusek.krwa.fuzz

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.function.Function
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType

class FuzzTest : TestModule() {
    private val smith = WasmSmithWrapper()
    private val interpreterRunner: WasmRunner = DefaultRunner()
    private val compilerRunner: WasmRunner =
        DefaultRunner(
            Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
        )

    @AfterEach
    fun tearDown() {
        shutdown()
    }

    @ParameterizedTest
    @EnumSource(
        value = InstructionType::class,
        names = ["NUMERIC", "TABLE", "MEMORY", "CONTROL", "VARIABLE", "PARAMETRIC", "REFERENCE"],
    )
    fun differentialFuzz(type: InstructionType) {
        val failures = ArrayList<String>()
        var smithFailures = 0

        for (i in 0..<ITERATIONS) {
            logger.info(String.format("Iteration %d of %d for %s", i + 1, ITERATIONS, type.value()))

            val targetWasm: File
            try {
                targetWasm = smith.run(type.value() + "-" + i, "test.wasm", InstructionTypes(type))
            } catch (e: IOException) {
                logger.warn("wasm-smith failed to generate module: $e")
                smithFailures++
                continue
            }

            val module: WasmModule
            try {
                module = Parser.parse(targetWasm)
            } catch (e: RuntimeException) {
                logger.warn("Generated WASM failed to parse: $e")
                saveCrashReproducer(targetWasm, type.value(), "parse", e)
                failures.add("parse: " + e.message)
                continue
            }

            var hasExportedFunction = false
            for (j in 0..<module.exportSection().exportCount()) {
                if (module.exportSection().getExport(j).exportType() == ExternalType.FUNCTION) {
                    hasExportedFunction = true
                    break
                }
            }
            if (!hasExportedFunction) {
                continue
            }

            val instance: Instance
            try {
                instance = Instance.builder(module).withInitialize(true).withStart(false).build()
            } catch (e: RuntimeException) {
                logger.info("Module trapped during instantiation (expected for random wasm): $e")
                continue
            }

            val results =
                testModule(
                    targetWasm,
                    module,
                    instance,
                    interpreterRunner,
                    compilerRunner,
                    type.value(),
                    true,
                )

            for (res in results) {
                if (res.engineResult == null) {
                    failures.add("compiler crash (subject returned null)")
                } else if (res.oracleResult != res.engineResult) {
                    failures.add(
                        "mismatch: oracle=" + res.oracleResult + " subject=" + res.engineResult
                    )
                }
            }
        }

        assertTrue(
            smithFailures < ITERATIONS,
            "wasm-smith failed on all " +
                ITERATIONS +
                " iterations for " +
                type.value() +
                " -- check smith.default.properties for unsupported flags",
        )

        assertTrue(
            failures.isEmpty(),
            failures.size.toString() +
                " failure(s) found for " +
                type.value() +
                ". Reproducers saved to target/crash-reproducers/.\n" +
                failures.joinToString("\n"),
        )
    }

    companion object {
        private val logger: Logger = SystemLogger()
        private val ITERATIONS = Integer.getInteger("fuzz.test.iterations", 10)

        private fun saveCrashReproducer(
            targetWasm: File,
            instructionType: String,
            phase: String,
            e: RuntimeException,
        ) {
            try {
                CrashReproducer.save(targetWasm, instructionType, phase, e.message, null)
            } catch (ex: IOException) {
                logger.error("Failed to save crash reproducer: $ex")
            }
        }
    }
}
