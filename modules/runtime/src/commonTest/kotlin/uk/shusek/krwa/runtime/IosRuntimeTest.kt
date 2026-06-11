package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class IosRuntimeTest {
    @Test
    fun shouldParseAndInstantiateMinimalModule() {
        val module = WasmParser.parse(EMPTY_WASM)
        val instance =
            Instance.builder(module)
                .withInitialize(false)
                .withStart(false)
                .build()

        assertEquals(0, instance.functionCount())
        assertEquals(0, instance.globalCount())
        assertEquals(0, instance.tableCount())
    }

    @Test
    fun shouldRunBasicAddFunction() {
        val module = WasmParser.parse(ADD_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(11, instance.export("add").apply(5, 6)[0].toInt())
    }

    @Test
    fun shouldRunMemoryLoadAndStore() {
        val module = WasmParser.parse(MEMORY_WASM)
        val instance = Instance.builder(module).build()

        assertEquals(0x12345678, instance.export("storeLoad").apply(0x12345678)[0].toInt())
        assertEquals(0x12345678, instance.exports().memory("memory").readInt(16))
    }

    @Test
    fun shouldCallHostImport() {
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

        val instance = Instance.builder(module).withImportValues(hostImports).build()

        assertEquals(14, instance.export("callDouble").apply(7)[0].toInt())
    }

    @Test
    fun shouldReturnEmptyArrayForVoidExport() {
        val module = WasmParser.parse(VOID_EXPORT_WASM)
        val instance = Instance.builder(module).build()

        assertContentEquals(LongArray(0), instance.export("nop").apply())
    }

    @Test
    fun shouldSurfaceRuntimeTrap() {
        val module = WasmParser.parse(TRAP_WASM)
        val instance = Instance.builder(module).build()

        assertFailsWith<TrapException> {
            instance.export("boom").apply()
        }
    }

    private companion object {
        val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

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
