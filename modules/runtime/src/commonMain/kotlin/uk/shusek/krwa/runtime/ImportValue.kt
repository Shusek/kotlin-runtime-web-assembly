package uk.shusek.krwa.runtime

/**
 * An external value is the runtime representation of an entity that can be imported. It is an
 * address denoting either a function instance, table instance, memory instance, or global instances
 * in the shared store.
 *
 * See also https://webassembly.github.io/spec/core/exec/runtime.html#syntax-externval.
 *
 * @see ExportFunction
 */
interface ImportValue {
    enum class Type {
        FUNCTION,
        GLOBAL,
        MEMORY,
        TABLE,
        TAG,
    }

    fun module(): String

    fun name(): String

    fun type(): Type
}
