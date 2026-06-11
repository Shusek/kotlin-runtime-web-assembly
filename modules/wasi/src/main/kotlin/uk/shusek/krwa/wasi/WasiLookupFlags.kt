package uk.shusek.krwa.wasi

/**
 * WASI
 * [lookupflags](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#lookupflags)
 * flags
 */
class WasiLookupFlags private constructor() {
    companion object {
        const val SYMLINK_FOLLOW = 1
    }
}
