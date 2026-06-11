package uk.shusek.krwa.wasm

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.RawSource
import uk.shusek.krwa.wasm.io.InputStreams
import uk.shusek.krwa.wasm.types.CustomSection

/** JVM facade for the portable WebAssembly binary parser. */
class Parser private constructor(private val core: WasmParserCore) {
    private constructor() : this(WasmParserCore.builder().build())

    class Builder private constructor() {
        private val coreBuilder = WasmParserCore.builder()

        /** @param sectionId the sectionId to be included while parsing, e.g. SectionId.MEMORY */
        fun includeSectionId(sectionId: Int): Builder {
            coreBuilder.includeSectionId(sectionId)
            return this
        }

        fun withCustomParsers(customParsers: Map<String, (ByteArray) -> CustomSection>): Builder {
            coreBuilder.withCustomParsers(customParsers)
            return this
        }

        fun withValidation(validate: Boolean): Builder {
            coreBuilder.withValidation(validate)
            return this
        }

        fun withMultiTable(multiTable: Boolean): Builder {
            coreBuilder.withMultiTable(multiTable)
            return this
        }

        fun withMultiMemory(multiMemory: Boolean): Builder {
            coreBuilder.withMultiMemory(multiMemory)
            return this
        }

        fun withThreadsMemory(threadsMemory: Boolean): Builder {
            coreBuilder.withThreadsMemory(threadsMemory)
            return this
        }

        fun withForwardTypeReferences(allow: Boolean): Builder {
            coreBuilder.withForwardTypeReferences(allow)
            return this
        }

        fun withLocalGlobalReferencesInConstantExpressions(allow: Boolean): Builder {
            coreBuilder.withLocalGlobalReferencesInConstantExpressions(allow)
            return this
        }

        fun build(): Parser = Parser(coreBuilder.build())

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    fun parse(inputStreamSupplier: () -> InputStream): WasmModule {
        val bytes =
            try {
                inputStreamSupplier().use { input -> InputStreams.readAllBytes(input) }
            } catch (e: IOException) {
                throw WasmEngineException(e)
            }
        return parseBytes(bytes)
    }

    fun parseBytes(bytes: ByteArray): WasmModule = core.parseBytes(bytes)

    fun parse(source: RawSource): WasmModule = core.parse(source)

    fun parse(input: InputStream, listener: ParserListener) {
        parse(input, listener, true)
    }

    fun parse(source: RawSource, listener: ParserListener) {
        core.parse(source, listener)
    }

    fun parseWithoutDecoding(input: InputStream, listener: ParserListener) {
        parse(input, listener, false)
    }

    private fun parse(input: InputStream, listener: ParserListener, decode: Boolean) {
        try {
            val bytes = InputStreams.readAllBytes(input)
            if (decode) {
                core.parseBytes(bytes, listener)
            } else {
                core.parseWithoutDecoding(bytes, listener)
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Failed to read wasm bytes.", e)
        }
    }

    companion object {
        @JvmField val MAGIC_BYTES: ByteArray = WasmParserCore.MAGIC_BYTES.copyOf()
        @JvmField val VERSION_BYTES: ByteArray = WasmParserCore.VERSION_BYTES.copyOf()

        @JvmStatic fun builder(): Builder = Builder.create()

        @JvmStatic fun parse(input: InputStream): WasmModule = Parser().parse { input }

        fun parse(source: RawSource): WasmModule = Parser().parse(source)

        @JvmStatic fun parse(buffer: ByteArray): WasmModule = Parser().parseBytes(buffer)

        @JvmStatic fun parse(file: File): WasmModule = parse(file.toPath())

        @JvmStatic
        fun parse(path: Path): WasmModule =
            Parser().parse {
                try {
                    Files.newInputStream(path)
                } catch (e: IOException) {
                    throw IllegalArgumentException("Error opening file: $path", e)
                }
            }

        @JvmStatic
        fun parseWithoutDecoding(bytes: ByteArray, listener: ParserListener) {
            WasmParserCore.parseWithoutDecoding(bytes, listener)
        }

        @JvmStatic
        fun parseWithoutDecoding(source: RawSource, listener: ParserListener) {
            WasmParserCore.parseWithoutDecoding(source, listener)
        }
    }
}
