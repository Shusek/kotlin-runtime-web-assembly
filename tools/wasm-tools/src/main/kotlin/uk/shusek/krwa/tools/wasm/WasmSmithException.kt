package uk.shusek.krwa.tools.wasm

open class WasmSmithException : RuntimeException {
    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
