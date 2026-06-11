package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule

/** This interface is implemented by build time compiled wasm modules. */
interface CompiledModule {
    fun wasmModule(): WasmModule

    fun machineFactory(): (Instance) -> Machine
}
