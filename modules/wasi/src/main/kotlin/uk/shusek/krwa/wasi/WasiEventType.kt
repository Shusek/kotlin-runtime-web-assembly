package uk.shusek.krwa.wasi

/**
 * WASI
 * [eventtype](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#eventtype)
 */
class WasiEventType private constructor() {
    companion object {
        const val CLOCK: Byte = 0
        const val FD_READ: Byte = 1
        const val FD_WRITE: Byte = 2
    }
}
