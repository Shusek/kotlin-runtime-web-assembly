package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.MemoryLimits

internal object RuntimeDefaults {
    fun defaultMemoryFactory(): (MemoryLimits) -> Memory = { limits -> PortableMemory(limits) }

    fun defaultMachineFactory(): (Instance) -> Machine =
        {
            Machine { _, _ ->
                throw WasmEngineException("Kotlin interpreter execution has been removed; use platform WebAssembly execution")
            }
        }
}
