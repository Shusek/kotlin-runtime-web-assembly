@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.shusek.krwa.runtime

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_component_wasi_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_execution_cancellation_cancel
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_execution_cancellation_create
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_execution_cancellation_free
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_execution_cancellation_is_cancelled
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_command_run_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_component_call0_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_component_call_s32_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_component_call_string
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_component_call_string_unavailable_reason
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason

@OptIn(ExperimentalForeignApi::class)
internal fun iosWasmtimePulleyUnavailableReason(): String? = krwa_pulley_unavailable_reason()?.toKString()

@OptIn(ExperimentalForeignApi::class)
fun wasmtimeComponentWasiUnavailableReason(): String? =
    krwa_wasmtime_component_wasi_unavailable_reason()?.toKString()

actual fun wasmtimePreview3ComponentUnavailableReason(config: WasmtimePreview3ComponentConfig): String? {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> return reason }
    return config.withIosPreview3Call { call ->
        krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
        )?.toKString()
    }
}

actual fun wasmtimePreview3ComponentCall0UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
): String? {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> return reason }
    return config.withIosPreview3Call { call ->
        krwa_wasmtime_p3_precompiled_component_call0_unavailable_reason(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            exportName,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
        )?.toKString()
    }
}

actual fun wasmtimePreview3ComponentCallS32UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: Int,
    expectedResult: Int,
): String? {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> return reason }
    return config.withIosPreview3Call { call ->
        krwa_wasmtime_p3_precompiled_component_call_s32_unavailable_reason(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            exportName,
            argument,
            expectedResult,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
        )?.toKString()
    }
}

actual fun wasmtimePreview3ComponentCallStringUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    expectedResult: String,
): String? {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> return reason }
    return config.withIosPreview3Call { call ->
        krwa_wasmtime_p3_precompiled_component_call_string_unavailable_reason(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            exportName,
            argument,
            expectedResult,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
        )?.toKString()
    }
}

actual fun wasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
): String = wasmtimePreview3ComponentCallString(
    config = config,
    exportName = exportName,
    argument = argument,
    cancellation = null,
)

class WasmtimePreview3ExecutionCancellation : AutoCloseable {
    internal val handle: COpaquePointer =
        krwa_wasmtime_p3_execution_cancellation_create()
            ?: throw WasmEngineException("Wasmtime Preview3 cancellation handle allocation failed")
    private var closed: Boolean = false

    fun cancel() {
        if (!closed) {
            krwa_wasmtime_p3_execution_cancellation_cancel(handle)
        }
    }

    val isCancellationRequested: Boolean
        get() = !closed && krwa_wasmtime_p3_execution_cancellation_is_cancelled(handle) != 0.toUByte()

    override fun close() {
        if (!closed) {
            closed = true
            krwa_wasmtime_p3_execution_cancellation_free(handle)
        }
    }
}

fun wasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    cancellation: WasmtimePreview3ExecutionCancellation?,
): String {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> throw WasmEngineException(reason) }
    return config.withIosPreview3Call { call ->
        val resultOut = alloc<ULongVar>()
        resultOut.value = 0u
        val error = krwa_wasmtime_p3_precompiled_component_call_string(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            exportName,
            argument,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
            call.executionTimeoutMillis,
            cancellation?.handle,
            resultOut.ptr,
        )
        if (error != null) {
            throw WasmEngineException(error.toKString())
        }
        resultOut.value.toLong().toCPointer<ByteVar>()?.toKString()
            ?: throw WasmEngineException("Wasmtime Preview3 component call returned a null result")
    }
}

