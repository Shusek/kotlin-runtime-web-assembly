package uk.shusek.krwa.component

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

internal class KtorSocketRuntime : WasiSuspendingSocketRuntime, WasiSuspendingTcpListenRuntime {
    private val selector = ActorSelectorManager(Dispatchers.IO)

    override fun connectTcp(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection =
        runBlocking {
            connectTcpSuspending(remoteAddress, keepAlive, receiveBufferSize, sendBufferSize)
        }

    override suspend fun connectTcpSuspending(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection {
        val socket =
            aSocket(selector).tcp().connect(remoteAddress) {
                this.keepAlive = keepAlive
                this.receiveBufferSize = receiveBufferSize
                this.sendBufferSize = sendBufferSize
            }
        return KtorTcpConnection(socket)
    }

    override fun listenTcp(localAddress: InetSocketAddress, backlogSize: Int): WasiTcpListener =
        runBlocking {
            listenTcpSuspending(localAddress, backlogSize)
        }

    override suspend fun listenTcpSuspending(
        localAddress: InetSocketAddress,
        backlogSize: Int,
    ): WasiTcpListener {
        val server =
            aSocket(selector).tcp().bind(localAddress) { this.backlogSize = backlogSize }
        return KtorTcpListener(server)
    }

    override fun bindUdp(
        localAddress: InetSocketAddress,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiUdpEndpoint = runBlocking {
        val socket =
            aSocket(selector).udp().bind(localAddress) {
                this.receiveBufferSize = receiveBufferSize
                this.sendBufferSize = sendBufferSize
            }
        KtorUdpEndpoint(socket)
    }
}

private class KtorTcpListener(private val server: ServerSocket) : WasiTcpListener {
    override val localAddress: InetSocketAddress
        get() = server.localAddress.toKtorInetSocketAddress()

    override fun accept(timeoutMillis: Long): WasiTcpConnection? = runBlocking {
        withTimeoutOrNull(timeoutMillis) { accept() }
    }

    override suspend fun accept(): WasiTcpConnection = KtorTcpConnection(server.accept())

    override fun isOpen(): Boolean = !server.socketContext.isCompleted

    override fun close() {
        server.close()
    }
}

private class KtorTcpConnection(private val socket: Socket) : WasiSuspendingTcpConnection {
    private val input: ByteReadChannel = socket.openReadChannel()
    private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)

    override val localAddress: InetSocketAddress
        get() = socket.localAddress.toKtorInetSocketAddress()

    override val remoteAddress: InetSocketAddress
        get() = socket.remoteAddress.toKtorInetSocketAddress()

    override fun isOpen(): Boolean = !socket.socketContext.isCompleted

    override fun send(data: ByteArray) {
        runBlocking {
            sendSuspending(data)
        }
    }

    override suspend fun sendSuspending(data: ByteArray) {
        output.writeFully(data)
        output.flush()
    }

    override fun read(max: Int, timeoutMillis: Long): WasiTcpReadChunk = runBlocking {
        withTimeoutOrNull(timeoutMillis) { readSuspending(max) }
            ?: WasiTcpReadChunk(ByteArray(0), closed = false)
    }

    override suspend fun readSuspending(max: Int): WasiTcpReadChunk {
        if (max <= 0) {
            return WasiTcpReadChunk(ByteArray(0), closed = false)
        }
        val buffer = ByteArray(max)
        val count = input.readAvailable(buffer, 0, max)
        return when {
            count < 0 -> WasiTcpReadChunk(ByteArray(0), closed = true)
            count == 0 -> WasiTcpReadChunk(ByteArray(0), closed = false)
            else -> WasiTcpReadChunk(buffer.copyOf(count), closed = false)
        }
    }

    override suspend fun awaitReadable(): Boolean = input.awaitContent(1)

    override fun readUntilIdle(firstByteTimeoutMillis: Long, idleTimeoutMillis: Long): ByteArray =
        runBlocking {
            readUntilIdleSuspending(firstByteTimeoutMillis, idleTimeoutMillis)
        }

    override suspend fun readUntilIdleSuspending(
        firstByteTimeoutMillis: Long,
        idleTimeoutMillis: Long,
    ): ByteArray {
        val out = Buffer()
        val buffer = ByteArray(8192)
        while (true) {
            val timeout = if (out.size == 0L) firstByteTimeoutMillis else idleTimeoutMillis
            val count =
                withTimeoutOrNull(timeout) { input.readAvailable(buffer, 0, buffer.size) } ?: 0
            if (count < 0 || count == 0) {
                return out.readByteArray()
            }
            out.write(buffer, 0, count)
        }
    }

    override fun inputSource(): RawSource = KtorTcpSource(input)

    override fun inputAvailable(): Int = input.availableForRead

    override fun outputSink(): RawSink = KtorTcpSink(output)

    override fun shutdownInput() {
        input.cancel()
    }

    override fun shutdownOutput() {
        runBlocking { output.flushAndClose() }
    }

    override fun close() {
        socket.close()
    }
}

private class KtorUdpEndpoint(
    private val socket: BoundDatagramSocket,
) : WasiSuspendingUdpEndpoint {
    override val localAddress: InetSocketAddress
        get() = socket.localAddress.toKtorInetSocketAddress()

    override fun isOpen(): Boolean = !socket.socketContext.isCompleted

    override fun send(data: ByteArray, remoteAddress: InetSocketAddress) {
        runBlocking {
            sendSuspending(data, remoteAddress)
        }
    }

    override suspend fun sendSuspending(data: ByteArray, remoteAddress: InetSocketAddress) {
        socket.send(Datagram(io.ktor.utils.io.core.ByteReadPacket(data), remoteAddress))
    }

    override fun receive(timeoutMillis: Long): WasiDatagram? = runBlocking {
        receiveSuspending(timeoutMillis)
    }

    override suspend fun receiveSuspending(timeoutMillis: Long): WasiDatagram? {
        val datagram = withTimeoutOrNull(timeoutMillis) { socket.receive() } ?: return null
        return WasiDatagram(
            datagram.packet.readByteArray(),
            datagram.address.toKtorInetSocketAddress(),
        )
    }

    override fun close() {
        socket.close()
    }
}

private class KtorTcpSource(private val channel: ByteReadChannel) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) {
            return 0L
        }
        val length = byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArray(length)
        val count = runBlocking { channel.readAvailable(buffer, 0, length) }
        if (count <= 0) {
            return count.toLong()
        }
        sink.write(buffer, 0, count)
        return count.toLong()
    }

    override fun close() {
        channel.cancel()
    }
}

private class KtorTcpSink(private val channel: ByteWriteChannel) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        if (byteCount == 0L) {
            return
        }
        val data = source.readByteArray(byteCount.toInt())
        runBlocking {
            channel.writeFully(data)
            channel.flush()
        }
    }

    override fun flush() {
        runBlocking { channel.flush() }
    }

    override fun close() {
        runBlocking { channel.flushAndClose() }
    }
}

private fun SocketAddress.toKtorInetSocketAddress(): InetSocketAddress =
    this as? InetSocketAddress ?: throw IllegalStateException("unsupported socket address")
