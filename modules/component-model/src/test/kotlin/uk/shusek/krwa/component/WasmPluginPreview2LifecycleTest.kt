package uk.shusek.krwa.component

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class WasmPluginPreview2LifecycleTest {
    @Test
    fun closesOwnedPreview2HostExactlyWithPluginLifecycle() {
        val host = WasiPreview2.builder().build()
        val plugin =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview2(host, WasiPreview2HostOwnership.OWNED)
                .build()

        host.newResponseOutparam()
        plugin.close()
        assertTrue(plugin.isClosed())
        assertPreview2Closed(host)

        plugin.close()
        assertPreview2Closed(host)
    }

    @Test
    fun leavesBorrowedPreview2HostOpenWhenPluginCloses() {
        val host = WasiPreview2.builder().build()
        val plugin =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview2(host, WasiPreview2HostOwnership.BORROWED)
                .build()

        plugin.close()
        host.newResponseOutparam()
        host.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyOverloadRemainsBorrowed() {
        val host = WasiPreview2.builder().build()
        val plugin =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview2(host)
                .build()

        plugin.close()
        host.newResponseOutparam()
        host.close()
    }

    @Test
    fun closesOwnedHostWhenBuildFailsBeforeInstanceCreation() {
        val host = WasiPreview2.builder().build()
        val failure =
            assertThrows(ComponentModelException::class.java) {
                WasmPlugin.builder(emptyWorld())
                    .withWasiPreview2(host, WasiPreview2HostOwnership.OWNED)
                    .build()
            }

        assertTrue(failure.message.orEmpty().contains("plugin module is required"))
        assertPreview2Closed(host)
    }

    @Test
    fun closesOwnedHostWhenBuildFailsAfterInstanceCreation() {
        val host = WasiPreview2.builder().build()

        assertThrows(ComponentModelException::class.java) {
            WasmPlugin.builder(worldWithRequiredExport())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview2(host, WasiPreview2HostOwnership.OWNED)
                .build()
        }

        assertPreview2Closed(host)
    }

    @Test
    fun leavesBorrowedHostOpenWhenBuildFails() {
        val host = WasiPreview2.builder().build()

        assertThrows(ComponentModelException::class.java) {
            WasmPlugin.builder(emptyWorld())
                .withWasiPreview2(host, WasiPreview2HostOwnership.BORROWED)
                .build()
        }

        host.newResponseOutparam()
        host.close()
    }

    @Test
    fun closesUnconsumedOwnedHostWhenItIsReplaced() {
        val first = WasiPreview2.builder().build()
        val second = WasiPreview2.builder().build()
        val builder =
            WasmPlugin.builder(emptyWorld())
                .withWasiPreview2(first, WasiPreview2HostOwnership.OWNED)

        builder.withWasiPreview2(second, WasiPreview2HostOwnership.BORROWED)

        assertPreview2Closed(first)
        second.newResponseOutparam()
        second.close()
    }

    @Test
    fun doesNotTransferTheSameOwnedHostTwice() {
        val host = WasiPreview2.builder().build()
        val builder =
            WasmPlugin.builder(emptyWorld())
                .withModule(Wat2Wasm.parse("(module)"))
                .withWasiPreview2(host, WasiPreview2HostOwnership.OWNED)
        val plugin = builder.build()
        try {
            val failure = assertThrows(IllegalStateException::class.java) { builder.build() }
            assertTrue(failure.message.orEmpty().contains("already been transferred"))
            host.newResponseOutparam()
        } finally {
            plugin.close()
        }
    }

    private fun assertPreview2Closed(host: WasiPreview2) {
        assertThrows(ComponentModelException::class.java) {
            host.newResponseOutparam()
        }
    }

    private fun emptyWorld(): WitPackage =
        WitPackage.parse(
            """
            package example:preview2-lifecycle;

            world plugin {}
            """
                .trimIndent()
        )

    private fun worldWithRequiredExport(): WitPackage =
        WitPackage.parse(
            """
            package example:preview2-lifecycle-error;

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
