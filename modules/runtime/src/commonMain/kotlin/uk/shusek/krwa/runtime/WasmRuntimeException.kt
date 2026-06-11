package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

/** Engine-specific runtime error in compiled code paths (e.g., OOB memory access). */
open class WasmRuntimeException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
