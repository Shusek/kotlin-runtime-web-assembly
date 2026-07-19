@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlin.random.Random
import kotlin.time.Clock as KotlinClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant as KotlinInstant
import kotlin.time.TimeSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.yield
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.readByteArray
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink as OkioSink
import okio.Timeout
import okio.buffer
import org.kotlincrypto.random.CryptoRand
import uk.shusek.krwa.runtime.canonicalizeExactNetworkHost

private const val CLI_PACKAGE: String = "wasi:cli"
private const val CLOCKS_PACKAGE: String = "wasi:clocks"
private const val FILESYSTEM_PACKAGE: String = "wasi:filesystem"
private const val HTTP_PACKAGE: String = "wasi:http"
private const val RANDOM_PACKAGE: String = "wasi:random"
private const val SOCKETS_PACKAGE: String = "wasi:sockets"
private const val STREAM_BLOCKED: Long = 0xffff_ffffL
private const val STREAM_COMPLETED: Int = 0
private const val STREAM_DROPPED: Int = 1
private const val STREAM_CANCELLED: Int = 2
private const val STREAM_MAX_LENGTH: Int = (1 shl 28) - 1
private const val DEFAULT_STREAM_BUFFER_CAPACITY: Int = 64 * 1024
private const val DIRECT_FILESYSTEM_WRITE_CHUNK_SIZE: Int = 8 * 1024
private const val U32_MASK: Long = 0xffff_ffffL
private const val TCP_EPHEMERAL_PORT_START: Int = 49_152
private const val TCP_EPHEMERAL_PORT_END: Int = 65_535

private data class WasiPreview3TcpBindKey(
    val address: List<Int>,
    val port: Int,
)

private object WasiPreview3TcpBindRegistry {
    private val lock = WasiPreviewLock()
    private val reserved: MutableSet<WasiPreview3TcpBindKey> = LinkedHashSet()
    private var nextEphemeralPort: Int =
        Random.Default.nextInt(TCP_EPHEMERAL_PORT_START, TCP_EPHEMERAL_PORT_END + 1)

    fun reserveExact(key: WasiPreview3TcpBindKey): Boolean =
        withWasiPreviewLock(lock) {
            if (reserved.any { conflicts(it, key) }) {
                false
            } else {
                reserved.add(key)
                true
            }
        }

    fun reserveEphemeral(address: List<Int>): WasiPreview3TcpBindKey? =
        withWasiPreviewLock(lock) {
            val portCount = TCP_EPHEMERAL_PORT_END - TCP_EPHEMERAL_PORT_START + 1
            repeat(portCount) {
                val port = nextEphemeralPort
                nextEphemeralPort =
                    if (port == TCP_EPHEMERAL_PORT_END) TCP_EPHEMERAL_PORT_START else port + 1
                val candidate = WasiPreview3TcpBindKey(address, port)
                if (reserved.none { conflicts(it, candidate) }) {
                    reserved.add(candidate)
                    return@withWasiPreviewLock candidate
                }
            }
            null
        }

    fun release(key: WasiPreview3TcpBindKey?) {
        if (key != null) {
            withWasiPreviewLock(lock) { reserved.remove(key) }
        }
    }

    private fun conflicts(
        first: WasiPreview3TcpBindKey,
        second: WasiPreview3TcpBindKey,
    ): Boolean {
        if (first.port != second.port || first.address.size != second.address.size) {
            return false
        }
        return first.address == second.address ||
            first.address.all { it == 0 } ||
            second.address.all { it == 0 }
    }
}

private fun defaultPreview3MonotonicClock(): () -> Long {
    val mark = TimeSource.Monotonic.markNow()
    return { mark.elapsedNow().inWholeNanoseconds }
}

private fun defaultPreview3CoroutineScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun <T : Any> requirePresent(value: T?, name: String): T = requireNotNull(value) { name }

private object DefaultWasiPreview3HttpClient : WasiHttpClient {
    override fun send(request: WasiHttpRequest): WasiHttpResponse =
        error("The default WASI Preview 3 HTTP client is created by Builder.build()")
}

