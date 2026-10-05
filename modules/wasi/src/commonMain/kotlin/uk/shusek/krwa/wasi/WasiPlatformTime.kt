package uk.shusek.krwa.wasi

internal expect fun wasiMonotonicNanos(): Long

/**
 * Blocks the calling thread for up to [nanos] nanoseconds while a guest waits in `poll_oneoff`.
 * Returns `false` when the wait was interrupted, in which case the caller reports `EINTR`.
 * Platforms without a blocking sleep (the browser main thread) return immediately.
 */
internal expect fun wasiSleepNanos(nanos: Long): Boolean
