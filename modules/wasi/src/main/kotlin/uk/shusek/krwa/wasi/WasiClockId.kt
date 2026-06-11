package uk.shusek.krwa.wasi

/**
 * WASI [clockid](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#clockid)
 */
class WasiClockId private constructor() {
    companion object {
        const val REALTIME = 0
        const val MONOTONIC = 1
        const val PROCESS_CPUTIME_ID = 2
        const val THREAD_CPUTIME_ID = 3
    }
}