private class WasiPreview3Transports(
    val httpClient: WasiHttpClient,
    val socketRuntime: WasiSocketRuntime,
    ownedHttpClient: WasiPreview3TransportResource?,
    ownedSocketRuntime: WasiPreview3TransportResource?,
) : WasiPreview3TransportResource {
    private val lifecycleLock = WasiPreviewLock()
    private val ownedResources: List<WasiPreview3TransportResource> =
        listOfNotNull(ownedSocketRuntime, ownedHttpClient)
    private var closed: Boolean = false

    override fun close() {
        val resources =
            withWasiPreviewLock(lifecycleLock) {
                if (closed) {
                    emptyList()
                } else {
                    closed = true
                    ownedResources
                }
            }
        var failure: Throwable? = null
        for (resource in resources) {
            try {
                resource.close()
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
}

private fun closeWasiPreview3TransportAfterFailure(
    failure: Throwable,
    resource: WasiPreview3TransportResource,
) {
    try {
        resource.close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
}

private fun stringValue(value: Any?): String = value.toString()

private fun hashValues(vararg values: Any?): Int = values.contentHashCode()

private inline fun <R> okio.FileHandle.useHandle(block: (okio.FileHandle) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private inline fun <R> okio.BufferedSink.useSink(block: (okio.BufferedSink) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private fun isClosedChannel(e: Throwable): Boolean = e::class.simpleName == "ClosedChannelException"

private fun exceptionSimpleName(value: Any?): String = value?.let { it::class.simpleName } ?: "null"

private fun exceptionClassName(value: Any?): String =
    value?.let { it::class.qualifiedName ?: exceptionSimpleName(it) } ?: "null"

private fun compareUnsigned(a: Long, b: Long): Int =
    (a xor Long.MIN_VALUE).compareTo(b xor Long.MIN_VALUE)

private fun unsignedByte(value: Byte): Int = value.toInt() and 0xff

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1L else quotient
}

private fun floorMod(value: Long, divisor: Long): Long = value - floorDiv(value, divisor) * divisor

private fun latin1String(bytes: ByteArray): String =
    buildString(bytes.size) {
        for (byte in bytes) {
            append(unsignedByte(byte).toChar())
        }
    }

private fun latin1Bytes(value: String): ByteArray =
    ByteArray(value.length) { index ->
        val code = value[index].code
        (if (code <= 0xff) code else '?'.code).toByte()
    }

fun interface WasiHttpHandler {
    fun handle(request: WasiPreview2.HttpRequestSnapshot): WasiPreview2.HttpResponseSnapshot
}

private fun defaultWasiHttpHandler(): WasiHttpHandler =
    WasiHttpHandler {
        WasiPreview2.HttpResponseSnapshot(501, emptyMap(), ByteArray(0), true)
    }

/**
 * One exact network destination. Host wildcards, URL-shaped values, and ambiguous IP literals are
 * rejected.
 *
 * Port `0` is accepted for raw-socket local binds requesting an ephemeral port. HTTP grants must
 * use an explicit port from `1` through `65535`.
 */
data class WasiNetworkEndpoint(
    val host: String,
    val port: Int,
) {
    internal val normalizedHost: String = normalizeNetworkPolicyHost(host)

    init {
        require(port in 0..0xffff) { "network endpoint port must be between 0 and 65535" }
    }
}

enum class WasiHttpNetworkProtocol {
    Http,
    Https,
}

/**
 * One exact HTTP destination. Scheme, host, and port must all match the request.
 */
data class WasiHttpNetworkEndpoint(
    val protocol: WasiHttpNetworkProtocol,
    val host: String,
    val port: Int,
) {
    internal val normalizedHost: String = normalizeNetworkPolicyHost(host)

    init {
        require(port in 1..0xffff) {
            "HTTP network endpoint port must be between 1 and 65535"
        }
    }
}

/**
 * Explicit network capabilities granted to a WASI Preview 3 host.
 *
 * HTTP and raw sockets are independent. Empty endpoint sets deny all network access. Matching is
 * exact after ASCII hostname/IP normalization; wildcard and suffix matching are deliberately not
 * supported.
 */
class WasiNetworkPolicy(
    httpEndpoints: Set<WasiHttpNetworkEndpoint> = emptySet(),
    rawSocketEndpoints: Set<WasiNetworkEndpoint> = emptySet(),
) {
    private val configuredHttpEndpoints: Set<WasiHttpNetworkEndpoint> =
        LinkedHashSet(httpEndpoints)
    private val configuredRawSocketEndpoints: Set<WasiNetworkEndpoint> =
        LinkedHashSet(rawSocketEndpoints)

    val httpEndpoints: Set<WasiHttpNetworkEndpoint>
        get() = configuredHttpEndpoints.toSet()

    val rawSocketEndpoints: Set<WasiNetworkEndpoint>
        get() = configuredRawSocketEndpoints.toSet()

    fun allowsHttp(
        protocol: WasiHttpNetworkProtocol,
        host: String,
        port: Int,
    ): Boolean =
        normalizedNetworkPolicyHostOrNull(host)?.let { normalized ->
            configuredHttpEndpoints.any { endpoint ->
                endpoint.protocol == protocol &&
                    endpoint.normalizedHost == normalized &&
                    endpoint.port == port
            }
        } ?: false

    fun allowsRawSocket(host: String, port: Int): Boolean =
        normalizedNetworkPolicyHostOrNull(host)?.let { normalized ->
            configuredRawSocketEndpoints.any { endpoint ->
                endpoint.normalizedHost == normalized && endpoint.port == port
            }
        } ?: false

    internal fun allowsRawSocketHost(normalizedHost: String): Boolean =
        configuredRawSocketEndpoints.any { it.normalizedHost == normalizedHost }

    internal fun allowsResolvedRawSocket(
        numericHost: String,
        port: Int,
        resolvedAddresses: Map<String, Set<String>>,
    ): Boolean =
        configuredRawSocketEndpoints.any { endpoint ->
            endpoint.port == port &&
                resolvedAddresses[endpoint.normalizedHost]?.contains(numericHost) == true
        }

    internal fun hasHttpAccess(): Boolean = configuredHttpEndpoints.isNotEmpty()

    internal fun hasRawSocketAccess(): Boolean = configuredRawSocketEndpoints.isNotEmpty()

    companion object {
        val DENY_ALL: WasiNetworkPolicy = WasiNetworkPolicy()
    }
}

private fun normalizedNetworkPolicyHostOrNull(host: String): String? =
    try {
        normalizeNetworkPolicyHost(host)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun normalizeNetworkPolicyHost(host: String): String =
    canonicalizeExactNetworkHost(host)

private fun networkHostFromAddress(address: ByteArray): String =
    when (address.size) {
        4 -> address.joinToString(".") { unsignedByte(it).toString() }
        16 ->
            (0 until 8).joinToString(":") { index ->
                ((unsignedByte(address[index * 2]) shl 8) or
                        unsignedByte(address[index * 2 + 1]))
                    .toString(16)
            }
        else -> throw IllegalArgumentException("network address must contain 4 or 16 bytes")
    }

class WasiPreview3
private constructor(
    builder: Builder,
    private val transports: WasiPreview3Transports,
) : WasiPreview3CanonicalIntrinsics, AutoCloseable {

    companion object {
        const val DEFAULT_VERSION: String = "0.3.0"

        @ComponentModelJvmStatic fun builder(): Builder = Builder()
    }

    private val version: String = builder.version
    private val stdin: RawSource = builder.stdin
    private val stdout: RawSink = builder.stdout
    private val stderr: RawSink = builder.stderr
    private val arguments: List<String> = builder.arguments.toList()
    private val environment: Map<String, String> = builder.environment.toMap()
    private val initialCwd: String? = builder.initialCwd
    private val wallClock: KotlinClock = builder.wallClock
    private val wallClockTimeZone: TimeZone = builder.wallClockTimeZone
    private val wallClockResolutionNanos: Long = builder.wallClockResolutionNanos
    private val monotonicClock: () -> Long = builder.monotonicClock
    private val monotonicBaseNanos: Long = builder.monotonicClock()
    private val monotonicResolutionNanos: Long = builder.monotonicResolutionNanos
    private val secureRandom: CryptoRand = builder.secureRandom
    private val insecureRandom: Random = builder.insecureRandom
    private val insecureSeedLower: Long = builder.insecureSeedLower
    private val insecureSeedUpper: Long = builder.insecureSeedUpper
    private val preopens: List<Preopen> = builder.preopens.toList()
    private val terminalStdin: Boolean = builder.terminalStdin
    private val terminalStdout: Boolean = builder.terminalStdout
    private val terminalStderr: Boolean = builder.terminalStderr
    private val networkPolicy: WasiNetworkPolicy = builder.networkPolicy
    private val unsafeAllowAllNetworking: Boolean = builder.unsafeAllowAllNetworking
    private val httpClient: WasiHttpClient = transports.httpClient
    private val configuredHostScope = builder.coroutineScope ?: defaultPreview3CoroutineScope()
    private val ownsConfiguredHostScope: Boolean =
        builder.coroutineScope == null || builder.ownsCoroutineScope
    private val hostJob = SupervisorJob(configuredHostScope.coroutineContext[Job])
    private val hostScope = CoroutineScope(configuredHostScope.coroutineContext + hostJob)
    private val httpHandler: WasiHttpHandler = builder.httpHandler
    private val fileSystem: FileSystem = builder.fileSystem
    private val socketRuntime: WasiSocketRuntime = transports.socketRuntime
    private val streamBufferCapacity: Int = builder.streamBufferCapacity
    private val maxCanonicalThreads: Int = builder.maxCanonicalThreads
    private val maxPendingFutures: Int = builder.maxPendingFutures
    private val maxPendingStreams: Int = builder.maxPendingStreams
    private val maxWaitables: Int = builder.maxWaitables
    private val maxInFlightHostTasks: Int = builder.maxInFlightHostTasks

    private val descriptors: WitResourceTable<FilesystemDescriptor> = WitResourceTable()
    private val directoryEntryStreams: WitResourceTable<DirectoryEntryStream> = WitResourceTable()
    private val fields: WitResourceTable<HttpFields> = WitResourceTable()
    private val requests: WitResourceTable<HttpRequest> = WitResourceTable()
    private val requestOptions: WitResourceTable<RequestOptions> = WitResourceTable()
    private val responses: WitResourceTable<HttpResponse> = WitResourceTable()
    private val tcpSockets: WitResourceTable<TcpSocket> = WitResourceTable()
    private val udpSockets: WitResourceTable<UdpSocket> = WitResourceTable()
    private val futures: WitResourceTable<FutureValue> = WitResourceTable(maxPendingFutures)
    private val streams: WitResourceTable<StreamValue> = WitResourceTable(maxPendingStreams)
    private val terminalInputs: WitResourceTable<TerminalInput> = WitResourceTable()
    private val terminalOutputs: WitResourceTable<TerminalOutput> = WitResourceTable()
    private val tcpBoundAddresses: MutableSet<SocketAddressKey> = LinkedHashSet()
    private val resolvedRawSocketAddresses: MutableMap<String, Set<String>> = LinkedHashMap()
    private val hostTaskLock = WasiPreviewLock()
    private val filesystemReadHandler = FilesystemReadHostHandler()
    private val filesystemWriteHandler = FilesystemWriteHostHandler()
    private val filesystemReadLock = WasiPreviewLock()
    private val filesystemWriteLock = WasiPreviewLock()
    private val filesystemReadBuffer = ByteArray(DIRECT_FILESYSTEM_WRITE_CHUNK_SIZE)
    private val filesystemWriteBuffer = ByteArray(DIRECT_FILESYSTEM_WRITE_CHUNK_SIZE)
    private var inFlightHostTasks: Int = 0
    private var closed: Boolean = false

    fun version(): String = version

    internal fun hostCoroutineScope(): CoroutineScope = hostScope

    internal fun maxCanonicalThreads(): Int = maxCanonicalThreads

    internal fun maxWaitables(): Int = maxWaitables

    internal fun maxInFlightHostTasks(): Int = maxInFlightHostTasks

    internal fun preview1SecureRandom(): CryptoRand = secureRandom

    internal fun preview1WallClock(): KotlinClock = wallClock

    internal fun preview1Stdin(): RawSource = stdin

    internal fun preview1Stdout(): RawSink = stdout

    internal fun preview1Stderr(): RawSink = stderr

    internal fun preview1StdinIsTty(): Boolean = terminalStdin

    internal fun preview1StdoutIsTty(): Boolean = terminalStdout

    internal fun preview1StderrIsTty(): Boolean = terminalStderr

    internal fun preview1Arguments(): List<String> = arguments

    internal fun preview1Environment(): Map<String, String> = environment

    internal fun preview1Preopens(): List<Preopen> = preopens.toList()

    internal fun preview1FileSystem(): FileSystem = fileSystem

    override fun close() {
        val shouldClose =
            withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
        if (!shouldClose) {
            return
        }
        hostJob.cancel()
        if (ownsConfiguredHostScope) {
            configuredHostScope.cancel()
        }
        try {
            closeResources()
        } catch (failure: Throwable) {
            closeWasiPreview3TransportAfterFailure(failure, transports)
            throw failure
        }
        transports.close()
    }

    fun cancel() {
        close()
    }

    private fun closeResources() {
        val streamsToClose = streams.close()
        val descriptorsToClose = descriptors.close()
        val tcpSocketsToClose = tcpSockets.close()
        val udpSocketsToClose = udpSockets.close()
        val futuresToCancel = futures.close()
        val responsesToClose = responses.close()
        directoryEntryStreams.close()
        fields.close()
        requests.close()
        requestOptions.close()
        terminalInputs.close()
        terminalOutputs.close()

        for (stream in streamsToClose) {
            closeStreamValue(stream)
        }
        for (descriptor in descriptorsToClose) {
            closeFilesystemDescriptor(descriptor)
        }
        for (socket in tcpSocketsToClose) {
            closeTcpSocket(socket)
        }
        for (socket in udpSocketsToClose) {
            closeUdpSocket(socket)
        }
        for (future in futuresToCancel) {
            future.state.cancelReadable()
            future.state.cancelWritable()
        }
        for (response in responsesToClose) {
            closeHttpResponse(response)
        }
        withWasiPreviewLock(hostTaskLock) {
            resolvedRawSocketAddresses.clear()
        }
    }

    private fun closeStreamValue(stream: StreamValue) {
        when (val data = stream.data) {
            is ByteStreamBuffer -> {
                data.cancelReadable()
                data.cancelWritable()
            }
            is ObjectStreamBuffer -> {
                data.cancelReadable()
                data.cancelWritable()
            }
            is TcpListenerStream -> data.close()
            is TcpReceiveStream -> data.drop()
            is SourceByteStream -> data.close()
            is RawSource -> closeIgnoringFailure { data.close() }
        }
    }

    private fun closeHttpResponse(response: HttpResponse) {
        when (val body = response.body) {
            is HttpBody -> {
                body.streamData.cancelReadable()
                body.streamData.cancelWritable()
            }
            is ByteStreamBuffer -> {
                body.cancelReadable()
                body.cancelWritable()
            }
            is SourceByteStream -> body.close()
            is RawSource -> closeIgnoringFailure { body.close() }
        }
    }

    private fun closeTcpSocket(socket: TcpSocket) {
        socket.boundAddressKey?.let { tcpBoundAddresses.remove(it) }
        socket.boundAddressKey = null
        WasiPreview3TcpBindRegistry.release(socket.globalBoundAddressKey)
        socket.globalBoundAddressKey = null
        closeIgnoringFailure { socket.connection?.close() }
        socket.connection = null
        closeIgnoringFailure { socket.listener?.close() }
        socket.listener = null
    }

    private fun closeUdpSocket(socket: UdpSocket) {
        closeIgnoringFailure { socket.endpoint?.close() }
        socket.endpoint = null
    }

    private fun closeFilesystemDescriptor(descriptor: FilesystemDescriptor) {
        closeIgnoringFailure { descriptor.close() }
    }

    private fun closeIgnoringFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    fun <T : WasiHostImportBuilder> install(builder: T): T {
        requirePresent(builder, "builder")
        builder.withWasiPreview3CanonicalIntrinsics(this)
        installCli(builder)
        installClocks(builder)
        installRandom(builder)
        installFilesystem(builder)
        installHttp(builder)
        installSockets(builder)
        return builder
    }

    fun handleHttpRequest(
        plugin: WasiComponentInvoker,
        request: WasiPreview2.HttpRequestSnapshot,
    ): WasiPreview2.HttpResponseSnapshot {
        requirePresent(plugin, "plugin")
        requirePresent(request, "request")
        val requestHandle =
            requests.insertResource(
                HttpRequest(
                    methodFromString(request.method()),
                    request.pathWithQuery().ifEmpty { null },
                    schemeFromString(request.scheme()),
                    request.authority().ifEmpty { null },
                    fieldsFromByteHeaders(request.headers(), false),
                    null,
                    completedHttpBody(request.body()),
                    emptyTrailers(),
                )
            )
        val result = plugin.call("handler.handle", requestHandle)
        return responseSnapshot(liftResponseResult(result))
    }

    fun handleHttpRequest(
        plugin: WasiComponentInvoker,
        method: String,
        pathWithQuery: String,
        scheme: String,
        authority: String,
        headers: Map<String, List<ByteArray>>,
        body: ByteArray,
    ): WasiPreview2.HttpResponseSnapshot =
        handleHttpRequest(
            plugin,
            WasiPreview2.HttpRequestSnapshot(
                method,
                pathWithQuery,
                scheme,
                authority,
                headers,
                body,
            ),
        )

    fun futureValue(future: WitFuture<*>): Any? = futureValue(future.handle())

    fun futureValue(handle: Long): Any? = futures.get(handle).state.value

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> awaitFuture(future: WitFuture<T>): T =
        futures.get(future.handle()).state.awaitValue() as T

    override suspend fun awaitFutureValue(futureHandle: Long): Any? =
        futures.get(futureHandle).state.awaitValue()

    override fun completedFutureHandle(value: Any?): Long = futureHandle(value)

    fun completedFuture(value: Any?): WitFuture<Any?> = WitFuture.of(completedFutureHandle(value))

    @Suppress("UNCHECKED_CAST")
    fun <T> completedFutureOf(value: T): WitFuture<T> = completedFuture(value) as WitFuture<T>

    fun <T> pendingFuture(): WitFuture<T> = WitFuture.of(futureHandle(FutureState()))

    fun completeFuture(future: WitFuture<*>, value: Any?) {
        completeFuture(future.handle(), value)
    }

    fun completeFuture(handle: Long, value: Any?) {
        futures.get(handle).state.complete(value)
    }

    private fun completeFutureIfPresent(future: WitFuture<*>, value: Any?) {
        futures.updateIfPresent(future.handle()) { entry ->
            entry.state.complete(value)
        }
    }

    fun futureCompleted(future: WitFuture<*>): Boolean = futureCompleted(future.handle())

    fun futureCompleted(handle: Long): Boolean = futures.get(handle).state.completed

    fun httpFields(headers: Map<String, List<ByteArray>>): WitResource<*> =
        fields.insertResource(fieldsFromByteHeaders(headers, true))

    fun httpFieldsSnapshot(fields: WitResource<*>): Map<String, List<ByteArray>> =
        httpFieldsSnapshot(fields.handle())

    fun httpFieldsSnapshot(handle: Long): Map<String, List<ByteArray>> =
        headersSnapshot(this.fields.get(handle))

    fun httpRequestTrailers(
        request: WitResource<*>
    ): WitResult<Map<String, List<ByteArray>>?, Any?> = httpRequestTrailers(request.handle())

    fun httpRequestTrailers(handle: Long): WitResult<Map<String, List<ByteArray>>?, Any?> =
        trailerSnapshot(requests.get(handle).trailers, "request")

    fun httpResponseTrailers(
        response: WitResource<*>
    ): WitResult<Map<String, List<ByteArray>>?, Any?> = httpResponseTrailers(response.handle())

    fun httpResponseTrailers(handle: Long): WitResult<Map<String, List<ByteArray>>?, Any?> =
        trailerSnapshot(responses.get(handle).trailers, "response")

    fun streamBytes(stream: WitStream<*>): ByteArray = streamBytes(stream.handle())

    @OptIn(ExperimentalUnsignedTypes::class)
    fun byteStream(bytes: ByteArray): WitStream<UByte> =
        WitStream.of(streamHandle(StreamValue("byte-stream", bytes.copyOf())))

    @OptIn(ExperimentalUnsignedTypes::class)
    fun byteStream(chunks: Flow<ByteArray>): WitStream<UByte> {
        requirePresent(chunks, "chunks")
        val buffer = ByteStreamBuffer(capacity = streamBufferCapacity)
        val stream = WitStream.of<UByte>(streamHandle(StreamValue("byte-stream", buffer)))
        launchHostTask {
            try {
                chunks.collect { chunk ->
                    if (!writeByteStreamChunk(buffer, chunk)) {
                        throw ByteStreamWriteStoppedException()
                    }
                }
                buffer.dropWritable()
            } catch (_: ByteStreamWriteStoppedException) {
            } catch (_: Throwable) {
                buffer.cancelWritable()
            }
        }
        return stream
    }

    fun <T> streamOf(values: Iterable<T>): WitStream<T> =
        WitStream.of(
            streamHandle(
                StreamValue(
                    "object-stream",
                    ObjectStreamBuffer(values.toList(), writableDropped = true),
                )
            )
        )

    fun streamBytes(handle: Long): ByteArray {
        val data = streams.get(handle).data
        if (data is ByteArray) {
            return data.copyOf()
        }
        if (data is ByteStreamBuffer) {
            return data.snapshotRemaining()
        }
        if (data is SourceByteStream) {
            return data.readBytes()
        }
        if (data is RawSource) {
            val source = SourceByteStream(data)
            streams.get(handle).data = source
            return source.readBytes()
        }
        if (data is TcpReceiveStream) {
            return data.readBytes()
        }
        throw ComponentModelException("WASI Preview 3 stream $handle does not contain byte data")
    }

    suspend fun readByteStreamChunk(
        stream: WitStream<*>,
        maxBytes: Int = DEFAULT_STREAM_BUFFER_CAPACITY,
    ): ByteArray? = readByteStreamChunk(stream.handle(), maxBytes)

    suspend fun readByteStreamChunk(
        handle: Long,
        maxBytes: Int = DEFAULT_STREAM_BUFFER_CAPACITY,
    ): ByteArray? {
        val limit = requireByteStreamChunkSize(maxBytes)
        while (true) {
            val chunk = readByteStreamChunkNow(handle, limit)
            if (chunk.bytes.isNotEmpty()) {
                return chunk.bytes
            }
            if (chunk.closed) {
                return null
            }
            awaitStreamReadable(handle)
            yield()
        }
    }

    suspend fun awaitStreamReadable(stream: WitStream<*>): Unit = awaitStreamReadable(stream.handle())

    override suspend fun awaitStreamReadable(streamHandle: Long) {
        when (val data = streams.get(streamHandle).data) {
            is ByteStreamBuffer -> data.awaitReadable()
            is ObjectStreamBuffer -> data.awaitReadable()
            is ByteArray,
            is List<*>,
            is SourceByteStream,
            is RawSource -> Unit
            is TcpReceiveStream -> data.awaitReadable()
            is TcpListenerStream -> data.awaitReadable()
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $streamHandle cannot be awaited for readability"
                )
        }
    }

    suspend fun awaitStreamWritable(stream: WitStream<*>): Unit = awaitStreamWritable(stream.handle())

    override suspend fun awaitStreamWritable(streamHandle: Long) {
        when (val data = streams.get(streamHandle).data) {
            is ByteStreamBuffer -> data.awaitWritable()
            is ObjectStreamBuffer -> data.awaitWritable()
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $streamHandle cannot be awaited for writability"
                )
        }
    }

    override fun streamNew(payloadType: WitPackage.TypeRef): Long {
        requireStreamCapacity(2)
        val buffer: Any =
            if (isBytePayload(payloadType)) ByteStreamBuffer(capacity = streamBufferCapacity)
            else
                ObjectStreamBuffer(
                    emptyList<Any?>(),
                    writableDropped = false,
                    capacity = streamBufferCapacity,
                )
        val handles =
            streams.insertResourceHandles(
                listOf(
                    StreamValue("stream-readable", buffer),
                    StreamValue("stream-writable", buffer),
                )
            )
        val reader = handles[0]
        val writer = handles[1]
        return (writer shl 32) or reader
    }

    override fun streamRead(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        if (!isBytePayload(payloadType)) {
            return streamReadObjects(context, streamHandle, ptr, len, payloadType)
        }
        val tcpReceive = streams.get(streamHandle).data as? TcpReceiveStream
        if (tcpReceive != null) {
            return streamReadTcpReceive(context, tcpReceive, ptr, len)
        }
        val sourceStream = sourceByteStream(streamHandle)
        if (sourceStream != null) {
            return streamReadSource(context, sourceStream, ptr, len)
        }
        val stream = byteStream(streamHandle)
        if (stream.readableDropped) {
            return streamDropped(0)
        }
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val bytes = stream.read(length)
        if (bytes.isNotEmpty()) {
            context.writeMemory(ptr, bytes)
            return if (stream.writableDropped && stream.remaining() == 0) {
                streamDropped(bytes.size)
            } else {
                streamCompleted(bytes.size)
            }
        }
        return if (stream.writableDropped) streamDropped(0) else STREAM_BLOCKED
    }

    override fun streamWrite(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        if (!isBytePayload(payloadType)) {
            return streamWriteObjects(context, streamHandle, ptr, len, payloadType)
        }
        val stream = byteStream(streamHandle)
        if (stream.readableDropped || stream.writableDropped) {
            return streamDropped(0)
        }
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val writable = length.coerceAtMost(stream.writableCapacity())
        if (writable <= 0) {
            return STREAM_BLOCKED
        }
        val written = stream.write(context.readMemory(ptr, writable))
        return if (written > 0) streamCompleted(written) else STREAM_BLOCKED
    }

    private fun streamReadObjects(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        val streamValue = streams.get(streamHandle)
        val listener = streamValue.data as? TcpListenerStream
        if (listener != null) {
            return streamReadTcpListener(context, listener, ptr, len, payloadType)
        }
        val stream = objectStream(streamHandle)
        if (stream.readableDropped) {
            return streamDropped(0)
        }
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val values = stream.read(length)
        if (values.isNotEmpty()) {
            context.storeListElements(ptr, payloadType, values)
            return if (stream.writableDropped && stream.remaining() == 0) {
                streamDropped(values.size)
            } else {
                streamCompleted(values.size)
            }
        }
        return if (stream.writableDropped) streamDropped(0) else STREAM_BLOCKED
    }

    private fun streamReadTcpReceive(
        context: WasiPreview3CanonicalContext,
        stream: TcpReceiveStream,
        ptr: Int,
        len: Int,
    ): Long {
        requireRawSocketAccess()
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val read = stream.read(length)
        if (read.bytes.isNotEmpty()) {
            context.writeMemory(ptr, read.bytes)
            return if (read.closed) streamDropped(read.bytes.size)
            else streamCompleted(read.bytes.size)
        }
        return if (read.closed) streamDropped(0) else STREAM_BLOCKED
    }

    private fun streamReadSource(
        context: WasiPreview3CanonicalContext,
        stream: SourceByteStream,
        ptr: Int,
        len: Int,
    ): Long {
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val read = stream.readToMemory(context, ptr, length)
        if (read.count > 0) {
            return if (read.closed) streamDropped(read.count)
            else streamCompleted(read.count)
        }
        return if (read.closed) streamDropped(0) else STREAM_BLOCKED
    }

    private fun streamWriteObjects(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        val stream = objectStream(streamHandle)
        if (stream.readableDropped || stream.writableDropped) {
            return streamDropped(0)
        }
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        val writable = length.coerceAtMost(stream.writableCapacity())
        if (writable <= 0) {
            return STREAM_BLOCKED
        }
        val values = context.loadListElements(ptr, writable, payloadType)
        val written = stream.write(values)
        return if (written > 0) streamCompleted(written) else STREAM_BLOCKED
    }

    private fun streamReadTcpListener(
        context: WasiPreview3CanonicalContext,
        listener: TcpListenerStream,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        requireRawSocketAccess()
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        if (!listener.socket.listening || listener.socket.listener == null) {
            return streamDropped(0)
        }
        val child = listener.takeAccepted() ?: return STREAM_BLOCKED
        val resource = tcpSockets.insertResource(child)
        context.storeListElements(ptr, payloadType, listOf(resource))
        return streamCompleted(1)
    }

    override fun streamCancelRead(streamHandle: Long): Long {
        when (val buffer = streams.get(streamHandle).data) {
            is ByteStreamBuffer -> buffer.cancelReadable()
            is ObjectStreamBuffer -> buffer.cancelReadable()
        }
        return streamCancelled(0)
    }

    override fun streamCancelWrite(streamHandle: Long): Long {
        when (val buffer = streams.get(streamHandle).data) {
            is ByteStreamBuffer -> buffer.cancelWritable()
            is ObjectStreamBuffer -> buffer.cancelWritable()
        }
        return streamCancelled(0)
    }

    override fun streamDropReadable(streamHandle: Long) {
        val stream = streams.remove(streamHandle)
        when (val buffer = stream.data) {
            is ByteStreamBuffer -> buffer.dropReadable()
            is ObjectStreamBuffer -> buffer.dropReadable()
            is TcpReceiveStream -> buffer.drop()
            is SourceByteStream -> buffer.close()
            is RawSource -> buffer.close()
        }
    }

    override fun streamDropWritable(streamHandle: Long) {
        val stream = streams.remove(streamHandle)
        when (val buffer = stream.data) {
            is ByteStreamBuffer -> buffer.dropWritable()
            is ObjectStreamBuffer -> buffer.dropWritable()
            is TcpReceiveStream -> buffer.drop()
            is SourceByteStream -> buffer.close()
            is RawSource -> buffer.close()
        }
    }

    override fun futureNew(): Long {
        requireFutureCapacity(2)
        val state = FutureState()
        val handles =
            futures.insertResourceHandles(
                listOf(
                    FutureValue(state),
                    FutureValue(state),
                )
            )
        val reader = handles[0]
        val writer = handles[1]
        return (writer shl 32) or reader
    }

    override fun futureRead(
        context: WasiPreview3CanonicalContext,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        val state = futures.get(futureHandle).state
        if (state.readableDropped) {
            return streamCancelled(0)
        }
        if (!state.completed) {
            return if (state.writableDropped) streamCancelled(0) else STREAM_BLOCKED
        }
        context.storeFutureValue(ptr, payloadType, state.value)
        return streamCompleted(0)
    }

    override fun futureWrite(
        context: WasiPreview3CanonicalContext,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        val state = futures.get(futureHandle).state
        if (state.readableDropped || state.writableDropped || state.completed) {
            return streamDropped(0)
        }
        state.complete(context.loadFutureValue(ptr, payloadType))
        return streamCompleted(0)
    }

    override fun futureCancelRead(futureHandle: Long): Long {
        futures.get(futureHandle).state.cancelReadable()
        return streamCancelled(0)
    }

    override fun futureCancelWrite(futureHandle: Long): Long {
        futures.get(futureHandle).state.cancelWritable()
        return streamCancelled(0)
    }

    override fun futureDropReadable(futureHandle: Long) {
        val future = futures.remove(futureHandle)
        future.state.dropReadable()
    }

    override fun futureDropWritable(futureHandle: Long) {
        val future = futures.remove(futureHandle)
        future.state.dropWritable()
    }

    @Suppress("UNCHECKED_CAST")
    fun streamDirectoryEntries(stream: WitStream<*>): List<Map<String, Any?>> =
        streamDirectoryEntries(stream.handle())

    @Suppress("UNCHECKED_CAST")
    fun streamDirectoryEntries(handle: Long): List<Map<String, Any?>> {
        val data = streams.get(handle).data
        if (data is List<*>) {
            return data.map { entry ->
                (entry as? Map<String, Any?>)
                    ?: throw ComponentModelException(
                        "WASI Preview 3 stream $handle contains a non-directory entry $entry"
                    )
            }
        }
        if (data is ObjectStreamBuffer) {
            return data.snapshotRemaining().map { entry ->
                @Suppress("UNCHECKED_CAST")
                (entry as? Map<String, Any?>)
                    ?: throw ComponentModelException(
                        "WASI Preview 3 stream $handle contains a non-directory entry $entry"
                    )
            }
        }
        throw ComponentModelException(
            "WASI Preview 3 stream $handle does not contain directory entries"
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> streamValues(stream: WitStream<T>): List<T> = streamValues(stream.handle())

    @Suppress("UNCHECKED_CAST")
    fun <T> streamValues(handle: Long): List<T> {
        val data = streams.get(handle).data
        if (data is ObjectStreamBuffer) {
            return data.snapshotRemaining() as List<T>
        }
        if (data is List<*>) {
            return data.toList() as List<T>
        }
        throw ComponentModelException("WASI Preview 3 stream $handle does not contain typed values")
    }

    fun acceptTcpConnection(stream: WitStream<*>): WitResult<WitResource<*>, Any?> =
        acceptTcpConnection(stream.handle())

    fun acceptTcpConnection(handle: Long): WitResult<WitResource<*>, Any?> = socketResultValue {
        val stream = streams.get(handle)
        if (stream.kind != "tcp-listener") {
            throw ComponentModelException(
                "WASI Preview 3 stream $handle is not a TCP listener stream"
            )
        }
        val listener =
            stream.data as? TcpListenerStream
                ?: throw ComponentModelException(
                    "WASI Preview 3 stream $handle does not contain a TCP listener"
                )
        acceptTcpConnectionResource(listener.socket)
    }

    fun tcpLocalAddress(socket: WitResource<*>): WitResult<Any, Any?> =
        tcpLocalAddress(socket.handle())

    fun tcpLocalAddress(handle: Long): WitResult<Any, Any?> = socketResultValue {
        tcpLocalAddressValue(tcpSockets.get(handle))
    }

    fun tcpRemoteAddress(socket: WitResource<*>): WitResult<Any, Any?> =
        tcpRemoteAddress(socket.handle())

    fun tcpRemoteAddress(handle: Long): WitResult<Any, Any?> = socketResultValue {
        tcpRemoteAddressValue(tcpSockets.get(handle))
    }

    private fun installCli(builder: WasiHostImportBuilder) {
        registerCli(builder, "environment", "get-environment", this::getEnvironment)
        registerCli(builder, "environment", "get-arguments", this::getArguments)
        registerCli(builder, "environment", "get-initial-cwd", this::getInitialCwd)

        registerCli(builder, "exit", "exit", this::exit)
        registerCli(builder, "exit", "exit-with-code", this::exitWithCode)

        registerCli(builder, "stdin", "read-via-stream", this::stdinReadViaStream)
        registerCli(builder, "stdout", "write-via-stream", this::stdoutWriteViaStream)
        registerCli(builder, "stderr", "write-via-stream", this::stderrWriteViaStream)

        registerCli(builder, "terminal-stdin", "get-terminal-stdin", this::getTerminalStdin)
        registerCli(builder, "terminal-stdout", "get-terminal-stdout", this::getTerminalStdout)
        registerCli(builder, "terminal-stderr", "get-terminal-stderr", this::getTerminalStderr)
        registerDrop(builder, CLI_PACKAGE, "terminal-input", "terminal-input", terminalInputs)
        registerDrop(builder, CLI_PACKAGE, "terminal-output", "terminal-output", terminalOutputs)
    }

    private fun installClocks(builder: WasiHostImportBuilder) {
        registerClocks(builder, "system-clock", "now", this::systemClockNow)
        registerClocks(builder, "system-clock", "get-resolution", this::systemClockResolution)
        registerClocks(builder, "monotonic-clock", "now", this::monotonicClockNow)
        registerClocks(builder, "monotonic-clock", "get-resolution", this::monotonicClockResolution)
        registerClocks(builder, "monotonic-clock", "wait-until", this::monotonicClockWaitUntil)
        registerClocks(builder, "monotonic-clock", "wait-for", this::monotonicClockWaitFor)
        registerClocks(builder, "timezone", "iana-id", this::timezoneIanaId)
        registerClocks(builder, "timezone", "utc-offset", this::timezoneUtcOffset)
        registerClocks(builder, "timezone", "to-debug-string", this::timezoneDebugString)
    }

    private fun installRandom(builder: WasiHostImportBuilder) {
        registerRandom(builder, "random", "get-random-bytes", this::getRandomBytes)
        registerRandom(builder, "random", "get-random-u64", this::getRandomU64)
        registerRandom(
            builder,
            "insecure",
            "get-insecure-random-bytes",
            this::getInsecureRandomBytes,
        )
        registerRandom(builder, "insecure", "get-insecure-random-u64", this::getInsecureRandomU64)
        registerRandom(builder, "insecure-seed", "get-insecure-seed", this::getInsecureSeed)
    }

    private fun installFilesystem(builder: WasiHostImportBuilder) {
        registerFilesystem(builder, "preopens", "get-directories", this::filesystemGetDirectories)

        registerFilesystem(
            builder,
            "types",
            "descriptor.read-via-stream",
            this::filesystemReadViaStream,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.read-via-stream",
            this::filesystemReadViaStream,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.write-via-stream",
            this::filesystemWriteViaStream,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.write-via-stream",
            this::filesystemWriteViaStream,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.append-via-stream",
            this::filesystemAppendViaStream,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.append-via-stream",
            this::filesystemAppendViaStream,
        )
        registerFilesystem(builder, "types", "descriptor.advise", this::filesystemAdvise)
        registerFilesystem(builder, "types", "[method]descriptor.advise", this::filesystemAdvise)
        registerFilesystem(builder, "types", "descriptor.sync-data", this::filesystemSyncData)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.sync-data",
            this::filesystemSyncData,
        )
        registerFilesystem(builder, "types", "descriptor.get-flags", this::filesystemGetFlags)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.get-flags",
            this::filesystemGetFlags,
        )
        registerFilesystem(builder, "types", "descriptor.get-type", this::filesystemGetType)
        registerFilesystem(builder, "types", "[method]descriptor.get-type", this::filesystemGetType)
        registerFilesystem(builder, "types", "descriptor.set-size", this::filesystemSetSize)
        registerFilesystem(builder, "types", "[method]descriptor.set-size", this::filesystemSetSize)
        registerFilesystem(builder, "types", "descriptor.set-times", this::filesystemSetTimes)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.set-times",
            this::filesystemSetTimes,
        )
        registerFilesystem(builder, "types", "descriptor.read", filesystemReadHandler)
        registerFilesystem(builder, "types", "[method]descriptor.read", filesystemReadHandler)
        registerFilesystem(builder, "types", "descriptor.write", filesystemWriteHandler)
        registerFilesystem(builder, "types", "[method]descriptor.write", filesystemWriteHandler)
        registerFilesystem(
            builder,
            "types",
            "descriptor.read-directory",
            this::filesystemReadDirectory,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.read-directory",
            this::filesystemReadDirectory,
        )
        registerFilesystem(builder, "types", "descriptor.sync", this::filesystemSync)
        registerFilesystem(builder, "types", "[method]descriptor.sync", this::filesystemSync)
        registerFilesystem(
            builder,
            "types",
            "descriptor.create-directory-at",
            this::filesystemCreateDirectoryAt,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.create-directory-at",
            this::filesystemCreateDirectoryAt,
        )
        registerFilesystem(builder, "types", "descriptor.stat", this::filesystemStat)
        registerFilesystem(builder, "types", "[method]descriptor.stat", this::filesystemStat)
        registerFilesystem(builder, "types", "descriptor.stat-at", this::filesystemStatAt)
        registerFilesystem(builder, "types", "[method]descriptor.stat-at", this::filesystemStatAt)
        registerFilesystem(builder, "types", "descriptor.set-times-at", this::filesystemSetTimesAt)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.set-times-at",
            this::filesystemSetTimesAt,
        )
        registerFilesystem(builder, "types", "descriptor.link-at", this::filesystemLinkAt)
        registerFilesystem(builder, "types", "[method]descriptor.link-at", this::filesystemLinkAt)
        registerFilesystem(builder, "types", "descriptor.open-at", this::filesystemOpenAt)
        registerFilesystem(builder, "types", "[method]descriptor.open-at", this::filesystemOpenAt)
        registerFilesystem(builder, "types", "descriptor.readlink-at", this::filesystemReadlinkAt)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.readlink-at",
            this::filesystemReadlinkAt,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.remove-directory-at",
            this::filesystemRemoveDirectoryAt,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.remove-directory-at",
            this::filesystemRemoveDirectoryAt,
        )
        registerFilesystem(builder, "types", "descriptor.rename-at", this::filesystemRenameAt)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.rename-at",
            this::filesystemRenameAt,
        )
        registerFilesystem(builder, "types", "descriptor.symlink-at", this::filesystemSymlinkAt)
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.symlink-at",
            this::filesystemSymlinkAt,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.unlink-file-at",
            this::filesystemUnlinkFileAt,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.unlink-file-at",
            this::filesystemUnlinkFileAt,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.is-same-object",
            this::filesystemIsSameObject,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.is-same-object",
            this::filesystemIsSameObject,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.metadata-hash",
            this::filesystemMetadataHash,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.metadata-hash",
            this::filesystemMetadataHash,
        )
        registerFilesystem(
            builder,
            "types",
            "descriptor.metadata-hash-at",
            this::filesystemMetadataHashAt,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]descriptor.metadata-hash-at",
            this::filesystemMetadataHashAt,
        )
        registerDrop(builder, FILESYSTEM_PACKAGE, "types", "descriptor", descriptors)

        registerFilesystem(
            builder,
            "types",
            "directory-entry-stream.read-directory-entry",
            this::filesystemReadDirectoryEntry,
        )
        registerFilesystem(
            builder,
            "types",
            "[method]directory-entry-stream.read-directory-entry",
            this::filesystemReadDirectoryEntry,
        )
        registerDrop(
            builder,
            FILESYSTEM_PACKAGE,
            "types",
            "directory-entry-stream",
            directoryEntryStreams,
        )
    }

    private fun installHttp(builder: WasiHostImportBuilder) {
        registerHttp(builder, "client", "send", this::clientSend)
        registerHttp(builder, "handler", "handle", this::handlerHandle)

        registerHttp(builder, "types", "fields.constructor", this::fieldsConstructor)
        registerHttp(builder, "types", "[constructor]fields", this::fieldsConstructor)
        registerHttp(builder, "types", "fields.from-list", this::fieldsFromList)
        registerHttp(builder, "types", "[static]fields.from-list", this::fieldsFromList)
        registerHttp(builder, "types", "fields.get", this::fieldsGet)
        registerHttp(builder, "types", "[method]fields.get", this::fieldsGet)
        registerHttp(builder, "types", "fields.has", this::fieldsHas)
        registerHttp(builder, "types", "[method]fields.has", this::fieldsHas)
        registerHttp(builder, "types", "fields.set", this::fieldsSet)
        registerHttp(builder, "types", "[method]fields.set", this::fieldsSet)
        registerHttp(builder, "types", "fields.delete", this::fieldsDelete)
        registerHttp(builder, "types", "[method]fields.delete", this::fieldsDelete)
        registerHttp(builder, "types", "fields.get-and-delete", this::fieldsGetAndDelete)
        registerHttp(builder, "types", "[method]fields.get-and-delete", this::fieldsGetAndDelete)
        registerHttp(builder, "types", "fields.append", this::fieldsAppend)
        registerHttp(builder, "types", "[method]fields.append", this::fieldsAppend)
        registerHttp(builder, "types", "fields.copy-all", this::fieldsCopyAll)
        registerHttp(builder, "types", "[method]fields.copy-all", this::fieldsCopyAll)
        registerHttp(builder, "types", "fields.clone", this::fieldsClone)
        registerHttp(builder, "types", "[method]fields.clone", this::fieldsClone)
        registerDrop(builder, HTTP_PACKAGE, "types", "fields", fields)

        registerHttp(builder, "types", "request.new", this::requestNew)
        registerHttp(builder, "types", "[static]request.new", this::requestNew)
        registerHttp(builder, "types", "request.get-method", this::requestMethod)
        registerHttp(builder, "types", "[method]request.get-method", this::requestMethod)
        registerHttp(builder, "types", "request.set-method", this::requestSetMethod)
        registerHttp(builder, "types", "[method]request.set-method", this::requestSetMethod)
        registerHttp(builder, "types", "request.get-path-with-query", this::requestPath)
        registerHttp(builder, "types", "[method]request.get-path-with-query", this::requestPath)
        registerHttp(builder, "types", "request.set-path-with-query", this::requestSetPath)
        registerHttp(builder, "types", "[method]request.set-path-with-query", this::requestSetPath)
        registerHttp(builder, "types", "request.get-scheme", this::requestScheme)
        registerHttp(builder, "types", "[method]request.get-scheme", this::requestScheme)
        registerHttp(builder, "types", "request.set-scheme", this::requestSetScheme)
        registerHttp(builder, "types", "[method]request.set-scheme", this::requestSetScheme)
        registerHttp(builder, "types", "request.get-authority", this::requestAuthority)
        registerHttp(builder, "types", "[method]request.get-authority", this::requestAuthority)
        registerHttp(builder, "types", "request.set-authority", this::requestSetAuthority)
        registerHttp(builder, "types", "[method]request.set-authority", this::requestSetAuthority)
        registerHttp(builder, "types", "request.get-options", this::requestOptions)
        registerHttp(builder, "types", "[method]request.get-options", this::requestOptions)
        registerHttp(builder, "types", "request.get-headers", this::requestHeaders)
        registerHttp(builder, "types", "[method]request.get-headers", this::requestHeaders)
        registerHttp(builder, "types", "request.consume-body", this::requestConsumeBody)
        registerHttp(builder, "types", "[static]request.consume-body", this::requestConsumeBody)
        registerDrop(builder, HTTP_PACKAGE, "types", "request", requests)

        registerHttp(
            builder,
            "types",
            "request-options.constructor",
            this::requestOptionsConstructor,
        )
        registerHttp(
            builder,
            "types",
            "[constructor]request-options",
            this::requestOptionsConstructor,
        )
        registerHttp(
            builder,
            "types",
            "request-options.get-connect-timeout",
            this::requestOptionsConnect,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.get-connect-timeout",
            this::requestOptionsConnect,
        )
        registerHttp(
            builder,
            "types",
            "request-options.set-connect-timeout",
            this::requestOptionsSetConnect,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.set-connect-timeout",
            this::requestOptionsSetConnect,
        )
        registerHttp(
            builder,
            "types",
            "request-options.get-first-byte-timeout",
            this::requestOptionsFirstByte,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.get-first-byte-timeout",
            this::requestOptionsFirstByte,
        )
        registerHttp(
            builder,
            "types",
            "request-options.set-first-byte-timeout",
            this::requestOptionsSetFirstByte,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.set-first-byte-timeout",
            this::requestOptionsSetFirstByte,
        )
        registerHttp(
            builder,
            "types",
            "request-options.get-between-bytes-timeout",
            this::requestOptionsBetweenBytes,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.get-between-bytes-timeout",
            this::requestOptionsBetweenBytes,
        )
        registerHttp(
            builder,
            "types",
            "request-options.set-between-bytes-timeout",
            this::requestOptionsSetBetweenBytes,
        )
        registerHttp(
            builder,
            "types",
            "[method]request-options.set-between-bytes-timeout",
            this::requestOptionsSetBetweenBytes,
        )
        registerHttp(builder, "types", "request-options.clone", this::requestOptionsClone)
        registerHttp(builder, "types", "[method]request-options.clone", this::requestOptionsClone)
        registerDrop(builder, HTTP_PACKAGE, "types", "request-options", requestOptions)

        registerHttp(builder, "types", "response.new", this::responseNew)
        registerHttp(builder, "types", "[static]response.new", this::responseNew)
        registerHttp(builder, "types", "response.get-status-code", this::responseStatus)
        registerHttp(builder, "types", "[method]response.get-status-code", this::responseStatus)
        registerHttp(builder, "types", "response.set-status-code", this::responseSetStatus)
        registerHttp(builder, "types", "[method]response.set-status-code", this::responseSetStatus)
        registerHttp(builder, "types", "response.get-headers", this::responseHeaders)
        registerHttp(builder, "types", "[method]response.get-headers", this::responseHeaders)
        registerHttp(builder, "types", "response.consume-body", this::responseConsumeBody)
        registerHttp(builder, "types", "[static]response.consume-body", this::responseConsumeBody)
        registerDrop(builder, HTTP_PACKAGE, "types", "response", responses)
    }

    private fun installSockets(builder: WasiHostImportBuilder) {
        registerSockets(builder, "ip-name-lookup", "resolve-addresses", this::resolveAddresses)

        registerSockets(builder, "types", "tcp-socket.create", this::tcpCreate)
        registerSockets(builder, "types", "[static]tcp-socket.create", this::tcpCreate)
        registerTcpMethod(builder, "bind", this::tcpBind)
        registerTcpMethod(builder, "connect", this::tcpConnect)
        registerTcpMethod(
            builder,
            "listen",
            object : ContextualSocketResourceMethod<TcpSocket> {
                override fun apply(
                    resource: TcpSocket,
                    args: List<Any?>,
                    context: HostCallContext,
                ): Any? = tcpListen(resource, args, context)
            },
        )
        registerTcpMethod(builder, "accept", this::tcpAccept)
        registerTcpMethod(builder, "send", this::tcpSend)
        registerTcpMethod(builder, "receive", this::tcpReceive)
        registerTcpMethod(builder, "get-local-address", this::tcpLocalAddress)
        registerTcpMethod(builder, "get-remote-address", this::tcpRemoteAddress)
        registerTcpMethod(builder, "get-is-listening", this::tcpIsListening)
        registerTcpMethod(builder, "get-address-family", this::tcpAddressFamily)
        registerTcpMethod(builder, "set-listen-backlog-size", this::tcpSetListenBacklogSize)
        registerTcpMethod(builder, "get-keep-alive-enabled", this::tcpKeepAliveEnabled)
        registerTcpMethod(builder, "set-keep-alive-enabled", this::tcpSetKeepAliveEnabled)
        registerTcpMethod(builder, "get-keep-alive-idle-time", this::tcpKeepAliveIdleTime)
        registerTcpMethod(builder, "set-keep-alive-idle-time", this::tcpSetKeepAliveIdleTime)
        registerTcpMethod(builder, "get-keep-alive-interval", this::tcpKeepAliveInterval)
        registerTcpMethod(builder, "set-keep-alive-interval", this::tcpSetKeepAliveInterval)
        registerTcpMethod(builder, "get-keep-alive-count", this::tcpKeepAliveCount)
        registerTcpMethod(builder, "set-keep-alive-count", this::tcpSetKeepAliveCount)
        registerTcpMethod(builder, "get-hop-limit", this::tcpHopLimit)
        registerTcpMethod(builder, "set-hop-limit", this::tcpSetHopLimit)
        registerTcpMethod(builder, "get-receive-buffer-size", this::tcpReceiveBufferSize)
        registerTcpMethod(builder, "set-receive-buffer-size", this::tcpSetReceiveBufferSize)
        registerTcpMethod(builder, "get-send-buffer-size", this::tcpSendBufferSize)
        registerTcpMethod(builder, "set-send-buffer-size", this::tcpSetSendBufferSize)
        registerDrop(builder, SOCKETS_PACKAGE, "types", "tcp-socket", tcpSockets)

        registerSockets(builder, "types", "udp-socket.create", this::udpCreate)
        registerSockets(builder, "types", "[static]udp-socket.create", this::udpCreate)
        registerUdpMethod(builder, "bind", this::udpBind)
        registerUdpMethod(builder, "connect", this::udpConnect)
        registerUdpMethod(builder, "disconnect", this::udpDisconnect)
        registerUdpMethod(builder, "send", this::udpSend)
        registerUdpMethod(builder, "receive", this::udpReceive)
        registerUdpMethod(builder, "get-local-address", this::udpLocalAddress)
        registerUdpMethod(builder, "get-remote-address", this::udpRemoteAddress)
        registerUdpMethod(builder, "get-address-family", this::udpAddressFamily)
        registerUdpMethod(builder, "get-unicast-hop-limit", this::udpUnicastHopLimit)
        registerUdpMethod(builder, "set-unicast-hop-limit", this::udpSetUnicastHopLimit)
        registerUdpMethod(builder, "get-receive-buffer-size", this::udpReceiveBufferSize)
        registerUdpMethod(builder, "set-receive-buffer-size", this::udpSetReceiveBufferSize)
        registerUdpMethod(builder, "get-send-buffer-size", this::udpSendBufferSize)
        registerUdpMethod(builder, "set-send-buffer-size", this::udpSetSendBufferSize)
        registerDrop(builder, SOCKETS_PACKAGE, "types", "udp-socket", udpSockets)
    }

    private fun getEnvironment(args: List<Any?>): Any? {
        requireArity("environment.get-environment", args, 0)
        val result = ArrayList<List<String>>()
        for (entry in environment.entries) {
            result.add(listOf(entry.key, entry.value))
        }
        return result
    }

    private fun getArguments(args: List<Any?>): Any? {
        requireArity("environment.get-arguments", args, 0)
        return ArrayList(arguments)
    }

    private fun getInitialCwd(args: List<Any?>): Any? {
        requireArity("environment.get-initial-cwd", args, 0)
        return initialCwd
    }

    private fun exit(args: List<Any?>): Any? {
        requireArity("exit.exit", args, 1)
        throw ExitException(exitStatus(args[0]))
    }

    private fun exitWithCode(args: List<Any?>): Any? {
        requireArity("exit.exit-with-code", args, 1)
        throw ExitException((asU64(args[0]) and 0xffL).toInt())
    }

    private fun stdinReadViaStream(args: List<Any?>): Any? {
        requireArity("stdin.read-via-stream", args, 0)
        val future = pendingFuture<Any?>()
        return listOf(
            streamHandle(
                StreamValue("stdin", SourceByteStream(stdin, cliStreamCompletion(future)))
            ),
            future.handle(),
        )
    }

    private fun stdoutWriteViaStream(args: List<Any?>): Any? {
        requireArity("stdout.write-via-stream", args, 1)
        return writeCliOutputStream(stdout, handle(args[0]))
    }

    private fun stderrWriteViaStream(args: List<Any?>): Any? {
        requireArity("stderr.write-via-stream", args, 1)
        return writeCliOutputStream(stderr, handle(args[0]))
    }

    private fun getTerminalStdin(args: List<Any?>): Any? {
        requireArity("terminal-stdin.get-terminal-stdin", args, 0)
        return if (terminalStdin) terminalInputs.insertResource(TerminalInput()) else null
    }

    private fun getTerminalStdout(args: List<Any?>): Any? {
        requireArity("terminal-stdout.get-terminal-stdout", args, 0)
        return if (terminalStdout) terminalOutputs.insertResource(TerminalOutput()) else null
    }

    private fun getTerminalStderr(args: List<Any?>): Any? {
        requireArity("terminal-stderr.get-terminal-stderr", args, 0)
        return if (terminalStderr) terminalOutputs.insertResource(TerminalOutput()) else null
    }

    private fun systemClockNow(args: List<Any?>): Any? {
        requireArity("system-clock.now", args, 0)
        val now = wallClock.now()
        return instant(now.epochSeconds, now.nanosecondsOfSecond.toLong())
    }

    private fun systemClockResolution(args: List<Any?>): Any? {
        requireArity("system-clock.get-resolution", args, 0)
        return wallClockResolutionNanos
    }

    private fun monotonicClockNow(args: List<Any?>): Any? {
        requireArity("monotonic-clock.now", args, 0)
        return monotonicNow()
    }

    private fun monotonicClockResolution(args: List<Any?>): Any? {
        requireArity("monotonic-clock.get-resolution", args, 0)
        return monotonicResolutionNanos
    }

    private fun monotonicClockWaitUntil(args: List<Any?>): Any? {
        requireArity("monotonic-clock.wait-until", args, 1)
        val target = asU64(args[0])
        val now = monotonicNow()
        return if (compareUnsigned(target, now) > 0) {
            monotonicClockWait(target - now)
        } else {
            null
        }
    }

    private fun monotonicClockWaitFor(args: List<Any?>): Any? {
        requireArity("monotonic-clock.wait-for", args, 1)
        return monotonicClockWait(asU64(args[0]))
    }

    private fun monotonicClockWait(nanos: Long): Any? {
        if (nanos == 0L) {
            return null
        }
        val future = pendingFuture<Any?>()
        launchHostTask {
            delay(unsignedNanosDuration(nanos))
            completeFutureIfPresent(future, null)
        }
        return future
    }

    private fun timezoneIanaId(args: List<Any?>): Any? {
        requireArity("timezone.iana-id", args, 0)
        return wallClockTimeZone.id
    }

    private fun timezoneUtcOffset(args: List<Any?>): Any? {
        requireArity("timezone.utc-offset", args, 1)
        val instant = instantFromValue(args[0])
        return wallClockTimeZone.offsetAt(instant).totalSeconds * 1_000_000_000L
    }

    private fun timezoneDebugString(args: List<Any?>): Any? {
        requireArity("timezone.to-debug-string", args, 0)
        return wallClockTimeZone.id
    }

    private fun getRandomBytes(args: List<Any?>): Any? {
        requireArity("random.get-random-bytes", args, 1)
        return randomBytes(secureRandom, checkedByteLength(args[0]))
    }

    private fun getRandomU64(args: List<Any?>): Any? {
        requireArity("random.get-random-u64", args, 0)
        return randomLong(secureRandom)
    }

    private fun getInsecureRandomBytes(args: List<Any?>): Any? {
        requireArity("insecure.get-insecure-random-bytes", args, 1)
        return randomBytes(insecureRandom, checkedByteLength(args[0]))
    }

    private fun getInsecureRandomU64(args: List<Any?>): Any? {
        requireArity("insecure.get-insecure-random-u64", args, 0)
        return randomLong(insecureRandom)
    }

    private fun getInsecureSeed(args: List<Any?>): Any? {
        requireArity("insecure-seed.get-insecure-seed", args, 0)
        return listOf(insecureSeedLower, insecureSeedUpper)
    }

    private fun filesystemGetDirectories(args: List<Any?>): Any? {
        requireArity("filesystem.get-directories", args, 0)
        val result = ArrayList<List<Any?>>()
        for (preopen in preopens) {
            result.add(
                listOf(
                    descriptors.insertResource(
                        FilesystemDescriptor(
                            preopen.hostPath,
                            preopen.hostPath,
                            preopen.flags,
                            "directory",
                            fileIdentity(preopen.hostPath),
                        )
                    ),
                    preopen.guestPath,
                )
            )
        }
        return result
    }

    private fun filesystemReadViaStream(args: List<Any?>): Any? {
        requireArity("descriptor.read-via-stream", args, 2)
        return try {
            val descriptor = readableDescriptor(args, 0)
            val offset = asU64(args[1])
            if (offset < 0) {
                throw FsException("invalid")
            }
            val future = pendingFuture<Any?>()
            listOf(
                streamHandle(
                    StreamValue(
                        "filesystem-read",
                        SourceByteStream(
                            FileHandleByteSource(descriptor.fileHandle(), offset),
                            completion = filesystemStreamCompletion(future),
                        ),
                    )
                ),
                future.handle(),
            )
        } catch (e: FsException) {
            listOf(
                streamHandle(StreamValue("filesystem-read-error")),
                futureHandle(WitResult.err(e.code)),
            )
        } catch (e: IOException) {
            listOf(
                streamHandle(StreamValue("filesystem-read-error")),
                futureHandle(WitResult.err(filesystemError(e))),
            )
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                listOf(
                    streamHandle(StreamValue("filesystem-read-error")),
                    futureHandle(WitResult.err("not-permitted")),
                )
            } else {
                throw e
            }
        }
    }

    private fun filesystemWriteViaStream(args: List<Any?>): Any? {
        requireArity("descriptor.write-via-stream", args, 3)
        return try {
            val descriptor = writableDescriptor(args, 0)
            val offset = asU64(args[2])
            if (offset < 0) {
                throw FsException("invalid")
            }
            pipeFilesystemOutputStream(
                SeekableFileSink(descriptor, offset).asKotlinxIoRawSink(),
                handle(args, 1),
            )
        } catch (e: FsException) {
            futureHandle(WitResult.err(e.code))
        } catch (e: IOException) {
            futureHandle(WitResult.err(filesystemError(e)))
        } catch (e: ComponentModelException) {
            futureHandle(WitResult.err("unsupported"))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                futureHandle(WitResult.err("not-permitted"))
            } else {
                throw e
            }
        }
    }

    private fun filesystemAppendViaStream(args: List<Any?>): Any? {
        requireArity("descriptor.append-via-stream", args, 2)
        return try {
            val descriptor = writableDescriptor(args, 0)
            val offset = descriptorSize(descriptor)
            pipeFilesystemOutputStream(
                SeekableFileSink(descriptor, offset).asKotlinxIoRawSink(),
                handle(args, 1),
            )
        } catch (e: FsException) {
            futureHandle(WitResult.err(e.code))
        } catch (e: IOException) {
            futureHandle(WitResult.err(filesystemError(e)))
        } catch (e: ComponentModelException) {
            futureHandle(WitResult.err("unsupported"))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                futureHandle(WitResult.err("not-permitted"))
            } else {
                throw e
            }
        }
    }

    private fun filesystemAdvise(args: List<Any?>): Any? {
        requireArity("descriptor.advise", args, 4)
        return filesystemResult {
            val descriptor = descriptors.get(handle(args, 0))
            if (descriptor.directory) {
                throw FsException("bad-descriptor")
            }
            if (asU64(args[1]) < 0 || asU64(args[2]) < 0) {
                throw FsException("file-too-large")
            }
            null
        }
    }

    private fun filesystemSyncData(args: List<Any?>): Any? {
        requireArity("descriptor.sync-data", args, 1)
        return filesystemResult {
            syncDescriptor(descriptors.get(handle(args, 0)), false)
            null
        }
    }

    private fun filesystemGetFlags(args: List<Any?>): Any? {
        requireArity("descriptor.get-flags", args, 1)
        return filesystemResult { ArrayList(descriptors.get(handle(args, 0)).flags) }
    }

    private fun filesystemGetType(args: List<Any?>): Any? {
        requireArity("descriptor.get-type", args, 1)
        return filesystemResult { descriptors.get(handle(args, 0)).type }
    }

    private fun filesystemSetSize(args: List<Any?>): Any? {
        requireArity("descriptor.set-size", args, 2)
        return filesystemResult {
            val descriptor = writableDescriptor(args, 0)
            val size = asU64(args[1])
            if (size < 0) {
                throw FsException("invalid")
            }
            descriptor.fileHandle().resize(size)
            null
        }
    }

    private fun filesystemSetTimes(args: List<Any?>): Any? {
        requireArity("descriptor.set-times", args, 3)
        return filesystemResult {
            val descriptor = descriptors.get(handle(args, 0))
            val access = timestamp(args[1])
            val modified = timestamp(args[2])
            if (access != null || modified != null) {
                if (!descriptor.directory && fileSystem.metadataOrNull(descriptor.path) == null) {
                    throw FsException("unsupported")
                }
                setTimes(descriptor.path, access, modified, followSymlinks = true)
            }
            null
        }
    }

    private fun filesystemRead(args: List<Any?>): Any? {
        requireArity("descriptor.read", args, 3)
        return filesystemResult {
            val descriptor = readableDescriptor(args, 0)
            val length = checkedByteLength(args[1])
            val offset = asU64(args[2])
            if (offset < 0) {
                throw FsException("invalid")
            }
            val buffer = ByteArray(length)
            val read = descriptor.fileHandle().read(offset, buffer, 0, length)
            if (read < 0) {
                listOf(ByteArray(0), true)
            } else {
                listOf(buffer.copyOf(read), read < length)
            }
        }
    }

    private inner class FilesystemReadHostHandler : DirectHostHandler {
        override fun apply(arguments: List<Any?>): Any? = filesystemRead(arguments)

        override fun applyDirect(
            context: CanonicalAbi.Context,
            function: WitPackage.Function,
            flatArguments: LongArray,
            resultPointer: Int?,
            callContext: HostCallContext,
        ): DirectHostCallResult? {
            if (function.parameters().size != 3 || flatArguments.size != 3 || resultPointer == null) return null
            return try {
                filesystemReadDirect(context, flatArguments, resultPointer)
                DirectHostCallResult(value = null, resultsStored = true)
            } catch (_: FsException) {
                null
            } catch (_: IOException) {
                null
            } catch (error: Exception) {
                if (isWasiSecurityException(error)) null else throw error
            }
        }
    }

    private fun filesystemReadDirect(
        context: CanonicalAbi.Context,
        flatArguments: LongArray,
        resultPointer: Int,
    ) {
        val descriptor = readableDescriptor(flatArguments[0] and U32_MASK)
        val length = checkedFlatByteLength(flatArguments[1])
        val offset = flatArguments[2]
        if (offset < 0) {
            throw FsException("invalid")
        }
        val listPtr = if (length > 0) {
            context.reallocate(0, 0, 1, length)
        } else {
            0
        }
        val bytesRead = readMemoryBytes(descriptor, context, listPtr, length, offset)
        context.memory().writeI32(resultPointer, 0)
        context.memory().writeI32(resultPointer + 4, listPtr)
        context.memory().writeI32(resultPointer + 8, bytesRead)
        context.memory().writeI32(resultPointer + 12, if (bytesRead < length) 1 else 0)
    }

    private fun filesystemWrite(args: List<Any?>): Any? {
        requireArity("descriptor.write", args, 3)
        return filesystemResult {
            val descriptor = writableDescriptor(args, 0)
            val offset = asU64(args[2])
            if (offset < 0) {
                throw FsException("invalid")
            }
            writeBytes(descriptor, bytes(args[1]), offset)
        }
    }

    private inner class FilesystemWriteHostHandler : DirectHostHandler {
        override fun apply(arguments: List<Any?>): Any? = filesystemWrite(arguments)

        override fun applyDirect(
            context: CanonicalAbi.Context,
            function: WitPackage.Function,
            flatArguments: LongArray,
            resultPointer: Int?,
            callContext: HostCallContext,
        ): DirectHostCallResult? {
            if (function.parameters().size != 3 || flatArguments.size != 4) return null
            return DirectHostCallResult(filesystemWriteDirect(context, flatArguments))
        }
    }

    private fun filesystemWriteDirect(
        context: CanonicalAbi.Context,
        flatArguments: LongArray,
    ): Any? =
        filesystemResult {
            val descriptor = writableDescriptor(flatArguments[0] and U32_MASK)
            val ptr = flatArguments[1].toInt()
            val length = checkedFlatByteLength(flatArguments[2])
            val offset = flatArguments[3]
            if (offset < 0) {
                throw FsException("invalid")
            }
            writeMemoryBytes(descriptor, context, ptr, length, offset)
        }

    private fun filesystemReadDirectory(args: List<Any?>): Any? {
        requireArity("descriptor.read-directory", args, 1)
        return try {
            val descriptor = descriptors.get(handle(args, 0))
            if (!descriptor.directory) {
                throw FsException("not-directory")
            }
            val entries = ArrayList<Map<String, Any?>>()
            for (path in fileSystem.list(descriptor.path)) {
                entries.add(WitValue.record("type", descriptorType(path), "name", path.name))
            }
            val future = pendingFuture<Any?>()
            listOf(
                streamHandle(
                    StreamValue(
                        "filesystem-directory",
                        ObjectStreamBuffer(
                            entries,
                            writableDropped = true,
                            capacity = streamBufferCapacity,
                            completion = filesystemStreamCompletion(future),
                        ),
                    )
                ),
                future.handle(),
            )
        } catch (e: FsException) {
            listOf(
                streamHandle(StreamValue("filesystem-directory-error")),
                futureHandle(WitResult.err(e.code)),
            )
        } catch (e: IOException) {
            listOf(
                streamHandle(StreamValue("filesystem-directory-error")),
                futureHandle(WitResult.err(filesystemError(e))),
            )
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                listOf(
                    streamHandle(StreamValue("filesystem-directory-error")),
                    futureHandle(WitResult.err("not-permitted")),
                )
            } else {
                throw e
            }
        }
    }

    private fun filesystemSync(args: List<Any?>): Any? {
        requireArity("descriptor.sync", args, 1)
        return filesystemResult {
            syncDescriptor(descriptors.get(handle(args, 0)), true)
            null
        }
    }

    private fun filesystemCreateDirectoryAt(args: List<Any?>): Any? {
        requireArity("descriptor.create-directory-at", args, 2)
        val rawPath = string(args[1])
        return filesystemResult {
            val base = mutableDirectoryDescriptor(args, 0)
            val path = resolvePath(base, rawPath, false)
            if (fileSystem.exists(path)) {
                throw FsException("exist")
            }
            fileSystem.createDirectory(path, mustCreate = true)
            null
        }
    }

    private fun filesystemStat(args: List<Any?>): Any? {
        requireArity("descriptor.stat", args, 1)
        return filesystemResult { descriptorStat(descriptors.get(handle(args, 0))) }
    }

    private fun filesystemStatAt(args: List<Any?>): Any? {
        requireArity("descriptor.stat-at", args, 3)
        return filesystemResult {
            descriptorStat(
                resolvePath(
                    descriptors.get(handle(args, 0)),
                    string(args[2]),
                    flag(args[1], "symlink-follow"),
                )
            )
        }
    }

    private fun filesystemSetTimesAt(args: List<Any?>): Any? {
        requireArity("descriptor.set-times-at", args, 5)
        return filesystemResult {
            val path =
                resolvePath(
                    mutableDirectoryDescriptor(args, 0),
                    string(args[2]),
                    flag(args[1], "symlink-follow"),
                )
            setTimes(
                path,
                timestamp(args[3]),
                timestamp(args[4]),
                flag(args[1], "symlink-follow"),
            )
            null
        }
    }

    private fun filesystemLinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.link-at", args, 5)
        return filesystemResult {
            val followSymlinks = flag(args[1], "symlink-follow")
            val oldPath =
                resolvePath(
                    descriptors.get(handle(args, 0)),
                    string(args[2]),
                    followSymlinks,
                )
            val oldLinkPath =
                if (followSymlinks) {
                    fileSystem.canonicalize(oldPath)
                } else {
                    if (descriptorType(oldPath) == "symbolic-link") {
                        throw FsException("not-permitted")
                    }
                    oldPath
                }
            val newPath = resolvePath(mutableDirectoryDescriptor(args, 3), string(args[4]), false)
            createHardLink(oldLinkPath, newPath)
            null
        }
    }

    private fun filesystemOpenAt(args: List<Any?>): Any? {
        requireArity("descriptor.open-at", args, 5)
        return filesystemResult {
            val base = descriptors.get(handle(args, 0))
            val openFlags = args[3]
            val requestedDescriptorFlags = descriptorFlags(args[4])
            if (
                (flag(openFlags, "create") ||
                    flag(openFlags, "truncate") ||
                    requestedDescriptorFlags.contains("write") ||
                    requestedDescriptorFlags.contains("mutate-directory")) &&
                    !base.flags.contains("mutate-directory")
            ) {
                throw FsException("read-only")
            }
            val followSymlinks = flag(args[1], "symlink-follow")
            val path = resolvePath(base, string(args[2]), followSymlinks)
            if (flag(openFlags, "create") && flag(openFlags, "exclusive") && fileSystem.exists(path)) {
                throw FsException("exist")
            }
            if (flag(openFlags, "create") && !fileSystem.exists(path)) {
                fileSystem.openReadWrite(path, mustCreate = true).close()
            }
            if (!fileSystem.exists(path)) {
                throw FsException("no-entry")
            }
            val type = descriptorType(path)
            if (type == "symbolic-link" && !followSymlinks) {
                throw FsException("not-permitted")
            }
            val directory = type == "directory"
            if (flag(openFlags, "directory") && !directory) {
                throw FsException("not-directory")
            }
            val effectiveDescriptorFlags =
                effectiveDescriptorFlags(openFlags, requestedDescriptorFlags, directory)
            if (directory && effectiveDescriptorFlags.contains("write")) {
                throw FsException("is-directory")
            }
            if (flag(openFlags, "truncate")) {
                fileSystem.openReadWrite(path, mustExist = true).useHandle { handle ->
                    handle.resize(0L)
                }
            }
            val descriptorRoot = if (directory) path else base.root
            val fileHandle =
                if (directory) {
                    null
                } else {
                    openFileDescriptor(path, effectiveDescriptorFlags)
                }
            descriptors.insertResource(
                FilesystemDescriptor(
                    descriptorRoot,
                    path,
                    effectiveDescriptorFlags,
                    type,
                    fileIdentity(path),
                    fileHandle,
                )
            )
        }
    }

    private fun filesystemReadlinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.readlink-at", args, 2)
        return filesystemResult {
            val path = resolvePath(descriptors.get(handle(args, 0)), string(args[1]), false)
            val target = fileSystem.metadata(path).symlinkTarget ?: throw FsException("invalid")
            if (target.isAbsolute) {
                throw FsException("not-permitted")
            }
            target.toString()
        }
    }

    private fun filesystemRemoveDirectoryAt(args: List<Any?>): Any? {
        requireArity("descriptor.remove-directory-at", args, 2)
        val rawPath = string(args[1])
        return filesystemResult {
            if (rawPath == ".") {
                throw FsException("invalid")
            }
            val path = resolvePath(mutableDirectoryDescriptor(args, 0), rawPath, false)
            if (!isDirectory(path)) {
                throw FsException("not-directory")
            }
            fileSystem.delete(path, mustExist = true)
            null
        }
    }

    private fun filesystemRenameAt(args: List<Any?>): Any? {
        requireArity("descriptor.rename-at", args, 4)
        return filesystemResult {
            if (string(args[1]) == ".") {
                throw FsException("invalid")
            }
            val oldPath = resolvePath(mutableDirectoryDescriptor(args, 0), string(args[1]), false)
            val newPath = resolvePath(mutableDirectoryDescriptor(args, 2), string(args[3]), false)
            fileSystem.atomicMove(oldPath, newPath)
            null
        }
    }

    private fun filesystemSymlinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.symlink-at", args, 3)
        return filesystemResult {
            val oldPath = string(args[1]).toPath(normalize = true)
            if (oldPath.isAbsolute) {
                throw FsException("not-permitted")
            }
            val newPath = resolvePath(mutableDirectoryDescriptor(args, 0), string(args[2]), false)
            fileSystem.createSymlink(newPath, oldPath)
            null
        }
    }

    private fun filesystemUnlinkFileAt(args: List<Any?>): Any? {
        requireArity("descriptor.unlink-file-at", args, 2)
        return filesystemResult {
            val path = resolvePath(mutableDirectoryDescriptor(args, 0), string(args[1]), false)
            if (isDirectory(path)) {
                throw FsException("is-directory")
            }
            fileSystem.delete(path, mustExist = true)
            null
        }
    }

    private fun filesystemIsSameObject(args: List<Any?>): Any? {
        requireArity("descriptor.is-same-object", args, 2)
        if (handle(args, 0) == handle(args, 1)) {
            return true
        }
        val first = descriptors.get(handle(args, 0))
        val second = descriptors.get(handle(args, 1))
        if (first.identity != null && first.identity == second.identity) {
            return true
        }
        return try {
            wasiIsSameFile(
                fileSystem,
                first.path,
                second.path,
            )
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            fileSystem.canonicalize(first.path) == fileSystem.canonicalize(second.path)
        }
    }

    private fun filesystemMetadataHash(args: List<Any?>): Any? {
        requireArity("descriptor.metadata-hash", args, 1)
        return filesystemResult { metadataHash(descriptors.get(handle(args, 0))) }
    }

    private fun filesystemMetadataHashAt(args: List<Any?>): Any? {
        requireArity("descriptor.metadata-hash-at", args, 3)
        return filesystemResult {
            metadataHash(
                resolvePath(
                    descriptors.get(handle(args, 0)),
                    string(args[2]),
                    flag(args[1], "symlink-follow"),
                )
            )
        }
    }

    private fun filesystemReadDirectoryEntry(args: List<Any?>): Any? {
        requireArity("directory-entry-stream.read-directory-entry", args, 1)
        return filesystemResult {
            val stream = directoryEntryStreams.get(handle(args, 0))
            if (stream.entries.hasNext()) stream.entries.next() else null
        }
    }

    private fun resolveAddresses(args: List<Any?>): Any? {
        requireArity("resolve-addresses", args, 1)
        return nameLookupResult {
            requireRawSocketAccess()
            val hostname = string(args[0])
            val normalizedHostname = requireRawSocketHostnameAllowed(hostname)
            val rawAddresses = resolveIpAddresses(hostname)
            rememberResolvedRawSocketAddresses(normalizedHostname, rawAddresses)
            val addresses = rawAddresses.map { ipAddress(it) }
            if (addresses.isEmpty()) {
                throw NameLookupException("name-unresolvable")
            }
            addresses
        }
    }

    private fun tcpCreate(args: List<Any?>): Any? {
        requireArity("tcp-socket.create", args, 1)
        return socketResult {
            requireRawSocketAccess()
            tcpSockets.insertResource(TcpSocket(addressFamily(args[0])))
        }
    }

    private fun tcpBind(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.bind", args, 2)
        return socketResult {
            requireRawSocketAccess()
            if (socket.bound || socket.connected || socket.listening) {
                throw NetException("invalid-state")
            }
            val local = socketAddress(args[1])
            requireFamily(socket.family, local)
            requireRawSocketEndpointAllowed(local)
            val assigned = reserveTcpBindAddress(socket.family, local)
            socket.localAddress = assigned.address
            socket.boundAddressKey = assigned.key
            socket.globalBoundAddressKey = assigned.globalKey
            socket.bound = true
            socket.localPolicyAuthorized = true
            null
        }
    }

    private fun tcpConnect(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.connect", args, 2)
        val connection =
            socketResultValue {
                requireRawSocketAccess()
                if (socket.connected || socket.listening) {
                    throw NetException("invalid-state")
                }
                val remote = socketAddress(args[1])
                validateRemoteAddress(socket.family, remote)
                requireRawSocketEndpointAllowed(remote)
                remote
            }
        if (connection is WitResult.Err<*, *>) {
            return connection
        }
        val remote = (connection as WitResult.Ok<*, *>).value() as InetSocketAddress
        val suspendingRuntime = socketRuntime as? WasiSuspendingSocketRuntime
        if (suspendingRuntime != null) {
            val future = pendingFuture<Any?>()
            launchHostTask {
                completeFutureIfPresent(
                    future,
                    socketResultValueSuspending {
                        val tcpConnection =
                            suspendingRuntime.connectTcpSuspending(
                                remote,
                                socket.keepAlive,
                                socket.receiveBufferSize,
                                socket.sendBufferSize,
                            )
                        completeTcpConnect(socket, tcpConnection)
                        null
                    },
                )
            }
            return future
        }
        return socketResult {
            completeTcpConnect(
                socket,
                socketRuntime.connectTcp(
                    remote,
                    socket.keepAlive,
                    socket.receiveBufferSize,
                    socket.sendBufferSize,
                ),
            )
            null
        }
    }

    private fun completeTcpConnect(socket: TcpSocket, connection: WasiTcpConnection) {
        try {
            val remoteAddress = connection.remoteAddress
            val localAddress = connection.localAddress
            withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                if (socket.connected || socket.listening) {
                    throw NetException("invalid-state")
                }
                socket.connection = connection
                socket.remoteAddress = remoteAddress
                socket.localAddress = localAddress
                socket.bound = true
                socket.connected = true
            }
        } catch (failure: Throwable) {
            closeIgnoringFailure { connection.close() }
            throw failure
        }
    }

    private fun tcpListen(
        socket: TcpSocket,
        args: List<Any?>,
        context: HostCallContext,
    ): Any? {
        requireArity("tcp-socket.listen", args, 1)
        val validation =
            socketResultValue {
                requireRawSocketAccess()
                if (socket.connected || socket.listening) {
                    throw NetException("invalid-state")
                }
                val local = socket.localAddress ?: wildcardAddress(socket.family)
                if (!socket.localPolicyAuthorized) {
                    requireRawSocketEndpointAllowed(local)
                    socket.localPolicyAuthorized = true
                }
                local
            }
        if (validation is WitResult.Err<*, *>) {
            return validation
        }
        val local = (validation as WitResult.Ok<*, *>).value() as InetSocketAddress
        val suspendingRuntime = socketRuntime as? WasiSuspendingTcpListenRuntime
        if (context.isAsync && suspendingRuntime != null) {
            val future = pendingFuture<Any?>()
            launchHostTask {
                completeFutureIfPresent(
                    future,
                    socketResultValueSuspending {
                        completeTcpListen(
                            socket,
                            suspendingRuntime.listenTcpSuspending(local, socket.listenBacklog),
                        )
                    },
                )
            }
            return future
        }
        return socketResult {
            completeTcpListen(socket, socketRuntime.listenTcp(local, socket.listenBacklog))
        }
    }

    private fun completeTcpListen(socket: TcpSocket, listener: WasiTcpListener): Long {
        var addedLocalKey: SocketAddressKey? = null
        var addedGlobalKey: WasiPreview3TcpBindKey? = null
        try {
            return withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                if (socket.connected || socket.listening) {
                    throw NetException("invalid-state")
                }
                val localAddress = listener.localAddress
                if (socket.boundAddressKey == null) {
                    val localKey = socketAddressKey(socket.family, localAddress)
                    val globalKey = globalSocketAddressKey(localAddress)
                    if (!WasiPreview3TcpBindRegistry.reserveExact(globalKey)) {
                        throw NetException("address-in-use")
                    }
                    addedGlobalKey = globalKey
                    if (!tcpBoundAddresses.add(localKey)) {
                        throw NetException("address-in-use")
                    }
                    addedLocalKey = localKey
                    socket.boundAddressKey = localKey
                    socket.globalBoundAddressKey = globalKey
                }
                val streamHandle =
                    streamHandle(StreamValue("tcp-listener", TcpListenerStream(socket)))
                socket.listener = listener
                socket.localAddress = localAddress
                socket.bound = true
                socket.listening = true
                streamHandle
            }
        } catch (failure: Throwable) {
            addedLocalKey?.let { key ->
                tcpBoundAddresses.remove(key)
                if (socket.boundAddressKey == key) {
                    socket.boundAddressKey = null
                }
            }
            WasiPreview3TcpBindRegistry.release(addedGlobalKey)
            if (socket.globalBoundAddressKey == addedGlobalKey) {
                socket.globalBoundAddressKey = null
            }
            closeIgnoringFailure { listener.close() }
            throw failure
        }
    }

    private fun tcpAccept(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.accept", args, 1)
        val validation =
            socketResultValue {
                requireRawSocketAccess()
                if (!socket.listening || socket.listener == null) {
                    throw NetException("invalid-state")
                }
                null
            }
        if (validation is WitResult.Err<*, *>) {
            return validation
        }

        val future = pendingFuture<Any?>()
        launchHostTask {
            completeFutureIfPresent(
                future,
                socketResultValueSuspending {
                    tcpAcceptResult(acceptTcpConnectionSocketSuspending(socket))
                },
            )
        }
        return future
    }

    private fun tcpAcceptResult(accepted: TcpSocket): List<Any?> {
        try {
            val remoteAddress =
                socketAddress(accepted.remoteAddress ?: throw NetException("invalid-state"))
            return withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                listOf(tcpSockets.insertResource(accepted), remoteAddress)
            }
        } catch (failure: Throwable) {
            closeTcpSocket(accepted)
            throw failure
        }
    }

    private fun acceptTcpConnectionResource(socket: TcpSocket): WitResource<Nothing> {
        val accepted = acceptTcpConnectionSocket(socket)
        try {
            return withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                tcpSockets.insertResource(accepted)
            }
        } catch (failure: Throwable) {
            closeTcpSocket(accepted)
            throw failure
        }
    }

    private fun acceptTcpConnectionSocket(socket: TcpSocket): TcpSocket =
        acceptTcpConnectionSocketOrNull(socket, 1_000L) ?: throw NetException("timeout")

    private fun acceptTcpConnectionSocketOrNull(socket: TcpSocket, timeoutMillis: Long): TcpSocket? {
        requireRawSocketAccess()
        if (!socket.listening || socket.listener == null) {
            throw NetException("invalid-state")
        }
        val accepted = socket.listener!!.accept(timeoutMillis) ?: return null
        return tcpSocketFromConnection(socket, accepted)
    }

    private suspend fun acceptTcpConnectionSocketSuspending(socket: TcpSocket): TcpSocket {
        requireRawSocketAccess()
        if (!socket.listening || socket.listener == null) {
            throw NetException("invalid-state")
        }
        return tcpSocketFromConnection(socket, socket.listener!!.accept())
    }

    private fun tcpSocketFromConnection(parent: TcpSocket, accepted: WasiTcpConnection): TcpSocket {
        try {
            val localAddress = accepted.localAddress
            val remoteAddress = accepted.remoteAddress
            val child = TcpSocket(parent.family)
            child.inheritConnectionOptionsFrom(parent)
            child.connection = accepted
            child.bound = true
            child.connected = true
            child.localAddress = localAddress
            child.remoteAddress = remoteAddress
            return child
        } catch (failure: Throwable) {
            closeIgnoringFailure { accepted.close() }
            throw failure
        }
    }

    private fun tcpSend(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.send", args, 2)
        val send =
            socketResultValue {
                requireRawSocketAccess()
                val connection = socket.connection
                if (!socket.connected || connection == null || socket.sendConsumed) {
                    throw NetException("invalid-state")
                }
                socket.sendConsumed = true
                PendingTcpSend(connection, handle(args, 1))
            }
        if (send is WitResult.Err<*, *>) {
            return send
        }
        val (connection, streamHandle) = (send as WitResult.Ok<*, *>).value() as PendingTcpSend
        return sendTcpOutputStream(connection, streamHandle)
    }

    private fun tcpReceive(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.receive", args, 1)
        return try {
            requireRawSocketAccess()
            val connection = socket.connection
            if (!socket.connected || connection == null || socket.receiveConsumed) {
                throw NetException("invalid-state")
            }
            socket.receiveConsumed = true
            val completion = pendingFuture<Any?>()
            listOf(
                streamHandle(StreamValue("tcp-receive", TcpReceiveStream(connection, completion))),
                completion.handle(),
            )
        } catch (e: NetException) {
            listOf(
                streamHandle(StreamValue("tcp-receive-error")),
                futureHandle(WitResult.err(e.code)),
            )
        } catch (e: Exception) {
            listOf(
                streamHandle(StreamValue("tcp-receive-error")),
                futureHandle(WitResult.err(socketExceptionCode(e))),
            )
        }
    }

    private fun tcpLocalAddress(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-local-address", args, 1)
        return socketResult { tcpLocalAddressValue(socket) }
    }

    private fun tcpRemoteAddress(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-remote-address", args, 1)
        return socketResult { tcpRemoteAddressValue(socket) }
    }

    private fun tcpLocalAddressValue(socket: TcpSocket): Any {
        val address =
            when {
                socket.listener != null -> socket.listener!!.localAddress
                socket.connection != null -> socket.connection!!.localAddress
                else -> socket.localAddress
            }
        return socketAddress(
            normalizeLocalAddress(socket.family, address ?: throw NetException("invalid-state"))
        )
    }

    private fun tcpRemoteAddressValue(socket: TcpSocket): Any {
        val address =
            if (socket.connection != null) socket.connection!!.remoteAddress
            else socket.remoteAddress
        return socketAddress(address ?: throw NetException("invalid-state"))
    }

    private fun tcpIsListening(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-is-listening", args, 1)
        return socket.listening
    }

    private fun tcpAddressFamily(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-address-family", args, 1)
        return socket.family.label
    }

    private fun tcpSetListenBacklogSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-listen-backlog-size", args, 2)
        return socketResult {
            val value = positiveInt(args[1])
            socket.listenBacklog = value
            null
        }
    }

    private fun tcpKeepAliveEnabled(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-keep-alive-enabled", args, 1)
        return socketResult { socket.keepAlive }
    }

    private fun tcpSetKeepAliveEnabled(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-keep-alive-enabled", args, 2)
        return socketResult {
            socket.keepAlive = args[1] == true
            null
        }
    }

    private fun tcpKeepAliveIdleTime(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-keep-alive-idle-time", args, 1)
        return socketResult { socket.keepAliveIdleTimeNanos }
    }

    private fun tcpSetKeepAliveIdleTime(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-keep-alive-idle-time", args, 2)
        return socketResult {
            socket.keepAliveIdleTimeNanos = positiveLong(args[1])
            null
        }
    }

    private fun tcpKeepAliveInterval(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-keep-alive-interval", args, 1)
        return socketResult { socket.keepAliveIntervalNanos }
    }

    private fun tcpSetKeepAliveInterval(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-keep-alive-interval", args, 2)
        return socketResult {
            socket.keepAliveIntervalNanos = positiveLong(args[1])
            null
        }
    }

    private fun tcpKeepAliveCount(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-keep-alive-count", args, 1)
        return socketResult { socket.keepAliveCount }
    }

    private fun tcpSetKeepAliveCount(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-keep-alive-count", args, 2)
        return socketResult {
            val count = asU64(args[1])
            if (count == 0L || count > 0xffff_ffffL) {
                throw NetException("invalid-argument")
            }
            socket.keepAliveCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            null
        }
    }

    private fun tcpHopLimit(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-hop-limit", args, 1)
        return socketResult { socket.hopLimit }
    }

    private fun tcpSetHopLimit(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-hop-limit", args, 2)
        return socketResult {
            socket.hopLimit = positiveByte(args[1])
            null
        }
    }

    private fun tcpReceiveBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-receive-buffer-size", args, 1)
        return socketResult { socket.receiveBufferSize.toLong() }
    }

    private fun tcpSetReceiveBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-receive-buffer-size", args, 2)
        return socketResult {
            socket.receiveBufferSize = positiveInt(args[1])
            null
        }
    }

    private fun tcpSendBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.get-send-buffer-size", args, 1)
        return socketResult { socket.sendBufferSize.toLong() }
    }

    private fun tcpSetSendBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-send-buffer-size", args, 2)
        return socketResult {
            socket.sendBufferSize = positiveInt(args[1])
            null
        }
    }

    private fun udpCreate(args: List<Any?>): Any? {
        requireArity("udp-socket.create", args, 1)
        return socketResult {
            requireRawSocketAccess()
            val family = addressFamily(args[0])
            udpSockets.insertResource(UdpSocket(family))
        }
    }

    private fun udpBind(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.bind", args, 2)
        return socketResult {
            requireRawSocketAccess()
            if (socket.bound) {
                throw NetException("invalid-state")
            }
            val local = socketAddress(args[1])
            requireFamily(socket.family, local)
            validateLocalBindAddress(socket.family, local)
            requireRawSocketEndpointAllowed(local)
            val endpoint =
                socketRuntime.bindUdp(local, socket.receiveBufferSize, socket.sendBufferSize)
            attachUdpEndpoint(socket, endpoint)
            null
        }
    }

    private fun udpConnect(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.connect", args, 2)
        return socketResult {
            requireRawSocketAccess()
            val remote = socketAddress(args[1])
            validateRemoteAddress(socket.family, remote)
            requireRawSocketEndpointAllowed(remote)
            if (!socket.bound && socket.endpoint == null) {
                socket.localAddress = addressWithPort(remote, 0)
            }
            val endpoint = udpEndpoint(socket)
            socket.remoteAddress = remote
            socket.localAddress = endpoint.localAddress
            socket.bound = true
            null
        }
    }

    private fun udpDisconnect(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.disconnect", args, 1)
        return socketResult {
            if (socket.remoteAddress == null) {
                throw NetException("invalid-state")
            }
            socket.remoteAddress = null
            null
        }
    }

    private fun udpSend(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.send", args, 3)
        val send =
            socketResultValue {
                requireRawSocketAccess()
                val data = bytes(args[1])
                if (data.size > 65_535) {
                    throw NetException("datagram-too-large")
                }
                val remoteValue = option(args[2])
                val remote =
                    if (remoteValue == null) socket.remoteAddress
                    else socketAddress(remoteValue).also { validateRemoteAddress(socket.family, it) }
                if (remote == null) {
                    throw NetException("invalid-argument")
                }
                requireRawSocketEndpointAllowed(remote)
                PendingUdpSend(udpEndpoint(socket), data, remote)
            }
        if (send is WitResult.Err<*, *>) {
            return send
        }
        val (endpoint, data, remote) = (send as WitResult.Ok<*, *>).value() as PendingUdpSend
        if (endpoint is WasiSuspendingUdpEndpoint) {
            val future = pendingFuture<Any?>()
            launchHostTask {
                completeFutureIfPresent(
                    future,
                    socketResultValueSuspending {
                        endpoint.sendSuspending(data, remote)
                        socket.localAddress = endpoint.localAddress
                        socket.bound = true
                        null
                    },
                )
            }
            return future
        }
        return socketResult {
            endpoint.send(data, remote)
            socket.localAddress = endpoint.localAddress
            socket.bound = true
            null
        }
    }

    private fun udpReceive(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.receive", args, 1)
        val receive =
            socketResultValue {
                requireRawSocketAccess()
                val endpoint = socket.endpoint
                if (!socket.bound || endpoint == null) {
                    throw NetException("invalid-state")
                }
                endpoint
            }
        if (receive is WitResult.Err<*, *>) {
            return receive
        }
        val endpoint = (receive as WitResult.Ok<*, *>).value()
        if (endpoint is WasiSuspendingUdpEndpoint) {
            val future = pendingFuture<Any?>()
            launchHostTask {
                completeFutureIfPresent(
                    future,
                    socketResultValueSuspending {
                        val datagram =
                            endpoint.receiveSuspending(1_000L) ?: throw NetException("timeout")
                        listOf(datagram.data, socketAddress(datagram.remoteAddress))
                    },
                )
            }
            return future
        }
        return socketResult {
            val datagram =
                (endpoint as WasiUdpEndpoint).receive(1_000L) ?: throw NetException("timeout")
            listOf(datagram.data, socketAddress(datagram.remoteAddress))
        }
    }

    private fun udpLocalAddress(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-local-address", args, 1)
        return socketResult {
            val address =
                if (socket.endpoint != null) socket.endpoint!!.localAddress else socket.localAddress
            socketAddress(
                normalizeLocalAddress(socket.family, address ?: throw NetException("invalid-state"))
            )
        }
    }

    private fun udpRemoteAddress(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-remote-address", args, 1)
        return socketResult {
            socketAddress(socket.remoteAddress ?: throw NetException("invalid-state"))
        }
    }

    private fun udpAddressFamily(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-address-family", args, 1)
        return socket.family.label
    }

    private fun udpUnicastHopLimit(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-unicast-hop-limit", args, 1)
        return socketResult { socket.unicastHopLimit }
    }

    private fun udpSetUnicastHopLimit(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-unicast-hop-limit", args, 2)
        return socketResult {
            socket.unicastHopLimit = positiveByte(args[1])
            null
        }
    }

    private fun udpReceiveBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-receive-buffer-size", args, 1)
        return socketResult { socket.receiveBufferSize.toLong() }
    }

    private fun udpSetReceiveBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-receive-buffer-size", args, 2)
        return socketResult {
            socket.receiveBufferSize = positiveInt(args[1])
            null
        }
    }

    private fun udpSendBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.get-send-buffer-size", args, 1)
        return socketResult { socket.sendBufferSize.toLong() }
    }

    private fun udpSetSendBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-send-buffer-size", args, 2)
        return socketResult {
            socket.sendBufferSize = positiveInt(args[1])
            null
        }
    }

    private fun clientSend(args: List<Any?>): Any? {
        requireArity("client.send", args, 1)
        if (!canAttemptHttpRequests()) {
            return WitResult.err("HTTP-request-denied")
        }
        val request = requests.get(handle(args, 0))
        val readyBody = request.body.completedBytesResult()
        if (readyBody != null && httpClient !is WasiSuspendingHttpClient) {
            return clientSendResult(request, readyBody)
        }
        val future = pendingFuture<Any?>()
        launchHostTask {
            completeFutureIfPresent(future, clientSendResultSuspending(request))
        }
        return future
    }

    private fun handlerHandle(args: List<Any?>): Any? {
        requireArity("handler.handle", args, 1)
        val request = requests.get(handle(args, 0))
        val readyBody = request.body.completedBytesResult()
        if (readyBody != null) {
            return handlerHandleResult(request, readyBody)
        }
        val future = pendingFuture<Any?>()
        launchHostTask {
            completeFutureIfPresent(future, handlerHandleResultSuspending(request))
        }
        return future
    }

    private fun fieldsConstructor(args: List<Any?>): Any? {
        requireArity("fields.constructor", args, 0)
        return fields.insertResource(HttpFields(true))
    }

    private fun fieldsFromList(args: List<Any?>): Any? {
        requireArity("fields.from-list", args, 1)
        return try {
            val result = HttpFields(true)
            for (entry in list(args[0])) {
                result.append(stringValue(tupleValue(entry, 0)), bytes(tupleValue(entry, 1)))
            }
            WitResult.ok(fields.insertResource(result))
        } catch (e: HeaderException) {
            WitResult.err(e.code)
        }
    }

    private fun fieldsGet(args: List<Any?>): Any? {
        requireArity("fields.get", args, 2)
        return fields.get(handle(args, 0)).get(string(args[1]))
    }

    private fun fieldsHas(args: List<Any?>): Any? {
        requireArity("fields.has", args, 2)
        return fields.get(handle(args, 0)).has(string(args[1]))
    }

    private fun fieldsSet(args: List<Any?>): Any? {
        requireArity("fields.set", args, 3)
        return fieldMutation("fields.set", handle(args, 0)) {
            it.set(string(args[1]), list(args[2]).map { value -> bytes(value) })
        }
    }

    private fun fieldsDelete(args: List<Any?>): Any? {
        requireArity("fields.delete", args, 2)
        return fieldMutation("fields.delete", handle(args, 0)) { it.delete(string(args[1])) }
    }

    private fun fieldsGetAndDelete(args: List<Any?>): Any? {
        requireArity("fields.get-and-delete", args, 2)
        return try {
            WitResult.ok(fields.get(handle(args, 0)).getAndDelete(string(args[1])))
        } catch (e: HeaderException) {
            WitResult.err(e.code)
        }
    }

    private fun fieldsAppend(args: List<Any?>): Any? {
        requireArity("fields.append", args, 3)
        return fieldMutation("fields.append", handle(args, 0)) {
            it.append(string(args[1]), bytes(args[2]))
        }
    }

    private fun fieldsCopyAll(args: List<Any?>): Any? {
        requireArity("fields.copy-all", args, 1)
        return fields.get(handle(args, 0)).entries()
    }

    private fun fieldsClone(args: List<Any?>): Any? {
        requireArity("fields.clone", args, 1)
        return fields.insertResource(fields.get(handle(args, 0)).copy(true))
    }

    private fun requestNew(args: List<Any?>): Any? {
        requireArity("request.new", args, 4)
        val headers = fields.get(handle(args, 0)).copy(false)
        val body = httpBodyFromOption(args[1])
        val trailers = resolveTrailersFuture(args[2])
        val options = optionHandle(args[3])?.let { requestOptions.get(it).copy(false) }
        val request = HttpRequest("get", null, null, null, headers, options, body, trailers)
        return listOf(requests.insertResource(request), body.future.handle())
    }

    private fun requestMethod(args: List<Any?>): Any? {
        requireArity("request.get-method", args, 1)
        return requests.get(handle(args, 0)).method
    }

    private fun requestSetMethod(args: List<Any?>): Any? {
        requireArity("request.set-method", args, 2)
        val method = httpMethod(args[1])
        val methodName =
            try {
                httpMethodName(method)
            } catch (_: HttpException) {
                return WitResult.err(null)
            }
        if (!isValidHttpMethodName(methodName)) {
            return WitResult.err(null)
        }
        requests.get(handle(args, 0)).method = method
        return WitResult.ok(null)
    }

    private fun requestPath(args: List<Any?>): Any? {
        requireArity("request.get-path-with-query", args, 1)
        return requests.get(handle(args, 0)).pathWithQuery
    }

    private fun requestSetPath(args: List<Any?>): Any? {
        requireArity("request.set-path-with-query", args, 2)
        val pathWithQuery = optionString(args[1])
        if (pathWithQuery != null && !isValidPathWithQuery(pathWithQuery)) {
            return WitResult.err(null)
        }
        requests.get(handle(args, 0)).pathWithQuery =
            if (pathWithQuery == "") "/" else pathWithQuery
        return WitResult.ok(null)
    }

    private fun requestScheme(args: List<Any?>): Any? {
        requireArity("request.get-scheme", args, 1)
        return requests.get(handle(args, 0)).scheme
    }

    private fun requestSetScheme(args: List<Any?>): Any? {
        requireArity("request.set-scheme", args, 2)
        val scheme = option(args[1])?.let { httpScheme(it) }
        if (scheme != null && !isValidHttpSchemeName(httpSchemeName(scheme))) {
            return WitResult.err(null)
        }
        requests.get(handle(args, 0)).scheme = scheme
        return WitResult.ok(null)
    }

    private fun requestAuthority(args: List<Any?>): Any? {
        requireArity("request.get-authority", args, 1)
        return requests.get(handle(args, 0)).authority
    }

    private fun requestSetAuthority(args: List<Any?>): Any? {
        requireArity("request.set-authority", args, 2)
        val authority = optionString(args[1])
        if (authority != null && !isValidAuthority(authority)) {
            return WitResult.err(null)
        }
        requests.get(handle(args, 0)).authority = authority
        return WitResult.ok(null)
    }

    private fun requestOptions(args: List<Any?>): Any? {
        requireArity("request.get-options", args, 1)
        val options = requests.get(handle(args, 0)).options ?: return null
        return requestOptions.insertResource(options.copy(false))
    }

    private fun requestHeaders(args: List<Any?>): Any? {
        requireArity("request.get-headers", args, 1)
        return fields.insertResource(requests.get(handle(args, 0)).headers.copy(false))
    }

    private fun requestConsumeBody(args: List<Any?>): Any? {
        requireArity("request.consume-body", args, 2)
        val request = requests.remove(handle(args, 0))
        return listOf(
            streamHandle(StreamValue("request-body", request.body.streamData)),
            trailerFutureHandle(request.trailers),
        )
    }

    private fun requestOptionsConstructor(args: List<Any?>): Any? {
        requireArity("request-options.constructor", args, 0)
        return requestOptions.insertResource(RequestOptions(true))
    }

    private fun requestOptionsConnect(args: List<Any?>): Any? {
        requireArity("request-options.get-connect-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).connectTimeout
    }

    private fun requestOptionsSetConnect(args: List<Any?>): Any? {
        requireArity("request-options.set-connect-timeout", args, 2)
        return requestOptionsMutation(handle(args, 0)) { it.connectTimeout = optionU64(args[1]) }
    }

    private fun requestOptionsFirstByte(args: List<Any?>): Any? {
        requireArity("request-options.get-first-byte-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).firstByteTimeout
    }

    private fun requestOptionsSetFirstByte(args: List<Any?>): Any? {
        requireArity("request-options.set-first-byte-timeout", args, 2)
        return requestOptionsMutation(handle(args, 0)) { it.firstByteTimeout = optionU64(args[1]) }
    }

    private fun requestOptionsBetweenBytes(args: List<Any?>): Any? {
        requireArity("request-options.get-between-bytes-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).betweenBytesTimeout
    }

    private fun requestOptionsSetBetweenBytes(args: List<Any?>): Any? {
        requireArity("request-options.set-between-bytes-timeout", args, 2)
        return requestOptionsMutation(handle(args, 0)) {
            it.betweenBytesTimeout = optionU64(args[1])
        }
    }

    private fun requestOptionsClone(args: List<Any?>): Any? {
        requireArity("request-options.clone", args, 1)
        return requestOptions.insertResource(requestOptions.get(handle(args, 0)).copy(true))
    }

    private fun responseNew(args: List<Any?>): Any? {
        requireArity("response.new", args, 3)
        val headers = fields.get(handle(args, 0)).copy(false)
        val body = httpBodyFromOption(args[1])
        val trailers = resolveTrailersFuture(args[2])
        return listOf(
            responses.insertResource(HttpResponse(200, headers, body, true, trailers)),
            body.future.handle(),
        )
    }

    private fun responseStatus(args: List<Any?>): Any? {
        requireArity("response.get-status-code", args, 1)
        return responses.get(handle(args, 0)).status
    }

    private fun responseSetStatus(args: List<Any?>): Any? {
        requireArity("response.set-status-code", args, 2)
        val status = asU64(args[1]).toInt()
        if (status < 100 || status > 599) {
            return WitResult.err(null)
        }
        responses.get(handle(args, 0)).status = status
        return WitResult.ok(null)
    }

    private fun responseHeaders(args: List<Any?>): Any? {
        requireArity("response.get-headers", args, 1)
        return fields.insertResource(responses.get(handle(args, 0)).headers.copy(false))
    }

    private fun responseConsumeBody(args: List<Any?>): Any? {
        requireArity("response.consume-body", args, 2)
        val response = responses.remove(handle(args, 0))
        return listOf(
            streamHandle(StreamValue("response-body", responseBodyStreamData(response.body))),
            trailerFutureHandle(response.trailers),
        )
    }

    private fun requestSnapshot(
        request: HttpRequest,
        bodyResult: WitResult<ByteArray, Any?>,
    ): WasiPreview2.HttpRequestSnapshot {
        val body = httpBodyBytesOrThrow(bodyResult)
        return WasiPreview2.HttpRequestSnapshot(
            httpMethodName(request.method),
            request.pathWithQuery ?: "",
            httpSchemeName(request.scheme),
            request.authority ?: "",
            headersSnapshot(request.headers),
            body,
        )
    }

    private fun responseResource(response: WasiPreview2.HttpResponseSnapshot): WitResource<Nothing> =
        responses.insertResource(
            HttpResponse(
                response.statusCode(),
                fieldsFromByteHeaders(response.headers(), false),
                response.body(),
                response.bodyFinished(),
                emptyTrailers(),
            )
        )

    private fun responseSnapshot(handle: Long): WasiPreview2.HttpResponseSnapshot {
        val response = responses.get(handle)
        return WasiPreview2.HttpResponseSnapshot(
            response.status,
            headersSnapshot(response.headers),
            responseBodyBytes(response.body),
            response.bodyFinished,
        )
    }

    private fun liftResponseResult(result: Any?): Long {
        if (result !is WitValue.Variant) {
            throw ComponentModelException(
                "wasi:http/handler.handle returned non-result value $result"
            )
        }
        if (result.label() == "err") {
            throw ComponentModelException(
                "wasi:http/handler.handle returned error ${result.value()}"
            )
        }
        if (result.label() != "ok") {
            throw ComponentModelException(
                "wasi:http/handler.handle returned unexpected result $result"
            )
        }
        return handle(result.value())
    }

    private fun fieldMutation(
        functionName: String,
        fieldsHandle: Long,
        block: (HttpFields) -> Unit,
    ): Any? =
        try {
            block(fields.get(fieldsHandle))
            WitResult.ok(null)
        } catch (e: HeaderException) {
            WitResult.err(e.code)
        }

    private fun requestOptionsMutation(handle: Long, block: (RequestOptions) -> Unit): Any? {
        val options = requestOptions.get(handle)
        if (!options.mutable) {
            return WitResult.err("immutable")
        }
        block(options)
        return WitResult.ok(null)
    }

    private fun filesystemResult(operation: () -> Any?): Any? =
        try {
            WitResult.ok(operation())
        } catch (e: FsException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(filesystemError(e))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) WitResult.err("not-permitted") else throw e
        }

    private fun readableDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        return readableDescriptor(handle(args, index))
    }

    private fun readableDescriptor(handle: Long): FilesystemDescriptor {
        val descriptor = descriptors.get(handle)
        if (!descriptor.flags.contains("read")) {
            throw FsException("bad-descriptor")
        }
        if (descriptor.directory) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun writableDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        return writableDescriptor(handle(args, index))
    }

    private fun writableDescriptor(handle: Long): FilesystemDescriptor {
        val descriptor = descriptors.get(handle)
        if (!descriptor.flags.contains("write")) {
            throw FsException("bad-descriptor")
        }
        if (descriptor.directory) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun mutableDirectoryDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        val descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("mutate-directory")) {
            throw FsException("read-only")
        }
        if (!descriptor.directory) {
            throw FsException("not-directory")
        }
        return descriptor
    }

    private fun descriptorFlags(value: Any?): Set<String> {
        val result = LinkedHashSet<String>()
        for (flagName in
            listOf(
                "read",
                "write",
                "file-integrity-sync",
                "data-integrity-sync",
                "requested-write-sync",
                "mutate-directory",
            )) {
            if (flag(value, flagName)) {
                result.add(flagName)
            }
        }
        return result
    }

    private fun flag(value: Any?, name: String): Boolean {
        if (value is Map<*, *>) {
            return value[name] == true || value[memberName(name)] == true
        }
        if (value is Iterable<*>) {
            for (item in value) {
                if (name == stringValue(item)) {
                    return true
                }
            }
            return false
        }
        if (value is Number) {
            val bit = flagBit(name)
            return bit >= 0 && ((value.toLong() ushr bit) and 1L) != 0L
        }
        return false
    }

    private fun memberName(name: String): String {
        val out = StringBuilder()
        var upper = false
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                out.append(if (upper) ch.uppercaseChar() else ch)
                upper = false
            } else {
                upper = true
            }
        }
        return out.toString()
    }

    private fun flagBit(name: String): Int =
        when (name) {
            "read",
            "create",
            "symlink-follow" -> 0
            "write",
            "directory" -> 1
            "file-integrity-sync",
            "exclusive" -> 2
            "data-integrity-sync",
            "truncate" -> 3
            "requested-write-sync" -> 4
            "mutate-directory" -> 5
            else -> -1
        }

    private fun readAllBytes(path: Path): ByteArray = fileSystem.read(path) { readByteArray() }

    private fun readAllBytes(descriptor: FilesystemDescriptor): ByteArray {
        val size = descriptorSize(descriptor)
        if (size == 0L) {
            return ByteArray(0)
        }
        if (size > Int.MAX_VALUE) {
            throw FsException("file-too-large")
        }
        val result = ByteArray(size.toInt())
        var offset = 0
        while (offset < result.size) {
            val read = descriptor.fileHandle().read(offset.toLong(), result, offset, result.size - offset)
            if (read < 0) {
                break
            }
            offset += read
        }
        return if (offset == result.size) result else result.copyOf(offset)
    }

    private fun isDirectory(path: Path): Boolean =
        fileSystem.metadataOrNull(path)?.isDirectory == true

    private fun isInsidePreopen(root: Path, path: Path): Boolean {
        val rootText = root.normalized().toString().trimEnd('/')
        val pathText = path.normalized().toString()
        return pathText == rootText || pathText.startsWith("$rootText/")
    }

    private fun resolvePath(
        base: FilesystemDescriptor,
        rawPath: String,
        followLast: Boolean,
    ): Path {
        if (rawPath.isEmpty()) {
            throw FsException("no-entry")
        }
        if (rawPath.split('/').any { it == ".." }) {
            throw FsException("not-permitted")
        }
        val raw = rawPath.toPath(normalize = true)
        if (raw.isAbsolute) {
            throw FsException("not-permitted")
        }
        if (!base.directory) {
            throw FsException("not-directory")
        }
        val candidate = base.path.resolve(raw, normalize = true)
        val rootReal = fileSystem.canonicalize(base.root)
        val checked = realPathForSandboxCheck(candidate, followLast || rawPath.hasTrailingSeparator())
        if (!isInsidePreopen(rootReal, checked)) {
            throw FsException("not-permitted")
        }
        return candidate
    }

    private fun String.hasTrailingSeparator(): Boolean =
        length > 1 && last() == '/'

    private fun realPathForSandboxCheck(path: Path, followLast: Boolean): Path {
        if (followLast) {
            return realPathAllowingMissingLeaf(path)
        }
        val normalized = path.normalized()
        val parent = normalized.parent ?: return normalized
        return realPathAllowingMissingLeaf(parent).resolve(normalized.name, normalize = true)
    }

    private fun realPathAllowingMissingLeaf(path: Path): Path {
        if (fileSystem.exists(path)) {
            return fileSystem.canonicalize(path)
        }

        val missing = ArrayList<Path>()
        var current: Path? = path.normalized()
        while (current != null && !fileSystem.exists(current)) {
            missing.add(current.name.toPath())
            current = current.parent
        }

        if (current == null) {
            return path.normalized()
        }

        var resolved = fileSystem.canonicalize(current)
        for (i in missing.size - 1 downTo 0) {
            resolved = resolved.resolve(missing[i], normalize = true)
        }
        return resolved.normalized()
    }

    private fun descriptorStat(path: Path): Map<String, Any?> {
        val metadata = fileSystem.metadata(path)
        return descriptorStatRecord(descriptorType(path), metadata.size ?: 0L, metadata)
    }

    private fun descriptorStat(descriptor: FilesystemDescriptor): Map<String, Any?> {
        if (descriptor.directory) {
            return descriptorStat(descriptor.path)
        }
        val metadata = fileSystem.metadataOrNull(descriptor.path)
        return descriptorStatRecord(descriptor.type, descriptorSize(descriptor), metadata)
    }

    private fun descriptorStatRecord(
        type: Any,
        size: Long,
        metadata: okio.FileMetadata?,
    ): Map<String, Any?> =
        WitValue.record(
            "type",
            type,
            "link-count",
            1L,
            "size",
            size,
            "data-access-timestamp",
            WitValue.variant("some", datetime(metadata?.lastAccessedAtMillis)),
            "data-modification-timestamp",
            WitValue.variant("some", datetime(metadata?.lastModifiedAtMillis)),
            "status-change-timestamp",
            WitValue.variant("some", datetime(metadata?.lastModifiedAtMillis)),
        )

    private fun metadataHash(path: Path): Map<String, Any?> {
        val metadata = fileSystem.metadata(path)
        return metadataHash(path, metadata.size ?: 0L, metadata, descriptorType(path))
    }

    private fun metadataHash(descriptor: FilesystemDescriptor): Map<String, Any?> {
        if (descriptor.directory) {
            return metadataHash(descriptor.path)
        }
        return metadataHash(
            descriptor.path,
            descriptorSize(descriptor),
            fileSystem.metadataOrNull(descriptor.path),
            descriptor.type,
        )
    }

    private fun metadataHash(
        path: Path,
        size: Long,
        metadata: okio.FileMetadata?,
        type: Any,
    ): Map<String, Any?> {
        val lower =
            hashValues(
                    path.normalized().toString(),
                    size,
                    metadata?.lastModifiedAtMillis ?: 0L,
                )
                .toLong()
        val upper =
            hashValues(
                    metadata?.createdAtMillis ?: 0L,
                    metadata?.isDirectory ?: (type == "directory"),
                    metadata?.isRegularFile ?: (type == "regular-file"),
                    metadata?.symlinkTarget?.toString(),
                )
                .toLong()
        return WitValue.record("lower", lower, "upper", upper)
    }

    private fun datetime(timeMillis: Long?): Map<String, Any?> {
        val millis = timeMillis ?: 0L
        val seconds = floorDiv(millis, 1_000L)
        val nanos = floorMod(millis, 1_000L) * 1_000_000L
        return instant(seconds, nanos)
    }

    private fun descriptorType(path: Path): Any {
        val metadata = fileSystem.metadata(path)
        if (metadata.symlinkTarget != null) {
            return "symbolic-link"
        }
        if (metadata.isDirectory) {
            return "directory"
        }
        if (metadata.isRegularFile) {
            return "regular-file"
        }
        return WitValue.variant("other", "unknown")
    }

    private fun syncDescriptor(descriptor: FilesystemDescriptor, metadata: Boolean) {
        if (descriptor.directory) {
            return
        }
        if (descriptor.flags.contains("write")) {
            descriptor.fileHandle().flush()
        }
    }

    private fun fileIdentity(path: Path): Any? =
        try {
            wasiFileIdentity(fileSystem, path)
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun setTimes(
        path: Path,
        access: KotlinInstant?,
        modified: KotlinInstant?,
        followSymlinks: Boolean,
    ) {
        if (access != null || modified != null) {
            try {
                wasiSetFileTimes(fileSystem, path, access, modified, followSymlinks)
            } catch (_: UnsupportedOperationException) {
                throw FsException("unsupported")
            }
        }
    }

    private fun createHardLink(oldPath: Path, newPath: Path) {
        try {
            if (isDirectory(oldPath)) {
                throw FsException("not-permitted")
            }
            wasiCreateHardLink(fileSystem, oldPath, newPath)
        } catch (_: UnsupportedOperationException) {
            throw FsException("unsupported")
        }
    }

    private fun effectiveDescriptorFlags(
        openFlags: Any?,
        requested: Set<String>,
        directory: Boolean,
    ): Set<String> {
        val result = LinkedHashSet<String>()
        result.addAll(requested)
        if (!result.contains("write")) {
            result.add("read")
        }
        if (flag(openFlags, "create")) {
            result.add("write")
            if (!requested.contains("write")) {
                result.add("read")
            }
        }
        if (flag(openFlags, "truncate")) {
            result.add("write")
        }
        if (directory) {
            result.remove("file-integrity-sync")
            result.remove("data-integrity-sync")
            result.remove("requested-write-sync")
        } else {
            result.remove("mutate-directory")
        }
        return result.toSet()
    }

    private fun timestamp(value: Any?): KotlinInstant? {
        if (value !is WitValue.Variant) {
            return null
        }
        if (value.label() == "no-change") {
            return null
        }
        if (value.label() == "now") {
            return wallClock.now()
        }
        val payload = value.value()
        if (value.label() != "timestamp" || payload !is Map<*, *>) {
            return null
        }
        return KotlinInstant.fromEpochSeconds(
            (payload["seconds"] as Number).toLong(),
            (payload["nanoseconds"] as Number).toLong(),
        )
    }

    private fun openFileDescriptor(path: Path, flags: Set<String>): okio.FileHandle =
        if (flags.contains("write")) {
            fileSystem.openReadWrite(path, mustExist = true)
        } else {
            fileSystem.openReadOnly(path)
        }

    private fun descriptorSize(descriptor: FilesystemDescriptor): Long =
        descriptor.fileHandle().size()

    private fun writeBytes(descriptor: FilesystemDescriptor, data: ByteArray, offset: Long): Long {
        descriptor.fileHandle().write(offset, data, 0, data.size)
        return data.size.toLong()
    }

    private fun readMemoryBytes(
        descriptor: FilesystemDescriptor,
        context: CanonicalAbi.Context,
        ptr: Int,
        length: Int,
        offset: Long,
    ): Int {
        if (length == 0) {
            return 0
        }
        withWasiPreviewLock(filesystemReadLock) {
            var totalRead = 0
            while (totalRead < length) {
                val count = minOf(filesystemReadBuffer.size, length - totalRead)
                val read = descriptor.fileHandle().read(offset + totalRead, filesystemReadBuffer, 0, count)
                if (read <= 0) {
                    return totalRead
                }
                context.memory().write(ptr + totalRead, filesystemReadBuffer, 0, read)
                totalRead += read
                if (read < count) {
                    return totalRead
                }
            }
            return totalRead
        }
    }

    private fun writeMemoryBytes(
        descriptor: FilesystemDescriptor,
        context: CanonicalAbi.Context,
        ptr: Int,
        length: Int,
        offset: Long,
    ): Long {
        if (length == 0) {
            return 0L
        }
        withWasiPreviewLock(filesystemWriteLock) {
            var written = 0
            while (written < length) {
                val count = minOf(filesystemWriteBuffer.size, length - written)
                context.memory().read(ptr + written, filesystemWriteBuffer, 0, count)
                descriptor.fileHandle().write(offset + written, filesystemWriteBuffer, 0, count)
                written += count
            }
        }
        return length.toLong()
    }

    private fun filesystemError(e: IOException): String = filesystemExceptionCode(e) ?: "io"

    private fun filesystemExceptionCode(e: IOException): String? {
        if (e is FilesystemIOException) {
            return e.code
        }
        if (isClosedChannel(e)) {
            return "bad-descriptor"
        }
        when (exceptionSimpleName(e)) {
            "AccessDeniedException" -> return "access"
            "FileAlreadyExistsException" -> return "exist"
            "DirectoryNotEmptyException" -> return "not-empty"
            "NoSuchFileException",
            "FileNotFoundException" -> return "no-entry"
            "NotDirectoryException" -> return "not-directory"
            "FileSystemLoopException" -> return "loop"
        }
        val message = e.message?.lowercase().orEmpty()
        return when {
            "permission" in message || "denied" in message -> "access"
            "already exists" in message -> "exist"
            "not empty" in message -> "not-empty"
            "no such file" in message || "not found" in message -> "no-entry"
            "not a directory" in message -> "not-directory"
            "too many levels" in message || "loop" in message -> "loop"
            else -> null
        }
    }

    private fun nameLookupResult(operation: () -> Any?): Any? =
        try {
            WitResult.ok(operation())
        } catch (e: NameLookupException) {
            WitResult.err(e.code)
        } catch (_: IllegalArgumentException) {
            WitResult.err("invalid-argument")
        } catch (e: IOException) {
            WitResult.err(WitValue.variant("other", e.message ?: exceptionClassName(e)))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) WitResult.err("access-denied") else throw e
        }

    private fun socketResult(operation: () -> Any?): Any? =
        try {
            WitResult.ok(operation())
        } catch (e: NetException) {
            WitResult.err(e.code)
        } catch (e: Exception) {
            WitResult.err(socketExceptionCode(e))
        }

    private fun <T> socketResultValue(operation: () -> T): WitResult<T, Any?> =
        try {
            WitResult.ok(operation())
        } catch (e: NetException) {
            WitResult.err(e.code)
        } catch (e: Exception) {
            WitResult.err(socketExceptionCode(e))
        }

    private suspend fun <T> socketResultValueSuspending(
        operation: suspend () -> T,
    ): WitResult<T, Any?> =
        try {
            WitResult.ok(operation())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetException) {
            WitResult.err(e.code)
        } catch (e: Exception) {
            WitResult.err(socketExceptionCode(e))
        }

    private fun canAttemptHttpRequests(): Boolean =
        unsafeAllowAllNetworking || networkPolicy.hasHttpAccess()

    private fun requireHttpRequestAllowed(request: WasiHttpRequest) {
        if (unsafeAllowAllNetworking) {
            return
        }
        val url =
            try {
                Url(request.uri)
            } catch (_: URLParserException) {
                throw HttpException("HTTP-request-URI-invalid")
            } catch (_: IllegalArgumentException) {
                throw HttpException("HTTP-request-URI-invalid")
            }
        val protocol =
            when (url.protocol.name.lowercase()) {
                "http" -> WasiHttpNetworkProtocol.Http
                "https" -> WasiHttpNetworkProtocol.Https
                else -> throw HttpException("HTTP-request-denied")
            }
        if (!networkPolicy.allowsHttp(protocol, url.host, url.port)) {
            throw HttpException("HTTP-request-denied")
        }
    }

    private fun requireRawSocketAccess() {
        if (!unsafeAllowAllNetworking && !networkPolicy.hasRawSocketAccess()) {
            throw NetException("access-denied")
        }
    }

    private fun requireRawSocketEndpointAllowed(address: InetSocketAddress) {
        if (unsafeAllowAllNetworking) {
            return
        }
        val numericHost = networkHostFromAddress(addressBytes(address))
        if (networkPolicy.allowsRawSocket(numericHost, address.port)) {
            return
        }
        val allowedByResolvedHostname =
            withWasiPreviewLock(hostTaskLock) {
                networkPolicy.allowsResolvedRawSocket(
                    numericHost,
                    address.port,
                    resolvedRawSocketAddresses,
                )
            }
        if (!allowedByResolvedHostname) {
            throw NetException("access-denied")
        }
    }

    private fun requireRawSocketHostnameAllowed(hostname: String): String? {
        if (unsafeAllowAllNetworking) {
            return null
        }
        val normalized = normalizeNetworkPolicyHost(hostname)
        if (!networkPolicy.allowsRawSocketHost(normalized)) {
            throw NameLookupException("access-denied")
        }
        return normalized
    }

    private fun rememberResolvedRawSocketAddresses(
        normalizedHostname: String?,
        addresses: List<ByteArray>,
    ) {
        if (normalizedHostname == null) {
            return
        }
        val normalizedAddresses = addresses.map(::networkHostFromAddress).toSet()
        withWasiPreviewLock(hostTaskLock) {
            resolvedRawSocketAddresses[normalizedHostname] = normalizedAddresses
        }
    }

    private fun socketExceptionCode(e: Exception): Any {
        exceptionCodeFromClassName(e)?.let {
            return it
        }
        return when {
            isWasiSecurityException(e) -> "access-denied"
            e is IllegalArgumentException -> "invalid-argument"
            e is UnsupportedOperationException -> "not-supported"
            e is IOException -> if (isClosedChannel(e)) "invalid-state" else socketError(e)
            else -> WitValue.variant("other", e.message ?: exceptionClassName(e))
        }
    }

    private fun exceptionCodeFromClassName(e: Throwable): String? =
        when (exceptionSimpleName(e)) {
            "BindException" -> "address-in-use"
            "ConnectException" -> "connection-refused"
            "NoRouteToHostException" -> "remote-unreachable"
            "SocketTimeoutException" -> "timeout"
            else -> null
        }

    private fun socketError(e: Exception): Any {
        val message =
            e.message?.lowercase() ?: return WitValue.variant("other", exceptionClassName(e))
        return when {
            "connection reset" in message -> "connection-reset"
            "broken pipe" in message -> "connection-broken"
            "connection refused" in message -> "connection-refused"
            "timed out" in message -> "timeout"
            "network is unreachable" in message || "no route to host" in message ->
                "remote-unreachable"
            else -> WitValue.variant("other", e.message ?: exceptionClassName(e))
        }
    }

    private fun addressFamily(value: Any?): AddressFamily =
        when (label(value, "ipv4", "ipv6")) {
            "ipv4" -> AddressFamily.IPV4
            "ipv6" -> AddressFamily.IPV6
            else -> throw NetException("invalid-argument")
        }

    private fun requireFamily(family: AddressFamily, address: InetSocketAddress) {
        val bytes = address.resolveAddress() ?: return
        if (family == AddressFamily.IPV4 && bytes.size != 4) {
            throw NetException("invalid-argument")
        }
        if (family == AddressFamily.IPV6 && bytes.size != 16) {
            throw NetException("invalid-argument")
        }
    }

    private fun socketAddress(value: Any?): InetSocketAddress {
        val variantLabel = label(value, "ipv4", "ipv6")
        val payload = variantPayload(value)
        val port = asU64(recordField(payload, "port")).toInt()
        if (port < 0 || port > 0xffff) {
            throw NetException("invalid-argument")
        }
        val rawAddress = recordField(payload, "address")
        return when (variantLabel) {
            "ipv4" -> {
                val octets = numericTuple(rawAddress, 4)
                InetSocketAddress(
                    byteArrayOf(
                        octets[0].toByte(),
                        octets[1].toByte(),
                        octets[2].toByte(),
                        octets[3].toByte(),
                    ),
                    port,
                )
            }
            "ipv6" -> {
                val words = numericTuple(rawAddress, 8)
                val bytes = ByteArray(16)
                for (i in words.indices) {
                    bytes[i * 2] = ((words[i] ushr 8) and 0xff).toByte()
                    bytes[i * 2 + 1] = (words[i] and 0xff).toByte()
                }
                InetSocketAddress(bytes, port)
            }
            else -> throw NetException("invalid-argument")
        }
    }

    private fun socketAddress(address: InetSocketAddress): WitValue.Variant {
        val bytes = address.resolveAddress() ?: throw NetException("invalid-state")
        if (bytes.size == 16) {
            val words = ArrayList<Int>()
            var i = 0
            while (i < bytes.size) {
                words.add((unsignedByte(bytes[i]) shl 8) or unsignedByte(bytes[i + 1]))
                i += 2
            }
            return WitValue.variant(
                "ipv6",
                WitValue.record(
                    "port",
                    address.port,
                    "flow-info",
                    0,
                    "address",
                    words,
                    "scope-id",
                    0,
                ),
            )
        }
        val octets = ArrayList<Int>()
        for (byte in bytes) {
            octets.add(unsignedByte(byte))
        }
        return WitValue.variant("ipv4", WitValue.record("port", address.port, "address", octets))
    }

    private fun ipAddress(bytes: ByteArray): WitValue.Variant {
        if (bytes.size == 16) {
            val words = ArrayList<Int>()
            var i = 0
            while (i < bytes.size) {
                words.add((unsignedByte(bytes[i]) shl 8) or unsignedByte(bytes[i + 1]))
                i += 2
            }
            return WitValue.variant("ipv6", words)
        }
        val octets = ArrayList<Int>()
        for (byte in bytes) {
            octets.add(unsignedByte(byte))
        }
        return WitValue.variant("ipv4", octets)
    }

    private fun resolveIpAddresses(hostname: String): List<ByteArray> {
        val address =
            InetSocketAddress(hostname, 0).resolveAddress()
                ?: throw NameLookupException("name-unresolvable")
        return listOf(address)
    }

    private fun wildcardAddress(family: AddressFamily): InetSocketAddress =
        when (family) {
            AddressFamily.IPV4 -> InetSocketAddress(byteArrayOf(0, 0, 0, 0), 0)
            AddressFamily.IPV6 -> InetSocketAddress(ByteArray(16), 0)
        }

    private fun normalizeLocalAddress(
        family: AddressFamily,
        address: InetSocketAddress,
    ): InetSocketAddress {
        val bytes = address.resolveAddress() ?: return address
        if (family == AddressFamily.IPV4 && bytes.size == 16 && bytes.all { it == 0.toByte() }) {
            return InetSocketAddress(byteArrayOf(0, 0, 0, 0), address.port)
        }
        if (family == AddressFamily.IPV6 && bytes.size == 4 && bytes.all { it == 0.toByte() }) {
            return InetSocketAddress(ByteArray(16), address.port)
        }
        return address
    }

    private fun reserveTcpBindAddress(
        family: AddressFamily,
        local: InetSocketAddress,
    ): ReservedSocketAddress {
        validateLocalBindAddress(family, local)
        val globalKey =
            if (local.port == 0) {
                WasiPreview3TcpBindRegistry.reserveEphemeral(addressBytes(local).asUnsignedList())
                    ?: throw NetException("address-in-use")
            } else {
                globalSocketAddressKey(local).also { key ->
                    if (!WasiPreview3TcpBindRegistry.reserveExact(key)) {
                        throw NetException("address-in-use")
                    }
                }
            }
        val assigned = addressWithPort(local, globalKey.port)
        val localKey = socketAddressKey(family, assigned)
        if (!tcpBoundAddresses.add(localKey)) {
            WasiPreview3TcpBindRegistry.release(globalKey)
            throw NetException("address-in-use")
        }
        return ReservedSocketAddress(assigned, localKey, globalKey)
    }

    private fun addressWithPort(address: InetSocketAddress, port: Int): InetSocketAddress =
        InetSocketAddress(addressBytes(address), port)

    private fun socketAddressKey(
        family: AddressFamily,
        address: InetSocketAddress,
    ): SocketAddressKey =
        SocketAddressKey(
            family,
            addressBytes(address).map { unsignedByte(it) },
            address.port,
        )

    private fun globalSocketAddressKey(address: InetSocketAddress): WasiPreview3TcpBindKey =
        WasiPreview3TcpBindKey(addressBytes(address).asUnsignedList(), address.port)

    private fun ByteArray.asUnsignedList(): List<Int> = map(::unsignedByte)

    private fun validateLocalBindAddress(
        family: AddressFamily,
        address: InetSocketAddress,
    ) {
        val bytes = addressBytes(address)
        requireFamily(family, address)
        if (isIpv4MappedIpv6(bytes) || isMulticastAddress(bytes) || isLimitedBroadcast(bytes)) {
            throw NetException("invalid-argument")
        }
        if (isDocumentationAddress(bytes)) {
            throw NetException("address-not-bindable")
        }
    }

    private fun validateRemoteAddress(
        family: AddressFamily,
        address: InetSocketAddress,
    ) {
        val bytes = addressBytes(address)
        requireFamily(family, address)
        if (
            address.port == 0 ||
                isUnspecifiedAddress(bytes) ||
                isIpv4MappedIpv6(bytes) ||
                isMulticastAddress(bytes) ||
                isLimitedBroadcast(bytes)
        ) {
            throw NetException("invalid-argument")
        }
    }

    private fun addressBytes(address: InetSocketAddress): ByteArray =
        address.resolveAddress() ?: throw NetException("invalid-argument")

    private fun isUnspecifiedAddress(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun isMulticastAddress(bytes: ByteArray): Boolean =
        when (bytes.size) {
            4 -> unsignedByte(bytes[0]) in 224..239
            16 -> unsignedByte(bytes[0]) == 0xff
            else -> false
        }

    private fun isLimitedBroadcast(bytes: ByteArray): Boolean =
        bytes.size == 4 && bytes.all { unsignedByte(it) == 0xff }

    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            bytes.take(10).all { it == 0.toByte() } &&
            unsignedByte(bytes[10]) == 0xff &&
            unsignedByte(bytes[11]) == 0xff

    private fun isDocumentationAddress(bytes: ByteArray): Boolean =
        when (bytes.size) {
            4 -> {
                val a = unsignedByte(bytes[0])
                val b = unsignedByte(bytes[1])
                val c = unsignedByte(bytes[2])
                (a == 192 && b == 0 && c == 2) ||
                    (a == 198 && b == 51 && c == 100) ||
                    (a == 203 && b == 0 && c == 113)
            }
            16 ->
                unsignedByte(bytes[0]) == 0x20 &&
                    unsignedByte(bytes[1]) == 0x01 &&
                    unsignedByte(bytes[2]) == 0x0d &&
                    unsignedByte(bytes[3]) == 0xb8
            else -> false
        }

    private fun udpEndpoint(socket: UdpSocket): WasiUdpEndpoint {
        val existing = socket.endpoint
        if (existing != null) {
            return existing
        }
        val endpoint =
            socketRuntime.bindUdp(
                socket.localAddress ?: wildcardAddress(socket.family),
                socket.receiveBufferSize,
                socket.sendBufferSize,
            )
        return attachUdpEndpoint(socket, endpoint)
    }

    private fun attachUdpEndpoint(
        socket: UdpSocket,
        endpoint: WasiUdpEndpoint,
    ): WasiUdpEndpoint {
        try {
            val localAddress = endpoint.localAddress
            return withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                val existing = socket.endpoint
                if (existing != null) {
                    closeIgnoringFailure { endpoint.close() }
                    existing
                } else {
                    socket.endpoint = endpoint
                    socket.localAddress = localAddress
                    socket.bound = true
                    endpoint
                }
            }
        } catch (failure: Throwable) {
            if (socket.endpoint !== endpoint) {
                closeIgnoringFailure { endpoint.close() }
            }
            throw failure
        }
    }

    private fun recordField(value: Any?, name: String): Any? {
        if (value is Map<*, *>) {
            if (value.containsKey(name)) {
                return value[name]
            }
            val member = memberName(name)
            if (value.containsKey(member)) {
                return value[member]
            }
        }
        if (value is List<*>) {
            return when (name) {
                "port",
                "data" -> value[0]
                "address",
                "remote-address" -> value[1]
                else -> throw ComponentModelException("missing WIT record field $name")
            }
        }
        throw ComponentModelException("missing WIT record field $name")
    }

    private fun numericTuple(value: Any?, size: Int): LongArray {
        val items = list(value)
        if (items.size != size) {
            throw ComponentModelException("expected tuple size $size, got ${items.size}")
        }
        val result = LongArray(size)
        for (i in items.indices) {
            result[i] = asU64(items[i])
        }
        return result
    }

    private fun positiveLong(value: Any?): Long {
        val result = asU64(value)
        if (result == 0L) {
            throw NetException("invalid-argument")
        }
        return if (result < 0L) Long.MAX_VALUE else result
    }

    private fun positiveInt(value: Any?): Int {
        val result = asU64(value)
        if (result == 0L) {
            throw NetException("invalid-argument")
        }
        if (result < 0L || result > Int.MAX_VALUE.toLong()) {
            return Int.MAX_VALUE
        }
        return result.toInt()
    }

    private fun positiveByte(value: Any?): Int {
        val result = asU64(value)
        if (result == 0L || result > 0xffL) {
            throw NetException("invalid-argument")
        }
        return result.toInt()
    }

    private fun registerCli(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, CLI_PACKAGE, interfaceName, functionName, handler)
    }

    private fun registerClocks(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, CLOCKS_PACKAGE, interfaceName, functionName, handler)
    }

    private fun registerRandom(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, RANDOM_PACKAGE, interfaceName, functionName, handler)
    }

    private fun registerFilesystem(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, FILESYSTEM_PACKAGE, interfaceName, functionName, handler)
    }

    private fun registerHttp(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, HTTP_PACKAGE, interfaceName, functionName, handler)
    }

    private fun registerSockets(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, SOCKETS_PACKAGE, interfaceName, functionName, handler)
    }

    private fun <T> registerSocketsMethod(
        builder: WasiHostImportBuilder,
        resourceName: String,
        methodName: String,
        table: WitResourceTable<T>,
        method: SocketResourceMethod<T>,
    ) {
        val handler =
            object : ContextualHostHandler {
                override fun apply(arguments: List<Any?>, context: HostCallContext): Any? {
                    val resource = table.get(handle(arguments, 0))
                    return if (method is ContextualSocketResourceMethod<T>) {
                        method.apply(resource, arguments, context)
                    } else {
                        method.apply(resource, arguments)
                    }
                }
            }
        registerSockets(builder, "types", "$resourceName.$methodName", handler)
        registerSockets(builder, "types", "[method]$resourceName.$methodName", handler)
    }

    private fun registerTcpMethod(
        builder: WasiHostImportBuilder,
        methodName: String,
        method: SocketResourceMethod<TcpSocket>,
    ) {
        registerSocketsMethod(builder, "tcp-socket", methodName, tcpSockets, method)
    }

    private fun registerUdpMethod(
        builder: WasiHostImportBuilder,
        methodName: String,
        method: SocketResourceMethod<UdpSocket>,
    ) {
        registerSocketsMethod(builder, "udp-socket", methodName, udpSockets, method)
    }

    private fun register(
        builder: WasiHostImportBuilder,
        packageName: String,
        interfaceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        for (moduleName in moduleNames(packageName, interfaceName)) {
            builder.withHostImport(moduleName, functionName, handler)
        }
    }

    private fun <T> registerDrop(
        builder: WasiHostImportBuilder,
        packageName: String,
        interfaceName: String,
        resourceName: String,
        table: WitResourceTable<T>,
    ) {
        register(builder, packageName, interfaceName, "[resource-drop]$resourceName") { args ->
            requireArity("[resource-drop]$resourceName", args, 1)
            closeDroppedResource(table.remove(handle(args, 0)))
            null
        }
    }

    private fun closeDroppedResource(resource: Any?) {
        when (resource) {
            is FilesystemDescriptor -> closeFilesystemDescriptor(resource)
            is HttpResponse -> closeHttpResponse(resource)
            is TcpSocket -> closeTcpSocket(resource)
            is UdpSocket -> closeUdpSocket(resource)
        }
    }

    private fun moduleNames(packageName: String, interfaceName: String): Set<String> {
        val result = LinkedHashSet<String>()
        result.add("$packageName/$interfaceName@$version")
        result.add("$packageName/$interfaceName")
        result.add(interfaceName)
        return result
    }

    private fun fieldsFromByteHeaders(
        headers: Map<String, List<ByteArray>>,
        mutable: Boolean,
    ): HttpFields {
        val result = HttpFields(true)
        for (entry in headers.entries) {
            for (value in entry.value) {
                result.append(entry.key, value)
            }
        }
        return result.copy(mutable)
    }

    private fun headersSnapshot(fields: HttpFields): Map<String, List<ByteArray>> {
        val result = LinkedHashMap<String, MutableList<ByteArray>>()
        for (entry in fields.entries) {
            result.getOrPut(entry.name.lowercase()) { ArrayList() }.add(entry.value.copyOf())
        }
        return result.mapValues { it.value.toList() }.toMap()
    }

    private fun emptyTrailers(): HttpTrailers = HttpTrailers(0L, WitResult.ok(null))

    private fun resolveTrailersFuture(value: Any?): HttpTrailers {
        val rawHandle = handle(value)
        if (rawHandle == 0L) {
            return emptyTrailers()
        }
        if (!futures.contains(rawHandle)) {
            return HttpTrailers(rawHandle, null)
        }
        return HttpTrailers(rawHandle, trailerResultFromValue(futureValue(rawHandle)))
    }

    private fun trailerResultFromValue(value: Any?): WitResult<HttpFields?, Any?> =
        when (value) {
            is WitResult.Ok<*, *> -> WitResult.ok(trailerFieldsFromOption(value.value()))
            is WitResult.Err<*, *> -> WitResult.err(value.value())
            is WitValue.Variant ->
                when (value.label()) {
                    "ok" -> WitResult.ok(trailerFieldsFromOption(value.value()))
                    "err" -> WitResult.err(value.value())
                    else -> WitResult.ok(trailerFieldsFromOption(value))
                }
            else -> WitResult.ok(trailerFieldsFromOption(value))
        }

    private fun trailerFieldsFromOption(value: Any?): HttpFields? {
        val payload = option(value) ?: return null
        return fields.get(handle(payload)).copy(false)
    }

    private fun trailerFutureHandle(trailers: HttpTrailers): Long {
        val result = trailers.result
        if (result == null && trailers.rawFutureHandle != 0L) {
            return trailers.rawFutureHandle
        }
        return futureHandle(trailerFutureValue(result ?: WitResult.ok(null)))
    }

    private fun trailerFutureValue(result: WitResult<HttpFields?, Any?>): Any? =
        when (result) {
            is WitResult.Ok<*, *> ->
                WitResult.ok(
                    (result.value() as? HttpFields)?.let { fields.insertResource(it.copy(false)) }
                )
            is WitResult.Err<*, *> -> WitResult.err(result.value())
        }

    private fun trailerSnapshot(
        trailers: HttpTrailers,
        owner: String,
    ): WitResult<Map<String, List<ByteArray>>?, Any?> =
        when (val result = trailers.result) {
            is WitResult.Ok<*, *> ->
                WitResult.ok((result.value() as? HttpFields)?.let { headersSnapshot(it) })
            is WitResult.Err<*, *> -> WitResult.err(result.value())
            null ->
                throw ComponentModelException(
                    "WASI Preview 3 HTTP $owner trailers future ${trailers.rawFutureHandle} is opaque"
                )
        }

    private fun sendHttpRequestResult(request: WasiHttpRequest): Any? =
        try {
            requireHttpRequestAllowed(request)
            WitResult.ok(httpResponseResource(httpClient.send(request)))
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private fun clientSendResult(
        request: HttpRequest,
        bodyResult: WitResult<ByteArray, Any?>,
    ): Any? =
        try {
            sendHttpRequestResult(httpRequestData(request, httpBodyBytesOrThrow(bodyResult)))
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private suspend fun clientSendResultSuspending(request: HttpRequest): Any? =
        try {
            val data = httpRequestData(request, request.body.awaitBytes())
            if (httpClient is WasiSuspendingHttpClient) {
                sendHttpRequestSuspendingResult(data)
            } else {
                sendHttpRequestResult(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private fun handlerHandleResult(
        request: HttpRequest,
        bodyResult: WitResult<ByteArray, Any?>,
    ): Any? =
        try {
            val response = httpHandler.handle(requestSnapshot(request, bodyResult))
            WitResult.ok(responseResource(response))
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private suspend fun handlerHandleResultSuspending(request: HttpRequest): Any? =
        try {
            handlerHandleResult(request, WitResult.ok(request.body.awaitBytes()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private suspend fun sendHttpRequestSuspendingResult(request: WasiHttpRequest): Any? =
        try {
            requireHttpRequestAllowed(request)
            WitResult.ok(httpResponseResource(httpClient.sendSuspending(request)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                WitResult.err(WitValue.variant("internal-error", "interrupted"))
            } else {
                WitResult.err(httpError(e))
            }
        }

    private fun httpResponseResource(response: WasiHttpResponse): WitResource<Nothing> {
        val bodySource = response.consumeBodySource()
        try {
            val resource =
                HttpResponse(
                    response.status,
                    fieldsFromHttpHeaders(response.headers, false),
                    bodySource,
                    true,
                    emptyTrailers(),
                )
            return withWasiPreviewLock(hostTaskLock) {
                if (closed) {
                    throw CancellationException("WASI Preview 3 host is closed")
                }
                responses.insertResource(resource)
            }
        } catch (failure: Throwable) {
            closeIgnoringFailure { bodySource.close() }
            throw failure
        }
    }

    private suspend fun httpRequestData(request: HttpRequest): WasiHttpRequest =
        httpRequestData(request, request.body.awaitBytes())

    private fun httpRequestData(request: HttpRequest, body: ByteArray): WasiHttpRequest =
        httpRequestData(
            request.method,
            request.scheme,
            request.authority,
            request.pathWithQuery,
            request.headers,
            body,
            request.options,
        )

    private fun httpRequestData(
        methodValue: Any,
        schemeValue: Any?,
        authority: String?,
        pathWithQuery: String?,
        headers: HttpFields,
        body: ByteArray,
        options: RequestOptions?,
    ): WasiHttpRequest {
        val scheme = schemeValue?.let { label(it, "HTTP", "HTTPS") } ?: "HTTP"
        val javaScheme =
            when (scheme) {
                "HTTP" -> "http"
                "HTTPS" -> "https"
                "other" -> stringValue(variantPayload(schemeValue))
                else -> throw HttpException("HTTP-request-URI-invalid")
            }
        if (authority == null || authority.isBlank()) {
            throw HttpException("HTTP-request-URI-invalid")
        }
        var path = pathWithQuery?.takeIf { it.isNotEmpty() } ?: "/"
        if (!path.startsWith("/")) {
            path = "/$path"
        }
        val uri =
            try {
                Url("$javaScheme://$authority$path").toString()
            } catch (_: URLParserException) {
                throw HttpException("HTTP-request-URI-invalid")
            } catch (_: IllegalArgumentException) {
                throw HttpException("HTTP-request-URI-invalid")
            }
        return WasiHttpRequest(
            httpMethodName(methodValue),
            uri,
            headers.entries.map { WasiHttpHeader(it.name, latin1String(it.value)) },
            body.copyOf(),
            httpTimeout(options),
        )
    }

    private fun httpTimeout(options: RequestOptions?): Duration? {
        val nanos = options?.firstByteTimeout ?: options?.connectTimeout
        if (nanos == null || nanos <= 0L) {
            return null
        }
        return nanos.nanoseconds
    }

    private fun fieldsFromHttpHeaders(
        headers: Map<String, List<String>>,
        mutable: Boolean,
    ): HttpFields {
        val result = HttpFields(true)
        for (entry in headers.entries) {
            for (value in entry.value) {
                result.append(entry.key, latin1Bytes(value))
            }
        }
        return result.copy(mutable)
    }

    private fun httpInternalError(e: Exception): Any =
        WitValue.variant("internal-error", e.message ?: exceptionClassName(e))

    private fun httpError(e: Exception): Any {
        var cause: Throwable? = e
        while (cause != null) {
            if (exceptionSimpleName(cause) == "ConnectException") {
                return "connection-refused"
            }
            if (
                exceptionSimpleName(cause) == "SocketTimeoutException" ||
                    exceptionClassName(cause).contains("Timeout")
            ) {
                return "connection-timeout"
            }
            cause = cause.cause
        }
        return httpInternalError(e)
    }

    private fun httpMethodName(value: Any?): String {
        val label =
            label(
                value,
                "get",
                "head",
                "post",
                "put",
                "delete",
                "connect",
                "options",
                "trace",
                "patch",
            )
        val method = if (label == "other") stringValue(variantPayload(value)) else label.uppercase()
        if (
            method.isBlank() || method.any { it == ' ' || it == '\r' || it == '\n' || it == '\t' }
        ) {
            throw HttpException("HTTP-request-method-invalid")
        }
        return method
    }

    private fun httpSchemeName(value: Any?): String {
        val label = value?.let { label(it, "HTTP", "HTTPS") } ?: return ""
        return when (label) {
            "HTTP" -> "http"
            "HTTPS" -> "https"
            "other" -> stringValue(variantPayload(value))
            else -> label.lowercase()
        }
    }

    private fun methodFromString(method: String): Any {
        if (method.isBlank()) {
            return "get"
        }
        val normalized = method.lowercase()
        return when (normalized) {
            "get",
            "head",
            "post",
            "put",
            "delete",
            "connect",
            "options",
            "trace",
            "patch" -> normalized
            else -> WitValue.variant("other", method)
        }
    }

    private fun schemeFromString(scheme: String): Any? {
        if (scheme.isBlank()) {
            return null
        }
        return when (scheme.lowercase()) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> WitValue.variant("other", scheme)
        }
    }

    private fun httpMethod(value: Any?): Any {
        val label =
            label(
                value,
                "get",
                "head",
                "post",
                "put",
                "delete",
                "connect",
                "options",
                "trace",
                "patch",
            )
        if (label != "other") {
            return label
        }
        val method = stringValue(variantPayload(value))
        return standardHttpMethodFromToken(method) ?: WitValue.variant("other", method)
    }

    private fun httpScheme(value: Any?): Any {
        val label = label(value, "HTTP", "HTTPS")
        if (label != "other") {
            return label
        }
        val scheme = stringValue(variantPayload(value))
        return when (scheme) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> WitValue.variant("other", scheme)
        }
    }

    private fun standardHttpMethodFromToken(method: String): String? =
        when (method) {
            "GET" -> "get"
            "HEAD" -> "head"
            "POST" -> "post"
            "PUT" -> "put"
            "DELETE" -> "delete"
            "CONNECT" -> "connect"
            "OPTIONS" -> "options"
            "TRACE" -> "trace"
            "PATCH" -> "patch"
            else -> null
        }

    private fun isValidHttpMethodName(method: String): Boolean =
        method.isNotEmpty() && method.all { isHttpTokenChar(it) }

    private fun isHttpTokenChar(char: Char): Boolean =
        when (char) {
            '!',
            '#',
            '$',
            '%',
            '&',
            '\'',
            '*',
            '+',
            '-',
            '.',
            '^',
            '_',
            '`',
            '|',
            '~' -> true
            in '0'..'9',
            in 'A'..'Z',
            in 'a'..'z' -> true
            else -> false
        }

    private fun isValidHttpSchemeName(scheme: String): Boolean {
        if (scheme.isEmpty() || !scheme[0].isAsciiLetter()) {
            return false
        }
        return scheme.drop(1).all { it.isAsciiLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    private fun isValidPathWithQuery(pathWithQuery: String): Boolean =
        pathWithQuery.all { char ->
            char.isAsciiLetterOrDigit() ||
                char.code >= 0x80 ||
                "-._~%!$&'()*+,;=:@/?".contains(char)
        }

    private fun isValidAuthority(authority: String): Boolean {
        if (
            authority.isEmpty() ||
                authority.any { it <= ' ' || it == '#' } ||
                authority.count { it == '@' } > 1
        ) {
            return false
        }
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty() || hostPort.startsWith("[") || hostPort.contains("::")) {
            return false
        }
        val colon = hostPort.lastIndexOf(':')
        val host =
            if (colon >= 0) {
                val port = hostPort.substring(colon + 1)
                if (port.isEmpty() || port.any { !it.isDigit() } || port.toInt() > 0xffff) {
                    return false
                }
                hostPort.substring(0, colon)
            } else {
                hostPort
            }
        return host.isNotEmpty() &&
            host.all { char ->
                char.isAsciiLetterOrDigit() || "-._~%!$&'()*+,;=".contains(char)
            }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

    private fun label(value: Any?, vararg known: String): String {
        if (value is WitValue.Variant) {
            return value.label()
        }
        if (value is Number) {
            val index = value.toInt()
            if (index >= 0 && index < known.size) {
                return known[index]
            }
        }
        val text = stringValue(value)
        for (candidate in known) {
            if (candidate == text) {
                return candidate
            }
        }
        return text
    }

    private fun option(value: Any?): Any? {
        if (value == null) {
            return null
        }
        if (value is WitValue.Variant) {
            if (value.label() == "none") {
                return null
            }
            if (value.label() == "some") {
                return value.value()
            }
        }
        return value
    }

    private fun optionString(value: Any?): String? = option(value)?.let { stringValue(it) }

    private fun optionU64(value: Any?): Long? = option(value)?.let { asU64(it) }

    private fun optionHandle(value: Any?): Long? = option(value)?.let { handle(it) }

    private fun variantPayload(value: Any?): Any? {
        if (value is WitValue.Variant) {
            return if (value.hasValue()) value.value() else null
        }
        throw ComponentModelException("expected WIT variant payload, got $value")
    }

    private fun tupleValue(value: Any?, index: Int): Any? {
        if (value is List<*>) {
            return value[index]
        }
        if (value is Map<*, *>) {
            return value[index.toString()]
        }
        throw ComponentModelException("expected WIT tuple, got $value")
    }

    private fun list(value: Any?): List<*> {
        if (value is List<*>) {
            return value
        }
        if (value is Array<*>) {
            return value.asList()
        }
        throw ComponentModelException("expected WIT list, got $value")
    }

    private fun instant(seconds: Long, nanos: Long): Map<String, Any?> =
        WitValue.record("seconds", seconds, "nanoseconds", nanos)

    private fun instantFromValue(value: Any?): KotlinInstant {
        if (value !is Map<*, *>) {
            throw ComponentModelException("expected wasi:clocks instant record, got $value")
        }
        val seconds = (value["seconds"] as Number).toLong()
        val nanos = (value["nanoseconds"] as Number).toLong()
        return KotlinInstant.fromEpochSeconds(seconds, nanos)
    }

    private fun monotonicNow(): Long = monotonicClock() - monotonicBaseNanos

    private fun sleepNanos(nanos: Long) {
        if (nanos == 0L) {
            return
        }
        wasiDelay(unsignedNanosDuration(nanos))
    }

    private fun unsignedNanosDuration(nanos: Long): Duration =
        if (nanos < 0L) Duration.INFINITE else nanos.nanoseconds

    private fun randomBytes(random: CryptoRand, length: Int): ByteArray {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    private fun randomBytes(random: Random, length: Int): ByteArray {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    private fun randomLong(random: CryptoRand): Long {
        if (random is CryptoRandLongSource) {
            return random.nextLong()
        }
        return longFromRandomBytes(randomBytes(random, Long.SIZE_BYTES))
    }

    private fun randomLong(random: Random): Long {
        return random.nextLong()
    }

    private fun checkedByteLength(value: Any?): Int {
        val requested = asU64(value)
        if (requested > Int.MAX_VALUE) {
            throw ComponentModelException("requested byte length too large: $requested")
        }
        return requested.toInt()
    }

    private fun checkedFlatByteLength(value: Long): Int {
        val requested = value and U32_MASK
        if (requested > Int.MAX_VALUE) {
            throw ComponentModelException("requested byte length too large: $requested")
        }
        return requested.toInt()
    }

    private fun ensureOpen() {
        val isClosed =
            withWasiPreviewLock(hostTaskLock) {
                closed
            }
        if (isClosed) {
            throw ComponentModelException("WASI Preview 3 host is closed")
        }
    }

    private fun launchHostTask(block: suspend () -> Unit) {
        withWasiPreviewLock(hostTaskLock) {
            if (closed) {
                throw ComponentModelException("WASI Preview 3 host is closed")
            }
            requireWasiPreview3Capacity(
                "in-flight host task",
                inFlightHostTasks,
                1,
                maxInFlightHostTasks,
            )
            inFlightHostTasks += 1
        }
        try {
            hostScope.launch { block() }
                .invokeOnCompletion {
                    withWasiPreviewLock(hostTaskLock) {
                        inFlightHostTasks -= 1
                    }
                }
        } catch (failure: Throwable) {
            withWasiPreviewLock(hostTaskLock) {
                inFlightHostTasks -= 1
            }
            throw failure
        }
    }

    private fun requireFutureCapacity(requested: Int) {
        ensureOpen()
        requireWasiPreview3Capacity("future", futures.size(), requested, maxPendingFutures)
    }

    private fun requireStreamCapacity(requested: Int) {
        ensureOpen()
        requireWasiPreview3Capacity("stream", streams.size(), requested, maxPendingStreams)
    }

    private fun futureHandle(value: Any?): Long =
        futureHandle(FutureState(value = value, completed = true, writableDropped = true))

    private fun futureHandle(state: FutureState): Long {
        requireFutureCapacity(1)
        return futures.insertResource(FutureValue(state)).handle()
    }

    private fun streamHandle(value: StreamValue): Long {
        requireStreamCapacity(1)
        return streams.insertResource(value).handle()
    }

    private fun streamBytesFromOption(value: Any?): ByteArray {
        val stream = option(value) ?: return ByteArray(0)
        return streamBytes(handle(stream))
    }

    private fun httpBodyFromOption(value: Any?): HttpBody {
        val stream = option(value) ?: return completedHttpBody(ByteArray(0))
        val streamHandle = handle(stream)
        val streamData = streams.get(streamHandle).data
        if (streamData is ByteArray) {
            return completedHttpBody(streamData.copyOf())
        }
        if (streamData is ByteStreamBuffer && streamData.writableDropped) {
            return completedHttpBody(streamData.snapshotRemaining())
        }
        val body = pendingHttpBody()
        launchHostTask {
            body.complete(
                try {
                    WitResult.ok(collectHttpBodyStream(streamHandle, body.streamData))
                } catch (e: HttpException) {
                    WitResult.err(e.code)
                } catch (e: IOException) {
                    WitResult.err(httpError(e))
                } catch (e: IllegalArgumentException) {
                    WitResult.err(httpInternalError(e))
                } catch (e: ComponentModelException) {
                    WitResult.err(httpInternalError(e))
                } catch (e: Exception) {
                    if (isWasiInterrupted(e)) {
                        restoreWasiInterruptStatus()
                        WitResult.err(WitValue.variant("internal-error", "interrupted"))
                    } else {
                        WitResult.err(httpError(e))
                    }
                }
            )
        }
        return body
    }

    private fun completedHttpBody(bytes: ByteArray): HttpBody =
        HttpBody(
            ByteStreamBuffer(bytes, writableDropped = true, capacity = Int.MAX_VALUE),
            completedFuture(WitResult.ok(null)),
            WitResult.ok(bytes.copyOf()),
        )

    private fun pendingHttpBody(): HttpBody =
        HttpBody(
            ByteStreamBuffer(capacity = Int.MAX_VALUE),
            pendingFuture(),
            null,
        )

    private suspend fun collectHttpBodyStream(
        streamHandle: Long,
        output: ByteStreamBuffer,
    ): ByteArray {
        val collected = KotlinxBuffer()
        try {
            collectByteStream(streamHandle) { chunk ->
                if (chunk.isNotEmpty()) {
                    collected.write(chunk)
                    writeHttpBodyChunk(output, chunk)
                }
            }
            return collected.readByteArray()
        } finally {
            output.dropWritable()
        }
    }

    private suspend fun collectByteStream(
        streamHandle: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ) {
        val stream = streams.get(streamHandle)
        when (val data = stream.data) {
            is ByteArray -> {
                onChunk(data)
                stream.data = ByteArray(0)
            }
            is ByteStreamBuffer -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.isNotEmpty()) {
                        onChunk(chunk)
                        continue
                    }
                    if (data.writableDropped) {
                        break
                    }
                    data.awaitReadable()
                }
            }
            is SourceByteStream -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.bytes.isNotEmpty()) {
                        onChunk(chunk.bytes)
                    }
                    if (chunk.closed) {
                        break
                    }
                }
            }
            is RawSource -> {
                val source = SourceByteStream(data)
                stream.data = source
                collectByteStream(streamHandle, onChunk)
            }
            is TcpReceiveStream -> onChunk(data.readBytes())
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $streamHandle does not contain byte data"
                )
        }
    }

    private suspend fun writeHttpBodyChunk(output: ByteStreamBuffer, chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val written = output.write(chunk, offset, chunk.size - offset)
            if (written > 0) {
                offset += written
            } else {
                output.awaitWritable()
            }
        }
    }

    private fun responseBodyStreamData(body: Any): Any =
        if (body is HttpBody) body.streamData else body

    private fun httpBodyBytesOrThrow(result: WitResult<ByteArray, Any?>): ByteArray =
        when (result) {
            is WitResult.Ok<*, *> -> (result.value() as ByteArray).copyOf()
            is WitResult.Err<*, *> -> throw HttpException(result.value())
        }

    private fun cliStreamCompletion(future: WitFuture<Any?>): StreamCompletion =
        StreamCompletion(future) { "io" }

    private fun filesystemStreamCompletion(future: WitFuture<Any?>): StreamCompletion =
        StreamCompletion(future) { e ->
            when (e) {
                is FsException -> e.code
                is IOException -> filesystemError(e)
                else -> "io"
            }
        }

    private fun writeCliOutputStream(sink: RawSink, streamHandle: Long): Long {
        val future = pendingFuture<Any?>()
        launchHostTask {
            completeFutureIfPresent(
                future,
                try {
                    writeByteStreamToSink(streamHandle, sink)
                    WitResult.ok(null)
                } catch (_: IOException) {
                    WitResult.err("io")
                } catch (_: IllegalStateException) {
                    WitResult.err("io")
                } catch (_: ComponentModelException) {
                    WitResult.err("io")
                },
            )
        }
        return future.handle()
    }

    private fun writeFilesystemOutputStream(sink: RawSink, streamHandle: Long): Long {
        val future = pendingFuture<Any?>()
        launchHostTask {
            var result: Any? =
                try {
                    writeByteStreamToSink(streamHandle, sink)
                    WitResult.ok(null)
                } catch (e: FsException) {
                    WitResult.err(e.code)
                } catch (e: IOException) {
                    WitResult.err(filesystemError(e))
                } catch (_: ComponentModelException) {
                    WitResult.err("unsupported")
                } catch (e: Exception) {
                    if (isWasiSecurityException(e)) WitResult.err("not-permitted") else throw e
                }
            try {
                sink.close()
            } catch (e: IOException) {
                if (result is WitResult.Ok<*, *>) {
                    result = WitResult.err(filesystemError(e))
                }
            }
            completeFutureIfPresent(future, result)
        }
        return future.handle()
    }

    private fun pipeFilesystemOutputStream(sink: RawSink, streamHandle: Long): Long {
        val future = pendingFuture<Any?>()
        try {
            byteStream(streamHandle)
                .pipeToSink(
                    sink,
                    { result -> completeFutureIfPresent(future, result) },
                    { error ->
                        when (error) {
                            is FsException -> error.code
                            is IOException -> filesystemError(error)
                            is ComponentModelException -> "unsupported"
                            else ->
                                if (isWasiSecurityException(error)) "not-permitted"
                                else "io"
                        }
                    },
                )
        } catch (e: FsException) {
            completeFutureIfPresent(future, WitResult.err(e.code))
        } catch (e: IOException) {
            completeFutureIfPresent(future, WitResult.err(filesystemError(e)))
        } catch (_: ComponentModelException) {
            completeFutureIfPresent(future, WitResult.err("unsupported"))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                completeFutureIfPresent(future, WitResult.err("not-permitted"))
            } else {
                throw e
            }
        }
        return future.handle()
    }

    private fun sendTcpOutputStream(connection: WasiTcpConnection, streamHandle: Long): Long {
        val future = pendingFuture<Any?>()
        launchHostTask {
            completeFutureIfPresent(
                future,
                socketResultValueSuspending {
                    if (connection is WasiSuspendingTcpConnection) {
                        sendByteStreamSuspending(streamHandle, connection)
                    } else {
                        val sink = connection.outputSink()
                        try {
                            writeByteStreamToSink(streamHandle, sink)
                        } finally {
                            sink.close()
                        }
                    }
                    null
                },
            )
        }
        return future.handle()
    }

    private suspend fun sendByteStreamSuspending(
        streamHandle: Long,
        connection: WasiSuspendingTcpConnection,
    ) {
        val stream = streams.get(streamHandle)
        when (val data = stream.data) {
            is ByteArray -> {
                connection.sendSuspending(data)
                stream.data = ByteArray(0)
            }
            is ByteStreamBuffer -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.isNotEmpty()) {
                        connection.sendSuspending(chunk)
                        continue
                    }
                    if (data.writableDropped) {
                        break
                    }
                    data.awaitReadable()
                }
            }
            is SourceByteStream -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.bytes.isNotEmpty()) {
                        connection.sendSuspending(chunk.bytes)
                    }
                    if (chunk.closed) {
                        break
                    }
                }
            }
            is RawSource -> {
                val source = SourceByteStream(data)
                stream.data = source
                sendByteStreamSuspending(streamHandle, connection)
                return
            }
            is TcpReceiveStream -> connection.sendSuspending(data.readBytes())
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $streamHandle does not contain byte data"
                )
        }
        connection.shutdownOutput()
    }

    private suspend fun writeByteStreamToSink(streamHandle: Long, sink: RawSink) {
        val stream = streams.get(streamHandle)
        when (val data = stream.data) {
            is ByteArray -> {
                writeSinkBytes(sink, data)
                stream.data = ByteArray(0)
            }
            is ByteStreamBuffer -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.isNotEmpty()) {
                        writeSinkBytes(sink, chunk)
                        continue
                    }
                    if (data.writableDropped) {
                        break
                    }
                    data.awaitReadable()
                }
            }
            is SourceByteStream -> {
                while (true) {
                    val chunk = data.read(STREAM_MAX_LENGTH)
                    if (chunk.bytes.isNotEmpty()) {
                        writeSinkBytes(sink, chunk.bytes)
                    }
                    if (chunk.closed) {
                        break
                    }
                }
            }
            is RawSource -> {
                val source = SourceByteStream(data)
                stream.data = source
                writeByteStreamToSink(streamHandle, sink)
                return
            }
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $streamHandle does not contain byte data"
                )
        }
        sink.flush()
    }

    private fun writeSinkBytes(sink: RawSink, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        val buffer = KotlinxBuffer()
        buffer.write(bytes)
        sink.write(buffer, bytes.size.toLong())
    }

    private fun responseBodyBytes(body: Any): ByteArray =
        when (body) {
            is ByteArray -> body.copyOf()
            is HttpBody ->
                body.completedBytesResult()?.let { httpBodyBytesOrThrow(it) }
                    ?: wasiRunBlockingOrNull { body.awaitBytes() }
                    ?: throw ComponentModelException(
                        "WASI Preview 3 HTTP response body is not ready"
                    )
            is SourceByteStream -> body.readBytes()
            is RawSource -> SourceByteStream(body).readBytes()
            else ->
                throw ComponentModelException("WASI Preview 3 HTTP response body is not byte data")
        }

    private fun byteStream(handle: Long): ByteStreamBuffer {
        val stream = streams.get(handle)
        val data = stream.data
        if (data is ByteStreamBuffer) {
            return data
        }
        val buffer =
            when (data) {
                is ByteArray -> ByteStreamBuffer(data, writableDropped = true)
                is TcpReceiveStream -> ByteStreamBuffer(data.readBytes(), writableDropped = true)
                else ->
                    throw ComponentModelException(
                        "WASI Preview 3 stream $handle does not contain byte data"
                    )
            }
        stream.data = buffer
        return buffer
    }

    private suspend fun writeByteStreamChunk(buffer: ByteStreamBuffer, chunk: ByteArray): Boolean {
        if (buffer.readableDropped || buffer.writableDropped) {
            return false
        }
        var offset = 0
        while (offset < chunk.size) {
            buffer.awaitWritable()
            val written = buffer.write(chunk, offset, chunk.size - offset)
            if (written <= 0) {
                if (buffer.readableDropped || buffer.writableDropped) {
                    return false
                }
                yield()
            } else {
                offset += written
            }
        }
        return true
    }

    private fun readByteStreamChunkNow(handle: Long, maxBytes: Int): ByteStreamReadChunk {
        val stream = streams.get(handle)
        return when (val data = stream.data) {
            is ByteArray -> {
                val buffer = ByteStreamBuffer(data, writableDropped = true)
                stream.data = buffer
                readByteStreamBufferChunk(buffer, maxBytes)
            }
            is ByteStreamBuffer -> readByteStreamBufferChunk(data, maxBytes)
            is SourceByteStream -> data.read(maxBytes).toByteStreamReadChunk()
            is RawSource -> {
                val source = SourceByteStream(data)
                stream.data = source
                source.read(maxBytes).toByteStreamReadChunk()
            }
            is TcpReceiveStream -> data.read(maxBytes).toByteStreamReadChunk()
            else ->
                throw ComponentModelException(
                    "WASI Preview 3 stream $handle does not contain byte data"
                )
        }
    }

    private fun readByteStreamBufferChunk(
        buffer: ByteStreamBuffer,
        maxBytes: Int,
    ): ByteStreamReadChunk {
        val bytes = buffer.read(maxBytes)
        val closed = bytes.isEmpty() && buffer.writableDropped && buffer.remaining() == 0
        return ByteStreamReadChunk(bytes, closed)
    }

    private fun SourceReadChunk.toByteStreamReadChunk(): ByteStreamReadChunk =
        ByteStreamReadChunk(bytes, closed)

    private fun TcpReadChunk.toByteStreamReadChunk(): ByteStreamReadChunk =
        ByteStreamReadChunk(bytes, closed)

    private fun requireByteStreamChunkSize(maxBytes: Int): Int {
        if (maxBytes <= 0 || maxBytes > STREAM_MAX_LENGTH) {
            throw IllegalArgumentException("maxBytes must be between 1 and $STREAM_MAX_LENGTH")
        }
        return maxBytes
    }

    private fun sourceByteStream(handle: Long): SourceByteStream? {
        val stream = streams.get(handle)
        val data = stream.data
        if (data is SourceByteStream) {
            return data
        }
        if (data is RawSource) {
            val source = SourceByteStream(data)
            stream.data = source
            return source
        }
        return null
    }

    private fun objectStream(handle: Long): ObjectStreamBuffer {
        val stream = streams.get(handle)
        val data = stream.data
        if (data is ObjectStreamBuffer) {
            return data
        }
        if (data is List<*>) {
            val buffer = ObjectStreamBuffer(ArrayList(data), writableDropped = true)
            stream.data = buffer
            return buffer
        }
        throw ComponentModelException("WASI Preview 3 stream $handle does not contain typed data")
    }

    private fun isBytePayload(type: WitPackage.TypeRef): Boolean =
        type.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE &&
            (type.name() == "u8" || type.name() == "s8")

    private fun streamCompleted(count: Int): Long = streamReturnCode(STREAM_COMPLETED, count)

    private fun streamDropped(count: Int): Long = streamReturnCode(STREAM_DROPPED, count)

    private fun streamCancelled(count: Int): Long = streamReturnCode(STREAM_CANCELLED, count)

    private fun streamReturnCode(kind: Int, count: Int): Long {
        if (count < 0 || count > STREAM_MAX_LENGTH) {
            throw ComponentModelException("canonical stream transfer count out of range: $count")
        }
        return ((count.toLong() shl 4) or kind.toLong()) and 0xffff_ffffL
    }

    private fun exitStatus(value: Any?): Int {
        if (value is WitValue.Variant) {
            return if (value.label() == "ok") 0 else 1
        }
        if (value is WitResult.Ok<*, *>) {
            return 0
        }
        if (value is WitResult.Err<*, *>) {
            return 1
        }
        return if (value == null) 0 else 1
    }

    private fun requireArity(functionName: String, args: List<Any?>, arity: Int) {
        if (args.size != arity) {
            throw ComponentModelException(
                "$functionName expected $arity arguments, got ${args.size}"
            )
        }
    }

    private fun handle(args: List<Any?>, index: Int): Long = handle(args[index])

    private fun handle(value: Any?): Long = asU64(value)

    private fun asU64(value: Any?): Long {
        if (value is WitResource<*>) {
            return value.handle()
        }
        if (value is WitFuture<*>) {
            return value.handle()
        }
        if (value is WitStream<*>) {
            return value.handle()
        }
        if (value is Number) {
            return value.toLong()
        }
        throw ComponentModelException("expected unsigned integer handle, got $value")
    }

    private fun string(value: Any?): String =
        value as? String ?: throw ComponentModelException("expected WIT string, got $value")

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun bytes(value: Any?): ByteArray {
        if (value is ByteArray) {
            return value.copyOf()
        }
        if (value is UByteArray) {
            val result = ByteArray(value.size)
            for (i in value.indices) {
                result[i] = value[i].toByte()
            }
            return result
        }
        if (value is List<*>) {
            val result = ByteArray(value.size)
            for (i in value.indices) {
                result[i] = (value[i] as Number).toByte()
            }
            return result
        }
        throw ComponentModelException("expected WIT byte list, got $value")
    }

    class Builder {
        var version: String = DEFAULT_VERSION
        var stdin: RawSource = defaultWasiStdin()
        var stdout: RawSink = defaultWasiStdout()
        var stderr: RawSink = defaultWasiStderr()
        val arguments: MutableList<String> = ArrayList()
        val environment: MutableMap<String, String> = LinkedHashMap()
        var initialCwd: String? = null
        var wallClock: KotlinClock = KotlinClock.System
        var wallClockTimeZone: TimeZone = TimeZone.UTC
        var wallClockResolutionNanos: Long = 1L
        var monotonicClock: () -> Long = defaultPreview3MonotonicClock()
        var monotonicResolutionNanos: Long = 1L
        var secureRandom: CryptoRand = CryptoRand.Default
        var insecureRandom: Random = Random(0L)
        var insecureSeedLower: Long = 0L
        var insecureSeedUpper: Long = 0L
        var fileSystem: FileSystem = defaultWasiFileSystem()
        internal val preopens: MutableList<Preopen> = ArrayList()
        var terminalStdin: Boolean = false
        var terminalStdout: Boolean = false
        var terminalStderr: Boolean = false
        internal var networkPolicy: WasiNetworkPolicy = WasiNetworkPolicy.DENY_ALL
        internal var unsafeAllowAllNetworking: Boolean = false
        /**
         * HTTP transport used by this host.
         *
         * Assigning a client transfers no ownership: the caller remains responsible for closing
         * it. The internally created default transport is owned and closed by the built host.
         */
        var httpClient: WasiHttpClient = DefaultWasiPreview3HttpClient
        var httpHandler: WasiHttpHandler = defaultWasiHttpHandler()
        var streamBufferCapacity: Int = DEFAULT_STREAM_BUFFER_CAPACITY
        var maxCanonicalThreads: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
        var maxPendingFutures: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
        var maxPendingStreams: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
        var maxWaitables: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
        var maxInFlightHostTasks: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
        internal var coroutineScope: CoroutineScope? = null
        internal var ownsCoroutineScope: Boolean = false
        internal var defaultHttpClientFactory: () -> WasiHttpClient = ::defaultWasiHttpClient
        internal var socketRuntime: WasiSocketRuntime? = null
        internal var socketRuntimeFactory: () -> WasiSocketRuntime = ::defaultWasiSocketRuntime

        fun withVersion(version: String): Builder {
            this.version = requirePresent(version, "version")
            return this
        }

        fun withStdin(stdin: RawSource): Builder {
            this.stdin = requirePresent(stdin, "stdin")
            return this
        }

        fun withStdout(stdout: RawSink): Builder {
            this.stdout = requirePresent(stdout, "stdout")
            return this
        }

        fun withStderr(stderr: RawSink): Builder {
            this.stderr = requirePresent(stderr, "stderr")
            return this
        }

        fun withArguments(arguments: List<String>): Builder {
            this.arguments.clear()
            this.arguments.addAll(arguments)
            return this
        }

        fun withArguments(vararg arguments: String): Builder = withArguments(arguments.asList())

        fun withEnvironment(environment: Map<String, String>): Builder {
            this.environment.clear()
            this.environment.putAll(environment)
            return this
        }

        fun withEnvironment(name: String, value: String): Builder {
            environment[requirePresent(name, "name")] = requirePresent(value, "value")
            return this
        }

        fun withInitialCwd(initialCwd: String?): Builder {
            this.initialCwd = initialCwd
            return this
        }

        fun withWallClock(wallClock: KotlinClock, zoneId: String = "UTC"): Builder =
            withWallClock(wallClock, TimeZone.of(requirePresent(zoneId, "zoneId")))

        fun withWallClock(wallClock: KotlinClock, timeZone: TimeZone): Builder {
            this.wallClock = requirePresent(wallClock, "wallClock")
            this.wallClockTimeZone = requirePresent(timeZone, "timeZone")
            return this
        }

        fun withWallClock(now: () -> KotlinInstant, zoneId: String = "UTC"): Builder =
            withWallClock(
                object : KotlinClock {
                    override fun now(): KotlinInstant = now.invoke()
                },
                zoneId,
            )

        fun withFixedWallClock(instant: KotlinInstant, zoneId: String = "UTC"): Builder =
            withWallClock({ instant }, zoneId)

        fun withWallClockResolutionNanos(wallClockResolutionNanos: Long): Builder {
            this.wallClockResolutionNanos =
                requireNanos("wallClockResolutionNanos", wallClockResolutionNanos)
            return this
        }

        fun withMonotonicClock(monotonicClock: () -> Long): Builder {
            this.monotonicClock = requirePresent(monotonicClock, "monotonicClock")
            return this
        }

        fun withMonotonicResolutionNanos(monotonicResolutionNanos: Long): Builder {
            this.monotonicResolutionNanos =
                requireNanos("monotonicResolutionNanos", monotonicResolutionNanos)
            return this
        }

        fun withSecureRandom(secureRandom: CryptoRand): Builder {
            this.secureRandom = requirePresent(secureRandom, "secureRandom")
            return this
        }

        fun withSecureRandom(secureRandom: Random): Builder =
            withSecureRandom(KotlinRandomCryptoRand(requirePresent(secureRandom, "secureRandom")))

        fun withInsecureRandom(insecureRandom: Random): Builder {
            this.insecureRandom = requirePresent(insecureRandom, "insecureRandom")
            return this
        }

        fun withInsecureSeed(lower: Long, upper: Long): Builder {
            this.insecureSeedLower = lower
            this.insecureSeedUpper = upper
            return this
        }

        fun withPreopenedDirectory(guestPath: String, hostPath: String): Builder =
            withPreopenedDirectory(guestPath, hostPath.toPath(normalize = true), true)

        fun withPreopenedDirectory(guestPath: String, hostPath: Path): Builder =
            withPreopenedDirectory(guestPath, hostPath, true)

        fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: String): Builder =
            withPreopenedDirectory(guestPath, hostPath.toPath(normalize = true), false)

        fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: Path): Builder =
            withPreopenedDirectory(guestPath, hostPath, false)

        fun withPreopenedDirectory(guestPath: String, hostPath: Path, writable: Boolean): Builder {
            preopens.add(Preopen(guestPath, fileSystem.canonicalize(hostPath), writable))
            return this
        }

        fun withFileSystem(fileSystem: FileSystem): Builder {
            this.fileSystem = requirePresent(fileSystem, "fileSystem")
            return this
        }

        fun withTerminalStdin(terminalStdin: Boolean): Builder {
            this.terminalStdin = terminalStdin
            return this
        }

        fun withTerminalStdout(terminalStdout: Boolean): Builder {
            this.terminalStdout = terminalStdout
            return this
        }

        fun withTerminalStderr(terminalStderr: Boolean): Builder {
            this.terminalStderr = terminalStderr
            return this
        }

        /**
         * Replaces the network capability set. The default policy is [WasiNetworkPolicy.DENY_ALL].
         *
         * HTTP and raw-socket endpoints are granted independently and matched by exact host and
         * port.
         */
        fun withNetworkPolicy(networkPolicy: WasiNetworkPolicy): Builder {
            this.networkPolicy = requirePresent(networkPolicy, "networkPolicy")
            unsafeAllowAllNetworking = false
            return this
        }

        @UnsafeComponentModelApi
        @Deprecated(
            message =
                "Unrestricted networking bypasses endpoint isolation. " +
                    "Use withNetworkPolicy with explicit HTTP and raw-socket grants.",
        )
        fun withNetworking(): Builder {
            unsafeAllowAllNetworking = true
            networkPolicy = WasiNetworkPolicy.DENY_ALL
            return this
        }

        @UnsafeComponentModelApi
        @Deprecated(
            message =
                "Unrestricted networking bypasses endpoint isolation. " +
                    "Use withNetworkPolicy with explicit HTTP and raw-socket grants.",
        )
        fun withNetworking(networkingEnabled: Boolean): Builder {
            if (!networkingEnabled) {
                return withoutNetworking()
            }
            unsafeAllowAllNetworking = true
            networkPolicy = WasiNetworkPolicy.DENY_ALL
            return this
        }

        fun withoutNetworking(): Builder {
            unsafeAllowAllNetworking = false
            networkPolicy = WasiNetworkPolicy.DENY_ALL
            return this
        }

        /**
         * Routes WASI UDP through a browser-compatible WebSocket proxy.
         *
         * The browser still does not get native UDP sockets; the configured proxy is responsible
         * for translating binary WebSocket frames to real UDP datagrams.
         */
        fun withUdpWebSocketProxy(proxyUrl: String): Builder {
            val checkedProxyUrl = requirePresent(proxyUrl, "proxyUrl")
            socketRuntime = null
            socketRuntimeFactory = { webSocketUdpProxySocketRuntime(checkedProxyUrl) }
            return this
        }

        /**
         * Uses a caller-owned HTTP transport. [WasiPreview3.close] never closes the supplied
         * client.
         */
        fun withHttpClient(httpClient: WasiHttpClient): Builder {
            this.httpClient = requirePresent(httpClient, "httpClient")
            return this
        }

        /**
         * Uses a caller-owned Ktor HTTP client. [WasiPreview3.close] never closes the supplied
         * client.
         */
        fun withHttpClient(httpClient: io.ktor.client.HttpClient): Builder =
            withHttpClient(ktorWasiHttpClient(requirePresent(httpClient, "httpClient")))

        fun withHttpHandler(httpHandler: WasiHttpHandler): Builder {
            this.httpHandler = requirePresent(httpHandler, "httpHandler")
            return this
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun withResourceBudget(
            parallelism: Int,
            streamBufferCapacity: Int = DEFAULT_STREAM_BUFFER_CAPACITY,
            maxPendingFutures: Int = 1_024,
            maxPendingStreams: Int = 1_024,
            maxWaitables: Int = maxPendingFutures + maxPendingStreams,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): Builder {
            val checkedParallelism = requireWasiPreview3Limit("parallelism", parallelism)
            if (streamBufferCapacity <= 0 || streamBufferCapacity > STREAM_MAX_LENGTH) {
                throw IllegalArgumentException(
                    "streamBufferCapacity must be between 1 and $STREAM_MAX_LENGTH"
                )
            }
            this.streamBufferCapacity = streamBufferCapacity
            this.maxCanonicalThreads = checkedParallelism
            this.maxInFlightHostTasks = checkedParallelism
            this.maxPendingFutures = requireWasiPreview3Limit("maxPendingFutures", maxPendingFutures)
            this.maxPendingStreams = requireWasiPreview3Limit("maxPendingStreams", maxPendingStreams)
            this.maxWaitables = requireWasiPreview3Limit("maxWaitables", maxWaitables)
            return withCoroutineDispatcher(
                requirePresent(dispatcher, "dispatcher").limitedParallelism(checkedParallelism)
            )
        }

        fun withCoroutineScope(scope: CoroutineScope): Builder {
            coroutineScope = requirePresent(scope, "scope")
            ownsCoroutineScope = false
            return this
        }

        fun withCoroutineDispatcher(dispatcher: CoroutineDispatcher): Builder =
            apply {
                coroutineScope = CoroutineScope(SupervisorJob() + requirePresent(dispatcher, "dispatcher"))
                ownsCoroutineScope = true
            }

        fun build(): WasiPreview3 {
            val ownsHttpClient = httpClient === DefaultWasiPreview3HttpClient
            var ownedHttpClient: WasiPreview3TransportResource? = null
            var ownedSocketRuntime: WasiPreview3TransportResource? = null
            var selectedTransports: WasiPreview3Transports? = null
            try {
                val selectedHttpClient =
                    if (ownsHttpClient) {
                        defaultHttpClientFactory().also { client ->
                            ownedHttpClient =
                                client as? WasiPreview3TransportResource
                                    ?: error(
                                        "The default WASI Preview 3 HTTP client must be closeable"
                                    )
                        }
                    } else {
                        httpClient
                    }
                val selectedSocketRuntime =
                    socketRuntime
                        ?: socketRuntimeFactory().also { runtime ->
                            ownedSocketRuntime =
                                runtime as? WasiPreview3TransportResource
                                    ?: error(
                                        "The default WASI Preview 3 socket runtime must be closeable"
                                    )
                        }
                val transports =
                    WasiPreview3Transports(
                        selectedHttpClient,
                        selectedSocketRuntime,
                        ownedHttpClient,
                        ownedSocketRuntime,
                    )
                selectedTransports = transports
                return WasiPreview3(this, transports)
            } catch (failure: Throwable) {
                val transports = selectedTransports
                if (transports != null) {
                    closeWasiPreview3TransportAfterFailure(failure, transports)
                } else {
                    ownedSocketRuntime?.let { resource ->
                        closeWasiPreview3TransportAfterFailure(failure, resource)
                    }
                    ownedHttpClient?.let { resource ->
                        closeWasiPreview3TransportAfterFailure(failure, resource)
                    }
                }
                throw failure
            }
        }

        private fun requireNanos(name: String, value: Long): Long {
            if (value <= 0L) {
                throw IllegalArgumentException("$name must be positive")
            }
            return value
        }
    }

    class ExitException(private val statusCode: Int) :
        RuntimeException("WASI Preview 3 component exited with status $statusCode") {
        fun statusCode(): Int = statusCode
    }

    private fun interface SocketResourceMethod<T> {
        fun apply(resource: T, args: List<Any?>): Any?
    }

    private interface ContextualSocketResourceMethod<T> : SocketResourceMethod<T> {
        fun apply(resource: T, args: List<Any?>, context: HostCallContext): Any?

        override fun apply(resource: T, args: List<Any?>): Any? =
            apply(resource, args, HostCallContext.SYNC)
    }

    private class HeaderException(val code: String) : RuntimeException(code)

    private class FsException(val code: String) : Exception(code)

    private class HttpException(val code: Any?) : Exception()

    private class NetException(val code: String) : Exception(code)

    private class NameLookupException(val code: String) : Exception(code)

    private class FilesystemIOException(val code: String, cause: IOException) :
        IOException(cause.message, cause)

    internal class Preopen(val guestPath: String, hostPath: Path, writable: Boolean) {
        val hostPath: Path = requirePresent(hostPath, "hostPath").normalized()
        val flags: Set<String>

        init {
            requirePresent(guestPath, "guestPath")
            val next = LinkedHashSet<String>()
            next.add("read")
            if (writable) {
                next.add("mutate-directory")
            }
            flags = next.toSet()
        }
    }

    private class FilesystemDescriptor(
        root: Path,
        path: Path,
        flags: Set<String>,
        val type: Any,
        val identity: Any? = null,
        private val handle: okio.FileHandle? = null,
    ) {
        val root: Path = requirePresent(root, "root").normalized()
        val path: Path = requirePresent(path, "path").normalized()
        val flags: Set<String> = flags.toSet()
        val directory: Boolean = type == "directory"

        fun fileHandle(): okio.FileHandle =
            handle ?: throw FsException("bad-descriptor")

        fun close() {
            handle?.close()
        }
    }

    private class DirectoryEntryStream(entries: Iterator<Map<String, Any?>>) {
        val entries: Iterator<Map<String, Any?>> = requirePresent(entries, "entries")
    }

    private enum class AddressFamily(val label: String) {
        IPV4("ipv4"),
        IPV6("ipv6"),
    }

    private class TcpSocket(val family: AddressFamily) {
        var connection: WasiTcpConnection? = null
        var listener: WasiTcpListener? = null
        var localAddress: InetSocketAddress? = null
        var remoteAddress: InetSocketAddress? = null
        var boundAddressKey: SocketAddressKey? = null
        var globalBoundAddressKey: WasiPreview3TcpBindKey? = null
        var bound: Boolean = false
        var connected: Boolean = false
        var listening: Boolean = false
        var localPolicyAuthorized: Boolean = false
        var sendConsumed: Boolean = false
        var receiveConsumed: Boolean = false
        var listenBacklog: Int = 128
        var keepAlive: Boolean = false
        var keepAliveIdleTimeNanos: Long = 7_200_000_000_000L
        var keepAliveIntervalNanos: Long = 75_000_000_000L
        var keepAliveCount: Int = 9
        var hopLimit: Int = 64
        var receiveBufferSize: Int = 65_536
        var sendBufferSize: Int = 65_536

        fun inheritConnectionOptionsFrom(parent: TcpSocket) {
            keepAlive = parent.keepAlive
            keepAliveIdleTimeNanos = parent.keepAliveIdleTimeNanos
            keepAliveIntervalNanos = parent.keepAliveIntervalNanos
            keepAliveCount = parent.keepAliveCount
            hopLimit = parent.hopLimit
            receiveBufferSize = parent.receiveBufferSize
            sendBufferSize = parent.sendBufferSize
        }
    }

    private class UdpSocket(val family: AddressFamily) {
        var endpoint: WasiUdpEndpoint? = null
        var localAddress: InetSocketAddress? = null
        var remoteAddress: InetSocketAddress? = null
        var bound: Boolean = false
        var unicastHopLimit: Int = 64
        var receiveBufferSize: Int = 65_536
        var sendBufferSize: Int = 65_536
    }

    private data class PendingTcpSend(
        val connection: WasiTcpConnection,
        val streamHandle: Long,
    )

    private data class PendingUdpSend(
        val endpoint: WasiUdpEndpoint,
        val data: ByteArray,
        val remoteAddress: InetSocketAddress,
    )

    private data class SocketAddressKey(
        val family: AddressFamily,
        val address: List<Int>,
        val port: Int,
    )

    private data class ReservedSocketAddress(
        val address: InetSocketAddress,
        val key: SocketAddressKey,
        val globalKey: WasiPreview3TcpBindKey,
    )

    private inner class TcpListenerStream(val socket: TcpSocket) {
        private val lifecycleLock = WasiPreviewLock()
        private var pendingAccepted: TcpSocket? = null
        private var closed: Boolean = false

        fun takeAccepted(): TcpSocket? =
            withWasiPreviewLock(lifecycleLock) {
                if (closed) {
                    return@withWasiPreviewLock null
                }
                pendingAccepted.also { pendingAccepted = null }
            }

        suspend fun awaitReadable() {
            val shouldAccept =
                withWasiPreviewLock(lifecycleLock) {
                    !closed && pendingAccepted == null
                }
            if (!shouldAccept) {
                return
            }
            val accepted = acceptTcpConnectionSocketSuspending(socket)
            val retained =
                withWasiPreviewLock(lifecycleLock) {
                    if (closed || pendingAccepted != null) {
                        false
                    } else {
                        pendingAccepted = accepted
                        true
                    }
                }
            if (!retained) {
                closeTcpSocket(accepted)
            }
        }

        fun close() {
            val pending =
                withWasiPreviewLock(lifecycleLock) {
                    if (closed) {
                        null
                    } else {
                        closed = true
                        pendingAccepted.also { pendingAccepted = null }
                    }
                }
            pending?.let(::closeTcpSocket)
        }
    }

    private inner class TcpReceiveStream(
        private val connection: WasiTcpConnection,
        private val completion: WitFuture<Any?>,
    ) {
        private var cachedRead: TcpReadChunk? = null
        private var cachedBytes: ByteArray? = null
        private var completionResolved: Boolean = false

        fun read(max: Int): TcpReadChunk {
            if (max <= 0) {
                return TcpReadChunk(ByteArray(0), closed = false)
            }
            val pending = cachedRead
            if (pending != null) {
                return consumeCachedRead(pending, max)
            }
            val read =
                try {
                    connection.read(max, 1_000L)
                } catch (_: UnsupportedOperationException) {
                    return TcpReadChunk(ByteArray(0), closed = false)
                } catch (e: Exception) {
                    completeErr(e)
                    return TcpReadChunk(ByteArray(0), closed = true)
                }
            val chunk = TcpReadChunk(read.bytes, read.closed)
            if (chunk.closed) {
                completeOk()
            }
            return chunk
        }

        suspend fun awaitReadable() {
            if (cachedRead != null || cachedBytes?.isNotEmpty() == true) {
                return
            }
            val suspendingConnection = connection as? WasiSuspendingTcpConnection
            if (suspendingConnection != null) {
                val read =
                    try {
                        suspendingConnection.readSuspending(STREAM_MAX_LENGTH)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        completeErr(e)
                        cachedRead = TcpReadChunk(ByteArray(0), closed = true)
                        return
                    }
                cachedRead = TcpReadChunk(read.bytes, read.closed)
                if (read.closed && read.bytes.isEmpty()) {
                    completeOk()
                }
                return
            }
            try {
                connection.awaitReadable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                completeErr(e)
                cachedRead = TcpReadChunk(ByteArray(0), closed = true)
            }
        }

        fun readBytes(): ByteArray {
            val current = cachedBytes
            if (current != null) {
                return current.copyOf()
            }
            val pending = cachedRead
            val bytes =
                if (pending != null) {
                    cachedRead = null
                    val drained =
                        try {
                            val trailing = connection.readUntilIdle(1_000L, 50L)
                            pending.bytes + trailing
                        } catch (_: UnsupportedOperationException) {
                            pending.bytes
                        }
                    if (pending.closed) {
                        completeOk()
                    }
                    drained
                } else {
                    try {
                        connection.readUntilIdle(1_000L, 50L)
                    } catch (_: UnsupportedOperationException) {
                        ByteArray(0)
                    }
                }
            cachedBytes = bytes
            return bytes.copyOf()
        }

        fun drop() {
            cachedRead = null
            cachedBytes = ByteArray(0)
            completeOk()
        }

        private fun consumeCachedRead(pending: TcpReadChunk, max: Int): TcpReadChunk {
            if (pending.bytes.size <= max) {
                cachedRead = null
                if (pending.closed) {
                    completeOk()
                }
                return pending
            }
            val head = pending.bytes.copyOfRange(0, max)
            val tail = pending.bytes.copyOfRange(max, pending.bytes.size)
            cachedRead = TcpReadChunk(tail, pending.closed)
            return TcpReadChunk(head, closed = false)
        }

        private fun completeOk() {
            if (!completionResolved) {
                completionResolved = true
                completeFutureIfPresent(completion, WitResult.ok(null))
            }
        }

        private fun completeErr(e: Exception) {
            if (!completionResolved) {
                completionResolved = true
                completeFutureIfPresent(completion, WitResult.err(socketExceptionCode(e)))
            }
        }
    }

    private data class TcpReadChunk(val bytes: ByteArray, val closed: Boolean)

    private inner class SeekableFileSink(
        descriptor: FilesystemDescriptor,
        private var offset: Long,
    ) : OkioSink {
        private val handle: okio.FileHandle = descriptor.fileHandle()

        override fun write(source: Buffer, byteCount: Long) {
            if (byteCount == 0L) {
                return
            }
            handle.write(offset, source, byteCount)
            offset += byteCount
        }

        override fun flush() {
            handle.flush()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            handle.flush()
        }
    }

    private data class HeaderEntry(val name: String, val value: ByteArray)

    private class HttpFields(val mutable: Boolean) {
        val entries: MutableList<HeaderEntry> = ArrayList()

        fun append(name: String, value: ByteArray) {
            requireMutable()
            requireValidName(name)
            requireValidValue(value)
            entries.add(HeaderEntry(canonicalName(name), value.copyOf()))
        }

        fun get(name: String): List<ByteArray> {
            if (!isValidName(name)) {
                return emptyList()
            }
            val result = ArrayList<ByteArray>()
            for (entry in entries) {
                if (entry.name.equals(name, ignoreCase = true)) {
                    result.add(entry.value.copyOf())
                }
            }
            return result
        }

        fun has(name: String): Boolean = get(name).isNotEmpty()

        fun set(name: String, values: List<ByteArray>) {
            requireMutable()
            requireValidName(name)
            for (value in values) {
                requireValidValue(value)
            }
            val canonical = canonicalName(name)
            deleteExisting(name)
            for (value in values) {
                entries.add(HeaderEntry(canonical, value.copyOf()))
            }
        }

        fun delete(name: String) {
            requireMutable()
            if (!isValidName(name)) {
                throw HeaderException("invalid-syntax")
            }
            deleteExisting(name)
        }

        fun getAndDelete(name: String): List<ByteArray> {
            val current = get(name)
            delete(name)
            return current
        }

        fun entries(): List<List<Any?>> {
            val result = ArrayList<List<Any?>>()
            for (entry in entries) {
                result.add(listOf(entry.name, entry.value.copyOf()))
            }
            return result
        }

        fun copy(mutable: Boolean): HttpFields {
            val copy = HttpFields(mutable)
            for (entry in entries) {
                copy.entries.add(HeaderEntry(entry.name, entry.value.copyOf()))
            }
            return copy
        }

        private fun deleteExisting(name: String) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().name.equals(name, ignoreCase = true)) {
                    iterator.remove()
                }
            }
        }

        private fun requireMutable() {
            if (!mutable) {
                throw HeaderException("immutable")
            }
        }

        private fun requireValidName(name: String) {
            if (!isValidName(name)) {
                throw HeaderException("invalid-syntax")
            }
        }

        private fun requireValidValue(value: ByteArray) {
            for (byte in value) {
                val code = byte.toInt() and 0xff
                if (!(code == 0x09 || code == 0x20 || code in 0x21..0x7e || code >= 0x80)) {
                    throw HeaderException("invalid-syntax")
                }
            }
        }

        private fun canonicalName(name: String): String {
            for (entry in entries) {
                if (entry.name.equals(name, ignoreCase = true)) {
                    return entry.name
                }
            }
            return name
        }

        private fun isValidName(name: String): Boolean =
            name.isNotEmpty() && name.all { char -> isTokenChar(char) }

        private fun isTokenChar(char: Char): Boolean =
            when (char) {
                '!',
                '#',
                '$',
                '%',
                '&',
                '\'',
                '*',
                '+',
                '-',
                '.',
                '^',
                '_',
                '`',
                '|',
                '~' -> true
                in '0'..'9',
                in 'A'..'Z',
                in 'a'..'z' -> true
                else -> false
            }
    }

    private inner class HttpBody(
        val streamData: ByteStreamBuffer,
        val future: WitFuture<Any?>,
        initialResult: WitResult<ByteArray, Any?>?,
    ) {
        private val lock = WasiPreviewLock()
        private val deferred = CompletableDeferred<WitResult<ByteArray, Any?>>()
        private var result: WitResult<ByteArray, Any?>? = initialResult

        init {
            if (initialResult != null) {
                deferred.complete(initialResult)
            }
        }

        fun completedBytesResult(): WitResult<ByteArray, Any?>? =
            withWasiPreviewLock(lock) {
                result
            }

        suspend fun awaitBytes(): ByteArray =
            httpBodyBytesOrThrow(completedBytesResult() ?: deferred.await())

        fun complete(value: WitResult<ByteArray, Any?>) {
            val shouldComplete =
                withWasiPreviewLock(lock) {
                    if (result == null) {
                        result = value
                        true
                    } else {
                        false
                    }
                }
            if (shouldComplete) {
                deferred.complete(value)
                completeFutureIfPresent(future, httpBodyFutureResult(value))
            }
        }

        private fun httpBodyFutureResult(value: WitResult<ByteArray, Any?>): Any? =
            when (value) {
                is WitResult.Ok<*, *> -> WitResult.ok(null)
                is WitResult.Err<*, *> -> WitResult.err(value.value())
            }
    }

    private class HttpRequest(
        var method: Any,
        var pathWithQuery: String?,
        var scheme: Any?,
        var authority: String?,
        val headers: HttpFields,
        val options: RequestOptions?,
        val body: HttpBody,
        val trailers: HttpTrailers,
    )

    private class RequestOptions(
        val mutable: Boolean,
        var connectTimeout: Long? = null,
        var firstByteTimeout: Long? = null,
        var betweenBytesTimeout: Long? = null,
    ) {
        fun copy(mutable: Boolean): RequestOptions =
            RequestOptions(mutable, connectTimeout, firstByteTimeout, betweenBytesTimeout)
    }

    private class HttpResponse(
        var status: Int,
        val headers: HttpFields,
        val body: Any,
        val bodyFinished: Boolean,
        val trailers: HttpTrailers,
    )

    private class HttpTrailers(val rawFutureHandle: Long, val result: WitResult<HttpFields?, Any?>?)

    private class FutureState(
        var value: Any? = null,
        var completed: Boolean = false,
        var readableDropped: Boolean = false,
        var writableDropped: Boolean = false,
    ) {
        private val completion = CompletableDeferred<Any?>()

        init {
            if (completed) {
                completion.complete(value)
            }
        }

        suspend fun awaitValue(): Any? = completion.await()

        fun complete(value: Any?) {
            this.value = value
            completed = true
            writableDropped = true
            completion.complete(value)
        }

        fun cancelReadable() {
            readableDropped = true
            failIfIncomplete("WASI Preview 3 future read was cancelled")
        }

        fun cancelWritable() {
            writableDropped = true
            failIfIncomplete("WASI Preview 3 future write was cancelled")
        }

        fun dropReadable() {
            readableDropped = true
            failIfIncomplete("WASI Preview 3 future readable end was dropped")
        }

        fun dropWritable() {
            writableDropped = true
            failIfIncomplete("WASI Preview 3 future writable end was dropped before completion")
        }

        private fun failIfIncomplete(message: String) {
            if (!completed && !completion.isCompleted) {
                completion.completeExceptionally(ComponentModelException(message))
            }
        }
    }

    private class FutureValue(val state: FutureState)

    private class StreamValue(val kind: String, var data: Any? = ByteArray(0))

    private inner class StreamCompletion(
        private val future: WitFuture<Any?>,
        private val errorCode: (Exception) -> Any?,
    ) {
        private val lock = WasiPreviewLock()
        private var completed = false

        fun ok() {
            complete(WitResult.ok(null))
        }

        fun err(error: Exception) {
            complete(WitResult.err(errorCode(error)))
        }

        private fun complete(value: Any?) {
            val shouldComplete =
                withWasiPreviewLock(lock) {
                    if (completed) {
                        false
                    } else {
                        completed = true
                        true
                    }
                }
            if (shouldComplete) {
                completeFutureIfPresent(future, value)
            }
        }
    }

    private class SourceByteStream(
        private val source: RawSource,
        private val completion: StreamCompletion? = null,
    ) {
        private var closed: Boolean = false
        private var cached: ByteArray? = null

        fun read(max: Int): SourceReadChunk {
            if (max <= 0 || closed) {
                return SourceReadChunk(ByteArray(0), closed)
            }
            val out = KotlinxBuffer()
            val read =
                try {
                    source.readAtMostTo(out, max.toLong())
                } catch (e: Exception) {
                    completion?.err(e)
                    close()
                    return SourceReadChunk(ByteArray(0), closed = true)
                }
            if (read < 0L) {
                close()
                return SourceReadChunk(ByteArray(0), closed = true)
            }
            if (read == 0L) {
                return SourceReadChunk(ByteArray(0), closed = false)
            }
            val bytes = out.readByteArray()
            if ((source as? EndAwareRawSource)?.exhaustedAfterRead() == true) {
                close()
                return SourceReadChunk(bytes, closed = true)
            }
            return SourceReadChunk(bytes, closed = false)
        }

        fun readToMemory(
            context: WasiPreview3CanonicalContext,
            ptr: Int,
            max: Int,
        ): SourceDirectReadChunk {
            if (max <= 0 || closed) {
                return SourceDirectReadChunk(0, closed)
            }
            val direct = source as? WasiMemoryRawSource
            if (direct == null) {
                val read = read(max)
                if (read.bytes.isNotEmpty()) {
                    context.writeMemory(ptr, read.bytes)
                }
                return SourceDirectReadChunk(read.bytes.size, read.closed)
            }
            val count =
                try {
                    direct.readAtMostToMemory(context, ptr, max)
                } catch (e: Exception) {
                    completion?.err(e)
                    close()
                    return SourceDirectReadChunk(0, closed = true)
                }
            if (count < 0) {
                close()
                return SourceDirectReadChunk(0, closed = true)
            }
            if (count == 0) {
                return SourceDirectReadChunk(0, closed = false)
            }
            if ((source as? EndAwareRawSource)?.exhaustedAfterRead() == true) {
                close()
                return SourceDirectReadChunk(count, closed = true)
            }
            return SourceDirectReadChunk(count, closed = false)
        }

        fun readBytes(): ByteArray {
            val current = cached
            if (current != null) {
                return current.copyOf()
            }
            val out = KotlinxBuffer()
            while (!closed) {
                val read =
                    try {
                        source.readAtMostTo(out, 8192L)
                    } catch (e: Exception) {
                        completion?.err(e)
                        close()
                        break
                    }
                if (read <= 0L) {
                    close()
                    break
                }
            }
            val bytes = out.readByteArray()
            cached = bytes
            return bytes.copyOf()
        }

        fun close() {
            if (!closed) {
                closed = true
                try {
                    source.close()
                    completion?.ok()
                } catch (e: Exception) {
                    completion?.err(e)
                }
            }
        }
    }

    private interface EndAwareRawSource {
        fun exhaustedAfterRead(): Boolean
    }

    private class FileHandleByteSource(
        private val handle: okio.FileHandle,
        private var offset: Long,
    ) : WasiMemoryRawSource, EndAwareRawSource {
        private var closed: Boolean = false
        private var exhaustedAfterRead: Boolean = false
        private var buffer = ByteArray(0)

        override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long {
            if (closed) {
                return -1L
            }
            exhaustedAfterRead = false
            if (byteCount <= 0L) {
                return 0L
            }
            val count = byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val buffer = ensureBuffer(count)
            val read = handle.read(offset, buffer, 0, count)
            if (read <= 0) {
                return -1L
            }
            sink.write(buffer, 0, read)
            offset += read
            exhaustedAfterRead = offset >= handle.size()
            return read.toLong()
        }

        override fun readAtMostToMemory(
            context: WasiPreview3CanonicalContext,
            ptr: Int,
            byteCount: Int,
        ): Int {
            if (closed) {
                return -1
            }
            exhaustedAfterRead = false
            if (byteCount <= 0) {
                return 0
            }
            val buffer = ensureBuffer(byteCount)
            val read = handle.read(offset, buffer, 0, byteCount)
            if (read <= 0) {
                return -1
            }
            context.writeMemory(ptr, buffer, 0, read)
            offset += read
            exhaustedAfterRead = offset >= handle.size()
            return read
        }

        override fun close() {
            closed = true
        }

        override fun exhaustedAfterRead(): Boolean = exhaustedAfterRead

        private fun ensureBuffer(size: Int): ByteArray {
            if (buffer.size < size) {
                buffer = ByteArray(size)
            }
            return buffer
        }
    }

    private data class SourceReadChunk(val bytes: ByteArray, val closed: Boolean)

    private data class SourceDirectReadChunk(val count: Int, val closed: Boolean)

    private data class ByteStreamReadChunk(val bytes: ByteArray, val closed: Boolean)

    private class ByteStreamWriteStoppedException : RuntimeException()

    private class ByteStreamBuffer(
        initial: ByteArray = ByteArray(0),
        readableDropped: Boolean = false,
        writableDropped: Boolean = false,
        capacity: Int = DEFAULT_STREAM_BUFFER_CAPACITY,
        private val completion: StreamCompletion? = null,
    ) {
        private val lock = WasiPreviewLock()
        private val data = Buffer()
        private val capacity = maxOf(capacity, initial.size)
        private var readableSignal = CompletableDeferred<Unit>()
        private var writableSignal = CompletableDeferred<Unit>()
        private var directSink: RawSink? = null
        private var directCompletion: ((Any?) -> Unit)? = null
        private var directErrorCode: ((Throwable) -> String)? = null
        var readableDropped: Boolean = readableDropped
            private set
        var writableDropped: Boolean = writableDropped
            private set

        init {
            data.write(initial)
            if (initial.isNotEmpty() || writableDropped) {
                readableSignal.complete(Unit)
            }
        }

        fun snapshotRemaining(): ByteArray {
            withWasiPreviewLock(lock) {
                return data.copy().readByteArray()
            }
        }

        fun read(max: Int): ByteArray {
            withWasiPreviewLock(lock) {
                val count = minOf(max, data.size.toInt())
                if (count <= 0) {
                    if (max > 0 && writableDropped && data.size == 0L) {
                        completion?.ok()
                    }
                    return ByteArray(0)
                }
                val chunk = data.readByteArray(count.toLong())
                writableSignal.complete(Unit)
                writableSignal = CompletableDeferred()
                if (writableDropped && remainingLocked() == 0) {
                    completion?.ok()
                }
                return chunk
            }
        }

        fun write(bytes: ByteArray): Int {
            return write(bytes, 0, bytes.size)
        }

        fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            if (bytes.isEmpty()) {
                return 0
            }
            val sink = withWasiPreviewLock(lock) { directSink }
            if (sink != null) {
                return writeDirectSink(sink, bytes, offset, length)
            }
            withWasiPreviewLock(lock) {
                if (readableDropped || writableDropped) {
                    return 0
                }
                val count = length.coerceAtMost(writableCapacityLocked())
                if (count <= 0) {
                    return 0
                }
                data.write(bytes, offset, count)
                readableSignal.complete(Unit)
                readableSignal = CompletableDeferred()
                return count
            }
        }

        fun remaining(): Int =
            withWasiPreviewLock(lock) {
                remainingLocked()
            }

        fun writableCapacity(): Int =
            withWasiPreviewLock(lock) {
                if (directSink != null && !readableDropped && !writableDropped) {
                    return@withWasiPreviewLock STREAM_MAX_LENGTH
                }
                if (readableDropped || writableDropped) 0 else writableCapacityLocked()
            }

        suspend fun awaitReadable() {
            while (true) {
                val signal =
                    withWasiPreviewLock(lock) {
                        if (readableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream readable end was dropped"
                            )
                        }
                        if (remainingLocked() > 0 || writableDropped) {
                            return
                        }
                        readableSignal
                    }
                signal.await()
            }
        }

        suspend fun awaitWritable() {
            while (true) {
                val signal =
                    withWasiPreviewLock(lock) {
                        if (directSink != null && !readableDropped && !writableDropped) {
                            return
                        }
                        if (writableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream writable end was dropped"
                            )
                        }
                        if (readableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream readable end was dropped"
                            )
                        }
                        if (writableCapacityLocked() > 0) {
                            return
                        }
                        writableSignal
                    }
                signal.await()
            }
        }

        fun cancelReadable() {
            failReadable("WASI Preview 3 stream read was cancelled")
        }

        fun cancelWritable() {
            failWritable("WASI Preview 3 stream write was cancelled")
        }

        fun dropReadable() {
            completion?.ok()
            failReadable("WASI Preview 3 stream readable end was dropped")
        }

        fun dropWritable() {
            var completeDirect = false
            withWasiPreviewLock(lock) {
                if (!writableDropped) {
                    writableDropped = true
                    if (remainingLocked() == 0) {
                        completion?.ok()
                    }
                    completeDirect = directSink != null
                    readableSignal.complete(Unit)
                    readableSignal = CompletableDeferred()
                    writableSignal.complete(Unit)
                    writableSignal = CompletableDeferred()
                }
            }
            if (completeDirect) {
                completeDirectSink(WitResult.ok(null))
            }
        }

        fun pipeToSink(
            sink: RawSink,
            completion: (Any?) -> Unit,
            errorCode: (Throwable) -> String,
        ) {
            var dropped = false
            withWasiPreviewLock(lock) {
                if (readableDropped || writableDropped) {
                    dropped = true
                } else {
                    directSink = sink
                    directCompletion = completion
                    directErrorCode = errorCode
                    writableSignal.complete(Unit)
                    writableSignal = CompletableDeferred()
                }
            }
            if (dropped) {
                closeDirectSink(sink)
                completion(WitResult.err("io"))
            }
        }

        private fun failReadable(message: String) {
            withWasiPreviewLock(lock) {
                readableDropped = true
                readableSignal.completeExceptionally(ComponentModelException(message))
                readableSignal = CompletableDeferred()
                writableSignal.completeExceptionally(ComponentModelException(message))
                writableSignal = CompletableDeferred()
            }
        }

        private fun failWritable(message: String) {
            var completeDirect = false
            withWasiPreviewLock(lock) {
                writableDropped = true
                completeDirect = directSink != null
                readableSignal.completeExceptionally(ComponentModelException(message))
                readableSignal = CompletableDeferred()
                writableSignal.completeExceptionally(ComponentModelException(message))
                writableSignal = CompletableDeferred()
            }
            if (completeDirect) {
                completeDirectSink(WitResult.err("io"))
            }
        }

        private fun remainingLocked(): Int = data.size.toInt()

        private fun writableCapacityLocked(): Int = (capacity - remainingLocked()).coerceAtLeast(0)

        private fun writeDirectSink(
            sink: RawSink,
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length <= 0) {
                return 0
            }
            return try {
                val buffer = KotlinxBuffer()
                buffer.write(bytes, offset, length)
                sink.write(buffer, length.toLong())
                sink.flush()
                length
            } catch (e: Throwable) {
                completeDirectSink(WitResult.err(directErrorCode(e)))
                0
            }
        }

        private fun directErrorCode(error: Throwable): String =
            withWasiPreviewLock(lock) { directErrorCode?.invoke(error) ?: "io" }

        private fun completeDirectSink(result: Any?) {
            var sink: RawSink? = null
            var completion: ((Any?) -> Unit)? = null
            var errorCode: ((Throwable) -> String)? = null
            withWasiPreviewLock(lock) {
                sink = directSink
                completion = directCompletion
                errorCode = directErrorCode
                directSink = null
                directCompletion = null
                directErrorCode = null
            }
            if (sink == null || completion == null) {
                return
            }
            val finalResult =
                try {
                    sink.close()
                    result
                } catch (e: Throwable) {
                    WitResult.err(errorCode?.invoke(e) ?: "io")
                }
            completion(finalResult)
        }

        private fun closeDirectSink(sink: RawSink) {
            try {
                sink.close()
            } catch (_: Throwable) {
            }
        }
    }

    private class ObjectStreamBuffer(
        initial: List<*>,
        readableDropped: Boolean = false,
        writableDropped: Boolean = false,
        capacity: Int = DEFAULT_STREAM_BUFFER_CAPACITY,
        private val completion: StreamCompletion? = null,
    ) {
        private val lock = WasiPreviewLock()
        private val data: MutableList<Any?> = ArrayList(initial)
        private var readOffset: Int = 0
        private val capacity = maxOf(capacity, initial.size)
        private var readableSignal = CompletableDeferred<Unit>()
        private var writableSignal = CompletableDeferred<Unit>()
        var readableDropped: Boolean = readableDropped
            private set
        var writableDropped: Boolean = writableDropped
            private set

        init {
            if (initial.isNotEmpty() || writableDropped) {
                readableSignal.complete(Unit)
            }
        }

        fun snapshotRemaining(): List<Any?> =
            withWasiPreviewLock(lock) {
                ArrayList(data.subList(readOffset, data.size))
            }

        fun read(max: Int): List<Any?> {
            withWasiPreviewLock(lock) {
                val count = minOf(max, remainingLocked())
                if (count <= 0) {
                    if (max > 0 && writableDropped) {
                        completion?.ok()
                    }
                    return emptyList()
                }
                val out = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    out.add(data[readOffset + i])
                }
                readOffset += count
                compactIfNeededLocked()
                writableSignal.complete(Unit)
                writableSignal = CompletableDeferred()
                if (writableDropped && remainingLocked() == 0) {
                    completion?.ok()
                }
                return out
            }
        }

        fun write(values: List<*>): Int {
            if (values.isEmpty()) {
                return 0
            }
            withWasiPreviewLock(lock) {
                if (readableDropped || writableDropped) {
                    return 0
                }
                val count = values.size.coerceAtMost(writableCapacityLocked())
                if (count <= 0) {
                    return 0
                }
                data.addAll(values.subList(0, count))
                readableSignal.complete(Unit)
                readableSignal = CompletableDeferred()
                return count
            }
        }

        fun remaining(): Int =
            withWasiPreviewLock(lock) {
                remainingLocked()
            }

        fun writableCapacity(): Int =
            withWasiPreviewLock(lock) {
                if (readableDropped || writableDropped) 0 else writableCapacityLocked()
            }

        suspend fun awaitReadable() {
            while (true) {
                val signal =
                    withWasiPreviewLock(lock) {
                        if (readableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream readable end was dropped"
                            )
                        }
                        if (remainingLocked() > 0 || writableDropped) {
                            return
                        }
                        readableSignal
                    }
                signal.await()
            }
        }

        suspend fun awaitWritable() {
            while (true) {
                val signal =
                    withWasiPreviewLock(lock) {
                        if (writableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream writable end was dropped"
                            )
                        }
                        if (readableDropped) {
                            throw ComponentModelException(
                                "WASI Preview 3 stream readable end was dropped"
                            )
                        }
                        if (writableCapacityLocked() > 0) {
                            return
                        }
                        writableSignal
                    }
                signal.await()
            }
        }

        fun cancelReadable() {
            failReadable("WASI Preview 3 stream read was cancelled")
        }

        fun cancelWritable() {
            failWritable("WASI Preview 3 stream write was cancelled")
        }

        fun dropReadable() {
            completion?.ok()
            failReadable("WASI Preview 3 stream readable end was dropped")
        }

        fun dropWritable() {
            withWasiPreviewLock(lock) {
                if (!writableDropped) {
                    writableDropped = true
                    if (remainingLocked() == 0) {
                        completion?.ok()
                    }
                    readableSignal.complete(Unit)
                    readableSignal = CompletableDeferred()
                    writableSignal.complete(Unit)
                    writableSignal = CompletableDeferred()
                }
            }
        }

        private fun failReadable(message: String) {
            withWasiPreviewLock(lock) {
                readableDropped = true
                readableSignal.completeExceptionally(ComponentModelException(message))
                readableSignal = CompletableDeferred()
                writableSignal.completeExceptionally(ComponentModelException(message))
                writableSignal = CompletableDeferred()
            }
        }

        private fun failWritable(message: String) {
            withWasiPreviewLock(lock) {
                writableDropped = true
                readableSignal.completeExceptionally(ComponentModelException(message))
                readableSignal = CompletableDeferred()
                writableSignal.completeExceptionally(ComponentModelException(message))
                writableSignal = CompletableDeferred()
            }
        }

        private fun remainingLocked(): Int = data.size - readOffset

        private fun writableCapacityLocked(): Int = (capacity - remainingLocked()).coerceAtLeast(0)

        private fun compactIfNeededLocked() {
            if (readOffset > 0 && readOffset >= data.size / 2) {
                data.subList(0, readOffset).clear()
                readOffset = 0
            }
        }
    }

    private class TerminalInput

    private class TerminalOutput
}
