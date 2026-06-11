package uk.shusek.krwa.wasm

/** Wasm spec: module cannot be linked (import/export type mismatches). */
open class UnlinkableException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
