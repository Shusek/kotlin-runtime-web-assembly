@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod as KtorHttpMethod
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem
import okio.Path

private const val DST_PROBE_SECONDS: Long = 183L * 24L * 60L * 60L

internal actual fun defaultWasiHttpClient(): WasiHttpClient =
    WasmJsKtorWasiHttpClient(
        KtorHttpClient(Js) {
            install(HttpTimeout)
            followRedirects = true
        }
    )

internal actual fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient =
    WasmJsKtorWasiHttpClient(httpClient)

internal actual fun defaultWasiStdin(): RawSource = NullSource()

internal actual fun defaultWasiStdinAvailable(): () -> Int = { 0 }

internal actual fun defaultWasiStdout(): RawSink = BlackholeSink()

internal actual fun defaultWasiStderr(): RawSink = BlackholeSink()

internal actual fun defaultWasiFileSystem(): FileSystem = MemoryWasmFileSystem()

internal actual fun defaultWasiSocketRuntime(): WasiSocketRuntime = WasmJsKtorSocketRuntime()

internal actual fun webSocketUdpProxySocketRuntime(proxyUrl: String): WasiSocketRuntime =
    WasmJsKtorSocketRuntime(proxyUrl)

internal actual fun isWasiDaylightSavingTime(timeZone: TimeZone, instant: Instant): Boolean {
    if (timeZone is FixedOffsetTimeZone) {
        return false
    }
    val offset = timeZone.offsetAt(instant).totalSeconds
    val standardOffset =
        listOfNotNull(
            offsetAtOrNull(timeZone, instant.epochSeconds - DST_PROBE_SECONDS, instant.nanosecondsOfSecond.toLong()),
            offset,
            offsetAtOrNull(timeZone, instant.epochSeconds + DST_PROBE_SECONDS, instant.nanosecondsOfSecond.toLong()),
        ).minOrNull() ?: offset
    return offset > standardOffset
}

private fun offsetAtOrNull(timeZone: TimeZone, epochSeconds: Long, nanoseconds: Long): Int? =
    try {
        timeZone.offsetAt(Instant.fromEpochSeconds(epochSeconds, nanoseconds)).totalSeconds
    } catch (_: IllegalArgumentException) {
        null
    }

internal actual fun defaultWasiEnvironment(): Map<String, String> = emptyMap()

internal actual fun isWasiInterrupted(throwable: Throwable): Boolean = false

internal actual fun restoreWasiInterruptStatus() {
}

internal actual fun isWasiSecurityException(throwable: Throwable): Boolean = false

internal actual fun wasiCreateHardLink(fileSystem: FileSystem, oldPath: Path, newPath: Path) {
    if (fileSystem is MemoryWasmFileSystem) {
        fileSystem.createHardLink(oldPath, newPath)
    } else {
        throw UnsupportedOperationException("WASI hard links are not available for this web/wasm filesystem")
    }
}

internal actual fun wasiFileIdentity(fileSystem: FileSystem, path: Path): Any? =
    if (fileSystem is MemoryWasmFileSystem) {
        fileSystem.fileIdentity(path)
    } else {
        null
    }

internal actual fun wasiIsSameFile(fileSystem: FileSystem, first: Path, second: Path): Boolean =
    if (fileSystem is MemoryWasmFileSystem) {
        fileSystem.isSameFile(first, second)
    } else {
        fileSystem.canonicalize(first) == fileSystem.canonicalize(second)
    }

internal actual fun wasiSetFileTimes(
    fileSystem: FileSystem,
    path: Path,
    accessTimestamp: Instant?,
    modificationTimestamp: Instant?,
    followSymlinks: Boolean,
) {
    if (fileSystem is MemoryWasmFileSystem) {
        fileSystem.setTimes(
            path,
            accessTimestamp?.toEpochMilliseconds(),
            modificationTimestamp?.toEpochMilliseconds(),
            followSymlinks,
        )
    } else {
        throw UnsupportedOperationException("WASI file timestamp updates are not available for this web/wasm filesystem")
    }
}

internal actual fun wasiDelay(duration: Duration) {
    throw UnsupportedOperationException("Blocking WASI delay is not available on web/wasm")
}

internal actual class WasiPreviewLock

internal actual inline fun <T> withWasiPreviewLock(
    lock: WasiPreviewLock,
    block: () -> T,
): T = block()

internal actual fun <T> wasiRunBlockingOrNull(block: suspend () -> T): T? = null

private class WasmJsKtorWasiHttpClient(private val delegate: KtorHttpClient) : WasiSuspendingHttpClient {
    override fun send(request: WasiHttpRequest): WasiHttpResponse =
        throw UnsupportedOperationException("Synchronous WASI HTTP is not available on web/wasm")

    override suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse {
        val response =
            delegate.request(request.uri) {
                method = KtorHttpMethod(request.method)
                val timeout = request.timeout
                if (timeout != null) {
                    timeout {
                        val millis = maxOf(1L, timeout.inWholeMilliseconds)
                        requestTimeoutMillis = millis
                        connectTimeoutMillis = millis
                        socketTimeoutMillis = millis
                    }
                }
                headers {
                    for (entry in request.headers) {
                        append(entry.name, entry.value)
                    }
                }
                setBody(request.body)
            }
        return WasiHttpResponse(
            response.status.value,
            response.headers.entries().associate { entry -> entry.key to entry.value.toList() },
            response.bodyAsBytes(),
        )
    }
}

private class NullSource : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L

    override fun close() {
    }
}

private class BlackholeSink : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        source.skip(byteCount)
    }

    override fun flush() {
    }

    override fun close() {
    }
}
