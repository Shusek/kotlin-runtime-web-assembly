package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import uk.shusek.krwa.wasm.WasmParser

class WasmFunctionHandleTest {
    @Test
    fun shouldReturnLongArrayFromSamHandle() {
        val instance =
            Instance.builder(WasmParser.parse(EMPTY_WASM))
                .build()
        val handle = WasmFunctionHandle { _, args -> longArrayOf(args[0] * 2) }

        assertContentEquals(longArrayOf(14), handle.apply(instance, longArrayOf(7)))
    }

    private companion object {
        val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
