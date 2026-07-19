@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path as JavaPath
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import okio.FileSystem
import okio.Path

internal actual fun defaultWasiHttpClient(): WasiHttpClient {
    val client =
        KtorHttpClient(CIO) {
            install(HttpTimeout)
            followRedirects = false
        }
    return ownedWasiHttpClient(KtorWasiHttpClient(client), client::close)
}

internal actual fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient =
    KtorWasiHttpClient(httpClient)

internal actual fun defaultWasiStdin(): RawSource = java.lang.System.`in`.asSource()

internal actual fun defaultWasiStdinAvailable(): () -> Int = { java.lang.System.`in`.available() }

internal actual fun defaultWasiStdout(): RawSink = java.lang.System.out.asSink()

internal actual fun defaultWasiStderr(): RawSink = java.lang.System.err.asSink()

internal actual fun defaultWasiFileSystem(): FileSystem = FileSystem.SYSTEM

internal actual fun defaultWasiSocketRuntime(): WasiSocketRuntime = KtorSocketRuntime()

internal actual fun webSocketUdpProxySocketRuntime(proxyUrl: String): WasiSocketRuntime =
    throw UnsupportedOperationException("WASI UDP WebSocket proxy is only available on wasmJs")

internal actual fun isWasiDaylightSavingTime(timeZone: TimeZone, instant: Instant): Boolean =
    timeZone.toJavaZoneId().rules.isDaylightSavings(instant.toJavaInstant())

internal actual fun defaultWasiEnvironment(): Map<String, String> =
    java.lang.System.getenv()

internal actual fun isWasiInterrupted(throwable: Throwable): Boolean =
    throwable is InterruptedException

internal actual fun restoreWasiInterruptStatus() {
    Thread.currentThread().interrupt()
}

internal actual fun isWasiSecurityException(throwable: Throwable): Boolean =
    throwable is SecurityException

internal actual fun wasiCreateHardLink(fileSystem: FileSystem, oldPath: Path, newPath: Path) {
    Files.createLink(wasiJavaPath(fileSystem, newPath), wasiJavaPath(fileSystem, oldPath))
}

internal actual fun wasiFileIdentity(fileSystem: FileSystem, path: Path): Any? =
    Files.readAttributes(
            wasiJavaPath(fileSystem, path),
            BasicFileAttributes::class.java,
        )
        .fileKey()

internal actual fun wasiIsSameFile(fileSystem: FileSystem, first: Path, second: Path): Boolean =
    Files.isSameFile(wasiJavaPath(fileSystem, first), wasiJavaPath(fileSystem, second))

internal actual fun wasiSetFileTimes(
    fileSystem: FileSystem,
    path: Path,
    accessTimestamp: Instant?,
    modificationTimestamp: Instant?,
    followSymlinks: Boolean,
) {
    val options =
        if (followSymlinks) {
            emptyArray<LinkOption>()
        } else {
            arrayOf(LinkOption.NOFOLLOW_LINKS)
        }
    Files.getFileAttributeView(
        wasiJavaPath(fileSystem, path),
        java.nio.file.attribute.BasicFileAttributeView::class.java,
        *options,
    ).setTimes(
        modificationTimestamp?.toJavaInstant()?.let(java.nio.file.attribute.FileTime::from),
        accessTimestamp?.toJavaInstant()?.let(java.nio.file.attribute.FileTime::from),
        null,
    )
}

private fun wasiJavaPath(fileSystem: FileSystem, path: Path): JavaPath {
    if (fileSystem != FileSystem.SYSTEM) {
        throw UnsupportedOperationException("WASI filesystem operation requires a JVM FileSystem")
    }
    return JavaPath.of(path.toString())
}

internal actual fun wasiDelay(duration: Duration) {
    runBlocking { delay(duration) }
}

internal actual class WasiPreviewLock {
    @PublishedApi internal val monitor: Any = Any()
}

internal actual inline fun <T> withWasiPreviewLock(
    lock: WasiPreviewLock,
    block: () -> T,
): T = synchronized(lock.monitor) { block() }

internal actual fun <T> wasiRunBlockingOrNull(block: suspend () -> T): T? =
    runBlocking { block() }

/**
 * Uses a caller-owned Ktor HTTP client. Closing the built [WasiPreview2] does not close
 * [httpClient].
 */
public fun WasiPreview2.Builder.withKtorHttpClient(httpClient: KtorHttpClient): WasiPreview2.Builder =
    withHttpClient(KtorWasiHttpClient(httpClient))

/**
 * Uses a caller-owned Ktor HTTP client. Closing the built [WasiPreview3] does not close
 * [httpClient].
 */
public fun WasiPreview3.Builder.withKtorHttpClient(httpClient: KtorHttpClient): WasiPreview3.Builder =
    withHttpClient(KtorWasiHttpClient(httpClient))

public fun WasiPreview2.Builder.withSecureRandom(secureRandom: java.util.Random): WasiPreview2.Builder =
    withSecureRandom(JavaRandomCryptoRand(requireNotNull(secureRandom) { "secureRandom" }))

public fun WasiPreview2.Builder.withInsecureRandom(insecureRandom: java.util.Random): WasiPreview2.Builder =
    withInsecureRandom(requireNotNull(insecureRandom) { "insecureRandom" }.asKotlinRandom())

public fun WasiPreview3.Builder.withSecureRandom(secureRandom: java.util.Random): WasiPreview3.Builder =
    withSecureRandom(JavaRandomCryptoRand(requireNotNull(secureRandom) { "secureRandom" }))

public fun WasiPreview3.Builder.withInsecureRandom(insecureRandom: java.util.Random): WasiPreview3.Builder =
    withInsecureRandom(requireNotNull(insecureRandom) { "insecureRandom" }.asKotlinRandom())
