@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.errno
import platform.posix.link
import platform.posix.lstat
import platform.posix.lutimes
import platform.posix.stat
import platform.posix.strerror
import platform.posix.timeval
import platform.posix.utimes

private const val DST_PROBE_SECONDS: Long = 183L * 24L * 60L * 60L

internal actual fun defaultWasiHttpClient(): WasiHttpClient =
    KtorWasiHttpClient(
        KtorHttpClient(Darwin) {
            install(HttpTimeout)
            followRedirects = true
        }
    )

internal actual fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient =
    KtorWasiHttpClient(httpClient)

internal actual fun defaultWasiStdin(): RawSource = NullSource()

internal actual fun defaultWasiStdinAvailable(): () -> Int = { 0 }

internal actual fun defaultWasiStdout(): RawSink = BlackholeSink()

internal actual fun defaultWasiStderr(): RawSink = BlackholeSink()

internal actual fun defaultWasiFileSystem(): FileSystem = FileSystem.SYSTEM

internal actual fun defaultWasiSocketRuntime(): WasiSocketRuntime = KtorSocketRuntime()

internal actual fun webSocketUdpProxySocketRuntime(proxyUrl: String): WasiSocketRuntime =
    throw UnsupportedOperationException("WASI UDP WebSocket proxy is only available on wasmJs")

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
    val result = link(wasiPosixPath(fileSystem, oldPath), wasiPosixPath(fileSystem, newPath))
    if (result != 0) {
        throw wasiPosixIOException("link", oldPath, newPath)
    }
}

internal actual fun wasiFileIdentity(fileSystem: FileSystem, path: Path): Any? =
    memScoped {
        val pathStat = alloc<stat>()
        if (stat(wasiPosixPath(fileSystem, path), pathStat.ptr) != 0) {
            throw wasiPosixIOException("stat", path)
        }
        "${pathStat.st_dev}:${pathStat.st_ino}"
    }

internal actual fun wasiIsSameFile(fileSystem: FileSystem, first: Path, second: Path): Boolean =
    memScoped {
        val firstStat = alloc<stat>()
        val secondStat = alloc<stat>()
        if (stat(wasiPosixPath(fileSystem, first), firstStat.ptr) != 0) {
            throw wasiPosixIOException("stat", first)
        }
        if (stat(wasiPosixPath(fileSystem, second), secondStat.ptr) != 0) {
            throw wasiPosixIOException("stat", second)
        }
        firstStat.st_dev == secondStat.st_dev && firstStat.st_ino == secondStat.st_ino
    }

internal actual fun wasiSetFileTimes(
    fileSystem: FileSystem,
    path: Path,
    accessTimestamp: Instant?,
    modificationTimestamp: Instant?,
    followSymlinks: Boolean,
) {
    val posixPath = wasiPosixPath(fileSystem, path)
    memScoped {
        val current = alloc<stat>()
        val statResult =
            if (followSymlinks) {
                stat(posixPath, current.ptr)
            } else {
                lstat(posixPath, current.ptr)
            }
        if (statResult != 0) {
            throw wasiPosixIOException(if (followSymlinks) "stat" else "lstat", path)
        }

        val times = allocArray<timeval>(2)
        fillTimeval(
            times[0],
            accessTimestamp,
            current.st_atimespec.tv_sec,
            current.st_atimespec.tv_nsec,
        )
        fillTimeval(
            times[1],
            modificationTimestamp,
            current.st_mtimespec.tv_sec,
            current.st_mtimespec.tv_nsec,
        )
        val result =
            if (followSymlinks) {
                utimes(posixPath, times)
            } else {
                lutimes(posixPath, times)
            }
        if (result != 0) {
            throw wasiPosixIOException(if (followSymlinks) "utimes" else "lutimes", path)
        }
    }
}

private fun wasiPosixPath(fileSystem: FileSystem, path: Path): String {
    if (fileSystem != FileSystem.SYSTEM) {
        throw UnsupportedOperationException("WASI filesystem operation requires an iOS FileSystem")
    }
    return path.toString()
}

private fun fillTimeval(
    target: timeval,
    timestamp: Instant?,
    fallbackSeconds: Long,
    fallbackNanoseconds: Long,
) {
    val seconds = timestamp?.epochSeconds ?: fallbackSeconds
    val nanoseconds = timestamp?.nanosecondsOfSecond?.toLong() ?: fallbackNanoseconds
    target.tv_sec = seconds.convert()
    target.tv_usec = (nanoseconds / 1_000L).convert()
}

private fun wasiPosixIOException(operation: String, path: Path, other: Path? = null): IOException {
    val message = strerror(errno)?.toKString() ?: "errno $errno"
    val target = if (other == null) path.toString() else "$path -> $other"
    return IOException("$operation failed for $target: $message")
}

internal actual fun wasiDelay(duration: Duration) {
    runBlocking { delay(duration) }
}

internal actual class WasiPreviewLock

internal actual inline fun <T> withWasiPreviewLock(
    lock: WasiPreviewLock,
    block: () -> T,
): T = block()

internal actual fun <T> wasiRunBlockingOrNull(block: suspend () -> T): T? =
    runBlocking { block() }

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
