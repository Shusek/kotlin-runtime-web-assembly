package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        configureWasmtimeExecution(
            module = module,
            config = WasmtimeExecutionConfig(
                target = WasmtimePulleyTarget,
                precompiledModuleBytes = compiled,
            ),
        )
        try {
            val instance =
                Instance.builder(module)
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()

            assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
            assertEquals(42, assertNotNull(instance.export("add")).apply(19, 23)[0].toInt())
        } finally {
            clearWasmtimeExecution(module)
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
    }
}
