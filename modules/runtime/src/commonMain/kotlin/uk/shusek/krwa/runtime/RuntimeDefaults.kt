package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.MemoryLimits

internal object RuntimeDefaults {
    fun defaultMemoryFactory(): (MemoryLimits) -> Memory = { limits -> PortableMemory(limits) }

    fun defaultMachineFactory(isInterrupted: () -> Boolean = { false }): (Instance) -> Machine =
        { instance ->
            object : InterpreterMachine(instance) {
                override fun isInterrupted(): Boolean = isInterrupted()
            }
        }
}
