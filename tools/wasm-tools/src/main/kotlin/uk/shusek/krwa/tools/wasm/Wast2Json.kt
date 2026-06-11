package uk.shusek.krwa.tools.wasm

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.StandardCopyOption
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class Wast2Json
private constructor(
    private val input: File,
    private val output: File,
    private val options: Array<String>,
) {
    fun process() {
        try {
            FileInputStream(input).use { inputStream ->
                ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder().setAttributeViews("unix").build()
                    )
                    .use { fs ->
                        val wasiOptions = WasiOptions.builder()
                        wasiOptions.inheritSystem()

                        val inputFolder = fs.getPath("input")
                        java.nio.file.Files.createDirectory(inputFolder)
                        val inputPath = inputFolder.resolve("spec.wast")
                        java.nio.file.Files.copy(
                            inputStream,
                            inputPath,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        wasiOptions.withDirectory(inputFolder.toString(), inputFolder)

                        val outputFolder = fs.getPath("output")
                        java.nio.file.Files.createDirectory(outputFolder)
                        wasiOptions.withDirectory(outputFolder.toString(), outputFolder)
                        java.nio.file.Files.createDirectory(outputFolder.resolve(output.name))

                        val args = ArrayList<String>()
                        args.add("wasm-tools")
                        args.add("json-from-wast")
                        args.add(inputPath.toString())
                        args.add("--wasm-dir")
                        args.add(outputFolder.resolve(output.name).toString())
                        args.add("--output")
                        args.add(outputFolder.resolve(output.name).resolve("spec.json").toString())
                        args.addAll(options)

                        LOGGER.info("Running command: ${args.joinToString(" ")}")
                        wasiOptions.withArguments(args)

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
                                    .withMemoryFactory { limits -> ByteArrayMemory(limits) }
                                    .withImportValues(imports)
                                    .build()
                            }

                        java.nio.file.Files.createDirectories(output.toPath())
                        Files.copyDirectory(outputFolder.resolve(output.name), output.toPath())
                    }
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    class Builder private constructor() {
        private lateinit var input: File
        private lateinit var output: File
        private var options: Array<String> = emptyArray()

        fun withFile(file: File): Builder {
            input = file
            return this
        }

        fun withOutput(file: File): Builder {
            output = file
            return this
        }

        fun withOptions(options: Array<String>): Builder {
            this.options = options
            return this
        }

        fun build(): Wast2Json = Wast2Json(input, output, options)

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    companion object {
        private val LOGGER: Logger =
            object : SystemLogger() {
                override fun isLoggable(level: Logger.Level): Boolean = false
            }

        @JvmStatic fun builder(): Builder = Builder.create()
    }
}
