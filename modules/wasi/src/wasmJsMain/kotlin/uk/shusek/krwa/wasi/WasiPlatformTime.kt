package uk.shusek.krwa.wasi

import kotlin.time.TimeSource

private val wasiTimeStart = TimeSource.Monotonic.markNow()

internal actual fun wasiMonotonicNanos(): Long = wasiTimeStart.elapsedNow().inWholeNanoseconds

/**
 * The browser and Node main threads have no blocking sleep, so `poll_oneoff` keeps polling until
 * its deadline there. Hosts that run untrusted guests on wasmJs should use a dedicated worker.
 */
internal actual fun wasiSleepNanos(nanos: Long): Boolean = true
