package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class WasiHttpStreamingTest {
    @Test
    fun preview2OutgoingHandlerDoesNotReadResponseBodyBeforeIncomingBodyConsume() {
        val bodyReads = AtomicReference(0)
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(204, emptyMap(), FailingRawSource(bodyReads))
            }
        val plugin =
            WasmPlugin.builder(preview2HttpClientPackage())
                .withModule(preview2HttpClientFutureModule())
                .withWasiPreview2(
                    WasiPreview2.builder().withNetworking().withHttpClient(client).build()
                )
                .build()

        assertEquals(0, bodyReads.get())
        assertTrue((plugin.call("api.run") as Long) > 0L)
        assertEquals(0, bodyReads.get())
    }

    @Test
    fun preview3ClientSendDoesNotReadResponseBodyBeforeConsumeBody() {
        val version = WasiPreview3.DEFAULT_VERSION
        val bodyReads = AtomicReference(0)
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(299, emptyMap(), FailingRawSource(bodyReads))
            }
        val plugin =
            WasmPlugin.builder(preview3HttpClientPackage(version))
                .withModule(preview3HttpClientStatusModule(version))
                .withWasiPreview3(
                    WasiPreview3.builder().withNetworking().withHttpClient(client).build()
                )
                .build()

        assertEquals(299L, plugin.call("api.run"))
        assertEquals(0, bodyReads.get())
    }

    @Test
    fun preview3ClientSendSuspendingClientCompletesThroughWaitableSet() {
        val version = WasiPreview3.DEFAULT_VERSION
        val syncCalls = AtomicInteger(0)
        val suspendingCalls = AtomicInteger(0)
        val client =
            object : WasiSuspendingHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse {
                    syncCalls.incrementAndGet()
                    throw AssertionError("synchronous send should not be used")
                }

                override suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse {
                    suspendingCalls.incrementAndGet()
                    delay(25)
                    return WasiHttpResponse(277, emptyMap(), ByteArray(0))
                }
            }
        val plugin =
            WasmPlugin.builder(preview3HttpClientPackage(version))
                .withModule(preview3HttpClientStatusModule(version))
                .withWasiPreview3(
                    WasiPreview3.builder().withNetworking().withHttpClient(client).build()
                )
                .build()

        assertEquals(277L, plugin.call("api.run"))
        assertEquals(0, syncCalls.get())
        assertEquals(1, suspendingCalls.get())
    }

    @Test
    fun preview3ClientResponseBodyCanBeReadInChunks() {
        val version = WasiPreview3.DEFAULT_VERSION
        val bodyReads = AtomicReference(0)
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(
                        200,
                        emptyMap(),
                        ChunkedRawSource("data".encodeToByteArray(), bodyReads),
                    )
            }
        val plugin =
            WasmPlugin.builder(preview3HttpClientPackage(version))
                .withModule(preview3HttpClientReadBodyModule(version))
                .withWasiPreview3(
                    WasiPreview3.builder().withNetworking().withHttpClient(client).build()
                )
                .build()

        assertEquals(410L, plugin.call("api.run"), "bodyReads=${bodyReads.get()}")
        assertEquals(2, bodyReads.get())
    }

    @Test
    fun preview3ClientResponseBodyStreamsLargeCatalogPayloadInChunks() {
        val version = WasiPreview3.DEFAULT_VERSION
        val bodyReads = AtomicReference(0)
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(
                        200,
                        emptyMap(),
                        GeneratedRawSource(
                            byteCount = LargeCatalogPayloadBytes,
                            maxChunkSize = LargeResponseReadChunkSize,
                            reads = bodyReads,
                        ),
                    )
            }
        val plugin =
            WasmPlugin.builder(preview3HttpClientPackage(version))
                .withModule(
                    preview3HttpClientDrainBodyModule(
                        version = version,
                        readChunkSize = LargeResponseReadChunkSize,
                    )
                )
                .withWasiPreview3(
                    WasiPreview3.builder().withNetworking().withHttpClient(client).build()
                )
                .build()

        val started = System.nanoTime()
        val result = plugin.call("api.run")
        val elapsedNanos = System.nanoTime() - started

        assertEquals(LargeCatalogPayloadBytes.toLong(), result, "bodyReads=${bodyReads.get()}")
        assertEquals(
            expectedReadCalls(LargeCatalogPayloadBytes, LargeResponseReadChunkSize),
            bodyReads.get(),
        )
        println(
            "KRWA preview3 HTTP stream: bytes=$LargeCatalogPayloadBytes, " +
                "chunk=$LargeResponseReadChunkSize, reads=${bodyReads.get()}, " +
                "elapsedMs=${elapsedNanos / 1_000_000}, " +
                "throughputMiBps=${throughputMiBps(LargeCatalogPayloadBytes, elapsedNanos)}"
        )
    }

    @Test
    fun ktorWasiHttpClientReturnsBeforeResponseBodyCompletes() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val firstChunkWritten = CompletableDeferred<Unit>()
        val finishBody = CompletableDeferred<Unit>()
        val serverJob =
            launch(Dispatchers.IO) {
                server.use { socketServer ->
                    val socket = socketServer.accept()
                    socket.use {
                        val reader =
                            it.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                break
                            }
                        }

                        val output = it.getOutputStream()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.ISO_8859_1)
                        )
                        output.write(byteArrayOf('a'.code.toByte()))
                        output.flush()
                        firstChunkWritten.complete(Unit)

                        withTimeout(5.seconds) {
                            finishBody.await()
                        }
                        output.write(byteArrayOf('b'.code.toByte()))
                        output.flush()
                    }
                }
            }

        val client = KtorHttpClient(CIO)
        try {
            val responseDeferred =
                async(Dispatchers.Default) {
                    KtorWasiHttpClient(client).sendSuspending(
                        WasiHttpRequest(
                            method = "GET",
                            uri = "http://127.0.0.1:${server.localPort}/stream",
                            headers = emptyList(),
                            body = ByteArray(0),
                            timeout = 10.seconds,
                        )
                    )
                }

            withTimeout(2.seconds) {
                firstChunkWritten.await()
            }
            val response =
                withTimeout(1.seconds) {
                    responseDeferred.await()
                }
            assertEquals(200, response.status)

            val source = response.consumeBodySource()
            val first = Buffer()
            assertEquals(1L, source.readAtMostTo(first, 1))
            assertEquals("a", first.readByteArray().decodeToString())

            finishBody.complete(Unit)

            val second = Buffer()
            assertEquals(1L, source.readAtMostTo(second, 1))
            assertEquals("b", second.readByteArray().decodeToString())
            assertEquals(-1L, source.readAtMostTo(Buffer(), 1))
            source.close()
        } finally {
            finishBody.complete(Unit)
            client.close()
            server.close()
            serverJob.cancelAndJoin()
        }
    }

    private fun preview2HttpClientPackage(): WitPackage =
        WitPackage.parse(
            """
            package example:wasi2-http-client-lazy-body;

            world plugin {
              import wasi:http/types@0.2.11;
              import wasi:http/outgoing-handler@0.2.11;
              export api;
            }

            interface api {
              run: func() -> u32;
            }

            package wasi:http@0.2.11 {
              interface types {
                variant error-code {
                  HTTP-request-denied,
                  HTTP-request-URI-invalid,
                  internal-error(option<string>),
                }

                resource fields {
                  constructor();
                }

                resource outgoing-request {
                  constructor(headers: fields);
                  set-authority: func(authority: option<string>) -> result<_, error-code>;
                  set-path-with-query: func(path-with-query: option<string>) -> result<_, error-code>;
                }

                resource request-options;
                resource future-incoming-response;
              }

              interface outgoing-handler {
                use types.{outgoing-request, request-options, future-incoming-response, error-code};
                handle: func(
                  request: outgoing-request,
                  options: option<borrow<request-options>>,
                ) -> result<future-incoming-response, error-code>;
              }
            }
            """
                .trimIndent()
        )

    private fun preview2HttpClientFutureModule(): ByteArray {
        val d = '$'
        val authority = "example.invalid"
        val pathWithQuery = "/lazy"
        return Wat2Wasm.parse(
            """
            (module
              (import "wasi:http/types@0.2.11" "[constructor]fields"
                (func ${d}fields_new (result i32)))
              (import "wasi:http/types@0.2.11" "[constructor]outgoing-request"
                (func ${d}request_new (param i32) (result i32)))
              (import "wasi:http/types@0.2.11" "[method]outgoing-request.set-authority"
                (func ${d}set_authority (param i32 i32 i32 i32 i32)))
              (import "wasi:http/types@0.2.11" "[method]outgoing-request.set-path-with-query"
                (func ${d}set_path (param i32 i32 i32 i32 i32)))
              (import "wasi:http/outgoing-handler@0.2.11" "handle"
                (func ${d}handle (param i32 i32 i32 i32)))
              (memory (export "memory") 1)
              (data (i32.const 16) "$authority")
              (data (i32.const 64) "$pathWithQuery")
              (func ${d}run (result i32)
                (local ${d}request i32)
                (local.set ${d}request
                  (call ${d}request_new (call ${d}fields_new)))
                (call ${d}set_authority
                  (local.get ${d}request)
                  (i32.const 1)
                  (i32.const 16)
                  (i32.const ${authority.length})
                  (i32.const 128))
                (if
                  (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                  (then (return (i32.const 98))))
                (call ${d}set_path
                  (local.get ${d}request)
                  (i32.const 1)
                  (i32.const 64)
                  (i32.const ${pathWithQuery.length})
                  (i32.const 144))
                (if
                  (i32.ne (i32.load8_u (i32.const 144)) (i32.const 0))
                  (then (return (i32.const 97))))
                (call ${d}handle
                  (local.get ${d}request)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 160))
                (if
                  (i32.ne (i32.load8_u (i32.const 160)) (i32.const 0))
                  (then (return (i32.const 99))))
                (i32.load (i32.const 164)))
              (export "api.run" (func ${d}run))
            )
            """
                .trimIndent()
        )
    }

    private fun preview3HttpClientPackage(version: String): WitPackage =
        WitPackage.parse(
            """
            package example:wasi3-http-client-lazy-body;

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

    private fun preview3HttpClientStatusModule(version: String): ByteArray {
        val d = '$'
        val authority = "example.invalid"
        val pathWithQuery = "/lazy"
        return Wat2Wasm.parse(
            """
            (module
              (import "wasi:http/types@$version" "[constructor]fields"
                (func ${d}fields_new (result i32)))
              (import "wasi:http/types@$version" "[static]request.new"
                (func ${d}request_new
                  (param i32 i32 i32 i32 i32 i32 i32)))
              (import "wasi:http/types@$version" "[method]request.set-authority"
                (func ${d}set_authority (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/types@$version" "[method]request.set-path-with-query"
                (func ${d}set_path (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/client@$version" "send"
                (func ${d}send (param i32) (result i32)))
              (import "wasi:http/client@$version" "[async-lower][future-read-0]send"
                (func ${d}send_future_read (param i32 i32) (result i32)))
              (import "wasi:http/client@$version" "waitable-set.new"
                (func ${d}waitable_set_new (result i32)))
              (import "wasi:http/client@$version" "waitable.join"
                (func ${d}waitable_join (param i32 i32)))
              (import "wasi:http/client@$version" "waitable-set.wait"
                (func ${d}waitable_set_wait (param i32 i32) (result i32)))
              (import "wasi:http/client@$version" "waitable-set.drop"
                (func ${d}waitable_set_drop (param i32)))
              (import "wasi:http/types@$version" "[method]response.get-status-code"
                (func ${d}status (param i32) (result i32)))
              (memory (export "memory") 1)
              (global ${d}heap (mut i32) (i32.const 256))
              (data (i32.const 16) "$authority")
              (data (i32.const 64) "$pathWithQuery")
              (func (export "canonical_abi_realloc")
                (param ${d}old i32) (param ${d}old_size i32)
                (param ${d}align i32) (param ${d}new_size i32)
                (result i32)
                (local ${d}ptr i32)
                (local.set ${d}ptr
                  (i32.and
                    (i32.add
                      (global.get ${d}heap)
                      (i32.sub (local.get ${d}align) (i32.const 1)))
                    (i32.xor
                      (i32.sub (local.get ${d}align) (i32.const 1))
                      (i32.const -1))))
                (global.set ${d}heap
                  (i32.add (local.get ${d}ptr) (local.get ${d}new_size)))
                (local.get ${d}ptr))
              (func ${d}run (result i32)
                (local ${d}request i32)
                (local ${d}response i32)
                (local ${d}future i32)
                (local ${d}read_status i32)
                (local ${d}waitable_set i32)
                (call ${d}request_new
                  (call ${d}fields_new)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 96))
                (local.set ${d}request (i32.load (i32.const 96)))
                (if
                  (i32.ne
                    (call ${d}set_authority
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 16)
                      (i32.const ${authority.length}))
                    (i32.const 0))
                  (then (return (i32.const 98))))
                (if
                  (i32.ne
                    (call ${d}set_path
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 64)
                      (i32.const ${pathWithQuery.length}))
                    (i32.const 0))
                  (then (return (i32.const 97))))
                (local.set ${d}future (call ${d}send (local.get ${d}request)))
                (local.set ${d}read_status
                  (call ${d}send_future_read
                    (local.get ${d}future)
                    (i32.const 128)))
                (if
                  (i32.eq (local.get ${d}read_status) (i32.const -1))
                  (then
                    (local.set ${d}waitable_set (call ${d}waitable_set_new))
                    (call ${d}waitable_join
                      (local.get ${d}future)
                      (local.get ${d}waitable_set))
                    (if
                      (i32.ne
                        (call ${d}waitable_set_wait
                          (local.get ${d}waitable_set)
                          (i32.const 192))
                        (i32.const 4))
                      (then (return (i32.const 95))))
                    (if
                      (i32.ne
                        (i32.load (i32.const 192))
                        (local.get ${d}future))
                      (then (return (i32.const 94))))
                    (if
                      (i32.ne
                        (i32.load (i32.const 196))
                        (i32.const 0))
                      (then (return (i32.const 93))))
                    (call ${d}waitable_set_drop (local.get ${d}waitable_set)))
                  (else
                    (if
                      (i32.ne (local.get ${d}read_status) (i32.const 0))
                      (then (return (i32.const 96))))))
                (if
                  (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                  (then (return (i32.const 99))))
                (local.set ${d}response (i32.load (i32.const 132)))
                (call ${d}status (local.get ${d}response)))
              (export "api.run" (func ${d}run))
            )
            """
                .trimIndent()
        )
    }

    private fun preview3HttpClientReadBodyModule(version: String): ByteArray {
        val d = '$'
        val authority = "example.invalid"
        val pathWithQuery = "/chunked"
        return Wat2Wasm.parse(
            """
            (module
              (import "wasi:http/types@$version" "[constructor]fields"
                (func ${d}fields_new (result i32)))
              (import "wasi:http/types@$version" "[static]request.new"
                (func ${d}request_new
                  (param i32 i32 i32 i32 i32 i32 i32)))
              (import "wasi:http/types@$version" "[method]request.set-authority"
                (func ${d}set_authority (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/types@$version" "[method]request.set-path-with-query"
                (func ${d}set_path (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/client@$version" "send"
                (func ${d}send (param i32) (result i32)))
              (import "wasi:http/client@$version" "[async-lower][future-read-0]send"
                (func ${d}send_future_read (param i32 i32) (result i32)))
              (import "wasi:http/types@$version" "[static]response.consume-body"
                (func ${d}response_consume (param i32 i32 i32)))
              (import "wasi:http/types@$version" "[async-lower][stream-read-1][static]response.consume-body"
                (func ${d}stream_read (param i32 i32 i32) (result i32)))
              (memory (export "memory") 1)
              (global ${d}heap (mut i32) (i32.const 256))
              (data (i32.const 16) "$authority")
              (data (i32.const 64) "$pathWithQuery")
              (func (export "canonical_abi_realloc")
                (param ${d}old i32) (param ${d}old_size i32)
                (param ${d}align i32) (param ${d}new_size i32)
                (result i32)
                (local ${d}ptr i32)
                (local.set ${d}ptr
                  (i32.and
                    (i32.add
                      (global.get ${d}heap)
                      (i32.sub (local.get ${d}align) (i32.const 1)))
                    (i32.xor
                      (i32.sub (local.get ${d}align) (i32.const 1))
                      (i32.const -1))))
                (global.set ${d}heap
                  (i32.add (local.get ${d}ptr) (local.get ${d}new_size)))
                (local.get ${d}ptr))
              (func ${d}run (result i32)
                (local ${d}request i32)
                (local ${d}response i32)
                (local ${d}future i32)
                (local ${d}stream i32)
                (local ${d}status i32)
                (call ${d}request_new
                  (call ${d}fields_new)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 96))
                (local.set ${d}request (i32.load (i32.const 96)))
                (if
                  (i32.ne
                    (call ${d}set_authority
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 16)
                      (i32.const ${authority.length}))
                    (i32.const 0))
                  (then (return (i32.const 98))))
                (if
                  (i32.ne
                    (call ${d}set_path
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 64)
                      (i32.const ${pathWithQuery.length}))
                    (i32.const 0))
                  (then (return (i32.const 97))))
                (local.set ${d}future (call ${d}send (local.get ${d}request)))
                (if
                  (i32.ne
                    (call ${d}send_future_read
                      (local.get ${d}future)
                      (i32.const 128))
                    (i32.const 0))
                  (then (return (i32.const 96))))
                (if
                  (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                  (then (return (i32.const 99))))
                (local.set ${d}response (i32.load (i32.const 132)))
                (call ${d}response_consume
                  (local.get ${d}response)
                  (i32.const 0)
                  (i32.const 160))
                (local.set ${d}stream (i32.load (i32.const 160)))
                (local.set ${d}status
                  (call ${d}stream_read
                    (local.get ${d}stream)
                    (i32.const 200)
                    (i32.const 2)))
                (if
                  (i32.ne (local.get ${d}status) (i32.const 32))
                  (then (return (i32.const 95))))
                (local.set ${d}status
                  (call ${d}stream_read
                    (local.get ${d}stream)
                    (i32.const 202)
                    (i32.const 2)))
                (if
                  (i32.ne (local.get ${d}status) (i32.const 32))
                  (then (return (local.get ${d}status))))
                (i32.add
                  (i32.add
                    (i32.add
                      (i32.load8_u (i32.const 200))
                      (i32.load8_u (i32.const 201)))
                    (i32.load8_u (i32.const 202)))
                  (i32.load8_u (i32.const 203))))
              (export "api.run" (func ${d}run))
            )
            """
                .trimIndent()
        )
    }

    private fun preview3HttpClientDrainBodyModule(version: String, readChunkSize: Int): ByteArray {
        val d = '$'
        val authority = "example.invalid"
        val pathWithQuery = "/large"
        val bufferPtr = 4096
        return Wat2Wasm.parse(
            """
            (module
              (import "wasi:http/types@$version" "[constructor]fields"
                (func ${d}fields_new (result i32)))
              (import "wasi:http/types@$version" "[static]request.new"
                (func ${d}request_new
                  (param i32 i32 i32 i32 i32 i32 i32)))
              (import "wasi:http/types@$version" "[method]request.set-authority"
                (func ${d}set_authority (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/types@$version" "[method]request.set-path-with-query"
                (func ${d}set_path (param i32 i32 i32 i32) (result i32)))
              (import "wasi:http/client@$version" "send"
                (func ${d}send (param i32) (result i32)))
              (import "wasi:http/client@$version" "[async-lower][future-read-0]send"
                (func ${d}send_future_read (param i32 i32) (result i32)))
              (import "wasi:http/types@$version" "[static]response.consume-body"
                (func ${d}response_consume (param i32 i32 i32)))
              (import "wasi:http/types@$version" "[async-lower][stream-read-1][static]response.consume-body"
                (func ${d}stream_read (param i32 i32 i32) (result i32)))
              (memory (export "memory") 8)
              (global ${d}heap (mut i32) (i32.const 300000))
              (data (i32.const 16) "$authority")
              (data (i32.const 64) "$pathWithQuery")
              (func (export "canonical_abi_realloc")
                (param ${d}old i32) (param ${d}old_size i32)
                (param ${d}align i32) (param ${d}new_size i32)
                (result i32)
                (local ${d}ptr i32)
                (local.set ${d}ptr
                  (i32.and
                    (i32.add
                      (global.get ${d}heap)
                      (i32.sub (local.get ${d}align) (i32.const 1)))
                    (i32.xor
                      (i32.sub (local.get ${d}align) (i32.const 1))
                      (i32.const -1))))
                (global.set ${d}heap
                  (i32.add (local.get ${d}ptr) (local.get ${d}new_size)))
                (local.get ${d}ptr))
              (func ${d}run (result i32)
                (local ${d}request i32)
                (local ${d}response i32)
                (local ${d}future i32)
                (local ${d}stream i32)
                (local ${d}status i32)
                (local ${d}kind i32)
                (local ${d}count i32)
                (local ${d}total i32)
                (call ${d}request_new
                  (call ${d}fields_new)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 0)
                  (i32.const 96))
                (local.set ${d}request (i32.load (i32.const 96)))
                (if
                  (i32.ne
                    (call ${d}set_authority
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 16)
                      (i32.const ${authority.length}))
                    (i32.const 0))
                  (then (return (i32.const 98))))
                (if
                  (i32.ne
                    (call ${d}set_path
                      (local.get ${d}request)
                      (i32.const 1)
                      (i32.const 64)
                      (i32.const ${pathWithQuery.length}))
                    (i32.const 0))
                  (then (return (i32.const 97))))
                (local.set ${d}future (call ${d}send (local.get ${d}request)))
                (if
                  (i32.ne
                    (call ${d}send_future_read
                      (local.get ${d}future)
                      (i32.const 128))
                    (i32.const 0))
                  (then (return (i32.const 96))))
                (if
                  (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0))
                  (then (return (i32.const 99))))
                (local.set ${d}response (i32.load (i32.const 132)))
                (call ${d}response_consume
                  (local.get ${d}response)
                  (i32.const 0)
                  (i32.const 160))
                (local.set ${d}stream (i32.load (i32.const 160)))
                (loop ${d}read_loop
                  (local.set ${d}status
                    (call ${d}stream_read
                      (local.get ${d}stream)
                      (i32.const $bufferPtr)
                      (i32.const $readChunkSize)))
                  (if
                    (i32.eq (local.get ${d}status) (i32.const -1))
                    (then (br ${d}read_loop)))
                  (local.set ${d}kind
                    (i32.and (local.get ${d}status) (i32.const 15)))
                  (local.set ${d}count
                    (i32.shr_u (local.get ${d}status) (i32.const 4)))
                  (local.set ${d}total
                    (i32.add (local.get ${d}total) (local.get ${d}count)))
                  (if
                    (i32.and
                      (i32.eq (local.get ${d}kind) (i32.const 0))
                      (i32.gt_u (local.get ${d}count) (i32.const 0)))
                    (then (br ${d}read_loop))))
                (local.get ${d}total))
              (export "api.run" (func ${d}run))
            )
            """
                .trimIndent()
        )
    }

}

private class FailingRawSource(private val reads: AtomicReference<Int>) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        reads.set(reads.get() + 1)
        throw AssertionError("HTTP response body was buffered before consume-body")
    }

    override fun close() {}
}

private class ChunkedRawSource(
    private val bytes: ByteArray,
    private val reads: AtomicReference<Int>,
) : RawSource {
    private var offset = 0

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        reads.set(reads.get() + 1)
        if (offset >= bytes.size) {
            return -1L
        }
        val count = minOf(byteCount.toInt(), 2, bytes.size - offset)
        sink.write(bytes, offset, offset + count)
        offset += count
        return count.toLong()
    }

    override fun close() {}
}

private class GeneratedRawSource(
    private val byteCount: Int,
    private val maxChunkSize: Int,
    private val reads: AtomicReference<Int>,
) : RawSource {
    private val chunk = ByteArray(maxChunkSize) { index -> index.toByte() }
    private var offset = 0

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        reads.set(reads.get() + 1)
        if (offset >= this.byteCount) {
            return -1L
        }
        val count = minOf(byteCount.toInt(), maxChunkSize, this.byteCount - offset)
        sink.write(chunk, 0, count)
        offset += count
        return count.toLong()
    }

    override fun close() {}
}

private fun expectedReadCalls(byteCount: Int, chunkSize: Int): Int =
    ((byteCount + chunkSize - 1) / chunkSize) + 1

private fun throughputMiBps(byteCount: Int, elapsedNanos: Long): Long {
    if (elapsedNanos <= 0L) {
        return 0L
    }
    val mib = byteCount.toDouble() / (1024.0 * 1024.0)
    val seconds = elapsedNanos.toDouble() / 1_000_000_000.0
    return (mib / seconds).toLong()
}

private const val LargeCatalogPayloadBytes = 20_731_837
private const val LargeResponseReadChunkSize = 256 * 1024
