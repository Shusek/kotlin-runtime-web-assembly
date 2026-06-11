package uk.shusek.krwa.wasm

/**
 * Base exception for errors raised by the Wasm engine (parsing, validation, linking, instantiation,
 * or execution). Distinct from `uk.shusek.krwa.runtime.WasmException`, which represents Wasm-level
 * tagged exceptions from the exception-handling proposal.
 */
open class WasmEngineException : RuntimeException {
    constructor(msg: String) : super(msg)

    constructor(cause: Throwable) : super(cause)

    constructor(msg: String, cause: Throwable) : super(msg, cause)
}
