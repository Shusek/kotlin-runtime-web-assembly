package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule

internal expect object PulleyExecution {
    fun create(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution

    fun availability(): ExecutionBackendAvailability
}
