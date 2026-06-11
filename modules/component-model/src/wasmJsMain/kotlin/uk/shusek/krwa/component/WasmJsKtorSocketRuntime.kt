package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.network.selector.SelectorManager
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

private const val WS_UDP_VERSION: Int = 1
private const val WS_UDP_BIND: Int = 0
private const val WS_UDP_SEND: Int = 1
private const val WS_UDP_RECEIVE: Int = 2
private const val WS_UDP_BOUND: Int = 3
private const val WS_UDP_ERROR: Int = 4
private const val WS_UDP_BIND_TIMEOUT_MILLIS: Long = 5_000L

internal class WasmJsKtorSocketRuntime(
    private val udpProxyUrl: String? = null,
) : WasiSuspendingSocketRuntime, WasiSuspendingTcpListenRuntime {
    private val selector = SelectorManager()

    override fun connectTcp(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection =
        throw UnsupportedOperationException("Synchronous WASI TCP connect is not available on web/wasm")

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
        return WasmJsKtorTcpConnection(socket)
    }

    override fun listenTcp(localAddress: InetSocketAddress, backlogSize: Int): WasiTcpListener =
        throw UnsupportedOperationException("WASI TCP listen is not available on web/wasm")

    override suspend fun listenTcpSuspending(
        localAddress: InetSocketAddress,
        backlogSize: Int,
    ): WasiTcpListener {
        val server =
            aSocket(selector).tcp().bind(localAddress) { this.backlogSize = backlogSize }
        return WasmJsKtorTcpListener(server)
    }

    override fun bindUdp(
        localAddress: InetSocketAddress,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiUdpEndpoint {
        val proxyUrl =
            udpProxyUrl
                ?: throw UnsupportedOperationException(
                    "Browser WASI UDP requires a WebSocket UDP proxy; configure " +
                        "WasiPreview3.Builder.withUdpWebSocketProxy(...)"
                )
        return WasmJsWebSocketUdpEndpoint(
            proxyUrl,
            localAddress,
            receiveBufferSize,
            sendBufferSize,
        )
    }
}

private class WasmJsWebSocketUdpEndpoint(
    private val proxyUrl: String,
    initialLocalAddress: InetSocketAddress,
    private val receiveBufferSize: Int,
    private val sendBufferSize: Int,
) : WasiSuspendingUdpEndpoint {
    private val client =
        KtorHttpClient(Js) {
            install(WebSockets)
        }
    private val sessionLock = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var closed: Boolean = false
    private var currentLocalAddress: InetSocketAddress = initialLocalAddress

    override val localAddress: InetSocketAddress
        get() = currentLocalAddress

    override fun isOpen(): Boolean = !closed && (session?.isActive ?: true)

    override fun send(data: ByteArray, remoteAddress: InetSocketAddress) {
        throw UnsupportedOperationException("Synchronous WASI UDP send is not available on web/wasm")
    }

    override suspend fun sendSuspending(data: ByteArray, remoteAddress: InetSocketAddress) {
        openSession().send(encodeWsUdpAddressFrame(WS_UDP_SEND, remoteAddress, data))
    }

    override fun receive(timeoutMillis: Long): WasiDatagram? {
        throw UnsupportedOperationException("Synchronous WASI UDP receive is not available on web/wasm")
    }

    override suspend fun receiveSuspending(timeoutMillis: Long): WasiDatagram? {
        val active = openSession()
        return withTimeoutOrNull(timeoutMillis) {
            var result: WasiDatagram? = null
            while (result == null) {
                val frame = active.incoming.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                val parsed = parseWsUdpFrame(frame)
                when (parsed.type) {
                    WS_UDP_RECEIVE -> {
                        val remote =
                            parsed.address
                                ?: throw IllegalStateException("UDP proxy datagram is missing a remote address")
                        result = WasiDatagram(parsed.payload, remote)
                    }
                    WS_UDP_BOUND -> {
                        currentLocalAddress =
                            parsed.address
                                ?: throw IllegalStateException("UDP proxy bind acknowledgement is missing an address")
                    }
                    WS_UDP_ERROR -> throw IllegalStateException(parsed.payload.decodeToString())
                    else -> throw IllegalStateException("Unsupported UDP proxy frame type ${parsed.type}")
                }
            }
            result
        }
    }

    override fun close() {
        closed = true
        val active = session
        session = null
        active?.cancel()
        client.close()
    }

    private suspend fun openSession(): DefaultClientWebSocketSession {
        val active = session
        if (active != null && active.isActive) {
            return active
        }
        return sessionLock.withLock {
            val lockedActive = session
            if (lockedActive != null && lockedActive.isActive) {
                return@withLock lockedActive
            }
            if (closed) {
                throw IllegalStateException("UDP proxy endpoint is closed")
            }
            val opened = client.webSocketSession(proxyUrl)
            opened.send(
                encodeWsUdpBindFrame(
                    currentLocalAddress,
                    receiveBufferSize,
                    sendBufferSize,
                )
            )
            val bound =
                withTimeoutOrNull(WS_UDP_BIND_TIMEOUT_MILLIS) {
                    awaitWsUdpBound(opened)
                } ?: throw IllegalStateException("UDP proxy did not acknowledge bind")
            currentLocalAddress = bound
            session = opened
            opened
        }
    }

    private suspend fun awaitWsUdpBound(
        active: DefaultClientWebSocketSession
    ): InetSocketAddress {
        while (true) {
            val frame =
                active.incoming.receiveCatching().getOrNull()
                    ?: throw IllegalStateException("UDP proxy closed before bind acknowledgement")
            val parsed = parseWsUdpFrame(frame)
            when (parsed.type) {
                WS_UDP_BOUND ->
                    return parsed.address
                        ?: throw IllegalStateException("UDP proxy bind acknowledgement is missing an address")
                WS_UDP_ERROR -> throw IllegalStateException(parsed.payload.decodeToString())
                else -> throw IllegalStateException("Unexpected UDP proxy frame type ${parsed.type} during bind")
            }
        }
    }
}

private data class WsUdpFrame(
    val type: Int,
    val address: InetSocketAddress?,
    val payload: ByteArray,
)

private fun encodeWsUdpBindFrame(
    address: InetSocketAddress,
    receiveBufferSize: Int,
    sendBufferSize: Int,
): ByteArray {
    val base = encodeWsUdpAddressFrame(WS_UDP_BIND, address, ByteArray(8))
    val offset = base.size - 8
    writeInt(base, offset, receiveBufferSize)
    writeInt(base, offset + 4, sendBufferSize)
    return base
}

private fun encodeWsUdpAddressFrame(
    type: Int,
    address: InetSocketAddress,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val host = address.hostname.encodeToByteArray()
    require(host.size <= 65_535) { "UDP proxy hostname is too long" }
    require(address.port in 0..65_535) { "UDP proxy port is out of range: ${address.port}" }
    val frame = ByteArray(2 + 2 + host.size + 2 + payload.size)
    frame[0] = WS_UDP_VERSION.toByte()
    frame[1] = type.toByte()
    writeShort(frame, 2, host.size)
    host.copyInto(frame, 4)
    val portOffset = 4 + host.size
    writeShort(frame, portOffset, address.port)
    payload.copyInto(frame, portOffset + 2)
    return frame
}

private fun parseWsUdpFrame(frame: Frame): WsUdpFrame {
    if (frame !is Frame.Binary) {
        throw IllegalStateException("UDP proxy sent a non-binary WebSocket frame")
    }
    val data = frame.readBytes()
    if (data.size < 2) {
        throw IllegalStateException("UDP proxy frame is too short")
    }
    val version = data[0].toInt() and 0xff
    if (version != WS_UDP_VERSION) {
        throw IllegalStateException("Unsupported UDP proxy frame version $version")
    }
    val type = data[1].toInt() and 0xff
    if (type == WS_UDP_ERROR) {
        return WsUdpFrame(type, null, data.copyOfRange(2, data.size))
    }
    val (address, payloadOffset) = readWsUdpAddress(data, 2)
    return WsUdpFrame(type, address, data.copyOfRange(payloadOffset, data.size))
}

private fun readWsUdpAddress(data: ByteArray, offset: Int): Pair<InetSocketAddress, Int> {
    if (data.size < offset + 2) {
        throw IllegalStateException("UDP proxy address is missing a host length")
    }
    val hostLength = readShort(data, offset)
    val hostOffset = offset + 2
    val portOffset = hostOffset + hostLength
    if (data.size < portOffset + 2) {
        throw IllegalStateException("UDP proxy address is truncated")
    }
    val host = data.copyOfRange(hostOffset, portOffset).decodeToString()
    val port = readShort(data, portOffset)
    return InetSocketAddress(host, port) to (portOffset + 2)
}

private fun writeShort(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xff).toByte()
    target[offset + 1] = (value and 0xff).toByte()
}

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 24) and 0xff).toByte()
    target[offset + 1] = ((value ushr 16) and 0xff).toByte()
    target[offset + 2] = ((value ushr 8) and 0xff).toByte()
    target[offset + 3] = (value and 0xff).toByte()
}

