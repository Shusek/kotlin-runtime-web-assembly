package uk.shusek.krwa.component

import java.io.IOException
import java.io.UncheckedIOException
import java.util.concurrent.ConcurrentHashMap
import okio.FileSystem
import okio.Path
import uk.shusek.krwa.compiler.Cache
import uk.shusek.krwa.compiler.InterpreterFallback
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmModule

internal actual fun wasmPluginReadPathBytes(path: Path): ByteArray {
    try {
        return FileSystem.SYSTEM.read(path) { readByteArray() }
    } catch (e: IOException) {
        throw UncheckedIOException(e)
    }
}

internal actual fun wasmPluginUnbundleComponent(
    componentBytes: ByteArray
): WasmPluginUnbundledComponent =
    WasmComponentTools.unbundleComponent(componentBytes).toWasmPluginComponent()

internal actual fun wasmPluginUnbundleComponent(componentPath: Path): WasmPluginUnbundledComponent =
    WasmComponentTools.unbundleComponent(componentPath).toWasmPluginComponent()

internal actual fun wasmPluginParseWit(componentBytes: ByteArray): WitPackage =
    Wit.parse(componentBytes)

internal actual fun wasmPluginParseWit(componentPath: Path): WitPackage =
    Wit.parse(componentPath)

internal actual fun <T : Any> wasmPluginReflectExports(
    plugin: WasmPlugin,
    contractType: ComponentModelJvmClass<T>,
): T {
    return WitReflection.exports(plugin, contractType)
}

internal actual fun wasmPluginHostHandler(
    hostObjects: List<Any>,
    interfaceName: String,
    functionName: String,
): HostHandler? =
    WitReflection.hostHandler(hostObjects, interfaceName, functionName)

internal actual fun wasmPluginCompiledMachineFactory(
    module: WasmModule,
): ((Instance) -> Machine)? {
    if (!componentCompilerEnabled()) {
        return null
    }
    return try {
        MachineFactoryCompiler.builder(module)
            .withCache(ComponentMachineCache)
            .withInterpreterFallback(InterpreterFallback.SILENT)
            .compile()
    } catch (e: Exception) {
        traceCompilerFallback(e)
        null
    } catch (e: LinkageError) {
        traceCompilerFallback(e)
        null
    }
}

internal actual fun wasmPluginPlatformUnsupported(feature: String): Nothing {
    throw ComponentModelException("$feature is not available on this platform")
}

private fun WasmComponentTools.UnbundledComponent.toWasmPluginComponent(): WasmPluginUnbundledComponent =
    WasmPluginUnbundledComponent(component(), modules())

private fun componentCompilerEnabled(): Boolean {
    val configured = System.getProperty(COMPONENT_COMPILER_PROPERTY)
    if (configured != null) {
        return configured.toBoolean()
    }
    return !androidRuntime()
}

private fun androidRuntime(): Boolean {
    val runtimeName = System.getProperty("java.runtime.name", "")
    val vmName = System.getProperty("java.vm.name", "")
    return runtimeName.contains("Android", ignoreCase = true) ||
        vmName.contains("Dalvik", ignoreCase = true)
}

private fun traceCompilerFallback(error: Throwable) {
    if (!java.lang.Boolean.getBoolean(COMPONENT_COMPILER_TRACE_PROPERTY)) {
        return
    }
    System.err.println(
        "KRWA component compiler failed; falling back to interpreter: " +
            error.javaClass.name +
            ": " +
            error.message
    )
    error.printStackTrace(System.err)
}

private const val COMPONENT_COMPILER_PROPERTY = "krwa.component.compiler"
private const val COMPONENT_COMPILER_TRACE_PROPERTY = "krwa.component.compiler.trace"

private object ComponentMachineCache : Cache {
    private val entries = ConcurrentHashMap<String, ByteArray>()

    override fun get(key: String): ByteArray? = entries[key]?.clone()

    override fun putIfAbsent(key: String, data: ByteArray) {
        entries.putIfAbsent(key, data.clone())
    }
}
