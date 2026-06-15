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
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import org.kotlincrypto.random.CryptoRand

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

private fun defaultPreview3MonotonicClock(): () -> Long {
    val mark = TimeSource.Monotonic.markNow()
    return { mark.elapsedNow().inWholeNanoseconds }
}

private fun <T : Any> requirePresent(value: T?, name: String): T = requireNotNull(value) { name }

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

class WasiPreview3 private constructor(builder: Builder) : WasiPreview3CanonicalIntrinsics {

    companion object {
        const val DEFAULT_VERSION: String = "0.3.0-rc-2026-03-15"

        @ComponentModelJvmStatic fun builder(): Builder = Builder()
    }

    private val version: String = builder.version
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
    private val networkingEnabled: Boolean = builder.networkingEnabled
    private val httpClient: WasiHttpClient = builder.httpClient
    private val fileSystem: FileSystem = builder.fileSystem
    private val socketRuntime: WasiSocketRuntime = builder.socketRuntime

    private val descriptors: WitResourceTable<FilesystemDescriptor> = WitResourceTable()
    private val directoryEntryStreams: WitResourceTable<DirectoryEntryStream> = WitResourceTable()
    private val fields: WitResourceTable<HttpFields> = WitResourceTable()
    private val requests: WitResourceTable<HttpRequest> = WitResourceTable()
    private val requestOptions: WitResourceTable<RequestOptions> = WitResourceTable()
    private val responses: WitResourceTable<HttpResponse> = WitResourceTable()
    private val tcpSockets: WitResourceTable<TcpSocket> = WitResourceTable()
    private val udpSockets: WitResourceTable<UdpSocket> = WitResourceTable()
    private val futures: WitResourceTable<FutureValue> = WitResourceTable()
    private val streams: WitResourceTable<StreamValue> = WitResourceTable()
    private val terminalInputs: WitResourceTable<TerminalInput> = WitResourceTable()
    private val terminalOutputs: WitResourceTable<TerminalOutput> = WitResourceTable()

    fun version(): String = version

    internal fun preview1SecureRandom(): CryptoRand = secureRandom

    internal fun preview1WallClock(): KotlinClock = wallClock

    internal fun preview1Arguments(): List<String> = arguments

    internal fun preview1Environment(): Map<String, String> = environment

    internal fun preview1Preopens(): List<Preopen> = preopens.toList()

    internal fun preview1FileSystem(): FileSystem = fileSystem

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
                    request.body().copyOf(),
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

    override fun completedFutureHandle(value: Any?): Long = futureHandle(value)

    fun completedFuture(value: Any?): WitFuture<Any?> = WitFuture.of(completedFutureHandle(value))

    @Suppress("UNCHECKED_CAST")
    fun <T> completedFutureOf(value: T): WitFuture<T> = completedFuture(value) as WitFuture<T>

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

