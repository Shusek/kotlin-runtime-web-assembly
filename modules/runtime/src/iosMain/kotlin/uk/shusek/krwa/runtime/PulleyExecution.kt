package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule

internal actual object PulleyExecution {
    actual fun create(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution =
        provider().createCheckedPulleyExecution(module, imports, hostInstance)

    actual fun availability(): ExecutionBackendAvailability =
        provider().availability()

    private fun provider(): PulleyExecutionProvider =
        PulleyExecutionProviders.installed() ?: IosPulleyExecutionProvider
}
