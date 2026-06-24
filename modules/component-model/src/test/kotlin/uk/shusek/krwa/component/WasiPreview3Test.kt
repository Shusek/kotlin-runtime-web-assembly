@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.ktor.network.sockets.InetSocketAddress as KtorInetSocketAddress
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant as KotlinInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class WasiPreview3Test {
    @Test
    fun resourceBudgetConfiguresPreview3Limits() {
        val runtime =
            WasiPreview3.builder()
                .withResourceBudget(
                    parallelism = 1,
                    maxPendingFutures = 1,
                    maxPendingStreams = 1,
                    maxWaitables = 1,
                )
                .build()

        runtime.pendingFuture<Any?>()
        assertThrows(ComponentModelException::class.java) {
            runtime.pendingFuture<Any?>()
        }

        runtime.byteStream(byteArrayOf(1))
        assertThrows(ComponentModelException::class.java) {
            runtime.byteStream(byteArrayOf(2))
        }

        runtime.close()
    }

    @Test
    fun enforcesConfiguredPreview3ResourceLimits() {
        val futures = WasiPreview3.builder().withResourceBudget(1, maxPendingFutures = 1).build()
        futures.pendingFuture<Any?>()
        assertThrows(ComponentModelException::class.java) {
            futures.pendingFuture<Any?>()
        }

        val streams = WasiPreview3.builder().withResourceBudget(1, maxPendingStreams = 1).build()
        streams.byteStream(byteArrayOf(1))
        assertThrows(ComponentModelException::class.java) {
            streams.byteStream(byteArrayOf(2))
        }

        val hostTasks = WasiPreview3.builder().withResourceBudget(1).build()
        hostTasks.byteStream(
            flow {
                delay(1_000)
                emit(byteArrayOf(1))
            }
        )
        assertThrows(ComponentModelException::class.java) {
            hostTasks.byteStream(flow { emit(byteArrayOf(2)) })
        }
        hostTasks.close()

        val closed = WasiPreview3.builder().build()
        closed.close()
        assertThrows(ComponentModelException::class.java) {
            closed.pendingFuture<Any?>()
        }
        closed.cancel()
    }

    @Test
    fun rejectsNonPositivePreview3ResourceLimits() {
        val configurations =
            listOf<() -> Unit>(
                { WasiPreview3.builder().withResourceBudget(parallelism = 0) },
                { WasiPreview3.builder().withResourceBudget(parallelism = 1, maxPendingFutures = 0) },
                { WasiPreview3.builder().withResourceBudget(parallelism = 1, maxPendingStreams = 0) },
                { WasiPreview3.builder().withResourceBudget(parallelism = 1, maxWaitables = 0) },
                { WasiPreview3.builder().withResourceBudget(parallelism = 1, streamBufferCapacity = 0) },
            )

        for (configure in configurations) {
            assertThrows(IllegalArgumentException::class.java) {
                configure()
            }
        }
    }

    @Test
    fun releasesInFlightHostTaskSlotAfterFlowByteStreamCompletes() = runBlocking {
        val hostTasks =
            WasiPreview3.builder()
                .withResourceBudget(parallelism = 1)
                .withCoroutineScope(this)
                .build()
        try {
            val first = hostTasks.byteStream(flow { emit(byteArrayOf(1, 2)) })
            assertArrayEquals(byteArrayOf(1, 2), hostTasks.readByteStreamChunk(first, 8))
            assertEquals(null, hostTasks.readByteStreamChunk(first, 8))

            var second: WitStream<*>? = null
            for (attempt in 0 until 50) {
                try {
                    second = hostTasks.byteStream(flow { emit(byteArrayOf(3)) })
                    break
                } catch (e: ComponentModelException) {
                    if (!e.message.orEmpty().contains("in-flight host task") || attempt == 49) {
                        throw e
                    }
                    delay(10)
                }
            }

            val completedSecond = requireNotNull(second) { "second byte stream was not created" }
            assertArrayEquals(byteArrayOf(3), hostTasks.readByteStreamChunk(completedSecond, 8))
            assertEquals(null, hostTasks.readByteStreamChunk(completedSecond, 8))
        } finally {
            hostTasks.close()
        }
    }

    @Test
    fun linksPreview3StableVersionResourceImports() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-stable-imports;

                world plugin {
                  import wasi:filesystem/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:filesystem@$version {
                  interface types {
                    resource descriptor {}
                  }
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
                          (import "wasi:filesystem/types@$version"
                            "[resource-drop]descriptor" (func ${'$'}drop_descriptor (param i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (i32.const 42))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().build())
                .build()

        assertEquals(42L, plugin.call("api.run"))
    }

    @Test
    fun canonicalFutureIntrinsicsEncodePreview3AsyncStatuses() {
        val payloadType = WitPackage.TypeRef.primitive("u32")
        val wasi = WasiPreview3.builder().build()
        val context = RecordingCanonicalContext()
        val pair = wasi.futureNew()
        val reader = pair and 0xffff_ffffL
        val writer = pair ushr 32

        assertEquals(CANONICAL_ASYNC_BLOCKED, wasi.futureRead(context, reader, 32, payloadType))

        context.futureLoads[64] = 42L
        assertEquals(CANONICAL_ASYNC_COMPLETED, wasi.futureWrite(context, writer, 64, payloadType))
        assertEquals(CANONICAL_ASYNC_COMPLETED, wasi.futureRead(context, reader, 32, payloadType))
        assertEquals(42L, context.futureStores[32])
        assertEquals(CANONICAL_ASYNC_DROPPED, wasi.futureWrite(context, writer, 64, payloadType))

        wasi.futureDropReadable(reader)
        wasi.futureDropWritable(writer)

        val cancelledPair = wasi.futureNew()
        val cancelledReader = cancelledPair and 0xffff_ffffL
        val cancelledWriter = cancelledPair ushr 32

        assertEquals(CANONICAL_ASYNC_CANCELLED, wasi.futureCancelWrite(cancelledWriter))
        assertEquals(
            CANONICAL_ASYNC_CANCELLED,
            wasi.futureRead(context, cancelledReader, 96, payloadType),
        )

        wasi.futureDropReadable(cancelledReader)
        wasi.futureDropWritable(cancelledWriter)
    }

    @Test
    fun canonicalByteStreamIntrinsicsEncodePreview3TransferStatuses() {
        val payloadType = WitPackage.TypeRef.primitive("u8")
        val wasi = WasiPreview3.builder().build()
        val context = RecordingCanonicalContext()
        val pair = wasi.streamNew(payloadType)
        val reader = pair and 0xffff_ffffL
        val writer = pair ushr 32

        assertEquals(CANONICAL_ASYNC_BLOCKED, wasi.streamRead(context, reader, 32, 8, payloadType))

        context.writeMemory(64, byteArrayOf(10, 11, 12))
        assertEquals(
            canonicalTransferCompleted(3),
            wasi.streamWrite(context, writer, 64, 3, payloadType),
        )
        assertEquals(
            canonicalTransferCompleted(2),
            wasi.streamRead(context, reader, 96, 2, payloadType),
        )
        assertArrayEquals(byteArrayOf(10, 11), context.readMemory(96, 2))

        wasi.streamDropWritable(writer)
        assertEquals(
            canonicalTransferDropped(1),
            wasi.streamRead(context, reader, 128, 8, payloadType),
        )
        assertArrayEquals(byteArrayOf(12), context.readMemory(128, 1))
        assertEquals(CANONICAL_ASYNC_DROPPED, wasi.streamRead(context, reader, 160, 8, payloadType))

        wasi.streamDropReadable(reader)
    }

    @Test
    fun canonicalObjectStreamIntrinsicsUsePreview3ListElementContext() {
        val payloadType = WitPackage.TypeRef.primitive("u32")
        val wasi = WasiPreview3.builder().build()
        val context = RecordingCanonicalContext()
        val pair = wasi.streamNew(payloadType)
        val reader = pair and 0xffff_ffffL
        val writer = pair ushr 32

        context.listLoads[64] = listOf(7L, 8L, 9L)
        assertEquals(
            canonicalTransferCompleted(3),
            wasi.streamWrite(context, writer, 64, 3, payloadType),
        )
        assertEquals(
            canonicalTransferCompleted(2),
            wasi.streamRead(context, reader, 96, 2, payloadType),
        )
        assertEquals(listOf(7L, 8L), context.listStores[96])

        wasi.streamDropWritable(writer)
        assertEquals(
            canonicalTransferDropped(1),
            wasi.streamRead(context, reader, 128, 8, payloadType),
        )
        assertEquals(listOf(9L), context.listStores[128])
        assertEquals(CANONICAL_ASYNC_DROPPED, wasi.streamRead(context, reader, 160, 8, payloadType))

        wasi.streamDropReadable(reader)
    }

    @Test
    fun handlesHttpServiceWorldStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val pathWithQuery = "/service?name=kotlin"
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi-http3;

                world plugin {
                  include wasi:http/service@$version;
                }

                package wasi:clocks@$version {
                  world imports {}
                }

                package wasi:random@$version {
                  world imports {}
                }

	                package wasi:cli@$version {
	                  interface types {
	                    enum error-code {
	                      io,
	                      illegal-byte-sequence,
	                      pipe,
	                    }
	                  }
	                  interface stdout {
	                    use types.{error-code};
	                    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
	                  }
	                  interface stderr {
	                    use types.{error-code};
	                    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
	                  }
	                  interface stdin {
	                    use types.{error-code};
	                    read-via-stream: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
	                  }
	                }

                package wasi:http@$version {
                  world service {
                    include wasi:clocks/imports@$version;
                    include wasi:random/imports@$version;
                    import wasi:cli/stdout@$version;
                    import wasi:cli/stderr@$version;
                    import wasi:cli/stdin@$version;
                    import client;
                    export handler;
                  }

                  interface client {
                    use types.{request, response, error-code};
                    send: async func(request: request) -> result<response, error-code>;
                  }

                  interface handler {
                    use types.{request, response, error-code};
                    handle: async func(request: request) -> result<response, error-code>;
                  }

                  interface types {
                    variant error-code {
                      internal-error,
                    }
                    variant header-error {
                      invalid-syntax,
                      forbidden,
                      immutable,
                    }
                    resource fields {
                      constructor();
                      append: func(name: string, value: list<u8>) -> result<_, header-error>;
                    }
                    type headers = fields;
                    resource request {
                      get-path-with-query: func() -> option<string>;
                      get-headers: func() -> headers;
                    }
	                    resource response {
	                      new: static func(
	                        headers: headers,
	                        contents: option<stream<u8>>,
	                        trailers: future<result<option<headers>, error-code>>,
	                      ) -> tuple<response, future<result<_, error-code>>>;
	                      get-status-code: func() -> u16;
	                      set-status-code: func(status-code: u16) -> result;
	                      get-headers: func() -> headers;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val wasi = WasiPreview3.builder().build()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]request.get-path-with-query\" (func" +
                            " \$path (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[constructor]fields\" (func \$fields_new (result" +
                            " i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]fields.append\" (func \$append (param i32)" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]response.new\" (func \$response_new" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]response.set-status-code\" (func \$set_status" +
                            " (param i32) (param i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (data (i32.const 16) \"x-preview\")\n" +
                            "  (data (i32.const 32) \"ok\")\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$handle (param \$request i32) (result i32)\n" +
                            "    (local \$fields i32)\n" +
                            "    (local \$response i32)\n" +
                            "    (call \$path (local.get \$request) (i32.const 64))\n" +
                            "    (if\n" +
                            "      (i32.or\n" +
                            "        (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 1))\n" +
                            "        (i32.ne (i32.load (i32.const 72)) (i32.const " +
                            pathWithQuery.length +
                            ")))\n" +
                            "      (then unreachable))\n" +
                            "    (local.set \$fields (call \$fields_new))\n" +
                            "    (call \$append\n" +
                            "      (local.get \$fields)\n" +
                            "      (i32.const 16)\n" +
                            "      (i32.const 9)\n" +
                            "      (i32.const 32)\n" +
                            "      (i32.const 2)\n" +
                            "      (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$response_new\n" +
                            "      (local.get \$fields)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 112))\n" +
                            "    (local.set \$response (i32.load (i32.const 112)))\n" +
                            "    (if (i32.ne (call \$set_status (local.get \$response)" +
                            " (i32.const 201)) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (i32.store8 (i32.const 128) (i32.const 0))\n" +
                            "    (i32.store (i32.const 132) (local.get \$response))\n" +
                            "    (i32.const 128))\n" +
                            "  (export \"wasi:http/handler@$version.handle\"" +
                            " (func \$handle))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        val response =
            wasi.handleHttpRequest(
                plugin,
                "GET",
                pathWithQuery,
                "http",
                "localhost",
                mapOf<String, List<ByteArray>>(),
                ByteArray(0),
            )

        assertEquals(201, response.statusCode())
        assertTrue(response.bodyFinished())
        assertArrayEquals(ByteArray(0), response.body())
        assertArrayEquals(
            "ok".toByteArray(StandardCharsets.ISO_8859_1),
            response.headers()["x-preview"]!![0],
        )
    }

    @Test
    fun sendsHttpClientRequestStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val requestLine = AtomicReference<String?>()
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serverThread =
                Thread(
                    {
                        try {
                            server.accept().use { socket ->
                                val reader =
                                    BufferedReader(
                                        InputStreamReader(
                                            socket.getInputStream(),
                                            StandardCharsets.ISO_8859_1,
                                        )
                                    )
                                requestLine.set(reader.readLine())
                                while (true) {
                                    val line = reader.readLine()
                                    if (line == null || line.isEmpty()) {
                                        break
                                    }
                                }
                                socket
                                    .getOutputStream()
                                    .write(
                                        ("HTTP/1.1 203 Accepted\r\n" +
                                                "Content-Length: 5\r\n" +
                                                "X-P3: ok\r\n" +
                                                "Connection: close\r\n" +
                                                "\r\nreply")
                                            .toByteArray(StandardCharsets.ISO_8859_1)
                                    )
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "wasi3-http-client-test",
                )
            serverThread.setDaemon(true)
            serverThread.start()

            val authority = "127.0.0.1:" + server.localPort
            val pathWithQuery = "/probe?x=p3"
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-http-client;

                    world plugin {
                      import wasi:http/types@$version;
                      import wasi:http/client@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:http@$version {
                      interface client {
                        use types.{request, response, error-code};
                        send: async func(request: request) -> result<response, error-code>;
                      }

                      interface types {
                        variant error-code {
                          HTTP-request-denied,
                          HTTP-request-URI-invalid,
                          HTTP-request-method-invalid,
                          connection-refused,
                          connection-timeout,
                          internal-error(option<string>),
                        }

                        variant header-error {
                          invalid-syntax,
                          forbidden,
                          immutable,
                        }

                        resource fields {
                          constructor();
                        }

                        type headers = fields;
                        type trailers = fields;

                        resource request-options;

                        resource request {
                          new: static func(
                            headers: headers,
                            contents: option<stream<u8>>,
                            trailers: future<result<option<trailers>, error-code>>,
                            options: option<request-options>,
                          ) -> tuple<request, future<result<_, error-code>>>;
                          set-authority: func(authority: option<string>) -> result;
                          set-path-with-query: func(path-with-query: option<string>) -> result;
                        }

                        resource response {
                          get-status-code: func() -> u16;
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:http/types@$version\"" +
                                " \"[constructor]fields\" (func \$fields_new" +
                                " (result i32)))\n" +
                                "  (import \"wasi:http/types@$version\"" +
                                " \"[static]request.new\" (func \$request_new" +
                                " (param i32) (param i32) (param i32) (param i32)" +
                                " (param i32) (param i32) (param i32)))\n" +
                                "  (import \"wasi:http/types@$version\"" +
                                " \"[method]request.set-authority\" (func" +
                                " \$set_authority (param i32) (param i32)" +
                                " (param i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:http/types@$version\"" +
                                " \"[method]request.set-path-with-query\" (func" +
                                " \$set_path (param i32) (param i32) (param i32)" +
                                " (param i32) (result i32)))\n" +
                                "  (import \"wasi:http/client@$version\" \"send\"" +
                                " (func \$send (param i32) (result i32)))\n" +
                                "  (import \"wasi:http/client@$version\"" +
                                " \"[async-lower][future-read-0]send\"" +
                                " (func \$send_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:http/client@$version\"" +
                                " \"waitable-set.new\"" +
                                " (func \$waitable_set_new (result i32)))\n" +
                                "  (import \"wasi:http/client@$version\"" +
                                " \"waitable.join\"" +
                                " (func \$waitable_join (param i32 i32)))\n" +
                                "  (import \"wasi:http/client@$version\"" +
                                " \"waitable-set.wait\"" +
                                " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:http/client@$version\"" +
                                " \"waitable-set.drop\"" +
                                " (func \$waitable_set_drop (param i32)))\n" +
                                "  (import \"wasi:http/types@$version\"" +
                                " \"[method]response.get-status-code\" (func" +
                                " \$status (param i32) (result i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (global \$heap (mut i32) (i32.const 256))\n" +
                                "  (data (i32.const 16) \"" +
                                authority +
                                "\")\n" +
                                "  (data (i32.const 64) \"" +
                                pathWithQuery +
                                "\")\n" +
                                "  (func (export \"canonical_abi_realloc\")\n" +
                                "    (param \$old i32) (param \$old_size i32)\n" +
                                "    (param \$align i32) (param \$new_size i32)\n" +
                                "    (result i32)\n" +
                                "    (local \$ptr i32)\n" +
                                "    (local.set \$ptr\n" +
                                "      (i32.and\n" +
                                "        (i32.add (global.get \$heap)" +
                                " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                                "        (i32.xor\n" +
                                "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                                "          (i32.const -1))))\n" +
                                "    (global.set \$heap\n" +
                                "      (i32.add (local.get \$ptr) (local.get" +
                                " \$new_size)))\n" +
                                "    (local.get \$ptr))\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$request i32)\n" +
                                "    (local \$response i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$send_status i32)\n" +
                                "    (local \$waitable_set i32)\n" +
                                "    (call \$request_new\n" +
                                "      (call \$fields_new)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 96))\n" +
                                "    (local.set \$request (i32.load (i32.const 96)))\n" +
                                "    (if\n" +
                                "      (i32.ne\n" +
                                "        (call \$set_authority\n" +
                                "          (local.get \$request)\n" +
                                "          (i32.const 1)\n" +
                                "          (i32.const 16)\n" +
                                "          (i32.const " +
                                authority.length +
                                "))\n" +
                                "        (i32.const 0))\n" +
                                "      (then (return (i32.const 98))))\n" +
                                "    (if\n" +
                                "      (i32.ne\n" +
                                "        (call \$set_path\n" +
                                "          (local.get \$request)\n" +
                                "          (i32.const 1)\n" +
                                "          (i32.const 64)\n" +
                                "          (i32.const " +
                                pathWithQuery.length +
                                "))\n" +
                                "        (i32.const 0))\n" +
                                "      (then (return (i32.const 97))))\n" +
                                "    (local.set \$future (call \$send (local.get \$request)))\n" +
                                "    (local.set \$send_status\n" +
                                "      (call \$send_future_read (local.get \$future)" +
                                " (i32.const 128)))\n" +
                                "    (if (i32.eq (local.get \$send_status) (i32.const -1))\n" +
                                "      (then\n" +
                                "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                "        (call \$waitable_join\n" +
                                "          (local.get \$future)\n" +
                                "          (local.get \$waitable_set))\n" +
                                "        (if\n" +
                                "          (i32.ne\n" +
                                "            (call \$waitable_set_wait\n" +
                                "              (local.get \$waitable_set)\n" +
                                "              (i32.const 192))\n" +
                                "            (i32.const 4))\n" +
                                "          (then (return (i32.const 95))))\n" +
                                "        (if\n" +
                                "          (i32.ne (i32.load (i32.const 192))" +
                                " (local.get \$future))\n" +
                                "          (then (return (i32.const 94))))\n" +
                                "        (if\n" +
                                "          (i32.ne (i32.load (i32.const 196))" +
                                " (i32.const 0))\n" +
                                "          (then (return (i32.const 93))))\n" +
                                "        (call \$waitable_set_drop (local.get \$waitable_set)))\n" +
                                "      (else\n" +
                                "        (if (i32.ne (local.get \$send_status) (i32.const 0))\n" +
                                "          (then (return (i32.const 96))))))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 128))" +
                                " (i32.const 0))\n" +
                                "      (then (return (i32.const 99))))\n" +
                                "    (local.set \$response (i32.load (i32.const 132)))\n" +
                                "    (call \$status (local.get \$response)))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                    .build()

            assertEquals(203L, plugin.call("api.run"))
            serverThread.join(2_000L)
        }

        if (serverFailure.get() != null) {
            throw AssertionError("HTTP test server failed", serverFailure.get())
        }
        assertEquals("GET /probe?x=p3 HTTP/1.1", requestLine.get())
    }

    @Test
    fun callsHttpMiddlewareHandlerImportStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val authority = "middleware.local"
        val pathWithQuery = "/downstream?via=middleware"
        val seenRequest = AtomicReference<WasiPreview2.HttpRequestSnapshot?>()
        val wasi =
            WasiPreview3.builder()
                .withHttpHandler(
                    WasiHttpHandler { request ->
                        seenRequest.set(request)
                        WasiPreview2.HttpResponseSnapshot(
                            204,
                            mapOf(
                                "x-downstream" to
                                    listOf("ok".toByteArray(StandardCharsets.ISO_8859_1))
                            ),
                            ByteArray(0),
                            true,
                        )
                    }
                )
                .build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-http-middleware;

                world plugin {
                  import wasi:http/types@$version;
                  import wasi:http/handler@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:http@$version {
                  interface handler {
                    use types.{request, response, error-code};
                    handle: async func(request: request) -> result<response, error-code>;
                  }

                  interface types {
                    variant error-code {
                      HTTP-request-denied,
                      HTTP-request-URI-invalid,
                      HTTP-request-method-invalid,
                      connection-refused,
                      connection-timeout,
                      internal-error(option<string>),
                    }

                    variant header-error {
                      invalid-syntax,
                      forbidden,
                      immutable,
                    }

                    resource fields {
                      constructor();
                    }

                    type headers = fields;
                    type trailers = fields;

                    resource request-options;

                    resource request {
                      new: static func(
                        headers: headers,
                        contents: option<stream<u8>>,
                        trailers: future<result<option<trailers>, error-code>>,
                        options: option<request-options>,
                      ) -> tuple<request, future<result<_, error-code>>>;
                      set-authority: func(authority: option<string>) -> result;
                      set-path-with-query: func(path-with-query: option<string>) -> result;
                    }

                    resource response {
                      get-status-code: func() -> u16;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[constructor]fields\" (func \$fields_new" +
                            " (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]request.new\" (func \$request_new" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32) (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]request.set-authority\" (func" +
                            " \$set_authority (param i32) (param i32)" +
                            " (param i32) (param i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]request.set-path-with-query\" (func" +
                            " \$set_path (param i32) (param i32) (param i32)" +
                            " (param i32) (result i32)))\n" +
                            "  (import \"wasi:http/handler@$version\" \"handle\"" +
                            " (func \$handle (param i32) (result i32)))\n" +
                            "  (import \"wasi:http/handler@$version\"" +
                            " \"[async-lower][future-read-0]handle\"" +
                            " (func \$handle_future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[method]response.get-status-code\" (func" +
                            " \$status (param i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (data (i32.const 16) \"" +
                            authority +
                            "\")\n" +
                            "  (data (i32.const 64) \"" +
                            pathWithQuery +
                            "\")\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$request i32)\n" +
                            "    (local \$response i32)\n" +
                            "    (local \$future i32)\n" +
                            "    (local \$handle_status i32)\n" +
                            "    (call \$request_new\n" +
                            "      (call \$fields_new)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 96))\n" +
                            "    (local.set \$request (i32.load (i32.const 96)))\n" +
                            "    (if\n" +
                            "      (i32.ne\n" +
                            "        (call \$set_authority\n" +
                            "          (local.get \$request)\n" +
                            "          (i32.const 1)\n" +
                            "          (i32.const 16)\n" +
                            "          (i32.const " +
                            authority.length +
                            "))\n" +
                            "        (i32.const 0))\n" +
                            "      (then (return (i32.const 98))))\n" +
                            "    (if\n" +
                            "      (i32.ne\n" +
                            "        (call \$set_path\n" +
                            "          (local.get \$request)\n" +
                            "          (i32.const 1)\n" +
                            "          (i32.const 64)\n" +
                            "          (i32.const " +
                            pathWithQuery.length +
                            "))\n" +
                            "        (i32.const 0))\n" +
                            "      (then (return (i32.const 97))))\n" +
                            "    (local.set \$future (call \$handle (local.get \$request)))\n" +
                            "    (local.set \$handle_status\n" +
                            "      (call \$handle_future_read (local.get \$future)" +
                            " (i32.const 128)))\n" +
                            "    (if (i32.ne (local.get \$handle_status) (i32.const 0))\n" +
                            "      (then (return (i32.const 96))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 128))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 99))))\n" +
                            "    (local.set \$response (i32.load (i32.const 132)))\n" +
                            "    (call \$status (local.get \$response)))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        assertEquals(204L, plugin.call("api.run"))
        val request = seenRequest.get() ?: throw AssertionError("middleware handler was not called")
        assertEquals("GET", request.method())
        assertEquals(authority, request.authority())
        assertEquals(pathWithQuery, request.pathWithQuery())
    }

    @Test
    fun preservesHttpTrailersStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-http-trailers;

                world plugin {
                  import wasi:http/types@$version;
                  export api;
                }

                interface api {
                  use wasi:http/types@$version.{request, response, trailers, error-code};
                  make-request: func(
                    trailers: future<result<option<trailers>, error-code>>,
                  ) -> request;
                  consume-request: func(
                    request: request,
                  ) -> future<result<option<trailers>, error-code>>;
                  make-response: func(
                    trailers: future<result<option<trailers>, error-code>>,
                  ) -> response;
                  consume-response: func(
                    response: response,
                  ) -> future<result<option<trailers>, error-code>>;
                }

                package wasi:http@$version {
                  interface types {
                    variant error-code {
                      internal-error(option<string>),
                    }

                    variant header-error {
                      invalid-syntax,
                      forbidden,
                      immutable,
                    }

                    resource fields {
                      constructor();
                    }

                    type headers = fields;
                    type trailers = fields;

                    resource request-options;

                    resource request {
                      new: static func(
                        headers: headers,
                        contents: option<stream<u8>>,
                        trailers: future<result<option<trailers>, error-code>>,
                        options: option<request-options>,
                      ) -> tuple<request, future<result<_, error-code>>>;
                      consume-body: static func(
                        this: request,
                        res: future<result<_, error-code>>,
                      ) -> tuple<stream<u8>, future<result<option<trailers>, error-code>>>;
                    }

                    resource response {
                      new: static func(
                        headers: headers,
                        contents: option<stream<u8>>,
                        trailers: future<result<option<trailers>, error-code>>,
                      ) -> tuple<response, future<result<_, error-code>>>;
                      consume-body: static func(
                        this: response,
                        res: future<result<_, error-code>>,
                      ) -> tuple<stream<u8>, future<result<option<trailers>, error-code>>>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val wasi = WasiPreview3.builder().build()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[constructor]fields\" (func \$fields_new (result" +
                            " i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]request.new\" (func \$request_new" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32) (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]request.consume-body\" (func" +
                            " \$request_consume (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]response.new\" (func \$response_new" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]response.consume-body\" (func" +
                            " \$response_consume (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$make_request (param \$trailers i32)" +
                            " (result i32)\n" +
                            "    (call \$request_new\n" +
                            "      (call \$fields_new)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (local.get \$trailers)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 64))\n" +
                            "    (i32.load (i32.const 64)))\n" +
                            "  (func \$consume_request (param \$request i32)" +
                            " (result i32)\n" +
                            "    (call \$request_consume\n" +
                            "      (local.get \$request)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 80))\n" +
                            "    (i32.load (i32.const 84)))\n" +
                            "  (func \$make_response (param \$trailers i32)" +
                            " (result i32)\n" +
                            "    (call \$response_new\n" +
                            "      (call \$fields_new)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (local.get \$trailers)\n" +
                            "      (i32.const 96))\n" +
                            "    (i32.load (i32.const 96)))\n" +
                            "  (func \$consume_response (param \$response i32)" +
                            " (result i32)\n" +
                            "    (call \$response_consume\n" +
                            "      (local.get \$response)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 112))\n" +
                            "    (i32.load (i32.const 116)))\n" +
                            "  (export \"api.make-request\" (func \$make_request))\n" +
                            "  (export \"api.consume-request\" (func" +
                            " \$consume_request))\n" +
                            "  (export \"api.make-response\" (func \$make_response))\n" +
                            "  (export \"api.consume-response\" (func" +
                            " \$consume_response))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        val requestTrailerFields =
            wasi.httpFields(
                mapOf(
                    "x-request-trailer" to
                        listOf("request-done".toByteArray(StandardCharsets.ISO_8859_1))
                )
            )
        val responseTrailerFields =
            wasi.httpFields(
                mapOf(
                    "x-response-trailer" to
                        listOf("response-done".toByteArray(StandardCharsets.ISO_8859_1))
                )
            )
        val requestFuture = wasi.completedFuture(WitResult.ok(WitValue.some(requestTrailerFields)))
        val responseFuture =
            wasi.completedFuture(WitResult.ok(WitValue.some(responseTrailerFields)))

        val request = (plugin.call("api.make-request", requestFuture) as Number).toLong()
        val response = (plugin.call("api.make-response", responseFuture) as Number).toLong()

        assertTrailerResult(wasi.httpRequestTrailers(request), "x-request-trailer", "request-done")
        assertTrailerResult(
            wasi.httpResponseTrailers(response),
            "x-response-trailer",
            "response-done",
        )

        val requestConsumeFuture = plugin.call("api.consume-request", request) as WitFuture<*>
        val responseConsumeFuture = plugin.call("api.consume-response", response) as WitFuture<*>

        assertTrailerMap(
            trailerFutureSnapshot(wasi, requestConsumeFuture),
            "x-request-trailer",
            "request-done",
        )
        assertTrailerMap(
            trailerFutureSnapshot(wasi, responseConsumeFuture),
            "x-response-trailer",
            "response-done",
        )
    }

    @Test
    fun readsHttpRequestBodyWithCanonicalIntrinsicsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-http-body-canonical;

                world plugin {
                  import wasi:http/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:http@$version {
                  interface types {
                    variant error-code {
                      internal-error(option<string>),
                    }

                    resource fields {
                      constructor();
                    }

                    type headers = fields;
                    type trailers = fields;

                    resource request-options;

                    resource request {
                      new: static func(
                        headers: headers,
                        contents: option<stream<u8>>,
                        trailers: future<result<option<trailers>, error-code>>,
                        options: option<request-options>,
                      ) -> tuple<request, future<result<_, error-code>>>;
                      consume-body: static func(
                        this: request,
                        res: future<result<_, error-code>>,
                      ) -> tuple<stream<u8>, future<result<option<trailers>, error-code>>>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[constructor]fields\" (func \$fields_new (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]request.new\" (func \$request_new" +
                            " (param i32 i32 i32 i32 i32 i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][future-read-1][static]request.new\"" +
                            " (func \$request_future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]request.consume-body\" (func" +
                            " \$request_consume (param i32 i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[stream-new-0][static]request.new\" (func" +
                            " \$stream_new (result i64)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][stream-write-0][static]request.new\"" +
                            " (func \$stream_write (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[stream-drop-writable-0][static]request.new\"" +
                            " (func \$drop_writable (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][stream-read-1][static]request.consume-body\"" +
                            " (func \$stream_read (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][future-read-2][static]request.consume-body\"" +
                            " (func \$future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.new\"" +
                            " (func \$waitable_set_new (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable.join\"" +
                            " (func \$waitable_join (param i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.wait\"" +
                            " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.drop\"" +
                            " (func \$waitable_set_drop (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (data (i32.const 32) \"body\")\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$pair i64)\n" +
                            "    (local \$reader i32)\n" +
                            "    (local \$writer i32)\n" +
                            "    (local \$request i32)\n" +
                            "    (local \$request_future i32)\n" +
                            "    (local \$stream i32)\n" +
                            "    (local \$future i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (local.set \$pair (call \$stream_new))\n" +
                            "    (local.set \$reader (i32.wrap_i64 (local.get \$pair)))\n" +
                            "    (local.set \$writer\n" +
                            "      (i32.wrap_i64\n" +
                            "        (i64.shr_u (local.get \$pair) (i64.const 32))))\n" +
                            "    (call \$request_new\n" +
                            "      (call \$fields_new)\n" +
                            "      (i32.const 1)\n" +
                            "      (local.get \$reader)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 64))\n" +
                            "    (local.set \$request (i32.load (i32.const 64)))\n" +
                            "    (local.set \$request_future (i32.load (i32.const 68)))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$request_future_read\n" +
                            "        (local.get \$request_future)\n" +
                            "        (i32.const 176)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const -1))\n" +
                            "      (then unreachable))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$stream_write\n" +
                            "        (local.get \$writer)\n" +
                            "        (i32.const 32)\n" +
                            "        (i32.const 4)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 64))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_writable (local.get \$writer))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$request_future_read\n" +
                            "        (local.get \$request_future)\n" +
                            "        (i32.const 176)))\n" +
                            "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                            "      (then\n" +
                            "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "        (call \$waitable_join\n" +
                            "          (local.get \$request_future)\n" +
                            "          (local.get \$waitable_set))\n" +
                            "        (if\n" +
                            "          (i32.ne\n" +
                            "            (call \$waitable_set_wait\n" +
                            "              (local.get \$waitable_set)\n" +
                            "              (i32.const 192))\n" +
                            "            (i32.const 4))\n" +
                            "          (then unreachable))\n" +
                            "        (call \$waitable_set_drop (local.get \$waitable_set))\n" +
                            "        (local.set \$status\n" +
                            "          (call \$request_future_read\n" +
                            "            (local.get \$request_future)\n" +
                            "            (i32.const 176)))))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 176))" +
                            " (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$request_consume\n" +
                            "      (local.get \$request)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 80))\n" +
                            "    (local.set \$stream (i32.load (i32.const 80)))\n" +
                            "    (local.set \$future (i32.load (i32.const 84)))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$future_read\n" +
                            "        (local.get \$future)\n" +
                            "        (i32.const 160)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 160))" +
                            " (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$stream_read\n" +
                            "        (local.get \$stream)\n" +
                            "        (i32.const 128)\n" +
                            "        (i32.const 4)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 65))\n" +
                            "      (then unreachable))\n" +
                            "    (i32.add\n" +
                            "      (i32.add\n" +
                            "        (i32.add\n" +
                            "          (i32.add\n" +
                            "            (local.get \$status)\n" +
                            "            (i32.load8_u (i32.const 128)))\n" +
                            "          (i32.load8_u (i32.const 129)))\n" +
                            "        (i32.load8_u (i32.const 130)))\n" +
                            "      (i32.load8_u (i32.const 131))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().build())
                .build()

        assertEquals(495L, plugin.call("api.run"))
    }

    @Test
    fun readsHttpResponseBodyWithCanonicalIntrinsicsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-http-response-body-canonical;

                world plugin {
                  import wasi:http/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:http@$version {
                  interface types {
                    variant error-code {
                      internal-error(option<string>),
                    }

                    resource fields {
                      constructor();
                    }

                    type headers = fields;
                    type trailers = fields;

                    resource response {
                      new: static func(
                        headers: headers,
                        contents: option<stream<u8>>,
                        trailers: future<result<option<trailers>, error-code>>,
                      ) -> tuple<response, future<result<_, error-code>>>;
                      consume-body: static func(
                        this: response,
                        res: future<result<_, error-code>>,
                      ) -> tuple<stream<u8>, future<result<option<trailers>, error-code>>>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[constructor]fields\" (func \$fields_new (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]response.new\" (func \$response_new" +
                            " (param i32 i32 i32 i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][future-read-1][static]response.new\"" +
                            " (func \$response_future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[static]response.consume-body\" (func" +
                            " \$response_consume (param i32 i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[stream-new-0][static]response.new\" (func" +
                            " \$stream_new (result i64)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][stream-write-0][static]response.new\"" +
                            " (func \$stream_write (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[stream-drop-writable-0][static]response.new\"" +
                            " (func \$drop_writable (param i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][stream-read-1][static]response.consume-body\"" +
                            " (func \$stream_read (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\"" +
                            " \"[async-lower][future-read-2][static]response.consume-body\"" +
                            " (func \$future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.new\"" +
                            " (func \$waitable_set_new (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable.join\"" +
                            " (func \$waitable_join (param i32 i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.wait\"" +
                            " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@$version\" \"waitable-set.drop\"" +
                            " (func \$waitable_set_drop (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (data (i32.const 32) \"pong\")\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$pair i64)\n" +
                            "    (local \$reader i32)\n" +
                            "    (local \$writer i32)\n" +
                            "    (local \$response i32)\n" +
                            "    (local \$response_future i32)\n" +
                            "    (local \$stream i32)\n" +
                            "    (local \$future i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (local.set \$pair (call \$stream_new))\n" +
                            "    (local.set \$reader (i32.wrap_i64 (local.get \$pair)))\n" +
                            "    (local.set \$writer\n" +
                            "      (i32.wrap_i64\n" +
                            "        (i64.shr_u (local.get \$pair) (i64.const 32))))\n" +
                            "    (call \$response_new\n" +
                            "      (call \$fields_new)\n" +
                            "      (i32.const 1)\n" +
                            "      (local.get \$reader)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 96))\n" +
                            "    (local.set \$response (i32.load (i32.const 96)))\n" +
                            "    (local.set \$response_future (i32.load (i32.const 100)))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$response_future_read\n" +
                            "        (local.get \$response_future)\n" +
                            "        (i32.const 176)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const -1))\n" +
                            "      (then unreachable))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$stream_write\n" +
                            "        (local.get \$writer)\n" +
                            "        (i32.const 32)\n" +
                            "        (i32.const 4)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 64))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_writable (local.get \$writer))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$response_future_read\n" +
                            "        (local.get \$response_future)\n" +
                            "        (i32.const 176)))\n" +
                            "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                            "      (then\n" +
                            "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "        (call \$waitable_join\n" +
                            "          (local.get \$response_future)\n" +
                            "          (local.get \$waitable_set))\n" +
                            "        (if\n" +
                            "          (i32.ne\n" +
                            "            (call \$waitable_set_wait\n" +
                            "              (local.get \$waitable_set)\n" +
                            "              (i32.const 192))\n" +
                            "            (i32.const 4))\n" +
                            "          (then unreachable))\n" +
                            "        (call \$waitable_set_drop (local.get \$waitable_set))\n" +
                            "        (local.set \$status\n" +
                            "          (call \$response_future_read\n" +
                            "            (local.get \$response_future)\n" +
                            "            (i32.const 176)))))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 176))" +
                            " (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$response_consume\n" +
                            "      (local.get \$response)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 128))\n" +
                            "    (local.set \$stream (i32.load (i32.const 128)))\n" +
                            "    (local.set \$future (i32.load (i32.const 132)))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$future_read\n" +
                            "        (local.get \$future)\n" +
                            "        (i32.const 160)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 160))" +
                            " (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$stream_read\n" +
                            "        (local.get \$stream)\n" +
                            "        (i32.const 64)\n" +
                            "        (i32.const 4)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 65))\n" +
                            "      (then unreachable))\n" +
                            "    (i32.add\n" +
                            "      (i32.add\n" +
                            "        (i32.add\n" +
                            "          (i32.add\n" +
                            "            (local.get \$status)\n" +
                            "            (i32.load8_u (i32.const 64)))\n" +
                            "          (i32.load8_u (i32.const 65)))\n" +
                            "        (i32.load8_u (i32.const 66)))\n" +
                            "      (i32.load8_u (i32.const 67))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().build())
                .build()

        assertEquals(501L, plugin.call("api.run"))
    }

    @Test
    fun linksSocketsDnsTcpAndUdpStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-sockets;

                world plugin {
                  import wasi:sockets/types@$version;
                  import wasi:sockets/ip-name-lookup@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    variant ip-address {
                      ipv4(ipv4-address),
                      ipv6(ipv6-address),
                    }

                    resource tcp-socket {
                      create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                      get-address-family: func() -> ip-address-family;
                    }

                    resource udp-socket {
                      create: static func(address-family: ip-address-family) -> result<udp-socket, error-code>;
                      get-address-family: func() -> ip-address-family;
                    }
                  }

                  interface ip-name-lookup {
                    use types.{ip-address};
                    variant error-code {
                      access-denied,
                      invalid-argument,
                      name-unresolvable,
                      temporary-resolver-failure,
                      permanent-resolver-failure,
                      other(option<string>),
                    }
                    resolve-addresses: async func(name: string) -> result<list<ip-address>, error-code>;
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:sockets/ip-name-lookup@$version\"" +
                            " \"resolve-addresses\" (func \$resolve" +
                            " (param i32) (param i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/ip-name-lookup@$version\"" +
                            " \"[async-lower][future-read-0]resolve-addresses\"" +
                            " (func \$resolve_future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[static]tcp-socket.create\" (func \$tcp_create" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]tcp-socket.get-address-family\" (func" +
                            " \$tcp_family (param i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[static]udp-socket.create\" (func \$udp_create" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]udp-socket.get-address-family\" (func" +
                            " \$udp_family (param i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (data (i32.const 16) \"127.0.0.1\")\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$tcp i32)\n" +
                            "    (local \$udp i32)\n" +
                            "    (local \$future i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local.set \$future (call \$resolve (i32.const 16)" +
                            " (i32.const 9)))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$resolve_future_read (local.get \$future)" +
                            " (i32.const 64)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then (return (i32.const 91))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 90))))\n" +
                            "    (if (i32.eqz (i32.load (i32.const 72)))\n" +
                            "      (then (return (i32.const 89))))\n" +
                            "    (call \$tcp_create (i32.const 0) (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 88))))\n" +
                            "    (local.set \$tcp (i32.load (i32.const 100)))\n" +
                            "    (call \$udp_create (i32.const 0) (i32.const 112))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 112))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 87))))\n" +
                            "    (local.set \$udp (i32.load (i32.const 116)))\n" +
                            "    (i32.add\n" +
                            "      (i32.mul (call \$tcp_family (local.get \$tcp))" +
                            " (i32.const 10))\n" +
                            "      (call \$udp_family (local.get \$udp))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                .build()

        assertEquals(0L, plugin.call("api.run"))
    }

    @Test
    fun tcpBindReservesAddressUntilSocketDropStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-tcp-bind;

                world plugin {
                  import wasi:sockets/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    record ipv4-socket-address {
                      port: u16,
                      address: ipv4-address,
                    }

                    record ipv6-socket-address {
                      port: u16,
                      flow-info: u32,
                      address: ipv6-address,
                      scope-id: u32,
                    }

                    variant ip-socket-address {
                      ipv4(ipv4-socket-address),
                      ipv6(ipv6-socket-address),
                    }

                    resource tcp-socket {
                      create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                      bind: func(local-address: ip-socket-address) -> result<_, error-code>;
                      get-local-address: func() -> result<ip-socket-address, error-code>;
                    }
                  }
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
                          (import "wasi:sockets/types@$version" "[static]tcp-socket.create"
                            (func ${'$'}tcp_create (param i32) (param i32)))
                          (import "wasi:sockets/types@$version" "[method]tcp-socket.bind"
                            (func ${'$'}tcp_bind
                              (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
                          (import "wasi:sockets/types@$version" "[method]tcp-socket.get-local-address"
                            (func ${'$'}tcp_local (param i32) (param i32)))
                          (import "wasi:sockets/types@$version" "[resource-drop]tcp-socket"
                            (func ${'$'}tcp_drop (param i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}bind_localhost
                            (param ${'$'}socket i32)
                            (param ${'$'}port i32)
                            (param ${'$'}result i32)
                            (call ${'$'}tcp_bind
                              (local.get ${'$'}socket)
                              (i32.const 0)
                              (local.get ${'$'}port)
                              (i32.const 127)
                              (i32.const 0)
                              (i32.const 0)
                              (i32.const 1)
                              (i32.const 0)
                              (i32.const 0)
                              (i32.const 0)
                              (i32.const 0)
                              (i32.const 0)
                              (i32.const 0)
                              (local.get ${'$'}result)))
                          (func ${'$'}run (result i32)
                            (local ${'$'}first i32)
                            (local ${'$'}second i32)
                            (local ${'$'}port i32)
                            (call ${'$'}tcp_create (i32.const 0) (i32.const 64))
                            (if (i32.ne (i32.load8_u (i32.const 64)) (i32.const 0))
                              (then (return (i32.const 90))))
                            (local.set ${'$'}first (i32.load (i32.const 68)))
                            (call ${'$'}bind_localhost (local.get ${'$'}first) (i32.const 0) (i32.const 96))
                            (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                              (then (return (i32.const 89))))
                            (call ${'$'}tcp_local (local.get ${'$'}first) (i32.const 128))
                            (if (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                              (then (return (i32.const 88))))
                            (local.set ${'$'}port (i32.load16_u (i32.const 136)))
                            (if (i32.eqz (local.get ${'$'}port))
                              (then (return (i32.const 87))))
                            (call ${'$'}tcp_create (i32.const 0) (i32.const 160))
                            (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 0))
                              (then (return (i32.const 86))))
                            (local.set ${'$'}second (i32.load (i32.const 164)))
                            (call ${'$'}bind_localhost (local.get ${'$'}second) (local.get ${'$'}port) (i32.const 192))
                            (if (i32.ne (i32.load8_u (i32.const 192)) (i32.const 1))
                              (then (return (i32.const 85))))
                            (if (i32.ne (i32.load8_u (i32.const 196)) (i32.const 7))
                              (then (return (i32.const 84))))
                            (call ${'$'}tcp_drop (local.get ${'$'}first))
                            (call ${'$'}bind_localhost (local.get ${'$'}second) (local.get ${'$'}port) (i32.const 224))
                            (if (i32.ne (i32.load8_u (i32.const 224)) (i32.const 0))
                              (then (return (i32.const 83))))
                            (i32.const 42))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                .build()

        assertEquals(42L, plugin.call("api.run"))
    }

    @Test
    fun performsTcpConnectAndUdpSendStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val serverFailure = AtomicReference<Throwable?>()
        val tcpAccepted = AtomicReference<Boolean>(false)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tcpServer ->
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { udpServer ->
                udpServer.soTimeout = 2_000
                val tcpThread =
                    Thread(
                        {
                            try {
                                tcpServer.accept().use { tcpAccepted.set(true) }
                            } catch (e: Throwable) {
                                serverFailure.set(e)
                            }
                        },
                        "wasi3-tcp-connect-test",
                    )
                tcpThread.isDaemon = true
                tcpThread.start()

                val witPackage =
                    WitPackage.parse(
                        """
                        package example:wasi3-socket-io;

                        world plugin {
                          import wasi:sockets/types@$version;
                          export api;
                        }

                        interface api {
                          run: func() -> u32;
                        }

                        package wasi:sockets@$version {
                          interface types {
                            variant error-code {
                              access-denied,
                              not-supported,
                              invalid-argument,
                              out-of-memory,
                              timeout,
                              invalid-state,
                              address-not-bindable,
                              address-in-use,
                              remote-unreachable,
                              connection-refused,
                              connection-broken,
                              connection-reset,
                              connection-aborted,
                              datagram-too-large,
                              other(option<string>),
                            }

                            enum ip-address-family {
                              ipv4,
                              ipv6,
                            }

                            type ipv4-address = tuple<u8, u8, u8, u8>;
                            type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                            record ipv4-socket-address {
                              port: u16,
                              address: ipv4-address,
                            }

                            record ipv6-socket-address {
                              port: u16,
                              flow-info: u32,
                              address: ipv6-address,
                              scope-id: u32,
                            }

                            variant ip-socket-address {
                              ipv4(ipv4-socket-address),
                              ipv6(ipv6-socket-address),
                            }

                            resource tcp-socket {
                              create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                              connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
                              get-local-address: func() -> result<ip-socket-address, error-code>;
                              get-remote-address: func() -> result<ip-socket-address, error-code>;
                            }

                            resource udp-socket {
                              create: static func(address-family: ip-address-family) -> result<udp-socket, error-code>;
                              send: async func(data: list<u8>, remote-address: option<ip-socket-address>) -> result<_, error-code>;
                            }
                          }
                        }
                        """
                            .trimIndent()
                    )
                val plugin =
                    WasmPlugin.builder(witPackage)
                        .withModule(
                            Wat2Wasm.parse(
                                "(module\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[static]tcp-socket.create\" (func" +
                                    " \$tcp_create (param i32) (param i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[async-lower][method]tcp-socket.connect\" (func" +
                                    " \$tcp_connect (param i32 i32) (result i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                                    " (func \$waitable_set_new (result i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                                    " (func \$waitable_join (param i32 i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                                    " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                                    " (func \$waitable_set_drop (param i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[method]tcp-socket.get-local-address\" (func" +
                                    " \$tcp_local (param i32) (param i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[method]tcp-socket.get-remote-address\" (func" +
                                    " \$tcp_remote (param i32) (param i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[static]udp-socket.create\" (func" +
                                    " \$udp_create (param i32) (param i32)))\n" +
                                    "  (import \"wasi:sockets/types@$version\"" +
                                    " \"[async-lower][method]udp-socket.send\" (func" +
                                    " \$udp_send (param i32 i32) (result i32)))\n" +
                                    "  (memory (export \"memory\") 1)\n" +
                                    "  (data (i32.const 16) \"ping\")\n" +
                                    "  (func \$run (result i32)\n" +
                                    "    (local \$tcp i32)\n" +
                                    "    (local \$udp i32)\n" +
                                    "    (local \$status i32)\n" +
                                    "    (local \$subtask i32)\n" +
                                    "    (local \$waitable_set i32)\n" +
                                    "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 90))))\n" +
                                    "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                                    "    (i32.store (i32.const 32) (local.get \$tcp))\n" +
                                    "    (i32.store8 (i32.const 36) (i32.const 0))\n" +
                                    "    (i32.store16 (i32.const 40) (i32.const " +
                                    tcpServer.localPort +
                                    "))\n" +
                                    "    (i32.store8 (i32.const 42) (i32.const 127))\n" +
                                    "    (i32.store8 (i32.const 43) (i32.const 0))\n" +
                                    "    (i32.store8 (i32.const 44) (i32.const 0))\n" +
                                    "    (i32.store8 (i32.const 45) (i32.const 1))\n" +
                                    "    (local.set \$status\n" +
                                    "      (call \$tcp_connect (i32.const 32) (i32.const 80)))\n" +
                                    "    (if (i32.ne (local.get \$status) (i32.const 2))\n" +
                                    "      (then\n" +
                                    "        (if (i32.ne (i32.and (local.get \$status) (i32.const 15)) (i32.const 1))\n" +
                                    "          (then (return (i32.const 84))))\n" +
                                    "        (local.set \$subtask (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                                    "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                    "        (call \$waitable_join (local.get \$subtask) (local.get \$waitable_set))\n" +
                                    "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 304)) (i32.const 1))\n" +
                                    "          (then (return (i32.const 82))))\n" +
                                    "        (if (i32.ne (i32.load (i32.const 304)) (local.get \$subtask))\n" +
                                    "          (then (return (i32.const 81))))\n" +
                                    "        (if (i32.ne (i32.load (i32.const 308)) (i32.const 2))\n" +
                                    "          (then (return (i32.const 80))))\n" +
                                    "        (call \$waitable_set_drop (local.get \$waitable_set))))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 89))))\n" +
                                    "    (call \$tcp_local (local.get \$tcp) (i32.const 96))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 88))))\n" +
                                    "    (call \$tcp_remote (local.get \$tcp) (i32.const 144))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 144))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 87))))\n" +
                                    "    (call \$udp_create (i32.const 0) (i32.const 192))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 192))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 86))))\n" +
                                    "    (local.set \$udp (i32.load (i32.const 196)))\n" +
                                    "    (i32.store (i32.const 224) (local.get \$udp))\n" +
                                    "    (i32.store (i32.const 228) (i32.const 16))\n" +
                                    "    (i32.store (i32.const 232) (i32.const 4))\n" +
                                    "    (i32.store8 (i32.const 236) (i32.const 1))\n" +
                                    "    (i32.store8 (i32.const 240) (i32.const 0))\n" +
                                    "    (i32.store16 (i32.const 244) (i32.const " +
                                    udpServer.localPort +
                                    "))\n" +
                                    "    (i32.store8 (i32.const 246) (i32.const 127))\n" +
                                    "    (i32.store8 (i32.const 247) (i32.const 0))\n" +
                                    "    (i32.store8 (i32.const 248) (i32.const 0))\n" +
                                    "    (i32.store8 (i32.const 249) (i32.const 1))\n" +
                                    "    (local.set \$status\n" +
                                    "      (call \$udp_send (i32.const 224) (i32.const 288)))\n" +
                                    "    (if (i32.ne (local.get \$status) (i32.const 2))\n" +
                                    "      (then\n" +
                                    "        (if (i32.ne (i32.and (local.get \$status) (i32.const 15)) (i32.const 1))\n" +
                                    "          (then (return (i32.const 83))))\n" +
                                    "        (local.set \$subtask (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                                    "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                    "        (call \$waitable_join (local.get \$subtask) (local.get \$waitable_set))\n" +
                                    "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 304)) (i32.const 1))\n" +
                                    "          (then (return (i32.const 83))))\n" +
                                    "        (if (i32.ne (i32.load (i32.const 304)) (local.get \$subtask))\n" +
                                    "          (then (return (i32.const 83))))\n" +
                                    "        (if (i32.ne (i32.load (i32.const 308)) (i32.const 2))\n" +
                                    "          (then (return (i32.const 83))))\n" +
                                    "        (call \$waitable_set_drop (local.get \$waitable_set))))\n" +
                                    "    (if (i32.ne (i32.load8_u (i32.const 288))" +
                                    " (i32.const 0))\n" +
                                    "      (then (return (i32.const 85))))\n" +
                                    "    (i32.const 42))\n" +
                                    "  (export \"api.run\" (func \$run))\n" +
                                    ")\n"
                            )
                        )
                        .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                        .build()

                assertEquals(42L, plugin.call("api.run"))
                val packet = DatagramPacket(ByteArray(16), 16)
                udpServer.receive(packet)
                tcpThread.join(2_000L)

                assertEquals(
                    "ping",
                    String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1),
                )
                assertEquals(true, tcpAccepted.get())
            }
        }

        if (serverFailure.get() != null) {
            throw AssertionError("TCP test server failed", serverFailure.get())
        }
    }

    @Test
    fun receivesTcpDataAfterReceiveStreamIsCreatedStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val serverFailure = AtomicReference<Throwable?>()
        val allowWrite = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tcpServer ->
            val tcpThread =
                Thread(
                    {
                        try {
                            tcpServer.accept().use { socket ->
                                if (!allowWrite.await(2, TimeUnit.SECONDS)) {
                                    throw AssertionError("Timed out waiting to write TCP payload")
                                }
                                socket
                                    .getOutputStream()
                                    .write("late".toByteArray(StandardCharsets.ISO_8859_1))
                                socket.getOutputStream().flush()
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "wasi3-tcp-lazy-receive-test",
                )
            tcpThread.isDaemon = true
            tcpThread.start()

            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-tcp-lazy-receive;

                    world plugin {
                      import wasi:sockets/types@$version;
                      export api;
                    }

                    interface api {
                      receive: func() -> stream<u8>;
                    }

                    package wasi:sockets@$version {
                      interface types {
                        variant error-code {
                          access-denied,
                          not-supported,
                          invalid-argument,
                          out-of-memory,
                          timeout,
                          invalid-state,
                          address-not-bindable,
                          address-in-use,
                          remote-unreachable,
                          connection-refused,
                          connection-broken,
                          connection-reset,
                          connection-aborted,
                          datagram-too-large,
                          other(option<string>),
                        }

                        enum ip-address-family {
                          ipv4,
                          ipv6,
                        }

                        type ipv4-address = tuple<u8, u8, u8, u8>;
                        type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                        record ipv4-socket-address {
                          port: u16,
                          address: ipv4-address,
                        }

                        record ipv6-socket-address {
                          port: u16,
                          flow-info: u32,
                          address: ipv6-address,
                          scope-id: u32,
                        }

                        variant ip-socket-address {
                          ipv4(ipv4-socket-address),
                          ipv6(ipv6-socket-address),
                        }

                        resource tcp-socket {
                          create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                          connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
                          receive: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val wasi = WasiPreview3.builder().withNetworking().build()
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[static]tcp-socket.create\" (func" +
                                " \$tcp_create (param i32) (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.connect\" (func" +
                                " \$tcp_connect (param i32 i32 i32 i32 i32 i32" +
                                " i32 i32 i32 i32 i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][future-read-0][method]tcp-socket.connect\"" +
                                " (func \$connect_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                                " (func \$waitable_set_new (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                                " (func \$waitable_join (param i32 i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                                " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                                " (func \$waitable_set_drop (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.receive\" (func" +
                                " \$tcp_receive (param i32 i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (func \$receive (result i32)\n" +
                                "    (local \$tcp i32)\n" +
                                "    (local \$stream i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (local \$waitable_set i32)\n" +
                                "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                                "    (local.set \$future\n" +
                                "      (call \$tcp_connect\n" +
                                "      (local.get \$tcp)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const " +
                                tcpServer.localPort +
                                ")\n" +
                                "      (i32.const 127)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 1)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$connect_future_read (local.get \$future)" +
                                " (i32.const 80)))\n" +
                                "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                                "      (then\n" +
                                "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                "        (call \$waitable_join (local.get \$future) (local.get \$waitable_set))\n" +
                                "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 192)) (i32.const 4))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 192)) (local.get \$future))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 196)) (i32.const 0))\n" +
                                "          (then unreachable))\n" +
                                "        (call \$waitable_set_drop (local.get \$waitable_set)))\n" +
                                "      (else\n" +
                                "        (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "          (then unreachable))))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (call \$tcp_receive (local.get \$tcp)" +
                                " (i32.const 96))\n" +
                                "    (local.set \$stream (i32.load (i32.const 96)))\n" +
                                "    (local.get \$stream))\n" +
                                "  (export \"api.receive\" (func \$receive))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(wasi)
                    .build()

            val stream = plugin.call("api.receive") as WitStream<*>
            runBlocking {
                val readable =
                    async {
                        wasi.awaitStreamReadable(stream)
                        "readable"
                    }
                delay(50L)

                assertFalse(readable.isCompleted)

                allowWrite.countDown()
                assertEquals("readable", readable.await())
            }

            assertArrayEquals(
                "late".toByteArray(StandardCharsets.ISO_8859_1),
                wasi.streamBytes(stream),
            )
            tcpThread.join(2_000L)
        }

        if (serverFailure.get() != null) {
            throw AssertionError("TCP lazy receive test server failed", serverFailure.get())
        }
    }

    @Test
    fun readsTcpReceiveStreamWithCanonicalIntrinsicsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tcpServer ->
            val tcpThread =
                Thread(
                    {
                        try {
                            tcpServer.accept().use { socket ->
                                socket
                                    .getOutputStream()
                                    .write("wave".toByteArray(StandardCharsets.ISO_8859_1))
                                socket.getOutputStream().flush()
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "wasi3-tcp-receive-canonical-test",
                )
            tcpThread.isDaemon = true
            tcpThread.start()

            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-tcp-receive-canonical;

                    world plugin {
                      import wasi:sockets/types@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:sockets@$version {
                      interface types {
                        variant error-code {
                          access-denied,
                          not-supported,
                          invalid-argument,
                          out-of-memory,
                          timeout,
                          invalid-state,
                          address-not-bindable,
                          address-in-use,
                          remote-unreachable,
                          connection-refused,
                          connection-broken,
                          connection-reset,
                          connection-aborted,
                          datagram-too-large,
                          other(option<string>),
                        }

                        enum ip-address-family {
                          ipv4,
                          ipv6,
                        }

                        type ipv4-address = tuple<u8, u8, u8, u8>;
                        type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                        record ipv4-socket-address {
                          port: u16,
                          address: ipv4-address,
                        }

                        record ipv6-socket-address {
                          port: u16,
                          flow-info: u32,
                          address: ipv6-address,
                          scope-id: u32,
                        }

                        variant ip-socket-address {
                          ipv4(ipv4-socket-address),
                          ipv6(ipv6-socket-address),
                        }

                        resource tcp-socket {
                          create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                          connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
                          receive: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[static]tcp-socket.create\" (func" +
                                " \$tcp_create (param i32) (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.connect\" (func" +
                                " \$tcp_connect (param i32 i32 i32 i32 i32 i32" +
                                " i32 i32 i32 i32 i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][future-read-0][method]tcp-socket.connect\"" +
                                " (func \$connect_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                                " (func \$waitable_set_new (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                                " (func \$waitable_join (param i32 i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                                " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                                " (func \$waitable_set_drop (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.receive\" (func" +
                                " \$tcp_receive (param i32 i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][stream-read-0][method]tcp-socket.receive\"" +
                                " (func \$stream_read (param i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][future-read-1][method]tcp-socket.receive\"" +
                                " (func \$future_read (param i32 i32) (result i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$tcp i32)\n" +
                                "    (local \$stream i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (local \$read_status i32)\n" +
                                "    (local \$waitable_set i32)\n" +
                                "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                                "    (local.set \$future\n" +
                                "      (call \$tcp_connect\n" +
                                "      (local.get \$tcp)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const " +
                                tcpServer.localPort +
                                ")\n" +
                                "      (i32.const 127)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 1)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$connect_future_read (local.get \$future)" +
                                " (i32.const 80)))\n" +
                                "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                                "      (then\n" +
                                "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                "        (call \$waitable_join (local.get \$future) (local.get \$waitable_set))\n" +
                                "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 192)) (i32.const 4))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 192)) (local.get \$future))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 196)) (i32.const 0))\n" +
                                "          (then unreachable))\n" +
                                "        (call \$waitable_set_drop (local.get \$waitable_set)))\n" +
                                "      (else\n" +
                                "        (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "          (then unreachable))))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (call \$tcp_receive (local.get \$tcp)" +
                                " (i32.const 96))\n" +
                                "    (local.set \$stream (i32.load (i32.const 96)))\n" +
                                "    (local.set \$future (i32.load (i32.const 100)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$future_read\n" +
                                "        (local.get \$future)\n" +
                                "        (i32.const 160)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const -1))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$stream_read\n" +
                                "        (local.get \$stream)\n" +
                                "        (i32.const 128)\n" +
                                "        (i32.const 4)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 64))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$read_status (local.get \$status))\n" +
                                "    (if (i32.ne\n" +
                                "      (call \$stream_read\n" +
                                "        (local.get \$stream)\n" +
                                "        (i32.const 144)\n" +
                                "        (i32.const 4))\n" +
                                "      (i32.const 1))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$future_read\n" +
                                "        (local.get \$future)\n" +
                                "        (i32.const 160)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 160))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (i32.add\n" +
                                "      (i32.add\n" +
                                "        (i32.add\n" +
                                "          (i32.add\n" +
                                "            (local.get \$read_status)\n" +
                                "            (i32.load8_u (i32.const 128)))\n" +
                                "          (i32.load8_u (i32.const 129)))\n" +
                                "        (i32.load8_u (i32.const 130)))\n" +
                                "      (i32.load8_u (i32.const 131))))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                    .build()

            assertEquals(499L, plugin.call("api.run"))
            tcpThread.join(2_000L)
        }

        if (serverFailure.get() != null) {
            throw AssertionError("TCP canonical receive test server failed", serverFailure.get())
        }
    }

    @Test
    fun sendsTcpStreamWithCanonicalIntrinsicsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val serverFailure = AtomicReference<Throwable?>()
        val received = AtomicReference<String?>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tcpServer ->
            val tcpThread =
                Thread(
                    {
                        try {
                            tcpServer.accept().use { socket ->
                                val input = socket.getInputStream()
                                val bytes = ByteArray(4)
                                var offset = 0
                                while (offset < bytes.size) {
                                    val read = input.read(bytes, offset, bytes.size - offset)
                                    if (read < 0) {
                                        break
                                    }
                                    offset += read
                                }
                                received.set(String(bytes, 0, offset, StandardCharsets.ISO_8859_1))
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "wasi3-tcp-send-canonical-test",
                )
            tcpThread.isDaemon = true
            tcpThread.start()

            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-tcp-send-canonical;

                    world plugin {
                      import wasi:sockets/types@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:sockets@$version {
                      interface types {
                        variant error-code {
                          access-denied,
                          not-supported,
                          invalid-argument,
                          out-of-memory,
                          timeout,
                          invalid-state,
                          address-not-bindable,
                          address-in-use,
                          remote-unreachable,
                          connection-refused,
                          connection-broken,
                          connection-reset,
                          connection-aborted,
                          datagram-too-large,
                          other(option<string>),
                        }

                        enum ip-address-family {
                          ipv4,
                          ipv6,
                        }

                        type ipv4-address = tuple<u8, u8, u8, u8>;
                        type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                        record ipv4-socket-address {
                          port: u16,
                          address: ipv4-address,
                        }

                        record ipv6-socket-address {
                          port: u16,
                          flow-info: u32,
                          address: ipv6-address,
                          scope-id: u32,
                        }

                        variant ip-socket-address {
                          ipv4(ipv4-socket-address),
                          ipv6(ipv6-socket-address),
                        }

                        resource tcp-socket {
                          create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                          connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
                          send: func(data: stream<u8>) -> future<result<_, error-code>>;
                        }
                      }
                    }
                    """
                        .trimIndent()
                )
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[static]tcp-socket.create\" (func" +
                                " \$tcp_create (param i32) (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.connect\" (func" +
                                " \$tcp_connect (param i32 i32 i32 i32 i32 i32" +
                                " i32 i32 i32 i32 i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][future-read-0][method]tcp-socket.connect\"" +
                                " (func \$connect_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                                " (func \$waitable_set_new (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                                " (func \$waitable_join (param i32 i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                                " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                                " (func \$waitable_set_drop (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[stream-new-0][method]tcp-socket.send\"" +
                                " (func \$stream_new (result i64)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][stream-write-0][method]tcp-socket.send\"" +
                                " (func \$stream_write (param i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[stream-drop-writable-0][method]tcp-socket.send\"" +
                                " (func \$drop_writable (param i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[method]tcp-socket.send\" (func \$tcp_send" +
                                " (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:sockets/types@$version\"" +
                                " \"[async-lower][future-read-1][method]tcp-socket.send\"" +
                                " (func \$send_future_read (param i32 i32) (result i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (data (i32.const 32) \"send\")\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$tcp i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (local \$waitable_set i32)\n" +
                                "    (local \$pair i64)\n" +
                                "    (local \$reader i32)\n" +
                                "    (local \$writer i32)\n" +
                                "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                                "    (local.set \$future\n" +
                                "      (call \$tcp_connect\n" +
                                "        (local.get \$tcp)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const " +
                                tcpServer.localPort +
                                ")\n" +
                                "        (i32.const 127)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 1)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)\n" +
                                "        (i32.const 0)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$connect_future_read (local.get \$future)" +
                                " (i32.const 80)))\n" +
                                "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                                "      (then\n" +
                                "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                "        (call \$waitable_join (local.get \$future) (local.get \$waitable_set))\n" +
                                "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 192)) (i32.const 4))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 192)) (local.get \$future))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 196)) (i32.const 0))\n" +
                                "          (then unreachable))\n" +
                                "        (call \$waitable_set_drop (local.get \$waitable_set)))\n" +
                                "      (else\n" +
                                "        (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "          (then unreachable))))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$pair (call \$stream_new))\n" +
                                "    (local.set \$reader (i32.wrap_i64 (local.get \$pair)))\n" +
                                "    (local.set \$writer\n" +
                                "      (i32.wrap_i64\n" +
                                "        (i64.shr_u (local.get \$pair) (i64.const 32))))\n" +
                                "    (local.set \$future (call \$tcp_send (local.get \$tcp)" +
                                " (local.get \$reader)))\n" +
                                "    (if (i32.ne\n" +
                                "      (call \$send_future_read (local.get \$future)" +
                                " (i32.const 128))\n" +
                                "      (i32.const -1))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$stream_write (local.get \$writer)" +
                                " (i32.const 32) (i32.const 4)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 64))\n" +
                                "      (then unreachable))\n" +
                                "    (if (i32.ne\n" +
                                "      (call \$send_future_read (local.get \$future)" +
                                " (i32.const 128))\n" +
                                "      (i32.const -1))\n" +
                                "      (then unreachable))\n" +
                                "    (call \$drop_writable (local.get \$writer))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$send_future_read (local.get \$future)" +
                                " (i32.const 128)))\n" +
                                "    (if (i32.eq (local.get \$status) (i32.const -1))\n" +
                                "      (then\n" +
                                "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                                "        (call \$waitable_join (local.get \$future) (local.get \$waitable_set))\n" +
                                "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 192)) (i32.const 4))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 192)) (local.get \$future))\n" +
                                "          (then unreachable))\n" +
                                "        (if (i32.ne (i32.load (i32.const 196)) (i32.const 0))\n" +
                                "          (then unreachable))\n" +
                                "        (call \$waitable_set_drop (local.get \$waitable_set)))\n" +
                                "      (else\n" +
                                "        (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "          (then unreachable))))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 128))" +
                                " (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (i32.const 42))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                    .build()

            assertEquals(42L, plugin.call("api.run"))
            tcpThread.join(2_000L)
        }

        if (serverFailure.get() != null) {
            throw AssertionError("TCP canonical send test server failed", serverFailure.get())
        }
        assertEquals("send", received.get())
    }

    @Test
    fun receivesUdpDatagramStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val guestPort = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-udp-receive;

                world plugin {
                  import wasi:sockets/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    record ipv4-socket-address {
                      port: u16,
                      address: ipv4-address,
                    }

                    record ipv6-socket-address {
                      port: u16,
                      flow-info: u32,
                      address: ipv6-address,
                      scope-id: u32,
                    }

                    variant ip-socket-address {
                      ipv4(ipv4-socket-address),
                      ipv6(ipv6-socket-address),
                    }

                    resource udp-socket {
                      create: static func(address-family: ip-address-family) -> result<udp-socket, error-code>;
                      bind: func(local-address: ip-socket-address) -> result<_, error-code>;
                      receive: async func() -> result<tuple<list<u8>, ip-socket-address>, error-code>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[static]udp-socket.create\" (func \$udp_create" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]udp-socket.bind\" (func \$udp_bind" +
                            " (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32" +
                            " i32 i32 i32 i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[async-lower][method]udp-socket.receive\" (func" +
                            " \$udp_receive (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                            " (func \$waitable_set_new (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                            " (func \$waitable_join (param i32 i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                            " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                            " (func \$waitable_set_drop (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 512))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$udp i32)\n" +
                            "    (local \$data i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$subtask i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (call \$udp_create (i32.const 0) (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 90))))\n" +
                            "    (local.set \$udp (i32.load (i32.const 68)))\n" +
                            "    (call \$udp_bind\n" +
                            "      (local.get \$udp)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const " +
                            guestPort +
                            ")\n" +
                            "      (i32.const 127)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 1)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 89))))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$udp_receive (local.get \$udp) (i32.const 128)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 2))\n" +
                            "      (then\n" +
                            "        (if (i32.ne (i32.and (local.get \$status) (i32.const 15)) (i32.const 1))\n" +
                            "          (then (return (i32.const 85))))\n" +
                            "        (local.set \$subtask (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                            "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "        (call \$waitable_join (local.get \$subtask) (local.get \$waitable_set))\n" +
                            "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 304)) (i32.const 1))\n" +
                            "          (then (return (i32.const 85))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 304)) (local.get \$subtask))\n" +
                            "          (then (return (i32.const 85))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 308)) (i32.const 2))\n" +
                            "          (then (return (i32.const 85))))\n" +
                            "        (call \$waitable_set_drop (local.get \$waitable_set))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 128))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 88))))\n" +
                            "    (if (i32.ne (i32.load (i32.const 136))" +
                            " (i32.const 4))\n" +
                            "      (then (return (i32.const 87))))\n" +
                            "    (local.set \$data (i32.load (i32.const 132)))\n" +
                            "    (if (i32.ne (i32.load (local.get \$data))" +
                            " (i32.const 1735290736))\n" +
                            "      (then (return (i32.const 86))))\n" +
                            "    (i32.const 43))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().withNetworking().build())
                .build()

        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(
                {
                    try {
                        result.set(plugin.call("api.run"))
                    } catch (e: Throwable) {
                        failure.set(e)
                    }
                },
                "wasi3-udp-receive-component",
            )
        thread.start()

        DatagramSocket().use { sender ->
            val bytes = "pong".toByteArray(StandardCharsets.ISO_8859_1)
            val packet =
                DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), guestPort)
            repeat(10) {
                sender.send(packet)
                if (result.get() != null || failure.get() != null) {
                    return@repeat
                }
                runBlocking { delay(50L) }
            }
        }
        thread.join(2_000L)

        if (failure.get() != null) {
            throw AssertionError("UDP receive component failed", failure.get())
        }
        assertEquals(43L, result.get())
    }

    @Test
    fun acceptsTcpConnectionFromListenStreamStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val wasi = WasiPreview3.builder().withNetworking().build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-tcp-listen;

                world plugin {
                  import wasi:sockets/types@$version;
                  export api;
                }

                interface api {
                  use wasi:sockets/types@$version.{tcp-socket};
                  listen: func() -> tuple<stream<tcp-socket>, u32>;
                  accept: func(listener: stream<tcp-socket>) -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    record ipv4-socket-address {
                      port: u16,
                      address: ipv4-address,
                    }

                    record ipv6-socket-address {
                      port: u16,
                      flow-info: u32,
                      address: ipv6-address,
                      scope-id: u32,
                    }

                    variant ip-socket-address {
                      ipv4(ipv4-socket-address),
                      ipv6(ipv6-socket-address),
                    }

                    resource tcp-socket {
                      create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                      listen: func() -> result<stream<tcp-socket>, error-code>;
                      get-local-address: func() -> result<ip-socket-address, error-code>;
                      get-remote-address: func() -> result<ip-socket-address, error-code>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[static]tcp-socket.create\" (func" +
                            " \$tcp_create (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]tcp-socket.listen\" (func \$tcp_listen" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[async-lower][stream-read-0][method]tcp-socket.listen\"" +
                            " (func \$tcp_listen_read (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]tcp-socket.get-local-address\" (func" +
                            " \$tcp_local (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]tcp-socket.get-remote-address\" (func" +
                            " \$tcp_remote (param i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (func \$listen (result i32)\n" +
                            "    (local \$tcp i32)\n" +
                            "    (local \$stream i32)\n" +
                            "    (local \$port i32)\n" +
                            "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 90)))))\n" +
                            "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                            "    (call \$tcp_listen (local.get \$tcp)" +
                            " (i32.const 80))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 89)))))\n" +
                            "    (local.set \$stream (i32.load (i32.const 84)))\n" +
                            "    (call \$tcp_local (local.get \$tcp)" +
                            " (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 88)))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 100))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 87)))))\n" +
                            "    (local.set \$port (i32.load16_u (i32.const 104)))\n" +
                            "    (i32.store (i32.const 160) (local.get \$stream))\n" +
                            "    (i32.store (i32.const 164) (local.get \$port))\n" +
                            "    (i32.const 160))\n" +
                            "  (func \$listen_error (param \$code i32) (result i32)\n" +
                            "    (i32.store (i32.const 160) (i32.const 0))\n" +
                            "    (i32.store (i32.const 164) (local.get \$code))\n" +
                            "    (i32.const 160))\n" +
                            "  (func \$accept (param \$stream i32) (result i32)\n" +
                            "    (local \$accepted i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local.set \$status\n" +
                            "      (call \$tcp_listen_read\n" +
                            "        (local.get \$stream)\n" +
                            "        (i32.const 192)\n" +
                            "        (i32.const 1)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 16))\n" +
                            "      (then (return (i32.const 86))))\n" +
                            "    (local.set \$accepted (i32.load (i32.const 192)))\n" +
                            "    (call \$tcp_remote (local.get \$accepted) (i32.const 208))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 208))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 85))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 212))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 84))))\n" +
                            "    (if (i32.eqz (i32.load16_u (i32.const 216)))\n" +
                            "      (then (return (i32.const 83))))\n" +
                            "    (i32.const 42))\n" +
                            "  (export \"api.listen\" (func \$listen))\n" +
                            "  (export \"api.accept\" (func \$accept))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        val result = plugin.call("api.listen") as List<*>
        val stream = result[0] as WitStream<*>
        val port = (result[1] as Number).toInt()
        assertTrue(port > 1024, "TCP listen diagnostic code or privileged port: $port")

        val clientFailure = AtomicReference<Throwable?>()
        val clientThread =
            Thread(
                {
                    try {
                        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                            socket
                                .getOutputStream()
                                .write("hello".toByteArray(StandardCharsets.ISO_8859_1))
                        }
                    } catch (e: Throwable) {
                        clientFailure.set(e)
                    }
                },
                "wasi3-tcp-listen-client",
            )
        clientThread.isDaemon = true

        runBlocking {
            val readable =
                async {
                    wasi.awaitStreamReadable(stream)
                    "readable"
                }
            delay(50L)

            assertFalse(readable.isCompleted)

            clientThread.start()
            assertEquals("readable", readable.await())
        }
        assertEquals(42L, plugin.call("api.accept", stream))
        clientThread.join(2_000L)

        if (clientFailure.get() != null) {
            throw AssertionError("TCP listen client failed", clientFailure.get())
        }

        val helperClientFailure = AtomicReference<Throwable?>()
        val helperClientThread =
            Thread(
                {
                    try {
                        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                            socket
                                .getOutputStream()
                                .write("again".toByteArray(StandardCharsets.ISO_8859_1))
                        }
                    } catch (e: Throwable) {
                        helperClientFailure.set(e)
                    }
                },
                "wasi3-tcp-listen-helper-client",
            )
        helperClientThread.isDaemon = true
        helperClientThread.start()
        val accepted =
            when (val acceptResult = wasi.acceptTcpConnection(stream)) {
                is WitResult.Ok -> acceptResult.value()
                is WitResult.Err ->
                    throw AssertionError("TCP accept failed: ${acceptResult.value()}")
            }
        helperClientThread.join(2_000L)

        if (helperClientFailure.get() != null) {
            throw AssertionError("TCP listen helper client failed", helperClientFailure.get())
        }
        val local =
            when (val localResult = wasi.tcpLocalAddress(accepted)) {
                is WitResult.Ok -> localResult.value()
                is WitResult.Err ->
                    throw AssertionError("accepted local address failed: ${localResult.value()}")
            }
        val remote =
            when (val remoteResult = wasi.tcpRemoteAddress(accepted)) {
                is WitResult.Ok -> remoteResult.value()
                is WitResult.Err ->
                    throw AssertionError("accepted remote address failed: ${remoteResult.value()}")
            }

        assertEquals(port, socketAddressPort(local))
        assertTrue(socketAddressPort(remote) > 0)
    }

    @Test
    fun acceptsTcpConnectionWithStableAsyncAcceptWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val wasi = WasiPreview3.builder().withNetworking().build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-tcp-accept;

                world plugin {
                  import wasi:sockets/types@$version;
                  export api;
                }

                interface api {
                  listen: func() -> u32;
                  accept: func() -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    record ipv4-socket-address {
                      port: u16,
                      address: ipv4-address,
                    }

                    record ipv6-socket-address {
                      port: u16,
                      flow-info: u32,
                      address: ipv6-address,
                      scope-id: u32,
                    }

                    variant ip-socket-address {
                      ipv4(ipv4-socket-address),
                      ipv6(ipv6-socket-address),
                    }

                    resource tcp-socket {
                      create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                      listen: async func() -> result<_, error-code>;
                      accept: async func() -> result<tuple<tcp-socket, ip-socket-address>, error-code>;
                      get-local-address: func() -> result<ip-socket-address, error-code>;
                    }
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[static]tcp-socket.create\" (func" +
                            " \$tcp_create (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[async-lower][method]tcp-socket.listen\" (func" +
                            " \$tcp_listen (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[async-lower][method]tcp-socket.accept\" (func" +
                            " \$tcp_accept (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.new\"" +
                            " (func \$waitable_set_new (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable.join\"" +
                            " (func \$waitable_join (param i32 i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.wait\"" +
                            " (func \$waitable_set_wait (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\" \"waitable-set.drop\"" +
                            " (func \$waitable_set_drop (param i32)))\n" +
                            "  (import \"wasi:sockets/types@$version\"" +
                            " \"[method]tcp-socket.get-local-address\" (func" +
                            " \$tcp_local (param i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$server_tcp (mut i32) (i32.const 0))\n" +
                            "  (func \$listen (result i32)\n" +
                            "    (local \$tcp i32)\n" +
                            "    (local \$port i32)\n" +
                            "    (if (i32.ne\n" +
                            "        (call \$tcp_listen_checked (call \$tcp_create_checked))\n" +
                            "        (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 90)))))\n" +
                            "    (local.set \$tcp (i32.load (i32.const 68)))\n" +
                            "    (call \$tcp_local (local.get \$tcp) (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 89)))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 100))" +
                            " (i32.const 0))\n" +
                            "      (then (return (call \$listen_error (i32.const 88)))))\n" +
                            "    (local.set \$port (i32.load16_u (i32.const 104)))\n" +
                            "    (global.set \$server_tcp (local.get \$tcp))\n" +
                            "    (local.get \$port))\n" +
                            "  (func \$tcp_create_checked (result i32)\n" +
                            "    (call \$tcp_create (i32.const 0) (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 0))))\n" +
                            "    (i32.load (i32.const 68)))\n" +
                            "  (func \$tcp_listen_checked (param \$tcp i32) (result i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$subtask i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (if (i32.eqz (local.get \$tcp))\n" +
                            "      (then (return (i32.const 1))))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$tcp_listen (local.get \$tcp) (i32.const 80)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 2))\n" +
                            "      (then\n" +
                            "        (if (i32.ne (i32.and (local.get \$status) (i32.const 15)) (i32.const 1))\n" +
                            "          (then (return (i32.const 1))))\n" +
                            "        (local.set \$subtask (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                            "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "        (call \$waitable_join (local.get \$subtask) (local.get \$waitable_set))\n" +
                            "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 320)) (i32.const 1))\n" +
                            "          (then (return (i32.const 1))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 320)) (local.get \$subtask))\n" +
                            "          (then (return (i32.const 1))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 324)) (i32.const 2))\n" +
                            "          (then (return (i32.const 1))))\n" +
                            "        (call \$waitable_set_drop (local.get \$waitable_set))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 1))))\n" +
                            "    (i32.const 0))\n" +
                            "  (func \$listen_error (param \$code i32) (result i32)\n" +
                            "    (local.get \$code))\n" +
                            "  (func \$accept (result i32)\n" +
                            "    (local \$accepted i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$subtask i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (local.set \$status\n" +
                            "      (call \$tcp_accept (global.get \$server_tcp) (i32.const 192)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 2))\n" +
                            "      (then\n" +
                            "        (if (i32.ne (i32.and (local.get \$status) (i32.const 15)) (i32.const 1))\n" +
                            "          (then (return (i32.const 87))))\n" +
                            "        (local.set \$subtask (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                            "        (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "        (call \$waitable_join (local.get \$subtask) (local.get \$waitable_set))\n" +
                            "        (if (i32.ne (call \$waitable_set_wait (local.get \$waitable_set) (i32.const 320)) (i32.const 1))\n" +
                            "          (then (return (i32.const 81))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 320)) (local.get \$subtask))\n" +
                            "          (then (return (i32.const 80))))\n" +
                            "        (if (i32.ne (i32.load (i32.const 324)) (i32.const 2))\n" +
                            "          (then (return (i32.const 79))))\n" +
                            "        (call \$waitable_set_drop (local.get \$waitable_set))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 192))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 86))))\n" +
                            "    (local.set \$accepted (i32.load (i32.const 196)))\n" +
                            "    (if (i32.eqz (local.get \$accepted))\n" +
                            "      (then (return (i32.const 85))))\n" +
                            "    (call \$tcp_local (local.get \$accepted) (i32.const 240))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 240))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 84))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 244))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 83))))\n" +
                            "    (if (i32.eqz (i32.load16_u (i32.const 248)))\n" +
                            "      (then (return (i32.const 82))))\n" +
                            "    (i32.const 42))\n" +
                            "  (export \"api.listen\" (func \$listen))\n" +
                            "  (export \"api.accept\" (func \$accept))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        val port = (plugin.call("api.listen") as Number).toInt()
        assertTrue(port > 1024, "TCP listen diagnostic code or privileged port: $port")

        val clientFailure = AtomicReference<Throwable?>()
        val clientThread =
            Thread(
                {
                    try {
                        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                            socket
                                .getOutputStream()
                                .write("hello".toByteArray(StandardCharsets.ISO_8859_1))
                        }
                    } catch (e: Throwable) {
                        clientFailure.set(e)
                    }
                },
                "wasi3-tcp-accept-client",
            )
        clientThread.isDaemon = true
        clientThread.start()

        assertEquals(42L, plugin.call("api.accept"))
        clientThread.join(2_000L)

        if (clientFailure.get() != null) {
            throw AssertionError("TCP accept client failed", clientFailure.get())
        }
    }

    @Test
    fun asyncTcpListenUsesSuspendingSocketRuntimeWithoutSyncFallback() {
        val version = WasiPreview3.DEFAULT_VERSION
        val socketRuntime = AsyncOnlyListenRuntime()
        val wasiBuilder = WasiPreview3.builder().withNetworking()
        wasiBuilder.socketRuntime = socketRuntime
        val wasi = wasiBuilder.build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-async-tcp-listen;

                world plugin {
                  import wasi:sockets/types@$version;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:sockets@$version {
                  interface types {
                    variant error-code {
                      access-denied,
                      not-supported,
                      invalid-argument,
                      out-of-memory,
                      timeout,
                      invalid-state,
                      address-not-bindable,
                      address-in-use,
                      remote-unreachable,
                      connection-refused,
                      connection-broken,
                      connection-reset,
                      connection-aborted,
                      datagram-too-large,
                      other(option<string>),
                    }

                    enum ip-address-family {
                      ipv4,
                      ipv6,
                    }

                    type ipv4-address = tuple<u8, u8, u8, u8>;
                    type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

                    record ipv4-socket-address {
                      port: u16,
                      address: ipv4-address,
                    }

                    record ipv6-socket-address {
                      port: u16,
                      flow-info: u32,
                      address: ipv6-address,
                      scope-id: u32,
                    }

                    variant ip-socket-address {
                      ipv4(ipv4-socket-address),
                      ipv6(ipv6-socket-address),
                    }

                    resource tcp-socket {
                      create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
                      listen: async func() -> result<_, error-code>;
                      get-local-address: func() -> result<ip-socket-address, error-code>;
                    }
                  }
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
                          (import "wasi:sockets/types@$version" "[static]tcp-socket.create"
                            (func ${'$'}tcp_create (param i32) (param i32)))
                          (import "wasi:sockets/types@$version" "[async-lower][method]tcp-socket.listen"
                            (func ${'$'}tcp_listen (param i32 i32) (result i32)))
                          (import "wasi:sockets/types@$version" "waitable-set.new"
                            (func ${'$'}waitable_set_new (result i32)))
                          (import "wasi:sockets/types@$version" "waitable.join"
                            (func ${'$'}waitable_join (param i32 i32)))
                          (import "wasi:sockets/types@$version" "waitable-set.wait"
                            (func ${'$'}waitable_set_wait (param i32 i32) (result i32)))
                          (import "wasi:sockets/types@$version" "waitable-set.drop"
                            (func ${'$'}waitable_set_drop (param i32)))
                          (import "wasi:sockets/types@$version" "[method]tcp-socket.get-local-address"
                            (func ${'$'}tcp_local (param i32) (param i32)))
                          (memory (export "memory") 1)
                          (func ${'$'}run (result i32)
                            (local ${'$'}tcp i32)
                            (local ${'$'}status i32)
                            (local ${'$'}subtask i32)
                            (local ${'$'}waitable_set i32)
                            (call ${'$'}tcp_create (i32.const 0) (i32.const 64))
                            (if (i32.ne (i32.load8_u (i32.const 64)) (i32.const 0))
                              (then (return (i32.const 90))))
                            (local.set ${'$'}tcp (i32.load (i32.const 68)))
                            (local.set ${'$'}status
                              (call ${'$'}tcp_listen (local.get ${'$'}tcp) (i32.const 80)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 2))
                              (then
                                (if (i32.ne (i32.and (local.get ${'$'}status) (i32.const 15)) (i32.const 1))
                                  (then (return (i32.const 89))))
                                (local.set ${'$'}subtask (i32.shr_u (local.get ${'$'}status) (i32.const 4)))
                                (local.set ${'$'}waitable_set (call ${'$'}waitable_set_new))
                                (call ${'$'}waitable_join (local.get ${'$'}subtask) (local.get ${'$'}waitable_set))
                                (if (i32.ne (call ${'$'}waitable_set_wait (local.get ${'$'}waitable_set) (i32.const 320)) (i32.const 1))
                                  (then (return (i32.const 88))))
                                (if (i32.ne (i32.load (i32.const 320)) (local.get ${'$'}subtask))
                                  (then (return (i32.const 87))))
                                (if (i32.ne (i32.load (i32.const 324)) (i32.const 2))
                                  (then (return (i32.const 86))))
                                (call ${'$'}waitable_set_drop (local.get ${'$'}waitable_set))))
                            (if (i32.ne (i32.load8_u (i32.const 80)) (i32.const 0))
                              (then (return (i32.const 85))))
                            (call ${'$'}tcp_local (local.get ${'$'}tcp) (i32.const 96))
                            (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                              (then (return (i32.const 84))))
                            (if (i32.ne (i32.load8_u (i32.const 100)) (i32.const 0))
                              (then (return (i32.const 83))))
                            (i32.load16_u (i32.const 104)))
                          (export "api.run" (func ${'$'}run))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        assertEquals(45_678L, plugin.call("api.run"))
        assertFalse(socketRuntime.syncListenCalled)
        assertTrue(socketRuntime.suspendingListenCalled)
    }

    @Test
    fun linksCliClocksAndRandomStableImportsWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        var monotonicReads = 0
        val dispatchedHostTasks = AtomicInteger()
        val hostDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    dispatchedHostTasks.incrementAndGet()
                    Dispatchers.Default.dispatch(context, block)
                }
            }
        val wasi =
            WasiPreview3.builder()
                .withCoroutineDispatcher(hostDispatcher)
                .withArguments("guest.wasm", "alpha", "beta")
                .withEnvironment("MODE", "p3")
                .withInitialCwd("/work")
                .withFixedWallClock(KotlinInstant.fromEpochSeconds(1_700_000_000L, 42))
                .withWallClockResolutionNanos(123L)
                .withMonotonicClock {
                    monotonicReads += 1
                    if (monotonicReads == 1) 1_000_000L else 1_000_123L
                }
                .withMonotonicResolutionNanos(456L)
                .withSecureRandom(Random(7L))
                .withInsecureSeed(11L, 12L)
                .build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-sync;

                world plugin {
                  import wasi:cli/environment@$version;
                  import wasi:clocks/system-clock@$version;
                  import wasi:clocks/monotonic-clock@$version;
                  import wasi:random/random@$version;
                  import wasi:random/insecure-seed@$version;
                  export api;
                }

                interface api {
                  run: func() -> u64;
                }

                package wasi:cli@$version {
                  interface environment {
                    get-environment: func() -> list<tuple<string, string>>;
                    get-arguments: func() -> list<string>;
                    get-initial-cwd: func() -> option<string>;
                  }
                }

                package wasi:clocks@$version {
                  interface types {
                    type duration = u64;
                  }
                  interface system-clock {
                    use types.{duration};
                    record instant {
                      seconds: s64,
                      nanoseconds: u32,
                    }
                    now: func() -> instant;
                    get-resolution: func() -> duration;
                  }
                  interface monotonic-clock {
                    use types.{duration};
                    type mark = u64;
                    now: func() -> mark;
                    get-resolution: func() -> duration;
                    wait-for: async func(how-long: duration);
                  }
                }

                package wasi:random@$version {
                  interface random {
                    get-random-bytes: func(max-len: u64) -> list<u8>;
                    get-random-u64: func() -> u64;
                  }
                  interface insecure-seed {
                    get-insecure-seed: func() -> tuple<u64, u64>;
                  }
                }
                """
                    .trimIndent()
            )

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/environment@$version\"" +
                            " \"get-arguments\" (func \$args (param i32)))\n" +
                            "  (import \"wasi:cli/environment@$version\"" +
                            " \"get-environment\" (func \$env (param i32)))\n" +
                            "  (import \"wasi:cli/environment@$version\"" +
                            " \"get-initial-cwd\" (func \$cwd (param i32)))\n" +
                            "  (import \"wasi:clocks/system-clock@$version\"" +
                            " \"now\" (func \$system_now (param i32)))\n" +
                            "  (import \"wasi:clocks/system-clock@$version\"" +
                            " \"get-resolution\" (func \$system_resolution" +
                            " (result i64)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"now\" (func \$monotonic_now (result i64)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"get-resolution\" (func \$monotonic_resolution" +
                            " (result i64)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"[async-lower]wait-for\" (func \$monotonic_wait_for" +
                            " (param i64) (result i32)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"waitable-set.new\" (func \$waitable_set_new" +
                            " (result i32)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"waitable.join\" (func \$waitable_join" +
                            " (param i32 i32)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"waitable-set.wait\" (func \$waitable_set_wait" +
                            " (param i32 i32) (result i32)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@$version\"" +
                            " \"waitable-set.drop\" (func \$waitable_set_drop" +
                            " (param i32)))\n" +
                            "  (import \"wasi:random/random@$version\"" +
                            " \"get-random-bytes\" (func \$random_bytes" +
                            " (param i64) (param i32)))\n" +
                            "  (import \"wasi:random/insecure-seed@$version\"" +
                            " \"get-insecure-seed\" (func \$seed (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr\n" +
                            "      (i32.and\n" +
                            "        (i32.add (global.get \$heap)" +
                            " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                            "        (i32.xor\n" +
                            "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                            "          (i32.const -1))))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (local.get \$ptr) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i64)\n" +
                            "    (local \$status i32)\n" +
                            "    (local \$subtask i32)\n" +
                            "    (local \$waitable_set i32)\n" +
                            "    (call \$args (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load (i32.const 68))" +
                            " (i32.const 3)) (then unreachable))\n" +
                            "    (call \$env (i32.const 80))\n" +
                            "    (if (i32.ne (i32.load (i32.const 84))" +
                            " (i32.const 1)) (then unreachable))\n" +
                            "    (call \$cwd (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 1)) (then unreachable))\n" +
                            "    (if (i32.ne (i32.load (i32.const 104))" +
                            " (i32.const 5)) (then unreachable))\n" +
                            "    (call \$system_now (i32.const 112))\n" +
                            "    (if (i64.ne (i64.load (i32.const 112))" +
                            " (i64.const 1700000000)) (then unreachable))\n" +
                            "    (if (i64.ne (call \$system_resolution)" +
                            " (i64.const 123)) (then unreachable))\n" +
                            "    (if (i64.ne (call \$monotonic_now)" +
                            " (i64.const 123)) (then unreachable))\n" +
                            "    (if (i64.ne (call \$monotonic_resolution)" +
                            " (i64.const 456)) (then unreachable))\n" +
                            "    (if (i32.ne (call \$monotonic_wait_for (i64.const 0))" +
                            " (i32.const 2)) (then unreachable))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$monotonic_wait_for (i64.const 5000000)))\n" +
                            "    (if (i32.ne (i32.and (local.get \$status) (i32.const 15))" +
                            " (i32.const 1)) (then unreachable))\n" +
                            "    (local.set \$subtask\n" +
                            "      (i32.shr_u (local.get \$status) (i32.const 4)))\n" +
                            "    (local.set \$waitable_set (call \$waitable_set_new))\n" +
                            "    (call \$waitable_join (local.get \$subtask)" +
                            " (local.get \$waitable_set))\n" +
                            "    (if (i32.ne (call \$waitable_set_wait" +
                            " (local.get \$waitable_set) (i32.const 176))" +
                            " (i32.const 1)) (then unreachable))\n" +
                            "    (if (i32.ne (i32.load (i32.const 176))" +
                            " (local.get \$subtask)) (then unreachable))\n" +
                            "    (if (i32.ne (i32.load (i32.const 180))" +
                            " (i32.const 2)) (then unreachable))\n" +
                            "    (call \$waitable_set_drop (local.get \$waitable_set))\n" +
                            "    (call \$random_bytes (i64.const 4) (i32.const 128))\n" +
                            "    (if (i32.ne (i32.load (i32.const 132))" +
                            " (i32.const 4)) (then unreachable))\n" +
                            "    (call \$seed (i32.const 144))\n" +
                            "    (if (i64.ne (i64.load (i32.const 144))" +
                            " (i64.const 11)) (then unreachable))\n" +
                            "    (if (i64.ne (i64.load (i32.const 152))" +
                            " (i64.const 12)) (then unreachable))\n" +
                            "    (i64.const 7))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        assertEquals(7L, plugin.call("api.run"))
        assertTrue(dispatchedHostTasks.get() > 0)
    }

    @Test
    fun linksFilesystemPreopensAndDescriptorsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem")
        val probe = tempDir.resolve("probe.txt")
        try {
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:clocks@$version {
                      interface system-clock {
                        record instant {
                          seconds: s64,
                          nanoseconds: u32,
                        }
                      }
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        use wasi:clocks/system-clock@$version.{instant};

                        type filesize = u64;
                        type link-count = u64;

                        variant descriptor-type {
                          directory,
                          regular-file,
                          symbolic-link,
                          other(option<string>),
                        }

                        flags descriptor-flags {
                          read,
                          write,
                          file-integrity-sync,
                          data-integrity-sync,
                          requested-write-sync,
                          mutate-directory,
                        }

                        record descriptor-stat {
                          %type: descriptor-type,
                          link-count: link-count,
                          size: filesize,
                          data-access-timestamp: option<instant>,
                          data-modification-timestamp: option<instant>,
                          status-change-timestamp: option<instant>,
                        }

                        flags path-flags {
                          symlink-follow,
                        }

                        flags open-flags {
                          create,
                          directory,
                          exclusive,
                          truncate,
                        }

                        record directory-entry {
                          %type: descriptor-type,
                          name: string,
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        record metadata-hash-value {
                          lower: u64,
                          upper: u64,
                        }

                        resource descriptor {
                          open-at: async func(
                            path-flags: path-flags,
                            path: string,
                            open-flags: open-flags,
                            %flags: descriptor-flags,
                          ) -> result<descriptor, error-code>;
                          stat: async func() -> result<descriptor-stat, error-code>;
                          metadata-hash: async func() -> result<metadata-hash-value, error-code>;
                          read-directory: func() -> tuple<stream<directory-entry>, future<result<_, error-code>>>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
                    }
                    """
                        .trimIndent()
                )
            val wasi =
                WasiPreview3.builder().withPreopenedDirectory("/", tempDir.toString()).build()
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:filesystem/preopens@$version\"" +
                                " \"get-directories\" (func \$get_directories (param" +
                                " i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][method]descriptor.open-at\" (func \$open_at" +
                                " (param i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][method]descriptor.stat\" (func \$stat (param" +
                                " i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][method]descriptor.metadata-hash\" (func \$hash" +
                                " (param i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[method]descriptor.read-directory\" (func \$read_dir" +
                                " (param i32) (param i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][stream-read-0][method]descriptor.read-directory\"" +
                                " (func \$read_dir_stream (param i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][future-read-1][method]descriptor.read-directory\"" +
                                " (func \$read_dir_future (param i32 i32) (result i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (global \$heap (mut i32) (i32.const 256))\n" +
                                "  (data (i32.const 16) \"probe.txt\")\n" +
                                "  (func (export \"canonical_abi_realloc\")\n" +
                                "    (param \$old i32) (param \$old_size i32)\n" +
                                "    (param \$align i32) (param \$new_size i32)\n" +
                                "    (result i32)\n" +
                                "    (local \$ptr i32)\n" +
                                "    (local.set \$ptr\n" +
                                "      (i32.and\n" +
                                "        (i32.add (global.get \$heap)" +
                                " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                                "        (i32.xor\n" +
                                "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                                "          (i32.const -1))))\n" +
                                "    (global.set \$heap\n" +
                                "      (i32.add (local.get \$ptr) (local.get" +
                                " \$new_size)))\n" +
                                "    (local.get \$ptr))\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$base i32)\n" +
                                "    (local \$file i32)\n" +
                                "    (local \$dir_stream i32)\n" +
                                "    (local \$dir_future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (call \$get_directories (i32.const 48))\n" +
                                "    (if (i32.ne (i32.load (i32.const 52))" +
                                " (i32.const 1)) (then unreachable))\n" +
                                "    (local.set \$base (i32.load (i32.load (i32.const 48))))\n" +
                                "    (i32.store (i32.const 32) (local.get \$base))\n" +
                                "    (i32.store8 (i32.const 36) (i32.const 0))\n" +
                                "    (i32.store (i32.const 40) (i32.const 16))\n" +
                                "    (i32.store (i32.const 44) (i32.const 9))\n" +
                                "    (i32.store8 (i32.const 48) (i32.const 1))\n" +
                                "    (i32.store8 (i32.const 49) (i32.const 3))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$open_at\n" +
                                "        (i32.const 32)\n" +
                                "        (i32.const 64)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 2))" +
                                " (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (local.set \$file (i32.load (i32.const 68)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$stat (local.get \$file) (i32.const 96)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 2))" +
                                " (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$hash (local.get \$file) (i32.const 176)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 2))" +
                                " (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 176))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (call \$read_dir (local.get \$base) (i32.const 224))\n" +
                                "    (if (i32.eqz (i32.load (i32.const 224)))" +
                                " (then unreachable))\n" +
                                "    (if (i32.eqz (i32.load (i32.const 228)))" +
                                " (then unreachable))\n" +
                                "    (local.set \$dir_stream (i32.load (i32.const 224)))\n" +
                                "    (local.set \$dir_future (i32.load (i32.const 228)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$read_dir_future\n" +
                                "        (local.get \$dir_future)\n" +
                                "        (i32.const 384)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const -1))" +
                                " (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$read_dir_stream\n" +
                                "        (local.get \$dir_stream)\n" +
                                "        (i32.const 304)\n" +
                                "        (i32.const 1)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 17))" +
                                " (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$read_dir_future\n" +
                                "        (local.get \$dir_future)\n" +
                                "        (i32.const 384)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 0))" +
                                " (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 384))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (if (i32.ne (i32.load (i32.const 324))" +
                                " (i32.const 9)) (then unreachable))\n" +
                                "    (local.get \$file))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(wasi)
                    .build()

            assertTrue((plugin.call("api.run") as Long) > 0)
            assertTrue(Files.exists(probe))
        } finally {
            Files.deleteIfExists(probe)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun supportsFilesystemLinkAtAndSetTimesAtStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-link-times")
        val source = tempDir.resolve("source.txt")
        val linked = tempDir.resolve("linked.txt")
        val modifiedSeconds = 1_700_000_100L
        try {
            Files.writeString(source, "linked-content", StandardCharsets.UTF_8)
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem-link-times;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:clocks@$version {
                      interface system-clock {
                        record instant {
                          seconds: s64,
                          nanoseconds: u32,
                        }
                      }
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        use wasi:clocks/system-clock@$version.{instant};

                        flags path-flags {
                          symlink-follow,
                        }

                        variant descriptor-timestamp {
                          no-change,
                          now,
                          timestamp(instant),
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        resource descriptor {
                          set-times-at: func(
                            path-flags: path-flags,
                            path: string,
                            data-access-timestamp: descriptor-timestamp,
                            data-modification-timestamp: descriptor-timestamp,
                          ) -> result<_, error-code>;
                          link-at: func(
                            old-flags: path-flags,
                            old-path: string,
                            new-descriptor: borrow<descriptor>,
                            new-path: string,
                          ) -> result<_, error-code>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
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
                              (import "wasi:filesystem/preopens@$version"
                                "get-directories" (func ${'$'}get_directories (param i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.set-times-at"
                                (func ${'$'}set_times_at
                                  (param i32 i32 i32 i32 i32 i64 i32 i32 i64 i32 i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.link-at"
                                (func ${'$'}link_at
                                  (param i32 i32 i32 i32 i32 i32 i32 i32)))
                              (memory (export "memory") 1)
                              (global ${'$'}heap (mut i32) (i32.const 256))
                              (data (i32.const 16) "source.txt")
                              (data (i32.const 32) "linked.txt")
                              (func (export "canonical_abi_realloc")
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
                              (func ${'$'}run (result i32)
                                (local ${'$'}base i32)
                                (call ${'$'}get_directories (i32.const 48))
                                (if (i32.ne (i32.load (i32.const 52)) (i32.const 1))
                                  (then unreachable))
                                (local.set ${'$'}base (i32.load (i32.load (i32.const 48))))
                                (call ${'$'}set_times_at
                                  (local.get ${'$'}base)
                                  (i32.const 0)
                                  (i32.const 16)
                                  (i32.const 10)
                                  (i32.const 0)
                                  (i64.const 0)
                                  (i32.const 0)
                                  (i32.const 2)
                                  (i64.const $modifiedSeconds)
                                  (i32.const 123000000)
                                  (i32.const 96))
                                (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                                  (then unreachable))
                                (call ${'$'}link_at
                                  (local.get ${'$'}base)
                                  (i32.const 0)
                                  (i32.const 16)
                                  (i32.const 10)
                                  (local.get ${'$'}base)
                                  (i32.const 32)
                                  (i32.const 10)
                                  (i32.const 112))
                                (if (i32.ne (i32.load8_u (i32.const 112)) (i32.const 0))
                                  (then unreachable))
                                (i32.const 42))
                              (export "api.run" (func ${'$'}run))
                            )
                            """
                                .trimIndent()
                        )
                    )
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", tempDir.toString())
                            .build()
                    )
                    .build()

            assertEquals(42L, plugin.call("api.run"))
            assertEquals("linked-content", Files.readString(linked, StandardCharsets.UTF_8))
            assertEquals(modifiedSeconds, Files.getLastModifiedTime(source).toMillis() / 1000L)
        } finally {
            Files.deleteIfExists(linked)
            Files.deleteIfExists(source)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun unlinksFileWhileDescriptorResourceIsStillOpenStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-unlink-open")
        val target = tempDir.resolve("open.txt")
        try {
            Files.writeString(target, "temporary-content", StandardCharsets.UTF_8)
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem-unlink-open;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        type filesize = u64;

                        flags descriptor-flags {
                          read,
                          write,
                          file-integrity-sync,
                          data-integrity-sync,
                          requested-write-sync,
                          mutate-directory,
                        }

                        flags path-flags {
                          symlink-follow,
                        }

                        flags open-flags {
                          create,
                          directory,
                          exclusive,
                          truncate,
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        resource descriptor {
                          open-at: async func(
                            path-flags: path-flags,
                            path: string,
                            open-flags: open-flags,
                            %flags: descriptor-flags,
                          ) -> result<descriptor, error-code>;
                          unlink-file-at: func(path: string) -> result<_, error-code>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
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
                              (import "wasi:filesystem/preopens@$version"
                                "get-directories" (func ${'$'}get_directories (param i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.open-at"
                                (func ${'$'}open_at
                                  (param i32 i32 i32 i32 i32 i32)
                                  (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[async-lower][future-read-0][method]descriptor.open-at"
                                (func ${'$'}open_at_future_read (param i32 i32) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.unlink-file-at"
                                (func ${'$'}unlink_file_at (param i32 i32 i32 i32)))
                              (import "wasi:filesystem/types@$version"
                                "[resource-drop]descriptor"
                                (func ${'$'}drop_descriptor (param i32)))
                              (memory (export "memory") 1)
                              (global ${'$'}heap (mut i32) (i32.const 256))
                              (data (i32.const 16) "open.txt")
                              (func (export "canonical_abi_realloc")
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
                              (func ${'$'}run (result i32)
                                (local ${'$'}root i32)
                                (local ${'$'}future i32)
                                (local ${'$'}file i32)
                                (call ${'$'}get_directories (i32.const 48))
                                (if (i32.ne (i32.load (i32.const 52)) (i32.const 1))
                                  (then unreachable))
                                (local.set ${'$'}root (i32.load (i32.load (i32.const 48))))
                                (local.set ${'$'}future
                                  (call ${'$'}open_at
                                    (local.get ${'$'}root)
                                    (i32.const 0)
                                    (i32.const 16)
                                    (i32.const 8)
                                    (i32.const 0)
                                    (i32.const 1)))
                                (if (i32.ne
                                    (call ${'$'}open_at_future_read
                                      (local.get ${'$'}future)
                                      (i32.const 80))
                                    (i32.const 0))
                                  (then unreachable))
                                (if (i32.ne (i32.load8_u (i32.const 80)) (i32.const 0))
                                  (then unreachable))
                                (local.set ${'$'}file (i32.load (i32.const 84)))
                                (call ${'$'}unlink_file_at
                                  (local.get ${'$'}root)
                                  (i32.const 16)
                                  (i32.const 8)
                                  (i32.const 96))
                                (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                                  (then unreachable))
                                (call ${'$'}drop_descriptor (local.get ${'$'}file))
                                (call ${'$'}drop_descriptor (local.get ${'$'}root))
                                (i32.const 42))
                              (export "api.run" (func ${'$'}run))
                            )
                            """
                                .trimIndent()
                        )
                    )
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", tempDir.toString())
                            .build()
                    )
                    .build()

            assertEquals(42L, plugin.call("api.run"))
            assertFalse(Files.exists(target))
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun keepsOpenFileDescriptorUsableAfterUnlink() {
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-unlink-descriptor")
        val target = tempDir.resolve("open.txt")
        val initial = "abcdef".toByteArray(StandardCharsets.UTF_8)
        try {
            Files.write(target, initial)
            val wasi =
                WasiPreview3.builder().withPreopenedDirectory("/", tempDir.toString()).build()
            val imports = CapturingHostImports()
            wasi.install(imports)
            try {
                @Suppress("UNCHECKED_CAST")
                val directories =
                    imports.call("preopens", "get-directories") as List<List<Any?>>
                val base = handle(directories.single()[0])
                val file =
                    handle(
                        expectOk<Any?>(
                            imports.call(
                                "types",
                                "[method]descriptor.open-at",
                                base,
                                emptyList<String>(),
                                "open.txt",
                                emptyList<String>(),
                                listOf("read", "write"),
                            ),
                            "descriptor.open-at",
                        )
                    )
                val sameFile =
                    handle(
                        expectOk<Any?>(
                            imports.call(
                                "types",
                                "[method]descriptor.open-at",
                                base,
                                emptyList<String>(),
                                "open.txt",
                                emptyList<String>(),
                                listOf("read", "write"),
                            ),
                            "descriptor.open-at second descriptor",
                        )
                    )

                expectOk<Any?>(
                    imports.call("types", "[method]descriptor.unlink-file-at", base, "open.txt"),
                    "descriptor.unlink-file-at",
                )
                assertFalse(Files.exists(target))
                assertEquals(
                    "no-entry",
                    expectErr(
                        imports.call(
                            "types",
                            "[method]descriptor.stat-at",
                            base,
                            emptyList<String>(),
                            "open.txt",
                        ),
                        "descriptor.stat-at",
                    ),
                )

                assertEquals(
                    "regular-file",
                    expectOk<Any?>(
                        imports.call("types", "[method]descriptor.get-type", file),
                        "descriptor.get-type",
                    ),
                )
                assertEquals(
                    listOf("read", "write"),
                    expectOk<List<String>>(
                        imports.call("types", "[method]descriptor.get-flags", file),
                        "descriptor.get-flags",
                    ),
                )
                expectOk<Any?>(
                    imports.call(
                        "types",
                        "[method]descriptor.advise",
                        file,
                        0L,
                        initial.size.toLong(),
                        "normal",
                    ),
                    "descriptor.advise",
                )
                assertEquals(
                    initial.size.toLong(),
                    descriptorSize(
                        expectOk(
                            imports.call("types", "[method]descriptor.stat", file),
                            "descriptor.stat",
                        )
                    ),
                )

                val readBeforeWrite =
                    expectOk<List<Any?>>(
                        imports.call("types", "[method]descriptor.read", file, 64L, 0L),
                        "descriptor.read",
                    )
                assertArrayEquals(initial, readBeforeWrite[0] as ByteArray)
                assertEquals(true, readBeforeWrite[1])

                assertEquals(
                    2L,
                    (expectOk<Any?>(
                            imports.call(
                                "types",
                                "[method]descriptor.write",
                                file,
                                "XY".toByteArray(StandardCharsets.UTF_8),
                                2L,
                            ),
                            "descriptor.write",
                        )
                        as Number)
                        .toLong(),
                )
                expectOk<Any?>(
                    imports.call("types", "[method]descriptor.set-size", file, 5L),
                    "descriptor.set-size",
                )

                val expected = "abXYe".toByteArray(StandardCharsets.UTF_8)
                val readAfterWrite =
                    expectOk<List<Any?>>(
                        imports.call("types", "[method]descriptor.read", file, 64L, 0L),
                        "descriptor.read after write",
                    )
                assertArrayEquals(expected, readAfterWrite[0] as ByteArray)
                assertEquals(true, readAfterWrite[1])
                assertEquals(
                    expected.size.toLong(),
                    descriptorSize(
                        expectOk(
                            imports.call("types", "[method]descriptor.stat", file),
                            "descriptor.stat after write",
                        )
                    ),
                )

                val streamRead =
                    imports.call("types", "[method]descriptor.read-via-stream", file, 0L)
                        as List<*>
                assertArrayEquals(
                    expected,
                    runBlocking { readAllStreamBytes(wasi, handle(streamRead[0])) },
                )
                expectOk<Any?>(
                    runBlocking { wasi.awaitFutureValue(handle(streamRead[1])) },
                    "descriptor.read-via-stream future",
                )

                val writeGate = CompletableDeferred<Unit>()
                val writeFuture =
                    handle(
                        imports.call(
                            "types",
                            "[method]descriptor.write-via-stream",
                            file,
                            wasi.byteStream(
                                    flow {
                                        writeGate.await()
                                        emit("ZZ".toByteArray(StandardCharsets.UTF_8))
                                    }
                                )
                                .handle(),
                            0L,
                        )
                    )
                writeGate.complete(Unit)
                expectOk<Any?>(
                    runBlocking { wasi.awaitFutureValue(writeFuture) },
                    "descriptor.write-via-stream",
                )
                val appendGate = CompletableDeferred<Unit>()
                val appendFuture =
                    handle(
                        imports.call(
                            "types",
                            "[method]descriptor.append-via-stream",
                            sameFile,
                            wasi.byteStream(
                                    flow {
                                        appendGate.await()
                                        emit("!".toByteArray(StandardCharsets.UTF_8))
                                    }
                                )
                                .handle(),
                        )
                    )
                appendGate.complete(Unit)
                expectOk<Any?>(
                    runBlocking { wasi.awaitFutureValue(appendFuture) },
                    "descriptor.append-via-stream",
                )
                val expectedAfterStreams = "ZZXYe!".toByteArray(StandardCharsets.UTF_8)
                val readAfterStreamWrite =
                    expectOk<List<Any?>>(
                        imports.call("types", "[method]descriptor.read", sameFile, 64L, 0L),
                        "descriptor.read after stream write",
                    )
                assertArrayEquals(expectedAfterStreams, readAfterStreamWrite[0] as ByteArray)
                assertEquals(true, readAfterStreamWrite[1])
                expectOk<Any?>(
                    imports.call("types", "[method]descriptor.sync-data", file),
                    "descriptor.sync-data",
                )
                expectOk<Any?>(
                    imports.call("types", "[method]descriptor.sync", file),
                    "descriptor.sync",
                )

                expectOk<Map<String, Any?>>(
                    imports.call("types", "[method]descriptor.metadata-hash", file),
                    "descriptor.metadata-hash",
                )
                assertEquals(
                    true,
                    imports.call("types", "[method]descriptor.is-same-object", file, file),
                )
                assertEquals(
                    true,
                    imports.call("types", "[method]descriptor.is-same-object", file, sameFile),
                )
                expectOk<Any?>(
                    imports.call(
                        "types",
                        "[method]descriptor.set-times",
                        file,
                        WitValue.variant("no-change"),
                        WitValue.variant("no-change"),
                    ),
                    "descriptor.set-times no-change",
                )
                assertEquals(
                    "unsupported",
                    expectErr(
                        imports.call(
                            "types",
                            "[method]descriptor.set-times",
                            file,
                            WitValue.variant("now"),
                            WitValue.variant("no-change"),
                        ),
                        "descriptor.set-times after unlink",
                    ),
                )
                assertFalse(Files.exists(target))
            } finally {
                wasi.close()
            }
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun filesystemPreopenRejectsPathAndSymlinkEscapes() {
        val sandboxRoot = Files.createTempDirectory("krwa-wasi3-filesystem-sandbox")
        val outsideRoot = Files.createTempDirectory("krwa-wasi3-filesystem-outside")
        val outsideSecret = outsideRoot.resolve("secret.txt")
        try {
            Files.writeString(outsideSecret, "outside-secret", StandardCharsets.UTF_8)
            Files.createSymbolicLink(sandboxRoot.resolve("secret-link"), outsideSecret)

            val wasi =
                WasiPreview3.builder().withPreopenedDirectory("/", sandboxRoot.toString()).build()
            val imports = CapturingHostImports()
            wasi.install(imports)

            @Suppress("UNCHECKED_CAST")
            val directories =
                imports.call("preopens", "get-directories") as List<List<Any?>>
            val base = handle(directories.single()[0])

            fun openError(path: String, pathFlags: List<String> = emptyList()): String =
                expectErr(
                    imports.call(
                        "types",
                        "[method]descriptor.open-at",
                        base,
                        pathFlags,
                        path,
                        emptyList<String>(),
                        listOf("read"),
                    ),
                    "descriptor.open-at $path",
                ) as String

            fun linkError(path: String, pathFlags: List<String> = emptyList()): String =
                expectErr(
                    imports.call(
                        "types",
                        "[method]descriptor.link-at",
                        base,
                        pathFlags,
                        path,
                        base,
                        "linked-secret",
                    ),
                    "descriptor.link-at $path",
                ) as String

            assertEquals("not-permitted", openError("../${outsideRoot.fileName}/secret.txt"))
            assertEquals("not-permitted", openError(outsideSecret.toAbsolutePath().toString()))
            assertEquals("not-permitted", openError("secret-link", listOf("symlink-follow")))
            assertEquals("not-permitted", openError("secret-link"))
            assertEquals("not-permitted", linkError("secret-link", listOf("symlink-follow")))
            assertEquals("not-permitted", linkError("secret-link"))
            assertEquals(
                "not-permitted",
                expectErr(
                    imports.call("types", "[method]descriptor.readlink-at", base, "secret-link"),
                    "descriptor.readlink-at secret-link",
                ),
            )
        } finally {
            sandboxRoot.toFile().deleteRecursively()
            outsideRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun filesystemPreopensEnforceReadOnlyRootAndWritableCache() {
        val root = Files.createTempDirectory("krwa-wasi3-filesystem-root")
        val cache = root.resolve("suvio/cache")
        val data = root.resolve("suvio/data")
        val publicFile = root.resolve("public.txt")
        try {
            Files.createDirectories(cache)
            Files.createDirectories(data)
            Files.writeString(publicFile, "public", StandardCharsets.UTF_8)

            val wasi =
                WasiPreview3.builder()
                    .withReadOnlyPreopenedDirectory("/", root.toString())
                    .withPreopenedDirectory("/suvio/cache", cache.toString())
                    .build()
            val imports = CapturingHostImports()
            wasi.install(imports)
            try {
                @Suppress("UNCHECKED_CAST")
                val directories =
                    imports.call("preopens", "get-directories") as List<List<Any?>>
                val preopens = directories.associate { it[1] as String to handle(it[0]) }
                val rootDescriptor = preopens.getValue("/")
                val cacheDescriptor = preopens.getValue("/suvio/cache")

                assertEquals(
                    "read-only",
                    expectErr(
                        imports.call(
                            "types",
                            "[method]descriptor.open-at",
                            rootDescriptor,
                            emptyList<String>(),
                            "blocked.txt",
                            listOf("create"),
                            listOf("write"),
                        ),
                        "descriptor.open-at read-only root create",
                    ),
                )
                assertFalse(Files.exists(root.resolve("blocked.txt")))

                expectOk<Any?>(
                    imports.call(
                        "types",
                        "[method]descriptor.open-at",
                        rootDescriptor,
                        emptyList<String>(),
                        "public.txt",
                        emptyList<String>(),
                        listOf("read"),
                    ),
                    "descriptor.open-at read-only root read",
                )

                expectOk<Any?>(
                    imports.call(
                        "types",
                        "[method]descriptor.open-at",
                        cacheDescriptor,
                        emptyList<String>(),
                        "cache.txt",
                        listOf("create"),
                        listOf("write"),
                    ),
                    "descriptor.open-at writable cache create",
                )
                assertTrue(Files.exists(cache.resolve("cache.txt")))
                assertFalse(Files.exists(root.resolve("cache.txt")))

                assertEquals(
                    "not-permitted",
                    expectErr(
                        imports.call(
                            "types",
                            "[method]descriptor.open-at",
                            cacheDescriptor,
                            emptyList<String>(),
                            "../data/leak.txt",
                            listOf("create"),
                            listOf("write"),
                        ),
                        "descriptor.open-at writable cache escape",
                    ),
                )
                assertFalse(Files.exists(data.resolve("leak.txt")))
            } finally {
                wasi.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun filesystemWritablePreopenCannotEscapeThroughSymlink() {
        val root = Files.createTempDirectory("krwa-wasi3-filesystem-root")
        val cache = root.resolve("suvio/cache")
        val outside = Files.createTempDirectory("krwa-wasi3-filesystem-outside")
        try {
            Files.createDirectories(cache)
            Files.createSymbolicLink(cache.resolve("escape"), outside)

            val wasi =
                WasiPreview3.builder()
                    .withReadOnlyPreopenedDirectory("/", root.toString())
                    .withPreopenedDirectory("/suvio/cache", cache.toString())
                    .build()
            val imports = CapturingHostImports()
            wasi.install(imports)
            try {
                @Suppress("UNCHECKED_CAST")
                val directories =
                    imports.call("preopens", "get-directories") as List<List<Any?>>
                val preopens = directories.associate { it[1] as String to handle(it[0]) }
                val cacheDescriptor = preopens.getValue("/suvio/cache")

                assertEquals(
                    "not-permitted",
                    expectErr(
                        imports.call(
                            "types",
                            "[method]descriptor.open-at",
                            cacheDescriptor,
                            emptyList<String>(),
                            "escape/outside.txt",
                            listOf("create"),
                            listOf("write"),
                        ),
                        "descriptor.open-at writable cache symlink escape",
                    ),
                )
                assertFalse(Files.exists(outside.resolve("outside.txt")))
            } finally {
                wasi.close()
            }
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun exposesFilesystemReadViaStreamBytesToHost() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-stream")
        val source = tempDir.resolve("hello.txt")
        try {
            Files.writeString(source, "hello", StandardCharsets.UTF_8)
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem-stream;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      read: func() -> stream<u8>;
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        type filesize = u64;

                        flags descriptor-flags {
                          read,
                          write,
                          file-integrity-sync,
                          data-integrity-sync,
                          requested-write-sync,
                          mutate-directory,
                        }

                        flags path-flags {
                          symlink-follow,
                        }

                        flags open-flags {
                          create,
                          directory,
                          exclusive,
                          truncate,
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        resource descriptor {
                          open-at: async func(
                            path-flags: path-flags,
                            path: string,
                            open-flags: open-flags,
                            %flags: descriptor-flags,
                          ) -> result<descriptor, error-code>;
                          read-via-stream: func(offset: filesize) -> tuple<stream<u8>, future<result<_, error-code>>>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
                    }
                    """
                        .trimIndent()
                )
            val wasi =
                WasiPreview3.builder().withPreopenedDirectory("/", tempDir.toString()).build()
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:filesystem/preopens@$version\"" +
                                " \"get-directories\" (func \$get_directories (param" +
                                " i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[method]descriptor.open-at\" (func \$open_at" +
                                " (param i32) (param i32) (param i32) (param i32)" +
                                " (param i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][future-read-0][method]descriptor.open-at\"" +
                                " (func \$open_at_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[method]descriptor.read-via-stream\" (func" +
                                " \$read_stream (param i32) (param i64) (param i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (global \$heap (mut i32) (i32.const 256))\n" +
                                "  (data (i32.const 16) \"hello.txt\")\n" +
                                "  (func (export \"canonical_abi_realloc\")\n" +
                                "    (param \$old i32) (param \$old_size i32)\n" +
                                "    (param \$align i32) (param \$new_size i32)\n" +
                                "    (result i32)\n" +
                                "    (local \$ptr i32)\n" +
                                "    (local.set \$ptr\n" +
                                "      (i32.and\n" +
                                "        (i32.add (global.get \$heap)" +
                                " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                                "        (i32.xor\n" +
                                "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                                "          (i32.const -1))))\n" +
                                "    (global.set \$heap\n" +
                                "      (i32.add (local.get \$ptr) (local.get" +
                                " \$new_size)))\n" +
                                "    (local.get \$ptr))\n" +
                                "  (func \$read (result i32)\n" +
                                "    (local \$base i32)\n" +
                                "    (local \$file i32)\n" +
                                "    (local \$stream i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (call \$get_directories (i32.const 48))\n" +
                                "    (local.set \$base (i32.load (i32.load (i32.const 48))))\n" +
                                "    (local.set \$future\n" +
                                "      (call \$open_at\n" +
                                "      (local.get \$base)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 16)\n" +
                                "      (i32.const 9)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 1)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$open_at_future_read (local.get \$future)" +
                                " (i32.const 64)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (local.set \$file (i32.load (i32.const 68)))\n" +
                                "    (call \$read_stream (local.get \$file) (i64.const 0)" +
                                " (i32.const 96))\n" +
                                "    (local.set \$stream (i32.load (i32.const 96)))\n" +
                                "    (local.set \$future (i32.load (i32.const 100)))\n" +
                                "    (local.get \$stream))\n" +
                                "  (export \"api.read\" (func \$read))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(wasi)
                    .build()

            val stream = plugin.call("api.read") as WitStream<*>

            assertArrayEquals("hello".toByteArray(StandardCharsets.UTF_8), wasi.streamBytes(stream))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun readsFilesystemByteStreamThroughCanonicalIntrinsicStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-canonical-stream")
        val source = tempDir.resolve("hello.txt")
        try {
            Files.writeString(source, "hello", StandardCharsets.UTF_8)
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem-canonical-stream;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        type filesize = u64;

                        flags descriptor-flags {
                          read,
                          write,
                          file-integrity-sync,
                          data-integrity-sync,
                          requested-write-sync,
                          mutate-directory,
                        }

                        flags path-flags {
                          symlink-follow,
                        }

                        flags open-flags {
                          create,
                          directory,
                          exclusive,
                          truncate,
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        resource descriptor {
                          open-at: async func(
                            path-flags: path-flags,
                            path: string,
                            open-flags: open-flags,
                            %flags: descriptor-flags,
                          ) -> result<descriptor, error-code>;
                          read-via-stream: func(offset: filesize) -> tuple<stream<u8>, future<result<_, error-code>>>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
                    }
                    """
                        .trimIndent()
                )
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:filesystem/preopens@$version\"" +
                                " \"get-directories\" (func \$get_directories (param" +
                                " i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[method]descriptor.open-at\" (func \$open_at" +
                                " (param i32) (param i32) (param i32) (param i32)" +
                                " (param i32) (param i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][future-read-0][method]descriptor.open-at\"" +
                                " (func \$open_at_future_read (param i32 i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[method]descriptor.read-via-stream\" (func" +
                                " \$read_stream (param i32) (param i64) (param i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][stream-read-0][method]descriptor.read-via-stream\"" +
                                " (func \$stream_read (param i32 i32 i32) (result i32)))\n" +
                                "  (import \"wasi:filesystem/types@$version\"" +
                                " \"[async-lower][future-read-1][method]descriptor.read-via-stream\"" +
                                " (func \$future_read (param i32 i32) (result i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (global \$heap (mut i32) (i32.const 256))\n" +
                                "  (data (i32.const 16) \"hello.txt\")\n" +
                                "  (func (export \"canonical_abi_realloc\")\n" +
                                "    (param \$old i32) (param \$old_size i32)\n" +
                                "    (param \$align i32) (param \$new_size i32)\n" +
                                "    (result i32)\n" +
                                "    (local \$ptr i32)\n" +
                                "    (local.set \$ptr\n" +
                                "      (i32.and\n" +
                                "        (i32.add (global.get \$heap)" +
                                " (i32.sub (local.get \$align) (i32.const 1)))\n" +
                                "        (i32.xor\n" +
                                "          (i32.sub (local.get \$align) (i32.const 1))\n" +
                                "          (i32.const -1))))\n" +
                                "    (global.set \$heap\n" +
                                "      (i32.add (local.get \$ptr) (local.get" +
                                " \$new_size)))\n" +
                                "    (local.get \$ptr))\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$base i32)\n" +
                                "    (local \$file i32)\n" +
                                "    (local \$stream i32)\n" +
                                "    (local \$future i32)\n" +
                                "    (local \$status i32)\n" +
                                "    (call \$get_directories (i32.const 48))\n" +
                                "    (local.set \$base (i32.load (i32.load (i32.const 48))))\n" +
                                "    (local.set \$future\n" +
                                "      (call \$open_at\n" +
                                "      (local.get \$base)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 16)\n" +
                                "      (i32.const 9)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 1)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$open_at_future_read (local.get \$future)" +
                                " (i32.const 64)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (local.set \$file (i32.load (i32.const 68)))\n" +
                                "    (call \$read_stream (local.get \$file) (i64.const 0)" +
                                " (i32.const 96))\n" +
                                "    (local.set \$stream (i32.load (i32.const 96)))\n" +
                                "    (local.set \$future (i32.load (i32.const 100)))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$future_read\n" +
                                "        (local.get \$future)\n" +
                                "        (i32.const 160)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const -1))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$stream_read\n" +
                                "        (local.get \$stream)\n" +
                                "        (i32.const 128)\n" +
                                "        (i32.const 16)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 81))\n" +
                                "      (then unreachable))\n" +
                                "    (local.set \$status\n" +
                                "      (call \$future_read\n" +
                                "        (local.get \$future)\n" +
                                "        (i32.const 160)))\n" +
                                "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                                "      (then unreachable))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const 160))" +
                                " (i32.const 0)) (then unreachable))\n" +
                                "    (i32.add\n" +
                                "      (i32.add\n" +
                                "        (i32.add\n" +
                                "          (i32.add\n" +
                                "            (i32.load8_u (i32.const 128))\n" +
                                "            (i32.load8_u (i32.const 129)))\n" +
                                "          (i32.load8_u (i32.const 130)))\n" +
                                "        (i32.load8_u (i32.const 131)))\n" +
                                "      (i32.load8_u (i32.const 132))))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", tempDir.toString())
                            .build()
                    )
                    .build()

            assertEquals(532L, plugin.call("api.run"))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun writesFilesystemByteStreamAfterHostImportStartsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val tempDir = Files.createTempDirectory("krwa-wasi3-filesystem-write-stream")
        val target = tempDir.resolve("target.txt")
        try {
            val witPackage =
                WitPackage.parse(
                    """
                    package example:wasi3-filesystem-write-stream;

                    world plugin {
                      import wasi:filesystem/types@$version;
                      import wasi:filesystem/preopens@$version;
                      export api;
                    }

                    interface api {
                      run: func() -> u32;
                    }

                    package wasi:filesystem@$version {
                      interface types {
                        type filesize = u64;

                        flags descriptor-flags {
                          read,
                          write,
                          file-integrity-sync,
                          data-integrity-sync,
                          requested-write-sync,
                          mutate-directory,
                        }

                        flags path-flags {
                          symlink-follow,
                        }

                        flags open-flags {
                          create,
                          directory,
                          exclusive,
                          truncate,
                        }

                        variant error-code {
                          access,
                          bad-descriptor,
                          exist,
                          io,
                          is-directory,
                          loop,
                          no-entry,
                          not-directory,
                          not-empty,
                          unsupported,
                          not-permitted,
                          read-only,
                          other(option<string>),
                        }

                        resource descriptor {
                          open-at: async func(
                            path-flags: path-flags,
                            path: string,
                            open-flags: open-flags,
                            %flags: descriptor-flags,
                          ) -> result<descriptor, error-code>;
                          write-via-stream: func(
                            data: stream<u8>,
                            offset: filesize,
                          ) -> future<result<_, error-code>>;
                        }
                      }

                      interface preopens {
                        use types.{descriptor};
                        get-directories: func() -> list<tuple<descriptor, string>>;
                      }
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
                              (import "wasi:filesystem/preopens@$version"
                                "get-directories" (func ${'$'}get_directories (param i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.open-at"
                                (func ${'$'}open_at
                                  (param i32 i32 i32 i32 i32 i32)
                                  (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[async-lower][future-read-0][method]descriptor.open-at"
                                (func ${'$'}open_at_future_read (param i32 i32) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[stream-new-0][method]descriptor.write-via-stream"
                                (func ${'$'}write_stream_new (result i64)))
                              (import "wasi:filesystem/types@$version"
                                "[async-lower][stream-write-0][method]descriptor.write-via-stream"
                                (func ${'$'}stream_write (param i32 i32 i32) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[stream-drop-writable-0][method]descriptor.write-via-stream"
                                (func ${'$'}stream_drop_writable (param i32)))
                              (import "wasi:filesystem/types@$version"
                                "[method]descriptor.write-via-stream"
                                (func ${'$'}write_stream (param i32 i32 i64) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "[async-lower][future-read-1][method]descriptor.write-via-stream"
                                (func ${'$'}write_future_read (param i32 i32) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "waitable-set.new" (func ${'$'}waitable_set_new (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "waitable.join" (func ${'$'}waitable_join (param i32 i32)))
                              (import "wasi:filesystem/types@$version"
                                "waitable-set.wait" (func ${'$'}waitable_set_wait (param i32 i32) (result i32)))
                              (import "wasi:filesystem/types@$version"
                                "waitable-set.drop" (func ${'$'}waitable_set_drop (param i32)))
                              (memory (export "memory") 1)
                              (global ${'$'}heap (mut i32) (i32.const 256))
                              (data (i32.const 16) "target.txt")
                              (data (i32.const 32) "abc")
                              (data (i32.const 40) "de")
                              (func (export "canonical_abi_realloc")
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
                              (func ${'$'}run (result i32)
                                (local ${'$'}base i32)
                                (local ${'$'}file i32)
                                (local ${'$'}pair i64)
                                (local ${'$'}reader i32)
                                (local ${'$'}writer i32)
                                (local ${'$'}future i32)
                                (local ${'$'}status i32)
                                (local ${'$'}waitable_set i32)
                                (call ${'$'}get_directories (i32.const 64))
                                (local.set ${'$'}base (i32.load (i32.load (i32.const 64))))
                                (local.set ${'$'}future
                                  (call ${'$'}open_at
                                    (local.get ${'$'}base)
                                    (i32.const 0)
                                    (i32.const 16)
                                    (i32.const 10)
                                    (i32.const 9)
                                    (i32.const 2)))
                                (local.set ${'$'}status
                                  (call ${'$'}open_at_future_read
                                    (local.get ${'$'}future)
                                    (i32.const 96)))
                                (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                                  (then unreachable))
                                (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                                  (then unreachable))
                                (local.set ${'$'}file (i32.load (i32.const 100)))
                                (local.set ${'$'}pair (call ${'$'}write_stream_new))
                                (local.set ${'$'}reader (i32.wrap_i64 (local.get ${'$'}pair)))
                                (local.set ${'$'}writer
                                  (i32.wrap_i64
                                    (i64.shr_u (local.get ${'$'}pair) (i64.const 32))))
                                (local.set ${'$'}future
                                  (call ${'$'}write_stream
                                    (local.get ${'$'}file)
                                    (local.get ${'$'}reader)
                                    (i64.const 0)))
                                (if (i32.ne
                                  (call ${'$'}write_future_read
                                    (local.get ${'$'}future)
                                    (i32.const 128))
                                  (i32.const -1))
                                  (then unreachable))
                                (if (i32.ne
                                  (call ${'$'}stream_write
                                    (local.get ${'$'}writer)
                                    (i32.const 32)
                                    (i32.const 3))
                                  (i32.const 48))
                                  (then unreachable))
                                (if (i32.ne
                                  (call ${'$'}stream_write
                                    (local.get ${'$'}writer)
                                    (i32.const 40)
                                    (i32.const 2))
                                  (i32.const 32))
                                  (then unreachable))
                                (if (i32.ne
                                  (call ${'$'}write_future_read
                                    (local.get ${'$'}future)
                                    (i32.const 128))
                                  (i32.const -1))
                                  (then unreachable))
                                (call ${'$'}stream_drop_writable (local.get ${'$'}writer))
                                (local.set ${'$'}status
                                  (call ${'$'}write_future_read
                                    (local.get ${'$'}future)
                                    (i32.const 128)))
                                (if (i32.eq (local.get ${'$'}status) (i32.const -1))
                                  (then
                                    (local.set ${'$'}waitable_set (call ${'$'}waitable_set_new))
                                    (call ${'$'}waitable_join
                                      (local.get ${'$'}future)
                                      (local.get ${'$'}waitable_set))
                                    (if (i32.ne
                                      (call ${'$'}waitable_set_wait
                                        (local.get ${'$'}waitable_set)
                                        (i32.const 192))
                                      (i32.const 4))
                                      (then unreachable))
                                    (call ${'$'}waitable_set_drop (local.get ${'$'}waitable_set))
                                    (local.set ${'$'}status
                                      (call ${'$'}write_future_read
                                        (local.get ${'$'}future)
                                        (i32.const 128)))))
                                (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                                  (then unreachable))
                                (if (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                                  (then unreachable))
                                (i32.const 42))
                              (export "api.run" (func ${'$'}run))
                            )
                            """
                                .trimIndent()
                        )
                    )
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", tempDir.toString())
                            .build()
                    )
                    .build()

            assertEquals(42L, plugin.call("api.run"))
            assertEquals("abcde", Files.readString(target, StandardCharsets.UTF_8))
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun supportsCanonicalByteStreamIntrinsicsStableWithoutJson() {
        val version = WasiPreview3.DEFAULT_VERSION
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-stream-intrinsics;

                world plugin {
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }

                package wasi:cli@$version {
                  interface stdin {
                    read-via-stream: func() -> tuple<stream<u8>, future<result>>;
                  }
                }
                """
                    .trimIndent()
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdin@$version\"" +
                            " \"[stream-new-0]read-via-stream\" (func" +
                            " \$stream_new (result i64)))\n" +
                            "  (import \"wasi:cli/stdin@$version\"" +
                            " \"[async-lower][stream-write-0]read-via-stream\" (func" +
                            " \$stream_write (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:cli/stdin@$version\"" +
                            " \"[stream-drop-writable-0]read-via-stream\" (func" +
                            " \$drop_writable (param i32)))\n" +
                            "  (import \"wasi:cli/stdin@$version\"" +
                            " \"[async-lower][stream-read-0]read-via-stream\" (func" +
                            " \$stream_read (param i32 i32 i32) (result i32)))\n" +
                            "  (import \"wasi:cli/stdin@$version\"" +
                            " \"[stream-drop-readable-0]read-via-stream\" (func" +
                            " \$drop_readable (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (data (i32.const 32) \"abc\")\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$pair i64)\n" +
                            "    (local \$reader i32)\n" +
                            "    (local \$writer i32)\n" +
                            "    (local \$write_status i32)\n" +
                            "    (local \$read_status i32)\n" +
                            "    (local.set \$pair (call \$stream_new))\n" +
                            "    (local.set \$reader (i32.wrap_i64 (local.get \$pair)))\n" +
                            "    (local.set \$writer\n" +
                            "      (i32.wrap_i64\n" +
                            "        (i64.shr_u (local.get \$pair) (i64.const 32))))\n" +
                            "    (local.set \$write_status\n" +
                            "      (call \$stream_write\n" +
                            "        (local.get \$writer)\n" +
                            "        (i32.const 32)\n" +
                            "        (i32.const 3)))\n" +
                            "    (if (i32.ne (local.get \$write_status) (i32.const 48))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_writable (local.get \$writer))\n" +
                            "    (local.set \$read_status\n" +
                            "      (call \$stream_read\n" +
                            "        (local.get \$reader)\n" +
                            "        (i32.const 64)\n" +
                            "        (i32.const 8)))\n" +
                            "    (if (i32.ne (local.get \$read_status) (i32.const 49))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_readable (local.get \$reader))\n" +
                            "    (i32.add\n" +
                            "      (i32.add\n" +
                            "        (i32.load8_u (i32.const 64))\n" +
                            "        (i32.load8_u (i32.const 65)))\n" +
                            "      (i32.load8_u (i32.const 66))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview3(WasiPreview3.builder().build())
                .build()

        assertEquals(294L, plugin.call("api.run"))
    }

    @Test
    fun preview3StdioStreamsUseConfiguredRawSourcesAndSinks() {
        val version = WasiPreview3.DEFAULT_VERSION
        val stdin = Buffer().also { it.write("in!".toByteArray(StandardCharsets.UTF_8)) }
        val stdout = Buffer()
        val stderr = Buffer()
        val wasi =
            WasiPreview3.builder()
                .withStdin(stdin)
                .withStdout(stdout)
                .withStderr(stderr)
                .build()
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-stdio-streams;

                world plugin {
                  import wasi:cli/stdin@$version;
                  import wasi:cli/stdout@$version;
                  import wasi:cli/stderr@$version;
                  export api;
                }

                interface api {
                  read-stdin: func() -> u32;
                  write-stdout: func() -> u32;
                  write-stderr: func() -> u32;
                }

                package wasi:cli@$version {
                  interface types {
                    enum error-code {
                      io,
                      illegal-byte-sequence,
                      pipe,
                    }
                  }

                  interface stdin {
                    use types.{error-code};
                    read-via-stream: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
                  }

                  interface stdout {
                    use types.{error-code};
                    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
                  }

                  interface stderr {
                    use types.{error-code};
                    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
                  }
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
                          (import "wasi:cli/stdin@$version" "read-via-stream"
                            (func ${'$'}stdin_read (param i32)))
                          (import "wasi:cli/stdin@$version" "[async-lower][stream-read-0]read-via-stream"
                            (func ${'$'}stdin_stream_read (param i32 i32 i32) (result i32)))
                          (import "wasi:cli/stdin@$version" "[async-lower][future-read-1]read-via-stream"
                            (func ${'$'}stdin_future_read (param i32 i32) (result i32)))
                          (import "wasi:cli/stdout@$version" "[stream-new-0]write-via-stream"
                            (func ${'$'}stdout_stream_new (result i64)))
                          (import "wasi:cli/stdout@$version" "[async-lower][stream-write-0]write-via-stream"
                            (func ${'$'}stdout_stream_write (param i32 i32 i32) (result i32)))
                          (import "wasi:cli/stdout@$version" "[stream-drop-writable-0]write-via-stream"
                            (func ${'$'}stdout_drop_writable (param i32)))
                          (import "wasi:cli/stdout@$version" "write-via-stream"
                            (func ${'$'}stdout_write (param i32) (result i32)))
                          (import "wasi:cli/stderr@$version" "[stream-new-0]write-via-stream"
                            (func ${'$'}stderr_stream_new (result i64)))
                          (import "wasi:cli/stderr@$version" "[async-lower][stream-write-0]write-via-stream"
                            (func ${'$'}stderr_stream_write (param i32 i32 i32) (result i32)))
                          (import "wasi:cli/stderr@$version" "[stream-drop-writable-0]write-via-stream"
                            (func ${'$'}stderr_drop_writable (param i32)))
                          (import "wasi:cli/stderr@$version" "write-via-stream"
                            (func ${'$'}stderr_write (param i32) (result i32)))
                          (memory (export "memory") 1)
                          (data (i32.const 16) "out")
                          (data (i32.const 24) "err")
                          (func ${'$'}read_stdin (result i32)
                            (local ${'$'}stream i32)
                            (local ${'$'}future i32)
                            (local ${'$'}status i32)
                            (call ${'$'}stdin_read (i32.const 32))
                            (local.set ${'$'}stream (i32.load (i32.const 32)))
                            (local.set ${'$'}future (i32.load (i32.const 36)))
                            (local.set ${'$'}status
                              (call ${'$'}stdin_future_read
                                (local.get ${'$'}future)
                                (i32.const 96)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const -1))
                              (then unreachable))
                            (local.set ${'$'}status
                              (call ${'$'}stdin_stream_read
                                (local.get ${'$'}stream)
                                (i32.const 64)
                                (i32.const 8)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 48))
                              (then unreachable))
                            (local.set ${'$'}status
                              (call ${'$'}stdin_stream_read
                                (local.get ${'$'}stream)
                                (i32.const 80)
                                (i32.const 8)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 1))
                              (then unreachable))
                            (local.set ${'$'}status
                              (call ${'$'}stdin_future_read
                                (local.get ${'$'}future)
                                (i32.const 96)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 0))
                              (then unreachable))
                            (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0))
                              (then unreachable))
                            (i32.add
                              (i32.add
                                (i32.load8_u (i32.const 64))
                                (i32.load8_u (i32.const 65)))
                              (i32.load8_u (i32.const 66))))
                          (func ${'$'}write_stdout (result i32)
                            (local ${'$'}pair i64)
                            (local ${'$'}reader i32)
                            (local ${'$'}writer i32)
                            (local ${'$'}status i32)
                            (local.set ${'$'}pair (call ${'$'}stdout_stream_new))
                            (local.set ${'$'}reader (i32.wrap_i64 (local.get ${'$'}pair)))
                            (local.set ${'$'}writer
                              (i32.wrap_i64
                                (i64.shr_u (local.get ${'$'}pair) (i64.const 32))))
                            (local.set ${'$'}status
                              (call ${'$'}stdout_stream_write
                                (local.get ${'$'}writer)
                                (i32.const 16)
                                (i32.const 3)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 48))
                              (then unreachable))
                            (call ${'$'}stdout_drop_writable (local.get ${'$'}writer))
                            (call ${'$'}stdout_write (local.get ${'$'}reader)))
                          (func ${'$'}write_stderr (result i32)
                            (local ${'$'}pair i64)
                            (local ${'$'}reader i32)
                            (local ${'$'}writer i32)
                            (local ${'$'}status i32)
                            (local.set ${'$'}pair (call ${'$'}stderr_stream_new))
                            (local.set ${'$'}reader (i32.wrap_i64 (local.get ${'$'}pair)))
                            (local.set ${'$'}writer
                              (i32.wrap_i64
                                (i64.shr_u (local.get ${'$'}pair) (i64.const 32))))
                            (local.set ${'$'}status
                              (call ${'$'}stderr_stream_write
                                (local.get ${'$'}writer)
                                (i32.const 24)
                                (i32.const 3)))
                            (if (i32.ne (local.get ${'$'}status) (i32.const 48))
                              (then unreachable))
                            (call ${'$'}stderr_drop_writable (local.get ${'$'}writer))
                            (call ${'$'}stderr_write (local.get ${'$'}reader)))
                          (export "api.read-stdin" (func ${'$'}read_stdin))
                          (export "api.write-stdout" (func ${'$'}write_stdout))
                          (export "api.write-stderr" (func ${'$'}write_stderr))
                        )
                        """
                            .trimIndent()
                    )
                )
                .withWasiPreview3(wasi)
                .build()

        assertEquals(248L, plugin.call("api.read-stdin"))
        val stdoutFuture = WitFuture.of<Any?>(plugin.call("api.write-stdout") as Long)
        val stderrFuture = WitFuture.of<Any?>(plugin.call("api.write-stderr") as Long)

        assertTrue(runBlocking { wasi.awaitFuture(stdoutFuture) } is WitResult.Ok<*, *>)
        assertTrue(runBlocking { wasi.awaitFuture(stderrFuture) } is WitResult.Ok<*, *>)
        assertEquals("out", stdout.readByteArray().decodeToString())
        assertEquals("err", stderr.readByteArray().decodeToString())
    }

    @Test
    fun supportsCanonicalFutureIntrinsicsStableWithoutJson() {
        val witPackage =
            WitPackage.parse(
                """
                package example:wasi3-future-intrinsics;

                world plugin {
                  import seed: func() -> future<u32>;
                  export api;
                }

                interface api {
                  run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val wasi = WasiPreview3.builder().build()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"plugin\" \"[future-new-0]seed\" (func" +
                            " \$future_new (result i64)))\n" +
                            "  (import \"plugin\" \"[async-lower][future-write-0]seed\"" +
                            " (func \$future_write (param i32 i32) (result i32)))\n" +
                            "  (import \"plugin\" \"[async-lower][future-read-0]seed\"" +
                            " (func \$future_read (param i32 i32) (result i32)))\n" +
                            "  (import \"plugin\" \"[future-drop-writable-0]seed\"" +
                            " (func \$drop_writable (param i32)))\n" +
                            "  (import \"plugin\" \"[future-drop-readable-0]seed\"" +
                            " (func \$drop_readable (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$pair i64)\n" +
                            "    (local \$reader i32)\n" +
                            "    (local \$writer i32)\n" +
                            "    (local \$status i32)\n" +
                            "    (local.set \$pair (call \$future_new))\n" +
                            "    (local.set \$reader (i32.wrap_i64 (local.get \$pair)))\n" +
                            "    (local.set \$writer\n" +
                            "      (i32.wrap_i64\n" +
                            "        (i64.shr_u (local.get \$pair) (i64.const 32))))\n" +
                            "    (i32.store (i32.const 32) (i32.const 123456))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$future_write\n" +
                            "        (local.get \$writer)\n" +
                            "        (i32.const 32)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_writable (local.get \$writer))\n" +
                            "    (local.set \$status\n" +
                            "      (call \$future_read\n" +
                            "        (local.get \$reader)\n" +
                            "        (i32.const 64)))\n" +
                            "    (if (i32.ne (local.get \$status) (i32.const 0))\n" +
                            "      (then unreachable))\n" +
                            "    (call \$drop_readable (local.get \$reader))\n" +
                            "    (i32.load (i32.const 64)))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withHostImport("plugin", "seed") { wasi.completedFuture(0L) }
                .withWasiPreview3(wasi)
                .build()

        assertEquals(123456L, plugin.call("api.run"))
    }

    private fun socketAddressPort(value: Any?): Int {
        val variant = value as WitValue.Variant
        val payload = variant.value() as Map<*, *>
        return (payload["port"] as Number).toInt()
    }

    private class AsyncOnlyListenRuntime : WasiSocketRuntime, WasiSuspendingTcpListenRuntime {
        var syncListenCalled: Boolean = false
        var suspendingListenCalled: Boolean = false

        override fun connectTcp(
            remoteAddress: KtorInetSocketAddress,
            keepAlive: Boolean,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiTcpConnection =
            throw UnsupportedOperationException("connect is not used by this test")

        override fun listenTcp(localAddress: KtorInetSocketAddress, backlogSize: Int): WasiTcpListener {
            syncListenCalled = true
            throw UnsupportedOperationException("sync listen must not be used by async listen")
        }

        override suspend fun listenTcpSuspending(
            localAddress: KtorInetSocketAddress,
            backlogSize: Int,
        ): WasiTcpListener {
            suspendingListenCalled = true
            return FakeTcpListener(KtorInetSocketAddress("127.0.0.1", 45_678))
        }

        override fun bindUdp(
            localAddress: KtorInetSocketAddress,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiUdpEndpoint =
            throw UnsupportedOperationException("UDP is not used by this test")
    }

    private class FakeTcpListener(
        override val localAddress: KtorInetSocketAddress,
    ) : WasiTcpListener {
        override fun accept(timeoutMillis: Long): WasiTcpConnection? =
            throw UnsupportedOperationException("accept is not used by this test")

        override suspend fun accept(): WasiTcpConnection =
            throw UnsupportedOperationException("accept is not used by this test")

        override fun isOpen(): Boolean = true

        override fun close() {
        }
    }

    private class RecordingCanonicalContext : WasiPreview3CanonicalContext {
        private val memory = ByteArray(256)
        val futureLoads = LinkedHashMap<Int, Any?>()
        val futureStores = LinkedHashMap<Int, Any?>()
        val listLoads = LinkedHashMap<Int, List<Any?>>()
        val listStores = LinkedHashMap<Int, List<Any?>>()

        override fun writeMemory(ptr: Int, bytes: ByteArray) {
            bytes.copyInto(memory, destinationOffset = ptr)
        }

        override fun readMemory(ptr: Int, len: Int): ByteArray =
            memory.copyOfRange(ptr, ptr + len)

        override fun storeListElements(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
            values: List<Any?>,
        ) {
            assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
            listStores[ptr] = values.toList()
        }

        override fun loadListElements(
            ptr: Int,
            len: Int,
            payloadType: WitPackage.TypeRef,
        ): List<Any?> {
            assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
            val values = requireNotNull(listLoads[ptr]) { "missing list load at $ptr" }
            return values.take(len)
        }

        override fun storeFutureValue(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
            value: Any?,
        ) {
            assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
            futureStores[ptr] = value
        }

        override fun loadFutureValue(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
        ): Any? {
            assertEquals(WitPackage.TypeRef.TypeKind.PRIMITIVE, payloadType.kind())
            return futureLoads[ptr]
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertTrailerResult(
        result: WitResult<Map<String, List<ByteArray>>?, Any?>,
        name: String,
        value: String,
    ) {
        when (result) {
            is WitResult.Ok<*, *> ->
                assertTrailerMap(result.value() as Map<String, List<ByteArray>>?, name, value)
            is WitResult.Err<*, *> ->
                throw AssertionError("expected HTTP trailers, got error ${result.value()}")
        }
    }

    private fun trailerFutureSnapshot(
        wasi: WasiPreview3,
        future: WitFuture<*>,
    ): Map<String, List<ByteArray>>? {
        return when (val result = wasi.futureValue(future)) {
            is WitResult.Ok<*, *> -> {
                val fields = result.value() ?: return null
                wasi.httpFieldsSnapshot(fields as WitResource<*>)
            }
            is WitResult.Err<*, *> ->
                throw AssertionError("expected HTTP trailers future, got error ${result.value()}")
            else -> throw AssertionError("expected HTTP trailers future result, got $result")
        }
    }

    private fun assertTrailerMap(
        trailers: Map<String, List<ByteArray>>?,
        name: String,
        value: String,
    ) {
        assertTrue(trailers != null, "expected HTTP trailers")
        val values = trailers!![name.lowercase()]
        assertTrue(values != null, "expected trailer $name")
        assertArrayEquals(value.toByteArray(StandardCharsets.ISO_8859_1), values!![0])
    }

    private class CapturingHostImports : WasiHostImportBuilder {
        private val handlers = LinkedHashMap<String, HostHandler>()

        override fun withHostImport(
            interfaceName: String?,
            functionName: String?,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[key(interfaceName, functionName)] = handler
            return this
        }

        override fun withHostImport(
            qualifiedName: String,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[qualifiedName] = handler
            return this
        }

        fun call(
            interfaceName: String,
            functionName: String,
            vararg arguments: Any?,
        ): Any? {
            val key = key(interfaceName, functionName)
            val handler = handlers[key] ?: error("missing host import $key")
            return handler.apply(arguments.asList())
        }

        private fun key(
            interfaceName: String?,
            functionName: String?,
        ): String = "$interfaceName::$functionName"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> expectOk(
        result: Any?,
        operation: String,
    ): T =
        when (result) {
            is WitResult.Ok<*, *> -> result.value() as T
            is WitResult.Err<*, *> ->
                throw AssertionError("expected $operation to succeed, got error ${result.value()}")
            else -> throw AssertionError("expected $operation result, got $result")
        }

    private fun expectErr(
        result: Any?,
        operation: String,
    ): Any? =
        when (result) {
            is WitResult.Ok<*, *> ->
                throw AssertionError("expected $operation to fail, got ${result.value()}")
            is WitResult.Err<*, *> -> result.value()
            else -> throw AssertionError("expected $operation result, got $result")
        }

    private fun handle(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is WitResource<*> -> value.handle()
            else -> throw AssertionError("expected resource handle, got $value")
        }

    private suspend fun readAllStreamBytes(
        wasi: WasiPreview3,
        stream: Long,
    ): ByteArray {
        val buffer = Buffer()
        while (true) {
            val chunk = wasi.readByteStreamChunk(stream) ?: break
            buffer.write(chunk)
        }
        return buffer.readByteArray()
    }

    private fun descriptorSize(stat: Map<String, Any?>): Long = (stat["size"] as Number).toLong()

    private companion object {
        private const val CANONICAL_ASYNC_BLOCKED: Long = 0xffff_ffffL
        private const val CANONICAL_ASYNC_COMPLETED: Long = 0L
        private const val CANONICAL_ASYNC_DROPPED: Long = 1L
        private const val CANONICAL_ASYNC_CANCELLED: Long = 2L

        private fun canonicalTransferCompleted(count: Int): Long = count.toLong() shl 4

        private fun canonicalTransferDropped(count: Int): Long =
            (count.toLong() shl 4) or CANONICAL_ASYNC_DROPPED
    }
}
