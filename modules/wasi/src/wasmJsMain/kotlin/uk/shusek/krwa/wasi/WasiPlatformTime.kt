package uk.shusek.krwa.wasi

import kotlin.time.TimeSource

private val wasiTimeStart = TimeSource.Monotonic.markNow()

internal actual fun wasiMonotonicNanos(): Long = wasiTimeStart.elapsedNow().inWholeNanoseconds
