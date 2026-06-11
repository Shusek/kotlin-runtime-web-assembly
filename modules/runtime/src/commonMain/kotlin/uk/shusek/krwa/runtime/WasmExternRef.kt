package uk.shusek.krwa.runtime

/**
 * Wrapper for externref values converted to anyref via any.convert_extern. Prevents GC ref ID
 * collisions between extern values and native GC objects.
 */
class WasmExternRef(private val valueValue: Long) : WasmGcRef {
    override fun typeIdx(): Int = ANY_HEAP_TYPE

    fun value(): Long = valueValue

    private companion object {
        const val ANY_HEAP_TYPE: Int = -18 // ValType.TypeIdxCode.ANY.code()
    }
}
