package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

internal expect object RuntimePlatform {
    fun defaultMemoryFactory(): (MemoryLimits) -> Memory

    fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
        definedMemoryLimits: Array<MemoryLimits>?,
    ): PlatformInstanceExecution?

    fun executionBackendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability

    fun usesPeriodicInterruptionPolling(): Boolean

    fun <T> runCatchingStackOverflow(block: () -> T): T
}
