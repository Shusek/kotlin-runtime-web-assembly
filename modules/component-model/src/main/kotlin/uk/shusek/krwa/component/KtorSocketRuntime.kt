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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

internal class KtorSocketRuntime :
    WasiSuspendingSocketRuntime,
    WasiSuspendingTcpListenRuntime,
    WasiPreview3TransportResource {
    private val selector = ActorSelectorManager(Dispatchers.IO)
    private val lifecycleLock = WasiPreviewLock()
    private var closed: Boolean = false

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
        ensureOpen()
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
        ensureOpen()
        val server =
            aSocket(selector).tcp().bind(localAddress) {
                this.backlogSize = backlogSize
                reuseAddress = wasiTcpReuseAddress()
            }
        return KtorTcpListener(server)
    }

    override fun bindUdp(
        localAddress: InetSocketAddress,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiUdpEndpoint = runBlocking {
        ensureOpen()
        val socket =
            aSocket(selector).udp().bind(localAddress) {
                this.receiveBufferSize = receiveBufferSize
                this.sendBufferSize = sendBufferSize
            }
        KtorUdpEndpoint(socket)
    }

    override fun close() {
        val shouldClose =
            withWasiPreviewLock(lifecycleLock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
        if (shouldClose) {
            selector.close()
        }
    }

    private fun ensureOpen() {
        withWasiPreviewLock(lifecycleLock) {
            check(!closed) { "WASI Preview 3 socket runtime is closed" }
        }
    }
}

private fun wasiTcpReuseAddress(): Boolean =
    !java.lang.System
        .getProperty("os.name")
        .orEmpty()
        .startsWith("Windows", ignoreCase = true)

private class KtorTcpListener(private val server: ServerSocket) : WasiTcpListener {
    private val acceptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = WasiPreviewLock()
    private var pendingAccept: Deferred<WasiTcpConnection>? = null
    private var closed: Boolean = false

    override val localAddress: InetSocketAddress
        get() = server.localAddress.toKtorInetSocketAddress()

    override fun accept(timeoutMillis: Long): WasiTcpConnection? = runBlocking {
        awaitPendingAccept(timeoutMillis)
    }

    override suspend fun accept(): WasiTcpConnection =
        checkNotNull(awaitPendingAccept(timeoutMillis = null))

    override fun isOpen(): Boolean = !server.socketContext.isCompleted

    override fun close() {
        var shouldClose = false
        val pending =
            withWasiPreviewLock(lifecycleLock) {
                if (!closed) {
                    closed = true
                    shouldClose = true
                }
                pendingAccept.also { pendingAccept = null }
            }
        if (!shouldClose) {
            return
        }
        var failure: Throwable? = null
        try {
            server.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        acceptScope.cancel()
        val accepted =
            pending?.let { deferred ->
                runBlocking {
                    try {
                        deferred.await()
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        if (accepted != null) {
            try {
                accepted.close()
            } catch (closeFailure: Throwable) {
                val previous = failure
                if (previous == null) {
                    failure = closeFailure
                } else {
                    previous.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun awaitPendingAccept(timeoutMillis: Long?): WasiTcpConnection? {
        while (true) {
            val pending =
                withWasiPreviewLock(lifecycleLock) {
                    check(!closed) { "WASI TCP listener is closed" }
                    pendingAccept
                        ?: acceptScope
                            .async { KtorTcpConnection(server.accept()) }
                            .also { pendingAccept = it }
                }
            val accepted =
                try {
                    if (timeoutMillis == null) {
                        pending.await()
                    } else {
                        withTimeoutOrNull(timeoutMillis) { pending.await() }
                    }
                } catch (failure: Throwable) {
                    if (pending.isCancelled) {
                        withWasiPreviewLock(lifecycleLock) {
                            if (pendingAccept === pending) {
                                pendingAccept = null
                            }
                        }
                    }
                    throw failure
                }
            if (accepted == null) {
                return null
            }
            val claimed =
                withWasiPreviewLock(lifecycleLock) {
                    if (pendingAccept === pending) {
                        pendingAccept = null
                        true
                    } else {
                        false
                    }
                }
            if (claimed) {
                return accepted
            }
        }
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
