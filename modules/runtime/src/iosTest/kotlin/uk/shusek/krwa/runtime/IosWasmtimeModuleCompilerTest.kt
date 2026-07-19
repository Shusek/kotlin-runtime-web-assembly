package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmParser

class IosWasmtimeModuleCompilerTest {
    @Test
    fun compilesModuleToPulleyCwasmAndRunsIt() {
        assertNull(iosWasmtimeModuleCompilerUnavailableReason())
        assertNotNull(iosWasmtimeModuleCompilerIdentity())

        installWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(ADD_WASM)
        val compiled = iosWasmtimeCompileModuleToCwasm(ADD_WASM)

        assertNotEquals(0, compiled.size)

        Instance.builder(module)
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .withWasmtimeExecutionConfig(
                WasmtimeExecutionConfig(
                    target = WasmtimePulleyTarget,
                    precompiledModuleBytes = compiled,
                ),
            )
            .build()
            .use { instance ->
                assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
                assertEquals(42, assertNotNull(instance.export("add")).apply(19, 23)[0].toInt())
            }
    }

    @Test
    fun mapsPulleyStartTrapToUninstantiableException() {
        installWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(START_TRAP_WASM)

        val exception = assertFailsWith<UninstantiableException> {
            Instance.builder(module)
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .withWasmtimeExecutionConfig(
                    WasmtimeExecutionConfig(target = WasmtimePulleyTarget),
                )
                .build()
        }
        assertTrue(exception.message.orEmpty().contains("unreachable"))
    }

    @Test
    fun resolvesMultibyteUtf8PulleyFunctionAndMemoryExportNames() {
        installWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(MULTIBYTE_EXPORTS_WASM)

        Instance.builder(module)
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .withWasmtimeExecutionConfig(
                WasmtimeExecutionConfig(target = WasmtimePulleyTarget),
            )
            .build()
            .use { instance ->
                assertEquals(42L, instance.export(MULTIBYTE_FUNCTION_EXPORT).apply()[0])
                val memory = instance.exports().memory(MULTIBYTE_MEMORY_EXPORT)
                memory.writeByte(0, 42)
                assertEquals(42, memory.read(0).toInt())
            }
    }

    private companion object {
        const val MULTIBYTE_FUNCTION_EXPORT = "ꠀ"
        const val MULTIBYTE_MEMORY_EXPORT = "記憶"
        val ADD_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x07, 0x01, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F,
                0x03, 0x02, 0x01, 0x00,
                0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
                0x0A, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B,
            )

        val START_TRAP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x08, 0x01, 0x00,
                0x0A, 0x05, 0x01, 0x03, 0x00, 0x00, 0x0B,
            )

        val MULTIBYTE_EXPORTS_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x02, 0x01, 0x00,
                0x05, 0x03, 0x01, 0x00, 0x01,
                0x07, 0x10, 0x02,
                0x03, 0xEA.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x00, 0x00,
                0x06,
                0xE8.toByte(), 0xA8.toByte(), 0x98.toByte(),
                0xE6.toByte(), 0x86.toByte(), 0xB6.toByte(),
                0x02, 0x00,
                0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x2A, 0x0B,
            )
    }
}
