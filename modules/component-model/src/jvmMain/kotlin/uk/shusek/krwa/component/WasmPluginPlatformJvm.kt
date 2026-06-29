package uk.shusek.krwa.component

import java.io.IOException
import java.io.UncheckedIOException
import okio.FileSystem
import okio.Path
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
): ((Instance) -> Machine)? = null

internal actual fun wasmPluginPlatformUnsupported(feature: String): Nothing {
    throw ComponentModelException("$feature is not available on this platform")
}

private fun WasmComponentTools.UnbundledComponent.toWasmPluginComponent(): WasmPluginUnbundledComponent =
    WasmPluginUnbundledComponent(component(), modules())
