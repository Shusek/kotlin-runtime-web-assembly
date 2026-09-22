package uk.shusek.krwa.runtime.wasmtime.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmtimeExecutionConfig
import uk.shusek.krwa.runtime.WasmtimeNativeTarget
import uk.shusek.krwa.runtime.WasmtimePulleyTarget
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmParser

@RunWith(AndroidJUnit4::class)
class AndroidWasmtimeModuleCompilerDeviceTest {
    @Test
    fun cachedMeteredModulesRunAndStillExhaustFuel() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        for (target in listOf(WasmtimeNativeTarget, WasmtimePulleyTarget)) {
            val compiled = androidWasmtimeCompileModuleToCwasm(ADD_WASM, target, consumeFuel = true)
            Instance.builder(WasmParser.parse(ADD_WASM))
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .withWasmtimeExecutionConfig(WasmtimeExecutionConfig(target, compiled, maxFuel = 16))
                .build().use { instance ->
                    assertEquals(42L, instance.export("add").apply(19, 23)[0])
                    val failure = assertFails { repeat(100) { instance.export("add").apply(19, 23) } }
                    assertTrue(failure.message.orEmpty().contains("fuel", ignoreCase = true))
                    instance.replenishExecutionFuel()
                    assertEquals(42L, instance.export("add").apply(19, 23)[0])
                }
        }
    }

    @Test
    fun cacheIdentitySeparatesMeteringAndUnmeteredArtifactsAreRejected() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        assertNotEquals(
            androidWasmtimeModuleCompilerIdentity(consumeFuel = false),
            androidWasmtimeModuleCompilerIdentity(consumeFuel = true),
        )
        val compiled = androidWasmtimeCompileModuleToCwasm(ADD_WASM)
        val failure = assertFailsWith<WasmEngineException> {
            Instance.builder(WasmParser.parse(ADD_WASM))
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .withWasmtimeExecutionConfig(WasmtimeExecutionConfig(target = WasmtimeNativeTarget, precompiledModuleBytes = compiled, maxFuel = 16))
                .build().close()
        }
        assertTrue(failure.message.orEmpty().contains("fuel", ignoreCase = true))
    }

    private companion object {
        val ADD_WASM = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x07, 0x01, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F,
            0x03, 0x02, 0x01, 0x00,
            0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
            0x0A, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B,
        )
    }
}
