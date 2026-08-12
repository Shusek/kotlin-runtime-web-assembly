package uk.shusek.krwa.component.tooling

import java.util.ServiceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DefaultWasmToolsExecutionProviderTest {
    @Test
    fun shouldDiscoverAndRunEmbeddedProvider() {
        val providers = ServiceLoader.load(WasmToolsExecutionProvider::class.java).toList()
        assertEquals(1, providers.size)
        val provider =
            assertInstanceOf(DefaultWasmToolsExecutionProvider::class.java, providers.single())

        withEmbeddedWasmTools {
            val result = provider.execute(listOf("wasm-tools", "--version"), emptyMap())
            assertEquals(0, result.exitCode(), result.stderrText())
        }
    }

    private fun withEmbeddedWasmTools(block: () -> Unit) {
        val previousValue = System.getProperty(FORCE_EMBEDDED_PROPERTY)
        System.setProperty(FORCE_EMBEDDED_PROPERTY, "true")
        try {
            block()
        } finally {
            if (previousValue == null) {
                System.clearProperty(FORCE_EMBEDDED_PROPERTY)
            } else {
                System.setProperty(FORCE_EMBEDDED_PROPERTY, previousValue)
            }
        }
    }

    private companion object {
        const val FORCE_EMBEDDED_PROPERTY = "krwa.wasmTools.forceEmbedded"
    }
}