    override fun streamNew(payloadType: WitPackage.TypeRef): Long {
        val buffer: Any =
            if (isBytePayload(payloadType)) ByteStreamBuffer()
            else ObjectStreamBuffer(emptyList<Any?>(), writableDropped = false)
        val reader = streamHandle(StreamValue("stream-readable", buffer))
        val writer = streamHandle(StreamValue("stream-writable", buffer))
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
        stream.write(context.readMemory(ptr, length))
        return streamCompleted(length)
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
        requireNetworking()
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
        val read = stream.read(length)
        if (read.bytes.isNotEmpty()) {
            context.writeMemory(ptr, read.bytes)
            return if (read.closed) streamDropped(read.bytes.size)
            else streamCompleted(read.bytes.size)
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
        val values = context.loadListElements(ptr, length, payloadType)
        stream.write(values)
        return streamCompleted(length)
    }

    private fun streamReadTcpListener(
        context: WasiPreview3CanonicalContext,
        listener: TcpListenerStream,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long {
        requireNetworking()
        val length = len.coerceAtMost(STREAM_MAX_LENGTH)
        if (length == 0) {
            return streamCompleted(0)
        }
        if (!listener.socket.listening || listener.socket.listener == null) {
            return streamDropped(0)
        }
        val accepted = listener.socket.listener!!.accept(1_000L) ?: return STREAM_BLOCKED
        val child = TcpSocket(listener.socket.family)
        child.inheritConnectionOptionsFrom(listener.socket)
        child.connection = accepted
        child.bound = true
        child.connected = true
        child.localAddress = accepted.localAddress
        child.remoteAddress = accepted.remoteAddress
        val resource = tcpSockets.insertResource(child)
        context.storeListElements(ptr, payloadType, listOf(resource))
        return streamCompleted(1)
    }

    override fun streamCancelRead(streamHandle: Long): Long {
        streams.get(streamHandle)
        return streamCancelled(0)
    }

    override fun streamCancelWrite(streamHandle: Long): Long {
        streams.get(streamHandle)
        return streamCancelled(0)
    }

    override fun streamDropReadable(streamHandle: Long) {
        val stream = streams.remove(streamHandle)
        when (val buffer = stream.data) {
            is ByteStreamBuffer -> buffer.readableDropped = true
            is ObjectStreamBuffer -> buffer.readableDropped = true
            is SourceByteStream -> buffer.close()
            is RawSource -> buffer.close()
        }
    }

    override fun streamDropWritable(streamHandle: Long) {
        val stream = streams.remove(streamHandle)
        when (val buffer = stream.data) {
            is ByteStreamBuffer -> buffer.writableDropped = true
            is ObjectStreamBuffer -> buffer.writableDropped = true
            is SourceByteStream -> buffer.close()
            is RawSource -> buffer.close()
        }
    }

    override fun futureNew(): Long {
        val state = FutureState()
        val reader = futureHandle(state)
        val writer = futureHandle(state)
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
        state.value = context.loadFutureValue(ptr, payloadType)
        state.completed = true
        state.writableDropped = true
        return streamCompleted(0)
    }

    override fun futureCancelRead(futureHandle: Long): Long {
        futures.get(futureHandle)
        return streamCancelled(0)
    }

    override fun futureCancelWrite(futureHandle: Long): Long {
        futures.get(futureHandle)
        return streamCancelled(0)
    }

    override fun futureDropReadable(futureHandle: Long) {
        val future = futures.remove(futureHandle)
        future.state.readableDropped = true
    }

    override fun futureDropWritable(futureHandle: Long) {
        val future = futures.remove(futureHandle)
        future.state.writableDropped = true
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
        registerFilesystem(builder, "types", "descriptor.read", this::filesystemRead)
        registerFilesystem(builder, "types", "[method]descriptor.read", this::filesystemRead)
        registerFilesystem(builder, "types", "descriptor.write", this::filesystemWrite)
        registerFilesystem(builder, "types", "[method]descriptor.write", this::filesystemWrite)
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
        registerTcpMethod(builder, "listen", this::tcpListen)
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
        return listOf(streamHandle(StreamValue("stdin")), futureHandle(WitResult.ok(null)))
    }

    private fun stdoutWriteViaStream(args: List<Any?>): Any? {
        requireArity("stdout.write-via-stream", args, 1)
        return futureHandle(WitResult.ok(null))
    }

    private fun stderrWriteViaStream(args: List<Any?>): Any? {
        requireArity("stderr.write-via-stream", args, 1)
        return futureHandle(WitResult.ok(null))
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
        if (compareUnsigned(target, now) > 0) {
            sleepNanos(target - now)
        }
        return null
    }

    private fun monotonicClockWaitFor(args: List<Any?>): Any? {
        requireArity("monotonic-clock.wait-for", args, 1)
        sleepNanos(asU64(args[0]))
        return null
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
                        FilesystemDescriptor(preopen.hostPath, preopen.hostPath, preopen.flags)
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
            val data = readAllBytes(descriptor.path)
            val payload =
                if (offset < 0 || offset >= data.size.toLong()) ByteArray(0)
                else data.copyOfRange(offset.toInt(), data.size)
            listOf(
                streamHandle(StreamValue("filesystem-read", payload)),
                futureHandle(WitResult.ok(null)),
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
            val payload = streamBytes(handle(args, 1))
            writeBytes(descriptor.path, payload, asU64(args[2]))
            futureHandle(WitResult.ok(null))
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
            val payload = streamBytes(handle(args, 1))
            fileSystem.appendingSink(descriptor.path).buffer().useSink { output ->
                output.write(payload)
            }
            futureHandle(WitResult.ok(null))
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
        descriptors.get(handle(args, 0))
        return WitResult.ok(null)
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
        return filesystemResult { descriptorType(descriptors.get(handle(args, 0)).path) }
    }

    private fun filesystemSetSize(args: List<Any?>): Any? {
        requireArity("descriptor.set-size", args, 2)
        return filesystemResult {
            val descriptor = writableDescriptor(args, 0)
            fileSystem.openReadWrite(descriptor.path).useHandle { handle ->
                handle.resize(asU64(args[1]))
            }
            null
        }
    }

    private fun filesystemSetTimes(args: List<Any?>): Any? {
        requireArity("descriptor.set-times", args, 3)
        return filesystemResult {
            val descriptor = descriptors.get(handle(args, 0))
            setTimes(descriptor.path, args[1], args[2])
            null
        }
    }

    private fun filesystemRead(args: List<Any?>): Any? {
        requireArity("descriptor.read", args, 3)
        return filesystemResult {
            val descriptor = readableDescriptor(args, 0)
            val length = checkedByteLength(args[1])
            fileSystem.openReadOnly(descriptor.path).useHandle { handle ->
                val buffer = ByteArray(length)
                val read = handle.read(asU64(args[2]), buffer, 0, length)
                if (read < 0) {
                    listOf(ByteArray(0), true)
                } else {
                    listOf(buffer.copyOf(read), read < length)
                }
            }
        }
    }

    private fun filesystemWrite(args: List<Any?>): Any? {
        requireArity("descriptor.write", args, 3)
        return filesystemResult {
            val descriptor = writableDescriptor(args, 0)
            writeBytes(descriptor.path, bytes(args[1]), asU64(args[2]))
        }
    }

    private fun filesystemReadDirectory(args: List<Any?>): Any? {
        requireArity("descriptor.read-directory", args, 1)
        return try {
            val descriptor = descriptors.get(handle(args, 0))
            if (!isDirectory(descriptor.path)) {
                throw FsException("not-directory")
            }
            val entries = ArrayList<Map<String, Any?>>()
            for (path in fileSystem.list(descriptor.path)) {
                entries.add(WitValue.record("type", descriptorType(path), "name", path.name))
            }
            listOf(
                streamHandle(StreamValue("filesystem-directory", entries)),
                futureHandle(WitResult.ok(null)),
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
        return filesystemResult {
            val base = mutableDirectoryDescriptor(args, 0)
            fileSystem.createDirectory(resolvePath(base, string(args[1]), false), mustCreate = true)
            null
        }
    }

    private fun filesystemStat(args: List<Any?>): Any? {
        requireArity("descriptor.stat", args, 1)
        return filesystemResult { descriptorStat(descriptors.get(handle(args, 0)).path) }
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
            setTimes(path, args[3], args[4])
            null
        }
    }

    private fun filesystemLinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.link-at", args, 5)
        return filesystemResult {
            val oldPath =
                resolvePath(
                    descriptors.get(handle(args, 0)),
                    string(args[2]),
                    flag(args[1], "symlink-follow"),
                )
            val newPath = resolvePath(mutableDirectoryDescriptor(args, 3), string(args[4]), false)
            throw FsException("unsupported")
            null
        }
    }

    private fun filesystemOpenAt(args: List<Any?>): Any? {
        requireArity("descriptor.open-at", args, 5)
        return filesystemResult {
            val base = descriptors.get(handle(args, 0))
            val openFlags = args[3]
            val descriptorFlags = descriptorFlags(args[4])
            if (
                (flag(openFlags, "create") ||
                    flag(openFlags, "truncate") ||
                    descriptorFlags.contains("write") ||
                    descriptorFlags.contains("mutate-directory")) &&
                    !base.flags.contains("mutate-directory")
            ) {
                throw FsException("read-only")
            }
            val path = resolvePath(base, string(args[2]), flag(args[1], "symlink-follow"))
            if (flag(openFlags, "exclusive") && fileSystem.exists(path)) {
                throw FsException("exist")
            }
            if (flag(openFlags, "create") && !fileSystem.exists(path)) {
                fileSystem.openReadWrite(path, mustCreate = true).close()
            }
            if (!fileSystem.exists(path)) {
                throw FsException("no-entry")
            }
            if (flag(openFlags, "directory") && !isDirectory(path)) {
                throw FsException("not-directory")
            }
            if (flag(openFlags, "truncate")) {
                fileSystem.openReadWrite(path, mustExist = true).useHandle { handle ->
                    handle.resize(0L)
                }
            }
            descriptors.insertResource(FilesystemDescriptor(base.root, path, descriptorFlags))
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
        return filesystemResult {
            fileSystem.delete(
                resolvePath(mutableDirectoryDescriptor(args, 0), string(args[1]), false),
                mustExist = true,
            )
            null
        }
    }

    private fun filesystemRenameAt(args: List<Any?>): Any? {
        requireArity("descriptor.rename-at", args, 4)
        return filesystemResult {
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
        return try {
            fileSystem.canonicalize(descriptors.get(handle(args, 0)).path) ==
                fileSystem.canonicalize(descriptors.get(handle(args, 1)).path)
        } catch (_: IOException) {
            false
        }
    }

    private fun filesystemMetadataHash(args: List<Any?>): Any? {
        requireArity("descriptor.metadata-hash", args, 1)
        return filesystemResult { metadataHash(descriptors.get(handle(args, 0)).path) }
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
            requireNetworking()
            val addresses = resolveIpAddresses(string(args[0])).map { ipAddress(it) }
            if (addresses.isEmpty()) {
                throw NameLookupException("name-unresolvable")
            }
            addresses
        }
    }

    private fun tcpCreate(args: List<Any?>): Any? {
        requireArity("tcp-socket.create", args, 1)
        return socketResult {
            requireNetworking()
            tcpSockets.insertResource(TcpSocket(addressFamily(args[0])))
        }
    }

    private fun tcpBind(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.bind", args, 2)
        return socketResult {
            requireNetworking()
            if (socket.bound || socket.connected || socket.listening) {
                throw NetException("invalid-state")
            }
            val local = socketAddress(args[1])
            requireFamily(socket.family, local)
            socket.localAddress = local
            socket.bound = true
            null
        }
    }

    private fun tcpConnect(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.connect", args, 2)
        return socketResult {
            requireNetworking()
            if (socket.connected || socket.listening) {
                throw NetException("invalid-state")
            }
            val remote = socketAddress(args[1])
            requireFamily(socket.family, remote)
            val connection =
                socketRuntime.connectTcp(
                    remote,
                    socket.keepAlive,
                    socket.receiveBufferSize,
                    socket.sendBufferSize,
                )
            socket.connection = connection
            socket.remoteAddress = connection.remoteAddress
            socket.localAddress = connection.localAddress
            socket.bound = true
            socket.connected = true
            null
        }
    }

    private fun tcpListen(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.listen", args, 1)
        return socketResult {
            requireNetworking()
            if (socket.connected || socket.listening) {
                throw NetException("invalid-state")
            }
            val local = socket.localAddress ?: wildcardAddress(socket.family)
            val listener = socketRuntime.listenTcp(local, socket.listenBacklog)
            socket.listener = listener
            socket.localAddress = listener.localAddress
            socket.bound = true
            socket.listening = true
            streamHandle(StreamValue("tcp-listener", TcpListenerStream(socket)))
        }
    }

    private fun acceptTcpConnectionResource(socket: TcpSocket): WitResource<Nothing> {
        requireNetworking()
        if (!socket.listening || socket.listener == null) {
            throw NetException("invalid-state")
        }
        val accepted = socket.listener!!.accept(1_000L) ?: throw NetException("timeout")
        val child = TcpSocket(socket.family)
        child.inheritConnectionOptionsFrom(socket)
        child.connection = accepted
        child.bound = true
        child.connected = true
        child.localAddress = accepted.localAddress
        child.remoteAddress = accepted.remoteAddress
        return tcpSockets.insertResource(child)
    }

    private fun tcpSend(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.send", args, 2)
        return socketResult {
            requireNetworking()
            val connection = socket.connection
            if (!socket.connected || connection == null) {
                throw NetException("invalid-state")
            }
            val data = streamBytes(handle(args, 1))
            connection.send(data)
            null
        }
    }

    private fun tcpReceive(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.receive", args, 1)
        return try {
            requireNetworking()
            val connection = socket.connection
            if (!socket.connected || connection == null || socket.receiveConsumed) {
                throw NetException("invalid-state")
            }
            socket.receiveConsumed = true
            listOf(
                streamHandle(StreamValue("tcp-receive", TcpReceiveStream(connection))),
                futureHandle(WitResult.ok(null)),
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
            socket.keepAliveCount = count.toInt()
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
            requireNetworking()
            val family = addressFamily(args[0])
            udpSockets.insertResource(UdpSocket(family))
        }
    }

    private fun udpBind(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.bind", args, 2)
        return socketResult {
            requireNetworking()
            if (socket.bound) {
                throw NetException("invalid-state")
            }
            val local = socketAddress(args[1])
            requireFamily(socket.family, local)
            val endpoint =
                socketRuntime.bindUdp(local, socket.receiveBufferSize, socket.sendBufferSize)
            socket.endpoint = endpoint
            socket.localAddress = endpoint.localAddress
            socket.bound = true
            null
        }
    }

    private fun udpConnect(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.connect", args, 2)
        return socketResult {
            requireNetworking()
            val remote = socketAddress(args[1])
            requireFamily(socket.family, remote)
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
        return socketResult {
            requireNetworking()
            val data = bytes(args[1])
            if (data.size > 65_535) {
                throw NetException("datagram-too-large")
            }
            val remoteValue = option(args[2])
            val remote =
                if (remoteValue == null) socket.remoteAddress
                else socketAddress(remoteValue).also { requireFamily(socket.family, it) }
            if (remote == null) {
                throw NetException("invalid-argument")
            }
            val endpoint = udpEndpoint(socket)
            endpoint.send(data, remote)
            socket.localAddress = endpoint.localAddress
            socket.bound = true
            null
        }
    }

    private fun udpReceive(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.receive", args, 1)
        return socketResult {
            requireNetworking()
            val endpoint = socket.endpoint
            if (!socket.bound || endpoint == null) {
                throw NetException("invalid-state")
            }
            val datagram = endpoint.receive(1_000L) ?: throw NetException("timeout")
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
        if (!networkingEnabled) {
            return WitResult.err("HTTP-request-denied")
        }
        return try {
            val request = requests.get(handle(args, 0))
            val response = sendHttpRequest(request)
            WitResult.ok(
                responses.insertResource(
                    HttpResponse(
                        response.status,
                        fieldsFromHttpHeaders(response.headers, false),
                        response.consumeBodySource(),
                        true,
                        emptyTrailers(),
                    )
                )
            )
        } catch (e: HttpException) {
            WitResult.err(e.code)
        } catch (e: IOException) {
            WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                return WitResult.err(WitValue.variant("internal-error", "interrupted"))
            }
            WitResult.err(httpError(e))
        }
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
        val body = streamBytesFromOption(args[1])
        val trailers = resolveTrailersFuture(args[2])
        val options = optionHandle(args[3])?.let { requestOptions.get(it).copy(false) }
        val request = HttpRequest("get", null, null, null, headers, options, body, trailers)
        return listOf(requests.insertResource(request), futureHandle(WitResult.ok(null)))
    }

    private fun requestMethod(args: List<Any?>): Any? {
        requireArity("request.get-method", args, 1)
        return requests.get(handle(args, 0)).method
    }

    private fun requestSetMethod(args: List<Any?>): Any? {
        requireArity("request.set-method", args, 2)
        requests.get(handle(args, 0)).method = httpMethod(args[1])
        return WitResult.ok(null)
    }

    private fun requestPath(args: List<Any?>): Any? {
        requireArity("request.get-path-with-query", args, 1)
        return requests.get(handle(args, 0)).pathWithQuery
    }

    private fun requestSetPath(args: List<Any?>): Any? {
        requireArity("request.set-path-with-query", args, 2)
        requests.get(handle(args, 0)).pathWithQuery = optionString(args[1])
        return WitResult.ok(null)
    }

    private fun requestScheme(args: List<Any?>): Any? {
        requireArity("request.get-scheme", args, 1)
        return requests.get(handle(args, 0)).scheme
    }

    private fun requestSetScheme(args: List<Any?>): Any? {
        requireArity("request.set-scheme", args, 2)
        requests.get(handle(args, 0)).scheme = option(args[1])?.let { httpScheme(it) }
        return WitResult.ok(null)
    }

    private fun requestAuthority(args: List<Any?>): Any? {
        requireArity("request.get-authority", args, 1)
        return requests.get(handle(args, 0)).authority
    }

    private fun requestSetAuthority(args: List<Any?>): Any? {
        requireArity("request.set-authority", args, 2)
        requests.get(handle(args, 0)).authority = optionString(args[1])
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
            streamHandle(StreamValue("request-body", request.body)),
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
        val body = streamBytesFromOption(args[1])
        val trailers = resolveTrailersFuture(args[2])
        return listOf(
            responses.insertResource(HttpResponse(200, headers, body, true, trailers)),
            futureHandle(WitResult.ok(null)),
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
            streamHandle(StreamValue("response-body", response.body)),
            trailerFutureHandle(response.trailers),
        )
    }

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
        val descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("read")) {
            throw FsException("bad-descriptor")
        }
        if (isDirectory(descriptor.path)) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun writableDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        val descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("write")) {
            throw FsException("bad-descriptor")
        }
        if (isDirectory(descriptor.path)) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun mutableDirectoryDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        val descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("mutate-directory")) {
            throw FsException("read-only")
        }
        if (!isDirectory(descriptor.path)) {
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
        val raw = rawPath.toPath(normalize = true)
        if (raw.isAbsolute) {
            throw FsException("not-permitted")
        }
        val candidate = base.path.resolve(raw, normalize = true)
        val rootReal = fileSystem.canonicalize(base.root)
        val checked = realPathForSandboxCheck(candidate, followLast)
        if (!isInsidePreopen(rootReal, checked)) {
            throw FsException("not-permitted")
        }
        return candidate
    }

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
        return WitValue.record(
            "type",
            descriptorType(path),
            "link-count",
            1L,
            "size",
            metadata.size ?: 0L,
            "data-access-timestamp",
            WitValue.variant("some", datetime(metadata.lastAccessedAtMillis)),
            "data-modification-timestamp",
            WitValue.variant("some", datetime(metadata.lastModifiedAtMillis)),
            "status-change-timestamp",
            WitValue.variant("some", datetime(metadata.lastModifiedAtMillis)),
        )
    }

    private fun metadataHash(path: Path): Map<String, Any?> {
        val metadata = fileSystem.metadata(path)
        val lower =
            hashValues(
                    path.normalized().toString(),
                    metadata.size ?: 0L,
                    metadata.lastModifiedAtMillis ?: 0L,
                )
                .toLong()
        val upper =
            hashValues(
                    metadata.createdAtMillis ?: 0L,
                    metadata.isDirectory,
                    metadata.isRegularFile,
                    metadata.symlinkTarget?.toString(),
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
        if (isDirectory(descriptor.path)) {
            return
        }
        if (descriptor.flags.contains("write")) {
            fileSystem.openReadWrite(descriptor.path, mustExist = true).useHandle { handle ->
                handle.flush()
            }
        }
    }

    private fun setTimes(path: Path, accessTimestamp: Any?, modificationTimestamp: Any?) {
        val access = timestamp(accessTimestamp)
        val modified = timestamp(modificationTimestamp)
        if (access != null || modified != null) {
            throw FsException("unsupported")
        }
    }

    private fun timestamp(value: Any?): Any? {
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

    private fun writeBytes(path: Path, data: ByteArray, offset: Long): Long {
        fileSystem.openReadWrite(path).useHandle { handle ->
            handle.write(offset, data, 0, data.size)
            return data.size.toLong()
        }
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

    private fun requireNetworking() {
        if (!networkingEnabled) {
            throw NetException("access-denied")
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
        socket.endpoint = endpoint
        socket.localAddress = endpoint.localAddress
        socket.bound = true
        return endpoint
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
        if (result <= 0L) {
            throw NetException("invalid-argument")
        }
        return result
    }

    private fun positiveInt(value: Any?): Int {
        val result = positiveLong(value)
        if (result > Int.MAX_VALUE) {
            throw NetException("invalid-argument")
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
        registerSockets(builder, "types", "$resourceName.$methodName") { args ->
            method.apply(table.get(handle(args, 0)), args)
        }
        registerSockets(builder, "types", "[method]$resourceName.$methodName") { args ->
            method.apply(table.get(handle(args, 0)), args)
        }
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
            table.remove(handle(args, 0))
            null
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

    private fun sendHttpRequest(request: HttpRequest): WasiHttpResponse {
        val data =
            httpRequestData(
                request.method,
                request.scheme,
                request.authority,
                request.pathWithQuery,
                request.headers,
                request.body,
                request.options,
            )
        return httpClient.send(data)
    }

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
            body,
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
        return if (label == "other") WitValue.variant("other", stringValue(variantPayload(value)))
        else label
    }

    private fun httpScheme(value: Any?): Any {
        val label = label(value, "HTTP", "HTTPS")
        return if (label == "other") WitValue.variant("other", stringValue(variantPayload(value)))
        else label
    }

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
        if (nanos <= 0L) {
            return
        }
        wasiDelay(nanos.nanoseconds)
    }

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

    private fun futureHandle(value: Any?): Long =
        futureHandle(FutureState(value = value, completed = true, writableDropped = true))

    private fun futureHandle(state: FutureState): Long =
        futures.insertResource(FutureValue(state)).handle()

    private fun streamHandle(value: StreamValue): Long = streams.insertResource(value).handle()

    private fun streamBytesFromOption(value: Any?): ByteArray {
        val stream = option(value) ?: return ByteArray(0)
        return streamBytes(handle(stream))
    }

    private fun responseBodyBytes(body: Any): ByteArray =
        when (body) {
            is ByteArray -> body.copyOf()
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
        var networkingEnabled: Boolean = false
        var httpClient: WasiHttpClient = defaultWasiHttpClient()
        internal var socketRuntime: WasiSocketRuntime = defaultWasiSocketRuntime()

        fun withVersion(version: String): Builder {
            this.version = requirePresent(version, "version")
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

        fun withNetworking(): Builder {
            networkingEnabled = true
            return this
        }

        fun withNetworking(networkingEnabled: Boolean): Builder {
            this.networkingEnabled = networkingEnabled
            return this
        }

        fun withoutNetworking(): Builder {
            networkingEnabled = false
            return this
        }

        fun withHttpClient(httpClient: WasiHttpClient): Builder {
            this.httpClient = requirePresent(httpClient, "httpClient")
            return this
        }

        fun withHttpClient(httpClient: io.ktor.client.HttpClient): Builder =
            withHttpClient(ktorWasiHttpClient(requirePresent(httpClient, "httpClient")))

        fun build(): WasiPreview3 = WasiPreview3(this)

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
                next.add("write")
                next.add("mutate-directory")
            }
            flags = next.toSet()
        }
    }

    private class FilesystemDescriptor(root: Path, path: Path, flags: Set<String>) {
        val root: Path = requirePresent(root, "root").normalized()
        val path: Path = requirePresent(path, "path").normalized()
        val flags: Set<String> = flags.toSet()
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
        var bound: Boolean = false
        var connected: Boolean = false
        var listening: Boolean = false
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

    private class TcpListenerStream(val socket: TcpSocket)

    private inner class TcpReceiveStream(private val connection: WasiTcpConnection) {
        private var cached: ByteArray? = null

        fun read(max: Int): TcpReadChunk {
            if (max <= 0) {
                return TcpReadChunk(ByteArray(0), closed = false)
            }
            val read = connection.read(max, 1_000L)
            return TcpReadChunk(read.bytes, read.closed)
        }

        fun readBytes(): ByteArray {
            val current = cached
            if (current != null) {
                return current.copyOf()
            }
            val bytes = connection.readUntilIdle(1_000L, 50L)
            cached = bytes
            return bytes.copyOf()
        }
    }

    private data class TcpReadChunk(val bytes: ByteArray, val closed: Boolean)

    private data class HeaderEntry(val name: String, val value: ByteArray)

    private class HttpFields(val mutable: Boolean) {
        val entries: MutableList<HeaderEntry> = ArrayList()

        fun append(name: String, value: ByteArray) {
            requireMutable()
            requireValidName(name)
            entries.add(HeaderEntry(name, value.copyOf()))
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
            deleteExisting(name)
            for (value in values) {
                entries.add(HeaderEntry(name, value.copyOf()))
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

        private fun isValidName(name: String): Boolean =
            name.isNotBlank() && name.indexOf('\u0000') < 0
    }

    private class HttpRequest(
        var method: Any,
        var pathWithQuery: String?,
        var scheme: Any?,
        var authority: String?,
        val headers: HttpFields,
        val options: RequestOptions?,
        val body: ByteArray,
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
    )

    private class FutureValue(val state: FutureState)

    private class StreamValue(val kind: String, var data: Any? = ByteArray(0))

    private class SourceByteStream(private val source: RawSource) {
        private var closed: Boolean = false
        private var cached: ByteArray? = null

        fun read(max: Int): SourceReadChunk {
            if (max <= 0 || closed) {
                return SourceReadChunk(ByteArray(0), closed)
            }
            val out = KotlinxBuffer()
            val read = source.readAtMostTo(out, max.toLong())
            if (read < 0L) {
                close()
                return SourceReadChunk(ByteArray(0), closed = true)
            }
            if (read == 0L) {
                return SourceReadChunk(ByteArray(0), closed = false)
            }
            return SourceReadChunk(out.readByteArray(), closed = false)
        }

        fun readBytes(): ByteArray {
            val current = cached
            if (current != null) {
                return current.copyOf()
            }
            val out = KotlinxBuffer()
            while (!closed) {
                val read = source.readAtMostTo(out, 8192L)
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
                source.close()
            }
        }
    }

    private data class SourceReadChunk(val bytes: ByteArray, val closed: Boolean)

    private class ByteStreamBuffer(
        initial: ByteArray = ByteArray(0),
        var readableDropped: Boolean = false,
        var writableDropped: Boolean = false,
    ) {
        private val data = Buffer()
        private var readOffset: Int = 0

        init {
            data.write(initial)
        }

        fun snapshotRemaining(): ByteArray {
            val bytes = data.copy().readByteArray()
            return if (readOffset >= bytes.size) {
                ByteArray(0)
            } else {
                bytes.copyOfRange(readOffset, bytes.size)
            }
        }

        fun read(max: Int): ByteArray {
            val bytes = data.copy().readByteArray()
            if (readOffset >= bytes.size || max <= 0) {
                return ByteArray(0)
            }
            val end = (readOffset + max).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(readOffset, end)
            readOffset = end
            return chunk
        }

        fun write(bytes: ByteArray) {
            data.write(bytes)
        }

        fun remaining(): Int = (data.size - readOffset).toInt()
    }

    private class ObjectStreamBuffer(
        initial: List<*>,
        var readableDropped: Boolean = false,
        var writableDropped: Boolean = false,
    ) {
        private val data: MutableList<Any?> = ArrayList(initial)
        private var readOffset: Int = 0

        fun snapshotRemaining(): List<Any?> = ArrayList(data.subList(readOffset, data.size))

        fun read(max: Int): List<Any?> {
            val count = minOf(max, remaining())
            if (count <= 0) {
                return emptyList()
            }
            val out = ArrayList<Any?>(count)
            for (i in 0 until count) {
                out.add(data[readOffset + i])
            }
            readOffset += count
            compactIfNeeded()
            return out
        }

        fun write(values: List<*>) {
            data.addAll(values)
        }

        fun remaining(): Int = data.size - readOffset

        private fun compactIfNeeded() {
            if (readOffset > 0 && readOffset >= data.size / 2) {
                data.subList(0, readOffset).clear()
                readOffset = 0
            }
        }
    }

    private class TerminalInput

    private class TerminalOutput
}