actual fun wasmtimePreview3CommandRunUnavailableReason(config: WasmtimePreview3ComponentConfig): String? {
    iosWasmtimePreview3TargetUnavailableReason(config.target)?.let { reason -> return reason }
    return config.withIosPreview3Call { call ->
        krwa_wasmtime_p3_precompiled_command_run_unavailable_reason(
            call.componentBytes,
            call.componentSize,
            call.hostRoots.pointer,
            call.guestRoots.pointer,
            call.writablePreopens,
            call.preopenCount,
            call.arguments.pointer,
            call.arguments.count,
            call.environmentKeys.pointer,
            call.environmentValues.pointer,
            call.environmentKeys.count,
            call.allowedHosts.pointer,
            call.allowedHosts.count,
            call.blockedHosts.pointer,
            call.blockedHosts.count,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
            call.executionTimeoutMillis,
        )?.toKString()
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> WasmtimePreview3ComponentConfig.withIosPreview3Call(
    block: MemScope.(IosPreview3Call) -> T,
): T = memScoped {
    val environmentEntries = environment.entries.toList()
    val hostRoots = allocCStringArray(preopens.map(WasmtimePreview3Preopen::hostRoot))
    val guestRoots = allocCStringArray(preopens.map(WasmtimePreview3Preopen::guestRoot))
    val arguments = allocCStringArray(arguments)
    val environmentKeys = allocCStringArray(environmentEntries.map { entry -> entry.key })
    val environmentValues = allocCStringArray(environmentEntries.map { entry -> entry.value })
    val allowedHosts = allocCStringArray(networkPolicy.allowedHosts)
    val blockedHosts = allocCStringArray(networkPolicy.blockedHosts)
    val writablePreopens = allocWritablePreopens(preopens)

    precompiledComponentBytes.usePinned { pinned ->
        block(
            IosPreview3Call(
                componentBytes = pinned.addressOf(0).reinterpret(),
                componentSize = precompiledComponentBytes.size.convert(),
                hostRoots = hostRoots,
                guestRoots = guestRoots,
                writablePreopens = writablePreopens,
                preopenCount = preopens.size.convert(),
                arguments = arguments,
                environmentKeys = environmentKeys,
                environmentValues = environmentValues,
                allowedHosts = allowedHosts,
                blockedHosts = blockedHosts,
                allowPrivateNetwork = if (networkPolicy.allowPrivateNetwork) 1u else 0u,
                maxMemoryBytes = maxMemoryBytes.toULong(),
                maxWasmStackBytes = maxWasmStackBytes.toULong(),
                maxTableElements = maxTableElements,
                maxInstances = maxInstances,
                maxTables = maxTables,
                maxMemories = maxMemories,
                executionTimeoutMillis = executionTimeoutMillis.toULong(),
            ),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private data class IosPreview3Call(
    val componentBytes: CPointer<UByteVar>,
    val componentSize: ULong,
    val hostRoots: IosCStringArray,
    val guestRoots: IosCStringArray,
    val writablePreopens: CPointer<UByteVar>?,
    val preopenCount: ULong,
    val arguments: IosCStringArray,
    val environmentKeys: IosCStringArray,
    val environmentValues: IosCStringArray,
    val allowedHosts: IosCStringArray,
    val blockedHosts: IosCStringArray,
    val allowPrivateNetwork: UByte,
    val maxMemoryBytes: ULong,
    val maxWasmStackBytes: ULong,
    val maxTableElements: Long,
    val maxInstances: Long,
    val maxTables: Long,
    val maxMemories: Long,
    val executionTimeoutMillis: ULong,
)

@OptIn(ExperimentalForeignApi::class)
private data class IosCStringArray(
    val pointer: CPointer<CPointerVarOf<CPointer<ByteVar>>>?,
    val count: ULong,
)

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.allocCStringArray(values: List<String>): IosCStringArray {
    if (values.isEmpty()) return IosCStringArray(pointer = null, count = 0u)
    val pointer = allocArray<CPointerVarOf<CPointer<ByteVar>>>(values.size)
    values.forEachIndexed { index, value ->
        pointer[index] = value.cstr.ptr
    }
    return IosCStringArray(pointer = pointer, count = values.size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.allocWritablePreopens(preopens: List<WasmtimePreview3Preopen>): CPointer<UByteVar>? {
    if (preopens.isEmpty()) return null
    val pointer = allocArray<UByteVar>(preopens.size)
    preopens.forEachIndexed { index, preopen ->
        pointer[index] = if (preopen.writable) 1u else 0u
    }
    return pointer
}

private fun iosWasmtimePreview3TargetUnavailableReason(target: String): String? =
    when (target) {
        WasmtimePulleyTarget -> null
        else -> "Wasmtime Preview3 iOS bridge only supports target $WasmtimePulleyTarget, got $target"
    }

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> ByteArray.usePinned(block: (Pinned<ByteArray>) -> T): T {
    val pinned = pin()
    return try {
        block(pinned)
    } finally {
        pinned.unpin()
    }
}
