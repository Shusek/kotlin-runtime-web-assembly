package uk.shusek.krwa.wasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WasmParserMultiplatformTest {
    @Test
    fun parsesMinimalModuleBytes() {
        val module = WasmParser.parse(EMPTY_WASM)

        assertEquals(0, module.typeSection().typeCount())
        assertEquals(0, module.functionSection().functionCount())
    }

    @Test
    fun writesMinimalModuleBytes() {
        assertContentEquals(EMPTY_WASM, WasmWriter().bytes())
    }

    private companion object {
        val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
