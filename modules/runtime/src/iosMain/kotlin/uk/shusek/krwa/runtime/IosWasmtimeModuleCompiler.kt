@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.shusek.krwa.runtime

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_compile_module_to_cwasm
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_compiled_module_free
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_wasmtime_module_compiler_unavailable_reason
import uk.shusek.krwa.wasm.WasmEngineException

fun iosWasmtimeModuleCompilerUnavailableReason(
    target: String = WasmtimePulleyTarget,
    maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
): String? =
    krwa_wasmtime_module_compiler_unavailable_reason(target, maxWasmStackBytes.convert())?.toKString()

fun iosWasmtimeModuleCompilerIdentity(
    target: String = WasmtimePulleyTarget,
    maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
): String? {
    if (iosWasmtimeModuleCompilerUnavailableReason(target, maxWasmStackBytes) != null) return null
    return "ios:$WasmtimeModuleCompilerBuildIdentity:target=$target:maxWasmStackBytes=$maxWasmStackBytes"
}

fun iosWasmtimeCompileModuleToCwasm(
    moduleBytes: ByteArray,
    target: String = WasmtimePulleyTarget,
    maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
): ByteArray {
    require(moduleBytes.isNotEmpty()) { "module bytes must not be empty" }
    return memScoped {
        val resultOut = alloc<CPointerVar<UByteVar>>()
        val resultSizeOut = alloc<ULongVar>()
        resultOut.value = null
        resultSizeOut.value = 0u
        moduleBytes.usePinned { pinned ->
            val error = krwa_wasmtime_compile_module_to_cwasm(
                pinned.addressOf(0).reinterpret(),
                moduleBytes.size.convert(),
                target,
                maxWasmStackBytes.convert(),
                resultOut.ptr,
                resultSizeOut.ptr,
            )
            if (error != null) {
                throw WasmEngineException(error.toKString())
            }
        }
        val result = resultOut.value
            ?: throw WasmEngineException("Wasmtime module compiler returned a null result")
        val resultSize = resultSizeOut.value.toLong()
        try {
            check(resultSize in 1..Int.MAX_VALUE.toLong()) {
                "Wasmtime compiled module size is out of range: $resultSize"
            }
            ByteArray(resultSize.toInt()) { index -> result[index].toByte() }
        } finally {
            krwa_wasmtime_compiled_module_free(result)
        }
    }
}

private inline fun <T> ByteArray.usePinned(block: (Pinned<ByteArray>) -> T): T {
    val pinned = pin()
    try {
        return block(pinned)
    } finally {
        pinned.unpin()
    }
}
