package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

internal expect object RuntimePlatform {
    fun defaultMemoryFactory(): (MemoryLimits) -> Memory

    fun defaultMachineFactory(): (Instance) -> Machine

    fun compareByteArraysUnsigned(
        left: ByteArray,
        leftOffset: Int,
        right: ByteArray,
        rightOffset: Int,
        length: Int,
    ): Int

    fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
    ): PlatformInstanceExecution?

    fun usesPeriodicInterruptionPolling(): Boolean

    fun <T> runCatchingStackOverflow(block: () -> T): T
}
