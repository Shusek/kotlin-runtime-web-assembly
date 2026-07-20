@file:Suppress("TooGenericExceptionCaught")

package uk.shusek.krwa.runtime.wasmtime.android

import uk.shusek.krwa.runtime.WasmtimePreview3ComponentConfig
import uk.shusek.krwa.runtime.WasmtimePreview3Preopen
import uk.shusek.krwa.wasm.WasmEngineException
import java.util.concurrent.atomic.AtomicBoolean

fun androidWasmtimePreview3ComponentUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
): String? {
    val targetReason = androidWasmtimePreview3TargetUnavailableReason(config.target)
    if (targetReason != null) return targetReason
    return AndroidWasmtimePreview3Native.componentUnavailableReason(config.toAndroidPreview3Call())
}

fun androidWasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
): String = androidWasmtimePreview3ComponentCallString(
    config = config,
    exportName = exportName,
    argument = argument,
    cancellation = null,
)

class AndroidWasmtimePreview3ExecutionCancellation : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val handle: Long = AndroidWasmtimePreview3Native.executionCancellationCreate()

    internal fun requireOpenHandle(): Long {
        check(!closed.get()) { "Wasmtime Preview3 execution cancellation is closed" }
        return handle
    }

    fun cancel() {
        if (!closed.get()) {
            AndroidWasmtimePreview3Native.executionCancellationCancel(handle)
        }
    }

    val isCancellationRequested: Boolean
        get() = !closed.get() && AndroidWasmtimePreview3Native.executionCancellationIsCancelled(handle)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            AndroidWasmtimePreview3Native.executionCancellationFree(handle)
        }
    }
}

fun androidWasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    cancellation: AndroidWasmtimePreview3ExecutionCancellation?,
): String {
    val targetReason = androidWasmtimePreview3TargetUnavailableReason(config.target)
    if (targetReason != null) throw WasmEngineException(targetReason)
    return AndroidWasmtimePreview3Native.componentCallString(
        call = config.toAndroidPreview3Call(),
        exportName = exportName,
        argument = argument,
        cancellation = cancellation,
    )
}

fun androidWasmtimePreview3CommandRunUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
): String? {
    val targetReason = androidWasmtimePreview3TargetUnavailableReason(config.target)
    if (targetReason != null) return targetReason
    return AndroidWasmtimePreview3Native.commandRunUnavailableReason(config.toAndroidPreview3Call())
}

fun androidWasmtimePreview3CommandRunString(
    config: WasmtimePreview3ComponentConfig,
    stdin: String,
): String = androidWasmtimePreview3CommandRunString(
    config = config,
    stdin = stdin,
    maxOutputBytes = DefaultCommandOutputBytes,
    cancellation = null,
)

fun androidWasmtimePreview3CommandRunString(
    config: WasmtimePreview3ComponentConfig,
    stdin: String,
    maxOutputBytes: Long = DefaultCommandOutputBytes,
    cancellation: AndroidWasmtimePreview3ExecutionCancellation?,
): String {
    val targetReason = androidWasmtimePreview3TargetUnavailableReason(config.target)
    if (targetReason != null) throw WasmEngineException(targetReason)
    return AndroidWasmtimePreview3Native.commandRunString(
        call = config.toAndroidPreview3Call(),
        stdin = stdin.encodeToByteArray(),
        maxOutputBytes = maxOutputBytes,
        cancellation = cancellation,
    )
}

private fun androidWasmtimePreview3TargetUnavailableReason(target: String): String? {
    val supportedTarget = androidWasmtimePulleyTarget()
    return when (androidWasmtimeTarget(target)) {
        supportedTarget -> null
        else -> "Wasmtime Preview3 Android bridge only supports target $supportedTarget, got $target"
    }
}

