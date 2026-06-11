package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

/** Thrown when the host interrupts a running Wasm execution. */
open class WasmInterruptedException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
