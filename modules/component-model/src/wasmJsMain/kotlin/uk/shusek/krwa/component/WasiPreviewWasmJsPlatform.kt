@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.network.sockets.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem

private const val DST_PROBE_SECONDS: Long = 183L * 24L * 60L * 60L

internal actual fun defaultWasiHttpClient(): WasiHttpClient = UnsupportedWasiHttpClient

internal actual fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient =
    UnsupportedWasiHttpClient

internal actual fun defaultWasiStdin(): RawSource = NullSource()

internal actual fun defaultWasiStdinAvailable(): () -> Int = { 0 }

internal actual fun defaultWasiStdout(): RawSink = BlackholeSink()

internal actual fun defaultWasiStderr(): RawSink = BlackholeSink()

internal actual fun defaultWasiFileSystem(): FileSystem = UnsupportedWasmFileSystem

internal actual fun defaultWasiSocketRuntime(): WasiSocketRuntime = UnsupportedWasiSocketRuntime

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

internal actual fun wasiDelay(duration: Duration) {
    throw UnsupportedOperationException("Blocking WASI delay is not available on web/wasm")
}

private object UnsupportedWasiHttpClient : WasiHttpClient {
    override fun send(request: WasiHttpRequest): WasiHttpResponse =
        throw UnsupportedOperationException("Default WASI HTTP is not available on web/wasm")
}

private class NullSource : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L

    override fun close() {
    }
}

private object UnsupportedWasiSocketRuntime : WasiSocketRuntime {
    override fun connectTcp(
        remoteAddress: InetSocketAddress,
        keepAlive: Boolean,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiTcpConnection = unsupportedSockets()

    override fun listenTcp(localAddress: InetSocketAddress, backlogSize: Int): WasiTcpListener =
        unsupportedSockets()

    override fun bindUdp(
        localAddress: InetSocketAddress,
        receiveBufferSize: Int,
        sendBufferSize: Int,
    ): WasiUdpEndpoint = unsupportedSockets()

    private fun unsupportedSockets(): Nothing =
        throw UnsupportedOperationException("WASI sockets are not available on web/wasm")
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
