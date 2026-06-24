package uk.shusek.krwa.runtime.wasmtime.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import uk.shusek.krwa.runtime.WasmtimePreview3ComponentConfig
import uk.shusek.krwa.runtime.WasmtimePreview3Preopen

@RunWith(AndroidJUnit4::class)
class AndroidWasmtimePreview3BridgeDeviceTest {
    @Test
    fun preview3BridgeLoadsAndReachesWasmtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preopenRoot = File(context.cacheDir, "krwa-preview3-bridge-${System.nanoTime()}")
        preopenRoot.mkdirs()

        val reason = androidWasmtimePreview3ComponentUnavailableReason(
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = byteArrayOf(0, 1, 2, 3),
                preopens = listOf(
                    WasmtimePreview3Preopen(
                        hostRoot = preopenRoot.absolutePath,
                        guestRoot = "/",
                        writable = true,
                    ),
                ),
            ),
        )

        val message = assertNotNull(reason)
        assertFalse(message.contains("not linked", ignoreCase = true), message)
        assertFalse(message.contains("failed to load", ignoreCase = true), message)
        assertTrue(
            message.contains("deserialize", ignoreCase = true) ||
                message.contains("component", ignoreCase = true) ||
                message.contains("wasm", ignoreCase = true),
            message,
        )
    }
}
