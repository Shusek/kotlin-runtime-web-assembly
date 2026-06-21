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
    ): PlatformInstanceExecution? =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.INTERPRETER -> null
            ExecutionBackend.NATIVE ->
                throw WasmEngineException("native WebAssembly execution is only available on wasmJs")
            ExecutionBackend.CHASM ->
                throw WasmEngineException("Chasm execution is only available on JVM")
        }

    actual fun usesPeriodicInterruptionPolling(): Boolean = true

    actual fun <T> runCatchingStackOverflow(block: () -> T): T = block()
}
