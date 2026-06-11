package uk.shusek.krwa.wasm

import kotlinx.io.RawSource
import uk.shusek.krwa.wasm.types.CustomSection

/** Portable WebAssembly binary parser for Kotlin Multiplatform targets. */
class WasmParser private constructor(private val core: WasmParserCore) {
    private constructor() : this(WasmParserCore.builder().build())

    class Builder private constructor() {
        private val coreBuilder = WasmParserCore.builder()

        /** @param sectionId the section id to include while parsing, e.g. SectionId.MEMORY. */
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

        fun build(): WasmParser = WasmParser(coreBuilder.build())

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    fun parseBytes(bytes: ByteArray): WasmModule = core.parseBytes(bytes)

    fun parse(source: RawSource): WasmModule = core.parse(source)

    fun parseBytes(bytes: ByteArray, listener: ParserListener) {
        core.parseBytes(bytes, listener)
    }

    fun parse(source: RawSource, listener: ParserListener) {
        core.parse(source, listener)
    }

    fun parseWithoutDecoding(bytes: ByteArray, listener: ParserListener) {
        core.parseWithoutDecoding(bytes, listener)
    }

    fun parseWithoutDecoding(source: RawSource, listener: ParserListener) {
        core.parseWithoutDecoding(source, listener)
    }

    companion object {
        val MAGIC_BYTES: ByteArray = WasmParserCore.MAGIC_BYTES.copyOf()
        val VERSION_BYTES: ByteArray = WasmParserCore.VERSION_BYTES.copyOf()

        fun builder(): Builder = Builder.create()

        fun parse(bytes: ByteArray): WasmModule = WasmParser().parseBytes(bytes)

        fun parse(source: RawSource): WasmModule = WasmParser().parse(source)

        fun parseWithoutDecoding(bytes: ByteArray, listener: ParserListener) {
            WasmParserCore.parseWithoutDecoding(bytes, listener)
        }

        fun parseWithoutDecoding(source: RawSource, listener: ParserListener) {
            WasmParserCore.parseWithoutDecoding(source, listener)
        }
    }
}
