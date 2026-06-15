package uk.shusek.krwa.component

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasm.Parser

class WasmPluginTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun usesCompiledMachineForJvmPluginModules() {
        val previous = System.getProperty("krwa.component.compiler")
        try {
            System.setProperty("krwa.component.compiler", "true")
            val witPackage =
                WitPackage.parse(
                    """
                    package example:compiled-machine;

                    world plugin {
                      export api;
                    }

                    interface api {
                      value: func() -> u32;
                    }
                    """
                        .trimIndent()
                )

            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (memory (export "memory") 1)
                              (func (export "api.value") (result i32)
                                (i32.const 42))
                            )
                            """
                                .trimIndent()
                        )
                    )
                    .build()

            assertEquals(42L, plugin.call("api.value"))
            assertFalse(plugin.instance().getMachine() is InterpreterMachine)
        } finally {
            if (previous == null) {
                System.clearProperty("krwa.component.compiler")
            } else {
                System.setProperty("krwa.component.compiler", previous)
            }
        }
    }

    @Test
    fun builderCanAttachUnsafeExecutionListenerToPluginInstance() {
        val previous = System.getProperty("krwa.component.compiler")
        try {
            System.setProperty("krwa.component.compiler", "false")
            val witPackage =
                WitPackage.parse(
                    """
                    package example:plugin-execution-listener;

                    world plugin {
                      export api;
                    }

