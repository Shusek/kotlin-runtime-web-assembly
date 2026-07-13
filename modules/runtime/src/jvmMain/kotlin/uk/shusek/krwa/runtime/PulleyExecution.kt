package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import java.lang.reflect.InvocationTargetException
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicBoolean

internal actual object PulleyExecution {
    actual fun create(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution {
        provider()?.let { return it.createCheckedPulleyExecution(module, imports, hostInstance) }
        if (isAndroidRuntime()) {
            throw WasmEngineException(AndroidUnavailableReason)
        }
        return invokeWasmtimeCreate(module, imports, hostInstance)
    }

    actual fun availability(): ExecutionBackendAvailability {
        provider()?.let { return it.availability() }
        if (isAndroidRuntime()) {
            return ExecutionBackendAvailability(available = false, reason = AndroidUnavailableReason)
        }
        return invokeWasmtimeUnavailableReason(DefaultWasmtimeTarget)?.let { reason ->
            ExecutionBackendAvailability(available = false, reason = reason)
        } ?: ExecutionBackendAvailability(available = true)
    }

    private fun provider(): PulleyExecutionProvider? =
        PulleyExecutionProviders.installed() ?: serviceLoaderProvider()

    private fun serviceLoaderProvider(): PulleyExecutionProvider? =
        ServiceLoader.load(PulleyExecutionProvider::class.java)
            .iterator()
            .asSequence()
            .firstOrNull()

    private fun invokeWasmtimeCreate(
        module: WasmModule,
        imports: ImportValues,
        hostInstance: Instance,
    ): PlatformInstanceExecution {
        val config = hostInstance.wasmtimeExecutionConfig() ?: WasmtimeExecutionConfig()
        val type = wasmtimePulleyExecutionClass()
        val method = type.getDeclaredMethod(
            "create",
            WasmModule::class.java,
            ImportValues::class.java,
            Instance::class.java,
            String::class.java,
            ByteArray::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        try {
            return method.invoke(
                null,
                module,
                imports,
                hostInstance,
                config.target,
                config.precompiledModuleBytes,
                config.maxMemoryBytes,
                config.maxWasmStackBytes,
                config.maxTableElements,
                config.maxInstances,
                config.maxTables,
                config.maxMemories,
                config.maxFuel,
            ) as PlatformInstanceExecution
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun invokeWasmtimeUnavailableReason(target: String): String? =
        try {
            val type = wasmtimePulleyExecutionClass()
            val method = type.getDeclaredMethod("unavailableReason", String::class.java)
            method.isAccessible = true
            method.invoke(null, target) as String?
        } catch (e: InvocationTargetException) {
            e.targetException.toUnavailableReason()
        } catch (e: Throwable) {
            e.toUnavailableReason()
        }

    private fun wasmtimePulleyExecutionClass(): Class<*> =
        Class.forName(WasmtimePulleyExecutionClassName)

    private fun Throwable.toUnavailableReason(): String {
        val message = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
        return "Wasmtime Pulley execution is not linked on this JVM runtime: $message"
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.runtime.name")
            ?.contains("Android", ignoreCase = true) == true ||
            runCatching { Class.forName("android.os.Build") }.isSuccess

    private const val AndroidUnavailableReason =
        "Wasmtime Pulley execution is not linked on this Android runtime"
    private const val DefaultWasmtimeTarget = WasmtimeNativeTarget
    private const val WasmtimePulleyExecutionClassName =
        "uk.shusek.krwa.runtime.WasmtimePulleyExecution"
}

actual fun wasmtimeTargetUnavailableReason(target: String): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod("unavailableReason", String::class.java)
        method.isAccessible = true
        method.invoke(null, target) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimeUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimeUnavailableReason()
    }

actual fun wasmtimePreview3ComponentUnavailableReason(config: WasmtimePreview3ComponentConfig): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3ComponentUnavailableReason",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
        ) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimePreview3ComponentUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimePreview3ComponentUnavailableReason()
    }

actual fun wasmtimePreview3ComponentCall0UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3ComponentCall0UnavailableReason",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            exportName,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
        ) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimePreview3ComponentUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimePreview3ComponentUnavailableReason()
    }

