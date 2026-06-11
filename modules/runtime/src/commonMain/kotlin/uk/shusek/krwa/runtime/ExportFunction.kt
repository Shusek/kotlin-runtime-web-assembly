package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

/** This represents an Exported function from the Wasm module. */
fun interface ExportFunction {
    @Throws(WasmEngineException::class) fun apply(vararg args: Long): LongArray
}
