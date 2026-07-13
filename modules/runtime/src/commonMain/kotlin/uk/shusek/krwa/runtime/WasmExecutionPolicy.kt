package uk.shusek.krwa.runtime

/**
 * Selects one complete WebAssembly execution strategy for an [Instance].
 *
 * The policy keeps the platform engine choice and its resource limit together, so embedders do
 * not have to coordinate [ExecutionBackend] and [WasmtimeExecutionConfig] independently.
 * Platform availability is still authoritative: host WebAssembly is available on wasmJs, iOS
 * Wasmtime supports Pulley targets, and native Wasmtime targets are only available where the
 * linked provider reports them.
 */
sealed interface WasmExecutionPolicy {
    /** Let the platform choose its normal engine, applying [maxMemoryBytes] to either path. */
    data class Automatic(val maxMemoryBytes: Long? = null) : WasmExecutionPolicy {
        init {
            validateHostMemoryLimit(maxMemoryBytes)
        }
    }

    /** Require the host platform WebAssembly engine, such as browser or Node WebAssembly. */
    data class HostWebAssembly(val maxMemoryBytes: Long? = null) : WasmExecutionPolicy {
        init {
            validateHostMemoryLimit(maxMemoryBytes)
        }
    }

    /** Require the linked Wasmtime provider with the supplied target and serialized-module policy. */
    data class Wasmtime(val config: WasmtimeExecutionConfig) : WasmExecutionPolicy
}

private fun validateHostMemoryLimit(maxMemoryBytes: Long?) {
    if (maxMemoryBytes == null) return
    require(maxMemoryBytes > 0) { "maximum WebAssembly memory bytes must be positive" }
    require(maxMemoryBytes % Memory.PAGE_SIZE.toLong() == 0L) {
        "maximum WebAssembly memory bytes must be a multiple of ${Memory.PAGE_SIZE}"
    }
    require(maxMemoryBytes / Memory.PAGE_SIZE.toLong() <= uk.shusek.krwa.wasm.types.MemoryLimits.MAX_PAGES) {
        "maximum WebAssembly memory must not exceed ${uk.shusek.krwa.wasm.types.MemoryLimits.MAX_PAGES} pages"
    }
}
