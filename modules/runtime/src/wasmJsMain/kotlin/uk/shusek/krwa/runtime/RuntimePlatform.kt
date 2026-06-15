package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.MemoryLimits

internal actual object RuntimePlatform {
    actual fun defaultMemoryFactory(): (MemoryLimits) -> Memory = RuntimeDefaults.defaultMemoryFactory()

    actual fun defaultMachineFactory(): (Instance) -> Machine = RuntimeDefaults.defaultMachineFactory()

    actual fun <T> runCatchingStackOverflow(block: () -> T): T = block()
}
