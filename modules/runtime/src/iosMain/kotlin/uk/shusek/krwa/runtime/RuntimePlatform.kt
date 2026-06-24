package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

internal actual object RuntimePlatform {
    actual fun defaultMemoryFactory(): (MemoryLimits) -> Memory = RuntimeDefaults.defaultMemoryFactory()

    actual fun defaultMachineFactory(): (Instance) -> Machine = RuntimeDefaults.defaultMachineFactory()

    actual fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
        memoryLimits: MemoryLimits?,
    ): PlatformInstanceExecution? =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.INTERPRETER -> null
            ExecutionBackend.NATIVE ->
                throw WasmEngineException("native WebAssembly execution is only available on wasmJs")
            ExecutionBackend.PULLEY -> PulleyExecution.create(module, imports, hostInstance)
        }

    actual fun executionBackendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.INTERPRETER -> ExecutionBackendAvailability(available = true)
            ExecutionBackend.NATIVE ->
                ExecutionBackendAvailability(
                    available = false,
                    reason = "native WebAssembly execution is only available on wasmJs",
                )
            ExecutionBackend.PULLEY -> PulleyExecution.availability()
        }

    actual fun usesPeriodicInterruptionPolling(): Boolean = true

    actual fun <T> runCatchingStackOverflow(block: () -> T): T = block()
}
