package uk.shusek.krwa.wabt

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class Wat2Wasm private constructor() {
    companion object {
        private val LOGGER: Logger = SystemLogger()
        private val RUNTIME = WabtRuntime("uk.shusek.krwa.wabt.Wat2WasmModule")

        @JvmStatic fun parse(input: InputStream): ByteArray = parse(input, "temp.wast")

        @JvmStatic
        fun parse(file: File): ByteArray =
            try {
                FileInputStream(file).use { parse(it, file.name) }
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

        private fun parse(input: InputStream, fileName: String): ByteArray {
            try {
                ByteArrayOutputStream().use { stdout ->
                    ByteArrayOutputStream().use { stderr ->
                        ZeroFs.newFileSystem(
                                Configuration.unix().toBuilder().setAttributeViews("unix").build()
                            )
                            .use { fs ->
                                val target = fs.getPath("tmp")
                                java.nio.file.Files.createDirectory(target)
                                val path = target.resolve(fileName)
                                java.nio.file.Files.copy(
                                    input,
                                    path,
                                    StandardCopyOption.REPLACE_EXISTING,
                                )

                                val wasiOptions =
                                    WasiOptions.builder()
                                        .withStdout(stdout)
                                        .withStderr(stderr)
                                        .withDirectory(target.toString(), target)
                                        .withArguments(
                                            listOf("wat2wasm", path.toString(), "--output=-")
                                        )
                                        .withThrowOnExit0(false)
                                        .build()

                                try {
                                    WasiPreview1.builder()
                                        .withLogger(LOGGER)
                                        .withOptions(wasiOptions)
                                        .build()
                                        .use { wasi ->
                                            val imports =
                                                ImportValues.builder()
                                                    .addFunction(*wasi.toHostFunctions())
                                                    .build()
                                            Instance.builder(RUNTIME.module)
                                                .withMachineFactory { instance ->
                                                    RUNTIME.create(instance)
                                                }
                                                .withImportValues(imports)
                                                .build()
                                        }
                                } catch (e: WasiExitException) {
                                    throw WatParseException(
                                        stdout.toString(StandardCharsets.UTF_8) +
                                            stderr.toString(StandardCharsets.UTF_8),
                                        e,
                                    )
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
