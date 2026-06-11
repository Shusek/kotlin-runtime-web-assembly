package uk.shusek.krwa.wasm

/** Wasm spec: binary format is malformed (parsing error). */
open class MalformedException : WasmEngineException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
