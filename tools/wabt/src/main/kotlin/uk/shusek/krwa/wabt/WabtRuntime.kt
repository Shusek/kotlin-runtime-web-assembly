package uk.shusek.krwa.wabt

import uk.shusek.krwa.runtime.WasmtimeExecutionConfig
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

internal class WabtRuntime(private val moduleResourceName: String) {
    val executionConfig: WasmtimeExecutionConfig =
        WasmtimeExecutionConfig(maxWasmStackBytes = 8L * 1024L * 1024L)

    val module: WasmModule by lazy {
        val input =
            WabtRuntime::class.java.getResourceAsStream("/$moduleResourceName")
                ?: throw IllegalStateException("Missing $moduleResourceName resource")
        input.use { Parser.parse(it) }
    }
}