private fun WasmtimePreview3ComponentConfig.toAndroidPreview3Call(): AndroidPreview3Call {
    val environmentEntries = environment.entries.toList()
    return AndroidPreview3Call(
        componentBytes = precompiledComponentBytes,
        hostRoots = preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
        guestRoots = preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
        writablePreopens = BooleanArray(preopens.size) { index -> preopens[index].writable },
        arguments = arguments.toTypedArray(),
        environmentKeys = environmentEntries.map { entry -> entry.key }.toTypedArray(),
        environmentValues = environmentEntries.map { entry -> entry.value }.toTypedArray(),
        allowedHosts = networkPolicy.encodedHttpEndpoints().toTypedArray(),
        blockedHosts = emptyArray<String>(),
        allowPrivateNetwork = false,
        maxMemoryBytes = maxMemoryBytes,
        maxWasmStackBytes = maxWasmStackBytes,
        maxTableElements = maxTableElements,
        maxInstances = maxInstances,
        maxTables = maxTables,
        maxMemories = maxMemories,
        maxFuel = maxFuel,
        executionTimeoutMillis = executionTimeoutMillis,
    )
}

private data class AndroidPreview3Call(
    val componentBytes: ByteArray,
    val hostRoots: Array<String>,
    val guestRoots: Array<String>,
    val writablePreopens: BooleanArray,
    val arguments: Array<String>,
    val environmentKeys: Array<String>,
    val environmentValues: Array<String>,
    val allowedHosts: Array<String>,
    val blockedHosts: Array<String>,
    val allowPrivateNetwork: Boolean,
    val maxMemoryBytes: Long,
    val maxWasmStackBytes: Long,
    val maxTableElements: Long,
    val maxInstances: Long,
    val maxTables: Long,
    val maxMemories: Long,
    val maxFuel: Long,
    val executionTimeoutMillis: Long,
)

