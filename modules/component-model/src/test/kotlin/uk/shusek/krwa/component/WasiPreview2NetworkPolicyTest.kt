package uk.shusek.krwa.component

import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WasiPreview2NetworkPolicyTest {
    @Test
    fun deniesAllNetworkingByDefault() {
        val client = RecordingHttpClient()
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withHttpClient(client)
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            val network = imports.call("instance-network", "instance-network")
            val socket = expectOk(imports.call("tcp-create-socket", "create-tcp-socket", "ipv4"))

            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "tcp",
                        "[method]tcp-socket.start-connect",
                        socket,
                        network,
                        ipv4SocketAddress(443),
                    )
                ),
            )
            assertEquals(
                "access-denied",
                expectErr(imports.call("ip-name-lookup", "resolve-addresses", network, "localhost")),
            )
            assertEquals("HTTP-request-denied", expectErr(sendHttp(imports, "api.example.test:443")))
            assertEquals(0, runtime.connectCalls)
            assertEquals(0, client.requests.size)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun enforcesExactHttpGrantAndKeepsRawSocketsDenied() {
        val client = RecordingHttpClient()
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withHttpClient(client)
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        httpEndpoints =
                            setOf(
                                WasiHttpNetworkEndpoint(
                                    WasiHttpNetworkProtocol.Https,
                                    "api.example.test",
                                    443,
                                )
                            )
                    )
                )
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            expectOk(sendHttp(imports, "API.EXAMPLE.TEST.:443"))
            assertEquals(1, client.requests.size)
            val sentUrl = Url(client.requests.single().uri)
            assertEquals("api.example.test", sentUrl.host.lowercase().trimEnd('.'))
            assertEquals(443, sentUrl.port)

            assertEquals("HTTP-request-denied", expectErr(sendHttp(imports, "api.example.test:444")))
            assertEquals("HTTP-request-denied", expectErr(sendHttp(imports, "api.example.test.evil:443")))
            assertEquals("HTTP-request-denied", expectErr(sendHttp(imports, "127.0.0.1:443")))
            assertEquals(
                "HTTP-request-denied",
                expectErr(sendHttp(imports, "api.example.test:443", scheme = "HTTP")),
            )
            assertEquals(1, client.requests.size)

            val network = imports.call("instance-network", "instance-network")
            val socket = expectOk(imports.call("tcp-create-socket", "create-tcp-socket", "ipv4"))
            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "tcp",
                        "[method]tcp-socket.start-connect",
                        socket,
                        network,
                        ipv4SocketAddress(443),
                    )
                ),
            )
            assertEquals(0, runtime.connectCalls)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun enforcesRawSocketGrantsBeforeCallingRuntime() {
        val client = RecordingHttpClient()
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withHttpClient(client)
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        rawSocketEndpoints = setOf(WasiNetworkEndpoint("127.0.0.1", 443))
                    )
                )
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            val network = imports.call("instance-network", "instance-network")
            val socket = expectOk(imports.call("tcp-create-socket", "create-tcp-socket", "ipv4"))
            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "tcp",
                        "[method]tcp-socket.start-connect",
                        socket,
                        network,
                        ipv4SocketAddress(444),
                    )
                ),
            )
            assertEquals(0, runtime.connectCalls)

            expectOk(
                imports.call(
                    "tcp",
                    "[method]tcp-socket.start-connect",
                    socket,
                    network,
                    ipv4SocketAddress(443),
                )
            )
            assertEquals(1, runtime.connectCalls)

            assertEquals(
                "access-denied",
                expectErr(
                    imports.call("ip-name-lookup", "resolve-addresses", network, "not-granted.invalid")
                ),
            )
            assertEquals("HTTP-request-denied", expectErr(sendHttp(imports, "api.example.test:443")))
            assertEquals(0, client.requests.size)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun hostnameGrantOnlyAuthorizesAddressesReturnedByNameLookup() {
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        rawSocketEndpoints = setOf(WasiNetworkEndpoint("localhost", 8443))
                    )
                )
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            val network = imports.call("instance-network", "instance-network")
            val stream =
                expectOk(imports.call("ip-name-lookup", "resolve-addresses", network, "localhost"))
            val resolved =
                expectOk(
                    imports.call(
                        "ip-name-lookup",
                        "[method]resolve-address-stream.resolve-next-address",
                        stream,
                    )
                ) as WitValue.Variant
            val socket =
                expectOk(imports.call("tcp-create-socket", "create-tcp-socket", resolved.label()))

            expectOk(
                imports.call(
                    "tcp",
                    "[method]tcp-socket.start-connect",
                    socket,
                    network,
                    resolvedSocketAddress(resolved, 8443),
                )
            )
            assertEquals(1, runtime.connectCalls)

            val otherSocket =
                expectOk(imports.call("tcp-create-socket", "create-tcp-socket", resolved.label()))
            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "tcp",
                        "[method]tcp-socket.start-connect",
                        otherSocket,
                        network,
                        resolvedSocketAddress(resolved, 8444),
                    )
                ),
            )
            assertEquals(1, runtime.connectCalls)
        } finally {
            wasi.close()
        }
    }

    @Suppress("DEPRECATION")
    @OptIn(UnsafeComponentModelApi::class)
    @Test
    fun deprecatedUnrestrictedSwitchStillGrantsEverything() {
        val client = RecordingHttpClient()
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview2.builder()
                .withHttpClient(client)
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
                    ipv4SocketAddress(444),
                )
            )
            expectOk(sendHttp(imports, "api.example.test:443"))
            assertEquals(1, runtime.connectCalls)
            assertEquals(1, client.requests.size)
        } finally {
            wasi.close()
        }
    }

    private fun sendHttp(
        imports: CapturingHostImports,
        authority: String,
        scheme: String = "HTTPS",
    ): Any? {
        val headers = imports.call("types", "[constructor]fields")
        val request = imports.call("types", "[constructor]outgoing-request", headers)
        expectOk(imports.call("types", "[method]outgoing-request.set-scheme", request, scheme))
        expectOk(imports.call("types", "[method]outgoing-request.set-authority", request, authority))
        return imports.call("outgoing-handler", "handle", request, null)
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

    private fun resolvedSocketAddress(
        address: WitValue.Variant,
        port: Int,
    ): WitValue.Variant =
        if (address.label() == "ipv4") {
            WitValue.variant(
                "ipv4",
                WitValue.record(
                    "port",
                    port,
                    "address",
                    address.value(),
                ),
            )
        } else {
            WitValue.variant(
                "ipv6",
                WitValue.record(
                    "port",
                    port,
                    "flow-info",
                    0,
                    "address",
                    address.value(),
                    "scope-id",
                    0,
                ),
            )
        }

    private fun expectOk(result: Any?): Any? =
        when (result) {
            is WitResult.Ok<*, *> -> result.value()
            is WitResult.Err<*, *> -> throw AssertionError("expected success, got error ${result.value()}")
            else -> throw AssertionError("expected result, got $result")
        }

    private fun expectErr(result: Any?): Any? =
        when (result) {
            is WitResult.Ok<*, *> -> throw AssertionError("expected failure, got ${result.value()}")
            is WitResult.Err<*, *> -> result.value()
            else -> throw AssertionError("expected result, got $result")
        }

    private class RecordingHttpClient : WasiHttpClient {
        val requests = ArrayList<WasiHttpRequest>()

        override fun send(request: WasiHttpRequest): WasiHttpResponse {
            requests.add(request)
            return WasiHttpResponse(204, emptyMap(), ByteArray(0))
        }
    }

    private class RecordingSocketRuntime : WasiSocketRuntime {
        var connectCalls: Int = 0

        override fun connectTcp(
            remoteAddress: InetSocketAddress,
            keepAlive: Boolean,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiTcpConnection {
            connectCalls += 1
            return FakeTcpConnection(remoteAddress)
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
