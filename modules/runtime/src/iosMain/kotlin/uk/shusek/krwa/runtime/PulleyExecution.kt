package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule

internal actual object PulleyExecution {
    actual fun create(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution =
        PulleyExecutionProviders.installed()?.createCheckedPulleyExecution(module, imports, hostInstance)
            ?: throw WasmEngineException(UnavailableReason)

    actual fun availability(): ExecutionBackendAvailability =
        PulleyExecutionProviders.installed()?.availability()
            ?: ExecutionBackendAvailability(available = false, reason = UnavailableReason)

    private const val UnavailableReason = "Wasmtime Pulley execution is not linked on this iOS runtime"
}
