package uk.shusek.krwa.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class WasmPluginPreview3LifecycleTest {
    @Test
    fun closesOwnedPreview3HostExactlyWithPluginLifecycle() {
        val host = WasiPreview3.builder().build()
        val plugin =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview3(host, WasiPreview3HostOwnership.OWNED)
                .build()

        host.pendingFuture<Any?>()
        plugin.close()
        assertTrue(plugin.isClosed())
        assertPreview3Closed(host)

        plugin.close()
        assertPreview3Closed(host)
    }

    @Test
    fun leavesBorrowedPreview3HostOpenWhenPluginCloses() {
        val host = WasiPreview3.builder().build()
        val plugin =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview3(host, WasiPreview3HostOwnership.BORROWED)
                .build()

        plugin.close()
        val future = host.pendingFuture<Any?>()
        assertEquals(null, host.futureValue(future))
        host.close()
    }

    @Test
    fun closesOwnedHostWhenBuildFailsBeforeInstanceCreation() {
        val host = WasiPreview3.builder().build()
        val failure =
            assertThrows(ComponentModelException::class.java) {
                WasmPlugin.builder(emptyWorld())
                    .withWasiPreview3(host, WasiPreview3HostOwnership.OWNED)
                    .build()
            }

        assertTrue(failure.message.orEmpty().contains("plugin module is required"))
        assertPreview3Closed(host)
    }

    @Test
    fun closesOwnedHostWhenBuildFailsAfterInstanceCreation() {
        val host = WasiPreview3.builder().build()

        assertThrows(ComponentModelException::class.java) {
            WasmPlugin.builder(worldWithRequiredExport())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview3(host, WasiPreview3HostOwnership.OWNED)
                .build()
        }

        assertPreview3Closed(host)
    }

    @Test
    fun leavesBorrowedHostOpenWhenBuildFails() {
        val host = WasiPreview3.builder().build()

        assertThrows(ComponentModelException::class.java) {
            WasmPlugin.builder(emptyWorld())
                .withWasiPreview3(host, WasiPreview3HostOwnership.BORROWED)
                .build()
        }

        val future = host.pendingFuture<Any?>()
        assertEquals(null, host.futureValue(future))
        host.close()
    }

    @Test
    fun closesUnconsumedOwnedHostWhenItIsReplaced() {
        val first = WasiPreview3.builder().build()
        val second = WasiPreview3.builder().build()
        val builder =
            WasmPlugin.builder(emptyWorld())
                .withWasiPreview3(first, WasiPreview3HostOwnership.OWNED)

        builder.withWasiPreview3(second, WasiPreview3HostOwnership.BORROWED)

        assertPreview3Closed(first)
        val future = second.pendingFuture<Any?>()
        assertEquals(null, second.futureValue(future))
        second.close()
    }

    @Test
    fun doesNotTransferTheSameOwnedHostTwice() {
        val host = WasiPreview3.builder().build()
        val builder =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview3(host, WasiPreview3HostOwnership.OWNED)
        val plugin = builder.build()
        try {
            val failure = assertThrows(IllegalStateException::class.java) { builder.build() }
            assertTrue(failure.message.orEmpty().contains("already been transferred"))
            host.pendingFuture<Any?>()
        } finally {
            plugin.close()
        }
    }

    private fun assertPreview3Closed(host: WasiPreview3) {
        assertThrows(ComponentModelException::class.java) {
            host.pendingFuture<Any?>()
        }
    }

    private fun emptyWorld(): WitPackage =
        WitPackage.parse(
            """
            package example:preview3-lifecycle;

            world plugin {}
            """
                .trimIndent()
        )

    private fun worldWithRequiredExport(): WitPackage =
        WitPackage.parse(
            """
            package example:preview3-lifecycle-error;

            interface api {
              run: func();
            }

            world plugin {
              export api;
            }
            """
                .trimIndent()
        )
}
