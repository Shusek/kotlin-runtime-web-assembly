package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

internal actual object RuntimePlatform {
    actual fun defaultMemoryFactory(): (MemoryLimits) -> Memory = { limits -> ByteBufferMemory(limits) }

    actual fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
        definedMemoryLimits: Array<MemoryLimits>?,
    ): PlatformInstanceExecution? =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.PULLEY -> PulleyExecution.create(module, imports, hostInstance)
            ExecutionBackend.NATIVE ->
                throw WasmEngineException("native WebAssembly execution is only available on wasmJs")
        }

    actual fun executionBackendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.PULLEY -> PulleyExecution.availability()
            ExecutionBackend.NATIVE ->
                ExecutionBackendAvailability(
                    available = false,
                    reason = "native WebAssembly execution is only available on wasmJs",
                )
        }

    actual fun usesPeriodicInterruptionPolling(): Boolean = false

    actual fun <T> runCatchingStackOverflow(block: () -> T): T =
        try {
            block()
        } catch (e: StackOverflowError) {
            throw WasmEngineException("call stack exhausted", e)
        }
}
