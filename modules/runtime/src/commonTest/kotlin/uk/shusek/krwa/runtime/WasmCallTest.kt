package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class WasmCallTest {
    @Test
    fun wasmCallPreservesArgumentOrderWhenBuildingCalleeFrame() {
        val module = WasmParser.parse(CALL_ARGUMENT_ORDER_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply(3, 4, 5)

        assertEquals(345, result[0].toInt())
    }

    @Test
    fun wasmCallCanInvokeImportedHostFunction() {
        val module = WasmParser.parse(CALL_IMPORTED_HOST_FUNCTION_WASM)
        val host =
            HostFunction(
                "env",
                "inc",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                WasmFunctionHandle { _, args -> longArrayOf(args[0] + 1L) },
            )
        val instance =
            Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(host).build())
                .build()

        val result = instance.export("run").apply(41)

        assertEquals(42, result[0].toInt())
    }

    @Test
    fun repeatedWasmCallResetsCalleeLocals() {
        val module = WasmParser.parse(REPEATED_CALL_LOCAL_RESET_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply()

        assertEquals(0, result[0].toInt())
    }

    @Test
    fun localSetAndTeePreserveStackAndLocalSlotSemantics() {
        val module = WasmParser.parse(LOCAL_SLOT_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply(21)

        assertEquals(42, result[0].toInt())
    }

    @Test
    fun nopInstructionsAreIgnoredDuringExecution() {
        val module = WasmParser.parse(NOP_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply()

        assertEquals(42, result[0].toInt())
    }

    @Test
    fun localGetSetCopyPreservesValue() {
        val module = WasmParser.parse(LOCAL_COPY_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply(37)

        assertEquals(37, result[0].toInt())
    }

    @Test
    fun ifElseUsesPredecodedLabels() {
        val module = WasmParser.parse(IF_ELSE_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(11, instance.export("run").apply(1)[0].toInt())
        assertEquals(22, instance.export("run").apply(0)[0].toInt())
    }

    @Test
    fun brIfUsesPredecodedBranchDepthAndLabels() {
        val module = WasmParser.parse(BR_IF_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(7, instance.export("run").apply(1)[0].toInt())
        assertEquals(3, instance.export("run").apply(0)[0].toInt())
    }

    @Test
    fun countdownLoopProducesTriangularNumber() {
        val module = WasmParser.parse(COUNTDOWN_LOOP_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply(5)

        assertEquals(15, result[0].toInt())
    }

    @Test
    fun nonLoweredCountdownLoopProducesTriangularNumber() {
        val module = WasmParser.parse(NON_LOWERED_COUNTDOWN_LOOP_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply(5)

        assertEquals(15, result[0].toInt())
    }

    @Test
    fun refIsNullIfProducesBranchResult() {
        val module = WasmParser.parse(REF_IS_NULL_IF_WASM)
        val instance = Instance.builder(module).build()

        val result = instance.export("run").apply()

        assertEquals(1, result[0].toInt())
    }

    @Test
    fun fastSmallFunctionCallsPreserveResults() {
        val module = WasmParser.parse(FAST_SMALL_CALLS_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(42L, instance.export("run").apply(42)[0])
        assertEquals(-1L, instance.export("run").apply(-1)[0])
    }

    private companion object {
        val FAST_SMALL_CALLS_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x10,
                0x03,
                0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x60, 0x01, 0x7F, 0x01, 0x7E,
                0x60, 0x01, 0x7F, 0x01, 0x7E,
                0x03, 0x04,
                0x03, 0x00, 0x01, 0x02,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x02,
                0x0A, 0x17,
                0x03,
                0x05, 0x00,
                0x20, 0x00,
                0x0F,
                0x0B,
                0x06, 0x00,
                0x20, 0x00,
                0xAC.toByte(),
                0x0F,
                0x0B,
                0x08, 0x00,
                0x20, 0x00,
                0x10, 0x00,
                0x10, 0x01,
                0x0B,
            )

        val CALL_ARGUMENT_ORDER_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x08,
                0x01, 0x60, 0x03, 0x7F, 0x7F, 0x7F, 0x01, 0x7F,
                0x03, 0x03,
                0x02, 0x00, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
                0x0A, 0x1E,
                0x02,
                0x11, 0x00,
                0x20, 0x00,
                0x41, 0xE4.toByte(), 0x00,
                0x6C,
                0x20, 0x01,
                0x41, 0x0A,
                0x6C,
                0x6A,
                0x20, 0x02,
                0x6A,
                0x0B,
                0x0A, 0x00,
                0x20, 0x00,
                0x20, 0x01,
                0x20, 0x02,
                0x10, 0x00,
                0x0B,
            )

        val CALL_IMPORTED_HOST_FUNCTION_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x02, 0x0B,
                0x01, 0x03, 0x65, 0x6E, 0x76, 0x03, 0x69, 0x6E, 0x63, 0x00, 0x00,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
                0x0A, 0x08,
                0x01,
                0x06, 0x00,
                0x20, 0x00,
                0x10, 0x00,
                0x0B,
            )

        val REPEATED_CALL_LOCAL_RESET_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0A,
                0x02,
                0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x60, 0x00, 0x01, 0x7F,
                0x03, 0x03,
                0x02, 0x00, 0x01,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
                0x0A, 0x21,
                0x02,
                0x13, 0x01, 0x01, 0x7F,
                0x20, 0x00,
                0x04, 0x7F,
                0x41, 0xE3.toByte(), 0x00,
                0x21, 0x01,
                0x20, 0x01,
                0x05,
                0x20, 0x01,
                0x0B,
                0x0B,
                0x0B, 0x00,
                0x41, 0x01,
                0x10, 0x00,
                0x1A,
                0x41, 0x00,
                0x10, 0x00,
                0x0B,
            )

        val LOCAL_SLOT_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x11,
                0x01,
                0x0F, 0x01, 0x01, 0x7F,
                0x20, 0x00,
                0x21, 0x01,
                0x20, 0x01,
                0x22, 0x01,
                0x20, 0x01,
                0x6A,
                0x0B,
            )

        val NOP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05,
                0x01, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x08,
                0x01,
                0x06, 0x00,
                0x01,
                0x01,
                0x41, 0x2A,
                0x0B,
            )

        val LOCAL_COPY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x0C,
                0x01,
                0x0A, 0x01, 0x01, 0x7F,
                0x20, 0x00,
                0x21, 0x01,
                0x20, 0x01,
                0x0B,
            )

        val IF_ELSE_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x0E,
                0x01,
                0x0C, 0x00,
                0x20, 0x00,
                0x04, 0x7F,
                0x41, 0x0B,
                0x05,
                0x41, 0x16,
                0x0B,
                0x0B,
            )

        val BR_IF_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x10,
                0x01,
                0x0E, 0x00,
                0x02, 0x7F,
                0x41, 0x07,
                0x20, 0x00,
                0x0D, 0x00,
                0x1A,
                0x41, 0x03,
                0x0B,
                0x0B,
            )

        val COUNTDOWN_LOOP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x1F,
                0x01,
                0x1D, 0x01, 0x01, 0x7F,
                0x41, 0x00,
                0x21, 0x01,
                0x03, 0x40,
                0x20, 0x01,
                0x20, 0x00,
                0x6A,
                0x21, 0x01,
                0x20, 0x00,
                0x41, 0x01,
                0x6B,
                0x22, 0x00,
                0x0D, 0x00,
                0x0B,
                0x20, 0x01,
                0x0B,
            )

        val NON_LOWERED_COUNTDOWN_LOOP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x22,
                0x01,
                0x20, 0x01, 0x01, 0x7F,
                0x41, 0x00,
                0x1A,
                0x41, 0x00,
                0x21, 0x01,
                0x03, 0x40,
                0x20, 0x01,
                0x20, 0x00,
                0x6A,
                0x21, 0x01,
                0x20, 0x00,
                0x41, 0x01,
                0x6B,
                0x22, 0x00,
                0x0D, 0x00,
                0x0B,
                0x20, 0x01,
                0x0B,
            )

        val REF_IS_NULL_IF_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05,
                0x01, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x0F,
                0x01,
                0x0D, 0x00,
                0xD0.toByte(), 0x6F,
                0xD1.toByte(),
                0x04, 0x7F,
                0x41, 0x01,
                0x05,
                0x41, 0x02,
                0x0B,
                0x0B,
            )
    }
}
