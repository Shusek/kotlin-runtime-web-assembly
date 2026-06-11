package uk.shusek.krwa.tools.wasm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class Validate private constructor(private val features: List<String>) {
    fun validateModule(file: File) {
        try {
            FileInputStream(file).use { validateModule(it) }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun validateModule(wat: String) {
        try {
            ByteArrayInputStream(wat.toByteArray(StandardCharsets.UTF_8)).use { validateModule(it) }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun validateModule(input: InputStream) {
        doValidate(input, features)
    }

    class Builder private constructor() {
        private val features = ArrayList<String>()

        fun withFeatures(vararg features: WasmFeature): Builder {
            for (feature in features) {
                this.features.add(feature.flag())
            }
            return this
        }

        fun withoutFeature(feature: WasmFeature): Builder {
            features.add(feature.negatedFlag())
            return this
        }

        fun build(): Validate = Validate(Collections.unmodifiableList(ArrayList(features)))

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

        @JvmStatic
        fun validate(file: File) {
            try {
                FileInputStream(file).use { validate(it) }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }

        @JvmStatic
        fun validate(wat: String) {
            try {
                ByteArrayInputStream(wat.toByteArray(StandardCharsets.UTF_8)).use { validate(it) }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }

        @JvmStatic
        fun validate(input: InputStream) {
            doValidate(input, emptyList())
        }

        private fun doValidate(input: InputStream, features: List<String>) {
            try {
                ByteArrayInputStream(input.readAllBytes()).use { stdin ->
                    ByteArrayOutputStream().use { stdout ->
                        ByteArrayOutputStream().use { stderr ->
                            val args = ArrayList<String>()
                            args.add("wasm-tools")
                            args.add("validate")
                            if (features.isNotEmpty()) {
                                args.add("--features")
                                args.add(features.joinToString(","))
                            }
                            args.add("-")

                            val options =
                                WasiOptions.builder()
                                    .withStdin(stdin, false)
                                    .withStdout(stdout, false)
                                    .withStderr(stderr, false)
                                    .withArguments(args)
                                    .build()

                            LOGGER.info("Running command: ${options.arguments().joinToString(" ")}")

                            WasiPreview1.builder()
                                .withLogger(LOGGER)
                                .withOptions(options)
                                .build()
                                .use { wasi ->
                                    val imports =
                                        ImportValues.builder()
                                            .addFunction(*wasi.toHostFunctions())
                                            .build()

                                    try {
                                        Instance.builder(WasmToolsRuntime.module)
                                            .withMachineFactory { instance ->
                                                WasmToolsRuntime.create(instance)
                                            }
                                            .withMemoryFactory { limits -> ByteArrayMemory(limits) }
                                            .withImportValues(imports)
                                            .build()
                                    } catch (e: WasiExitException) {
                                        if (e.exitCode() != 0) {
                                            throw WatParseException(
                                                stdout.toString(StandardCharsets.UTF_8) +
                                                    stderr.toString(StandardCharsets.UTF_8),
                                                e,
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }
}
