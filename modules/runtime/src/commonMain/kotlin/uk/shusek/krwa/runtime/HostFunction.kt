package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.FunctionType

/** A HostFunction is an ExternalFunction that has been defined by the host. */
open class HostFunction : ImportFunction {
    /** @deprecated use [HostFunction] with [FunctionType]. */
    @Deprecated("Use HostFunction(String, String, FunctionType, WasmFunctionHandle).")
    @Suppress("DEPRECATION")
    constructor(
        moduleName: String,
        symbolName: String,
        paramTypes: List<*>,
        returnTypes: List<*>,
        handle: WasmFunctionHandle?,
    ) : super(
        moduleName,
        symbolName,
        FunctionType.of(ImportFunction.convert(paramTypes), ImportFunction.convert(returnTypes)),
        handle,
    )

    constructor(
        moduleName: String,
        symbolName: String,
        type: FunctionType,
        handle: WasmFunctionHandle?,
    ) : super(moduleName, symbolName, type, handle)
}
