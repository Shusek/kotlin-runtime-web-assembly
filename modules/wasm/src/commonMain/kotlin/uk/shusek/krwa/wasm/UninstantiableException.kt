package uk.shusek.krwa.wasm

/** Wasm spec: module cannot be instantiated (trap during initialization). */
open class UninstantiableException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
