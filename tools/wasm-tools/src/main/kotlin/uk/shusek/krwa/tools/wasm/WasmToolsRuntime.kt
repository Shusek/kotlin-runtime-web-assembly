package uk.shusek.krwa.tools.wasm

import uk.shusek.krwa.runtime.WasmtimeExecutionConfig
import uk.shusek.krwa.runtime.configureWasmtimeExecution
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

object WasmToolsRuntime {
    val module: WasmModule by lazy {
        loadRawModule()
            .also { module ->
                configureWasmtimeExecution(
                    module,
                    WasmtimeExecutionConfig(maxWasmStackBytes = 8L * 1024L * 1024L),
                )
            }
    }

    private fun loadRawModule(): WasmModule {
        val input =
            WasmToolsRuntime::class.java.getResourceAsStream("/wasm-tools.wasm")
                ?: throw IllegalStateException("Missing wasm-tools.wasm resource")
        return input.use { Parser.parse(it) }
    }
}
