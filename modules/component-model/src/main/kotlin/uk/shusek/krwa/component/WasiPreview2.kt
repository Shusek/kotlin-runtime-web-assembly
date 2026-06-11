@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlin.random.Random
import kotlin.time.Clock as KotlinClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant as KotlinInstant
import kotlin.time.TimeSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink as OkioSink
import okio.Source as OkioSource
import okio.Timeout
import org.kotlincrypto.random.CryptoRand

private const val CLI_PACKAGE: String = "wasi:cli"
private const val CLOCKS_PACKAGE: String = "wasi:clocks"
private const val FILESYSTEM_PACKAGE: String = "wasi:filesystem"
private const val HTTP_PACKAGE: String = "wasi:http"
private const val IO_PACKAGE: String = "wasi:io"
private const val RANDOM_PACKAGE: String = "wasi:random"
private const val SOCKETS_PACKAGE: String = "wasi:sockets"
private const val MAX_IO_CHUNK: Int = 4096
private const val MAX_UDP_DATAGRAM: Int = 65_535

private fun defaultMonotonicClock(): () -> Long {
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

private fun isClosedChannel(e: Throwable): Boolean = e::class.simpleName == "ClosedChannelException"

private fun exceptionSimpleName(value: Any?): String = value?.let { it::class.simpleName } ?: "null"

private fun exceptionClassName(value: Any?): String =
    value?.let { it::class.qualifiedName ?: exceptionSimpleName(it) } ?: "null"

private fun compareUnsigned(a: Long, b: Long): Int =
    (a xor Long.MIN_VALUE).compareTo(b xor Long.MIN_VALUE)

private fun unsignedByte(value: Byte): Int = value.toInt() and 0xff

private fun unsignedInt(value: Int): Long = value.toLong() and 0xffff_ffffL

private fun unsignedLongString(value: Long): String = value.toULong().toString()

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

private fun camelCaseMemberName(name: String): String {
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

class WasiPreview2 private constructor(builder: Builder) {

    companion object {
        const val DEFAULT_VERSION: String = "0.2.11"
        const val KOTLIN_COMPONENT_MODEL_VERSION: String = "0.2.9"

        private val COMPATIBLE_VERSIONS: List<String> =
            listOf("0.2.12", DEFAULT_VERSION, KOTLIN_COMPONENT_MODEL_VERSION, "0.2.0")

        @ComponentModelJvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }

    private val version: String
    private val stdin: WasiInputStream
    private val stdout: WasiOutputStream
    private val stderr: WasiOutputStream
    private val arguments: List<String>
    private val environment: Map<String, String>
    private val initialCwd: String?
    private val wallClock: KotlinClock
    private val wallClockTimeZone: TimeZone
    private val wallClockResolutionNanos: Long
    private val monotonicClock: () -> Long
    private val monotonicBaseNanos: Long
    private val monotonicResolutionNanos: Long
    private val secureRandom: CryptoRand
    private val insecureRandom: Random
    private val insecureSeedLower: Long
    private val insecureSeedUpper: Long
    private val preopens: List<Preopen>
    private val terminalStdin: Boolean
    private val terminalStdout: Boolean
    private val terminalStderr: Boolean
    private val networkingEnabled: Boolean
    private val httpClient: WasiHttpClient
    private val fileSystem: FileSystem
    private val socketRuntime: WasiSocketRuntime
    private val inputStreams: WitResourceTable<WasiInputStream> = WitResourceTable()
    private val outputStreams: WitResourceTable<WasiOutputStream> = WitResourceTable()
    private val descriptors: WitResourceTable<FilesystemDescriptor> = WitResourceTable()
    private val directoryEntryStreams: WitResourceTable<DirectoryEntryStream> = WitResourceTable()
    private val errors: WitResourceTable<WasiError> = WitResourceTable()
    private val pollables: WitResourceTable<Pollable> = WitResourceTable()
    private val terminalInputs: WitResourceTable<TerminalInput> = WitResourceTable()
    private val terminalOutputs: WitResourceTable<TerminalOutput> = WitResourceTable()
    private val networks: WitResourceTable<Network> = WitResourceTable()
    private val resolveAddressStreams: WitResourceTable<ResolveAddressStream> = WitResourceTable()
    private val tcpSockets: WitResourceTable<TcpSocket> = WitResourceTable()
    private val udpSockets: WitResourceTable<UdpSocket> = WitResourceTable()
    private val incomingDatagramStreams: WitResourceTable<IncomingDatagramStream> =
        WitResourceTable()
    private val outgoingDatagramStreams: WitResourceTable<OutgoingDatagramStream> =
        WitResourceTable()
    private val httpFields: WitResourceTable<HttpFields> = WitResourceTable()
    private val incomingRequests: WitResourceTable<IncomingRequest> = WitResourceTable()
    private val outgoingRequests: WitResourceTable<OutgoingRequest> = WitResourceTable()
    private val requestOptions: WitResourceTable<RequestOptions> = WitResourceTable()
    private val responseOutparams: WitResourceTable<ResponseOutparam> = WitResourceTable()
    private val incomingResponses: WitResourceTable<IncomingResponse> = WitResourceTable()
    private val incomingBodies: WitResourceTable<IncomingBody> = WitResourceTable()
    private val futureTrailers: WitResourceTable<FutureTrailers> = WitResourceTable()
    private val outgoingResponses: WitResourceTable<OutgoingResponse> = WitResourceTable()
    private val outgoingBodies: WitResourceTable<OutgoingBody> = WitResourceTable()
    private val futureIncomingResponses: WitResourceTable<FutureIncomingResponse> =
        WitResourceTable()

    init {
        this.version = builder.version
        this.stdin = builder.stdin
        this.stdout = builder.stdout
        this.stderr = builder.stderr
        this.arguments = builder.arguments.toList()
        this.environment = builder.environment.toMap()
        this.initialCwd = builder.initialCwd
        this.wallClock = builder.wallClock
        this.wallClockTimeZone = builder.wallClockTimeZone
        this.wallClockResolutionNanos = builder.wallClockResolutionNanos
        this.monotonicClock = builder.monotonicClock
        this.monotonicBaseNanos = builder.monotonicClock()
        this.monotonicResolutionNanos = builder.monotonicResolutionNanos
        this.secureRandom = builder.secureRandom
        this.insecureRandom = builder.insecureRandom
        this.insecureSeedLower = builder.insecureSeedLower
        this.insecureSeedUpper = builder.insecureSeedUpper
        this.preopens = builder.preopens.toList()
        this.terminalStdin = builder.terminalStdin
        this.terminalStdout = builder.terminalStdout
        this.terminalStderr = builder.terminalStderr
        this.networkingEnabled = builder.networkingEnabled
        this.httpClient = builder.httpClient
        this.fileSystem = builder.fileSystem
        this.socketRuntime = builder.socketRuntime
    }

    fun version(): String {
        return version
    }

    fun arguments(): List<String> {
        return arguments
    }

    fun environment(): Map<String, String> {
        return environment
    }

    fun initialCwd(): String? {
        return initialCwd
    }

    fun inputStreams(): WitResourceTable<WasiInputStream> {
        return inputStreams
    }

    fun outputStreams(): WitResourceTable<WasiOutputStream> {
        return outputStreams
    }

    fun handleHttpRequest(
        plugin: WasiComponentInvoker,
        request: HttpRequestSnapshot,
    ): HttpResponseSnapshot {
        requirePresent(plugin, "plugin")
        requirePresent(request, "request")
        val incomingRequest =
            newIncomingRequest(
                request.method(),
                request.pathWithQuery(),
                request.scheme(),
                request.authority(),
                request.headers(),
                request.body(),
            )
        val responseOutparam = newResponseOutparam()
        plugin.call("incoming-handler.handle", incomingRequest, responseOutparam)
        return responseOutparamHttpResponse(responseOutparam)
    }

    fun handleHttpRequest(
        plugin: WasiComponentInvoker,
        method: String,
        pathWithQuery: String,
        scheme: String,
        authority: String,
        headers: Map<String, List<ByteArray>>,
        body: ByteArray,
    ): HttpResponseSnapshot =
        handleHttpRequest(
            plugin,
            HttpRequestSnapshot(method, pathWithQuery, scheme, authority, headers, body),
        )

    fun newIncomingRequest(
        method: String,
        pathWithQuery: String,
        scheme: String,
        authority: String,
        headers: Map<String, List<ByteArray>>,
        body: ByteArray,
    ): WitResource<IncomingRequestTag> {
        try {
            return incomingRequests.insertResource(
                IncomingRequest(
                    incomingMethod(method),
                    pathWithQuery,
                    incomingScheme(scheme),
                    authority,
                    fieldsFromByteHeaders(headers, false),
                    if (body == null) ByteArray(0) else body,
                )
            )
        } catch (e: HttpException) {
            throw ComponentModelException("invalid incoming HTTP request: " + e.code, e)
        }
    }

    fun newResponseOutparam(): WitResource<ResponseOutparamTag> {
        return responseOutparams.insertResource(ResponseOutparam())
    }

    fun responseOutparamIsSet(responseOutparam: WitResource<ResponseOutparamTag>): Boolean {
        return responseOutparams.get(responseOutparam).set
    }

    fun responseOutparamResponse(responseOutparam: WitResource<ResponseOutparamTag>): Any? {
        return responseOutparams.get(responseOutparam).response
    }

    fun responseOutparamHttpResponse(
        responseOutparam: WitResource<ResponseOutparamTag>
    ): HttpResponseSnapshot {
        var outparam = responseOutparams.get(responseOutparam)
        if (!outparam.set) {
            throw ComponentModelException("HTTP response-outparam has not been set")
        }
        if (!(outparam.response is WitValue.Variant)) {
            throw ComponentModelException(
                "HTTP response-outparam contains non-result value " + outparam.response
            )
        }
        var result = outparam.response as WitValue.Variant
        if ("err".equals(result.label())) {
            throw ComponentModelException("HTTP handler returned error " + result.value())
        }
        if (!"ok".equals(result.label())) {
            throw ComponentModelException(
                "HTTP response-outparam contains unexpected result " + result
            )
        }
        var response = outgoingResponses.get(asU64(result.value()))
        val responseBody = response.body
        var body = responseBody?.bodyBytes() ?: ByteArray(0)
        var bodyFinished = responseBody == null || responseBody.finished
        return HttpResponseSnapshot(
            response.status,
            httpHeadersSnapshot(response.headers),
            body,
            bodyFinished,
        )
    }

    fun <T : WasiHostImportBuilder> install(builder: T): T {
        requirePresent(builder, "builder")

        register(builder, CLI_PACKAGE, "stdin", "get-stdin", this::getStdin)
        register(builder, CLI_PACKAGE, "stdout", "get-stdout", this::getStdout)
        register(builder, CLI_PACKAGE, "stderr", "get-stderr", this::getStderr)
        register(builder, CLI_PACKAGE, "environment", "get-environment", this::getEnvironment)
        register(builder, CLI_PACKAGE, "environment", "get-arguments", this::getArguments)
        register(builder, CLI_PACKAGE, "environment", "initial-cwd", this::initialCwd)
        register(builder, CLI_PACKAGE, "exit", "exit", this::exit)
        register(builder, CLI_PACKAGE, "exit", "exit-with-code", this::exitWithCode)
        register(
            builder,
            CLI_PACKAGE,
            "terminal-stdin",
            "get-terminal-stdin",
            this::getTerminalStdin,
        )
        register(
            builder,
            CLI_PACKAGE,
            "terminal-stdout",
            "get-terminal-stdout",
            this::getTerminalStdout,
        )
        register(
            builder,
            CLI_PACKAGE,
            "terminal-stderr",
            "get-terminal-stderr",
            this::getTerminalStderr,
        )
        registerResourceDrop(
            builder,
            CLI_PACKAGE,
            "terminal-input",
            "terminal-input",
            terminalInputs,
        )
        registerResourceDrop(
            builder,
            CLI_PACKAGE,
            "terminal-output",
            "terminal-output",
            terminalOutputs,
        )

        register(builder, CLOCKS_PACKAGE, "wall-clock", "now", this::wallClockNow)
        register(builder, CLOCKS_PACKAGE, "wall-clock", "resolution", this::wallClockResolution)
        register(builder, CLOCKS_PACKAGE, "monotonic-clock", "now", this::monotonicClockNow)
        register(
            builder,
            CLOCKS_PACKAGE,
            "monotonic-clock",
            "resolution",
            this::monotonicClockResolution,
        )
        register(
            builder,
            CLOCKS_PACKAGE,
            "monotonic-clock",
            "subscribe-instant",
            this::subscribeInstant,
        )
        register(
            builder,
            CLOCKS_PACKAGE,
            "monotonic-clock",
            "subscribe-duration",
            this::subscribeDuration,
        )
        register(builder, CLOCKS_PACKAGE, "timezone", "display", this::timezoneDisplay)
        register(builder, CLOCKS_PACKAGE, "timezone", "utc-offset", this::timezoneUtcOffset)

        register(
            builder,
            FILESYSTEM_PACKAGE,
            "preopens",
            "get-directories",
            this::filesystemGetDirectories,
        )

        registerResourceMethod(
            builder,
            "descriptor",
            "read-via-stream",
            this::filesystemReadViaStream,
        )
        registerResourceMethod(
            builder,
            "descriptor",
            "write-via-stream",
            this::filesystemWriteViaStream,
        )
        registerResourceMethod(
            builder,
            "descriptor",
            "append-via-stream",
            this::filesystemAppendViaStream,
        )
        registerResourceMethod(builder, "descriptor", "advise", this::filesystemAdvise)
        registerResourceMethod(builder, "descriptor", "sync-data", this::filesystemSyncData)
        registerResourceMethod(builder, "descriptor", "get-flags", this::filesystemGetFlags)
        registerResourceMethod(builder, "descriptor", "get-type", this::filesystemGetType)
        registerResourceMethod(builder, "descriptor", "set-size", this::filesystemSetSize)
        registerResourceMethod(builder, "descriptor", "set-times", this::filesystemSetTimes)
        registerResourceMethod(builder, "descriptor", "read", this::filesystemRead)
        registerResourceMethod(builder, "descriptor", "write", this::filesystemWrite)
        registerResourceMethod(
            builder,
            "descriptor",
            "read-directory",
            this::filesystemReadDirectory,
        )
        registerResourceMethod(builder, "descriptor", "sync", this::filesystemSync)
        registerResourceMethod(
            builder,
            "descriptor",
            "create-directory-at",
            this::filesystemCreateDirectoryAt,
        )
        registerResourceMethod(builder, "descriptor", "stat", this::filesystemStat)
        registerResourceMethod(builder, "descriptor", "stat-at", this::filesystemStatAt)
        registerResourceMethod(builder, "descriptor", "set-times-at", this::filesystemSetTimesAt)
        registerResourceMethod(builder, "descriptor", "link-at", this::filesystemLinkAt)
        registerResourceMethod(builder, "descriptor", "open-at", this::filesystemOpenAt)
        registerResourceMethod(builder, "descriptor", "readlink-at", this::filesystemReadlinkAt)
        registerResourceMethod(
            builder,
            "descriptor",
            "remove-directory-at",
            this::filesystemRemoveDirectoryAt,
        )
        registerResourceMethod(builder, "descriptor", "rename-at", this::filesystemRenameAt)
        registerResourceMethod(builder, "descriptor", "symlink-at", this::filesystemSymlinkAt)
        registerResourceMethod(
            builder,
            "descriptor",
            "unlink-file-at",
            this::filesystemUnlinkFileAt,
        )
        registerResourceMethod(
            builder,
            "descriptor",
            "is-same-object",
            this::filesystemIsSameObject,
        )
        registerResourceMethod(builder, "descriptor", "metadata-hash", this::filesystemMetadataHash)
        registerResourceMethod(
            builder,
            "descriptor",
            "metadata-hash-at",
            this::filesystemMetadataHashAt,
        )
        registerResourceDrop(builder, FILESYSTEM_PACKAGE, "types", "descriptor", descriptors)

        registerResourceMethod(
            builder,
            "directory-entry-stream",
            "read-directory-entry",
            this::filesystemReadDirectoryEntry,
        )
        registerResourceDrop(
            builder,
            FILESYSTEM_PACKAGE,
            "types",
            "directory-entry-stream",
            directoryEntryStreams,
        )
        register(
            builder,
            FILESYSTEM_PACKAGE,
            "types",
            "filesystem-error-code",
            this::filesystemErrorCode,
        )

        register(builder, HTTP_PACKAGE, "types", "http-error-code", this::httpErrorCode)
        registerHttpConstructor(builder, "fields", this::httpFieldsConstructor)
        registerHttpStatic(builder, "fields", "from-list", this::httpFieldsFromList)
        registerHttpMethod(builder, "fields", "get", this::httpFieldsGet)
        registerHttpMethod(builder, "fields", "has", this::httpFieldsHas)
        registerHttpMethod(builder, "fields", "set", this::httpFieldsSet)
        registerHttpMethod(builder, "fields", "delete", this::httpFieldsDelete)
        registerHttpMethod(builder, "fields", "append", this::httpFieldsAppend)
        registerHttpMethod(builder, "fields", "entries", this::httpFieldsEntries)
        registerHttpMethod(builder, "fields", "clone", this::httpFieldsClone)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "fields", httpFields)

        registerHttpMethod(builder, "incoming-request", "method", this::incomingRequestMethod)
        registerHttpMethod(
            builder,
            "incoming-request",
            "path-with-query",
            this::incomingRequestPath,
        )
        registerHttpMethod(builder, "incoming-request", "scheme", this::incomingRequestScheme)
        registerHttpMethod(builder, "incoming-request", "authority", this::incomingRequestAuthority)
        registerHttpMethod(builder, "incoming-request", "headers", this::incomingRequestHeaders)
        registerHttpMethod(builder, "incoming-request", "consume", this::incomingRequestConsume)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "incoming-request", incomingRequests)

        registerHttpConstructor(builder, "outgoing-request", this::outgoingRequestConstructor)
        registerHttpMethod(builder, "outgoing-request", "body", this::outgoingRequestBody)
        registerHttpMethod(builder, "outgoing-request", "method", this::outgoingRequestMethod)
        registerHttpMethod(
            builder,
            "outgoing-request",
            "set-method",
            this::outgoingRequestSetMethod,
        )
        registerHttpMethod(
            builder,
            "outgoing-request",
            "path-with-query",
            this::outgoingRequestPath,
        )
        registerHttpMethod(
            builder,
            "outgoing-request",
            "set-path-with-query",
            this::outgoingRequestSetPath,
        )
        registerHttpMethod(builder, "outgoing-request", "scheme", this::outgoingRequestScheme)
        registerHttpMethod(
            builder,
            "outgoing-request",
            "set-scheme",
            this::outgoingRequestSetScheme,
        )
        registerHttpMethod(builder, "outgoing-request", "authority", this::outgoingRequestAuthority)
        registerHttpMethod(
            builder,
            "outgoing-request",
            "set-authority",
            this::outgoingRequestSetAuthority,
        )
        registerHttpMethod(builder, "outgoing-request", "headers", this::outgoingRequestHeaders)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "outgoing-request", outgoingRequests)

        registerHttpConstructor(builder, "request-options", this::requestOptionsConstructor)
        registerHttpMethod(
            builder,
            "request-options",
            "connect-timeout",
            this::requestOptionsConnect,
        )
        registerHttpMethod(
            builder,
            "request-options",
            "set-connect-timeout",
            this::requestOptionsSetConnect,
        )
        registerHttpMethod(
            builder,
            "request-options",
            "first-byte-timeout",
            this::requestOptionsFirstByte,
        )
        registerHttpMethod(
            builder,
            "request-options",
            "set-first-byte-timeout",
            this::requestOptionsSetFirstByte,
        )
        registerHttpMethod(
            builder,
            "request-options",
            "between-bytes-timeout",
            this::requestOptionsBetweenBytes,
        )
        registerHttpMethod(
            builder,
            "request-options",
            "set-between-bytes-timeout",
            this::requestOptionsSetBetweenBytes,
        )
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "request-options", requestOptions)

        registerHttpMethod(
            builder,
            "response-outparam",
            "send-informational",
            this::responseOutparamSendInformational,
        )
        registerHttpStatic(builder, "response-outparam", "set", this::responseOutparamSet)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "response-outparam", responseOutparams)

        registerHttpMethod(builder, "incoming-response", "status", this::incomingResponseStatus)
        registerHttpMethod(builder, "incoming-response", "headers", this::incomingResponseHeaders)
        registerHttpMethod(builder, "incoming-response", "consume", this::incomingResponseConsume)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "incoming-response", incomingResponses)

        registerHttpMethod(builder, "incoming-body", "stream", this::incomingBodyStream)
        registerHttpStatic(builder, "incoming-body", "finish", this::incomingBodyFinish)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "incoming-body", incomingBodies)

        registerHttpMethod(builder, "future-trailers", "subscribe", this::futureTrailersSubscribe)
        registerHttpMethod(builder, "future-trailers", "get", this::futureTrailersGet)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "future-trailers", futureTrailers)

        registerHttpConstructor(builder, "outgoing-response", this::outgoingResponseConstructor)
        registerHttpMethod(
            builder,
            "outgoing-response",
            "status-code",
            this::outgoingResponseStatus,
        )
        registerHttpMethod(
            builder,
            "outgoing-response",
            "set-status-code",
            this::outgoingResponseSetStatus,
        )
        registerHttpMethod(builder, "outgoing-response", "headers", this::outgoingResponseHeaders)
        registerHttpMethod(builder, "outgoing-response", "body", this::outgoingResponseBody)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "outgoing-response", outgoingResponses)

        registerHttpMethod(builder, "outgoing-body", "write", this::outgoingBodyWrite)
        registerHttpStatic(builder, "outgoing-body", "finish", this::outgoingBodyFinish)
        registerResourceDrop(builder, HTTP_PACKAGE, "types", "outgoing-body", outgoingBodies)

        registerHttpMethod(
            builder,
            "future-incoming-response",
            "subscribe",
            this::futureIncomingResponseSubscribe,
        )
        registerHttpMethod(
            builder,
            "future-incoming-response",
            "get",
            this::futureIncomingResponseGet,
        )
        registerResourceDrop(
            builder,
            HTTP_PACKAGE,
            "types",
            "future-incoming-response",
            futureIncomingResponses,
        )
        register(builder, HTTP_PACKAGE, "outgoing-handler", "handle", this::outgoingHandlerHandle)

        register(builder, IO_PACKAGE, "error", "error.to-debug-string", this::errorToDebugString)
        register(
            builder,
            IO_PACKAGE,
            "error",
            "[method]error.to-debug-string",
            this::errorToDebugString,
        )
        registerResourceDrop(builder, IO_PACKAGE, "error", "error", errors)

        register(builder, IO_PACKAGE, "poll", "pollable.ready", this::pollableReady)
        register(builder, IO_PACKAGE, "poll", "[method]pollable.ready", this::pollableReady)
        register(builder, IO_PACKAGE, "poll", "pollable.block", this::pollableBlock)
        register(builder, IO_PACKAGE, "poll", "[method]pollable.block", this::pollableBlock)
        register(builder, IO_PACKAGE, "poll", "poll", this::poll)
        registerResourceDrop(builder, IO_PACKAGE, "poll", "pollable", pollables)

        register(builder, IO_PACKAGE, "streams", "input-stream.read", { args -> read(args, false) })
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]input-stream.read",
            { args -> read(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "input-stream.blocking-read",
            { args -> read(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]input-stream.blocking-read",
            { args -> read(args, true) },
        )
        register(builder, IO_PACKAGE, "streams", "input-stream.skip", { args -> skip(args, false) })
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]input-stream.skip",
            { args -> skip(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "input-stream.blocking-skip",
            { args -> skip(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]input-stream.blocking-skip",
            { args -> skip(args, true) },
        )
        register(builder, IO_PACKAGE, "streams", "input-stream.subscribe", this::subscribeInput)
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]input-stream.subscribe",
            this::subscribeInput,
        )
        registerResourceDrop(builder, IO_PACKAGE, "streams", "input-stream", inputStreams)

        register(builder, IO_PACKAGE, "streams", "output-stream.check-write", this::checkWrite)
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.check-write",
            this::checkWrite,
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.write",
            { args -> write(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.write",
            { args -> write(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.blocking-write-and-flush",
            { args -> write(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.blocking-write-and-flush",
            { args -> write(args, true) },
        )
        register(builder, IO_PACKAGE, "streams", "output-stream.flush", this::flush)
        register(builder, IO_PACKAGE, "streams", "[method]output-stream.flush", this::flush)
        register(builder, IO_PACKAGE, "streams", "output-stream.blocking-flush", this::flush)
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.blocking-flush",
            this::flush,
        )
        register(builder, IO_PACKAGE, "streams", "output-stream.subscribe", this::subscribeOutput)
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.subscribe",
            this::subscribeOutput,
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.write-zeroes",
            { args -> writeZeroes(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.write-zeroes",
            { args -> writeZeroes(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.blocking-write-zeroes-and-flush",
            { args -> writeZeroes(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.blocking-write-zeroes-and-flush",
            { args -> writeZeroes(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.splice",
            { args -> splice(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.splice",
            { args -> splice(args, false) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "output-stream.blocking-splice",
            { args -> splice(args, true) },
        )
        register(
            builder,
            IO_PACKAGE,
            "streams",
            "[method]output-stream.blocking-splice",
            { args -> splice(args, true) },
        )
        registerResourceDrop(builder, IO_PACKAGE, "streams", "output-stream", outputStreams)

        register(builder, RANDOM_PACKAGE, "random", "get-random-bytes", this::getRandomBytes)
        register(builder, RANDOM_PACKAGE, "random", "get-random-u64", this::getRandomU64)
        register(
            builder,
            RANDOM_PACKAGE,
            "insecure",
            "get-insecure-random-bytes",
            this::getInsecureRandomBytes,
        )
        register(
            builder,
            RANDOM_PACKAGE,
            "insecure",
            "get-insecure-random-u64",
            this::getInsecureRandomU64,
        )
        register(builder, RANDOM_PACKAGE, "insecure-seed", "insecure-seed", this::getInsecureSeed)

        register(
            builder,
            SOCKETS_PACKAGE,
            "instance-network",
            "instance-network",
            this::instanceNetwork,
        )
        register(builder, SOCKETS_PACKAGE, "network", "network-error-code", this::networkErrorCode)
        registerResourceDrop(builder, SOCKETS_PACKAGE, "network", "network", networks)

        register(
            builder,
            SOCKETS_PACKAGE,
            "ip-name-lookup",
            "resolve-addresses",
            this::resolveAddresses,
        )
        registerSocketsResourceMethod(
            builder,
            "ip-name-lookup",
            "resolve-address-stream",
            "resolve-next-address",
            this::resolveNextAddress,
        )
        registerSocketsResourceMethod(
            builder,
            "ip-name-lookup",
            "resolve-address-stream",
            "subscribe",
            this::resolveAddressSubscribe,
        )
        registerResourceDrop(
            builder,
            SOCKETS_PACKAGE,
            "ip-name-lookup",
            "resolve-address-stream",
            resolveAddressStreams,
        )

        register(
            builder,
            SOCKETS_PACKAGE,
            "tcp-create-socket",
            "create-tcp-socket",
            this::createTcpSocket,
        )
        registerTcpMethod(builder, "start-bind", this::tcpStartBind)
        registerTcpMethod(builder, "finish-bind", this::tcpFinishBind)
        registerTcpMethod(builder, "start-connect", this::tcpStartConnect)
        registerTcpMethod(builder, "finish-connect", this::tcpFinishConnect)
        registerTcpMethod(builder, "start-listen", this::tcpStartListen)
        registerTcpMethod(builder, "finish-listen", this::tcpFinishListen)
        registerTcpMethod(builder, "accept", this::tcpAccept)
        registerTcpMethod(builder, "local-address", this::tcpLocalAddress)
        registerTcpMethod(builder, "remote-address", this::tcpRemoteAddress)
        registerTcpMethod(builder, "is-listening", this::tcpIsListening)
        registerTcpMethod(builder, "address-family", this::tcpAddressFamily)
        registerTcpMethod(builder, "set-listen-backlog-size", this::tcpSetListenBacklogSize)
        registerTcpMethod(builder, "keep-alive-enabled", this::tcpKeepAliveEnabled)
        registerTcpMethod(builder, "set-keep-alive-enabled", this::tcpSetKeepAliveEnabled)
        registerTcpMethod(builder, "keep-alive-idle-time", this::tcpKeepAliveIdleTime)
        registerTcpMethod(builder, "set-keep-alive-idle-time", this::tcpSetKeepAliveIdleTime)
        registerTcpMethod(builder, "keep-alive-interval", this::tcpKeepAliveInterval)
        registerTcpMethod(builder, "set-keep-alive-interval", this::tcpSetKeepAliveInterval)
        registerTcpMethod(builder, "keep-alive-count", this::tcpKeepAliveCount)
        registerTcpMethod(builder, "set-keep-alive-count", this::tcpSetKeepAliveCount)
        registerTcpMethod(builder, "hop-limit", this::tcpHopLimit)
        registerTcpMethod(builder, "set-hop-limit", this::tcpSetHopLimit)
        registerTcpMethod(builder, "receive-buffer-size", this::tcpReceiveBufferSize)
        registerTcpMethod(builder, "set-receive-buffer-size", this::tcpSetReceiveBufferSize)
        registerTcpMethod(builder, "send-buffer-size", this::tcpSendBufferSize)
        registerTcpMethod(builder, "set-send-buffer-size", this::tcpSetSendBufferSize)
        registerTcpMethod(builder, "subscribe", this::tcpSubscribe)
        registerTcpMethod(builder, "shutdown", this::tcpShutdown)
        registerResourceDrop(builder, SOCKETS_PACKAGE, "tcp", "tcp-socket", tcpSockets)

        register(
            builder,
            SOCKETS_PACKAGE,
            "udp-create-socket",
            "create-udp-socket",
            this::createUdpSocket,
        )
        registerUdpMethod(builder, "start-bind", this::udpStartBind)
        registerUdpMethod(builder, "finish-bind", this::udpFinishBind)
        registerUdpMethod(builder, "stream", this::udpStream)
        registerUdpMethod(builder, "local-address", this::udpLocalAddress)
        registerUdpMethod(builder, "remote-address", this::udpRemoteAddress)
        registerUdpMethod(builder, "address-family", this::udpAddressFamily)
        registerUdpMethod(builder, "unicast-hop-limit", this::udpUnicastHopLimit)
        registerUdpMethod(builder, "set-unicast-hop-limit", this::udpSetUnicastHopLimit)
        registerUdpMethod(builder, "receive-buffer-size", this::udpReceiveBufferSize)
        registerUdpMethod(builder, "set-receive-buffer-size", this::udpSetReceiveBufferSize)
        registerUdpMethod(builder, "send-buffer-size", this::udpSendBufferSize)
        registerUdpMethod(builder, "set-send-buffer-size", this::udpSetSendBufferSize)
        registerUdpMethod(builder, "subscribe", this::udpSubscribe)
        registerSocketsResourceMethod(
            builder,
            "udp",
            "incoming-datagram-stream",
            "receive",
            this::udpReceive,
        )
        registerSocketsResourceMethod(
            builder,
            "udp",
            "incoming-datagram-stream",
            "subscribe",
            this::udpIncomingSubscribe,
        )
        registerResourceDrop(
            builder,
            SOCKETS_PACKAGE,
            "udp",
            "incoming-datagram-stream",
            incomingDatagramStreams,
        )
        registerSocketsResourceMethod(
            builder,
            "udp",
            "outgoing-datagram-stream",
            "check-send",
            this::udpCheckSend,
        )
        registerSocketsResourceMethod(
            builder,
            "udp",
            "outgoing-datagram-stream",
            "send",
            this::udpSend,
        )
        registerSocketsResourceMethod(
            builder,
            "udp",
            "outgoing-datagram-stream",
            "subscribe",
            this::udpOutgoingSubscribe,
        )
        registerResourceDrop(
            builder,
            SOCKETS_PACKAGE,
            "udp",
            "outgoing-datagram-stream",
            outgoingDatagramStreams,
        )
        registerResourceDrop(builder, SOCKETS_PACKAGE, "udp", "udp-socket", udpSockets)

        return builder
    }

    private fun getStdin(args: List<Any?>): Any? {
        requireArity("get-stdin", args, 0)
        return inputStreams.insertResource(stdin)
    }

    private fun getStdout(args: List<Any?>): Any? {
        requireArity("get-stdout", args, 0)
        return outputStreams.insertResource(stdout)
    }

    private fun getStderr(args: List<Any?>): Any? {
        requireArity("get-stderr", args, 0)
        return outputStreams.insertResource(stderr)
    }

    private fun getEnvironment(args: List<Any?>): Any? {
        requireArity("get-environment", args, 0)
        var result = ArrayList<List<String>>()
        for (entry in environment.entries) {
            result.add(listOf(entry.key, entry.value))
        }
        return result
    }

    private fun getArguments(args: List<Any?>): Any? {
        requireArity("get-arguments", args, 0)
        return ArrayList(arguments)
    }

    private fun initialCwd(args: List<Any?>): Any? {
        requireArity("initial-cwd", args, 0)
        return initialCwd
    }

    private fun exit(args: List<Any?>): Any? {
        requireArity("exit", args, 1)
        throw ExitException(exitCode(args.get(0)))
    }

    private fun exitWithCode(args: List<Any?>): Any? {
        requireArity("exit-with-code", args, 1)
        throw ExitException((asU64(args.get(0)) and 0xffL).toInt())
    }

    private fun getTerminalStdin(args: List<Any?>): Any? {
        requireArity("get-terminal-stdin", args, 0)
        return if (terminalStdin) terminalInputs.insertResource(TerminalInput()) else null
    }

    private fun getTerminalStdout(args: List<Any?>): Any? {
        requireArity("get-terminal-stdout", args, 0)
        return if (terminalStdout) terminalOutputs.insertResource(TerminalOutput()) else null
    }

    private fun getTerminalStderr(args: List<Any?>): Any? {
        requireArity("get-terminal-stderr", args, 0)
        return if (terminalStderr) terminalOutputs.insertResource(TerminalOutput()) else null
    }

    private fun wallClockNow(args: List<Any?>): Any? {
        requireArity("wall-clock.now", args, 0)
        val now = wallClock.now()
        return datetime(now.epochSeconds, now.nanosecondsOfSecond.toLong())
    }

    private fun wallClockResolution(args: List<Any?>): Any? {
        requireArity("wall-clock.resolution", args, 0)
        return datetime(
            wallClockResolutionNanos / 1_000_000_000L,
            wallClockResolutionNanos % 1_000_000_000L,
        )
    }

    private fun timezoneDisplay(args: List<Any?>): Any? {
        requireArity("timezone.display", args, 1)
        val instant = instantFromDatetime(args.get(0))
        return WitValue.record(
            "utc-offset",
            wallClockTimeZone.offsetAt(instant).totalSeconds,
            "name",
            timezoneName(instant),
            "in-daylight-saving-time",
            isWasiDaylightSavingTime(wallClockTimeZone, instant),
        )
    }

    private fun timezoneUtcOffset(args: List<Any?>): Any? {
        requireArity("timezone.utc-offset", args, 1)
        return wallClockTimeZone.offsetAt(instantFromDatetime(args.get(0))).totalSeconds
    }

    private fun monotonicClockNow(args: List<Any?>): Any? {
        requireArity("monotonic-clock.now", args, 0)
        return monotonicNow()
    }

    private fun monotonicClockResolution(args: List<Any?>): Any? {
        requireArity("monotonic-clock.resolution", args, 0)
        return monotonicResolutionNanos
    }

    private fun subscribeInstant(args: List<Any?>): Any? {
        requireArity("monotonic-clock.subscribe-instant", args, 1)
        var readyAt: Long = asU64(args.get(0))
        return pollables.insertResource(Pollable { compareUnsigned(monotonicNow(), readyAt) >= 0 })
    }

    private fun subscribeDuration(args: List<Any?>): Any? {
        requireArity("monotonic-clock.subscribe-duration", args, 1)
        var readyAt: Long = monotonicNow() + asU64(args.get(0))
        return pollables.insertResource(Pollable { compareUnsigned(monotonicNow(), readyAt) >= 0 })
    }

    private fun errorToDebugString(args: List<Any?>): Any? {
        requireArity("error.to-debug-string", args, 1)
        return errors.get(handle(args, 0)).debug
    }

    private fun read(args: List<Any?>, blocking: Boolean): Any? {
        requireArity("input-stream.read", args, 2)
        var stream = inputStreams.get(handle(args, 0))
        var len = boundedLength(args.get(1))
        if (len == 0) {
            return WitResult.ok(ByteArray(0))
        }
        try {
            return WitResult.ok(stream.readBytes(len, blocking))
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun skip(args: List<Any?>, blocking: Boolean): Any? {
        requireArity("input-stream.skip", args, 2)
        var stream = inputStreams.get(handle(args, 0))
        var requested: Int = boundedLength(args.get(1))
        try {
            return WitResult.ok(stream.skip(requested, blocking))
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun subscribeInput(args: List<Any?>): Any? {
        requireArity("input-stream.subscribe", args, 1)
        var stream = inputStreams.get(handle(args, 0))
        return pollables.insertResource(
            Pollable({
                try {
                    stream.available() > 0
                } catch (e: IOException) {
                    true
                }
            })
        )
    }

    private fun checkWrite(args: List<Any?>): Any? {
        requireArity("output-stream.check-write", args, 1)
        outputStreams.get(handle(args, 0))
        return WitResult.ok(MAX_IO_CHUNK.toLong())
    }

    private fun write(args: List<Any?>, flush: Boolean): Any? {
        requireArity("output-stream.write", args, 2)
        var stream = outputStreams.get(handle(args, 0))
        try {
            stream.write(bytes(args.get(1)))
            if (flush) {
                stream.flush()
            }
            return WitResult.ok(null)
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun flush(args: List<Any?>): Any? {
        requireArity("output-stream.flush", args, 1)
        var stream = outputStreams.get(handle(args, 0))
        try {
            stream.flush()
            return WitResult.ok(null)
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun subscribeOutput(args: List<Any?>): Any? {
        requireArity("output-stream.subscribe", args, 1)
        outputStreams.get(handle(args, 0))
        return pollables.insertResource(Pollable { true })
    }

    private fun writeZeroes(args: List<Any?>, flush: Boolean): Any? {
        requireArity("output-stream.write-zeroes", args, 2)
        var stream = outputStreams.get(handle(args, 0))
        var bytes = ByteArray(boundedLength(args.get(1)))
        try {
            stream.write(bytes)
            if (flush) {
                stream.flush()
            }
            return WitResult.ok(null)
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun splice(args: List<Any?>, blocking: Boolean): Any? {
        requireArity("output-stream.splice", args, 3)
        var output = outputStreams.get(handle(args, 0))
        var input = inputStreams.get(handle(args, 1))
        var len = boundedLength(args.get(2))
        try {
            var bytes = input.readBytes(len, blocking)
            output.write(bytes)
            return WitResult.ok(bytes.size.toLong())
        } catch (e: IOException) {
            return streamError(e)
        }
    }

    private fun pollableReady(args: List<Any?>): Any? {
        requireArity("pollable.ready", args, 1)
        return pollables.get(handle(args, 0)).ready()
    }

    private fun pollableBlock(args: List<Any?>): Any? {
        requireArity("pollable.block", args, 1)
        block(pollables.get(handle(args, 0)))
        return null
    }

    private fun poll(args: List<Any?>): Any? {
        requireArity("poll", args, 1)
        var handles = list(args.get(0))
        if (handles.isEmpty()) {
            throw ComponentModelException("wasi:io/poll.poll requires at least one pollable")
        }
        while (true) {
            var ready = ArrayList<Int>()
            for (i in 0 until handles.size) {
                if (pollables.get(asU64(handles.get(i))).ready()) {
                    ready.add(i)
                }
            }
            if (!ready.isEmpty()) {
                return ready
            }
            waitForPollableReadiness()
        }
    }

    private fun getRandomBytes(args: List<Any?>): Any? {
        requireArity("random.get-random-bytes", args, 1)
        return randomBytes(secureRandom, checkedByteLength(args.get(0)))
    }

    private fun getRandomU64(args: List<Any?>): Any? {
        requireArity("random.get-random-u64", args, 0)
        return randomLong(secureRandom)
    }

    private fun getInsecureRandomBytes(args: List<Any?>): Any? {
        requireArity("insecure.get-insecure-random-bytes", args, 1)
        return randomBytes(insecureRandom, checkedByteLength(args.get(0)))
    }

    private fun getInsecureRandomU64(args: List<Any?>): Any? {
        requireArity("insecure.get-insecure-random-u64", args, 0)
        return randomLong(insecureRandom)
    }

    private fun getInsecureSeed(args: List<Any?>): Any? {
        requireArity("insecure-seed.insecure-seed", args, 0)
        return listOf(insecureSeedLower, insecureSeedUpper)
    }

    private fun instanceNetwork(args: List<Any?>): Any? {
        requireArity("instance-network", args, 0)
        return networks.insertResource(Network(networkingEnabled))
    }

    private fun networkErrorCode(args: List<Any?>): Any? {
        requireArity("network-error-code", args, 1)
        return errors.get(handle(args, 0)).networkCode
    }

    private fun resolveAddresses(args: List<Any?>): Any? {
        requireArity("resolve-addresses", args, 2)
        return networkResult {
            requireNetwork(args, 0)
            var addresses = resolveIpAddresses(args.get(1) as String)
            return@networkResult resolveAddressStreams.insertResource(
                ResolveAddressStream(addresses.iterator())
            )
        }
    }

    private fun resolveNextAddress(args: List<Any?>): Any? {
        requireArity("resolve-address-stream.resolve-next-address", args, 1)
        return networkResult {
            var stream = resolveAddressStreams.get(handle(args, 0))
            return@networkResult if (stream.addresses.hasNext()) ipAddress(stream.addresses.next())
            else null
        }
    }

    private fun resolveAddressSubscribe(args: List<Any?>): Any? {
        requireArity("resolve-address-stream.subscribe", args, 1)
        resolveAddressStreams.get(handle(args, 0))
        return pollables.insertResource(Pollable { true })
    }

    private fun createTcpSocket(args: List<Any?>): Any? {
        requireArity("create-tcp-socket", args, 1)
        return networkResult {
            var family = addressFamily(args.get(0))
            return@networkResult tcpSockets.insertResource(TcpSocket(family))
        }
    }

    private fun tcpStartBind(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.start-bind", args, 3)
        return networkResult {
            requireNetwork(args, 1)
            if (socket.bound) {
                throw NetException("invalid-state")
            }
            var local = socketAddress(args.get(2))
            requireFamily(socket.family, local)
            socket.pendingBind = local
            socket.bindStarted = true
            return@networkResult null
        }
    }

    private fun tcpFinishBind(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.finish-bind", args, 1)
        return networkResult {
            if (!socket.bindStarted) {
                throw NetException("not-in-progress")
            }
            socket.localAddress = socket.pendingBind
            socket.pendingBind = null
            socket.bindStarted = false
            socket.bound = true
            return@networkResult null
        }
    }

    private fun tcpStartConnect(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.start-connect", args, 3)
        return networkResult {
            requireNetwork(args, 1)
            if (socket.listening || socket.connected || socket.connectStarted) {
                throw NetException("invalid-state")
            }
            var remote = socketAddress(args.get(2))
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
            socket.connectStarted = false
            socket.connected = true
            socket.bound = true
            return@networkResult null
        }
    }

    private fun tcpFinishConnect(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.finish-connect", args, 1)
        return networkResult {
            if (!socket.connected) {
                if (!socket.connectStarted) {
                    throw NetException("not-in-progress")
                }
                throw NetException("would-block")
            }
            val connection = socket.connection ?: throw NetException("invalid-state")
            return@networkResult listOf(
                inputStreams.insertResource(
                    WasiInputStream(connection.inputSource(), connection::inputAvailable)
                ),
                outputStreams.insertResource(WasiOutputStream(connection.outputSink())),
            )
        }
    }

    private fun tcpStartListen(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.start-listen", args, 1)
        return networkResult {
            if (!socket.bound || socket.localAddress == null || socket.connected) {
                throw NetException("invalid-state")
            }
            if (socket.listening) {
                return@networkResult null
            }
            val listener = socketRuntime.listenTcp(socket.localAddress!!, socket.listenBacklog)
            socket.listener = listener
            socket.localAddress = listener.localAddress
            socket.listenStarted = true
            return@networkResult null
        }
    }

    private fun tcpFinishListen(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.finish-listen", args, 1)
        return networkResult {
            if (!socket.listenStarted) {
                throw NetException("not-in-progress")
            }
            socket.listenStarted = false
            socket.listening = true
            return@networkResult null
        }
    }

    private fun tcpAccept(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.accept", args, 1)
        return networkResult {
            if (!socket.listening || socket.listener == null) {
                throw NetException("invalid-state")
            }
            val accepted = socket.listener!!.accept(1L) ?: throw NetException("would-block")
            var child = TcpSocket(socket.family)
            child.inheritConnectionOptionsFrom(socket)
            child.connection = accepted
            child.bound = true
            child.connected = true
            child.localAddress = accepted.localAddress
            child.remoteAddress = accepted.remoteAddress
            return@networkResult listOf(
                tcpSockets.insertResource(child),
                inputStreams.insertResource(
                    WasiInputStream(accepted.inputSource(), accepted::inputAvailable)
                ),
                outputStreams.insertResource(WasiOutputStream(accepted.outputSink())),
            )
        }
    }

    private fun tcpLocalAddress(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.local-address", args, 1)
        return networkResult {
            var address = socket.localAddress
            if (socket.listener != null) {
                address = socket.listener!!.localAddress
            } else if (socket.connection != null) {
                address = socket.connection!!.localAddress
            }
            if (address == null) {
                throw NetException("invalid-state")
            }
            return@networkResult socketAddress(normalizeLocalAddress(socket.family, address))
        }
    }

    private fun tcpRemoteAddress(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.remote-address", args, 1)
        return networkResult {
            var address = socket.remoteAddress
            if (socket.connection != null) {
                address = socket.connection!!.remoteAddress
            }
            if (address == null) {
                throw NetException("invalid-state")
            }
            return@networkResult socketAddress(address)
        }
    }

    private fun tcpIsListening(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.is-listening", args, 1)
        return socket.listening
    }

    private fun tcpAddressFamily(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.address-family", args, 1)
        return socket.family.label
    }

    private fun tcpKeepAliveEnabled(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.keep-alive-enabled", args, 1)
        return networkResult { socket.keepAlive }
    }

    private fun tcpSetKeepAliveEnabled(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-keep-alive-enabled", args, 2)
        return networkResult {
            socket.keepAlive = args.get(1) == true
            return@networkResult null
        }
    }

    private fun tcpKeepAliveIdleTime(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.keep-alive-idle-time", args, 1)
        return networkResult { socket.keepAliveIdleTimeNanos }
    }

    private fun tcpSetKeepAliveIdleTime(socket: TcpSocket, args: List<Any?>): Any? {
        return setPositiveDuration(args, socket, "set-keep-alive-idle-time", 1)
    }

    private fun tcpKeepAliveInterval(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.keep-alive-interval", args, 1)
        return networkResult { socket.keepAliveIntervalNanos }
    }

    private fun tcpSetKeepAliveInterval(socket: TcpSocket, args: List<Any?>): Any? {
        return setPositiveDuration(args, socket, "set-keep-alive-interval", 2)
    }

    private fun tcpKeepAliveCount(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.keep-alive-count", args, 1)
        return networkResult { socket.keepAliveCount }
    }

    private fun tcpSetKeepAliveCount(socket: TcpSocket, args: List<Any?>): Any? {
        return setPositiveU32(args, socket, "set-keep-alive-count", 3)
    }

    private fun tcpHopLimit(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.hop-limit", args, 1)
        return networkResult { socket.hopLimit }
    }

    private fun tcpSetHopLimit(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-hop-limit", args, 2)
        return networkResult {
            socket.hopLimit = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun tcpReceiveBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.receive-buffer-size", args, 1)
        return networkResult { unsignedInt(socket.receiveBufferSize) }
    }

    private fun tcpSetReceiveBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-receive-buffer-size", args, 2)
        return networkResult {
            socket.receiveBufferSize = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun tcpSendBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.send-buffer-size", args, 1)
        return networkResult { unsignedInt(socket.sendBufferSize) }
    }

    private fun tcpSetSendBufferSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-send-buffer-size", args, 2)
        return networkResult {
            socket.sendBufferSize = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun tcpSubscribe(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.subscribe", args, 1)
        return pollables.insertResource(Pollable { tcpReady(socket) })
    }

    private fun tcpSetListenBacklogSize(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.set-listen-backlog-size", args, 2)
        return networkResult {
            var value = asU64(args.get(1))
            if (value == 0L) {
                throw NetException("invalid-argument")
            }
            if (value > Int.MAX_VALUE) {
                value = Int.MAX_VALUE.toLong()
            }
            socket.listenBacklog = value.toInt()
            return@networkResult null
        }
    }

    private fun setPositiveDuration(
        args: List<Any?>,
        socket: TcpSocket,
        name: String,
        field: Int,
    ): Any? {
        requireArity("tcp-socket." + name, args, 2)
        return networkResult {
            var value = asU64(args.get(1))
            if (value == 0L) {
                throw NetException("invalid-argument")
            }
            if (field == 1) {
                socket.keepAliveIdleTimeNanos = value
            } else {
                socket.keepAliveIntervalNanos = value
            }
            return@networkResult null
        }
    }

    private fun setPositiveU32(
        args: List<Any?>,
        socket: TcpSocket,
        name: String,
        field: Int,
    ): Any? {
        requireArity("tcp-socket." + name, args, 2)
        return networkResult {
            var value = asU64(args.get(1))
            if (value == 0L || value > 0xffff_ffffL) {
                throw NetException("invalid-argument")
            }
            if (field == 3) {
                socket.keepAliveCount = value.toInt()
            }
            return@networkResult null
        }
    }

    private fun tcpShutdown(socket: TcpSocket, args: List<Any?>): Any? {
        requireArity("tcp-socket.shutdown", args, 2)
        return networkResult {
            val connection = socket.connection
            if (!socket.connected || connection == null) {
                throw NetException("invalid-state")
            }
            when (label(args.get(1), "receive", "send", "both")) {
                "receive" -> connection.shutdownInput()
                "send" -> connection.shutdownOutput()
                "both" -> {
                    connection.shutdownInput()
                    connection.shutdownOutput()
                }
                else -> throw NetException("invalid-argument")
            }
            return@networkResult null
        }
    }

    private fun createUdpSocket(args: List<Any?>): Any? {
        requireArity("create-udp-socket", args, 1)
        return networkResult {
            var family = addressFamily(args.get(0))
            return@networkResult udpSockets.insertResource(UdpSocket(family))
        }
    }

    private fun udpStartBind(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.start-bind", args, 3)
        return networkResult {
            requireNetwork(args, 1)
            if (socket.bound) {
                throw NetException("invalid-state")
            }
            var local = socketAddress(args.get(2))
            requireFamily(socket.family, local)
            socket.pendingBind = local
            socket.bindStarted = true
            return@networkResult null
        }
    }

    private fun udpFinishBind(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.finish-bind", args, 1)
        return networkResult {
            if (!socket.bindStarted) {
                throw NetException("not-in-progress")
            }
            val endpoint =
                socketRuntime.bindUdp(
                    socket.pendingBind!!,
                    socket.receiveBufferSize,
                    socket.sendBufferSize,
                )
            socket.endpoint = endpoint
            socket.localAddress = endpoint.localAddress
            socket.pendingBind = null
            socket.bindStarted = false
            socket.bound = true
            return@networkResult null
        }
    }

    private fun udpStream(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.stream", args, 2)
        return networkResult {
            if (!socket.bound) {
                throw NetException("invalid-state")
            }
            var remote = option(args.get(1))
            if (remote != null) {
                var remoteAddress = socketAddress(remote)
                requireFamily(socket.family, remoteAddress)
                socket.remoteAddress = remoteAddress
            } else if (socket.remoteAddress != null) {
                socket.remoteAddress = null
            }
            return@networkResult listOf(
                incomingDatagramStreams.insertResource(IncomingDatagramStream(socket)),
                outgoingDatagramStreams.insertResource(OutgoingDatagramStream(socket)),
            )
        }
    }

    private fun udpLocalAddress(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.local-address", args, 1)
        return networkResult {
            var address = socket.localAddress
            if (socket.endpoint != null) {
                address = socket.endpoint!!.localAddress
            }
            if (address == null) {
                throw NetException("invalid-state")
            }
            return@networkResult socketAddress(normalizeLocalAddress(socket.family, address))
        }
    }

    private fun udpRemoteAddress(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.remote-address", args, 1)
        return networkResult {
            var address = socket.remoteAddress
            if (address == null) {
                throw NetException("invalid-state")
            }
            return@networkResult socketAddress(address)
        }
    }

    private fun udpAddressFamily(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.address-family", args, 1)
        return socket.family.label
    }

    private fun udpUnicastHopLimit(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.unicast-hop-limit", args, 1)
        return networkResult { socket.unicastHopLimit }
    }

    private fun udpSetUnicastHopLimit(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-unicast-hop-limit", args, 2)
        return networkResult {
            socket.unicastHopLimit = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun udpReceiveBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.receive-buffer-size", args, 1)
        return networkResult { unsignedInt(socket.receiveBufferSize) }
    }

    private fun udpSetReceiveBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-receive-buffer-size", args, 2)
        return networkResult {
            socket.receiveBufferSize = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun udpSendBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.send-buffer-size", args, 1)
        return networkResult { unsignedInt(socket.sendBufferSize) }
    }

    private fun udpSetSendBufferSize(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.set-send-buffer-size", args, 2)
        return networkResult {
            socket.sendBufferSize = positiveInt(args.get(1))
            return@networkResult null
        }
    }

    private fun udpSubscribe(socket: UdpSocket, args: List<Any?>): Any? {
        requireArity("udp-socket.subscribe", args, 1)
        return pollables.insertResource(Pollable { socket.endpoint?.isOpen() == true })
    }

    private fun udpReceive(args: List<Any?>): Any? {
        requireArity("incoming-datagram-stream.receive", args, 2)
        return networkResult {
            var stream = incomingDatagramStreams.get(handle(args, 0))
            var max = kotlin.math.min(asU64(args.get(1)), 1024L)
            var result = ArrayList<Map<String, Any?>>()
            val endpoint = stream.socket.endpoint ?: throw NetException("invalid-state")
            for (i in 0 until max) {
                val datagram = endpoint.receive(if (result.isEmpty()) 1_000L else 1L)
                if (datagram == null) {
                    break
                }
                result.add(
                    WitValue.record(
                        "data",
                        datagram.data,
                        "remote-address",
                        socketAddress(datagram.remoteAddress),
                    )
                )
            }
            return@networkResult result
        }
    }

    private fun udpIncomingSubscribe(args: List<Any?>): Any? {
        requireArity("incoming-datagram-stream.subscribe", args, 1)
        var stream = incomingDatagramStreams.get(handle(args, 0))
        return pollables.insertResource(Pollable { stream.socket.endpoint?.isOpen() == true })
    }

    private fun udpCheckSend(args: List<Any?>): Any? {
        requireArity("outgoing-datagram-stream.check-send", args, 1)
        return networkResult {
            var stream = outgoingDatagramStreams.get(handle(args, 0))
            stream.permittedDatagrams = 1024L
            return@networkResult stream.permittedDatagrams
        }
    }

    private fun udpSend(args: List<Any?>): Any? {
        requireArity("outgoing-datagram-stream.send", args, 2)
        return networkResult {
            var stream = outgoingDatagramStreams.get(handle(args, 0))
            var datagrams = list(args.get(1))
            if (datagrams.size > stream.permittedDatagrams) {
                throw ComponentModelException("udp send exceeds last check-send permit")
            }
            var sent: Long = 0
            for (datagram in datagrams) {
                var data = bytes(recordField(datagram, "data"))
                var remoteValue = option(recordField(datagram, "remote-address"))
                var remote: InetSocketAddress? = null
                if (remoteValue != null) {
                    remote = socketAddress(remoteValue)
                    requireFamily(stream.socket.family, remote)
                } else if (stream.socket.remoteAddress != null) {
                    remote = stream.socket.remoteAddress
                }
                if (remote == null) {
                    throw NetException("invalid-argument")
                }
                val endpoint = stream.socket.endpoint ?: throw NetException("invalid-state")
                endpoint.send(data, remote)
                sent++
            }
            stream.permittedDatagrams = kotlin.math.max(0L, stream.permittedDatagrams - sent)
            return@networkResult sent
        }
    }

    private fun udpOutgoingSubscribe(args: List<Any?>): Any? {
        requireArity("outgoing-datagram-stream.subscribe", args, 1)
        var stream = outgoingDatagramStreams.get(handle(args, 0))
        return pollables.insertResource(Pollable { stream.socket.endpoint?.isOpen() == true })
    }

    private fun httpErrorCode(args: List<Any?>): Any? {
        requireArity("http-error-code", args, 1)
        return errors.get(handle(args, 0)).httpCode
    }

    private fun httpFieldsConstructor(args: List<Any?>): Any? {
        requireArity("fields.constructor", args, 0)
        return httpFields.insertResource(HttpFields(true))
    }

    private fun httpFieldsFromList(args: List<Any?>): Any? {
        requireArity("fields.from-list", args, 1)
        try {
            var fields = HttpFields(true)
            for (entry in list(args.get(0))) {
                fields.append(stringValue(tupleValue(entry, 0)), bytes(tupleValue(entry, 1)))
            }
            return WitResult.ok(httpFields.insertResource(fields))
        } catch (e: HttpException) {
            return WitResult.err(e.code)
        }
    }

    private fun httpFieldsGet(args: List<Any?>): Any? {
        requireArity("fields.get", args, 2)
        return httpFields.get(handle(args, 0)).get(args.get(1) as String)
    }

    private fun httpFieldsHas(args: List<Any?>): Any? {
        requireArity("fields.has", args, 2)
        return httpFields.get(handle(args, 0)).has(args.get(1) as String)
    }

    private fun httpFieldsSet(args: List<Any?>): Any? {
        requireArity("fields.set", args, 3)
        try {
            var values = ArrayList<ByteArray>()
            for (value in list(args.get(2))) {
                values.add(bytes(value))
            }
            httpFields.get(handle(args, 0)).set(args.get(1) as String, values)
            return WitResult.ok(null)
        } catch (e: HttpException) {
            return WitResult.err(e.code)
        }
    }

    private fun httpFieldsDelete(args: List<Any?>): Any? {
        requireArity("fields.delete", args, 2)
        try {
            httpFields.get(handle(args, 0)).delete(args.get(1) as String)
            return WitResult.ok(null)
        } catch (e: HttpException) {
            return WitResult.err(e.code)
        }
    }

    private fun httpFieldsAppend(args: List<Any?>): Any? {
        requireArity("fields.append", args, 3)
        try {
            httpFields.get(handle(args, 0)).append(args.get(1) as String, bytes(args.get(2)))
            return WitResult.ok(null)
        } catch (e: HttpException) {
            return WitResult.err(e.code)
        }
    }

    private fun httpFieldsEntries(args: List<Any?>): Any? {
        requireArity("fields.entries", args, 1)
        return httpFields.get(handle(args, 0)).entries()
    }

    private fun httpFieldsClone(args: List<Any?>): Any? {
        requireArity("fields.clone", args, 1)
        return httpFields.insertResource(httpFields.get(handle(args, 0)).copy(true))
    }

    private fun incomingRequestMethod(args: List<Any?>): Any? {
        requireArity("incoming-request.method", args, 1)
        return incomingRequests.get(handle(args, 0)).method
    }

    private fun incomingRequestPath(args: List<Any?>): Any? {
        requireArity("incoming-request.path-with-query", args, 1)
        return incomingRequests.get(handle(args, 0)).pathWithQuery
    }

    private fun incomingRequestScheme(args: List<Any?>): Any? {
        requireArity("incoming-request.scheme", args, 1)
        return incomingRequests.get(handle(args, 0)).scheme
    }

    private fun incomingRequestAuthority(args: List<Any?>): Any? {
        requireArity("incoming-request.authority", args, 1)
        return incomingRequests.get(handle(args, 0)).authority
    }

    private fun incomingRequestHeaders(args: List<Any?>): Any? {
        requireArity("incoming-request.headers", args, 1)
        return httpFields.insertResource(incomingRequests.get(handle(args, 0)).headers.copy(false))
    }

    private fun incomingRequestConsume(args: List<Any?>): Any? {
        requireArity("incoming-request.consume", args, 1)
        var request = incomingRequests.get(handle(args, 0))
        if (request.bodyConsumed) {
            return WitResult.err(null)
        }
        request.bodyConsumed = true
        return WitResult.ok(incomingBodies.insertResource(IncomingBody(request.body)))
    }

    private fun outgoingRequestConstructor(args: List<Any?>): Any? {
        requireArity("outgoing-request.constructor", args, 1)
        return outgoingRequests.insertResource(
            OutgoingRequest(httpFields.get(handle(args, 0)).copy(false))
        )
    }

    private fun outgoingRequestBody(args: List<Any?>): Any? {
        requireArity("outgoing-request.body", args, 1)
        var request = outgoingRequests.get(handle(args, 0))
        if (request.bodyTaken) {
            return WitResult.err(null)
        }
        request.bodyTaken = true
        val body = OutgoingBody()
        request.body = body
        return WitResult.ok(outgoingBodies.insertResource(body))
    }

    private fun outgoingRequestMethod(args: List<Any?>): Any? {
        requireArity("outgoing-request.method", args, 1)
        return outgoingRequests.get(handle(args, 0)).method
    }

    private fun outgoingRequestSetMethod(args: List<Any?>): Any? {
        requireArity("outgoing-request.set-method", args, 2)
        try {
            outgoingRequests.get(handle(args, 0)).method = httpMethod(args.get(1))
            return WitResult.ok(null)
        } catch (e: HttpException) {
            return WitResult.err(null)
        }
    }

    private fun outgoingRequestPath(args: List<Any?>): Any? {
        requireArity("outgoing-request.path-with-query", args, 1)
        return outgoingRequests.get(handle(args, 0)).pathWithQuery
    }

    private fun outgoingRequestSetPath(args: List<Any?>): Any? {
        requireArity("outgoing-request.set-path-with-query", args, 2)
        outgoingRequests.get(handle(args, 0)).pathWithQuery = optionString(args.get(1), "/")
        return WitResult.ok(null)
    }

    private fun outgoingRequestScheme(args: List<Any?>): Any? {
        requireArity("outgoing-request.scheme", args, 1)
        return outgoingRequests.get(handle(args, 0)).scheme
    }

    private fun outgoingRequestSetScheme(args: List<Any?>): Any? {
        requireArity("outgoing-request.set-scheme", args, 2)
        try {
            outgoingRequests.get(handle(args, 0)).scheme = httpScheme(option(args.get(1)))
            return WitResult.ok(null)
        } catch (e: HttpException) {
            return WitResult.err(null)
        }
    }

    private fun outgoingRequestAuthority(args: List<Any?>): Any? {
        requireArity("outgoing-request.authority", args, 1)
        return outgoingRequests.get(handle(args, 0)).authority
    }

    private fun outgoingRequestSetAuthority(args: List<Any?>): Any? {
        requireArity("outgoing-request.set-authority", args, 2)
        outgoingRequests.get(handle(args, 0)).authority = optionString(args.get(1), null)
        return WitResult.ok(null)
    }

    private fun outgoingRequestHeaders(args: List<Any?>): Any? {
        requireArity("outgoing-request.headers", args, 1)
        return httpFields.insertResource(
            outgoingRequests.get(handle(args, 0)).headers!!.copy(false)
        )
    }

    private fun requestOptionsConstructor(args: List<Any?>): Any? {
        requireArity("request-options.constructor", args, 0)
        return requestOptions.insertResource(RequestOptions())
    }

    private fun requestOptionsConnect(args: List<Any?>): Any? {
        requireArity("request-options.connect-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).connectTimeoutNanos
    }

    private fun requestOptionsSetConnect(args: List<Any?>): Any? {
        requireArity("request-options.set-connect-timeout", args, 2)
        requestOptions.get(handle(args, 0)).connectTimeoutNanos = optionU64(args.get(1))
        return WitResult.ok(null)
    }

    private fun requestOptionsFirstByte(args: List<Any?>): Any? {
        requireArity("request-options.first-byte-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).firstByteTimeoutNanos
    }

    private fun requestOptionsSetFirstByte(args: List<Any?>): Any? {
        requireArity("request-options.set-first-byte-timeout", args, 2)
        requestOptions.get(handle(args, 0)).firstByteTimeoutNanos = optionU64(args.get(1))
        return WitResult.ok(null)
    }

    private fun requestOptionsBetweenBytes(args: List<Any?>): Any? {
        requireArity("request-options.between-bytes-timeout", args, 1)
        return requestOptions.get(handle(args, 0)).betweenBytesTimeoutNanos
    }

    private fun requestOptionsSetBetweenBytes(args: List<Any?>): Any? {
        requireArity("request-options.set-between-bytes-timeout", args, 2)
        requestOptions.get(handle(args, 0)).betweenBytesTimeoutNanos = optionU64(args.get(1))
        return WitResult.ok(null)
    }

    private fun responseOutparamSendInformational(args: List<Any?>): Any? {
        requireArity("response-outparam.send-informational", args, 3)
        var status = asU64(args.get(1)).toInt()
        if (status < 100 || status > 199) {
            return WitResult.err("HTTP-protocol-error")
        }
        return WitResult.err(
            WitValue.variant("internal-error", "informational responses are unsupported")
        )
    }

    private fun responseOutparamSet(args: List<Any?>): Any? {
        requireArity("response-outparam.set", args, 2)
        var outparam = responseOutparams.get(handle(args, 0))
        outparam.response = args.get(1)
        outparam.set = true
        return null
    }

    private fun incomingResponseStatus(args: List<Any?>): Any? {
        requireArity("incoming-response.status", args, 1)
        return incomingResponses.get(handle(args, 0)).status
    }

    private fun incomingResponseHeaders(args: List<Any?>): Any? {
        requireArity("incoming-response.headers", args, 1)
        return httpFields.insertResource(incomingResponses.get(handle(args, 0)).headers.copy(false))
    }

    private fun incomingResponseConsume(args: List<Any?>): Any? {
        requireArity("incoming-response.consume", args, 1)
        var response = incomingResponses.get(handle(args, 0))
        if (response.bodyConsumed) {
            return WitResult.err(null)
        }
        response.bodyConsumed = true
        return WitResult.ok(incomingBodies.insertResource(IncomingBody(response.body)))
    }

    private fun incomingBodyStream(args: List<Any?>): Any? {
        requireArity("incoming-body.stream", args, 1)
        var body = incomingBodies.get(handle(args, 0))
        if (body.streamTaken) {
            return WitResult.err(null)
        }
        body.streamTaken = true
        return WitResult.ok(inputStreams.insertResource(body.inputStream()))
    }

    private fun incomingBodyFinish(args: List<Any?>): Any? {
        requireArity("incoming-body.finish", args, 1)
        incomingBodies.remove(handle(args, 0))
        return futureTrailers.insertResource(FutureTrailers(null, null))
    }

    private fun futureTrailersSubscribe(args: List<Any?>): Any? {
        requireArity("future-trailers.subscribe", args, 1)
        futureTrailers.get(handle(args, 0))
        return pollables.insertResource(Pollable { true })
    }

    private fun futureTrailersGet(args: List<Any?>): Any? {
        requireArity("future-trailers.get", args, 1)
        var future = futureTrailers.get(handle(args, 0))
        if (future.consumed) {
            return WitResult.err(null)
        }
        future.consumed = true
        if (future.errorCode != null) {
            return WitResult.ok(WitResult.err(future.errorCode))
        }
        var trailers = future.trailers?.let { httpFields.insertResource(it.copy(false)) }
        return WitResult.ok(WitResult.ok(trailers))
    }

    private fun outgoingResponseConstructor(args: List<Any?>): Any? {
        requireArity("outgoing-response.constructor", args, 1)
        return outgoingResponses.insertResource(
            OutgoingResponse(httpFields.get(handle(args, 0)).copy(false))
        )
    }

    private fun outgoingResponseStatus(args: List<Any?>): Any? {
        requireArity("outgoing-response.status-code", args, 1)
        return outgoingResponses.get(handle(args, 0)).status
    }

    private fun outgoingResponseSetStatus(args: List<Any?>): Any? {
        requireArity("outgoing-response.set-status-code", args, 2)
        var status = asU64(args.get(1))
        if (status < 100 || status > 999) {
            return WitResult.err(null)
        }
        outgoingResponses.get(handle(args, 0)).status = status.toInt()
        return WitResult.ok(null)
    }

    private fun outgoingResponseHeaders(args: List<Any?>): Any? {
        requireArity("outgoing-response.headers", args, 1)
        return httpFields.insertResource(outgoingResponses.get(handle(args, 0)).headers.copy(false))
    }

    private fun outgoingResponseBody(args: List<Any?>): Any? {
        requireArity("outgoing-response.body", args, 1)
        var response = outgoingResponses.get(handle(args, 0))
        if (response.bodyTaken) {
            return WitResult.err(null)
        }
        response.bodyTaken = true
        val body = OutgoingBody()
        response.body = body
        return WitResult.ok(outgoingBodies.insertResource(body))
    }

    private fun outgoingBodyWrite(args: List<Any?>): Any? {
        requireArity("outgoing-body.write", args, 1)
        var body = outgoingBodies.get(handle(args, 0))
        if (body.streamTaken) {
            return WitResult.err(null)
        }
        body.streamTaken = true
        return WitResult.ok(outputStreams.insertResource(body.outputStream()))
    }

    private fun outgoingBodyFinish(args: List<Any?>): Any? {
        requireArity("outgoing-body.finish", args, 2)
        outgoingBodies.get(handle(args, 0)).finished = true
        return WitResult.ok(null)
    }

    private fun futureIncomingResponseSubscribe(args: List<Any?>): Any? {
        requireArity("future-incoming-response.subscribe", args, 1)
        futureIncomingResponses.get(handle(args, 0))
        return pollables.insertResource(Pollable { true })
    }

    private fun futureIncomingResponseGet(args: List<Any?>): Any? {
        requireArity("future-incoming-response.get", args, 1)
        var future = futureIncomingResponses.get(handle(args, 0))
        if (future.consumed) {
            return WitResult.err(null)
        }
        future.consumed = true
        if (future.errorCode != null) {
            return WitResult.ok(WitResult.err(future.errorCode))
        }
        return WitResult.ok(WitResult.ok(incomingResponses.insertResource(future.response)))
    }

    private fun outgoingHandlerHandle(args: List<Any?>): Any? {
        requireArity("outgoing-handler.handle", args, 2)
        if (!networkingEnabled) {
            return WitResult.err("HTTP-request-denied")
        }
        try {
            var request = outgoingRequests.get(handle(args, 0))
            var optionsValue = option(args.get(1))
            var options =
                if (optionsValue == null) null else requestOptions.get(asU64(optionsValue))
            val response = sendHttpRequest(request, options)
            var incoming =
                IncomingResponse(
                    response.status,
                    fieldsFromHttpHeaders(response.headers, false),
                    WasiInputStream(response.consumeBodySource()),
                )
            return WitResult.ok(
                futureIncomingResponses.insertResource(FutureIncomingResponse(incoming, null))
            )
        } catch (e: HttpException) {
            return WitResult.err(e.code)
        } catch (e: IOException) {
            return WitResult.err(httpError(e))
        } catch (e: IllegalArgumentException) {
            return WitResult.err(httpInternalError(e))
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                return WitResult.err(WitValue.variant("internal-error", "interrupted"))
            }
            return WitResult.err(httpError(e))
        }
    }

    private fun filesystemGetDirectories(args: List<Any?>): Any? {
        requireArity("filesystem.get-directories", args, 0)
        var result = ArrayList<List<Any?>>()
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
        return filesystemResult {
            var descriptor = readableDescriptor(args, 0)
            return@filesystemResult inputStreams.insertResource(
                WasiInputStream(
                    SeekableFileSource(descriptor.path, asU64(args.get(1))).asKotlinxIoRawSource()
                )
            )
        }
    }

    private fun filesystemWriteViaStream(args: List<Any?>): Any? {
        requireArity("descriptor.write-via-stream", args, 2)
        return filesystemResult {
            var descriptor = writableDescriptor(args, 0)
            return@filesystemResult outputStreams.insertResource(
                WasiOutputStream(
                    SeekableFileSink(descriptor.path, asU64(args.get(1))).asKotlinxIoRawSink()
                )
            )
        }
    }

    private fun filesystemAppendViaStream(args: List<Any?>): Any? {
        requireArity("descriptor.append-via-stream", args, 1)
        return filesystemResult {
            var descriptor = writableDescriptor(args, 0)
            return@filesystemResult outputStreams.insertResource(
                WasiOutputStream(fileSystem.appendingSink(descriptor.path).asKotlinxIoRawSink())
            )
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
            return@filesystemResult null
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
            var descriptor = writableDescriptor(args, 0)
            fileSystem.openReadWrite(descriptor.path).useHandle { handle ->
                handle.resize(asU64(args.get(1)))
            }
            return@filesystemResult null
        }
    }

    private fun filesystemSetTimes(args: List<Any?>): Any? {
        requireArity("descriptor.set-times", args, 3)
        return filesystemResult {
            var descriptor = descriptors.get(handle(args, 0))
            setTimes(descriptor.path, args.get(1), args.get(2), followSymlinks = true)
            return@filesystemResult null
        }
    }

    private fun filesystemRead(args: List<Any?>): Any? {
        requireArity("descriptor.read", args, 3)
        return filesystemResult {
            var descriptor = readableDescriptor(args, 0)
            var length: Int = checkedByteLength(args.get(1))
            fileSystem.openReadOnly(descriptor.path).useHandle { handle ->
                var buffer = ByteArray(length)
                var read = handle.read(asU64(args.get(2)), buffer, 0, length)
                if (read < 0) {
                    return@filesystemResult listOf(ByteArray(0), true)
                }
                return@filesystemResult listOf(buffer.copyOf(read), read < length)
            }
        }
    }

    private fun filesystemWrite(args: List<Any?>): Any? {
        requireArity("descriptor.write", args, 3)
        return filesystemResult {
            var descriptor = writableDescriptor(args, 0)
            val data = bytes(args.get(1))
            fileSystem.openReadWrite(descriptor.path).useHandle { handle ->
                handle.write(asU64(args.get(2)), data, 0, data.size)
            }
            return@filesystemResult data.size.toLong()
        }
    }

    private fun filesystemReadDirectory(args: List<Any?>): Any? {
        requireArity("descriptor.read-directory", args, 1)
        return filesystemResult {
            var descriptor = descriptors.get(handle(args, 0))
            if (!isDirectory(descriptor.path)) {
                throw FsException("not-directory")
            }
            var entries = ArrayList<Map<String, Any?>>()
            for (path in fileSystem.list(descriptor.path)) {
                entries.add(WitValue.record("type", descriptorType(path), "name", path.name))
            }
            return@filesystemResult directoryEntryStreams.insertResource(
                DirectoryEntryStream(entries.iterator())
            )
        }
    }

    private fun filesystemSync(args: List<Any?>): Any? {
        requireArity("descriptor.sync", args, 1)
        return filesystemResult {
            syncDescriptor(descriptors.get(handle(args, 0)), true)
            return@filesystemResult null
        }
    }

    private fun filesystemCreateDirectoryAt(args: List<Any?>): Any? {
        requireArity("descriptor.create-directory-at", args, 2)
        return filesystemResult {
            var base = mutableDirectoryDescriptor(args, 0)
            fileSystem.createDirectory(
                resolvePath(base, args.get(1) as String, false),
                mustCreate = true,
            )
            return@filesystemResult null
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
                    args.get(2) as String,
                    flag(args.get(1), "symlink-follow"),
                )
            )
        }
    }

    private fun filesystemSetTimesAt(args: List<Any?>): Any? {
        requireArity("descriptor.set-times-at", args, 5)
        return filesystemResult {
            var path =
                resolvePath(
                    mutableDirectoryDescriptor(args, 0),
                    args.get(2) as String,
                    flag(args.get(1), "symlink-follow"),
                )
            setTimes(path, args.get(3), args.get(4), flag(args.get(1), "symlink-follow"))
            return@filesystemResult null
        }
    }

    private fun filesystemLinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.link-at", args, 5)
        return filesystemResult {
            var oldPath =
                resolvePath(
                    descriptors.get(handle(args, 0)),
                    args.get(2) as String,
                    flag(args.get(1), "symlink-follow"),
                )
            val oldLinkPath =
                if (flag(args.get(1), "symlink-follow")) {
                    fileSystem.canonicalize(oldPath)
                } else {
                    oldPath
                }
            var newPath =
                resolvePath(mutableDirectoryDescriptor(args, 3), args.get(4) as String, false)
            createHardLink(oldLinkPath, newPath)
            return@filesystemResult null
        }
    }

    private fun filesystemOpenAt(args: List<Any?>): Any? {
        requireArity("descriptor.open-at", args, 5)
        return filesystemResult {
            var base = descriptors.get(handle(args, 0))
            var openFlags = args.get(3)
            var descriptorFlags = descriptorFlags(args.get(4))
            if (
                (flag(openFlags, "create") ||
                    flag(openFlags, "truncate") ||
                    descriptorFlags.contains("write") ||
                    descriptorFlags.contains("mutate-directory")) &&
                    !base.flags.contains("mutate-directory")
            ) {
                throw FsException("read-only")
            }
            var path = resolvePath(base, args.get(2) as String, flag(args.get(1), "symlink-follow"))
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
            return@filesystemResult descriptors.insertResource(
                FilesystemDescriptor(base.root, path, descriptorFlags)
            )
        }
    }

    private fun filesystemReadlinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.readlink-at", args, 2)
        return filesystemResult {
            var path = resolvePath(descriptors.get(handle(args, 0)), args.get(1) as String, false)
            var target = fileSystem.metadata(path).symlinkTarget ?: throw FsException("invalid")
            if (target.isAbsolute) {
                throw FsException("not-permitted")
            }
            return@filesystemResult target.toString()
        }
    }

    private fun filesystemRemoveDirectoryAt(args: List<Any?>): Any? {
        requireArity("descriptor.remove-directory-at", args, 2)
        return filesystemResult {
            fileSystem.delete(
                resolvePath(mutableDirectoryDescriptor(args, 0), args.get(1) as String, false),
                mustExist = true,
            )
            return@filesystemResult null
        }
    }

    private fun filesystemRenameAt(args: List<Any?>): Any? {
        requireArity("descriptor.rename-at", args, 4)
        return filesystemResult {
            var oldPath =
                resolvePath(mutableDirectoryDescriptor(args, 0), args.get(1) as String, false)
            var newPath =
                resolvePath(mutableDirectoryDescriptor(args, 2), args.get(3) as String, false)
            fileSystem.atomicMove(oldPath, newPath)
            return@filesystemResult null
        }
    }

    private fun filesystemSymlinkAt(args: List<Any?>): Any? {
        requireArity("descriptor.symlink-at", args, 3)
        return filesystemResult {
            var oldPath = (args.get(1) as String).toPath(normalize = true)
            if (oldPath.isAbsolute) {
                throw FsException("not-permitted")
            }
            var newPath =
                resolvePath(mutableDirectoryDescriptor(args, 0), args.get(2) as String, false)
            fileSystem.createSymlink(newPath, oldPath)
            return@filesystemResult null
        }
    }

    private fun filesystemUnlinkFileAt(args: List<Any?>): Any? {
        requireArity("descriptor.unlink-file-at", args, 2)
        return filesystemResult {
            var path =
                resolvePath(mutableDirectoryDescriptor(args, 0), args.get(1) as String, false)
            if (isDirectory(path)) {
                throw FsException("is-directory")
            }
            fileSystem.delete(path, mustExist = true)
            return@filesystemResult null
        }
    }

    private fun filesystemIsSameObject(args: List<Any?>): Any? {
        requireArity("descriptor.is-same-object", args, 2)
        try {
            return fileSystem.canonicalize(descriptors.get(handle(args, 0)).path) ==
                fileSystem.canonicalize(descriptors.get(handle(args, 1)).path)
        } catch (e: IOException) {
            return false
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
                    args.get(2) as String,
                    flag(args.get(1), "symlink-follow"),
                )
            )
        }
    }

    private fun filesystemReadDirectoryEntry(args: List<Any?>): Any? {
        requireArity("directory-entry-stream.read-directory-entry", args, 1)
        return filesystemResult {
            var stream = directoryEntryStreams.get(handle(args, 0))
            return@filesystemResult if (stream.entries.hasNext()) stream.entries.next() else null
        }
    }

    private fun filesystemErrorCode(args: List<Any?>): Any? {
        requireArity("filesystem-error-code", args, 1)
        return errors.get(handle(args, 0)).filesystemCode
    }

    private fun block(pollable: Pollable) {
        while (!pollable.ready()) {
            waitForPollableReadiness()
        }
    }

    private fun waitForPollableReadiness() {
        try {
            wasiDelay(1.milliseconds)
        } catch (e: Exception) {
            if (isWasiInterrupted(e)) {
                restoreWasiInterruptStatus()
                throw ComponentModelException("interrupted while waiting on WASI pollable", e)
            }
            throw e
        }
    }

    private fun datetime(seconds: Long, nanos: Long): Map<String, Any?> {
        return WitValue.record("seconds", seconds, "nanoseconds", nanos)
    }

    private fun instantFromDatetime(value: Any?): KotlinInstant {
        return KotlinInstant.fromEpochSeconds(
            asU64(recordField(value, "seconds")),
            asU64(recordField(value, "nanoseconds")),
        )
    }

    private fun timezoneName(instant: KotlinInstant): String {
        if (
            wallClockTimeZone.id == "UTC" && wallClockTimeZone.offsetAt(instant).totalSeconds == 0
        ) {
            return "UTC"
        }
        return wallClockTimeZone.id
    }

    private fun monotonicNow(): Long {
        return monotonicClock() - monotonicBaseNanos
    }

    private fun randomBytes(random: CryptoRand, length: Int): ByteArray {
        var bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    private fun randomBytes(random: Random, length: Int): ByteArray {
        var bytes = ByteArray(length)
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

    private fun filesystemResult(operation: () -> Any?): Any? {
        try {
            return WitResult.ok(operation())
        } catch (e: FsException) {
            return WitResult.err(e.code)
        } catch (e: IOException) {
            return WitResult.err(filesystemError(e))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                return WitResult.err("not-permitted")
            }
            throw e
        }
    }

    private fun readableDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        var descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("read")) {
            throw FsException("bad-descriptor")
        }
        if (isDirectory(descriptor.path)) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun writableDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        var descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("write")) {
            throw FsException("bad-descriptor")
        }
        if (isDirectory(descriptor.path)) {
            throw FsException("is-directory")
        }
        return descriptor
    }

    private fun mutableDirectoryDescriptor(args: List<Any?>, index: Int): FilesystemDescriptor {
        var descriptor = descriptors.get(handle(args, index))
        if (!descriptor.flags.contains("mutate-directory")) {
            throw FsException("read-only")
        }
        if (!isDirectory(descriptor.path)) {
            throw FsException("not-directory")
        }
        return descriptor
    }

    private fun descriptorFlags(value: Any?): Set<String> {
        var result = LinkedHashSet<String>()
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
            var map = value as Map<*, *>
            return map.get(name) == true || map.get(memberName(name)) == true
        }
        if (value is Iterable<*>) {
            for (item in value as Iterable<*>) {
                if (name.equals(stringValue(item))) {
                    return true
                }
            }
            return false
        }
        if (value is Number) {
            var bit: Int = flagBit(name)
            return bit >= 0 && (((value as Number).toLong() ushr bit) and 1L) != 0L
        }
        return false
    }

    private fun memberName(name: String): String {
        return camelCaseMemberName(name)
    }

    private fun flagBit(name: String): Int {
        return when (name) {
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
    }

    private fun resolvePath(
        base: FilesystemDescriptor,
        rawPath: String,
        followLast: Boolean,
    ): Path {
        var raw = (rawPath ?: "").toPath(normalize = true)
        if (raw.isAbsolute) {
            throw FsException("not-permitted")
        }
        var candidate = base.path.resolve(raw, normalize = true)
        var rootReal = fileSystem.canonicalize(base.root)
        var checked = realPathForSandboxCheck(candidate, followLast)
        if (!isInsidePreopen(rootReal, checked)) {
            throw FsException("not-permitted")
        }
        return candidate
    }

    private fun isDirectory(path: Path): Boolean =
        fileSystem.metadataOrNull(path)?.isDirectory == true

    private fun isInsidePreopen(root: Path, path: Path): Boolean {
        val rootText = root.normalized().toString().trimEnd('/')
        val pathText = path.normalized().toString()
        return pathText == rootText || pathText.startsWith("$rootText/")
    }

    private fun realPathForSandboxCheck(path: Path, followLast: Boolean): Path {
        if (followLast) {
            return realPathAllowingMissingLeaf(path)
        }
        var absolute = path.normalized()
        var parent = absolute.parent
        if (parent == null) {
            return absolute
        }
        return realPathAllowingMissingLeaf(parent).resolve(absolute.name, normalize = true)
    }

    private fun realPathAllowingMissingLeaf(path: Path): Path {
        if (fileSystem.exists(path)) {
            return fileSystem.canonicalize(path)
        }

        var missing = ArrayList<Path>()
        var current: Path? = path.normalized()
        while (current != null && !fileSystem.exists(current)) {
            missing.add(current.name.toPath())
            current = current.parent
        }

        if (current == null) {
            return path.normalized()
        }

        var resolved: Path = fileSystem.canonicalize(current)
        for (i in missing.size - 1 downTo 0) {
            resolved = resolved.resolve(missing.get(i), normalize = true)
        }
        return resolved.normalized()
    }

    private fun descriptorStat(path: Path): Map<String, Any?> {
        var metadata = fileSystem.metadata(path)
        return WitValue.record(
            "type",
            descriptorType(path),
            "link-count",
            1L,
            "size",
            metadata.size ?: 0L,
            "data-access-timestamp",
            datetime(metadata.lastAccessedAtMillis),
            "data-modification-timestamp",
            datetime(metadata.lastModifiedAtMillis),
            "status-change-timestamp",
            datetime(metadata.lastModifiedAtMillis),
        )
    }

    private fun metadataHash(path: Path): Map<String, Any?> {
        var metadata = fileSystem.metadata(path)
        val lower =
            hashValues(
                path.normalized().toString(),
                metadata.size ?: 0L,
                metadata.lastModifiedAtMillis ?: 0L,
            )
        val upper =
            hashValues(
                metadata.createdAtMillis ?: 0L,
                metadata.isDirectory,
                metadata.isRegularFile,
                metadata.symlinkTarget?.toString(),
            )
        return WitValue.record("lower", lower, "upper", upper)
    }

    private fun datetime(timeMillis: Long?): Map<String, Any?> {
        val millis = timeMillis ?: 0L
        val seconds = floorDiv(millis, 1_000L)
        val nanos = floorMod(millis, 1_000L) * 1_000_000L
        return datetime(seconds, nanos)
    }

    private fun descriptorType(path: Path): String {
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
        return "unknown"
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

    private fun setTimes(
        path: Path,
        accessTimestamp: Any?,
        modificationTimestamp: Any?,
        followSymlinks: Boolean,
    ) {
        var access = timestamp(accessTimestamp)
        var modified = timestamp(modificationTimestamp)
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
            wasiCreateHardLink(fileSystem, oldPath, newPath)
        } catch (_: UnsupportedOperationException) {
            throw FsException("unsupported")
        }
    }

    private fun timestamp(value: Any?): KotlinInstant? {
        if (!(value is WitValue.Variant)) {
            return null
        }
        var variant = value as WitValue.Variant
        if ("no-change".equals(variant.label())) {
            return null
        }
        if ("now".equals(variant.label())) {
            return wallClock.now()
        }
        if (!"timestamp".equals(variant.label()) || !(variant.value() is Map<*, *>)) {
            return null
        }
        var fields = variant.value() as Map<*, *>
        return KotlinInstant.fromEpochSeconds(
            asU64(fields.get("seconds")),
            asU64(fields.get("nanoseconds")),
        )
    }

    private fun filesystemError(e: IOException): String {
        var code = filesystemExceptionCode(e)
        return if (code == null) "io" else code
    }

    private fun filesystemExceptionCode(e: IOException): String? {
        if (e is FilesystemIOException) {
            return (e as FilesystemIOException).code
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

    private fun streamError(e: IOException): Any? {
        return WitResult.err(
            WitValue.variant("last-operation-failed", errors.insertResource(wasiError(e)))
        )
    }

    private fun networkResult(operation: () -> Any?): Any? {
        try {
            return WitResult.ok(operation())
        } catch (e: NetException) {
            return WitResult.err(e.code)
        } catch (e: IllegalArgumentException) {
            return WitResult.err("invalid-argument")
        } catch (e: UnsupportedOperationException) {
            return WitResult.err("not-supported")
        } catch (e: IOException) {
            if (isClosedChannel(e)) {
                return WitResult.err("invalid-state")
            }
            return WitResult.err(networkError(e))
        } catch (e: Exception) {
            if (isWasiSecurityException(e)) {
                return WitResult.err("access-denied")
            }
            throw e
        }
    }

    private fun requireNetwork(args: List<Any?>, index: Int) {
        if (!networks.get(handle(args, index)).enabled) {
            throw NetException("access-denied")
        }
    }

    private fun addressFamily(value: Any?): AddressFamily {
        var label = label(value, "ipv4", "ipv6")
        if ("ipv4".equals(label)) {
            return AddressFamily.IPV4
        }
        if ("ipv6".equals(label)) {
            return AddressFamily.IPV6
        }
        throw NetException("invalid-argument")
    }

    private fun tcpReady(socket: TcpSocket): Boolean {
        try {
            if (socket.listening && socket.listener != null) {
                return true
            }
            if (socket.connectStarted) {
                return socket.connection != null
            }
            return socket.connection == null || socket.connection!!.isOpen()
        } catch (e: Exception) {
            return true
        }
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
        var label = label(value, "ipv4", "ipv6")
        var payload = payload(value)
        var port: Int = asU64(recordField(payload, "port")).toInt()
        if (port < 0 || port > 0xffff) {
            throw NetException("invalid-argument")
        }
        var rawAddress: Any? = recordField(payload, "address")
        if ("ipv4".equals(label)) {
            var octets = numericTuple(rawAddress, 4)
            return InetSocketAddress(
                byteArrayOf(
                    octets[0].toByte(),
                    octets[1].toByte(),
                    octets[2].toByte(),
                    octets[3].toByte(),
                ),
                port,
            )
        }
        if ("ipv6".equals(label)) {
            var words = numericTuple(rawAddress, 8)
            var bytes = ByteArray(16)
            for (i in 0 until words.size) {
                bytes[i * 2] = ((words[i] ushr 8) and 0xff).toByte()
                bytes[i * 2 + 1] = (words[i] and 0xff).toByte()
            }
            return InetSocketAddress(bytes, port)
        }
        throw NetException("invalid-argument")
    }

    private fun socketAddress(address: InetSocketAddress): WitValue.Variant {
        val bytes = address.resolveAddress() ?: throw NetException("invalid-state")
        if (bytes.size == 16) {
            var words = ArrayList<Int>()
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
        var octets = ArrayList<Int>()
        for (b in bytes) {
            octets.add(unsignedByte(b))
        }
        return WitValue.variant("ipv4", WitValue.record("port", address.port, "address", octets))
    }

    private fun ipAddress(bytes: ByteArray): WitValue.Variant {
        if (bytes.size == 16) {
            var words = ArrayList<Int>()
            var i = 0
            while (i < bytes.size) {
                words.add((unsignedByte(bytes[i]) shl 8) or unsignedByte(bytes[i + 1]))
                i += 2
            }
            return WitValue.variant("ipv6", words)
        }
        var octets = ArrayList<Int>()
        for (b in bytes) {
            octets.add(unsignedByte(b))
        }
        return WitValue.variant("ipv4", octets)
    }

    private fun resolveIpAddresses(hostname: String): List<ByteArray> {
        val address =
            InetSocketAddress(hostname, 0).resolveAddress()
                ?: throw NetException("name-unresolvable")
        return listOf(address)
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

    private fun option(value: Any?): Any? {
        if (value == null) {
            return null
        }
        if (value is WitValue.Variant) {
            var variant = value as WitValue.Variant
            if ("none".equals(variant.label())) {
                return null
            }
            if ("some".equals(variant.label())) {
                return variant.value()
            }
        }
        return value
    }

    private fun label(value: Any?, vararg numericLabels: String): String {
        if (value is WitValue.Variant) {
            return (value as WitValue.Variant).label()
        }
        if (value is Number) {
            var index: Int = asU64(value).toInt()
            if (index >= 0 && index < numericLabels.size) {
                return numericLabels[index]
            }
        }
        return stringValue(value)
    }

    private fun payload(value: Any?): Any? {
        if (value is WitValue.Variant) {
            var variant = value as WitValue.Variant
            if (!variant.hasValue()) {
                throw NetException("invalid-argument")
            }
            return variant.value()
        }
        if (value is Map<*, *>) {
            var map = value as Map<*, *>
            if (map.size == 1) {
                return map.values.iterator().next()
            }
        }
        throw NetException("invalid-argument")
    }

    private fun recordField(value: Any?, name: String): Any? {
        if (value is Map<*, *>) {
            var map = value as Map<*, *>
            if (map.containsKey(name)) {
                return map.get(name)
            }
            var member = memberNameStatic(name)
            if (map.containsKey(member)) {
                return map.get(member)
            }
        }
        if (value is List<*>) {
            var list = value as List<*>
            when (name) {
                "port",
                "data" -> return list.get(0)
                "address",
                "remote-address" -> return list.get(1)
            }
        }
        throw ComponentModelException("missing WIT record field " + name)
    }

    private fun memberNameStatic(name: String): String {
        return camelCaseMemberName(name)
    }

    private fun numericTuple(value: Any?, size: Int): LongArray {
        var list = list(value)
        if (list.size != size) {
            throw ComponentModelException("expected tuple size " + size + ", got " + list.size)
        }
        var result = LongArray(size)
        for (i in 0 until size) {
            result[i] = asU64(list.get(i))
        }
        return result
    }

    private fun positiveInt(value: Any?): Int {
        var longValue = asU64(value)
        if (longValue <= 0 || longValue > Int.MAX_VALUE) {
            throw NetException("invalid-argument")
        }
        return longValue.toInt()
    }

    private fun networkError(e: IOException): String {
        var code = networkCode(e)
        return if (code == null) "unknown" else code
    }

    private fun wasiError(e: IOException): WasiError {
        var debug = e.message ?: exceptionClassName(e)
        return WasiError(debug, httpCode(debug), networkCode(e), filesystemExceptionCode(e))
    }

    private fun networkCode(e: IOException): String? {
        exceptionCodeFromClassName(e)?.let {
            return it
        }
        if (e.message != null) {
            var message = (e.message ?: "").lowercase()
            if (message.contains("permission") || message.contains("denied")) {
                return "access-denied"
            }
            if (message.contains("address already")) {
                return "address-in-use"
            }
            if (message.contains("connection reset")) {
                return "connection-reset"
            }
            if (message.contains("connection refused")) {
                return "connection-refused"
            }
            if (
                message.contains("network is unreachable") ||
                    message.contains("no route") ||
                    message.contains("host is unreachable")
            ) {
                return "remote-unreachable"
            }
        }
        return null
    }

    private fun exceptionCodeFromClassName(e: Throwable): String? =
        when (exceptionSimpleName(e)) {
            "BindException" -> "address-in-use"
            "ConnectException" -> "connection-refused"
            "NoRouteToHostException" -> "remote-unreachable"
            "SocketTimeoutException" -> "timeout"
            else -> null
        }

    private fun httpCode(debug: String): Any? {
        return when (debug) {
            "DNS-timeout",
            "destination-not-found",
            "destination-unavailable",
            "destination-IP-prohibited",
            "destination-IP-unroutable",
            "connection-refused",
            "connection-terminated",
            "connection-timeout",
            "connection-read-timeout",
            "connection-write-timeout",
            "connection-limit-reached",
            "TLS-protocol-error",
            "TLS-certificate-error",
            "HTTP-request-denied",
            "HTTP-request-length-required",
            "HTTP-request-method-invalid",
            "HTTP-request-URI-invalid",
            "HTTP-request-URI-too-long",
            "HTTP-response-incomplete",
            "HTTP-response-timeout",
            "HTTP-upgrade-failed",
            "HTTP-protocol-error",
            "loop-detected",
            "configuration-error" -> debug
            else -> null
        }
    }

    private fun sendHttpRequest(
        request: OutgoingRequest,
        options: RequestOptions?,
    ): WasiHttpResponse {
        val data = httpRequestData(request, options)
        return httpClient.send(data)
    }

    private fun httpRequestData(
        request: OutgoingRequest,
        options: RequestOptions?,
    ): WasiHttpRequest {
        val scheme = if (request.scheme == null) "HTTP" else label(request.scheme, "HTTP", "HTTPS")
        val javaScheme =
            when (scheme) {
                "HTTP" -> "http"
                "HTTPS" -> "https"
                "other" -> stringValue(variantPayload(request.scheme))
                else -> throw HttpException("HTTP-request-URI-invalid")
            }
        val authority = request.authority
        if (authority == null || authority.isBlank()) {
            throw HttpException("HTTP-request-URI-invalid")
        }
        var path = request.pathWithQuery?.takeIf { it.isNotEmpty() } ?: "/"
        if (!path.startsWith("/")) {
            path = "/" + path
        }
        val uri =
            try {
                Url(javaScheme + "://" + authority + path).toString()
            } catch (_: URLParserException) {
                throw HttpException("HTTP-request-URI-invalid")
            } catch (_: IllegalArgumentException) {
                throw HttpException("HTTP-request-URI-invalid")
            }
        val requestBody = request.body
        if (requestBody != null && !requestBody.finished) {
            throw HttpException(WitValue.variant("HTTP-request-body-size", null))
        }
        val method = httpMethodName(request.method)
        val body = requestBody?.bodyBytes() ?: ByteArray(0)
        return WasiHttpRequest(
            method,
            uri,
            request.headers!!.entries.map { WasiHttpHeader(it.name, latin1String(it.value)) },
            body,
            httpTimeout(options),
        )
    }

    private fun httpTimeout(options: RequestOptions?): Duration? {
        val nanos = options?.firstByteTimeoutNanos ?: options?.connectTimeoutNanos
        if (nanos == null || nanos <= 0) {
            return null
        }
        return nanos.nanoseconds
    }

    private fun fieldsFromHttpHeaders(
        headers: Map<String, List<String>>,
        mutable: Boolean,
    ): HttpFields {
        var fields = HttpFields(true)
        for (entry in headers.entries) {
            for (value in entry.value) {
                fields.append(entry.key, latin1Bytes(value))
            }
        }
        return if (mutable) fields else fields.copy(false)
    }

    private fun fieldsFromByteHeaders(
        headers: Map<String, List<ByteArray>>?,
        mutable: Boolean,
    ): HttpFields {
        var fields = HttpFields(true)
        if (headers == null) {
            return if (mutable) fields else fields.copy(false)
        }
        for (entry in headers.entries) {
            for (value in entry.value) {
                fields.append(entry.key, value)
            }
        }
        return if (mutable) fields else fields.copy(false)
    }

    private fun httpHeadersSnapshot(fields: HttpFields): Map<String, List<ByteArray>> {
        val result = LinkedHashMap<String, MutableList<ByteArray>>()
        for (entry in fields.entries) {
            result.getOrPut(entry.name) { ArrayList() }.add(entry.value.copyOf())
        }
        return result.mapValues { it.value.toList() }.toMap()
    }

    private fun httpInternalError(e: Exception): Any? {
        var message = e.message ?: exceptionClassName(e)
        return WitValue.variant("internal-error", message)
    }

    private fun httpError(e: Exception): Any? {
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

    private fun httpMethod(value: Any?): Any? {
        var label =
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
        return when (label) {
            "get",
            "head",
            "post",
            "put",
            "delete",
            "connect",
            "options",
            "trace",
            "patch" -> label
            "other" -> {
                var method = stringValue(variantPayload(value))
                if (method.isBlank() || method.indexOf(' ') >= 0) {
                    throw HttpException("HTTP-request-method-invalid")
                }
                WitValue.variant("other", method)
            }
            else -> throw HttpException("HTTP-request-method-invalid")
        }
    }

    private fun incomingMethod(method: String): Any? {
        if (method == null || method.isBlank()) {
            return "get"
        }
        var normalized = method.lowercase()
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
            else -> {
                if (method.indexOf(' ') >= 0) {
                    throw HttpException("HTTP-request-method-invalid")
                }
                WitValue.variant("other", method)
            }
        }
    }

    private fun httpMethodName(value: Any?): String {
        var label =
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
        if ("other".equals(label)) {
            return stringValue(variantPayload(value))
        }
        return label.uppercase()
    }

    private fun httpScheme(value: Any?): Any? {
        if (value == null) {
            return null
        }
        var label = label(value, "HTTP", "HTTPS")
        if ("HTTP".equals(label) || "HTTPS".equals(label)) {
            return label
        }
        if ("other".equals(label)) {
            var scheme = stringValue(variantPayload(value))
            if (scheme.isBlank()) {
                throw HttpException("HTTP-request-URI-invalid")
            }
            return WitValue.variant("other", scheme)
        }
        throw HttpException("HTTP-request-URI-invalid")
    }

    private fun incomingScheme(scheme: String): Any? {
        if (scheme == null || scheme.isBlank()) {
            return null
        }
        var normalized = scheme.lowercase()
        if ("http".equals(normalized)) {
            return "HTTP"
        }
        if ("https".equals(normalized)) {
            return "HTTPS"
        }
        if (scheme.indexOf(':') >= 0) {
            throw HttpException("HTTP-request-URI-invalid")
        }
        return WitValue.variant("other", scheme)
    }

    private fun optionString(value: Any?, defaultValue: String?): String? {
        var option = option(value)
        return if (option == null) defaultValue else option.toString()
    }

    private fun optionU64(value: Any?): Long? {
        var option = option(value)
        return if (option == null) null else asU64(option)
    }

    private fun tupleValue(value: Any?, index: Int): Any? {
        if (value is List<*>) {
            return (value as List<*>).get(index)
        }
        if (value is Map<*, *>) {
            var map = value as Map<*, *>
            if (index == 0) {
                return map.get("0")
            }
            return map.get("1")
        }
        throw ComponentModelException("expected WIT tuple, got " + value)
    }

    private fun variantPayload(value: Any?): Any? {
        if (value is WitValue.Variant) {
            var variant = value as WitValue.Variant
            if (variant.hasValue()) {
                return variant.value()
            }
        }
        if (value is Map<*, *>) {
            var map = value as Map<*, *>
            if (map.size == 1) {
                return map.values.iterator().next()
            }
        }
        throw ComponentModelException("expected WIT variant payload, got " + value)
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

    private fun registerResourceMethod(
        builder: WasiHostImportBuilder,
        resourceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        registerResourceMethod(
            builder,
            FILESYSTEM_PACKAGE,
            "types",
            resourceName,
            functionName,
            handler,
        )
    }

    private fun registerSocketsResourceMethod(
        builder: WasiHostImportBuilder,
        interfaceName: String,
        resourceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        registerResourceMethod(
            builder,
            SOCKETS_PACKAGE,
            interfaceName,
            resourceName,
            functionName,
            handler,
        )
    }

    private fun registerHttpMethod(
        builder: WasiHostImportBuilder,
        resourceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        registerResourceMethod(builder, HTTP_PACKAGE, "types", resourceName, functionName, handler)
    }

    private fun registerHttpConstructor(
        builder: WasiHostImportBuilder,
        resourceName: String,
        handler: HostHandler,
    ) {
        register(builder, HTTP_PACKAGE, "types", resourceName + ".constructor", handler)
        register(builder, HTTP_PACKAGE, "types", "[constructor]" + resourceName, handler)
    }

    private fun registerHttpStatic(
        builder: WasiHostImportBuilder,
        resourceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, HTTP_PACKAGE, "types", resourceName + "." + functionName, handler)
        register(
            builder,
            HTTP_PACKAGE,
            "types",
            "[static]" + resourceName + "." + functionName,
            handler,
        )
    }

    private fun registerResourceMethod(
        builder: WasiHostImportBuilder,
        packageName: String,
        interfaceName: String,
        resourceName: String,
        functionName: String,
        handler: HostHandler,
    ) {
        register(builder, packageName, interfaceName, resourceName + "." + functionName, handler)
        register(
            builder,
            packageName,
            interfaceName,
            "[method]" + resourceName + "." + functionName,
            handler,
        )
    }

    private fun registerTcpMethod(
        builder: WasiHostImportBuilder,
        methodName: String,
        method: SocketResourceMethod<TcpSocket>,
    ) {
        registerSocketsResourceMethod(
            builder,
            "tcp",
            "tcp-socket",
            methodName,
            { args -> method.apply(tcpSockets.get(handle(args, 0)), args) },
        )
    }

    private fun registerUdpMethod(
        builder: WasiHostImportBuilder,
        methodName: String,
        method: SocketResourceMethod<UdpSocket>,
    ) {
        registerSocketsResourceMethod(
            builder,
            "udp",
            "udp-socket",
            methodName,
            { args -> method.apply(udpSockets.get(handle(args, 0)), args) },
        )
    }

    private fun <T> registerResourceDrop(
        builder: WasiHostImportBuilder,
        packageName: String,
        interfaceName: String,
        resourceName: String,
        table: WitResourceTable<T>,
    ) {
        register(
            builder,
            packageName,
            interfaceName,
            "[resource-drop]" + resourceName,
            { args ->
                requireArity("[resource-drop]" + resourceName, args, 1)
                table.remove(handle(args, 0))
                null
            },
        )
    }

    private fun moduleNames(packageName: String, interfaceName: String): Set<String> {
        var result = LinkedHashSet<String>()
        result.add(packageName + "/" + interfaceName + "@" + version)
        for (compatibleVersion in COMPATIBLE_VERSIONS) {
            result.add(packageName + "/" + interfaceName + "@" + compatibleVersion)
        }
        result.add(packageName + "/" + interfaceName)
        result.add(interfaceName)
        return result
    }

    private fun requireArity(functionName: String, args: List<Any?>, arity: Int) {
        if (args.size != arity) {
            throw ComponentModelException(
                functionName + " expected " + arity + " arguments, got " + args.size
            )
        }
    }

    private fun handle(args: List<Any?>, index: Int): Long {
        return asU64(args.get(index))
    }

    private fun boundedLength(value: Any?): Int {
        var length = asU64(value)
        if (length < 0 || length > MAX_IO_CHUNK) {
            return MAX_IO_CHUNK
        }
        return length.toInt()
    }

    private fun checkedByteLength(value: Any?): Int {
        var length = asU64(value)
        if (length < 0 || length > Int.MAX_VALUE) {
            throw ComponentModelException(
                "WASI list length is too large for this host: " + unsignedLongString(length)
            )
        }
        return length.toInt()
    }

    private fun bytes(value: Any?): ByteArray {
        if (value is ByteArray) {
            return value as ByteArray
        }
        if (value is List<*>) {
            var list = value as List<*>
            var result = ByteArray(list.size)
            for (i in 0 until list.size) {
                result[i] = asU64(list.get(i)).toByte()
            }
            return result
        }
        if (value is Iterable<*>) {
            var out = Buffer()
            for (item in value as Iterable<*>) {
                out.writeByte(asU64(item).toByte())
            }
            return out.readByteArray()
        }
        if (value == null) {
            return ByteArray(0)
        }
        throw ComponentModelException("expected list<u8>, got " + exceptionClassName(value))
    }

    private fun list(value: Any?): List<*> {
        if (value is List<*>) {
            return value as List<*>
        }
        if (value is Iterable<*>) {
            var result = ArrayList<Any?>()
            for (item in value as Iterable<*>) {
                result.add(item)
            }
            return result
        }
        throw ComponentModelException("expected WIT list, got " + value)
    }

    private fun asU64(value: Any?): Long {
        if (value is Number) {
            return (value as Number).toLong()
        }
        if (value is WitResource<*>) {
            return (value as WitResource<*>).handle()
        }
        throw ComponentModelException("expected numeric WASI value, got " + value)
    }

    private fun exitCode(status: Any?): Int {
        if (status is WitValue.Variant) {
            var variant = status as WitValue.Variant
            if ("ok".equals(variant.label())) {
                return 0
            }
            if ("err".equals(variant.label())) {
                return 1
            }
        }
        if (status is WitResult.Ok<*, *>) {
            return 0
        }
        if (status is WitResult.Err<*, *>) {
            return 1
        }
        if (status is Number) {
            return (status as Number).toInt()
        }
        if (status is Boolean) {
            return if (status as Boolean) 0 else 1
        }
        return 1
    }

    class Builder {
        var version: String = DEFAULT_VERSION
        var stdin: WasiInputStream =
            WasiInputStream(defaultWasiStdin(), defaultWasiStdinAvailable())
        var stdout: WasiOutputStream = WasiOutputStream(defaultWasiStdout())
        var stderr: WasiOutputStream = WasiOutputStream(defaultWasiStderr())
        val arguments: MutableList<String> = ArrayList()
        val environment: MutableMap<String, String> = LinkedHashMap()
        var initialCwd: String? = null
        var wallClock: KotlinClock = KotlinClock.System
        var wallClockTimeZone: TimeZone = TimeZone.UTC
        var wallClockResolutionNanos: Long = 1_000_000L
        var monotonicClock: () -> Long = defaultMonotonicClock()
        var monotonicResolutionNanos: Long = 1L
        var secureRandom: CryptoRand = CryptoRand.Default
        var insecureRandom: Random = Random.Default
        var insecureSeedLower: Long = Random.Default.nextLong()
        var insecureSeedUpper: Long = Random.Default.nextLong()
        var fileSystem: FileSystem = defaultWasiFileSystem()
        internal val preopens: MutableList<Preopen> = ArrayList()
        var terminalStdin: Boolean = false
        var terminalStdout: Boolean = false
        var terminalStderr: Boolean = false
        var networkingEnabled: Boolean = false
        var httpClient: WasiHttpClient = defaultWasiHttpClient()
        internal var socketRuntime: WasiSocketRuntime = defaultWasiSocketRuntime()

        constructor() {}

        fun withVersion(version: String): Builder {
            this.version = requirePresent(version, "version")
            return this
        }

        fun withStdin(stdin: RawSource): Builder {
            this.stdin = WasiInputStream(requirePresent(stdin, "stdin"))
            return this
        }

        fun withStdin(stdin: RawSource, available: () -> Int): Builder {
            this.stdin =
                WasiInputStream(
                    requirePresent(stdin, "stdin"),
                    requirePresent(available, "available"),
                )
            return this
        }

        fun withStdout(stdout: RawSink): Builder {
            this.stdout = WasiOutputStream(requirePresent(stdout, "stdout"))
            return this
        }

        fun withStderr(stderr: RawSink): Builder {
            this.stderr = WasiOutputStream(requirePresent(stderr, "stderr"))
            return this
        }

        fun withArguments(arguments: List<String>): Builder {
            this.arguments.clear()
            this.arguments.addAll(arguments)
            return this
        }

        fun withArguments(vararg arguments: String): Builder {
            this.arguments.clear()
            for (argument in arguments) {
                this.arguments.add(requirePresent(argument, "argument"))
            }
            return this
        }

        fun withEnvironment(environment: Map<String, String>): Builder {
            this.environment.clear()
            this.environment.putAll(environment)
            return this
        }

        fun withEnvironment(name: String, value: String): Builder {
            this.environment.put(requirePresent(name, "name"), requirePresent(value, "value"))
            return this
        }

        fun withSystemEnvironment(): Builder {
            this.environment.clear()
            this.environment.putAll(defaultWasiEnvironment())
            return this
        }

        fun withInitialCwd(initialCwd: String): Builder {
            this.initialCwd = initialCwd
            return this
        }

        fun withInitialCwd(initialCwd: Path): Builder {
            this.initialCwd = requirePresent(initialCwd, "initialCwd").normalized().toString()
            return this
        }

        fun withPreopenedDirectory(guestPath: String, hostPath: String): Builder {
            return withPreopenedDirectory(guestPath, hostPath.toPath(normalize = true), true)
        }

        fun withPreopenedDirectory(guestPath: String, hostPath: Path): Builder {
            return withPreopenedDirectory(guestPath, hostPath, true)
        }

        fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: String): Builder {
            return withPreopenedDirectory(guestPath, hostPath.toPath(normalize = true), false)
        }

        fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: Path): Builder {
            return withPreopenedDirectory(guestPath, hostPath, false)
        }

        fun withPreopenedDirectory(guestPath: String, hostPath: Path, writable: Boolean): Builder {
            preopens.add(Preopen(guestPath, fileSystem.canonicalize(hostPath), writable))
            return this
        }

        fun withFileSystem(fileSystem: FileSystem): Builder {
            this.fileSystem = requirePresent(fileSystem, "fileSystem")
            return this
        }

        fun withTerminal(terminal: Boolean): Builder {
            this.terminalStdin = terminal
            this.terminalStdout = terminal
            this.terminalStderr = terminal
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
            this.networkingEnabled = true
            return this
        }

        fun withNetworking(networkingEnabled: Boolean): Builder {
            this.networkingEnabled = networkingEnabled
            return this
        }

        fun withoutNetworking(): Builder {
            this.networkingEnabled = false
            return this
        }

        fun withHttpClient(httpClient: WasiHttpClient): Builder {
            this.httpClient = requirePresent(httpClient, "httpClient")
            return this
        }

        fun withHttpClient(httpClient: io.ktor.client.HttpClient): Builder =
            withHttpClient(ktorWasiHttpClient(requirePresent(httpClient, "httpClient")))

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

        fun withSecureRandom(secureRandom: Random): Builder {
            return withSecureRandom(
                KotlinRandomCryptoRand(requirePresent(secureRandom, "secureRandom"))
            )
        }

        fun withInsecureRandom(insecureRandom: Random): Builder {
            this.insecureRandom = requirePresent(insecureRandom, "insecureRandom")
            return this
        }

        fun withInsecureSeed(lower: Long, upper: Long): Builder {
            this.insecureSeedLower = lower
            this.insecureSeedUpper = upper
            return this
        }

        fun build(): WasiPreview2 {
            return WasiPreview2(this)
        }

        private fun requireNanos(name: String, value: Long): Long {
            if (value <= 0) {
                throw IllegalArgumentException(name + " must be positive")
            }
            return value
        }
    }

    private fun interface SocketResourceMethod<T> {
        fun apply(resource: T, args: List<Any?>): Any?
    }

    private class FsException : Exception {
        val code: String

        constructor(code: String) {
            this.code = code
        }
    }

    private class WasiError {
        val debug: String
        val httpCode: Any?
        val networkCode: String?
        val filesystemCode: String?

        constructor(debug: String, httpCode: Any?, networkCode: String?, filesystemCode: String?) {
            this.debug = debug
            this.httpCode = httpCode
            this.networkCode = networkCode
            this.filesystemCode = filesystemCode
        }
    }

    private class FilesystemIOException(val code: String, cause: IOException) :
        IOException(cause.message, cause)

    class WasiInputStream
    internal constructor(
        private val source: RawSource,
        private val available: (() -> Int)? = null,
    ) {
        @Throws(IOException::class)
        internal fun readBytes(len: Int, blocking: Boolean): ByteArray {
            var count = len
            if (!blocking) {
                count = kotlin.math.min(count, kotlin.math.max(0, available()))
                if (count == 0) {
                    return ByteArray(0)
                }
            }
            val out = Buffer()
            var remaining = count
            while (remaining > 0) {
                val read =
                    source.readAtMostTo(out, kotlin.math.min(MAX_IO_CHUNK, remaining).toLong())
                if (read <= 0L) {
                    break
                }
                remaining -= read.toInt()
                if (!blocking) {
                    break
                }
            }
            return out.readByteArray()
        }

        @Throws(IOException::class)
        internal fun skip(requested: Int, blocking: Boolean): Long =
            readBytes(requested, blocking).size.toLong()

        @Throws(IOException::class) internal fun available(): Int = available?.invoke() ?: 0

        @Throws(IOException::class)
        internal fun close() {
            source.close()
        }

        internal companion object {
            fun fromBytes(bytes: ByteArray): WasiInputStream {
                val buffer = Buffer()
                buffer.write(bytes)
                return WasiInputStream(buffer) {
                    buffer.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
        }
    }

    class WasiOutputStream internal constructor(private val sink: RawSink) {
        @Throws(IOException::class)
        internal fun write(bytes: ByteArray) {
            val buffer = Buffer()
            buffer.write(bytes)
            sink.write(buffer, bytes.size.toLong())
        }

        @Throws(IOException::class)
        internal fun flush() {
            sink.flush()
        }

        @Throws(IOException::class)
        internal fun close() {
            sink.close()
        }
    }

    private class NetException : Exception {
        val code: String

        constructor(code: String) {
            this.code = code
        }
    }

    private class HttpException : Exception {
        val code: Any?

        constructor(code: Any?) {
            this.code = code
        }
    }

    internal class Preopen {
        val guestPath: String
        val hostPath: Path
        val flags: Set<String>

        constructor(guestPath: String, hostPath: Path, writable: Boolean) {
            this.guestPath = requirePresent(guestPath, "guestPath")
            this.hostPath = requirePresent(hostPath, "hostPath").normalized()
            var next = LinkedHashSet<String>()
            next.add("read")
            if (writable) {
                next.add("write")
                next.add("mutate-directory")
            }
            this.flags = next.toSet()
        }
    }

    private class FilesystemDescriptor {
        val root: Path
        val path: Path
        val flags: Set<String>

        constructor(root: Path, path: Path, flags: Set<String>) {
            this.root = requirePresent(root, "root").normalized()
            this.path = requirePresent(path, "path").normalized()
            this.flags = flags.toSet()
        }
    }

    private class DirectoryEntryStream {
        val entries: Iterator<Map<String, Any?>>

        constructor(entries: Iterator<Map<String, Any?>>) {
            this.entries = requirePresent(entries, "entries")
        }
    }

    private inner class SeekableFileSource : OkioSource {
        private val handle: okio.FileHandle
        private var offset: Long

        constructor(path: Path, offset: Long) {
            this.handle = fileSystem.openReadOnly(path)
            this.offset = offset
        }

        override fun read(sink: okio.Buffer, byteCount: Long): Long {
            if (byteCount == 0L) {
                return 0L
            }
            try {
                val read = handle.read(offset, sink, byteCount)
                if (read > 0L) {
                    offset += read
                }
                return read
            } catch (e: IOException) {
                throw filesystemStreamError(e)
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            try {
                handle.close()
            } catch (e: IOException) {
                throw filesystemStreamError(e)
            }
        }
    }

    private inner class SeekableFileSink : OkioSink {
        private val handle: okio.FileHandle
        private var offset: Long = 0L

        constructor(path: Path, offset: Long) {
            this.handle = fileSystem.openReadWrite(path)
            this.offset = offset
        }

        override fun write(source: okio.Buffer, byteCount: Long) {
            if (byteCount == 0L) {
                return
            }
            try {
                handle.write(offset, source, byteCount)
                offset += byteCount
            } catch (e: IOException) {
                throw filesystemStreamError(e)
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun flush() {
            try {
                handle.flush()
            } catch (e: IOException) {
                throw filesystemStreamError(e)
            }
        }

        override fun close() {
            try {
                handle.close()
            } catch (e: IOException) {
                throw filesystemStreamError(e)
            }
        }
    }

    private fun filesystemStreamError(e: IOException): FilesystemIOException {
        if (e is FilesystemIOException) {
            return e as FilesystemIOException
        }
        return FilesystemIOException(filesystemError(e), e)
    }

    private class TerminalInput

    private class TerminalOutput

    private class Network {
        val enabled: Boolean

        constructor(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private enum class AddressFamily {
        IPV4("ipv4"),
        IPV6("ipv6");

        val label: String

        constructor(label: String) {
            this.label = label
        }
    }

    private class ResolveAddressStream {
        val addresses: Iterator<ByteArray>

        constructor(addresses: Iterator<ByteArray>) {
            this.addresses = requirePresent(addresses, "addresses")
        }
    }

    private class TcpSocket {
        val family: AddressFamily
        var connection: WasiTcpConnection? = null
        var listener: WasiTcpListener? = null
        var pendingBind: InetSocketAddress? = null
        var localAddress: InetSocketAddress? = null
        var remoteAddress: InetSocketAddress? = null
        var bindStarted: Boolean = false
        var listenStarted: Boolean = false
        var connectStarted: Boolean = false
        var bound: Boolean = false
        var listening: Boolean = false
        var connected: Boolean = false
        var listenBacklog: Int = 128
        var keepAlive: Boolean = false
        var hopLimit: Int = 64
        var keepAliveIdleTimeNanos: Long = 7_200_000_000_000L
        var keepAliveIntervalNanos: Long = 75_000_000_000L
        var keepAliveCount: Int = 9
        var receiveBufferSize: Int = 65_536
        var sendBufferSize: Int = 65_536

        constructor(family: AddressFamily) {
            this.family = requirePresent(family, "family")
        }

        fun inheritConnectionOptionsFrom(parent: TcpSocket) {
            keepAlive = parent.keepAlive
            hopLimit = parent.hopLimit
            keepAliveIdleTimeNanos = parent.keepAliveIdleTimeNanos
            keepAliveIntervalNanos = parent.keepAliveIntervalNanos
            keepAliveCount = parent.keepAliveCount
            receiveBufferSize = parent.receiveBufferSize
            sendBufferSize = parent.sendBufferSize
        }
    }

    private class UdpSocket {
        val family: AddressFamily
        var endpoint: WasiUdpEndpoint? = null
        var pendingBind: InetSocketAddress? = null
        var localAddress: InetSocketAddress? = null
        var remoteAddress: InetSocketAddress? = null
        var bindStarted: Boolean = false
        var bound: Boolean = false
        var unicastHopLimit: Int = 64
        var receiveBufferSize: Int = 65_536
        var sendBufferSize: Int = 65_536

        constructor(family: AddressFamily) {
            this.family = requirePresent(family, "family")
        }
    }

    private class IncomingDatagramStream {
        val socket: UdpSocket

        constructor(socket: UdpSocket) {
            this.socket = requirePresent(socket, "socket")
        }
    }

    private class OutgoingDatagramStream {
        val socket: UdpSocket
        var permittedDatagrams: Long = 0L

        constructor(socket: UdpSocket) {
            this.socket = requirePresent(socket, "socket")
        }
    }

    private class HttpField {
        val name: String
        val value: ByteArray

        constructor(name: String, value: ByteArray) {
            this.name = requirePresent(name, "name")
            this.value = value.copyOf()
        }
    }

    private class HttpFields {
        val mutable: Boolean
        val entries: MutableList<HttpField> = ArrayList()

        constructor(mutable: Boolean) {
            this.mutable = mutable
        }

        fun get(name: String): List<ByteArray> {
            var result = ArrayList<ByteArray>()
            if (!validName(name)) {
                return result
            }
            for (entry in entries) {
                if (entry.name.equals(name, ignoreCase = true)) {
                    result.add(entry.value.copyOf())
                }
            }
            return result
        }

        fun has(name: String): Boolean {
            if (!validName(name)) {
                return false
            }
            for (entry in entries) {
                if (entry.name.equals(name, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun set(name: String, values: List<ByteArray>) {
            ensureMutable()
            ensureValid(name)
            deleteExisting(name)
            for (value in values) {
                entries.add(HttpField(name, value))
            }
        }

        fun delete(name: String) {
            ensureMutable()
            ensureValid(name)
            deleteExisting(name)
        }

        fun append(name: String, value: ByteArray) {
            ensureMutable()
            ensureValid(name)
            entries.add(HttpField(name, value))
        }

        fun entries(): List<List<Any?>> {
            var result = ArrayList<List<Any?>>()
            for (entry in entries) {
                result.add(listOf(entry.name, entry.value.copyOf()))
            }
            return result
        }

        fun copy(mutable: Boolean): HttpFields {
            var copy = HttpFields(mutable)
            for (entry in entries) {
                copy.entries.add(HttpField(entry.name, entry.value))
            }
            return copy
        }

        private fun deleteExisting(name: String) {
            entries.removeAll { entry -> entry.name.equals(name, ignoreCase = true) }
        }

        private fun ensureMutable() {
            if (!mutable) {
                throw HttpException("immutable")
            }
        }

        private fun ensureValid(name: String) {
            if (!validName(name)) {
                throw HttpException("invalid-syntax")
            }
        }

        private fun validName(name: String): Boolean {
            if (name == null || name.isEmpty()) {
                return false
            }
            for (i in 0 until name.length) {
                var ch: Char = name[i]
                if (ch <= ' ' || ch.code >= 127 || ch == ':') {
                    return false
                }
            }
            return true
        }
    }

    private class IncomingRequest {
        val method: Any?
        val pathWithQuery: String
        val scheme: Any?
        val authority: String
        val headers: HttpFields
        val body: ByteArray
        var bodyConsumed: Boolean = false

        constructor(
            method: Any?,
            pathWithQuery: String,
            scheme: Any?,
            authority: String,
            headers: HttpFields,
            body: ByteArray,
        ) {
            this.method = method
            this.pathWithQuery = pathWithQuery
            this.scheme = scheme
            this.authority = authority
            this.headers = headers
            this.body = body.copyOf()
        }
    }

    private class OutgoingRequest {
        var headers: HttpFields? = null
        var method: Any? = "get"
        var pathWithQuery: String? = null
        var scheme: Any? = null
        var authority: String? = null
        var body: OutgoingBody? = null
        var bodyTaken: Boolean = false

        constructor(headers: HttpFields) {
            this.headers = requirePresent(headers, "headers")
        }
    }

    private class RequestOptions {
        var connectTimeoutNanos: Long? = null
        var firstByteTimeoutNanos: Long? = null
        var betweenBytesTimeoutNanos: Long? = null
    }

    private class ResponseOutparam {
        var response: Any? = null
        var set: Boolean = false
    }

    private class IncomingResponse {
        val status: Int
        val headers: HttpFields
        val body: WasiInputStream
        var bodyConsumed: Boolean = false

        constructor(
            status: Int,
            headers: HttpFields,
            body: ByteArray,
        ) : this(status, headers, WasiInputStream.fromBytes(body))

        constructor(status: Int, headers: HttpFields, body: WasiInputStream) {
            this.status = status
            this.headers = requirePresent(headers, "headers")
            this.body = requirePresent(body, "body")
        }
    }

    private class IncomingBody {
        val body: WasiInputStream
        var streamTaken: Boolean = false

        constructor(body: ByteArray) : this(WasiInputStream.fromBytes(body))

        constructor(body: WasiInputStream) {
            this.body = requirePresent(body, "body")
        }

        fun inputStream(): WasiInputStream = body
    }

    private class FutureTrailers {
        val trailers: HttpFields?
        val errorCode: Any?
        var consumed: Boolean = false

        constructor(trailers: HttpFields?, errorCode: Any?) {
            this.trailers = trailers
            this.errorCode = errorCode
        }
    }

    private class OutgoingResponse {
        val headers: HttpFields
        var status: Int = 200
        var body: OutgoingBody? = null
        var bodyTaken: Boolean = false

        constructor(headers: HttpFields) {
            this.headers = requirePresent(headers, "headers")
        }
    }

    private class OutgoingBody {
        private val output: Buffer = Buffer()
        var streamTaken: Boolean = false
        var finished: Boolean = false

        fun outputStream(): WasiOutputStream = WasiOutputStream(output)

        fun bodyBytes(): ByteArray = output.copy().readByteArray()
    }

    private class FutureIncomingResponse {
        val response: IncomingResponse
        val errorCode: Any?
        var consumed: Boolean = false

        constructor(response: IncomingResponse, errorCode: Any?) {
            this.response = response
            this.errorCode = errorCode
        }
    }

    class HttpRequestSnapshot {
        private val method: String
        private val pathWithQuery: String
        private val scheme: String
        private val authority: String
        private val headers: Map<String, List<ByteArray>>
        private val body: ByteArray

        constructor(
            method: String,
            pathWithQuery: String,
            scheme: String,
            authority: String,
            headers: Map<String, List<ByteArray>>,
            body: ByteArray,
        ) {
            this.method = requirePresent(method, "method")
            this.pathWithQuery = requirePresent(pathWithQuery, "pathWithQuery")
            this.scheme = requirePresent(scheme, "scheme")
            this.authority = requirePresent(authority, "authority")
            this.headers = cloneHeaders(requirePresent(headers, "headers"))
            this.body = requirePresent(body, "body").copyOf()
        }

        fun method(): String {
            return method
        }

        fun getMethod(): String {
            return method
        }

        fun pathWithQuery(): String {
            return pathWithQuery
        }

        fun getPathWithQuery(): String {
            return pathWithQuery
        }

        fun scheme(): String {
            return scheme
        }

        fun getScheme(): String {
            return scheme
        }

        fun authority(): String {
            return authority
        }

        fun getAuthority(): String {
            return authority
        }

        fun headers(): Map<String, List<ByteArray>> {
            return cloneHeaders(headers)
        }

        fun getHeaders(): Map<String, List<ByteArray>> {
            return headers()
        }

        fun body(): ByteArray {
            return body.copyOf()
        }

        fun getBody(): ByteArray {
            return body()
        }

        private fun cloneHeaders(
            source: Map<String, List<ByteArray>>
        ): Map<String, List<ByteArray>> {
            var result = LinkedHashMap<String, List<ByteArray>>()
            for (entry in source.entries) {
                var values = ArrayList<ByteArray>()
                for (value in entry.value) {
                    values.add(value.copyOf())
                }
                result.put(entry.key, values.toList())
            }
            return result.toMap()
        }
    }

    class HttpResponseSnapshot {
        private val statusCode: Int
        private val headers: Map<String, List<ByteArray>>
        private val body: ByteArray
        private val bodyFinished: Boolean

        constructor(
            statusCode: Int,
            headers: Map<String, List<ByteArray>>,
            body: ByteArray,
            bodyFinished: Boolean,
        ) {
            this.statusCode = statusCode
            this.headers = cloneHeaders(headers)
            this.body = body.copyOf()
            this.bodyFinished = bodyFinished
        }

        fun statusCode(): Int {
            return statusCode
        }

        fun getStatusCode(): Int {
            return statusCode
        }

        fun headers(): Map<String, List<ByteArray>> {
            return cloneHeaders(headers)
        }

        fun getHeaders(): Map<String, List<ByteArray>> {
            return headers()
        }

        fun body(): ByteArray {
            return body.copyOf()
        }

        fun getBody(): ByteArray {
            return body()
        }

        fun bodyFinished(): Boolean {
            return bodyFinished
        }

        fun isBodyFinished(): Boolean {
            return bodyFinished
        }

        private fun cloneHeaders(
            source: Map<String, List<ByteArray>>
        ): Map<String, List<ByteArray>> {
            var result = LinkedHashMap<String, List<ByteArray>>()
            for (entry in source.entries) {
                var values = ArrayList<ByteArray>()
                for (value in entry.value) {
                    values.add(value.copyOf())
                }
                result.put(entry.key, values.toList())
            }
            return result.toMap()
        }
    }

    class ExitException(private val statusCode: Int) :
        RuntimeException("WASI Preview 2 exit with status code " + statusCode) {

        fun statusCode(): Int {
            return statusCode
        }

        fun getStatusCode(): Int {
            return statusCode
        }
    }

    class InputStreamTag {
        private constructor() {}
    }

    class OutputStreamTag {
        private constructor() {}
    }

    class ErrorTag {
        private constructor() {}
    }

    class PollableTag {
        private constructor() {}
    }

    class DescriptorTag {
        private constructor() {}
    }

    class DirectoryEntryStreamTag {
        private constructor() {}
    }

    class TerminalInputTag {
        private constructor() {}
    }

    class TerminalOutputTag {
        private constructor() {}
    }

    class NetworkTag {
        private constructor() {}
    }

    class ResolveAddressStreamTag {
        private constructor() {}
    }

    class TcpSocketTag {
        private constructor() {}
    }

    class UdpSocketTag {
        private constructor() {}
    }

    class IncomingDatagramStreamTag {
        private constructor() {}
    }

    class OutgoingDatagramStreamTag {
        private constructor() {}
    }

    class HttpFieldsTag {
        private constructor() {}
    }

    class IncomingRequestTag {
        private constructor() {}
    }

    class OutgoingRequestTag {
        private constructor() {}
    }

    class RequestOptionsTag {
        private constructor() {}
    }

    class ResponseOutparamTag {
        private constructor() {}
    }

    class IncomingResponseTag {
        private constructor() {}
    }

    class IncomingBodyTag {
        private constructor() {}
    }

    class FutureTrailersTag {
        private constructor() {}
    }

    class OutgoingResponseTag {
        private constructor() {}
    }

    class OutgoingBodyTag {
        private constructor() {}
    }

    class FutureIncomingResponseTag {
        private constructor() {}
    }

    private class Pollable {
        val readySupplier: () -> Boolean

        constructor(ready: () -> Boolean) {
            this.readySupplier = requirePresent(ready, "ready")
        }

        fun ready(): Boolean {
            return readySupplier()
        }
    }
}
