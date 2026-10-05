package uk.shusek.krwa.wasi

internal actual fun wasiMonotonicNanos(): Long = System.nanoTime()

internal actual fun wasiSleepNanos(nanos: Long): Boolean {
    if (nanos <= 0L) {
        return true
    }
    return try {
        Thread.sleep(nanos / 1_000_000L, (nanos % 1_000_000L).toInt())
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
