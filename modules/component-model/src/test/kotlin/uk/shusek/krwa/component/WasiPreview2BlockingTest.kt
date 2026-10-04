package uk.shusek.krwa.component

import io.ktor.network.sockets.InetSocketAddress
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A guest must not be able to park a host thread indefinitely or leak host resources by dropping
 * handles: HTTP requests always carry a bounded timeout and `[resource-drop]` releases sockets and
 * response bodies.
 */
class WasiPreview2BlockingTest {
    @Test
    fun appliesTheDefaultHttpTimeoutWhenTheGuestSuppliesNone() {
        val client = RecordingHttpClient()
        val imports = CapturingHostImports()
        val wasi = WasiPreview2.builder().withHttpClient(client).withNetworking().build()
        wasi.install(imports)
        try {
            expectOk(sendHttp(imports, options = null))

            assertEquals(WASI_PREVIEW_DEFAULT_HTTP_TIMEOUT, client.requests.single().timeout)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun keepsGuestHttpTimeoutsBelowTheHostMaximum() {
        val client = RecordingHttpClient()
        val imports = CapturingHostImports()
        val wasi = WasiPreview2.builder().withHttpClient(client).withNetworking().build()
        wasi.install(imports)
        try {
            val options = imports.call("types", "[constructor]request-options")
            expectOk(
                imports.call(
                    "types",
                    "[method]request-options.set-first-byte-timeout",
                    options,
                    WitValue.variant("some", 5.seconds.inWholeNanoseconds),
                )
            )
            expectOk(sendHttp(imports, options))

            assertEquals(5.seconds, client.requests.single().timeout)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun clampsGuestHttpTimeoutsToTheHostMaximum() {
        val client = RecordingHttpClient()
        val imports = CapturingHostImports()
        val wasi = WasiPreview2.builder().withHttpClient(client).withNetworking().build()
        wasi.install(imports)
        try {
            val options = imports.call("types", "[constructor]request-options")
            expectOk(
                imports.call(
                    "types",
                    "[method]request-options.set-connect-timeout",
                    options,
                    WitValue.variant("some", 1.hours.inWholeNanoseconds),
                )
            )
            expectOk(sendHttp(imports, options))

            assertEquals(WASI_PREVIEW_MAX_HTTP_TIMEOUT, client.requests.single().timeout)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun droppingAnUnconsumedFutureIncomingResponseClosesTheBody() {
        val body = RecordingBodySource()
        val client = RecordingHttpClient(body)
        val imports = CapturingHostImports()
        val wasi = WasiPreview2.builder().withHttpClient(client).withNetworking().build()
        wasi.install(imports)
        try {
            val future = expectOk(sendHttp(imports, options = null))
            assertFalse(body.closed)

            imports.call("types", "[resource-drop]future-incoming-response", future)

            assertTrue(body.closed)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun droppingATcpSocketClosesTheConnection() {
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withHttpClient(RecordingHttpClient())
                .withNetworking()
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            val network = imports.call("instance-network", "instance-network")
            val socket = expectOk(imports.call("tcp-create-socket", "create-tcp-socket", "ipv4"))
            expectOk(
                imports.call(
                    "tcp",
                    "[method]tcp-socket.start-connect",
                    socket,
                    network,
                    ipv4SocketAddress(443),
                )
            )
            val connection = runtime.connections.single()
            assertTrue(connection.isOpen())

            imports.call("tcp", "[resource-drop]tcp-socket", socket)

            assertFalse(connection.isOpen())
        } finally {
            wasi.close()
        }
    }

    private fun sendHttp(
        imports: CapturingHostImports,
        options: Any?,
    ): Any? {
        val headers = imports.call("types", "[constructor]fields")
        val request = imports.call("types", "[constructor]outgoing-request", headers)
        expectOk(imports.call("types", "[method]outgoing-request.set-scheme", request, "HTTPS"))
        expectOk(
            imports.call(
                "types",
                "[method]outgoing-request.set-authority",
                request,
                "api.example.test",
            )
        )
        return imports.call("outgoing-handler", "handle", request, options)
    }

    private fun ipv4SocketAddress(port: Int): WitValue.Variant =
        WitValue.variant(
            "ipv4",
            WitValue.record(
                "port",
                port,
                "address",
                listOf(127, 0, 0, 1),
            ),
        )

    private fun expectOk(result: Any?): Any? =
        when (result) {
            is WitResult.Ok<*, *> -> result.value()
            is WitResult.Err<*, *> -> throw AssertionError("expected success, got error ${result.value()}")
            else -> throw AssertionError("expected result, got $result")
        }

    private class RecordingBodySource : RawSource {
        var closed: Boolean = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L

        override fun close() {
            closed = true
        }
    }

    private class RecordingHttpClient(
        private val body: RawSource? = null,
    ) : WasiHttpClient {
        val requests = ArrayList<WasiHttpRequest>()

        override fun send(request: WasiHttpRequest): WasiHttpResponse {
            requests.add(request)
            val bodySource = body
            return if (bodySource == null) {
                WasiHttpResponse(204, emptyMap(), ByteArray(0))
            } else {
                WasiHttpResponse(200, emptyMap(), bodySource)
            }
        }
    }

    private class RecordingSocketRuntime : WasiSocketRuntime {
        val connections = ArrayList<FakeTcpConnection>()

        override fun connectTcp(
            remoteAddress: InetSocketAddress,
            keepAlive: Boolean,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiTcpConnection {
            val connection = FakeTcpConnection(remoteAddress)
            connections.add(connection)
            return connection
        }

        override fun listenTcp(
            localAddress: InetSocketAddress,
            backlogSize: Int,
        ): WasiTcpListener =
            throw UnsupportedOperationException("TCP listen is not used by this test")

        override fun bindUdp(
            localAddress: InetSocketAddress,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiUdpEndpoint =
            throw UnsupportedOperationException("UDP is not used by this test")
    }

    private class FakeTcpConnection(
        override val remoteAddress: InetSocketAddress,
    ) : WasiTcpConnection {
        override val localAddress: InetSocketAddress =
            InetSocketAddress(byteArrayOf(127, 0, 0, 1), 49_152)
        private var open = true

        override fun isOpen(): Boolean = open

        override fun send(data: ByteArray) {
            throw UnsupportedOperationException("send is not used by this test")
        }

        override fun read(max: Int, timeoutMillis: Long): WasiTcpReadChunk =
            throw UnsupportedOperationException("read is not used by this test")

        override suspend fun awaitReadable(): Boolean = false

        override fun readUntilIdle(
            firstByteTimeoutMillis: Long,
            idleTimeoutMillis: Long,
        ): ByteArray = ByteArray(0)

        override fun inputSource(): RawSource =
            throw UnsupportedOperationException("input is not used by this test")

        override fun inputAvailable(): Int = 0

        override fun outputSink(): RawSink =
            throw UnsupportedOperationException("output is not used by this test")

        override fun shutdownInput() {
        }

        override fun shutdownOutput() {
        }

        override fun close() {
            open = false
        }
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
}
