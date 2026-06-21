package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.FunctionType

enum class ExecutionBackend {
    AUTO,
    INTERPRETER,
    NATIVE,
    CHASM,
}

internal interface PlatformInstanceExecution {
    val backend: ExecutionBackend

    fun export(name: String): ExportFunction

    fun exportType(name: String): FunctionType

    fun memory(name: String): Memory

    fun memory(index: Int): Memory?
}
