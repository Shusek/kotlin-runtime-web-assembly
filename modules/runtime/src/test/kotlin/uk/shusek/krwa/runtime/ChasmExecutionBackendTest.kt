package uk.shusek.krwa.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class ChasmExecutionBackendTest {
    @Test
    fun runsBasicAdd() {
        val instance =
            Instance.builder(WasmParser.parse(ADD_WASM))
                .withExecutionBackend(ExecutionBackend.CHASM)
                .build()

        assertEquals(ExecutionBackend.CHASM, instance.executionBackend())
        assertEquals(11, instance.export("add").apply(5, 6)[0].toInt())
    }

    @Test
    fun callsHostFunctionImport() {
        val hostImports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "double",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                    ) { _, args ->
                        longArrayOf(args[0] * 2)
                    }
                )
                .build()
        val instance =
            Instance.builder(WasmParser.parse(HOST_IMPORT_WASM))
                .withImportValues(hostImports)
                .withExecutionBackend(ExecutionBackend.CHASM)
                .build()

        assertEquals(ExecutionBackend.CHASM, instance.executionBackend())
        assertEquals(14, instance.export("callDouble").apply(7)[0].toInt())
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
    }
}
