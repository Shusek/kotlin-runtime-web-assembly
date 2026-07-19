package uk.shusek.krwa.component

import io.ktor.network.sockets.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class WasiPreview3TransportLifecycleTest {
    @Test
    fun builderDefersOwnedTransportCreationAndHostClosesItExactlyOnce() {
        var httpCreates = 0
        var socketCreates = 0
        lateinit var httpClient: RecordingHttpClient
        lateinit var socketRuntime: RecordingSocketRuntime
        val builder = WasiPreview3.builder()
        builder.defaultHttpClientFactory = {
            httpCreates++
            RecordingHttpClient().also { httpClient = it }
        }
        builder.socketRuntimeFactory = {
            socketCreates++
            RecordingSocketRuntime().also { socketRuntime = it }
        }

        assertEquals(0, httpCreates)
        assertEquals(0, socketCreates)

        val host = builder.build()
        assertEquals(1, httpCreates)
        assertEquals(1, socketCreates)

        host.close()
        host.close()

        assertEquals(1, httpClient.closeCount)
        assertEquals(1, socketRuntime.closeCount)
    }

    @Test
    fun injectedTransportsRemainBorrowedEvenWhenTheyAreCloseable() {
        val httpClient = RecordingHttpClient()
        val socketRuntime = RecordingSocketRuntime()
        val builder =
            WasiPreview3.builder()
                .withHttpClient(httpClient)
                .also { configured ->
                    configured.socketRuntime = socketRuntime
                    configured.defaultHttpClientFactory = {
                        error("default HTTP transport must not be created")
                    }
                    configured.socketRuntimeFactory = {
                        error("default socket transport must not be created")
                    }
                }

        val host = builder.build()
        host.close()
        host.close()

        assertEquals(0, httpClient.closeCount)
        assertEquals(0, socketRuntime.closeCount)
    }

    @Test
    fun buildFailureClosesTransportCreatedEarlierInTheBuild() {
        val httpClient = RecordingHttpClient()
        val builder = WasiPreview3.builder()
        builder.defaultHttpClientFactory = { httpClient }
        builder.socketRuntimeFactory = { error("socket factory failed") }

        assertThrows(IllegalStateException::class.java) {
            builder.build()
        }

        assertEquals(1, httpClient.closeCount)
    }

    @Test
    fun constructorFailureClosesEveryTransportCreatedByTheBuild() {
        val httpClient = RecordingHttpClient()
        val socketRuntime = RecordingSocketRuntime()
        val builder = WasiPreview3.builder()
        builder.defaultHttpClientFactory = { httpClient }
        builder.socketRuntimeFactory = { socketRuntime }
        builder.monotonicClock = { error("host construction failed") }

        assertThrows(IllegalStateException::class.java) {
            builder.build()
        }

        assertEquals(1, httpClient.closeCount)
        assertEquals(1, socketRuntime.closeCount)
    }

    @Test
    fun closeAttemptsEveryOwnedTransportAndRemainsIdempotentAfterFailure() {
        val httpClient = RecordingHttpClient()
        val socketRuntime =
            RecordingSocketRuntime(closeFailure = IllegalStateException("close failed"))
        val builder = WasiPreview3.builder()
        builder.defaultHttpClientFactory = { httpClient }
        builder.socketRuntimeFactory = { socketRuntime }
        val host = builder.build()

        assertThrows(IllegalStateException::class.java) {
            host.close()
        }
        host.close()

        assertEquals(1, socketRuntime.closeCount)
        assertEquals(1, httpClient.closeCount)
    }

    @Test
    fun closeCancelsHostChildWithoutCancellingBorrowedScope() {
        val parentJob = SupervisorJob()
        val borrowedScope = CoroutineScope(parentJob + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val host = WasiPreview3.builder().withCoroutineScope(borrowedScope).build()

        host.byteStream(
            flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        )
        runBlocking { withTimeout(2_000L) { started.await() } }

        host.close()

        runBlocking { withTimeout(2_000L) { cancelled.await() } }
        assertTrue(parentJob.isActive, "closing a host must not cancel its borrowed parent scope")
        parentJob.cancel()
    }

    @Test
    fun autoCloseableUseClosesHost() {
        val host = WasiPreview3.builder().build()

        host.use { it.pendingFuture<Any?>() }

        assertThrows(ComponentModelException::class.java) {
            host.pendingFuture<Any?>()
        }
    }

    private class RecordingHttpClient :
        WasiHttpClient,
        WasiPreview3TransportResource {
        var closeCount: Int = 0
            private set

        override fun send(request: WasiHttpRequest): WasiHttpResponse =
            WasiHttpResponse(200, emptyMap(), ByteArray(0))

        override fun close() {
            closeCount++
        }
    }

    private class RecordingSocketRuntime(
        private val closeFailure: Throwable? = null,
    ) :
        WasiSocketRuntime,
        WasiPreview3TransportResource {
        var closeCount: Int = 0
            private set

        override fun connectTcp(
            remoteAddress: InetSocketAddress,
            keepAlive: Boolean,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiTcpConnection = error("not used")

        override fun listenTcp(
            localAddress: InetSocketAddress,
            backlogSize: Int,
        ): WasiTcpListener = error("not used")

        override fun bindUdp(
            localAddress: InetSocketAddress,
            receiveBufferSize: Int,
            sendBufferSize: Int,
        ): WasiUdpEndpoint = error("not used")

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }
}
