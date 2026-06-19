package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class ExperimentalFastInterpreterMachineTest {
    @Test
    fun doesNotReplaceDefaultInterpreter() {
        val module = WasmParser.parse(ADD_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(false, instance.getMachine() is ExperimentalFastInterpreterMachine)
    }

    @Test
    fun runsBasicAddWithStandaloneMachine() {
        val module = WasmParser.parse(ADD_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(true, instance.getMachine() is ExperimentalFastInterpreterMachine)
        assertEquals(11, instance.export("add").apply(5, 6)[0].toInt())
    }

    @Test
    fun runsMemoryLoadAndStoreWithStandaloneMachine() {
        val module = WasmParser.parse(MEMORY_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(0x12345678, instance.export("storeLoad").apply(0x12345678)[0].toInt())
        assertEquals(0x12345678, instance.exports().memory("memory").readInt(16))
    }

    @Test
    fun callsHostImportWithStandaloneMachine() {
        val module = WasmParser.parse(HOST_IMPORT_WASM)
        val hostImports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "double",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args -> longArrayOf(args[0] * 2) },
                    )
                )
                .build()
        val instance =
            Instance.builder(module)
                .withImportValues(hostImports)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(14, instance.export("callDouble").apply(7)[0].toInt())
    }

    @Test
    fun resumesHostSuspensionWithStandaloneMachine() {
        val module = WasmParser.parse(HOST_SUSPEND_WASM)
        var calls = 0
        val hostImports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "pause",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args ->
                            calls += 1
                            throw WasmExecutionSuspended(longArrayOf(args[0] + 10))
                        },
                    )
                )
                .build()
        val instance =
            Instance.builder(module)
                .withImportValues(hostImports)
                .withExperimentalFastInterpreter()
                .build()

        val suspended =
            assertFailsWith<WasmExecutionSuspended> {
                instance.export("run").apply(5)
            }
        val machine = instance.getMachine()
        val results =
            (machine as ResumableMachine).resume(
                suspended.continuation ?: error("missing continuation")
            )

        assertEquals(16, results[0].toInt())
        assertEquals(1, calls)
    }

    @Test
    fun keepsStandaloneContinuationsSeparate() {
        val module = WasmParser.parse(HOST_SUSPEND_WASM)
        val hostImports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "pause",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args ->
                            throw WasmExecutionSuspended(longArrayOf(args[0] + 10))
                        },
                    )
                )
                .build()
        val instance =
            Instance.builder(module)
                .withImportValues(hostImports)
                .withExperimentalFastInterpreter()
                .build()
        val machine = instance.getMachine() as ResumableMachine

        val first =
            assertFailsWith<WasmExecutionSuspended> {
                instance.export("run").apply(1)
            }.continuation ?: error("missing first continuation")
        val second =
            assertFailsWith<WasmExecutionSuspended> {
                instance.export("run").apply(20)
            }.continuation ?: error("missing second continuation")

        assertEquals(31, machine.resume(second)[0].toInt())
        assertEquals(12, machine.resume(first)[0].toInt())
    }

    @Test
    fun runsIfElseWithStandaloneMachine() {
        val module = WasmParser.parse(IF_ELSE_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(11, instance.export("run").apply(1)[0].toInt())
        assertEquals(22, instance.export("run").apply(0)[0].toInt())
    }

    @Test
    fun runsBrIfWithStandaloneMachine() {
        val module = WasmParser.parse(BR_IF_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(7, instance.export("run").apply(1)[0].toInt())
        assertEquals(3, instance.export("run").apply(0)[0].toInt())
    }

    @Test
    fun runsCountdownLoopWithStandaloneMachine() {
        val module = WasmParser.parse(COUNTDOWN_LOOP_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(15, instance.export("run").apply(5)[0].toInt())
    }

    @Test
    fun runsBrTableWithStandaloneMachine() {
        val module = WasmParser.parse(BR_TABLE_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(102, instance.export("switch_like").apply(0)[0].toInt())
        assertEquals(101, instance.export("switch_like").apply(1)[0].toInt())
        assertEquals(100, instance.export("switch_like").apply(2)[0].toInt())
        assertEquals(103, instance.export("switch_like").apply(3)[0].toInt())
        assertEquals(103, instance.export("switch_like").apply(-1)[0].toInt())
    }

    @Test
    fun runsGlobalSetAndGetWithStandaloneMachine() {
        val module = WasmParser.parse(GLOBAL_SET_GET_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertEquals(41, instance.export("setGet").apply(41)[0].toInt())
        assertEquals(7, instance.export("setGet").apply(7)[0].toInt())
    }

    @Test
    fun returnsEmptyArrayForVoidExportWithStandaloneMachine() {
        val module = WasmParser.parse(VOID_EXPORT_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertContentEquals(LongArray(0), instance.export("nop").apply())
    }

    @Test
    fun surfacesTrapWithStandaloneMachine() {
        val module = WasmParser.parse(TRAP_WASM)
        val instance =
            Instance.builder(module)
                .withExperimentalFastInterpreter()
                .build()

        assertFailsWith<TrapException> {
            instance.export("boom").apply()
        }
    }

    private companion object {
        val ADD_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x07, 0x01, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
                0x0A, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B,
            )

        val MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02, 0x01, 0x00,
                0x05, 0x03, 0x01, 0x00, 0x01,
                0x07, 0x16, 0x02,
                0x06, 0x6D, 0x65, 0x6D, 0x6F, 0x72, 0x79, 0x02, 0x00,
                0x09, 0x73, 0x74, 0x6F, 0x72, 0x65, 0x4C, 0x6F, 0x61, 0x64, 0x00, 0x00,
                0x0A, 0x10, 0x01, 0x0E, 0x00,
                0x41, 0x10,
                0x20, 0x00,
                0x36, 0x02, 0x00,
                0x41, 0x10,
                0x28, 0x02, 0x00,
                0x0B,
            )

        val HOST_IMPORT_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x02, 0x0F, 0x01,
                0x04, 0x68, 0x6F, 0x73, 0x74,
                0x06, 0x64, 0x6F, 0x75, 0x62, 0x6C, 0x65,
                0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x0E, 0x01,
                0x0A, 0x63, 0x61, 0x6C, 0x6C, 0x44, 0x6F, 0x75, 0x62, 0x6C, 0x65,
                0x00, 0x01,
                0x0A, 0x08, 0x01, 0x06, 0x00,
                0x20, 0x00,
                0x10, 0x00,
                0x0B,
            )

        val HOST_SUSPEND_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x02, 0x0E, 0x01,
                0x04, 0x68, 0x6F, 0x73, 0x74,
                0x05, 0x70, 0x61, 0x75, 0x73, 0x65,
                0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x07, 0x01,
                0x03, 0x72, 0x75, 0x6E,
                0x00, 0x01,
                0x0A, 0x0B, 0x01, 0x09, 0x00,
                0x20, 0x00,
                0x10, 0x00,
                0x41, 0x01,
                0x6A,
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

        val BR_TABLE_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60,
                0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x0F, 0x01, 0x0B,
                0x73, 0x77, 0x69, 0x74, 0x63, 0x68, 0x5F, 0x6C, 0x69, 0x6B, 0x65,
                0x00, 0x00,
                0x0A, 0x28, 0x01, 0x26, 0x00,
                0x02, 0x40,
                0x02, 0x40,
                0x02, 0x40,
                0x02, 0x40,
                0x20, 0x00,
                0x0E, 0x03, 0x02, 0x01, 0x00, 0x03,
                0x0B,
                0x41, 0xE4.toByte(), 0x00,
                0x0F,
                0x0B,
                0x41, 0xE5.toByte(), 0x00,
                0x0F,
                0x0B,
                0x41, 0xE6.toByte(), 0x00,
                0x0F,
                0x0B,
                0x41, 0xE7.toByte(), 0x00,
                0x0F,
                0x0B,
            )

        val GLOBAL_SET_GET_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x06, 0x06,
                0x01, 0x7F, 0x01, 0x41, 0x07, 0x0B,
                0x07, 0x0A,
                0x01, 0x06, 0x73, 0x65, 0x74, 0x47, 0x65, 0x74, 0x00, 0x00,
                0x0A, 0x0A,
                0x01, 0x08, 0x00,
                0x20, 0x00,
                0x24, 0x00,
                0x23, 0x00,
                0x0B,
            )

        val VOID_EXPORT_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x07, 0x01, 0x03, 0x6E, 0x6F, 0x70, 0x00, 0x00,
                0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
            )

        val TRAP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x08, 0x01, 0x04, 0x62, 0x6F, 0x6F, 0x6D, 0x00, 0x00,
                0x0A, 0x05, 0x01, 0x03, 0x00, 0x00, 0x0B,
            )
    }
}