actual fun wasmtimePreview3ComponentCallS32UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: Int,
    expectedResult: Int,
): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3ComponentCallS32UnavailableReason",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            exportName,
            argument,
            expectedResult,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
        ) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimePreview3ComponentUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimePreview3ComponentUnavailableReason()
    }

actual fun wasmtimePreview3ComponentCallStringUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    expectedResult: String,
): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3ComponentCallStringUnavailableReason",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            exportName,
            argument,
            expectedResult,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
        ) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimePreview3ComponentUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimePreview3ComponentUnavailableReason()
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
    private val closed = AtomicBoolean(false)
    internal val handle: Long = WasmtimePulleyExecution.preview3ExecutionCancellationCreate()

    fun cancel() {
        if (!closed.get()) {
            WasmtimePulleyExecution.preview3ExecutionCancellationCancel(handle)
        }
    }

    val isCancellationRequested: Boolean
        get() = !closed.get() && WasmtimePulleyExecution.preview3ExecutionCancellationIsCancelled(handle)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WasmtimePulleyExecution.preview3ExecutionCancellationFree(handle)
        }
    }
}

fun wasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    cancellation: WasmtimePreview3ExecutionCancellation?,
): String =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3ComponentCallString",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            exportName,
            argument,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
            config.executionTimeoutMillis,
            cancellation?.handle ?: 0L,
        ) as String
    } catch (e: InvocationTargetException) {
        throw e.targetException
    } catch (e: Throwable) {
        val message = e.message?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName
        throw WasmEngineException("Wasmtime Preview3 component bridge is not linked on this JVM runtime: $message", e)
    }

actual fun wasmtimePreview3CommandRunUnavailableReason(config: WasmtimePreview3ComponentConfig): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod(
            "preview3CommandRunUnavailableReason",
            ByteArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            BooleanArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val preopens = config.preopens
        val environment = config.environment.entries.toList()
        val networkPolicy = config.networkPolicy
        method.isAccessible = true
        method.invoke(
            null,
            config.precompiledComponentBytes,
            preopens.map(WasmtimePreview3Preopen::hostRoot).toTypedArray(),
            preopens.map(WasmtimePreview3Preopen::guestRoot).toTypedArray(),
            BooleanArray(preopens.size) { index -> preopens[index].writable },
            config.arguments.toTypedArray(),
            environment.map { entry -> entry.key }.toTypedArray(),
            environment.map { entry -> entry.value }.toTypedArray(),
            networkPolicy.allowedHosts.toTypedArray(),
            networkPolicy.blockedHosts.toTypedArray(),
            networkPolicy.allowPrivateNetwork,
            config.target,
            config.maxMemoryBytes,
            config.maxWasmStackBytes,
            config.maxTableElements,
            config.maxInstances,
            config.maxTables,
            config.maxMemories,
            config.maxFuel,
            config.executionTimeoutMillis,
        ) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimePreview3ComponentUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimePreview3ComponentUnavailableReason()
    }

private fun Throwable.toWasmtimeUnavailableReason(): String {
    val message = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    return "Wasmtime Pulley execution is not linked on this JVM runtime: $message"
}

private fun Throwable.toWasmtimePreview3ComponentUnavailableReason(): String {
    val message = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: $message"
}

fun wasmtimeComponentWasiUnavailableReason(): String? =
    try {
        val type = Class.forName("uk.shusek.krwa.runtime.WasmtimePulleyExecution")
        val method = type.getDeclaredMethod("componentWasiUnavailableReason")
        method.isAccessible = true
        method.invoke(null) as String?
    } catch (e: InvocationTargetException) {
        e.targetException.toWasmtimeComponentWasiUnavailableReason()
    } catch (e: Throwable) {
        e.toWasmtimeComponentWasiUnavailableReason()
    }

private fun Throwable.toWasmtimeComponentWasiUnavailableReason(): String {
    val message = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    return "Wasmtime C API component/WASIp2 primitives are not linked on this JVM runtime: $message"
}

actual fun installWasmtimePulleyExecutionProviderIfAvailable() = Unit
