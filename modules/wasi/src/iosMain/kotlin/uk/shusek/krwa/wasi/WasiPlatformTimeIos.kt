@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.shusek.krwa.wasi

import kotlin.time.TimeSource
import platform.posix.usleep

private val wasiTimeStart = TimeSource.Monotonic.markNow()

internal actual fun wasiMonotonicNanos(): Long = wasiTimeStart.elapsedNow().inWholeNanoseconds

internal actual fun wasiSleepNanos(nanos: Long): Boolean {
    var remainingMicros = nanos / 1_000L
    while (remainingMicros > 0L) {
        // usleep is only guaranteed for intervals below one second; sleep in bounded slices.
        val slice = minOf(remainingMicros, 500_000L)
        usleep(slice.toUInt())
        remainingMicros -= slice
    }
    return true
}