private fun readShort(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

private class WasmJsKtorTcpListener(private val server: ServerSocket) : WasiTcpListener {
    override val localAddress: InetSocketAddress
        get() = server.localAddress.toKtorInetSocketAddress()

    override fun accept(timeoutMillis: Long): WasiTcpConnection? =
        throw UnsupportedOperationException("Synchronous WASI TCP accept is not available on web/wasm")

    override suspend fun accept(): WasiTcpConnection = WasmJsKtorTcpConnection(server.accept())

    override fun isOpen(): Boolean = !server.socketContext.isCompleted

    override fun close() {
        server.close()
    }
}

private class WasmJsKtorTcpConnection(private val socket: Socket) : WasiSuspendingTcpConnection {
    private val input: ByteReadChannel = socket.openReadChannel()
    private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)

    override val localAddress: InetSocketAddress
        get() = socket.localAddress.toKtorInetSocketAddress()

    override val remoteAddress: InetSocketAddress
        get() = socket.remoteAddress.toKtorInetSocketAddress()

    override fun isOpen(): Boolean = !socket.socketContext.isCompleted

    override fun send(data: ByteArray) {
        throw UnsupportedOperationException("Synchronous WASI TCP send is not available on web/wasm")
    }

    override suspend fun sendSuspending(data: ByteArray) {
        output.writeFully(data)
        output.flush()
    }

    override fun read(max: Int, timeoutMillis: Long): WasiTcpReadChunk =
        throw UnsupportedOperationException("Synchronous WASI TCP read is not available on web/wasm")

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
        throw UnsupportedOperationException("Synchronous WASI TCP read is not available on web/wasm")

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

    override fun inputSource(): RawSource =
        throw UnsupportedOperationException("Synchronous WASI TCP source is not available on web/wasm")

    override fun inputAvailable(): Int = input.availableForRead

    override fun outputSink(): RawSink =
        throw UnsupportedOperationException("Synchronous WASI TCP sink is not available on web/wasm")

    override fun shutdownInput() {
        input.cancel()
    }

    override fun shutdownOutput() {
        socket.close()
    }

    override fun close() {
        socket.close()
    }
}

private fun SocketAddress.toKtorInetSocketAddress(): InetSocketAddress =
    this as? InetSocketAddress ?: throw IllegalStateException("unsupported socket address")
