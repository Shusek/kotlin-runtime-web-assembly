package uk.shusek.krwa.wasi

/**
 * WASI [fstflags](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#fstflags)
 */
class WasiFstFlags private constructor() {
    companion object {
        const val ATIM = 1
        const val ATIM_NOW = 2
        const val MTIM = 4
        const val MTIM_NOW = 8
    }
}
