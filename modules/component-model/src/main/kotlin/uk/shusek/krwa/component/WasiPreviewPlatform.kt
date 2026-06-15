@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import kotlin.time.Instant
import kotlin.time.Duration
import kotlinx.datetime.TimeZone
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem

internal expect fun defaultWasiStdin(): RawSource

internal expect fun defaultWasiStdinAvailable(): () -> Int

internal expect fun defaultWasiStdout(): RawSink

internal expect fun defaultWasiStderr(): RawSink

internal expect fun defaultWasiFileSystem(): FileSystem

internal expect fun isWasiDaylightSavingTime(timeZone: TimeZone, instant: Instant): Boolean

internal expect fun defaultWasiEnvironment(): Map<String, String>

internal expect fun isWasiInterrupted(throwable: Throwable): Boolean

internal expect fun restoreWasiInterruptStatus()

internal expect fun isWasiSecurityException(throwable: Throwable): Boolean

internal expect fun wasiDelay(duration: Duration)