private object AndroidWasmtimePreview3Native {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("krwa_wasmtime_p3_android")
    }.exceptionOrNull()

    fun componentUnavailableReason(call: AndroidPreview3Call): String? =
        nativeOrLoadError {
            nativeComponentUnavailableReason(
                call.componentBytes,
                call.hostRoots,
                call.guestRoots,
                call.writablePreopens,
                call.arguments,
                call.environmentKeys,
                call.environmentValues,
                call.allowedHosts,
                call.blockedHosts,
                call.allowPrivateNetwork,
                call.maxMemoryBytes,
                call.maxWasmStackBytes,
                call.maxTableElements,
                call.maxInstances,
                call.maxTables,
                call.maxMemories,
                call.maxFuel,
            )
        }

    fun componentCallString(
        call: AndroidPreview3Call,
        exportName: String,
        argument: String,
        cancellation: AndroidWasmtimePreview3ExecutionCancellation?,
    ): String {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        return nativeComponentCallString(
            call.componentBytes,
            call.hostRoots,
            call.guestRoots,
            call.writablePreopens,
            call.arguments,
            call.environmentKeys,
            call.environmentValues,
            call.allowedHosts,
            call.blockedHosts,
            call.allowPrivateNetwork,
            exportName,
            argument,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
            call.maxFuel,
            call.executionTimeoutMillis,
            cancellation?.requireOpenHandle() ?: 0L,
        )
    }

    fun executionCancellationCreate(): Long {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        return nativeExecutionCancellationCreate()
    }

    fun executionCancellationCancel(handle: Long) {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        nativeExecutionCancellationCancel(handle)
    }

    fun executionCancellationIsCancelled(handle: Long): Boolean {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        return nativeExecutionCancellationIsCancelled(handle)
    }

    fun executionCancellationFree(handle: Long) {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        nativeExecutionCancellationFree(handle)
    }

    fun commandRunUnavailableReason(call: AndroidPreview3Call): String? =
        nativeOrLoadError {
            nativeCommandRunUnavailableReason(
                call.componentBytes,
                call.hostRoots,
                call.guestRoots,
                call.writablePreopens,
                call.arguments,
                call.environmentKeys,
                call.environmentValues,
                call.allowedHosts,
                call.blockedHosts,
                call.allowPrivateNetwork,
                call.maxMemoryBytes,
                call.maxWasmStackBytes,
                call.maxTableElements,
                call.maxInstances,
                call.maxTables,
                call.maxMemories,
                call.maxFuel,
                call.executionTimeoutMillis,
            )
        }

    fun commandRunString(
        call: AndroidPreview3Call,
        stdin: ByteArray,
        maxOutputBytes: Long,
        cancellation: AndroidWasmtimePreview3ExecutionCancellation?,
    ): String {
        loadError?.let { error ->
            throw WasmEngineException(androidPreview3LoadErrorMessage(error), error)
        }
        return nativeCommandRunString(
            call.componentBytes,
            call.hostRoots,
            call.guestRoots,
            call.writablePreopens,
            call.arguments,
            call.environmentKeys,
            call.environmentValues,
            stdin,
            call.allowedHosts,
            call.blockedHosts,
            call.allowPrivateNetwork,
            call.maxMemoryBytes,
            call.maxWasmStackBytes,
            call.maxTableElements,
            call.maxInstances,
            call.maxTables,
            call.maxMemories,
            call.maxFuel,
            maxOutputBytes,
            call.executionTimeoutMillis,
            cancellation?.requireOpenHandle() ?: 0L,
        )
    }

    private fun nativeOrLoadError(call: () -> String?): String? {
        loadError?.let { error -> return androidPreview3LoadErrorMessage(error) }
        return try {
            call()
        } catch (error: Throwable) {
            androidPreview3LoadErrorMessage(error)
        }
    }

    @JvmStatic
    external fun nativeComponentUnavailableReason(
        componentBytes: ByteArray,
        hostRoots: Array<String>,
        guestRoots: Array<String>,
        writablePreopens: BooleanArray,
        arguments: Array<String>,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        allowedHosts: Array<String>,
        blockedHosts: Array<String>,
        allowPrivateNetwork: Boolean,
        maxMemoryBytes: Long,
        maxWasmStackBytes: Long,
        maxTableElements: Long,
        maxInstances: Long,
        maxTables: Long,
        maxMemories: Long,
        maxFuel: Long,
    ): String?

    @JvmStatic
    external fun nativeComponentCallString(
        componentBytes: ByteArray,
        hostRoots: Array<String>,
        guestRoots: Array<String>,
        writablePreopens: BooleanArray,
        arguments: Array<String>,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        allowedHosts: Array<String>,
        blockedHosts: Array<String>,
        allowPrivateNetwork: Boolean,
        exportName: String,
        argument: String,
        maxMemoryBytes: Long,
        maxWasmStackBytes: Long,
        maxTableElements: Long,
        maxInstances: Long,
        maxTables: Long,
        maxMemories: Long,
        maxFuel: Long,
        executionTimeoutMillis: Long,
        executionCancellationHandle: Long,
    ): String

    @JvmStatic
    external fun nativeExecutionCancellationCreate(): Long

    @JvmStatic
    external fun nativeExecutionCancellationCancel(handle: Long)

    @JvmStatic
    external fun nativeExecutionCancellationIsCancelled(handle: Long): Boolean

    @JvmStatic
    external fun nativeExecutionCancellationFree(handle: Long)

    @JvmStatic
    external fun nativeCommandRunUnavailableReason(
        componentBytes: ByteArray,
        hostRoots: Array<String>,
        guestRoots: Array<String>,
        writablePreopens: BooleanArray,
        arguments: Array<String>,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        allowedHosts: Array<String>,
        blockedHosts: Array<String>,
        allowPrivateNetwork: Boolean,
        maxMemoryBytes: Long,
        maxWasmStackBytes: Long,
        maxTableElements: Long,
        maxInstances: Long,
        maxTables: Long,
        maxMemories: Long,
        maxFuel: Long,
        executionTimeoutMillis: Long,
    ): String?

    @JvmStatic
    external fun nativeCommandRunString(
        componentBytes: ByteArray,
        hostRoots: Array<String>,
        guestRoots: Array<String>,
        writablePreopens: BooleanArray,
        arguments: Array<String>,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        stdinBytes: ByteArray,
        allowedHosts: Array<String>,
        blockedHosts: Array<String>,
        allowPrivateNetwork: Boolean,
        maxMemoryBytes: Long,
        maxWasmStackBytes: Long,
        maxTableElements: Long,
        maxInstances: Long,
        maxTables: Long,
        maxMemories: Long,
        maxFuel: Long,
        maxOutputBytes: Long,
        executionTimeoutMillis: Long,
        executionCancellationHandle: Long,
    ): String
}

private const val DefaultCommandOutputBytes = 16L * 1024L * 1024L

private fun androidPreview3LoadErrorMessage(error: Throwable): String {
    val message = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName ?: "unknown error"
    return "Wasmtime Preview3 component bridge is not linked on this Android runtime: $message"
}
