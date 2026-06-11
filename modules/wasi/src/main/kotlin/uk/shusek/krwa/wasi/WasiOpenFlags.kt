package uk.shusek.krwa.wasi

/** WASI [oflags](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#oflags) */
class WasiOpenFlags private constructor() {
    companion object {
        const val CREAT = 1
        const val DIRECTORY = 2
        const val EXCL = 4
        const val TRUNC = 8
    }
}
