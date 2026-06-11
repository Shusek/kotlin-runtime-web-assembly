package uk.shusek.krwa.wasm.types

import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionTypeTest {
    @Test
    fun toStringContract() {
        val emptyToNil = FunctionType.empty()
        assertEquals("() -> nil", emptyToNil.toString())

        val i32I64ToF32 = FunctionType.of(listOf(ValType.I32, ValType.I64), listOf(ValType.F32))
        assertEquals("(I32,I64) -> (F32)", i32I64ToF32.toString())

        val v128ToI32I32 = FunctionType.of(listOf(ValType.V128), listOf(ValType.I32, ValType.I32))
        assertEquals("(V128) -> (I32,I32)", v128ToI32I32.toString())
    }
}
