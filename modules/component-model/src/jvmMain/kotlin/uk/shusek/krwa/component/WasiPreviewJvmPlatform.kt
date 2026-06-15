@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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

internal actual fun defaultWasiHttpClient(): WasiHttpClient =
    KtorWasiHttpClient(
        KtorHttpClient(CIO) {
            install(HttpTimeout)
            followRedirects = true
        }
    )

internal actual fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient =
    KtorWasiHttpClient(httpClient)

internal actual fun defaultWasiStdin(): RawSource = java.lang.System.`in`.asSource()

internal actual fun defaultWasiStdinAvailable(): () -> Int = { java.lang.System.`in`.available() }

internal actual fun defaultWasiStdout(): RawSink = java.lang.System.out.asSink()

internal actual fun defaultWasiStderr(): RawSink = java.lang.System.err.asSink()

internal actual fun defaultWasiFileSystem(): FileSystem = FileSystem.SYSTEM

internal actual fun defaultWasiSocketRuntime(): WasiSocketRuntime = KtorSocketRuntime()

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

internal actual fun wasiDelay(duration: Duration) {
    runBlocking { delay(duration) }
}

public fun WasiPreview2.Builder.withKtorHttpClient(httpClient: KtorHttpClient): WasiPreview2.Builder =
    withHttpClient(KtorWasiHttpClient(httpClient))

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
