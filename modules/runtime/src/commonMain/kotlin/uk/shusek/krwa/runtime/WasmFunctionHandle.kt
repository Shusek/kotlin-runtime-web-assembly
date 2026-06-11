package uk.shusek.krwa.runtime

/** Represents a function that can be called from Wasm. */
fun interface WasmFunctionHandle {
    fun apply(instance: Instance, args: LongArray): LongArray?
}

fun WasmFunctionHandle.apply(instance: Instance, vararg args: Long): LongArray? =
    apply(instance, args)
