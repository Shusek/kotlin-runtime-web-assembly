package uk.shusek.krwa.wasi

/**
 * WASI [fdflags](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#fdflags)
 */
class WasiFdFlags private constructor() {
    companion object {
        const val APPEND = 1
        const val DSYNC = 2
        const val NONBLOCK = 4
        const val RSYNC = 8
        const val SYNC = 16
    }
}
