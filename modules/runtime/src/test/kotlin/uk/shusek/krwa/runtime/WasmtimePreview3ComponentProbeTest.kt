package uk.shusek.krwa.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import uk.shusek.krwa.wasm.WasmEngineException

class WasmtimePreview3ComponentProbeTest {
    @Test
    fun jvmPreview3CancellationRejectsClosedWrappersAndIgnoresStaleNativeTokens() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val cancellation = WasmtimePreview3ExecutionCancellation()

        cancellation.close()
        cancellation.cancel()
        assertFalse(cancellation.isCancellationRequested)
        assertFailsWith<IllegalStateException> {
            cancellation.requireOpenHandle()
        }

        val staleHandle = WasmtimePulleyExecution.preview3ExecutionCancellationCreate()
        WasmtimePulleyExecution.preview3ExecutionCancellationFree(staleHandle)
        WasmtimePulleyExecution.preview3ExecutionCancellationCancel(staleHandle)
        assertFalse(
            WasmtimePulleyExecution.preview3ExecutionCancellationIsCancelled(staleHandle),
        )
        WasmtimePulleyExecution.preview3ExecutionCancellationFree(staleHandle)
    }

    @Test
    fun jvmPreview3ComponentProbeLoadsBuiltBridge() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-probe")

        try {
            val reason = wasmtimePreview3ComponentUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = InvalidPrecompiledComponentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            reason.assertBridgeLoadedAndRejectedInvalidComponent()
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentProbeInstantiatesMinimalPulleyComponent() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-probe")
        val componentBytes = compilePulleyComponent(sandbox, "minimal-component", "(component)")

        try {
            val reason = wasmtimePreview3ComponentUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentProbeLinksWasiHttpClientHostFailClosed() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-http")
        val componentBytes = compilePulleyComponentFromDummyWit(
            directory = sandbox,
            name = "http-import-component",
            witSource = Preview3HttpProbeWit,
        )

        try {
            val reason = wasmtimePreview3ComponentUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCallsMinimalPulleyComponentExport() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (func ${'$'}run (export "run"))
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (func ${'$'}run (canon lift (core func ${'$'}instance "run")))
                  (export "run" (func ${'$'}run))
                )
            """.trimIndent(),
        )

        try {
            val reason = wasmtimePreview3ComponentCall0UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "run",
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCallsPulleyComponentS32Export() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-s32")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-s32-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (func ${'$'}increment (export "increment") (param i32) (result i32)
                      local.get 0
                      i32.const 1
                      i32.add
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (func ${'$'}increment (param "value" s32) (result s32)
                    (canon lift (core func ${'$'}instance "increment"))
                  )
                  (export "increment" (func ${'$'}increment))
                )
            """.trimIndent(),
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "increment",
                argument = 41,
                expectedResult = 42,
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCallsNestedInterfacePulleyComponentS32Export() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-nested-s32")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-nested-s32-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (func ${'$'}get-catalogs (export "get-catalogs") (param i32) (result i32)
                      local.get 0
                      i32.const 1
                      i32.add
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (func ${'$'}get-catalogs (param "value" s32) (result s32)
                    (canon lift (core func ${'$'}instance "get-catalogs"))
                  )
                  (instance ${'$'}catalog
                    (export "get-catalogs" (func ${'$'}get-catalogs))
                  )
                  (export "catalog" (instance ${'$'}catalog))
                )
            """.trimIndent(),
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "catalog.get-catalogs",
                argument = 41,
                expectedResult = 42,
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCallsNestedInterfacePulleyComponentStringExport() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-nested-string")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-nested-string-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (memory (export "memory") 1)
                    (global ${'$'}heap (mut i32) (i32.const 4096))
                    (func (export "cabi_realloc")
                      (param ${'$'}old i32)
                      (param ${'$'}old-align i32)
                      (param ${'$'}align i32)
                      (param ${'$'}size i32)
                      (result i32)
                      (local ${'$'}ptr i32)
                      global.get ${'$'}heap
                      local.set ${'$'}ptr
                      global.get ${'$'}heap
                      local.get ${'$'}size
                      i32.add
                      i32.const 7
                      i32.add
                      i32.const -8
                      i32.and
                      global.set ${'$'}heap
                      local.get ${'$'}ptr
                    )
                    (func ${'$'}echo (export "echo") (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                      (local ${'$'}result i32)
                      global.get ${'$'}heap
                      local.set ${'$'}result
                      global.get ${'$'}heap
                      i32.const 8
                      i32.add
                      global.set ${'$'}heap
                      local.get ${'$'}result
                      local.get ${'$'}ptr
                      i32.store
                      local.get ${'$'}result
                      i32.const 4
                      i32.add
                      local.get ${'$'}len
                      i32.store
                      local.get ${'$'}result
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (alias core export ${'$'}instance "memory" (core memory ${'$'}memory))
                  (alias core export ${'$'}instance "cabi_realloc" (core func ${'$'}realloc))
                  (alias core export ${'$'}instance "echo" (core func ${'$'}echo-core))
                  (func ${'$'}echo (param "value" string) (result string)
                    (canon lift (core func ${'$'}echo-core) (memory ${'$'}memory) (realloc ${'$'}realloc))
                  )
                  (instance ${'$'}catalog
                    (export "echo" (func ${'$'}echo))
                  )
                  (export "catalog" (instance ${'$'}catalog))
                )
            """.trimIndent(),
        )

        try {
            val reason = wasmtimePreview3ComponentCallStringUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "catalog.echo",
                argument = "suvio",
                expectedResult = "suvio",
            )

            assertNull(reason)
            assertEquals(
                "suvio",
                wasmtimePreview3ComponentCallString(
                    WasmtimePreview3ComponentConfig(
                        precompiledComponentBytes = componentBytes,
                        hostPreopenRoot = sandbox.toString(),
                        guestPreopenRoot = "/",
                        maxMemoryBytes = 64L * 1024L * 1024L,
                    ),
                    exportName = "catalog.echo",
                    argument = "suvio",
                ),
            )
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeTimesOutLongRunningPulleyComponentStringExport() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-timeout")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-timeout-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (memory (export "memory") 1)
                    (global ${'$'}heap (mut i32) (i32.const 4096))
                    (func (export "cabi_realloc")
                      (param ${'$'}old i32)
                      (param ${'$'}old-align i32)
                      (param ${'$'}align i32)
                      (param ${'$'}size i32)
                      (result i32)
                      (local ${'$'}ptr i32)
                      global.get ${'$'}heap
                      local.set ${'$'}ptr
                      global.get ${'$'}heap
                      local.get ${'$'}size
                      i32.add
                      i32.const 7
                      i32.add
                      i32.const -8
                      i32.and
                      global.set ${'$'}heap
                      local.get ${'$'}ptr
                    )
                    (func ${'$'}spin (export "spin") (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                      loop ${'$'}again
                        br ${'$'}again
                      end
                      unreachable
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (alias core export ${'$'}instance "memory" (core memory ${'$'}memory))
                  (alias core export ${'$'}instance "cabi_realloc" (core func ${'$'}realloc))
                  (alias core export ${'$'}instance "spin" (core func ${'$'}spin-core))
                  (func ${'$'}spin (param "value" string) (result string)
                    (canon lift (core func ${'$'}spin-core) (memory ${'$'}memory) (realloc ${'$'}realloc))
                  )
                  (instance ${'$'}catalog
                    (export "spin" (func ${'$'}spin))
                  )
                  (export "catalog" (instance ${'$'}catalog))
                )
            """.trimIndent(),
        )

        try {
            val error = assertFailsWith<WasmEngineException> {
                wasmtimePreview3ComponentCallString(
                    WasmtimePreview3ComponentConfig(
                        precompiledComponentBytes = componentBytes,
                        hostPreopenRoot = sandbox.toString(),
                        guestPreopenRoot = "/",
                        maxMemoryBytes = 64L * 1024L * 1024L,
                        executionTimeoutMillis = 100L,
                    ),
                    exportName = "catalog.spin",
                    argument = "suvio",
                )
            }

            assertContains(error.message.orEmpty(), "timed out after 100 ms")
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeTrapsWhenPulleyComponentFuelIsExhausted() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-fuel")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-fuel-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (memory (export "memory") 1)
                    (global ${'$'}heap (mut i32) (i32.const 4096))
                    (func (export "cabi_realloc")
                      (param ${'$'}old i32)
                      (param ${'$'}old-align i32)
                      (param ${'$'}align i32)
                      (param ${'$'}size i32)
                      (result i32)
                      (local ${'$'}ptr i32)
                      global.get ${'$'}heap
                      local.set ${'$'}ptr
                      global.get ${'$'}heap
                      local.get ${'$'}size
                      i32.add
                      i32.const 7
                      i32.add
                      i32.const -8
                      i32.and
                      global.set ${'$'}heap
                      local.get ${'$'}ptr
                    )
                    (func ${'$'}spin (export "spin") (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                      loop ${'$'}again
                        br ${'$'}again
                      end
                      unreachable
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (alias core export ${'$'}instance "memory" (core memory ${'$'}memory))
                  (alias core export ${'$'}instance "cabi_realloc" (core func ${'$'}realloc))
                  (alias core export ${'$'}instance "spin" (core func ${'$'}spin-core))
                  (func ${'$'}spin (param "value" string) (result string)
                    (canon lift (core func ${'$'}spin-core) (memory ${'$'}memory) (realloc ${'$'}realloc))
                  )
                  (instance ${'$'}catalog
                    (export "spin" (func ${'$'}spin))
                  )
                  (export "catalog" (instance ${'$'}catalog))
                )
            """.trimIndent(),
            fuel = true,
        )

        try {
            val error = assertFailsWith<WasmEngineException> {
                wasmtimePreview3ComponentCallString(
                    WasmtimePreview3ComponentConfig(
                        precompiledComponentBytes = componentBytes,
                        hostPreopenRoot = sandbox.toString(),
                        guestPreopenRoot = "/",
                        maxMemoryBytes = 64L * 1024L * 1024L,
                        maxFuel = 10_000,
                    ),
                    exportName = "catalog.spin",
                    argument = "suvio",
                )
            }

            assertContains(error.message.orEmpty(), "fuel")
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCancelsLongRunningPulleyComponentStringExport() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-cancel")
        val componentBytes = compilePulleyComponent(
            directory = sandbox,
            name = "call-cancel-component",
            watSource = """
                (component
                  (core module ${'$'}module
                    (memory (export "memory") 1)
                    (global ${'$'}heap (mut i32) (i32.const 4096))
                    (func (export "cabi_realloc")
                      (param ${'$'}old i32)
                      (param ${'$'}old-align i32)
                      (param ${'$'}align i32)
                      (param ${'$'}size i32)
                      (result i32)
                      (local ${'$'}ptr i32)
                      global.get ${'$'}heap
                      local.set ${'$'}ptr
                      global.get ${'$'}heap
                      local.get ${'$'}size
                      i32.add
                      i32.const 7
                      i32.add
                      i32.const -8
                      i32.and
                      global.set ${'$'}heap
                      local.get ${'$'}ptr
                    )
                    (func ${'$'}spin (export "spin") (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                      loop ${'$'}again
                        br ${'$'}again
                      end
                      unreachable
                    )
                  )
                  (core instance ${'$'}instance (instantiate ${'$'}module))
                  (alias core export ${'$'}instance "memory" (core memory ${'$'}memory))
                  (alias core export ${'$'}instance "cabi_realloc" (core func ${'$'}realloc))
                  (alias core export ${'$'}instance "spin" (core func ${'$'}spin-core))
                  (func ${'$'}spin (param "value" string) (result string)
                    (canon lift (core func ${'$'}spin-core) (memory ${'$'}memory) (realloc ${'$'}realloc))
                  )
                  (instance ${'$'}catalog
                    (export "spin" (func ${'$'}spin))
                  )
                  (export "catalog" (instance ${'$'}catalog))
                )
            """.trimIndent(),
        )
        val cancellation = WasmtimePreview3ExecutionCancellation()
        val canceller = Thread {
            Thread.sleep(100)
            cancellation.cancel()
        }

        try {
            canceller.start()
            val error = assertFailsWith<WasmEngineException> {
                wasmtimePreview3ComponentCallString(
                    config = WasmtimePreview3ComponentConfig(
                        precompiledComponentBytes = componentBytes,
                        hostPreopenRoot = sandbox.toString(),
                        guestPreopenRoot = "/",
                        maxMemoryBytes = 64L * 1024L * 1024L,
                    ),
                    exportName = "catalog.spin",
                    argument = "suvio",
                    cancellation = cancellation,
                )
            }

            assertContains(error.message.orEmpty(), "was cancelled")
            assertTrue(cancellation.isCancellationRequested)
        } finally {
            cancellation.close()
            canceller.join(5_000)
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeCallsStacklessAsyncNestedInterfacePulleyComponentS32Export() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-stackless-async-s32")
        val componentBytes = compilePulleyComponentFromCore(
            directory = sandbox,
            name = "call-stackless-async-s32-component",
            witSource = Preview3AsyncInterfaceS32ProbeWit,
            coreWatSource = Preview3AsyncInterfaceS32ProbeCoreWat,
            asyncStackful = false,
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "catalog.get-catalogs",
                argument = 41,
                expectedResult = 42,
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeFailsClosedForRootStacklessAsyncPulleyComponentS32Export() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-call-async-s32")
        val componentBytes = compilePulleyComponentFromCore(
            directory = sandbox,
            name = "call-async-s32-component",
            witSource = Preview3AsyncS32ProbeWit,
            coreWatSource = Preview3AsyncS32ProbeCoreWat,
            asyncStackful = false,
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    hostPreopenRoot = sandbox.toString(),
                    guestPreopenRoot = "/",
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "run",
                argument = 41,
                expectedResult = 42,
            )

            val message = assertNotNull(reason)
            assertContains(message, "lift_result field is missing")
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeLinksFilesystemImportsAndEnforcesPreopenPolicy() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-fs")
        val commandRoot = sandbox.resolve("command-root")
        val cacheRoot = sandbox.resolve("cache-root")
        Files.createDirectories(commandRoot)
        Files.createDirectories(cacheRoot)
        val componentBytes = compilePulleyComponentFromCore(
            directory = sandbox,
            name = "filesystem-preopens-component",
            witSource = Preview3FilesystemProbeWit,
            coreWatSource = preview3FilesystemProbeCoreWat(
                cachePath = "cache.txt",
                expectCacheWrite = true,
            ),
            asyncStackful = false,
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = commandRoot.toString(),
                            guestRoot = "/",
                            writable = false,
                        ),
                        WasmtimePreview3Preopen(
                            hostRoot = cacheRoot.toString(),
                            guestRoot = "/suvio/cache",
                            writable = true,
                        ),
                    ),
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "catalog.run",
                argument = 41,
                expectedResult = 42,
            )

            assertNull(reason)
            assertFalse(Files.exists(commandRoot.resolve("blocked.txt")))
            assertTrue(Files.exists(cacheRoot.resolve("cache.txt")))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3ComponentBridgeDoesNotFollowWritablePreopenSymlink() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-fs-symlink")
        val commandRoot = sandbox.resolve("command-root")
        val cacheRoot = sandbox.resolve("cache-root")
        val outsideRoot = sandbox.resolve("outside-root")
        Files.createDirectories(commandRoot)
        Files.createDirectories(cacheRoot)
        Files.createDirectories(outsideRoot)
        Files.createSymbolicLink(cacheRoot.resolve("escape"), outsideRoot)
        val componentBytes = compilePulleyComponentFromCore(
            directory = sandbox,
            name = "filesystem-symlink-preopens-component",
            witSource = Preview3FilesystemProbeWit,
            coreWatSource = preview3FilesystemProbeCoreWat(
                cachePath = "escape/outside.txt",
                expectCacheWrite = false,
            ),
            asyncStackful = false,
        )

        try {
            val reason = wasmtimePreview3ComponentCallS32UnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = commandRoot.toString(),
                            guestRoot = "/",
                            writable = false,
                        ),
                        WasmtimePreview3Preopen(
                            hostRoot = cacheRoot.toString(),
                            guestRoot = "/suvio/cache",
                            writable = true,
                        ),
                    ),
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
                exportName = "catalog.run",
                argument = 41,
                expectedResult = 42,
            )

            assertNull(reason)
            assertFalse(Files.exists(outsideRoot.resolve("outside.txt")))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3CommandBridgeRunsMinimalCommand() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-command")
        val componentBytes = compilePulleyCommandComponentFromCore(
            directory = sandbox,
            name = "minimal-command-component",
            coreWatSource = preview3CommandReturnCoreWat(resultTag = 0),
        )

        try {
            val reason = wasmtimePreview3CommandRunUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = sandbox.toString(),
                            guestRoot = "/",
                            writable = true,
                        ),
                    ),
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            assertNull(reason)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3CommandBridgeEnforcesFilesystemPreopenPolicy() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-command-fs")
        val commandRoot = sandbox.resolve("command-root")
        val cacheRoot = sandbox.resolve("cache-root")
        Files.createDirectories(commandRoot)
        Files.createDirectories(cacheRoot)
        val componentBytes = compilePulleyCommandComponentFromCore(
            directory = sandbox,
            name = "filesystem-command-preopens-component",
            coreWatSource = preview3FilesystemCommandCoreWat(
                cachePath = "cache.txt",
                expectCacheWrite = true,
            ),
        )

        try {
            val reason = wasmtimePreview3CommandRunUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = commandRoot.toString(),
                            guestRoot = "/",
                            writable = false,
                        ),
                        WasmtimePreview3Preopen(
                            hostRoot = cacheRoot.toString(),
                            guestRoot = "/suvio/cache",
                            writable = true,
                        ),
                    ),
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            assertNull(reason)
            assertFalse(Files.exists(commandRoot.resolve("blocked.txt")))
            assertTrue(Files.exists(cacheRoot.resolve("cache.txt")))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmPreview3CommandBridgeDoesNotFollowWritablePreopenSymlink() {
        assumeTrue(System.getProperty("krwa.wasmtime.p3.bridge.integration") == "true")
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val bridgeLibraryPath = Path.of(System.getProperty("krwa.wasmtime.p3.bridge.library"))
        assertTrue(Files.isRegularFile(bridgeLibraryPath), "Missing bridge library: $bridgeLibraryPath")
        val sandbox = Files.createTempDirectory("krwa-wasmtime-p3-command-fs-symlink")
        val commandRoot = sandbox.resolve("command-root")
        val cacheRoot = sandbox.resolve("cache-root")
        val outsideRoot = sandbox.resolve("outside-root")
        Files.createDirectories(commandRoot)
        Files.createDirectories(cacheRoot)
        Files.createDirectories(outsideRoot)
        Files.createSymbolicLink(cacheRoot.resolve("escape"), outsideRoot)
        val componentBytes = compilePulleyCommandComponentFromCore(
            directory = sandbox,
            name = "filesystem-command-symlink-preopens-component",
            coreWatSource = preview3FilesystemCommandCoreWat(
                cachePath = "escape/outside.txt",
                expectCacheWrite = false,
            ),
        )

        try {
            val reason = wasmtimePreview3CommandRunUnavailableReason(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = componentBytes,
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = commandRoot.toString(),
                            guestRoot = "/",
                            writable = false,
                        ),
                        WasmtimePreview3Preopen(
                            hostRoot = cacheRoot.toString(),
                            guestRoot = "/suvio/cache",
                            writable = true,
                        ),
                    ),
                    maxMemoryBytes = 64L * 1024L * 1024L,
                ),
            )

            assertNull(reason)
            assertFalse(Files.exists(outsideRoot.resolve("outside.txt")))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private fun String?.assertBridgeLoadedAndRejectedInvalidComponent() {
        val message = assertNotNull(this)
        assertContains(message, "failed to deserialize Wasmtime component")
        assertFalse(message.contains("not linked", ignoreCase = true), message)
        assertFalse(message.contains("native access", ignoreCase = true), message)
    }

    private fun compilePulleyComponent(
        directory: Path,
        name: String,
        watSource: String,
        fuel: Boolean = false,
    ): ByteArray {
        val wasmtime = System.getProperty("krwa.wasmtime.cli")?.takeIf(String::isNotBlank) ?: "wasmtime"
        val wat = directory.resolve("$name.wat")
        val cwasm = directory.resolve("$name.cwasm")
        Files.writeString(wat, watSource)
        return compilePulleyComponentFile(wasmtime, wat, cwasm, fuel)
    }

    private fun compilePulleyComponentFromCore(
        directory: Path,
        name: String,
        witSource: String,
        coreWatSource: String,
        asyncStackful: Boolean = true,
    ): ByteArray {
        val wasmTools = System.getProperty("krwa.wasm.tools.cli")?.takeIf(String::isNotBlank) ?: "wasm-tools"
        val wasmtime = System.getProperty("krwa.wasmtime.cli")?.takeIf(String::isNotBlank) ?: "wasmtime"
        val witRoot = directory.resolve("$name-wit")
        val wit = witRoot.resolve("plugin.wit")
        val witDeps = witRoot.resolve("deps")
        val coreWat = directory.resolve("$name.core.wat")
        val coreWasm = directory.resolve("$name.core.wasm")
        val metadataModuleWasm = directory.resolve("$name.metadata-module.wasm")
        val embeddedCoreWasm = directory.resolve("$name.embedded-core.wasm")
        val componentWasm = directory.resolve("$name.component.wasm")
        val cwasm = directory.resolve("$name.cwasm")
        Files.createDirectories(witDeps)
        Files.writeString(wit, witSource)
        Files.newDirectoryStream(wasmtimePreview3WitDirectory().resolve("deps")) { path ->
            path.fileName.toString().endsWith(".wit")
        }.use { sources ->
            for (source in sources) {
                Files.copy(source, witDeps.resolve(source.fileName.toString()))
            }
        }
        Files.writeString(coreWat, coreWatSource)

        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools parse core timed out",
            failureMessage = "wasm-tools parse core failed",
            command = listOf(
                wasmTools,
                "parse",
                coreWat.toString(),
                "-o",
                coreWasm.toString(),
            ),
        )
        val embedCommand = mutableListOf(
            wasmTools,
            "component",
            "embed",
            "--dummy-names",
            "legacy",
        )
        if (asyncStackful) {
            embedCommand.add("--async-stackful")
        } else {
            embedCommand.add("--async-callback")
        }
        embedCommand.addAll(
            listOf(
                witRoot.toString(),
                "--world",
                "plugin",
                "-o",
                metadataModuleWasm.toString(),
            ),
        )
        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component embed timed out",
            failureMessage = "wasm-tools component embed failed",
            command = embedCommand,
        )
        appendComponentTypeSection(
            coreWasm = coreWasm,
            metadataModuleWasm = metadataModuleWasm,
            output = embeddedCoreWasm,
        )
        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component new timed out",
            failureMessage = "wasm-tools component new failed",
            command = listOf(
                wasmTools,
                "component",
                "new",
                embeddedCoreWasm.toString(),
                "-o",
                componentWasm.toString(),
            ),
        )
        return compilePulleyComponentFile(wasmtime, componentWasm, cwasm)
    }

    private fun compilePulleyCommandComponentFromCore(
        directory: Path,
        name: String,
        coreWatSource: String,
    ): ByteArray {
        val wasmTools = System.getProperty("krwa.wasm.tools.cli")?.takeIf(String::isNotBlank) ?: "wasm-tools"
        val wasmtime = System.getProperty("krwa.wasmtime.cli")?.takeIf(String::isNotBlank) ?: "wasmtime"
        val wit = wasmtimePreview3WitDirectory()
        val coreWat = directory.resolve("$name.core.wat")
        val coreWasm = directory.resolve("$name.core.wasm")
        val metadataModuleWasm = directory.resolve("$name.metadata-module.wasm")
        val embeddedCoreWasm = directory.resolve("$name.embedded-core.wasm")
        val componentWasm = directory.resolve("$name.component.wasm")
        val cwasm = directory.resolve("$name.cwasm")
        Files.writeString(coreWat, coreWatSource)

        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools parse command core timed out",
            failureMessage = "wasm-tools parse command core failed",
            command = listOf(
                wasmTools,
                "parse",
                coreWat.toString(),
                "-o",
                coreWasm.toString(),
            ),
        )
        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component command metadata timed out",
            failureMessage = "wasm-tools component command metadata failed",
            command = listOf(
                wasmTools,
                "component",
                "embed",
                "--dummy-names",
                "legacy",
                "--async-stackful",
                wit.toString(),
                "--world",
                "wasi:cli/command",
                "-o",
                metadataModuleWasm.toString(),
            ),
        )
        appendComponentTypeSection(
            coreWasm = coreWasm,
            metadataModuleWasm = metadataModuleWasm,
            output = embeddedCoreWasm,
        )
        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component new command timed out",
            failureMessage = "wasm-tools component new command failed",
            command = listOf(
                wasmTools,
                "component",
                "new",
                embeddedCoreWasm.toString(),
                "-o",
                componentWasm.toString(),
            ),
        )
        return compilePulleyComponentFile(wasmtime, componentWasm, cwasm)
    }

    private fun wasmtimePreview3WitDirectory(): Path {
        val witCandidates = listOf(
            Path.of("modules/runtime/build/wasmtime-pulley/source/crates/wasi/src/p3/wit"),
            Path.of("build/wasmtime-pulley/source/crates/wasi/src/p3/wit"),
            Path.of("modules/runtime/build/wasmtime-pulley-ios/source/crates/wasi/src/p3/wit"),
            Path.of("build/wasmtime-pulley-ios/source/crates/wasi/src/p3/wit"),
        ).map { path -> path.toAbsolutePath().normalize() }
        return assertNotNull(
            witCandidates.firstOrNull(Files::isDirectory),
            "Missing Wasmtime Preview3 WIT directory. Checked: $witCandidates",
        )
    }

    private fun appendComponentTypeSection(
        coreWasm: Path,
        metadataModuleWasm: Path,
        output: Path,
    ) {
        val coreBytes = Files.readAllBytes(coreWasm)
        val componentTypeSection = Files.readAllBytes(metadataModuleWasm).customSection("component-type")
        Files.write(output, coreBytes + componentTypeSection)
    }

    private fun ByteArray.customSection(name: String): ByteArray {
        require(size >= WasmHeaderSize && copyOfRange(0, WasmHeaderSize).contentEquals(WasmHeader)) {
            "Expected a WebAssembly module"
        }
        var offset = WasmHeaderSize
        while (offset < size) {
            val sectionStart = offset
            val sectionId = this[offset++].toInt() and 0xff
            val sectionSize = readUnsignedLeb128(offset)
            val payloadStart = sectionSize.nextOffset
            val payloadEnd = payloadStart + sectionSize.value
            require(payloadEnd <= size) { "Malformed WebAssembly section" }
            if (sectionId == WasmCustomSectionId) {
                val sectionNameLength = readUnsignedLeb128(payloadStart)
                val nameStart = sectionNameLength.nextOffset
                val nameEnd = nameStart + sectionNameLength.value
                require(nameEnd <= payloadEnd) { "Malformed WebAssembly custom section name" }
                val sectionName = decodeToString(nameStart, nameEnd)
                if (sectionName == name) {
                    return copyOfRange(sectionStart, payloadEnd)
                }
            }
            offset = payloadEnd
        }
        error("Missing WebAssembly custom section: $name")
    }

    private fun ByteArray.readUnsignedLeb128(offset: Int): Leb128 {
        var currentOffset = offset
        var value = 0
        var shift = 0
        while (currentOffset < size) {
            val byte = this[currentOffset++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                return Leb128(value, currentOffset)
            }
            shift += 7
            require(shift < Int.SIZE_BITS) { "Unsupported WebAssembly LEB128 value" }
        }
        error("Truncated WebAssembly LEB128 value")
    }

    private data class Leb128(
        val value: Int,
        val nextOffset: Int,
    )

    private fun compilePulleyComponentFromDummyWit(
        directory: Path,
        name: String,
        witSource: String,
    ): ByteArray {
        val wasmTools = System.getProperty("krwa.wasm.tools.cli")?.takeIf(String::isNotBlank) ?: "wasm-tools"
        val wasmtime = System.getProperty("krwa.wasmtime.cli")?.takeIf(String::isNotBlank) ?: "wasmtime"
        val witRoot = directory.resolve("$name-wit")
        val wit = witRoot.resolve("plugin.wit")
        val witDeps = witRoot.resolve("deps")
        val embeddedCoreWasm = directory.resolve("$name.embedded-core.wasm")
        val componentWasm = directory.resolve("$name.component.wasm")
        val cwasm = directory.resolve("$name.cwasm")
        Files.createDirectories(witDeps)
        Files.writeString(wit, witSource)
        Files.newDirectoryStream(wasmtimePreview3HttpWitDirectory().resolve("deps")) { path ->
            path.fileName.toString().endsWith(".wit")
        }.use { sources ->
            for (source in sources) {
                Files.copy(source, witDeps.resolve(source.fileName.toString()))
            }
        }

        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component embed dummy timed out",
            failureMessage = "wasm-tools component embed dummy failed",
            command = listOf(
                wasmTools,
                "component",
                "embed",
                witRoot.toString(),
                "--world",
                "plugin",
                "--dummy",
                "-o",
                embeddedCoreWasm.toString(),
            ),
        )
        runProcess(
            directory = directory,
            timeoutSeconds = 30,
            timeoutMessage = "wasm-tools component new timed out",
            failureMessage = "wasm-tools component new failed",
            command = listOf(
                wasmTools,
                "component",
                "new",
                embeddedCoreWasm.toString(),
                "-o",
                componentWasm.toString(),
            ),
        )
        return compilePulleyComponentFile(wasmtime, componentWasm, cwasm)
    }

    private fun wasmtimePreview3HttpWitDirectory(): Path {
        val witCandidates = listOf(
            Path.of("modules/runtime/build/wasmtime-pulley/source/crates/wasi-http/src/p3/wit"),
            Path.of("build/wasmtime-pulley/source/crates/wasi-http/src/p3/wit"),
            Path.of("modules/runtime/build/wasmtime-pulley-ios/source/crates/wasi-http/src/p3/wit"),
            Path.of("build/wasmtime-pulley-ios/source/crates/wasi-http/src/p3/wit"),
        ).map { path -> path.toAbsolutePath().normalize() }
        return assertNotNull(
            witCandidates.firstOrNull(Files::isDirectory),
            "Missing Wasmtime Preview3 HTTP WIT directory. Checked: $witCandidates",
        )
    }

    private fun compilePulleyComponentFile(
        wasmtime: String,
        input: Path,
        output: Path,
        fuel: Boolean = false,
    ): ByteArray {
        val command = mutableListOf(
            wasmtime,
            "compile",
            "--target",
            WasmtimePulleyTarget,
            "-C",
            "collector=drc",
            "-W",
            "component-model=y",
            "-W",
            "component-model-async=y",
            "-W",
            "epoch-interruption=y",
            "-W",
            "component-model-more-async-builtins=y",
            "-W",
            "component-model-async-stackful=y",
            "-W",
            "component-model-threading=y",
            "-W",
            "component-model-error-context=y",
            "-W",
            "gc=y",
            "-W",
            "function-references=y",
            "-W",
            "exceptions=y",
            "-W",
            "multi-memory=y",
        )
        if (fuel) {
            command += listOf("-W", "fuel=1")
        }
        command += listOf(
            "-o",
            output.toString(),
            input.toString(),
        )
        runProcess(
            directory = input.parent,
            timeoutSeconds = 30,
            timeoutMessage = "wasmtime compile timed out",
            failureMessage = "wasmtime compile failed",
            command = command,
        )
        return Files.readAllBytes(output)
    }

    private fun runProcess(
        directory: Path,
        timeoutSeconds: Long,
        timeoutMessage: String,
        failureMessage: String,
        command: List<String>,
    ) {
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "$timeoutMessage:\n$output")
        assertEquals(0, process.exitValue(), "$failureMessage:\n$output")
    }

    private companion object {
        const val WasmCustomSectionId = 0
        const val WasmHeaderSize = 8
        val WasmHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val InvalidPrecompiledComponentBytes = byteArrayOf(0, 1, 2, 3)
        val Preview3FilesystemProbeWit = """
            package test:fs@1.0.0;

            interface catalog {
              run: async func(value: s32) -> s32;
            }

            world plugin {
              import wasi:filesystem/types@0.3.0;
              import wasi:filesystem/preopens@0.3.0;
              export catalog;
            }
        """.trimIndent()

        val Preview3AsyncS32ProbeWit = """
            package test:async-export;

            world plugin {
              export run: async func(value: s32) -> s32;
            }
        """.trimIndent()

        val Preview3AsyncS32ProbeCoreWat = """
            (module
              (type ${'$'}task-return-run (func (param i32)))
              (import "[export]${'$'}root" "[task-return]run" (func ${'$'}task-return-run (type ${'$'}task-return-run)))
              (memory (export "memory") 1)
              (func (export "cabi_realloc")
                (param ${'$'}old i32)
                (param ${'$'}old-align i32)
                (param ${'$'}align i32)
                (param ${'$'}size i32)
                (result i32)
                i32.const 1024
              )
              (func (export "_initialize"))
              (func (export "cabi_post_run") (param i32))
              (func (export "run") (param ${'$'}value i32) (result i32)
                local.get ${'$'}value
                i32.const 1
                i32.add
                call ${'$'}task-return-run
                i32.const 0
              )
              (func (export "[callback][async-lift]${'$'}root#run")
                (param i32)
                (param i32)
                (param i32)
                (result i32)
                i32.const 0
              )
            )
        """.trimIndent()

        val Preview3AsyncInterfaceS32ProbeWit = """
            package test:async-interface@1.0.0;

            interface catalog {
              get-catalogs: async func(value: s32) -> s32;
            }

            world plugin {
              export catalog;
            }
        """.trimIndent()

        val Preview3AsyncInterfaceS32ProbeCoreWat = """
            (module
              (type ${'$'}task-return-get-catalogs (func (param i32)))
              (import "[export]test:async-interface/catalog@1.0.0" "[task-return]get-catalogs" (func ${'$'}task-return-get-catalogs (type ${'$'}task-return-get-catalogs)))
              (memory (export "memory") 1)
              (func (export "cabi_realloc")
                (param ${'$'}old i32)
                (param ${'$'}old-align i32)
                (param ${'$'}align i32)
                (param ${'$'}size i32)
                (result i32)
                i32.const 1024
              )
              (func (export "_initialize"))
              (func (export "cabi_post_run") (param i32))
              (func (export "[async-lift]test:async-interface/catalog@1.0.0#get-catalogs")
                (param ${'$'}value i32)
                (result i32)
                local.get ${'$'}value
                i32.const 1
                i32.add
                call ${'$'}task-return-get-catalogs
                i32.const 0
              )
              (func (export "[callback][async-lift]test:async-interface/catalog@1.0.0#get-catalogs")
                (param i32)
                (param i32)
                (param i32)
                (result i32)
                i32.const 0
              )
            )
        """.trimIndent()

        val Preview3HttpProbeWit = """
            package test:http;

            world plugin {
              import wasi:http/types@0.3.0;
              import wasi:http/client@0.3.0;
              export run: func(value: s32) -> s32;
            }
        """.trimIndent()

        fun preview3CommandReturnCoreWat(resultTag: Int): String {
            require(resultTag == 0 || resultTag == 1) {
                "wasi:cli/run result tag must be 0 for ok or 1 for err"
            }
            return """
            (module
              (type ${'$'}task-return-run (func (param i32)))

              (import "[export]wasi:cli/run@0.3.0" "[task-return]run" (func ${'$'}task-return-run (type ${'$'}task-return-run)))

              (memory (export "memory") 1)
              (global ${'$'}heap (mut i32) (i32.const 4096))

              (func (export "cabi_realloc")
                (param ${'$'}old i32)
                (param ${'$'}old-align i32)
                (param ${'$'}align i32)
                (param ${'$'}size i32)
                (result i32)
                (local ${'$'}ptr i32)
                global.get ${'$'}heap
                local.set ${'$'}ptr
                global.get ${'$'}heap
                local.get ${'$'}size
                i32.add
                i32.const 7
                i32.add
                i32.const -8
                i32.and
                global.set ${'$'}heap
                local.get ${'$'}ptr
              )

              (func (export "_initialize"))

              (func (export "[async-lift-stackful]wasi:cli/run@0.3.0#run")
                i32.const $resultTag
                call ${'$'}task-return-run
              )
            )
        """.trimIndent()
        }

        fun preview3FilesystemProbeCoreWat(
            cachePath: String,
            expectCacheWrite: Boolean,
        ): String {
            val escapedCachePath = cachePath.watStringLiteralBody()
            val cachePathLength = cachePath.encodeToByteArray().size
            val cacheWriteAssertion = if (expectCacheWrite) {
                """
                i32.const 2220
                i32.load8_u
                if
                  i32.const 2224
                  i32.load8_u
                  i32.const 800
                  i32.add
                  call ${'$'}return-run
                  return
                end
                i32.const 2224
                i32.load
                local.set ${'$'}file-descriptor
                local.get ${'$'}file-descriptor
                call ${'$'}drop-descriptor
                """.trimIndent()
            } else {
                """
                i32.const 2220
                i32.load8_u
                i32.const 1
                i32.ne
                if
                  i32.const 820
                  call ${'$'}return-run
                  return
                end
                """.trimIndent()
            }
            return """
            (module
              (type ${'$'}async-open-at (func (param i32 i32) (result i32)))
              (type ${'$'}drop-descriptor (func (param i32)))
              (type ${'$'}get-directories (func (param i32)))
              (type ${'$'}waitable-set-new (func (result i32)))
              (type ${'$'}waitable-set-wait (func (param i32 i32) (result i32)))
              (type ${'$'}waitable-set-drop (func (param i32)))
              (type ${'$'}waitable-join (func (param i32 i32)))
              (type ${'$'}subtask-drop (func (param i32)))
              (type ${'$'}task-return-run (func (param i32)))

              (import "wasi:filesystem/types@0.3.0" "[async-lower][method]descriptor.open-at" (func ${'$'}open-at (type ${'$'}async-open-at)))
              (import "wasi:filesystem/types@0.3.0" "[resource-drop]descriptor" (func ${'$'}drop-descriptor (type ${'$'}drop-descriptor)))
              (import "wasi:filesystem/preopens@0.3.0" "get-directories" (func ${'$'}get-directories (type ${'$'}get-directories)))
              (import "${'$'}root" "[waitable-set-new]" (func ${'$'}waitable-set-new (type ${'$'}waitable-set-new)))
              (import "${'$'}root" "[waitable-set-wait]" (func ${'$'}waitable-set-wait (type ${'$'}waitable-set-wait)))
              (import "${'$'}root" "[waitable-set-drop]" (func ${'$'}waitable-set-drop (type ${'$'}waitable-set-drop)))
              (import "${'$'}root" "[waitable-join]" (func ${'$'}waitable-join (type ${'$'}waitable-join)))
              (import "${'$'}root" "[subtask-drop]" (func ${'$'}subtask-drop (type ${'$'}subtask-drop)))
              (import "[export]test:fs/catalog@1.0.0" "[task-return]run" (func ${'$'}task-return-run (type ${'$'}task-return-run)))

              (memory (export "memory") 1)
              (global ${'$'}heap (mut i32) (i32.const 4096))
              (data (i32.const 1024) "blocked.txt")
              (data (i32.const 1040) "$escapedCachePath")

              (func (export "cabi_realloc")
                (param ${'$'}old i32)
                (param ${'$'}old-align i32)
                (param ${'$'}align i32)
                (param ${'$'}size i32)
                (result i32)
                (local ${'$'}ptr i32)
                global.get ${'$'}heap
                local.set ${'$'}ptr
                global.get ${'$'}heap
                local.get ${'$'}size
                i32.add
                i32.const 7
                i32.add
                i32.const -8
                i32.and
                global.set ${'$'}heap
                local.get ${'$'}ptr
              )

              (func (export "_initialize"))
              (func (export "cabi_post_run") (param i32))

              (func ${'$'}return-run (param ${'$'}value i32) (result i32)
                local.get ${'$'}value
                call ${'$'}task-return-run
                i32.const 0
              )

              (func ${'$'}await-open-at
                (param ${'$'}params i32)
                (param ${'$'}result i32)
                (result i32)
                (local ${'$'}packed i32)
                (local ${'$'}status i32)
                (local ${'$'}handle i32)
                (local ${'$'}set i32)
                local.get ${'$'}params
                local.get ${'$'}result
                call ${'$'}open-at
                local.tee ${'$'}packed
                i32.const 15
                i32.and
                local.set ${'$'}status
                local.get ${'$'}packed
                i32.const 4
                i32.shr_u
                local.set ${'$'}handle

                block ${'$'}done
                  loop ${'$'}again
                    local.get ${'$'}status
                    i32.const 2
                    i32.eq
                    br_if ${'$'}done

                    local.get ${'$'}status
                    i32.const 3
                    i32.ge_u
                    if
                      i32.const 700
                      return
                    end

                    local.get ${'$'}set
                    i32.eqz
                    if
                      call ${'$'}waitable-set-new
                      local.set ${'$'}set
                      local.get ${'$'}handle
                      i32.eqz
                      if
                        i32.const 703
                        return
                      end
                      local.get ${'$'}handle
                      local.get ${'$'}set
                      call ${'$'}waitable-join
                    end

                    local.get ${'$'}set
                    i32.const 3000
                    call ${'$'}waitable-set-wait
                    i32.const 1
                    i32.ne
                    if
                      i32.const 701
                      return
                    end

                    i32.const 3000
                    i32.load
                    local.get ${'$'}handle
                    i32.ne
                    if
                      i32.const 702
                      return
                    end

                    i32.const 3004
                    i32.load
                    local.set ${'$'}status
                    br ${'$'}again
                  end
                end

                local.get ${'$'}set
                if
                  local.get ${'$'}handle
                  if
                    local.get ${'$'}handle
                    call ${'$'}subtask-drop
                  end
                  local.get ${'$'}set
                  call ${'$'}waitable-set-drop
                end
                i32.const 0
              )

              (func ${'$'}create-file-at
                (param ${'$'}descriptor i32)
                (param ${'$'}path i32)
                (param ${'$'}path-len i32)
                (param ${'$'}params i32)
                (param ${'$'}result i32)
                (result i32)
                (local ${'$'}await-result i32)

                local.get ${'$'}params
                local.get ${'$'}descriptor
                i32.store

                local.get ${'$'}params
                i32.const 4
                i32.add
                i32.const 0
                i32.store8

                local.get ${'$'}params
                i32.const 8
                i32.add
                local.get ${'$'}path
                i32.store

                local.get ${'$'}params
                i32.const 12
                i32.add
                local.get ${'$'}path-len
                i32.store

                local.get ${'$'}params
                i32.const 16
                i32.add
                i32.const 1
                i32.store8

                local.get ${'$'}params
                i32.const 17
                i32.add
                i32.const 2
                i32.store8

                local.get ${'$'}params
                local.get ${'$'}result
                call ${'$'}await-open-at
                local.tee ${'$'}await-result
                if
                  local.get ${'$'}await-result
                  return
                end
                i32.const 0
              )

              (func (export "[async-lift]test:fs/catalog@1.0.0#run") (param ${'$'}value i32) (result i32)
                (local ${'$'}list-ptr i32)
                (local ${'$'}list-len i32)
                (local ${'$'}root-descriptor i32)
                (local ${'$'}cache-descriptor i32)
                (local ${'$'}file-descriptor i32)
                (local ${'$'}error-code i32)
                (local ${'$'}operation-result i32)

                i32.const 2000
                call ${'$'}get-directories
                i32.const 2000
                i32.load
                local.set ${'$'}list-ptr
                i32.const 2004
                i32.load
                local.set ${'$'}list-len
                local.get ${'$'}list-len
                i32.const 2
                i32.lt_u
                if
                  i32.const 710
                  call ${'$'}return-run
                  return
                end

                local.get ${'$'}list-ptr
                i32.load
                local.set ${'$'}root-descriptor
                local.get ${'$'}list-ptr
                i32.const 12
                i32.add
                i32.load
                local.set ${'$'}cache-descriptor

                local.get ${'$'}root-descriptor
                i32.const 1024
                i32.const 11
                i32.const 2100
                i32.const 2120
                call ${'$'}create-file-at
                local.tee ${'$'}operation-result
                if
                  local.get ${'$'}operation-result
                  call ${'$'}return-run
                  return
                end

                i32.const 2120
                i32.load8_u
                i32.const 1
                i32.ne
                if
                  i32.const 720
                  call ${'$'}return-run
                  return
                end
                i32.const 2124
                i32.load8_u
                local.set ${'$'}error-code
                local.get ${'$'}error-code
                i32.const 30
                i32.eq
                local.get ${'$'}error-code
                i32.const 32
                i32.eq
                i32.or
                i32.eqz
                if
                  local.get ${'$'}error-code
                  i32.const 730
                  i32.add
                  call ${'$'}return-run
                  return
                end

                local.get ${'$'}cache-descriptor
                i32.const 1040
                i32.const $cachePathLength
                i32.const 2200
                i32.const 2220
                call ${'$'}create-file-at
                local.tee ${'$'}operation-result
                if
                  local.get ${'$'}operation-result
                  call ${'$'}return-run
                  return
                end

                $cacheWriteAssertion
                local.get ${'$'}root-descriptor
                call ${'$'}drop-descriptor
                local.get ${'$'}cache-descriptor
                call ${'$'}drop-descriptor

                local.get ${'$'}value
                i32.const 1
                i32.add
                call ${'$'}return-run
              )
              (func (export "[callback][async-lift]test:fs/catalog@1.0.0#run")
                (param i32)
                (param i32)
                (param i32)
                (result i32)
                i32.const 0
              )
            )
        """.trimIndent()
        }

        fun preview3FilesystemCommandCoreWat(
            cachePath: String,
            expectCacheWrite: Boolean,
        ): String {
            val escapedCachePath = cachePath.watStringLiteralBody()
            val cachePathLength = cachePath.encodeToByteArray().size
            val cacheWriteAssertion = if (expectCacheWrite) {
                ""
            } else {
                ""
            }
            return """
            (module
              (type ${'$'}async-open-at (func (param i32 i32) (result i32)))
              (type ${'$'}drop-descriptor (func (param i32)))
              (type ${'$'}get-directories (func (param i32)))
              (type ${'$'}waitable-set-new (func (result i32)))
              (type ${'$'}waitable-set-wait (func (param i32 i32) (result i32)))
              (type ${'$'}waitable-set-drop (func (param i32)))
              (type ${'$'}waitable-join (func (param i32 i32)))
              (type ${'$'}subtask-drop (func (param i32)))
              (type ${'$'}task-return-run (func (param i32)))

              (import "wasi:filesystem/types@0.3.0" "[async-lower][method]descriptor.open-at" (func ${'$'}open-at (type ${'$'}async-open-at)))
              (import "wasi:filesystem/types@0.3.0" "[resource-drop]descriptor" (func ${'$'}drop-descriptor (type ${'$'}drop-descriptor)))
              (import "wasi:filesystem/preopens@0.3.0" "get-directories" (func ${'$'}get-directories (type ${'$'}get-directories)))
              (import "${'$'}root" "[waitable-set-new]" (func ${'$'}waitable-set-new (type ${'$'}waitable-set-new)))
              (import "${'$'}root" "[waitable-set-wait]" (func ${'$'}waitable-set-wait (type ${'$'}waitable-set-wait)))
              (import "${'$'}root" "[waitable-set-drop]" (func ${'$'}waitable-set-drop (type ${'$'}waitable-set-drop)))
              (import "${'$'}root" "[waitable-join]" (func ${'$'}waitable-join (type ${'$'}waitable-join)))
              (import "${'$'}root" "[subtask-drop]" (func ${'$'}subtask-drop (type ${'$'}subtask-drop)))
              (import "[export]wasi:cli/run@0.3.0" "[task-return]run" (func ${'$'}task-return-run (type ${'$'}task-return-run)))

              (memory (export "memory") 1)
              (global ${'$'}heap (mut i32) (i32.const 4096))
              (data (i32.const 1024) "blocked.txt")
              (data (i32.const 1040) "$escapedCachePath")

              (func (export "cabi_realloc")
                (param ${'$'}old i32)
                (param ${'$'}old-align i32)
                (param ${'$'}align i32)
                (param ${'$'}size i32)
                (result i32)
                (local ${'$'}ptr i32)
                global.get ${'$'}heap
                local.set ${'$'}ptr
                global.get ${'$'}heap
                local.get ${'$'}size
                i32.add
                i32.const 7
                i32.add
                i32.const -8
                i32.and
                global.set ${'$'}heap
                local.get ${'$'}ptr
              )

              (func (export "_initialize"))
              (func (export "cabi_post_run") (param i32))

              (func ${'$'}finish (param ${'$'}tag i32)
                local.get ${'$'}tag
                call ${'$'}task-return-run
              )

              (func ${'$'}fail
                i32.const 1
                call ${'$'}finish
              )

              (func ${'$'}succeed
                i32.const 0
                call ${'$'}finish
              )

              (func ${'$'}await-open-at
                (param ${'$'}params i32)
                (param ${'$'}result i32)
                (result i32)
                (local ${'$'}packed i32)
                (local ${'$'}status i32)
                (local ${'$'}handle i32)
                (local ${'$'}set i32)
                local.get ${'$'}params
                local.get ${'$'}result
                call ${'$'}open-at
                local.tee ${'$'}packed
                i32.const 15
                i32.and
                local.set ${'$'}status
                local.get ${'$'}packed
                i32.const 4
                i32.shr_u
                local.set ${'$'}handle

                block ${'$'}done
                  loop ${'$'}again
                    local.get ${'$'}status
                    i32.const 2
                    i32.eq
                    br_if ${'$'}done

                    local.get ${'$'}status
                    i32.const 3
                    i32.ge_u
                    if
                      i32.const 700
                      return
                    end

                    local.get ${'$'}set
                    i32.eqz
                    if
                      call ${'$'}waitable-set-new
                      local.set ${'$'}set
                      local.get ${'$'}handle
                      local.get ${'$'}set
                      call ${'$'}waitable-join
                    end

                    local.get ${'$'}set
                    i32.const 3000
                    call ${'$'}waitable-set-wait
                    i32.const 1
                    i32.ne
                    if
                      i32.const 701
                      return
                    end

                    i32.const 3000
                    i32.load
                    local.get ${'$'}handle
                    i32.ne
                    if
                      i32.const 702
                      return
                    end

                    i32.const 3004
                    i32.load
                    local.set ${'$'}status
                    br ${'$'}again
                  end
                end

                local.get ${'$'}set
                if
                  local.get ${'$'}handle
                  if
                    local.get ${'$'}handle
                    call ${'$'}subtask-drop
                  end
                  local.get ${'$'}set
                  call ${'$'}waitable-set-drop
                end
                i32.const 0
              )

              (func ${'$'}create-file-at
                (param ${'$'}descriptor i32)
                (param ${'$'}path i32)
                (param ${'$'}path-len i32)
                (param ${'$'}params i32)
                (param ${'$'}result i32)
                (result i32)
                (local ${'$'}await-result i32)

                local.get ${'$'}params
                local.get ${'$'}descriptor
                i32.store

                local.get ${'$'}params
                i32.const 4
                i32.add
                i32.const 0
                i32.store8

                local.get ${'$'}params
                i32.const 8
                i32.add
                local.get ${'$'}path
                i32.store

                local.get ${'$'}params
                i32.const 12
                i32.add
                local.get ${'$'}path-len
                i32.store

                local.get ${'$'}params
                i32.const 16
                i32.add
                i32.const 1
                i32.store8

                local.get ${'$'}params
                i32.const 17
                i32.add
                i32.const 2
                i32.store8

                local.get ${'$'}params
                local.get ${'$'}result
                call ${'$'}await-open-at
                local.tee ${'$'}await-result
                if
                  local.get ${'$'}await-result
                  return
                end
                i32.const 0
              )

              (func (export "[async-lift-stackful]wasi:cli/run@0.3.0#run")
                (local ${'$'}list-ptr i32)
                (local ${'$'}list-len i32)
                (local ${'$'}root-descriptor i32)
                (local ${'$'}cache-descriptor i32)
                (local ${'$'}operation-result i32)

                i32.const 2000
                call ${'$'}get-directories
                i32.const 2000
                i32.load
                local.set ${'$'}list-ptr
                i32.const 2004
                i32.load
                local.set ${'$'}list-len
                local.get ${'$'}list-len
                i32.const 2
                i32.lt_u
                if
                  call ${'$'}fail
                  return
                end

                local.get ${'$'}list-ptr
                i32.load
                local.set ${'$'}root-descriptor
                local.get ${'$'}list-ptr
                i32.const 12
                i32.add
                i32.load
                local.set ${'$'}cache-descriptor

                local.get ${'$'}root-descriptor
                i32.const 1024
                i32.const 11
                i32.const 2100
                i32.const 2120
                call ${'$'}create-file-at
                local.tee ${'$'}operation-result
                if
                  call ${'$'}fail
                  return
                end

                local.get ${'$'}cache-descriptor
                i32.const 1040
                i32.const $cachePathLength
                i32.const 2200
                i32.const 2220
                call ${'$'}create-file-at
                local.tee ${'$'}operation-result
                if
                  call ${'$'}fail
                  return
                end

                $cacheWriteAssertion
                local.get ${'$'}root-descriptor
                call ${'$'}drop-descriptor
                local.get ${'$'}cache-descriptor
                call ${'$'}drop-descriptor

                call ${'$'}succeed
              )
            )
        """.trimIndent()
        }

        private fun String.watStringLiteralBody(): String {
            require(all { char -> char.code in 0x20..0x7e && char != '"' && char != '\\' }) {
                "Only simple ASCII WAT string literals are supported in this test"
            }
            return this
        }
    }
}
