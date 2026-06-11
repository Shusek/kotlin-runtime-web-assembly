package uk.shusek.krwa.wasi

/**
 * WASI
 * [subclockflags](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#subclockflags)
 * flags
 */
class WasiSubClockFlags private constructor() {
    companion object {
        const val SUBSCRIPTION_CLOCK_ABSTIME = 1
    }
}
