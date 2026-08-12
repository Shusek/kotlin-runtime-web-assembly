package uk.shusek.krwa.component.tooling

import java.nio.file.Path
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.tools.wasm.WasmToolsCli
import uk.shusek.krwa.tools.wasm.WasmToolsRuntime
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class DefaultWasmToolsExecutionProvider : WasmToolsExecutionProvider {
    override fun execute(
        args: List<String>,
        directories: Map<String, okio.Path>,
    ): WasmToolsExecutionResult {
        val nioDirectories = directories.mapValues { (_, path) -> Path.of(path.toString()) }
        WasmToolsCli.run(args, directories = nioDirectories)?.let { result ->
            return WasmToolsExecutionResult(result.exitCode, result.stdout(), result.stderr())
        }

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
                Instance.builder(WasmToolsRuntime.module)
                    .withWasmtimeExecutionConfig(WasmToolsRuntime.executionConfig)
                    .withImportValues(imports)
                    .build()
            }
        } catch (error: WasiExitException) {
            exitCode = error.exitCode()
        }

        return WasmToolsExecutionResult(
            exitCode,
            stdout.readByteArray(),
            stderr.readByteArray(),
        )
    }

    private companion object {
        val LOGGER: Logger =
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
    }
}
