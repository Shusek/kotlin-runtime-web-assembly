package uk.shusek.krwa.runtime.wasmtime.android

import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmtimePreview3ComponentConfig
import uk.shusek.krwa.runtime.WasmtimePreview3Preopen
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmParser

@RunWith(AndroidJUnit4::class)
class AndroidWasmtimePreview3BridgeDeviceTest {
    @Test
    fun mapsPulleyStartTrapToUninstantiableException() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(START_TRAP_WASM)

        val exception = assertFailsWith<UninstantiableException> {
            Instance.builder(module)
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .build()
        }
        assertTrue(exception.message.orEmpty().contains("unreachable"))
    }

    @Test
    fun resolvesMultibyteUtf8PulleyFunctionAndMemoryExportNames() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(MULTIBYTE_EXPORTS_WASM)

        Instance.builder(module)
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .build()
            .use { instance ->
                assertEquals(42L, instance.export(MULTIBYTE_FUNCTION_EXPORT).apply()[0])
                val memory = instance.exports().memory(MULTIBYTE_MEMORY_EXPORT)
                memory.writeByte(0, 42)
                assertEquals(42, memory.read(0).toInt())
            }
    }

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

    @Test
    fun preview3CommandRunStringLoadsAndReachesWasmtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preopenRoot = File(context.cacheDir, "krwa-preview3-command-run-${System.nanoTime()}")
        preopenRoot.mkdirs()

        val error = assertFailsWith<WasmEngineException> {
            androidWasmtimePreview3CommandRunString(
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
                stdin = "{}",
            )
        }
        val message = error.message.orEmpty()
        assertFalse(message.contains("not linked", ignoreCase = true), message)
        assertFalse(message.contains("failed to load", ignoreCase = true), message)
        assertTrue(
            message.contains("deserialize", ignoreCase = true) ||
                message.contains("component", ignoreCase = true) ||
                message.contains("wasm", ignoreCase = true),
            message,
        )
    }

    @Test
    fun preview3CommandRunStringRunsWasip2CommandComponent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preopenRoot = File(context.cacheDir, "krwa-wasip2-command-run-${System.nanoTime()}")
        preopenRoot.mkdirs()
        val componentBytes = minimalWasip2CommandComponentBytes()

        val stdout = androidWasmtimePreview3CommandRunString(
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = componentBytes,
                preopens = listOf(
                    WasmtimePreview3Preopen(
                        hostRoot = preopenRoot.absolutePath,
                        guestRoot = "/",
                        writable = true,
                    ),
                ),
            ),
            stdin = "",
        )

        assertTrue(stdout.isEmpty(), stdout)
    }
}

private val START_TRAP_WASM =
    byteArrayOf(
        0x00, 0x61, 0x73, 0x6D,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x08, 0x01, 0x00,
        0x0A, 0x05, 0x01, 0x03, 0x00, 0x00, 0x0B,
    )

private const val MULTIBYTE_FUNCTION_EXPORT = "ꠀ"
private const val MULTIBYTE_MEMORY_EXPORT = "記憶"
private val MULTIBYTE_EXPORTS_WASM =
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

private fun minimalWasip2CommandComponentBytes(): ByteArray {
    val encoded =
        if (Process.is64Bit()) {
            MinimalWasip2CommandPulley64GzipBase64
        } else {
            MinimalWasip2CommandPulley32GzipBase64
        }
    val compressedBytes = Base64.decode(encoded, Base64.DEFAULT)
    return GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { input -> input.readBytes() }
}
