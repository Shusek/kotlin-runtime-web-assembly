package uk.shusek.krwa.wasi

/** WASI [whence](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#whence) */
class WasiWhence private constructor() {
    companion object {
        const val SET = 0
        const val CUR = 1
        const val END = 2
    }
}