                    interface api {
                      value: func() -> u32;
                    }
                    """
                        .trimIndent()
                )
            var instructions = 0

            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (memory (export "memory") 1)
                              (func (export "api.value") (result i32)
                                (i32.const 40)
                                (i32.const 2)
                                (i32.add))
                            )
                            """
                                .trimIndent()
                        )
                    )
                    .withUnsafeExecutionListener { _, _ -> instructions += 1 }
                    .build()

            assertEquals(42L, plugin.call("api.value"))
            assertTrue(instructions > 0)
        } finally {
            if (previous == null) {
                System.clearProperty("krwa.component.compiler")
            } else {
                System.setProperty("krwa.component.compiler", previous)
            }
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty()) {
            return true
        }
        if (needle.size > size) {
            return false
        }
        for (offset in 0..(size - needle.size)) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return true
            }
        }
        return false
    }

    @Test
    fun validatesCatchRefHandlersTargetingNonNullableExceptionReferences() {
        Parser.parse(
            Wat2Wasm.parse(
                """
                (module
                  (tag ${'$'}e)

                  (func (export "catch-ref")
                    (block ${'$'}h (result (ref exn))
                      (try_table (catch_ref ${'$'}e ${'$'}h)
                        (throw ${'$'}e)
                      )
                      (unreachable)
                    )
                    drop)

                  (func (export "catch-all-ref")
                    (block ${'$'}h (result (ref exn))
                      (try_table (catch_all_ref ${'$'}h)
                        (throw ${'$'}e)
                      )
                      (unreachable)
                    )
                    drop)
                )
                """
                    .trimIndent()
            )
        )
    }

    @Test
    fun loadsWitWorldWithHostImportsAndExportedInterface() {
        val witPackage =
            WitPackage.parse(
                String(
                    javaClass.getResourceAsStream("/plugin.wit")!!.readAllBytes(),
                    StandardCharsets.UTF_8,
                )
            )
        val observedContentType = AtomicReference<String>()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withWorld("plugin")
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "host" "log"
                            (func ${'$'}log (param i32 i32 i32)))
                          (memory (export "memory") 1)
                          (data (i32.const 128) "\01\02")
                          (func (export "canonical_abi_realloc")
                            (param ${'$'}old i32) (param ${'$'}old_size i32)
                            (param ${'$'}align i32) (param ${'$'}new_size i32)
                            (result i32)
                            (i32.const 256))
                          (func (export "export")
                            (param ${'$'}body_ptr i32) (param ${'$'}body_len i32)
                            (param ${'$'}content_type_ptr i32)
                            (param ${'$'}content_type_len i32)
                            (result i32)
                            (call ${'$'}log
                              (i32.const 1)
                              (local.get ${'$'}content_type_ptr)
                              (local.get ${'$'}content_type_len))
                            (i32.store8 (i32.const 80) (i32.const 0))
                            (i32.store8 (i32.const 84) (i32.const 0))
                            (i32.store (i32.const 88) (i32.const 128))
                            (i32.store (i32.const 92) (i32.const 2))
                            (i32.const 80))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("host", "log") { arguments ->
                    assertEquals(1, arguments[0])
                    observedContentType.set(arguments[1] as String)
                    null
                }
                .build()

        val result =
            plugin.call(
                "transform.export",
                WitValue.record("body", byteArrayOf(9, 9), "content-type", "application/test"),
            ) as WitValue.Variant
        val response = result.value() as WitValue.Variant

        assertEquals("application/test", observedContentType.get())
        assertEquals("ok", result.label())
        assertEquals("ok", response.label())
        assertArrayEquals(byteArrayOf(1, 2), response.value() as ByteArray)
    }

    @Test
    fun linksAsyncLoweredWorldHostImport() {
        val witPackage =
            WitPackage.parse(
                """
                package example:async-lower-import;

                world plugin {
                  import seed: async func(value: u32) -> u32;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "plugin" "[async-lower]seed"
                            (func ${'$'}seed (param i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (local ${'$'}status i32)
                            (local.set ${'$'}status
                              (call ${'$'}seed (i32.const 41) (i32.const 32)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                              (then unreachable))
                            (i32.load (i32.const 32)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("plugin", "seed") { arguments ->
                    (arguments[0] as Number).toLong() + 1
                }
                .build()

        assertEquals(42L, plugin.call("api.run"))
    }

    @Test
    fun linksAsyncLoweredWorldHostImportWithParameterArea() {
        val witPackage =
            WitPackage.parse(
                """
                package example:async-lower-params;

                world plugin {
                  type pair = tuple<u32, u32>;
                  import sum: async func(input: pair) -> u32;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "plugin" "[async-lower]sum"
                            (func ${'$'}sum (param i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (local ${'$'}status i32)
                            (i32.store (i32.const 64) (i32.const 7))
                            (i32.store (i32.const 68) (i32.const 35))
                            (local.set ${'$'}status
                              (call ${'$'}sum (i32.const 64) (i32.const 96)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                              (then unreachable))
                            (i32.load (i32.const 96)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("plugin", "sum") { arguments ->
                    val pair = arguments[0] as List<*>
                    (pair[0] as Number).toLong() + (pair[1] as Number).toLong()
                }
                .build()

        assertEquals(42L, plugin.call("api.run"))
    }

    @Test
    fun resolvesWorldAliasPayloadForCanonicalStreamIntrinsics() {
        val witPackage =
            WitPackage.parse(
                """
                package example:stream-alias;

                world plugin {
                  type byte = u8;
                  import source: func() -> stream<byte>;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val buffer = ByteArrayOutputStream()
        var writableDropped = false
        val streams =
            object : CanonicalStreamIntrinsics {
                override fun streamNew(payloadType: WitPackage.TypeRef): Long {
                    assertBytePayload(payloadType)
                    return (2L shl 32) or 1L
                }

                override fun streamRead(
                    instance: Instance,
                    streamHandle: Long,
                    ptr: Int,
                    len: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(1L, streamHandle)
                    assertBytePayload(payloadType)
                    val bytes = buffer.toByteArray().copyOf(len.coerceAtMost(buffer.size()))
                    instance.memory().write(ptr, bytes)
                    return ((bytes.size.toLong() shl 4) or if (writableDropped) 1L else 0L)
                }

                override fun streamWrite(
                    instance: Instance,
                    streamHandle: Long,
                    ptr: Int,
                    len: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(2L, streamHandle)
                    assertBytePayload(payloadType)
                    buffer.write(instance.memory().readBytes(ptr, len))
                    return len.toLong() shl 4
                }

                override fun streamCancelRead(streamHandle: Long): Long = 2L

                override fun streamCancelWrite(streamHandle: Long): Long = 2L

                override fun streamDropReadable(streamHandle: Long) {
                    assertEquals(1L, streamHandle)
                }

                override fun streamDropWritable(streamHandle: Long) {
                    assertEquals(2L, streamHandle)
                    writableDropped = true
                }

                private fun assertBytePayload(payloadType: WitPackage.TypeRef) {
                    assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
                    assertEquals("u8", payloadType.name())
                }
            }
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "plugin" "[stream-new-0]source"
                            (func ${'$'}stream_new (result i64)))
                          (import "plugin" "[async-lower][stream-write-0]source"
                            (func ${'$'}stream_write (param i32 i32 i32) (result i32)))
                          (import "plugin" "[stream-drop-writable-0]source"
                            (func ${'$'}drop_writable (param i32)))
                          (import "plugin" "[async-lower][stream-read-0]source"
                            (func ${'$'}stream_read (param i32 i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (data (i32.const 32) "\03\04\05")
                          (func ${'$'}run (result i32)
                            (local ${'$'}pair i64)
                            (local ${'$'}reader i32)
                            (local ${'$'}writer i32)
                            (local ${'$'}status i32)
                            (local.set ${'$'}pair (call ${'$'}stream_new))
                            (local.set ${'$'}reader (i32.wrap_i64 (local.get ${'$'}pair)))
                            (local.set ${'$'}writer
                              (i32.wrap_i64
                                (i64.shr_u (local.get ${'$'}pair) (i64.const 32))))
                            (local.set ${'$'}status
                              (call ${'$'}stream_write
                                (local.get ${'$'}writer)
                                (i32.const 32)
                                (i32.const 3)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 48))
                              (then unreachable))
                            (call ${'$'}drop_writable (local.get ${'$'}writer))
                            (local.set ${'$'}status
                              (call ${'$'}stream_read
                                (local.get ${'$'}reader)
                                (i32.const 64)
                                (i32.const 3)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 49))
                              (then unreachable))
                            (i32.add
                              (i32.add
                                (i32.add
                                  (local.get ${'$'}status)
                                  (i32.load8_u (i32.const 64)))
                                (i32.load8_u (i32.const 65)))
                              (i32.load8_u (i32.const 66))))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("plugin", "source") {
                    throw AssertionError("source host function should not be called")
                }
                .withCanonicalStreamIntrinsics(streams)
                .build()

        assertEquals(61L, plugin.call("api.run"))
    }

    @Test
    fun resolvesWorldAliasPayloadForCanonicalFutureIntrinsics() {
        val witPackage =
            WitPackage.parse(
                """
                package example:future-alias;

                world plugin {
                  type count = u32;
                  import seed: func() -> future<count>;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        var value = 0L
        val futures =
            object : CanonicalFutureIntrinsics {
                override fun futureNew(): Long = (2L shl 32) or 1L

                override fun futureRead(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(1L, futureHandle)
                    assertCountPayload(payloadType)
                    instance.memory().writeI32(ptr, value.toInt())
                    return 0L
                }

                override fun futureWrite(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(2L, futureHandle)
                    assertCountPayload(payloadType)
                    value = instance.memory().readI32(ptr)
                    return 0L
                }

                override fun futureCancelRead(futureHandle: Long): Long = 2L

                override fun futureCancelWrite(futureHandle: Long): Long = 2L

                override fun futureDropReadable(futureHandle: Long) {
                    assertEquals(1L, futureHandle)
                }

                override fun futureDropWritable(futureHandle: Long) {
                    assertEquals(2L, futureHandle)
                }

                private fun assertCountPayload(payloadType: WitPackage.TypeRef) {
                    assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
                    assertEquals("u32", payloadType.name())
                }
            }
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "plugin" "[future-new-0]seed"
                            (func ${'$'}future_new (result i64)))
                          (import "plugin" "[async-lower][future-write-0]seed"
                            (func ${'$'}future_write (param i32 i32) (result i32)))
                          (import "plugin" "[async-lower][future-read-0]seed"
                            (func ${'$'}future_read (param i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (local ${'$'}pair i64)
                            (local ${'$'}reader i32)
                            (local ${'$'}writer i32)
                            (local ${'$'}status i32)
                            (local.set ${'$'}pair (call ${'$'}future_new))
                            (local.set ${'$'}reader (i32.wrap_i64 (local.get ${'$'}pair)))
                            (local.set ${'$'}writer
                              (i32.wrap_i64
                                (i64.shr_u (local.get ${'$'}pair) (i64.const 32))))
                            (i32.store (i32.const 32) (i32.const 77))
                            (local.set ${'$'}status
                              (call ${'$'}future_write
                                (local.get ${'$'}writer)
                                (i32.const 32)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                              (then unreachable))
                            (local.set ${'$'}status
                              (call ${'$'}future_read
                                (local.get ${'$'}reader)
                                (i32.const 64)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                              (then unreachable))
                            (i32.load (i32.const 64)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("plugin", "seed") {
                    throw AssertionError("seed host function should not be called")
                }
                .withCanonicalFutureIntrinsics(futures)
                .build()

        assertEquals(77L, plugin.call("api.run"))
    }

    @Test
    fun lowersCompletedFuturePayloadsReturnedByHostImports() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host-future;

                world plugin {
                  import client;
                  export api;
                }

                interface client {
                  resource response;
                  send: func() -> future<result<response, string>>;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        var completedValue: Any? = null
        val futures =
            object : CanonicalFutureIntrinsics {
                override fun completedFutureHandle(value: Any?): Long {
                    completedValue = value
                    return 7L
                }

                override fun futureNew(): Long = throw AssertionError("future.new should not be used")

                override fun futureRead(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(7L, futureHandle)
                    abi.storeValues(
                        CanonicalAbi.Context.forInstance(instance),
                        ptr,
                        listOf(WitPackage.Field("result", payloadType)),
                        listOf(completedValue),
                    )
                    return 0L
                }

                override fun futureWrite(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long = throw AssertionError("future.write should not be used")

                override fun futureCancelRead(futureHandle: Long): Long = 0L

                override fun futureCancelWrite(futureHandle: Long): Long = 0L

                override fun futureDropReadable(futureHandle: Long) = Unit

                override fun futureDropWritable(futureHandle: Long) = Unit
            }
        val d = '$'
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "client" "send" (func ${d}send (result i32)))
                          (import "client" "[async-lower][future-read-0]send"
                            (func ${d}future_read (param i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (func ${d}run (result i32)
                            (local ${d}future i32)
                            (local.set ${d}future (call ${d}send))
                            (if
                              (i32.ne
                                (call ${d}future_read (local.get ${d}future) (i32.const 64))
                                (i32.const 0))
                              (then (return (i32.const 90))))
                            (if
                              (i32.ne (i32.load8_u (i32.const 64)) (i32.const 0))
                              (then (return (i32.const 91))))
                            (i32.load (i32.const 68)))
                          (export "api.run" (func ${d}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("client", "send") {
                    WitResult.ok(WitResource<Any>(42))
                }
                .withCanonicalFutureIntrinsics(futures)
                .build()

        assertEquals(42L, plugin.call("api.run"))
    }

    @Test
    fun lowersCompletedFutureResultErrVariantWithOptionStringPayload() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host-future-error;

                world plugin {
                  import client;
                  export api;
                }

                interface client {
                  resource response;
                  variant error-code {
                    HTTP-request-denied,
                    HTTP-request-URI-invalid,
                    HTTP-request-method-invalid,
                    connection-refused,
                    connection-timeout,
                    internal-error(option<string>),
                  }
                  send: func() -> future<result<response, error-code>>;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        var completedValue: Any? = null
        val futures =
            object : CanonicalFutureIntrinsics {
                override fun completedFutureHandle(value: Any?): Long {
                    completedValue = value
                    return 7L
                }

                override fun futureNew(): Long = throw AssertionError("future.new should not be used")

                override fun futureRead(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long {
                    assertEquals(7L, futureHandle)
                    abi.storeValues(
                        CanonicalAbi.Context.forInstance(instance),
                        ptr,
                        listOf(WitPackage.Field("result", payloadType)),
                        listOf(completedValue),
                    )
                    return 0L
                }

                override fun futureWrite(
                    instance: Instance,
                    futureHandle: Long,
                    ptr: Int,
                    abi: CanonicalAbi,
                    payloadType: WitPackage.TypeRef,
                ): Long = throw AssertionError("future.write should not be used")

                override fun futureCancelRead(futureHandle: Long): Long = 0L

                override fun futureCancelWrite(futureHandle: Long): Long = 0L

                override fun futureDropReadable(futureHandle: Long) = Unit

                override fun futureDropWritable(futureHandle: Long) = Unit
            }
        val d = '$'
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "client" "send" (func ${d}send (result i32)))
                          (import "client" "[async-lower][future-read-0]send"
                            (func ${d}future_read (param i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (global ${d}heap (mut i32) (i32.const 1024))
                          (func ${d}realloc
                            (param ${d}old i32) (param ${d}old_size i32)
                            (param ${d}align i32) (param ${d}new_size i32)
                            (result i32)
                            (local ${d}ptr i32)
                            (local.set ${d}ptr (global.get ${d}heap))
                            (global.set ${d}heap
                              (i32.add (global.get ${d}heap) (local.get ${d}new_size)))
                            (local.get ${d}ptr))
                          (export "canonical_abi_realloc" (func ${d}realloc))
                          (func ${d}run (result i32)
                            (local ${d}future i32)
                            (local ${d}message i32)
                            (local.set ${d}future (call ${d}send))
                            (if
                              (i32.ne
                                (call ${d}future_read (local.get ${d}future) (i32.const 64))
                                (i32.const 0))
                              (then (return (i32.const 90))))
                            (if
                              (i32.ne (i32.load8_u (i32.const 64)) (i32.const 1))
                              (then (return (i32.const 91))))
                            (if
                              (i32.ne (i32.load8_u (i32.const 68)) (i32.const 5))
                              (then (return (i32.const 92))))
                            (if
                              (i32.ne (i32.load8_u (i32.const 72)) (i32.const 1))
                              (then (return (i32.const 93))))
                            (if
                              (i32.ne (i32.load (i32.const 80)) (i32.const 4))
                              (then (return (i32.const 94))))
                            (local.set ${d}message (i32.load (i32.const 76)))
                            (if
                              (i32.ne (i32.load8_u (local.get ${d}message)) (i32.const 98))
                              (then (return (i32.const 95))))
                            (if
                              (i32.ne (i32.load8_u (i32.add (local.get ${d}message) (i32.const 1))) (i32.const 111))
                              (then (return (i32.const 96))))
                            (if
                              (i32.ne (i32.load8_u (i32.add (local.get ${d}message) (i32.const 2))) (i32.const 111))
                              (then (return (i32.const 97))))
                            (if
                              (i32.ne (i32.load8_u (i32.add (local.get ${d}message) (i32.const 3))) (i32.const 109))
                              (then (return (i32.const 98))))
                            (i32.const 123))
                          (export "api.run" (func ${d}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("client", "send") {
                    WitResult.err(WitValue.variant("internal-error", "boom"))
                }
                .withCanonicalFutureIntrinsics(futures)
                .build()

        assertEquals(123L, plugin.call("api.run"))
    }

    @Test
    fun loadsComponentArtifactByUnbundlingCoreModule() {
        val witPath = tempDir.resolve("plugin.wit")
        val corePath = tempDir.resolve("plugin.core.wasm")
        val embeddedPath = tempDir.resolve("plugin.embedded.wasm")
        val componentPath = tempDir.resolve("plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:component;
            interface api {
              len: func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func (export "canonical_abi_realloc")
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (local.get ${'$'}len))
                  (export "len" (func ${'$'}len))
                  (export "api.len" (func ${'$'}len))
                  (export "api#len" (func ${'$'}len))
                  (export "api/len" (func ${'$'}len))
                  (export "example:component/api#len" (func ${'$'}len))
                )
                """
                    .trimIndent()
            ),
        )
        Files.write(
            embeddedPath,
            WasmComponentTools.embedWit(witPath.toOkioPath(), "plugin", corePath.toOkioPath()),
        )
        Files.write(componentPath, WasmComponentTools.componentNew(embeddedPath.toOkioPath()))

        val plugin = WasmPlugin.builderFromComponent(componentPath.toOkioPath()).build()

        assertEquals(5L, plugin.call("api.len", "hello"))
    }

    @Test
    fun packagesComponentArtifactWithCli() {
        val witPath = tempDir.resolve("cli-plugin.wit")
        val corePath = tempDir.resolve("cli-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/cli-plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:component;
            interface api {
              len: func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func (export "canonical_abi_realloc")
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (local.get ${'$'}len))
                  (export "len" (func ${'$'}len))
                  (export "api.len" (func ${'$'}len))
                  (export "example:component/api#len" (func ${'$'}len))
                )
                """
                    .trimIndent()
            ),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))

        val plugin = WasmPlugin.builderFromComponent(componentPath.toOkioPath()).build()

        assertEquals(6L, plugin.call("api.len", "kotlin"))
    }

    @Test
    fun packagesAsyncCallbackComponentArtifactWithCli() {
        val witPath = tempDir.resolve("async-callback-plugin.wit")
        val corePath = tempDir.resolve("async-callback-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/async-callback-plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:async-callback;
            interface api {
              len: func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func (export "canonical_abi_realloc")
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (local.get ${'$'}len))
                  (export "len" (func ${'$'}len))
                  (export "api.len" (func ${'$'}len))
                  (export "example:async-callback/api#len" (func ${'$'}len))
                )
                """
                    .trimIndent()
            ),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                    "--async-callback",
                    "--skip-validate",
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))
        assertEquals(true, Files.size(componentPath) > 0)

        val plugin = WasmPlugin.builderFromComponent(componentPath.toOkioPath()).build()

        assertEquals(6L, plugin.call("api.len", "kotlin"))
    }

    @Test
    fun packagesAsyncCallbackComponentWithOriginalCoreModule() {
        val witPath = tempDir.resolve("async-callback-original-core.wit")
        val corePath = tempDir.resolve("async-callback-original-core.core.wasm")
        val componentPath = tempDir.resolve("generated/async-callback-original-core.component.wasm")
        val marker = "real-core-marker"
        Files.writeString(
            witPath,
            """
            package example:async-export;
            interface api {
              len: async func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "[export]example:async-export/api" "[task-return][async]len"
                    (func ${'$'}task_return_len (param i32)))
                  (memory (export "memory") 1)
                  (data (i32.const 2048) "$marker")
                  (func ${'$'}realloc
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (i32.const 1024))
                  (export "canonical_abi_realloc" (func ${'$'}realloc))
                  (export "cabi_realloc" (func ${'$'}realloc))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (call ${'$'}task_return_len (local.get ${'$'}len))
                    (i32.const 0))
                  (func ${'$'}callback
                    (param ${'$'}event i32) (param ${'$'}payload1 i32) (param ${'$'}payload2 i32)
                    (result i32)
                    unreachable)
                  (export "[async-lift]example:async-export/api#[async]len" (func ${'$'}len))
                  (export "[callback][async-lift]example:async-export/api#[async]len"
                    (func ${'$'}callback))
                )
                """
                    .trimIndent()
            ),
        )

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                    "--async-callback",
                ),
                PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            ),
        )

        val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
        val unbundled = WasmComponentTools.unbundleComponent(componentPath.toOkioPath())
        assertTrue(
            unbundled.modules().values.any { module -> module.containsBytes(markerBytes) },
            "packaged component should contain the original core module",
        )
    }

    @Test
    fun packagesPreview1RandomGetThroughBundledPreview1Adapter() {
        val witPath = tempDir.resolve("preview1-random-plugin.wit")
        val corePath = tempDir.resolve("preview1-random-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/preview1-random-plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:preview1-random;
            interface api {
              run: func() -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "wasi_snapshot_preview1" "random_get"
                    (func ${'$'}random_get (param i32) (param i32) (result i32)))
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func ${'$'}realloc
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr
                      (i32.and
                        (i32.add
                          (global.get ${'$'}heap)
                          (i32.sub (local.get ${'$'}align) (i32.const 1)))
                        (i32.xor
                          (i32.sub (local.get ${'$'}align) (i32.const 1))
                          (i32.const -1))))
                    (global.set ${'$'}heap
                      (i32.add (local.get ${'$'}ptr) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (export "canonical_abi_realloc" (func ${'$'}realloc))
                  (export "cabi_realloc" (func ${'$'}realloc))
                  (func ${'$'}run (result i32)
                    (local ${'$'}errno i32)
                    (local.set ${'$'}errno (call ${'$'}random_get (i32.const 128) (i32.const 4)))
                    (if (i32.ne (local.get ${'$'}errno) (i32.const 0))
                      (then (return (local.get ${'$'}errno))))
                    (i32.add
                      (i32.add
                        (i32.load8_u (i32.const 128))
                        (i32.load8_u (i32.const 129)))
                      (i32.add
                        (i32.load8_u (i32.const 130))
                        (i32.load8_u (i32.const 131)))))
                  (export "run" (func ${'$'}run))
                  (export "api.run" (func ${'$'}run))
                  (export "example:preview1-random/api#run" (func ${'$'}run))
                )
                """
                    .trimIndent()
            ),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))

        val componentWit = Wit.normalize(componentPath.toOkioPath())
        assertEquals(
            true,
            componentWit.contains("import wasi:random/random@"),
            componentWit,
        )
        assertEquals(false, componentWit.contains("wasi_snapshot_preview1"), componentWit)

        val plugin =
            WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                .withWasiPreview3(
                    WasiPreview3.builder()
                        .withSecureRandom(kotlin.random.Random(1234L))
                        .build()
                )
                .build()
        assertTrue((plugin.call("api.run") as Long) in 1L..1020L)
    }

    @Test
    fun packagesPreview1PollOneoffThroughBundledPreview1Adapter() {
        val witPath = tempDir.resolve("preview1-poll-plugin.wit")
        val corePath = tempDir.resolve("preview1-poll-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/preview1-poll-plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:preview1-poll;
            interface api {
              run: func() -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "wasi_snapshot_preview1" "poll_oneoff"
                    (func ${'$'}poll_oneoff
                      (param i32) (param i32) (param i32) (param i32)
                      (result i32)))
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func ${'$'}realloc
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (export "canonical_abi_realloc" (func ${'$'}realloc))
                  (export "cabi_realloc" (func ${'$'}realloc))
                  (func ${'$'}run (result i32)
                    (i32.const 7))
                  (export "run" (func ${'$'}run))
                  (export "api.run" (func ${'$'}run))
                  (export "example:preview1-poll/api#run" (func ${'$'}run))
                )
                """
                    .trimIndent()
            ),
        )

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                ),
                PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            ),
        )

        val componentWit = Wit.normalize(componentPath.toOkioPath())
        assertEquals(true, componentWit.contains("import wasi:io/poll@"), componentWit)
        assertEquals(false, componentWit.contains("wasi_snapshot_preview1"), componentWit)

        val plugin =
            WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                .withWasiPreview3(WasiPreview3.builder().build())
                .build()
        assertEquals(7L, plugin.call("api.run"))
    }

    @Test
    fun loadsVersionedPreview1AdapterImportsWithPreview1HostFunctions() {
        val stdout = ByteArrayOutputStream()
        val witPackage =
            WitPackage.parse(
                """
                package example:preview1-versioned;
                interface api {
                  run: func() -> u32;
                }
                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "wasi_snapshot_preview1" "fd_write [v2]"
                            (func ${'$'}fd_write (param i32 i32 i32 i32) (result i32)))
                          (memory (export "memory") 1)
                          (data (i32.const 64) "ok")
                          (func ${'$'}run (result i32)
                            (i32.store (i32.const 32) (i32.const 64))
                            (i32.store (i32.const 36) (i32.const 2))
                            (if
                              (i32.ne
                                (call ${'$'}fd_write
                                  (i32.const 1)
                                  (i32.const 32)
                                  (i32.const 1)
                                  (i32.const 48))
                                (i32.const 0))
                              (then (return (i32.const 90))))
                            (i32.load (i32.const 48)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withWasiPreview1(
                    WasiPreview1.builder()
                        .withOptions(WasiOptions.builder().withStdout(stdout).build())
                        .build()
                )
                .build()

        assertEquals(2L, plugin.call("api.run"))
        assertEquals("ok", stdout.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun packagesAsyncCallbackComponentWithBundledPreview1Adapter() {
        val witPath = tempDir.resolve("async-random-plugin.wit")
        val corePath = tempDir.resolve("async-random-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/async-random-plugin.component.wasm")
        val marker = "real-random-core-marker"
        Files.writeString(
            witPath,
            """
            package example:async-random;
            interface api {
              len: async func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "wasi_snapshot_preview1" "random_get"
                    (func ${'$'}random_get (param i32) (param i32) (result i32)))
                  (import "${'$'}root" "[context-get-0]"
                    (func ${'$'}context_get (result i32)))
                  (import "${'$'}root" "[context-set-0]"
                    (func ${'$'}context_set (param i32)))
                  (import "[export]example:async-random/api" "[task-return][async]len"
                    (func ${'$'}task_return_len (param i32)))
                  (memory (export "memory") 1)
                  (data (i32.const 2048) "$marker")
                  (func ${'$'}realloc
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (i32.const 1024))
                  (export "canonical_abi_realloc" (func ${'$'}realloc))
                  (export "cabi_realloc" (func ${'$'}realloc))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (drop (call ${'$'}random_get (i32.const 128) (i32.const 1)))
                    (call ${'$'}context_set (call ${'$'}context_get))
                    (call ${'$'}task_return_len (local.get ${'$'}len))
                    (i32.const 0))
                  (func ${'$'}callback
                    (param ${'$'}event i32) (param ${'$'}payload1 i32) (param ${'$'}payload2 i32)
                    (result i32)
                    unreachable)
                  (export "[async-lift]example:async-random/api#[async]len" (func ${'$'}len))
                  (export "[callback][async-lift]example:async-random/api#[async]len"
                    (func ${'$'}callback))
                )
                """
                    .trimIndent()
            ),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                    "--async-callback",
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )

        val componentWit = Wit.normalize(componentPath.toOkioPath())
        assertEquals(
            true,
            componentWit.contains("import wasi:random/random@"),
            componentWit,
        )
        val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
        val unbundled = WasmComponentTools.unbundleComponent(componentPath.toOkioPath())
        assertTrue(
            unbundled.modules().values.any { module -> module.containsBytes(markerBytes) },
            "packaged component should contain the original core module",
        )
        val plugin =
            WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                .withWasiPreview1(WasiPreview1.builder().build())
                .build()
        assertEquals(6L, plugin.call("api.len", "kotlin"))
    }

    @Test
    fun packagesAsyncWitExportWithCallbackTaskReturnAbi() {
        val witPath = tempDir.resolve("async-export-plugin.wit")
        val corePath = tempDir.resolve("async-export-plugin.core.wasm")
        val componentPath = tempDir.resolve("generated/async-export-plugin.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:async-export;
            interface api {
              len: async func(input: string) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "[export]example:async-export/api" "[task-return][async]len"
                    (func ${'$'}task_return_len (param i32)))
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (global ${'$'}initialized (mut i32) (i32.const 0))
                  (func ${'$'}realloc
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (export "canonical_abi_realloc" (func ${'$'}realloc))
                  (export "cabi_realloc" (func ${'$'}realloc))
                  (func ${'$'}init
                    (global.set ${'$'}initialized (i32.const 40)))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (call ${'$'}task_return_len
                      (i32.add (global.get ${'$'}initialized) (local.get ${'$'}len)))
                    (i32.const 0))
                  (func ${'$'}callback
                    (param ${'$'}event i32) (param ${'$'}payload1 i32) (param ${'$'}payload2 i32)
                    (result i32)
                    unreachable)
                  (export "krwa_guest_init" (func ${'$'}init))
                  (export "[async-lift]example:async-export/api#[async]len" (func ${'$'}len))
                  (export "[callback][async-lift]example:async-export/api#[async]len"
                    (func ${'$'}callback))
                )
                """
                    .trimIndent()
            ),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            WasmComponentPackager.run(
                arrayOf(
                    "--wit",
                    witPath.toString(),
                    "--world",
                    "plugin",
                    "--core",
                    corePath.toString(),
                    "--out",
                    componentPath.toString(),
                    "--async-callback",
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))
        assertEquals(true, Files.size(componentPath) > 0)

        val plugin = WasmPlugin.builderFromComponent(componentPath.toOkioPath()).build()
        assertEquals(46L, plugin.call("api.len", "kotlin"))
    }

    @Test
    fun linksDuplicateDependencyInterfaceNamesByQualifiedPackage() {
        val witDir = tempDir.resolve("qualified-deps")
        Files.createDirectories(witDir.resolve("deps/http"))
        Files.createDirectories(witDir.resolve("deps/filesystem"))
        Files.writeString(
            witDir.resolve("plugin.wit"),
            """
            package example:qualified-deps@1.0.0;
            interface api {
              run: func() -> u32;
            }
            world plugin {
              import wasi:http/types@0.2.11;
              import wasi:filesystem/types@0.2.11;
              export api;
            }
            """
                .trimIndent(),
        )
        Files.writeString(
            witDir.resolve("deps/http/types.wit"),
            """
            package wasi:http@0.2.11;
            interface types {
              status: func() -> u32;
            }
            """
                .trimIndent(),
        )
        Files.writeString(
            witDir.resolve("deps/filesystem/types.wit"),
            """
            package wasi:filesystem@0.2.11;
            interface types {
              path: func() -> u32;
            }
            """
                .trimIndent(),
        )
        val witPackage = Wit.parse(witDir.toOkioPath())
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "wasi:http/types@0.2.11" "status"
                            (func ${'$'}status (result i32)))
                          (import "wasi:filesystem/types@0.2.11" "path"
                            (func ${'$'}path (result i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (i32.add (call ${'$'}status) (call ${'$'}path)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("wasi:http/types@0.2.11", "status") { 3 }
                .withHostImport("wasi:filesystem/types@0.2.11", "path") { 4 }
                .build()

        assertEquals(7L, plugin.call("api.run"))
    }

    @Test
    fun loadsComponentArtifactWithHostImports() {
        val witPath = tempDir.resolve("plugin-with-host.wit")
        val corePath = tempDir.resolve("plugin-with-host.core.wasm")
        val embeddedPath = tempDir.resolve("plugin-with-host.embedded.wasm")
        val componentPath = tempDir.resolve("plugin-with-host.component.wasm")
        Files.writeString(
            witPath,
            """
            package example:component;
            interface host {
              log: func(message: string);
            }
            interface api {
              run: func() -> u32;
            }
            world plugin {
              import host;
              export api;
            }
            """
                .trimIndent(),
        )
        Files.write(
            corePath,
            Wat2Wasm.parse(
                """
                (module
                  (import "example:component/host" "log"
                    (func ${'$'}log (param i32 i32)))
                  (memory (export "memory") 1)
                  (data (i32.const 16) "hello")
                  (func ${'$'}run (result i32)
                    (call ${'$'}log (i32.const 16) (i32.const 5))
                    (i32.const 7))
                  (export "run" (func ${'$'}run))
                  (export "api.run" (func ${'$'}run))
                  (export "api#run" (func ${'$'}run))
                  (export "api/run" (func ${'$'}run))
                  (export "example:component/api#run" (func ${'$'}run))
                )
                """
                    .trimIndent()
            ),
        )
        Files.write(
            embeddedPath,
            WasmComponentTools.embedWit(witPath.toOkioPath(), "plugin", corePath.toOkioPath()),
        )
        Files.write(componentPath, WasmComponentTools.componentNew(embeddedPath.toOkioPath()))

        val observed = AtomicReference<String>()
        val plugin =
            WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                .withHostImport("host", "log") { arguments ->
                    observed.set(arguments[0] as String)
                    null
                }
                .build()

        assertEquals(7L, plugin.call("api.run"))
        assertEquals("hello", observed.get())
    }

    @Test
    fun linksWorldInlineInterfaces() {
        val witPackage =
            WitPackage.parse(
                """
                package example:inline;
                world plugin {
                  import host: interface {
                    log: func(message: string);
                  }
                  export guest: interface {
                    scan: func(document: string) -> u32;
                  }
                }
                """
                    .trimIndent()
            )
        val observed = AtomicReference<String>()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "host" "log" (func ${'$'}log (param i32 i32)))
                          (memory (export "memory") 1)
                          (global ${'$'}heap (mut i32) (i32.const 64))
                          (func (export "canonical_abi_realloc")
                            (param ${'$'}old i32) (param ${'$'}old_size i32)
                            (param ${'$'}align i32) (param ${'$'}new_size i32)
                            (result i32)
                            (local ${'$'}ptr i32)
                            (local.set ${'$'}ptr (global.get ${'$'}heap))
                            (global.set ${'$'}heap
                              (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                            (local.get ${'$'}ptr))
                          (func ${'$'}scan
                            (param ${'$'}ptr i32)
                            (param ${'$'}len i32)
                            (result i32)
                            (call ${'$'}log (local.get ${'$'}ptr) (local.get ${'$'}len))
                            (local.get ${'$'}len))
                          (export "guest.scan" (func ${'$'}scan))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHostImport("host", "log") { arguments ->
                    observed.set(arguments[0] as String)
                    null
                }
                .build()

        assertEquals(5L, plugin.call("guest.scan", "hello"))
        assertEquals("hello", observed.get())
    }

    @Test
    fun bindsResourceFunctionsFromExportedInterface() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    constructor(seed: u32);
                    read: func(delta: u32) -> u32;
                    clone: static func(src: borrow<blob>) -> blob;
                  }
                }
                world plugin {
                  export db;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (func ${'$'}constructor
                            (param ${'$'}seed i32) (result i32)
                            (i32.add (local.get ${'$'}seed) (i32.const 10)))
                          (func ${'$'}read
                            (param ${'$'}self i32) (param ${'$'}delta i32) (result i32)
                            (i32.add (local.get ${'$'}self) (local.get ${'$'}delta)))
                          (func ${'$'}clone
                            (param ${'$'}src i32) (result i32)
                            (i32.add (local.get ${'$'}src) (i32.const 1)))
                          (export "[constructor]blob" (func ${'$'}constructor))
                          (export "[method]blob.read" (func ${'$'}read))
                          (export "[static]blob.clone" (func ${'$'}clone))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        assertEquals(15L, plugin.call("db.blob.constructor", 5))
        assertEquals(10L, plugin.call("db.blob.read", 7, 3))
        assertEquals(8L, plugin.call("db.blob.clone", 7))

        val db = plugin.exports(Db::class.java)
        val blob = db.blobConstructor(5)

        assertEquals(15L, blob.handle())
        assertEquals(18, db.blobRead(blob, 3))
        assertEquals(16L, db.blobClone(blob).handle())
    }

    @Test
    fun linksExportedResourceCanonicalIntrinsics() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    constructor(seed: u32);
                    read: func(delta: u32) -> u32;
                  }
                  close: func(value: blob);
                }
                world plugin {
                  export db;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "[export]example:resources/db" "[resource-new]blob"
                            (func ${'$'}new (param i32) (result i32)))
                          (import "[export]example:resources/db" "[resource-rep]blob"
                            (func ${'$'}rep (param i32) (result i32)))
                          (import "[export]example:resources/db" "[resource-drop]blob"
                            (func ${'$'}drop (param i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}constructor
                            (param ${'$'}seed i32) (result i32)
                            (call ${'$'}new (i32.add (local.get ${'$'}seed) (i32.const 10))))
                          (func ${'$'}read
                            (param ${'$'}self i32) (param ${'$'}delta i32) (result i32)
                            (i32.add
                              (call ${'$'}rep (local.get ${'$'}self))
                              (local.get ${'$'}delta)))
                          (func ${'$'}close (param ${'$'}self i32)
                            (call ${'$'}drop (local.get ${'$'}self)))
                          (export "example:resources/db#[constructor]blob" (func ${'$'}constructor))
                          (export "example:resources/db#[method]blob.read" (func ${'$'}read))
                          (export "example:resources/db#close" (func ${'$'}close))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val blob = plugin.call("db.blob.constructor", 5) as Long

        assertEquals(1L, blob)
        assertEquals(18L, plugin.call("db.blob.read", WitResource<Blob>(blob), 3))

        plugin.call("db.close", blob)

        assertThrows(ComponentModelException::class.java) {
            plugin.call("db.blob.read", WitResource<Blob>(blob), 3)
        }
    }

    @Test
    fun callsExportedResourceDestructorOnCanonicalDrop() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    constructor(seed: u32);
                  }
                  close: func(value: blob);
                  dropped: func() -> u32;
                }
                world plugin {
                  export db;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "[export]example:resources/db" "[resource-new]blob"
                            (func ${'$'}new (param i32) (result i32)))
                          (import "[export]example:resources/db" "[resource-drop]blob"
                            (func ${'$'}drop (param i32)))
                          (memory (export "memory") 1)
                          (global ${'$'}dropped (mut i32) (i32.const 0))
                          (func ${'$'}constructor
                            (param ${'$'}seed i32) (result i32)
                            (call ${'$'}new (i32.add (local.get ${'$'}seed) (i32.const 10))))
                          (func ${'$'}close (param ${'$'}self i32)
                            (call ${'$'}drop (local.get ${'$'}self)))
                          (func ${'$'}dropped (result i32)
                            (global.get ${'$'}dropped))
                          (func ${'$'}dtor (param ${'$'}rep i32)
                            (global.set ${'$'}dropped (local.get ${'$'}rep)))
                          (export "example:resources/db#[constructor]blob" (func ${'$'}constructor))
                          (export "example:resources/db#close" (func ${'$'}close))
                          (export "example:resources/db#dropped" (func ${'$'}dropped))
                          (export "example:resources/db#[dtor]blob" (func ${'$'}dtor))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val blob = plugin.call("db.blob.constructor", 5) as Long

        assertEquals(0L, plugin.call("db.dropped"))
        plugin.call("db.close", blob)
        assertEquals(15L, plugin.call("db.dropped"))
    }

    @Test
    fun bindsResourceFunctionsFromImportedInterface() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    read: func(delta: u32) -> u32;
                  }
                }
                interface api {
                  run: func() -> u32;
                }
                world plugin {
                  import db;
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "db" "[method]blob.read"
                            (func ${'$'}read (param i32) (param i32) (result i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (call ${'$'}read (i32.const 7) (i32.const 4)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHost(
                    object : ResourcePluginHost {
                        override fun getDb(): ResourceHost = ResourceHost()
                    }
                )
                .build()

        assertEquals(11L, plugin.call("api.run"))
    }

    @Test
    fun linksImportedResourceDropIntrinsicWithoutManualHostImport() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob;
                }
                interface api {
                  run: func();
                }
                world plugin {
                  import db;
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "db" "[resource-drop]blob" (func ${'$'}drop (param i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run
                            (call ${'$'}drop (i32.const 7)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        assertNull(plugin.call("api.run"))
    }

    @Test
    fun storesKotlinHostResourcesBehindTypedHandles() {
        val table = WitResourceTable<String>()
        val first = table.insert("first")
        val second: WitResource<Blob> = table.insertResource("second")
        val sameHandle = WitResource<String>(first.handle())

        assertEquals(first, sameHandle)
        assertEquals("first", table.get(first))
        assertEquals("second", table.get(second))
        assertEquals(2, table.size())
        assertEquals("first", table.remove(first))
        assertEquals("second", table.remove(second))
        assertEquals(0, table.size())
        assertThrows(ComponentModelException::class.java) { table.get(first) }
    }

    @Test
    fun callsCanonicalPostReturnAfterLiftingExportResults() {
        val witPackage =
            WitPackage.parse(
                """
                package example:post-return;
                interface api {
                  label: func() -> string;
                  posted: func() -> u32;
                }
                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (data (i32.const 96) "hello")
                          (func ${'$'}label (result i32)
                            (i32.store (i32.const 32) (i32.const 96))
                            (i32.store (i32.const 36) (i32.const 5))
                            (i32.const 32))
                          (func ${'$'}post_label (param ${'$'}retptr i32)
                            (i32.store (i32.const 0) (local.get ${'$'}retptr)))
                          (func ${'$'}posted (result i32)
                            (i32.load (i32.const 0)))
                          (export "api.label" (func ${'$'}label))
                          (export "cabi_post_api.label" (func ${'$'}post_label))
                          (export "api.posted" (func ${'$'}posted))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        assertEquals("hello", plugin.call("api.label"))
        assertEquals(32L, plugin.call("api.posted"))
    }

    @Test
    fun returnsTypedTupleFromExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:tuples;
                world plugin {
                  export stats: func() -> tuple<u32, u32, bool, u8>;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (func (export "stats") (result i32)
                            (i32.store (i32.const 32) (i32.const 11))
                            (i32.store (i32.const 36) (i32.const 22))
                            (i32.store8 (i32.const 40) (i32.const 1))
                            (i32.store8 (i32.const 41) (i32.const 7))
                            (i32.const 32))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val stats = plugin.exports(TuplePlugin::class.java).stats()

        assertEquals(11L, stats.first())
        assertEquals(22L, stats.second())
        assertEquals(true, stats.third())
        assertEquals(7, stats.fourth())
    }

    @Test
    fun returnsRecordDtoFromExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:records;
                interface api {
                  record profile {
                    body: list<u8>,
                    label: string,
                  }
                  profile: func() -> profile;
                }
                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (data (i32.const 80) "\01\02\03")
                          (data (i32.const 96) "demo")
                          (func ${'$'}profile (result i32)
                            (i32.store (i32.const 32) (i32.const 80))
                            (i32.store (i32.const 36) (i32.const 3))
                            (i32.store (i32.const 40) (i32.const 96))
                            (i32.store (i32.const 44) (i32.const 4))
                            (i32.const 32))
                          (export "api.profile" (func ${'$'}profile))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val profile = plugin.exports(Api::class.java).profile()

        assertArrayEquals(byteArrayOf(1, 2, 3), profile.body())
        assertEquals("demo", profile.label())
    }

    @Test
    fun returnsGenericResultPayloadFromExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:records;
                interface api {
                  record profile {
                    body: list<u8>,
                    label: string,
                  }
                  load: func() -> result<profile, string>;
                }
                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (data (i32.const 80) "\04\05")
                          (data (i32.const 96) "done")
                          (func ${'$'}load (result i32)
                            (i32.store8 (i32.const 32) (i32.const 0))
                            (i32.store (i32.const 36) (i32.const 80))
                            (i32.store (i32.const 40) (i32.const 2))
                            (i32.store (i32.const 44) (i32.const 96))
                            (i32.store (i32.const 48) (i32.const 4))
                            (i32.const 32))
                          (export "api.load" (func ${'$'}load))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val result =
            assertInstanceOf(WitResult.Ok::class.java, plugin.exports(Api::class.java).load())
        val profile = assertInstanceOf(Profile::class.java, result.value())

        assertArrayEquals(byteArrayOf(4, 5), profile.body())
        assertEquals("done", profile.label())
    }

    @Test
    fun returnsNestedVariantErrorPayloadFromExportProxyResult() {
        val witPackage =
            WitPackage.parse(
                """
                package example:errors;
                interface error-result-api {
                  variant plugin-error {
                    failed(string),
                    skipped(string),
                  }
                  fail: func() -> result<string, plugin-error>;
                }
                world plugin {
                  export error-result-api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (data (i32.const 96) "broken")
                          (func ${'$'}fail (result i32)
                            (i32.store8 (i32.const 32) (i32.const 1))
                            (i32.store8 (i32.const 36) (i32.const 0))
                            (i32.store (i32.const 40) (i32.const 96))
                            (i32.store (i32.const 44) (i32.const 6))
                            (i32.const 32))
                          (export "error-result-api.fail" (func ${'$'}fail))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val result = assertInstanceOf(WitResult.Err::class.java, plugin.exports(ErrorResultApi::class.java).fail())
        val error = assertInstanceOf(PluginError.Failed::class.java, result.value())

        assertEquals("broken", error.value())
    }

    @Test
    fun bindsVariantDtoThroughExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:variants;
                interface variant-api {
                  record profile {
                    body: list<u8>,
                    label: string,
                  }
                  variant outcome {
                    accepted(profile),
                    empty,
                  }
                  outcome: func() -> outcome;
                  is-accepted: func(value: outcome) -> bool;
                }
                world plugin {
                  export variant-api;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (global ${'$'}heap (mut i32) (i32.const 128))
                          (data (i32.const 80) "\04\05")
                          (data (i32.const 96) "done")
                          (func (export "canonical_abi_realloc")
                            (param ${'$'}old i32) (param ${'$'}old_size i32)
                            (param ${'$'}align i32) (param ${'$'}new_size i32)
                            (result i32)
                            (local ${'$'}ptr i32)
                            (local.set ${'$'}ptr (global.get ${'$'}heap))
                            (global.set ${'$'}heap
                              (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                            (local.get ${'$'}ptr))
                          (func ${'$'}outcome (result i32)
                            (i32.store8 (i32.const 32) (i32.const 0))
                            (i32.store (i32.const 36) (i32.const 80))
                            (i32.store (i32.const 40) (i32.const 2))
                            (i32.store (i32.const 44) (i32.const 96))
                            (i32.store (i32.const 48) (i32.const 4))
                            (i32.const 32))
                          (func ${'$'}is_accepted
                            (param ${'$'}tag i32) (param ${'$'}body_ptr i32) (param ${'$'}body_len i32)
                            (param ${'$'}label_ptr i32) (param ${'$'}label_len i32)
                            (result i32)
                            (i32.and
                              (i32.eqz (local.get ${'$'}tag))
                              (i32.and
                                (i32.eq (local.get ${'$'}body_len) (i32.const 2))
                                (i32.eq (local.get ${'$'}label_len) (i32.const 4)))))
                          (export "variant-api.outcome" (func ${'$'}outcome))
                          (export "variant-api.is-accepted" (func ${'$'}is_accepted))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val api = plugin.exports(VariantApi::class.java)
        val accepted = assertInstanceOf(Outcome.Accepted::class.java, api.outcome())

        assertArrayEquals(byteArrayOf(4, 5), accepted.value().body())
        assertEquals("done", accepted.value().label())
        assertEquals(true, api.isAccepted(Outcome.Accepted(Profile(byteArrayOf(4, 5), "done"))))
    }

    @Test
    fun returnsOptionsAndEnumsFromExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:types;
                interface types {
                  enum mode {
                    fast,
                    slow-mode,
                  }
                  label: func(flag: bool) -> option<string>;
                  mode: func() -> mode;
                }
                world plugin {
                  export types;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (data (i32.const 96) "demo")
                          (func ${'$'}label (param ${'$'}flag i32) (result i32)
                            (if (local.get ${'$'}flag)
                              (then
                                (i32.store8 (i32.const 32) (i32.const 1))
                                (i32.store (i32.const 36) (i32.const 96))
                                (i32.store (i32.const 40) (i32.const 4)))
                              (else
                                (i32.store8 (i32.const 32) (i32.const 0))))
                            (i32.const 32))
                          (func ${'$'}mode (result i32)
                            (i32.const 1))
                          (export "types.label" (func ${'$'}label))
                          (export "types.mode" (func ${'$'}mode))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val types = plugin.exports(Types::class.java)

        assertEquals("demo", types.label(true))
        assertNull(types.label(false))
        assertEquals(Mode.SLOW_MODE, types.mode())
    }

    @Test
    fun bindsFlagsDtoThroughExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:flags;
                interface perms {
                  flags permissions {
                    read,
                    write,
                    network-access,
                  }
                  permissions: func() -> permissions;
                  has-access: func(value: permissions) -> bool;
                }
                world plugin {
                  export perms;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (func ${'$'}permissions (result i32)
                            (i32.const 5))
                          (func ${'$'}has_access (param ${'$'}value i32) (result i32)
                            (i32.eq (local.get ${'$'}value) (i32.const 5)))
                          (export "perms.permissions" (func ${'$'}permissions))
                          (export "perms.has-access" (func ${'$'}has_access))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        val perms = plugin.exports(Perms::class.java)
        val permissions = perms.permissions()

        assertEquals(true, permissions.read())
        assertEquals(false, permissions.write())
        assertEquals(true, permissions.networkAccess())
        assertEquals(true, perms.hasAccess(Permissions(true, false, true)))
    }

    @Test
    fun bindsLargeFlagsAcrossPluginBoundary() {
        val witPackage =
            WitPackage.parse(
                """
                package example:flags;
                interface perms {
                  flags permissions {
                ${flagsCases(35)}  }
                  permissions: func() -> permissions;
                  has-access: func(value: permissions) -> bool;
                }
                world plugin {
                  export perms;
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (memory (export "memory") 1)
                          (func ${'$'}permissions (result i32)
                            (i32.store (i32.const 32) (i32.const -2147483647))
                            (i32.store (i32.const 36) (i32.const 4))
                            (i32.const 32))
                          (func ${'$'}has_access
                            (param ${'$'}word0 i32) (param ${'$'}word1 i32) (result i32)
                            (i32.and
                              (i32.eq (local.get ${'$'}word0) (i32.const -2147483647))
                              (i32.eq (local.get ${'$'}word1) (i32.const 4))))
                          (export "perms.permissions" (func ${'$'}permissions))
                          (export "perms.has-access" (func ${'$'}has_access))
                        )
                        """
                            .trimIndent()
                    )
                )
                .build()

        @Suppress("UNCHECKED_CAST")
        val permissions = plugin.call("perms.permissions") as Map<String, Boolean>

        assertEquals(true, permissions["f0"])
        assertEquals(false, permissions["f1"])
        assertEquals(true, permissions["f31"])
        assertEquals(true, permissions["f34"])
        assertEquals(
            true,
            plugin.call("perms.has-access", mapOf("f0" to true, "f31" to true, "f34" to true)),
        )
    }

    @Test
    fun bindsReflectiveHostObjectAndExportProxy() {
        val witPackage =
            WitPackage.parse(
                """
                package example:component;
                interface host {
                  log: func(message: string);
                }
                interface api {
                  run: func() -> u32;
                }
                world plugin {
                  import host;
                  export api;
                }
                """
                    .trimIndent()
            )
        val observed = AtomicReference<String>()
        val host =
            object : ReflectivePluginHost {
                override fun getHost(): ReflectiveHost =
                    object : ReflectiveHost {
                        override fun log(message: String) {
                            observed.set(message)
                        }
                    }
            }
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        """
                        (module
                          (import "host" "log" (func ${'$'}log (param i32 i32)))
                          (memory (export "memory") 1)
                          (data (i32.const 16) "hello")
                          (func ${'$'}run (result i32)
                            (call ${'$'}log (i32.const 16) (i32.const 5))
                            (i32.const 7))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withHost(host)
                .build()

        val guest = plugin.exports(ReflectivePluginGuest::class.java)

        assertEquals(7L, guest.getApi().run())
        assertEquals("hello", observed.get())
    }

    interface ReflectivePluginHost {
        fun getHost(): ReflectiveHost
    }

    interface ReflectiveHost {
        fun log(message: String)
    }

    interface ReflectivePluginGuest {
        fun getApi(): ReflectiveApi
    }

    private fun flagsCases(count: Int): String = buildString {
        repeat(count) { append("    f").append(it).append(",\n") }
    }

    interface ReflectiveApi {
        fun run(): Long
    }

    interface TuplePlugin {
        fun stats(): WitTuple4<Long, Long, Boolean, Int>
    }

    interface Api {
        fun profile(): Profile

        fun load(): WitResult<Profile, String>
    }

    interface ErrorResultApi {
        fun fail(): WitResult<String, PluginError>
    }

    sealed interface PluginError {
        data class Failed(private val value: String) : PluginError {
            fun value(): String = value
        }

        data class Skipped(private val value: String) : PluginError {
            fun value(): String = value
        }
    }

    interface VariantApi {
        fun outcome(): Outcome

        fun isAccepted(value: Outcome): Boolean
    }

    interface Outcome {
        class Accepted(private val value: Profile) : Outcome {
            fun value(): Profile = value
        }

        class Empty : Outcome
    }

    class Profile(private val body: ByteArray, private val label: String) {
        fun body(): ByteArray = body

        fun label(): String = label
    }

    interface Types {
        fun label(flag: Boolean): String?

        fun mode(): Mode
    }

    enum class Mode {
        FAST,
        SLOW_MODE,
    }

    interface Perms {
        fun permissions(): Permissions

        fun hasAccess(value: Permissions): Boolean
    }

    class Permissions(
        private val read: Boolean,
        private val write: Boolean,
        private val networkAccess: Boolean,
    ) {
        fun read(): Boolean = read

        fun write(): Boolean = write

        fun networkAccess(): Boolean = networkAccess
    }

    interface Db {
        fun blobConstructor(seed: Int): WitResource<Blob>

        fun blobRead(self: WitResource<Blob>, delta: Int): Int

        fun blobClone(src: WitResource<Blob>): WitResource<Blob>
    }

    interface Blob

    interface ResourcePluginHost {
        fun getDb(): ResourceHost
    }

    class ResourceHost {
        fun blobRead(self: Long, delta: Int): Int {
            assertEquals(7L, self)
            assertEquals(4, delta)
            return 11
        }
    }
}

private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
