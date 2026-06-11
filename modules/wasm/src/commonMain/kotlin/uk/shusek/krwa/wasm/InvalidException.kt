package uk.shusek.krwa.wasm

/** Wasm spec: module fails validation (type errors, constraint violations). */
open class InvalidException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
