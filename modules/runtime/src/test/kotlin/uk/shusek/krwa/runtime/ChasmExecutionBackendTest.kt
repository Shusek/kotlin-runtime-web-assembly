package uk.shusek.krwa.runtime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
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
    fun autoSelectsChasmWhenSupported() {
        val instance =
            Instance.builder(WasmParser.parse(ADD_WASM))
                .withExecutionBackend(ExecutionBackend.AUTO)
                .build()

        assertEquals(ExecutionBackend.CHASM, instance.executionBackend())
        assertEquals(11, instance.export("add").apply(5, 6)[0].toInt())
    }

    @Test
    fun platformBackendDoesNotExposeInterpreterMachineCalls() {
        val instance =
            Instance.builder(WasmParser.parse(ADD_WASM))
                .withExecutionBackend(ExecutionBackend.CHASM)
                .build()

        assertThrows(WasmEngineException::class.java) {
            instance.getMachine().call(0, longArrayOf(5, 6))
        }
    }

    @Test
    fun autoFallsBackToInterpreterWhenChasmCannotSupportImports() {
        val memory = ByteArrayMemory(MemoryLimits(1))
        val hostImports =
            ImportValues.builder()
                .addMemory(ImportMemory("env", "memory", memory))
                .build()
        val instance =
            Instance.builder(WasmParser.parse(IMPORTED_MEMORY_WASM))
                .withImportValues(hostImports)
                .withExecutionBackend(ExecutionBackend.AUTO)
                .build()

        memory.writeI32(0, 0x11223344)

        assertEquals(ExecutionBackend.INTERPRETER, instance.executionBackend())
        assertEquals(0x11223344, instance.export("read").apply(0)[0].toInt())
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

    @Test
    fun bridgesNumericHostImportAndExportValues() {
        val hostImports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "id_i64",
                        FunctionType.of(listOf(ValType.I64), listOf(ValType.I64)),
                    ) { _, args ->
                        longArrayOf(args[0] + 1)
                    }
                )
                .addFunction(
                    HostFunction(
                        "host",
                        "id_f32",
                        FunctionType.of(listOf(ValType.F32), listOf(ValType.F32)),
                    ) { _, args ->
                        val value = Float.fromBits(args[0].toInt())
                        longArrayOf((value * 2.0f).toRawBits().toLong())
                    }
                )
                .addFunction(
                    HostFunction(
                        "host",
                        "id_f64",
                        FunctionType.of(listOf(ValType.F64), listOf(ValType.F64)),
                    ) { _, args ->
                        val value = Double.fromBits(args[0])
                        longArrayOf((value * 2.0).toRawBits())
                    }
                )
                .build()
        val instance =
            Instance.builder(WasmParser.parse(NUMERIC_VALUES_WASM))
                .withImportValues(hostImports)
                .withExecutionBackend(ExecutionBackend.CHASM)
                .build()

        val i64 = 0x1_0000_0000L + 9
        val f32 = 1.25f
        val f64 = 3.5

        assertEquals(ExecutionBackend.CHASM, instance.executionBackend())
        assertEquals(i64 + 1, instance.export("callI64").apply(i64)[0])
        assertEquals(
            f32 * 2.0f,
            Float.fromBits(instance.export("callF32").apply(f32.toRawBits().toLong())[0].toInt()),
        )
        assertEquals(
            f64 * 2.0,
            Double.fromBits(instance.export("callF64").apply(f64.toRawBits())[0]),
        )
        assertEquals(1.25f, Float.fromBits(instance.export("constF32").apply()[0].toInt()))
        assertEquals(3.5, Double.fromBits(instance.export("constF64").apply()[0]))
    }

    @Test
    fun exposesExportedMemoryView() {
        val instance =
            Instance.builder(WasmParser.parse(MEMORY_EXPORT_WASM))
                .withExecutionBackend(ExecutionBackend.CHASM)
                .build()

        val memory = instance.exports().memory("memory")

        assertEquals(ExecutionBackend.CHASM, instance.executionBackend())
        assertEquals(1, memory.initialPages())
        assertEquals(1, memory.pages())
        assertEquals(2, memory.maximumPages())
        assertFalse(memory.shared())

        memory.writeI32(0, 0x11223344)
        assertEquals(0x11223344, instance.export("read").apply(0)[0].toInt())

        instance.export("write").apply(8, 0x22334455)
        assertEquals(0x22334455, memory.readInt(8))

        memory.writeShort(4, 0x5566.toShort())
        assertEquals(0x5566L, memory.readU16(4))

        memory.write(6, byteArrayOf(1, 2, 3))
        val bytes = ByteArray(3)
        memory.read(6, bytes, 0, bytes.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)

        assertEquals(1, memory.grow(1))
        assertEquals(2, memory.pages())
        assertEquals(-1, memory.grow(1))
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

        val IMPORTED_MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x02, 0x0F, 0x01,
                0x03, 0x65, 0x6E, 0x76,
                0x06, 0x6D, 0x65, 0x6D, 0x6F, 0x72, 0x79,
                0x02, 0x00, 0x01,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x08, 0x01,
                0x04, 0x72, 0x65, 0x61, 0x64,
                0x00, 0x00,
                0x0A, 0x09, 0x01, 0x07, 0x00,
                0x20, 0x00,
                0x28, 0x02, 0x00,
                0x0B,
            )

        val NUMERIC_VALUES_WASM =
            b(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x18,
                0x05,
                0x60, 0x01, 0x7E, 0x01, 0x7E,
                0x60, 0x01, 0x7D, 0x01, 0x7D,
                0x60, 0x01, 0x7C, 0x01, 0x7C,
                0x60, 0x00, 0x01, 0x7D,
                0x60, 0x00, 0x01, 0x7C,
                0x02, 0x2B,
                0x03,
                0x04, 0x68, 0x6F, 0x73, 0x74,
                0x06, 0x69, 0x64, 0x5F, 0x69, 0x36, 0x34,
                0x00, 0x00,
                0x04, 0x68, 0x6F, 0x73, 0x74,
                0x06, 0x69, 0x64, 0x5F, 0x66, 0x33, 0x32,
                0x00, 0x01,
                0x04, 0x68, 0x6F, 0x73, 0x74,
                0x06, 0x69, 0x64, 0x5F, 0x66, 0x36, 0x34,
                0x00, 0x02,
                0x03, 0x06,
                0x05, 0x00, 0x01, 0x02, 0x03, 0x04,
                0x07, 0x35,
                0x05,
                0x07, 0x63, 0x61, 0x6C, 0x6C, 0x49, 0x36, 0x34,
                0x00, 0x03,
                0x07, 0x63, 0x61, 0x6C, 0x6C, 0x46, 0x33, 0x32,
                0x00, 0x04,
                0x07, 0x63, 0x61, 0x6C, 0x6C, 0x46, 0x36, 0x34,
                0x00, 0x05,
                0x08, 0x63, 0x6F, 0x6E, 0x73, 0x74, 0x46, 0x33, 0x32,
                0x00, 0x06,
                0x08, 0x63, 0x6F, 0x6E, 0x73, 0x74, 0x46, 0x36, 0x34,
                0x00, 0x07,
                0x0A, 0x2A,
                0x05,
                0x06, 0x00, 0x20, 0x00, 0x10, 0x00, 0x0B,
                0x06, 0x00, 0x20, 0x00, 0x10, 0x01, 0x0B,
                0x06, 0x00, 0x20, 0x00, 0x10, 0x02, 0x0B,
                0x07, 0x00, 0x43, 0x00, 0x00, 0xA0, 0x3F, 0x0B,
                0x0B, 0x00, 0x44, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x0C, 0x40, 0x0B,
            )

        val MEMORY_EXPORT_WASM =
            b(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0B,
                0x02,
                0x60, 0x02, 0x7F, 0x7F, 0x00,
                0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x03,
                0x02, 0x00, 0x01,
                0x05, 0x04,
                0x01,
                0x01, 0x01, 0x02,
                0x07, 0x19,
                0x03,
                0x06, 0x6D, 0x65, 0x6D, 0x6F, 0x72, 0x79,
                0x02, 0x00,
                0x05, 0x77, 0x72, 0x69, 0x74, 0x65,
                0x00, 0x00,
                0x04, 0x72, 0x65, 0x61, 0x64,
                0x00, 0x01,
                0x0A, 0x13,
                0x02,
                0x09, 0x00,
                0x20, 0x00,
                0x20, 0x01,
                0x36, 0x02, 0x00,
                0x0B,
                0x07, 0x00,
                0x20, 0x00,
                0x28, 0x02, 0x00,
                0x0B,
            )

        private fun b(vararg bytes: Int): ByteArray =
            ByteArray(bytes.size) { index -> bytes[index].toByte() }
    }
}
