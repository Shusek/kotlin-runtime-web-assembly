package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.MemoryLimits

/**
 * Bounds every WebAssembly memory owned by one instance.
 *
 * [maxTotalBytes] is enforced against imported memory maxima plus the effective maxima assigned to
 * defined memories, so growth cannot make the instance exceed the aggregate budget.
 */
data class WasmMemoryPolicy(
    val maxBytesPerMemory: Long = 256L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
    val maxMemories: Int = 16,
) {
    init {
        requireMemoryBytes("maxBytesPerMemory", maxBytesPerMemory)
        requireMemoryBytes("maxTotalBytes", maxTotalBytes)
        require(maxMemories > 0) { "maxMemories must be positive" }
        require(maxTotalBytes >= Memory.PAGE_SIZE.toLong()) {
            "maxTotalBytes must allow at least one WebAssembly page"
        }
    }

    internal val maxPagesPerMemory: Int
        get() = (maxBytesPerMemory / Memory.PAGE_SIZE.toLong()).toInt()

    internal val maxTotalPages: Long
        get() = maxTotalBytes / Memory.PAGE_SIZE.toLong()

    companion object {
        internal fun fromLegacyMaxBytes(maxMemoryBytes: Long): WasmMemoryPolicy =
            WasmMemoryPolicy(
                maxBytesPerMemory = maxMemoryBytes,
                maxTotalBytes = maxMemoryBytes,
                maxMemories = 1,
            )
    }
}

private fun requireMemoryBytes(name: String, bytes: Long) {
    require(bytes > 0) { "$name must be positive" }
    require(bytes % Memory.PAGE_SIZE.toLong() == 0L) {
        "$name must be a multiple of ${Memory.PAGE_SIZE}"
    }
    require(bytes / Memory.PAGE_SIZE.toLong() <= MemoryLimits.MAX_PAGES.toLong()) {
        "$name must not exceed ${MemoryLimits.MAX_PAGES} WebAssembly pages"
    }
}
