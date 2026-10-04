package uk.shusek.krwa.runtime

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType

/**
 * Native lifecycle of the Wasmtime (Pulley) backend: a failed build releases what it created, host
 * callback failures always reach the caller, and `close()` never frees the store under a guest that
 * is still running on another thread.
 */
@Timeout(120)
class PulleyLifecycleTest {
    @Test
    fun failedInstantiationIsReleasedAndTheBackendStaysUsable() {
        assumePulley()

        repeat(3) {
            assertThrows(WasmEngineException::class.java) {
                Instance.builder(trappingStartModule())
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()
            }
        }

        Instance.builder(callingModule())
            .withImportValues(hostImports { LongArray(0) })
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .build()
            .use { instance -> instance.export("run").apply() }
    }

    @Test
    fun hostCallbackFailuresReachTheCaller() {
        assumePulley()

        Instance.builder(callingModule())
            .withImportValues(hostImports { throw IllegalStateException("host refused") })
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .build()
            .use { instance ->
                val failure =
                    assertThrows(IllegalStateException::class.java) { instance.export("run").apply() }
                assertEquals("host refused", failure.message)
            }
    }

    @Test
    fun closingWhileAnExportRunsDefersTheNativeRelease() {
        assumePulley()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val instance =
            Instance.builder(callingModule())
                .withImportValues(
                    hostImports {
                        entered.countDown()
                        assertTrue(release.await(60, TimeUnit.SECONDS), "host callback was never released")
                        LongArray(0)
                    }
                )
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .build()
        val run = instance.export("run")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val call = executor.submit(Callable { run.apply() })
            assertTrue(entered.await(60, TimeUnit.SECONDS), "guest never reached the host callback")

            // Closing from another thread while the guest is parked inside the host callback must
            // not free the Wasmtime store under the running call.
            instance.close()
            assertTrue(instance.isClosed())

            release.countDown()
            call.get(60, TimeUnit.SECONDS)

            assertThrows(IllegalStateException::class.java) { run.apply() }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assumePulley() {
        val availability = ExecutionBackend.PULLEY.availability()
        assumeTrue(availability.available, availability.reason ?: "Wasmtime Pulley execution is unavailable")
    }

    private fun hostImports(block: () -> LongArray): ImportValues =
        ImportValues.builder()
            .addFunction(
                HostFunction(
                    "env",
                    "block",
                    FunctionType.of(emptyList(), emptyList()),
                    WasmFunctionHandle { _, _ -> block() },
                )
            )
            .build()

    private companion object {
        private val WASM_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        private const val TYPE_SECTION = 1
        private const val IMPORT_SECTION = 2
        private const val FUNCTION_SECTION = 3
        private const val EXPORT_SECTION = 7
        private const val START_SECTION = 8
        private const val CODE_SECTION = 10

        /** `(module (func $start unreachable) (start $start))` */
        private fun trappingStartModule(): WasmModule =
            parse(
                section(TYPE_SECTION, bytes(0x01, 0x60, 0x00, 0x00)),
                section(FUNCTION_SECTION, bytes(0x01, 0x00)),
                section(START_SECTION, bytes(0x00)),
                section(CODE_SECTION, bytes(0x01, 0x03, 0x00, 0x00, 0x0B)),
            )

        /** `(module (import "env" "block" (func)) (func (export "run") (call 0)))` */
        private fun callingModule(): WasmModule =
            parse(
                section(TYPE_SECTION, bytes(0x01, 0x60, 0x00, 0x00)),
                section(
                    IMPORT_SECTION,
                    bytes(0x01) + name("env") + name("block") + bytes(0x00, 0x00),
                ),
                section(FUNCTION_SECTION, bytes(0x01, 0x00)),
                section(EXPORT_SECTION, bytes(0x01) + name("run") + bytes(0x00, 0x01)),
                section(CODE_SECTION, bytes(0x01, 0x04, 0x00, 0x10, 0x00, 0x0B)),
            )

        private fun parse(vararg sections: ByteArray): WasmModule =
            Parser.parse(WASM_HEADER + sections.fold(ByteArray(0)) { acc, next -> acc + next })

        private fun section(id: Int, body: ByteArray): ByteArray {
            require(body.size < 0x80) { "section bodies in this test stay below one LEB128 byte" }
            return bytes(id, body.size) + body
        }

        private fun name(value: String): ByteArray {
            val encoded = value.encodeToByteArray()
            return bytes(encoded.size) + encoded
        }

        private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}
