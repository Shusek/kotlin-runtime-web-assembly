package uk.shusek.krwa.tools.wasm

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

class WasmSmith private constructor() {
    companion object {
        private val LOGGER: Logger =
            object : SystemLogger() {
                override fun isLoggable(level: Logger.Level): Boolean = false
            }

        @JvmStatic
        @Throws(WasmSmithException::class)
        fun run(
            seed: ByteArray,
            properties: Map<String, String>,
            allowedInstructions: String,
        ): ByteArray =
            try {
                runInternal(seed, properties, allowedInstructions)
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }

        @Throws(IOException::class, WasmSmithException::class)
        private fun runInternal(
            seed: ByteArray,
            properties: Map<String, String>,
            allowedInstructions: String,
        ): ByteArray {
            ByteArrayInputStream(seed).use { stdin ->
                ByteArrayOutputStream().use { stdout ->
                    ByteArrayOutputStream().use { stderr ->
                        ZeroFs.newFileSystem(
                                Configuration.unix().toBuilder().setAttributeViews("unix").build()
                            )
                            .use { fs ->
                                val outputDir = fs.getPath("output")
                                java.nio.file.Files.createDirectory(outputDir)
                                val outputPath = outputDir.resolve("generated.wasm")

                                val wasiOptions = WasiOptions.builder()
                                wasiOptions.withStdin(stdin, false)
                                wasiOptions.withStdout(stdout, false)
                                wasiOptions.withStderr(stderr, false)
                                wasiOptions.withDirectory(outputDir.toString(), outputDir)

                                val args = ArrayList<String>()
                                args.add("wasm-tools")
                                args.add("smith")
                                for ((key, value) in properties) {
                                    args.add("--$key")
                                    args.add(value)
                                }
                                args.add("--allowed-instructions")
                                args.add(allowedInstructions)
                                args.add("-o")
                                args.add(outputPath.toString())

                                wasiOptions.withArguments(args)

                                try {
                                    WasiPreview1.builder()
                                        .withLogger(LOGGER)
                                        .withOptions(wasiOptions.build())
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
                                                .withMemoryFactory { limits ->
                                                    ByteArrayMemory(limits)
                                                }
                                                .withImportValues(imports)
                                                .build()
                                        }
                                } catch (e: WasiExitException) {
                                    if (e.exitCode() != 0) {
                                        throw WasmSmithException(
                                            stderr.toString(StandardCharsets.UTF_8),
                                            e,
                                        )
                                    }
                                }

                                if (!java.nio.file.Files.exists(outputPath)) {
                                    throw WasmSmithException(
                                        "wasm-smith produced no output file: " +
                                            stderr.toString(StandardCharsets.UTF_8)
                                    )
                                }

                                return java.nio.file.Files.readAllBytes(outputPath)
                            }
                    }
                }
            }
        }
    }
}
