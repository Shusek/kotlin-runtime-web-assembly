package uk.shusek.krwa.tools.wasm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class Wat2Wasm private constructor() {
    companion object {
        private val LOGGER: Logger =
            object : SystemLogger() {
                override fun isLoggable(level: Logger.Level): Boolean = false
            }

        @JvmStatic
        fun parse(file: File): ByteArray =
            try {
                FileInputStream(file).use { parse(it) }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }

        @JvmStatic
        fun parse(wat: String): ByteArray =
            try {
                ByteArrayInputStream(wat.toByteArray(StandardCharsets.UTF_8)).use { parse(it) }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }

        @JvmStatic
        fun parse(input: InputStream): ByteArray {
            try {
                ByteArrayInputStream(input.readAllBytes()).use { stdin ->
                    ByteArrayOutputStream().use { stdout ->
                        ByteArrayOutputStream().use { stderr ->
                            Validate.validate(stdin)
                            stdin.reset()

                            val options =
                                WasiOptions.builder()
                                    .withStdin(stdin, false)
                                    .withStdout(stdout, false)
                                    .withStderr(stderr, false)
                                    .withArguments(listOf("wasm-tools", "parse", "-"))
                                    .build()

                            LOGGER.info("Running command: ${options.arguments().joinToString(" ")}")

                            try {
                                WasiPreview1.builder()
                                    .withLogger(LOGGER)
                                    .withOptions(options)
                                    .build()
                                    .use { wasi ->
                                        val imports =
                                            ImportValues.builder()
                                                .addFunction(*wasi.toHostFunctions())
                                                .build()

                                        Instance.builder(WasmToolsRuntime.module)
                                            .withMachineFactory { instance ->
                                                WasmToolsRuntime.create(instance)
                                            }
                                            .withMemoryFactory { limits -> ByteArrayMemory(limits) }
                                            .withImportValues(imports)
                                            .build()
                                    }
                            } catch (e: WasiExitException) {
                                if (e.exitCode() != 0 || stdout.size() <= 0) {
                                    throw WatParseException(
                                        stdout.toString(StandardCharsets.UTF_8) +
                                            stderr.toString(StandardCharsets.UTF_8),
                                        e,
                                    )
                                }
                            }

                            return stdout.toByteArray()
                        }
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }
}
