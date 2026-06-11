package uk.shusek.krwa.runtime

/**
 * Boxed representation of an i31ref value for storage in int-typed containers (tables, globals). On
 * the stack, i31 values use an efficient tagged-long encoding (see
 * [uk.shusek.krwa.wasm.types.Value.encodeI31]). This class is only used when i31 values need to pass
 * through int-typed storage where the tag would be lost.
 */
class WasmI31Ref(value: Int) : WasmGcRef {
    private val valueValue: Int = value and 0x7FFFFFFF

    override fun typeIdx(): Int = I31_HEAP_TYPE

    fun value(): Int = valueValue

    private companion object {
        const val I31_HEAP_TYPE: Int = -20 // ValType.TypeIdxCode.I31.code()
    }
}
