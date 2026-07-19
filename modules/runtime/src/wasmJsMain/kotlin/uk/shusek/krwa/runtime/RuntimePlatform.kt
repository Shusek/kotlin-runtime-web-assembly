package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits

internal actual object RuntimePlatform {
    actual fun defaultMemoryFactory(): (MemoryLimits) -> Memory = RuntimeDefaults.defaultMemoryFactory()

    actual fun createPlatformExecution(
        module: WasmModule,
        imports: ImportValues,
        backend: ExecutionBackend,
        hostInstance: Instance,
        definedMemoryLimits: Array<MemoryLimits>?,
    ): PlatformInstanceExecution? {
        if (backend == ExecutionBackend.PULLEY) {
            return PulleyExecution.create(module, imports, hostInstance)
        }
        if (!NativeWasmFeatures.available()) {
            throw WasmEngineException("native WebAssembly execution is not available in this wasmJs host")
        }

        return NativePlatformInstanceExecution(
            module,
            NativeWasmInstance.instantiate(
                module,
                NativeWasmImports.fromImportValues(imports, hostInstance),
                definedMemoryLimits,
            ),
        )
    }

    actual fun executionBackendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability =
        when (backend) {
            ExecutionBackend.AUTO,
            ExecutionBackend.NATIVE ->
                if (NativeWasmFeatures.available()) {
                    ExecutionBackendAvailability(available = true)
                } else {
                    ExecutionBackendAvailability(
                        available = false,
                        reason = "native WebAssembly execution is not available in this wasmJs host",
                    )
                }
            ExecutionBackend.PULLEY -> PulleyExecution.availability()
        }

    actual fun usesPeriodicInterruptionPolling(): Boolean = true

    actual fun <T> runCatchingStackOverflow(block: () -> T): T = block()
}

private class NativePlatformInstanceExecution(
    private val module: WasmModule,
    private val native: NativeWasmInstance,
) : PlatformInstanceExecution {
    override val backend: ExecutionBackend = ExecutionBackend.NATIVE

    override fun export(name: String): ExportFunction =
        ExportFunction { args -> native.export(name).apply(*args) }

    override fun exportType(name: String): FunctionType = native.exportType(name)

    override fun memory(name: String): Memory = native.memory(name)

    override fun memory(index: Int): Memory? {
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            if (
                export.exportType() == ExternalType.MEMORY &&
                    export.index() == index
            ) {
                return native.memory(export.name())
            }
        }
        return null
    }
}
