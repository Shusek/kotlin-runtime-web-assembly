package uk.shusek.krwa.wabt

import uk.shusek.krwa.runtime.WasmtimeExecutionConfig
import uk.shusek.krwa.runtime.configureWasmtimeExecution
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

internal class WabtRuntime(private val moduleResourceName: String) {
    val module: WasmModule by lazy {
        val input =
            WabtRuntime::class.java.getResourceAsStream("/$moduleResourceName")
                ?: throw IllegalStateException("Missing $moduleResourceName resource")
        input.use { Parser.parse(it) }
            .also { module ->
                configureWasmtimeExecution(
                    module,
                    WasmtimeExecutionConfig(maxWasmStackBytes = 8L * 1024L * 1024L),
                )
            }
    }
}
