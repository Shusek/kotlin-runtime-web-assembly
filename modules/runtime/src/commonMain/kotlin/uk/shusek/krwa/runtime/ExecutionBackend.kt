package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.FunctionType

enum class ExecutionBackend {
    /** Let the runtime choose its normal backend for the current platform. */
    AUTO,

    /** Execute with the host platform's native WebAssembly engine where available. */
    NATIVE,

    /** Execute with the Wasmtime-backed platform engine where a binding is linked. */
    PULLEY,
}

data class ExecutionBackendAvailability(
    val available: Boolean,
    val reason: String? = null,
)

fun ExecutionBackend.availability(): ExecutionBackendAvailability =
    RuntimePlatform.executionBackendAvailability(this)

fun ExecutionBackend.isAvailable(): Boolean = availability().available

/**
 * Execution surface used by host-backed engines selected through [ExecutionBackend].
 *
 * Implementations own the actual exported functions and linear memories, while
 * KRWA still owns import wiring, component-model lifting/lowering, and host
 * callback dispatch. Memory lookup by index should return `null` when the
 * platform engine cannot expose that memory to KRWA.
 */
interface PlatformInstanceExecution : AutoCloseable {
    val backend: ExecutionBackend

    fun export(name: String): ExportFunction

    fun exportType(name: String): FunctionType

    fun memory(name: String): Memory

    fun memory(index: Int): Memory?

    /**
     * Restores metered execution fuel to the maximum configured when this execution was created.
     * Backends without fuel metering, and executions configured without a fuel limit, do nothing.
     */
    fun replenishFuel() = Unit

    override fun close() = Unit
}
