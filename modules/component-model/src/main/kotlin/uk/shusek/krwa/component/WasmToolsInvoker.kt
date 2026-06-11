package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.Path
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.tools.wasm.WasmToolsModule
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasm.WasmModule

object WasmToolsInvoker {
    private val LOGGER: Logger =
        object : SystemLogger() {
            override fun log(level: Logger.Level, msg: String, throwable: Throwable?) {
                if (!isLoggable(level)) {
                    return
                }
                System.err.println(msg)
                throwable?.printStackTrace(System.err)
            }

            override fun isLoggable(level: Logger.Level): Boolean =
                java.lang.Boolean.getBoolean("krwa.component.wasmtools.trace")
        }

    private val MODULE: WasmModule = WasmToolsModule.load()

    @JvmStatic
    fun run(args: List<String>, directories: Map<String, Path>): Result {
        val stdin = Buffer()
        val stdout = Buffer()
        val stderr = Buffer()
        val options =
            WasiOptions.builder()
                .withStdin(stdin, false)
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withArguments(args)
        for ((guestName, hostPath) in directories) {
            options.withDirectory(guestName, hostPath)
        }

        var exitCode = 0
        try {
            WasiPreview1.builder().withLogger(LOGGER).withOptions(options.build()).build().use {
                wasi ->
                val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                Instance.builder(MODULE)
                    .withMachineFactory { instance -> WasmToolsModule.create(instance) }
                    .withMemoryFactory { limits -> ByteArrayMemory(limits) }
                    .withImportValues(imports)
                    .build()
            }
        } catch (e: WasiExitException) {
            exitCode = e.exitCode()
        }

        val result = Result(exitCode, stdout.readByteArray(), stderr.readByteArray())
        if (exitCode != 0) {
            throw ComponentModelException(result.stderrText() + result.stdoutText())
        }
        return result
    }

    @JvmStatic
    fun directory(guestName: String, hostPath: Path): Map<String, Path> {
        val directories = LinkedHashMap<String, Path>()
        directories[guestName] = hostPath
        return directories
    }

    class Result
    internal constructor(private val exitCode: Int, stdout: ByteArray, stderr: ByteArray) {
        private val stdout: ByteArray = stdout.clone()
        private val stderr: ByteArray = stderr.clone()

        fun exitCode(): Int = exitCode

        fun stdout(): ByteArray = stdout.clone()

        fun stdoutText(): String = String(stdout, StandardCharsets.UTF_8)

        fun stderrText(): String = String(stderr, StandardCharsets.UTF_8)
    }
}
