package uk.shusek.krwa.runtime.wasmtime.android

import uk.shusek.krwa.runtime.WasmtimeNativeTarget
import uk.shusek.krwa.runtime.WasmtimeModuleCompilerBuildIdentity
import uk.shusek.krwa.wasm.WasmEngineException

fun androidWasmtimeModuleCompilerUnavailableReason(
    target: String = WasmtimeNativeTarget,
    maxWasmStackBytes: Long = DefaultCompilerMaxWasmStackBytes,
): String? =
    AndroidWasmtimeModuleCompilerNative.compilerUnavailableReason(target, maxWasmStackBytes)

fun androidWasmtimeModuleCompilerIdentity(
    target: String = WasmtimeNativeTarget,
    maxWasmStackBytes: Long = DefaultCompilerMaxWasmStackBytes,
): String? {
    if (androidWasmtimeModuleCompilerUnavailableReason(target, maxWasmStackBytes) != null) return null
    return "android:$WasmtimeModuleCompilerBuildIdentity:target=$target:maxWasmStackBytes=$maxWasmStackBytes"
}

fun androidWasmtimeCompileModuleToCwasm(
    moduleBytes: ByteArray,
    target: String = WasmtimeNativeTarget,
    maxWasmStackBytes: Long = DefaultCompilerMaxWasmStackBytes,
): ByteArray =
    AndroidWasmtimeModuleCompilerNative.compileModuleToCwasm(moduleBytes, target, maxWasmStackBytes)

private object AndroidWasmtimeModuleCompilerNative {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("krwa_pulley_android")
    }.exceptionOrNull()

    fun compilerUnavailableReason(target: String, maxWasmStackBytes: Long): String? {
        loadError?.let { error ->
            return androidModuleCompilerLoadErrorMessage(error)
        }
        return nativeCompilerUnavailableReason(target, maxWasmStackBytes)
    }

    fun compileModuleToCwasm(moduleBytes: ByteArray, target: String, maxWasmStackBytes: Long): ByteArray {
        loadError?.let { error ->
            throw WasmEngineException(androidModuleCompilerLoadErrorMessage(error), error)
        }
        return nativeCompileModuleToCwasm(moduleBytes, target, maxWasmStackBytes)
    }

    @JvmStatic
    external fun nativeCompilerUnavailableReason(target: String, maxWasmStackBytes: Long): String?

    @JvmStatic
    external fun nativeCompileModuleToCwasm(moduleBytes: ByteArray, target: String, maxWasmStackBytes: Long): ByteArray
}

private const val DefaultCompilerMaxWasmStackBytes = 512L * 1024L

private fun androidModuleCompilerLoadErrorMessage(error: Throwable): String {
    val message = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName ?: "unknown error"
    return "Wasmtime module compiler is not linked on this Android runtime: $message"
}
