package uk.shusek.krwa.wasm.types

/**
 * The type of external definition, import, or export.
 *
 * See https://webassembly.github.io/spec/core/syntax/types.html#external-types for reference. See
 * also
 * https://github.com/WebAssembly/exception-handling/blob/main/proposals/exception-handling/Exceptions.md#external_kind
 * for the history of [TAG].
 */
enum class ExternalType(private val id: Int) {
    // note: keep in order
    FUNCTION(0x00),
    TABLE(0x01),
    MEMORY(0x02),
    GLOBAL(0x03),
    TAG(0x04);

    init {
        @Suppress("SENSELESS_COMPARISON") assert(ordinal == id)
    }

    /** @return the numerical identifier for this external kind */
    fun id(): Int = id

    companion object {
        private val valuesList = values().toList()

        fun byId(id: Int): ExternalType = valuesList[id]
    }
}
