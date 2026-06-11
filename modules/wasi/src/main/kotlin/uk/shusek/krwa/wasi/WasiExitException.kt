package uk.shusek.krwa.wasi

import uk.shusek.krwa.wasm.WasmEngineException

/** WASI proc_exit with a specific exit code. */
class WasiExitException(private val exitCode: Int) :
    WasmEngineException("Process exit code: $exitCode") {
    fun exitCode(): Int = exitCode
}
