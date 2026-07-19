package uk.shusek.krwa.component

import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WasiPreview3NetworkPolicyTest {
    @Test
    fun rejectsAmbiguousOrUrlShapedEndpoints() {
        val invalidHosts =
            listOf(
                "",
                " ",
                " api.example.test",
                "api.example.test ",
                "*.example.test",
                "https://api.example.test",
                "user@api.example.test",
                "api.example.test/path",
                "-api.example.test",
                "api-.example.test",
                "api..example.test",
                "api_example.test",
                "127.000.0.1",
                "127.0.0.1:443",
                "[api.example.test]",
                "[2001:db8::1",
                "2001:db8::1]",
                "2001:db8::1::2",
                "fe80::1%en0",
            )

        for (host in invalidHosts) {
            assertThrows(IllegalArgumentException::class.java) {
                WasiNetworkEndpoint(host, 443)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WasiNetworkEndpoint("api.example.test", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WasiNetworkEndpoint("api.example.test", 65_536)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WasiHttpNetworkEndpoint(
                WasiHttpNetworkProtocol.Https,
                "api.example.test",
                0,
            )
        }
    }

    @Test
    fun normalizesExactHostsAndDefensivelyCopiesGrants() {
        val mutableHttpEndpoints =
            linkedSetOf(
                WasiHttpNetworkEndpoint(
                    WasiHttpNetworkProtocol.Https,
                    "API.EXAMPLE.TEST.",
                    443,
                )
            )
        val policy =
            WasiNetworkPolicy(
                httpEndpoints = mutableHttpEndpoints,
                rawSocketEndpoints =
                    setOf(
                        WasiNetworkEndpoint("2001:0DB8::1", 8443),
                        WasiNetworkEndpoint("127.0.0.1", 9000),
                    ),
            )
        mutableHttpEndpoints.clear()

        assertTrue(
            policy.allowsHttp(
                WasiHttpNetworkProtocol.Https,
                "api.example.test",
                443,
            )
        )
        assertTrue(
            policy.allowsHttp(
                WasiHttpNetworkProtocol.Https,
                "API.EXAMPLE.TEST.",
                443,
            )
        )
        assertTrue(policy.allowsRawSocket("[2001:db8::1]", 8443))
        assertTrue(policy.allowsRawSocket("127.0.0.1", 9000))
        assertEquals(
            false,
            policy.allowsHttp(
                WasiHttpNetworkProtocol.Http,
                "api.example.test",
                443,
            ),
        )
        assertEquals(
            false,
            policy.allowsHttp(
                WasiHttpNetworkProtocol.Https,
                "api.example.test",
                80,
            ),
        )
        assertEquals(
            false,
            policy.allowsHttp(
                WasiHttpNetworkProtocol.Https,
                "api.example.test.evil",
                443,
            ),
        )
        assertEquals(false, policy.allowsRawSocket("127.0.0.1", 9001))

        @Suppress("UNCHECKED_CAST")
        val returnedSnapshot =
            policy.rawSocketEndpoints as MutableSet<WasiNetworkEndpoint>
        returnedSnapshot.clear()
        assertTrue(policy.allowsRawSocket("[2001:db8::1]", 8443))
        assertTrue(policy.allowsRawSocket("127.0.0.1", 9000))
    }

    @Test
    fun deniesAllNetworkingByDefaultAndKeepsGrantKindsIndependent() {
        val defaultImports = CapturingHostImports()
        val defaultWasi = WasiPreview3.builder().build()
        defaultWasi.install(defaultImports)
        try {
            assertEquals(
                "access-denied",
                expectErr(
                    defaultImports.call("types", "[static]tcp-socket.create", "ipv4")
                ),
            )
        } finally {
            defaultWasi.close()
        }

        val rawOnlyClient = RecordingHttpClient()
        val rawOnlyImports = CapturingHostImports()
        val rawOnlyWasi =
            WasiPreview3.builder()
                .withHttpClient(rawOnlyClient)
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        rawSocketEndpoints =
                            setOf(WasiNetworkEndpoint("127.0.0.1", 443))
                    )
                )
                .build()
        rawOnlyWasi.install(rawOnlyImports)
        try {
            assertEquals(
                "HTTP-request-denied",
                expectErr(sendHttp(rawOnlyImports, "api.example.test:443")),
            )
            assertEquals(0, rawOnlyClient.requests.size)
            expectOk<Any?>(
                rawOnlyImports.call("types", "[static]tcp-socket.create", "ipv4")
            )
        } finally {
            rawOnlyWasi.close()
        }

        val httpOnlyImports = CapturingHostImports()
        val httpOnlyWasi =
            WasiPreview3.builder()
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
                .build()
        httpOnlyWasi.install(httpOnlyImports)
        try {
            assertEquals(
                "access-denied",
                expectErr(
                    httpOnlyImports.call("types", "[static]udp-socket.create", "ipv4")
                ),
            )
        } finally {
            httpOnlyWasi.close()
        }
    }

    @Test
    fun enforcesExactHttpHostAndPortBeforeCallingClient() {
        val client = RecordingHttpClient()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview3.builder()
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
                .build()
        wasi.install(imports)
        try {
            expectOk<Any?>(sendHttp(imports, "API.EXAMPLE.TEST.:443"))
            assertEquals(1, client.requests.size)
            val sentUrl = Url(client.requests.single().uri)
            assertEquals("api.example.test", sentUrl.host.lowercase().trimEnd('.'))
            assertEquals(443, sentUrl.port)

            assertEquals(
                "HTTP-request-denied",
                expectErr(sendHttp(imports, "api.example.test:444")),
            )
            assertEquals(
                "HTTP-request-denied",
                expectErr(sendHttp(imports, "api.example.test.evil:443")),
            )
            assertEquals(
                "HTTP-request-denied",
                expectErr(
                    sendHttp(
                        imports,
                        "api.example.test:443",
                        scheme = "HTTP",
                    )
                ),
            )
            assertEquals(1, client.requests.size)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun enforcesRawSocketEndpointBeforeCallingRuntime() {
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview3.builder()
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        rawSocketEndpoints =
                            setOf(WasiNetworkEndpoint("127.0.0.1", 443))
                    )
                )
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            val socket =
                expectOk<Any?>(
                    imports.call("types", "[static]tcp-socket.create", "ipv4")
                )
            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "types",
                        "[method]tcp-socket.connect",
                        socket,
                        ipv4SocketAddress(444),
                    )
                ),
            )
            assertEquals(0, runtime.connectCalls)

            expectOk<Any?>(
                imports.call(
                    "types",
                    "[method]tcp-socket.connect",
                    socket,
                    ipv4SocketAddress(443),
                )
            )
            assertEquals(1, runtime.connectCalls)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun hostnameGrantOnlyAuthorizesAddressesReturnedByNameLookup() {
        val runtime = RecordingSocketRuntime()
        val imports = CapturingHostImports()
        val wasi =
            WasiPreview3.builder()
                .withNetworkPolicy(
                    WasiNetworkPolicy(
                        rawSocketEndpoints =
                            setOf(WasiNetworkEndpoint("localhost", 8443))
                    )
                )
                .also { it.socketRuntime = runtime }
                .build()
        wasi.install(imports)
        try {
            assertEquals(
                "access-denied",
                expectErr(
                    imports.call(
                        "ip-name-lookup",
                        "resolve-addresses",
                        "not-granted.invalid",
                    )
                ),
            )

            val addresses =
                expectOk<List<Any?>>(
                    imports.call("ip-name-lookup", "resolve-addresses", "localhost")
                )
            val resolved = addresses.single() as WitValue.Variant
            val socket =
                expectOk<Any?>(
                    imports.call(
                        "types",
                        "[static]tcp-socket.create",
                        resolved.label(),
                    )
                )
            expectOk<Any?>(
                imports.call(
                    "types",
                    "[method]tcp-socket.connect",
                    socket,
                    resolvedSocketAddress(resolved, 8443),
                )
            )
            assertEquals(1, runtime.connectCalls)
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
        val request =
            (imports.call(
                    "types",
                    "[static]request.new",
                    headers,
                    null,
                    0L,
                    null,
                )
                as List<*>)[0]
        expectOk<Any?>(
            imports.call("types", "[method]request.set-scheme", request, scheme)
        )
        expectOk<Any?>(
            imports.call(
                "types",
                "[method]request.set-authority",
                request,
                authority,
            )
        )
        return imports.call("client", "send", request)
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

    @Suppress("OVERRIDE_DEPRECATION")
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
            val id = key(interfaceName, functionName)
            return requireNotNull(handlers[id]) { "missing host import $id" }
                .apply(arguments.asList())
        }

        private fun key(interfaceName: String?, functionName: String?): String =
            "$interfaceName::$functionName"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> expectOk(result: Any?): T =
        when (result) {
            is WitResult.Ok<*, *> -> result.value() as T
            is WitResult.Err<*, *> ->
                throw AssertionError("expected success, got ${result.value()}")
            else -> throw AssertionError("expected WIT result, got $result")
        }

    private fun expectErr(result: Any?): Any? =
        when (result) {
            is WitResult.Ok<*, *> ->
                throw AssertionError("expected error, got ${result.value()}")
            is WitResult.Err<*, *> -> result.value()
            else -> throw AssertionError("expected WIT result, got $result")
        }
}
