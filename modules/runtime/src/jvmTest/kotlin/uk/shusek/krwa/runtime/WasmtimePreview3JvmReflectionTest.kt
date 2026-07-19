package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import uk.shusek.krwa.wasm.WasmEngineException

class WasmtimePreview3JvmReflectionTest {
    @Test
    fun preview3JvmReflectionAcceptsResourceLimitSignatures() {
        val config = unsupportedTargetConfig()

        assertUnsupportedTarget(wasmtimePreview3ComponentUnavailableReason(config))
        assertUnsupportedTarget(wasmtimePreview3ComponentCall0UnavailableReason(config, "run"))
        assertUnsupportedTarget(wasmtimePreview3ComponentCallS32UnavailableReason(config, "add-one", 1, 2))
        assertUnsupportedTarget(wasmtimePreview3ComponentCallStringUnavailableReason(config, "echo", "in", "out"))
        assertUnsupportedTarget(wasmtimePreview3CommandRunUnavailableReason(config))

        val error = assertFailsWith<WasmEngineException> {
            wasmtimePreview3ComponentCallString(config, "echo", "in")
        }
        assertUnsupportedTarget(error.message)
    }

    private fun unsupportedTargetConfig(): WasmtimePreview3ComponentConfig =
        WasmtimePreview3ComponentConfig(
            target = "unsupported-target",
            precompiledComponentBytes = byteArrayOf(1),
            preopens = listOf(
                WasmtimePreview3Preopen(
                    hostRoot = "/tmp/suvio-plugin-cache/reflection",
                    guestRoot = "/suvio/cache",
                ),
            ),
            maxMemoryBytes = 8L * 1024L * 1024L,
            maxWasmStackBytes = 128L * 1024L,
            maxTableElements = 16L,
            maxInstances = 1L,
            maxTables = 4L,
            maxMemories = 2L,
        )

    private fun assertUnsupportedTarget(message: String?) {
        assertContains(message.orEmpty(), "supports only $WasmtimePulleyTarget")
    }
}
