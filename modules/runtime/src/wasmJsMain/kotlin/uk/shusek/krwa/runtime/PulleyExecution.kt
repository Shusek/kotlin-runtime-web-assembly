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

    private const val UnavailableReason = "Wasmtime Pulley execution is not available on wasmJs"
}

actual fun wasmtimeTargetUnavailableReason(target: String): String? = when (target) {
    WasmtimeAutomaticTarget -> "Wasmtime execution is not available on wasmJs"
    WasmtimeNativeTarget -> "Wasmtime native AOT target $target is not available on wasmJs"
    WasmtimePulleyTarget -> "Wasmtime Pulley execution is not available on wasmJs"
    else -> "Wasmtime target $target is not supported on wasmJs"
}

actual fun wasmtimePreview3ComponentUnavailableReason(config: WasmtimePreview3ComponentConfig): String? =
    "Wasmtime Preview3 component bridge is not available on wasmJs"

actual fun wasmtimePreview3ComponentCall0UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
): String? = "Wasmtime Preview3 component bridge is not available on wasmJs"

actual fun wasmtimePreview3ComponentCallS32UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: Int,
    expectedResult: Int,
): String? = "Wasmtime Preview3 component bridge is not available on wasmJs"

actual fun wasmtimePreview3ComponentCallStringUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    expectedResult: String,
): String? = "Wasmtime Preview3 component bridge is not available on wasmJs"

actual fun wasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
): String = throw WasmEngineException("Wasmtime Preview3 component bridge is not available on wasmJs")

actual fun wasmtimePreview3CommandRunUnavailableReason(config: WasmtimePreview3ComponentConfig): String? =
    "Wasmtime Preview3 component bridge is not available on wasmJs"

actual fun installWasmtimePulleyExecutionProviderIfAvailable() = Unit
