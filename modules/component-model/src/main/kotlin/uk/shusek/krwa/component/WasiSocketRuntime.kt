package uk.shusek.krwa.component

import io.ktor.network.sockets.InetSocketAddress
import kotlinx.io.RawSink
import kotlinx.io.RawSource

internal interface WasiSocketRuntime {
    fun connectTcp(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection

    fun listenTcp(localAddress: InetSocketAddress, backlogSize: Int): WasiTcpListener

    fun bindUdp(
        localAddress: InetSocketAddress,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiUdpEndpoint
}

internal interface WasiSuspendingSocketRuntime : WasiSocketRuntime {
    suspend fun connectTcpSuspending(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection
}

internal interface WasiSuspendingTcpListenRuntime : WasiSocketRuntime {
    suspend fun listenTcpSuspending(
        localAddress: InetSocketAddress,
        backlogSize: Int,
    ): WasiTcpListener
}

internal expect fun defaultWasiSocketRuntime(): WasiSocketRuntime

internal expect fun webSocketUdpProxySocketRuntime(proxyUrl: String): WasiSocketRuntime

internal interface WasiTcpListener {
    val localAddress: InetSocketAddress

    fun accept(timeoutMillis: Long): WasiTcpConnection?

    suspend fun accept(): WasiTcpConnection

    fun isOpen(): Boolean

    fun close()
}

internal interface WasiTcpConnection {
    val localAddress: InetSocketAddress
    val remoteAddress: InetSocketAddress

    fun isOpen(): Boolean

    fun send(data: ByteArray)

    fun read(max: Int, timeoutMillis: Long): WasiTcpReadChunk

    suspend fun awaitReadable(): Boolean

    fun readUntilIdle(firstByteTimeoutMillis: Long, idleTimeoutMillis: Long): ByteArray

    fun inputSource(): RawSource

    fun inputAvailable(): Int

    fun outputSink(): RawSink

    fun shutdownInput()

    fun shutdownOutput()

    fun close()
}

internal interface WasiSuspendingTcpConnection : WasiTcpConnection {
    suspend fun sendSuspending(data: ByteArray)

    suspend fun readSuspending(max: Int): WasiTcpReadChunk

    suspend fun readUntilIdleSuspending(
        firstByteTimeoutMillis: Long,
        idleTimeoutMillis: Long,
    ): ByteArray
}

internal data class WasiTcpReadChunk(val bytes: ByteArray, val closed: Boolean)

internal interface WasiUdpEndpoint {
    val localAddress: InetSocketAddress

    fun isOpen(): Boolean

    fun send(data: ByteArray, remoteAddress: InetSocketAddress)

    fun receive(timeoutMillis: Long): WasiDatagram?

    fun close()
}

internal interface WasiSuspendingUdpEndpoint : WasiUdpEndpoint {
    suspend fun sendSuspending(data: ByteArray, remoteAddress: InetSocketAddress)

    suspend fun receiveSuspending(timeoutMillis: Long): WasiDatagram?
}

internal data class WasiDatagram(val data: ByteArray, val remoteAddress: InetSocketAddress)
