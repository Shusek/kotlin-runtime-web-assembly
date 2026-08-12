package uk.shusek.krwa.component.tooling

import java.nio.charset.StandardCharsets
import okio.Path

/**
 * JVM service-provider interface for component-model operations backed by `wasm-tools`.
 *
 * Applications that only load core WebAssembly modules do not need an implementation. Add the
 * `component-model-tooling` artifact when using WIT normalization, component unbundling, or
 * component packaging APIs.
 */
interface WasmToolsExecutionProvider {
    fun execute(args: List<String>, directories: Map<String, Path>): WasmToolsExecutionResult
}

/** Result returned by a [WasmToolsExecutionProvider]. */
class WasmToolsExecutionResult(
    private val exitCode: Int,
    stdout: ByteArray,
    stderr: ByteArray,
) {
    private val stdout: ByteArray = stdout.clone()
    private val stderr: ByteArray = stderr.clone()

    fun exitCode(): Int = exitCode

    fun stdout(): ByteArray = stdout.clone()

    fun stdoutText(): String = String(stdout, StandardCharsets.UTF_8)

    fun stderr(): ByteArray = stderr.clone()

    fun stderrText(): String = String(stderr, StandardCharsets.UTF_8)
}
