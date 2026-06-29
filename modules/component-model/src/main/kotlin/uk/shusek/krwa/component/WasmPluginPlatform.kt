package uk.shusek.krwa.component

import okio.Path

internal class WasmPluginUnbundledComponent(
    private val witBytes: ByteArray,
    private val modules: Map<String, ByteArray>,
) {
    fun witBytes(): ByteArray = witBytes.copyOf()

    fun module(name: String): ByteArray =
        modules[name]?.copyOf()
            ?: throw ComponentModelException("component does not contain core module $name")

    fun modules(): Map<String, ByteArray> =
        modules.mapValues { (_, bytes) -> bytes.copyOf() }
}

internal expect fun wasmPluginReadPathBytes(path: Path): ByteArray

internal expect fun wasmPluginUnbundleComponent(componentBytes: ByteArray): WasmPluginUnbundledComponent

internal expect fun wasmPluginUnbundleComponent(componentPath: Path): WasmPluginUnbundledComponent

internal expect fun wasmPluginParseWit(componentBytes: ByteArray): WitPackage

internal expect fun wasmPluginParseWit(componentPath: Path): WitPackage

internal expect fun <T : Any> wasmPluginReflectExports(
    plugin: WasmPlugin,
    contractType: ComponentModelJvmClass<T>,
): T

internal expect fun wasmPluginHostHandler(
    hostObjects: List<Any>,
    interfaceName: String,
    functionName: String,
): HostHandler?

internal expect fun wasmPluginPlatformUnsupported(feature: String): Nothing
