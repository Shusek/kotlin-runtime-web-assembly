package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

/** Signals successful completion of execution (used by WASI proc_exit with code 0). */
open class ExecutionCompletedException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
