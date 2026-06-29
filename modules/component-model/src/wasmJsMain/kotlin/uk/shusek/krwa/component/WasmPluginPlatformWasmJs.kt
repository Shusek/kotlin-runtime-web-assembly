package uk.shusek.krwa.component

import okio.Path

internal actual fun wasmPluginReadPathBytes(path: Path): ByteArray =
    wasmPluginPlatformUnsupported("WasmPlugin path loading")

internal actual fun wasmPluginUnbundleComponent(
    componentBytes: ByteArray
): WasmPluginUnbundledComponent =
    wasmPluginPlatformUnsupported("WasmPlugin component unbundling")

internal actual fun wasmPluginUnbundleComponent(componentPath: Path): WasmPluginUnbundledComponent =
    wasmPluginPlatformUnsupported("WasmPlugin component unbundling")

internal actual fun wasmPluginParseWit(componentBytes: ByteArray): WitPackage =
    wasmPluginPlatformUnsupported("WasmPlugin WIT extraction from component bytes")

internal actual fun wasmPluginParseWit(componentPath: Path): WitPackage =
    wasmPluginPlatformUnsupported("WasmPlugin WIT extraction from component path")

internal actual fun <T : Any> wasmPluginReflectExports(
    plugin: WasmPlugin,
    contractType: ComponentModelJvmClass<T>,
): T =
    wasmPluginPlatformUnsupported("WasmPlugin reflection exports")

internal actual fun wasmPluginHostHandler(
    hostObjects: List<Any>,
    interfaceName: String,
    functionName: String,
): HostHandler? = null

internal actual fun wasmPluginPlatformUnsupported(feature: String): Nothing {
    throw ComponentModelException("$feature is not available on wasmJs")
}
