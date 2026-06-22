package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

internal actual object RuntimePlatform {
    actual fun defaultMemoryFactory(): (MemoryLimits) -> Memory = { limits -> ByteArrayMemory(limits) }

    actual fun defaultMachineFactory(): (Instance) -> Machine =
        RuntimeDefaults.defaultMachineFactory { Thread.currentThread().isInterrupted }

    actual fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
    ): PlatformInstanceExecution? =
        when (backend) {
            ExecutionBackend.INTERPRETER -> null
            ExecutionBackend.AUTO ->
                if (ChasmPlatformInstanceExecution.canCreate(module, imports)) {
                    ChasmPlatformInstanceExecution.create(module, imports, hostInstance)
                } else {
                    null
                }
            ExecutionBackend.NATIVE ->
                throw WasmEngineException("native WebAssembly execution is only available on wasmJs")
            ExecutionBackend.CHASM ->
                ChasmPlatformInstanceExecution.create(module, imports, hostInstance)
        }

    actual fun usesPeriodicInterruptionPolling(): Boolean = false

    actual fun <T> runCatchingStackOverflow(block: () -> T): T =
        try {
            block()
        } catch (e: StackOverflowError) {
            throw WasmEngineException("call stack exhausted", e)
        }
}
