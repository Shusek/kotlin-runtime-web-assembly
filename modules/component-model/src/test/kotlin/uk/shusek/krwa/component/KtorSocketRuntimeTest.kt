package uk.shusek.krwa.component

import io.ktor.network.sockets.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KtorSocketRuntimeTest {
    @Test
    fun timedAcceptPollingReusesPendingSelectorRegistration() {
        val runtime = KtorSocketRuntime()
        val listener = runtime.listenTcp(InetSocketAddress("127.0.0.1", 0), backlogSize = 1)
        try {
            assertNull(listener.accept(25L))
            assertNull(listener.accept(25L))

            Socket(InetAddress.getLoopbackAddress(), listener.localAddress.port).use {
                val accepted = listener.accept(2_000L)
                assertNotNull(accepted)
                accepted!!.close()
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun shutdownOutputFlushesPendingBytesAndKeepsInputOpen() {
        assertShutdownOutputFlushesPendingBytesAndKeepsInputOpen("127.0.0.1")
    }

    @Test
    fun shutdownOutputFlushesPendingBytesAndKeepsInputOpenIpv6() {
        assertShutdownOutputFlushesPendingBytesAndKeepsInputOpen("::1")
    }

    private fun assertShutdownOutputFlushesPendingBytesAndKeepsInputOpen(host: String) {
        val runtime = KtorSocketRuntime()
        val listener = runtime.listenTcp(InetSocketAddress(host, 0), backlogSize = 1)
        val client =
            runtime.connectTcp(
                listener.localAddress,
                keepAlive = false,
                receiveBufferSize = 65_536,
                sendBufferSize = 65_536,
            )
        val server = listener.accept(2_000L)
        assertNotNull(server)
        try {
            val outbound = ByteArray(10)
            server!!.send(outbound)
            server.shutdownOutput()

            assertArrayEquals(outbound, client.read(outbound.size, 2_000L).bytes)

            val inbound = byteArrayOf(1, 2, 3, 4)
            client.send(inbound)
            assertArrayEquals(inbound, server.read(inbound.size, 2_000L).bytes)
        } finally {
            server?.close()
            client.close()
            listener.close()
        }
    }
}
