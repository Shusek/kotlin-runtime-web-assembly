package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule

/**
 * Supplies a platform binding for [ExecutionBackend.PULLEY].
 *
 * This is intended for embedders that package a native Wasmtime/Pulley binding
 * separately from KRWA, for example an Android JNI library or an iOS cinterop
 * library. Install the provider before creating instances that select
 * [ExecutionBackend.PULLEY].
 *
 * KRWA checks [availability] before calling [create], and rejects executions
 * whose [PlatformInstanceExecution.backend] is not [ExecutionBackend.PULLEY].
 */
interface PulleyExecutionProvider {
    fun availability(): ExecutionBackendAvailability

    fun create(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution
}

internal fun PulleyExecutionProvider.createCheckedPulleyExecution(
    module: WasmModule,
    imports: ImportValues,
    hostInstance: Instance,
): PlatformInstanceExecution {
    val availability = availability()
    if (!availability.available) {
        throw WasmEngineException(
            availability.reason ?: "Wasmtime Pulley execution is not available on this runtime"
        )
    }

    val execution = create(module, imports, hostInstance)
    if (execution.backend != ExecutionBackend.PULLEY) {
        throw WasmEngineException(
            "PulleyExecutionProvider returned ${execution.backend} execution for ${ExecutionBackend.PULLEY}"
        )
    }
    return execution
}

expect object PulleyExecutionProviders {
    fun install(provider: PulleyExecutionProvider?): PulleyExecutionProvider?

    internal fun installed(): PulleyExecutionProvider?
}
