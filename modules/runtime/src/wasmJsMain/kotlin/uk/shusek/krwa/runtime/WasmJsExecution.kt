package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType

class WasmJsExecution
private constructor(
    private val nativeInstance: NativeWasmInstance,
) {
    fun export(name: String): ExportFunction =
        ExportFunction { args -> nativeInstance.export(name).apply(*args) }

    fun exportType(name: String): FunctionType = nativeInstance.exportType(name)

    fun memory(name: String): Memory = nativeInstance.memory(name)

    fun global(name: String): WasmJsGlobal = WasmJsGlobal(nativeInstance.global(name))

    fun table(name: String): WasmJsTable = WasmJsTable(nativeInstance.table(name))

    fun tag(name: String): WasmJsTag = WasmJsTag(nativeInstance.tag(name))

    fun native(): NativeWasmInstance = nativeInstance

    companion object {
        fun instantiate(module: WasmModule): WasmJsExecution =
            instantiate(module, NativeWasmImports.empty())

        fun instantiate(module: WasmModule, imports: ImportValues): WasmJsExecution =
            instantiate(module, NativeWasmImports.fromImportValues(imports))

        fun instantiate(module: WasmModule, nativeImports: NativeWasmImports): WasmJsExecution =
            WasmJsExecution(NativeWasmInstance.instantiate(module, nativeImports))

        @Deprecated(
            "wasmJs execution is native-only. Provide NativeWasmImports directly; extra ImportValues are ignored.",
        )
        fun instantiate(
            module: WasmModule,
            nativeImports: NativeWasmImports,
            extraImports: ImportValues,
        ): WasmJsExecution = instantiate(module, nativeImports)
    }
}

class WasmJsGlobal internal constructor(
    private val nativeGlobal: NativeWasmGlobal,
) {
    fun type(): ValType = nativeGlobal.type()

    fun mutability(): MutabilityType = nativeGlobal.mutability()

    fun value(): Long = nativeGlobal.value()

    fun setValue(value: Long) {
        nativeGlobal.setValue(value)
    }

    fun native(): NativeWasmGlobal = nativeGlobal
}

class WasmJsTable internal constructor(
    private val nativeTable: NativeWasmTable,
) {
    fun size(): Int = nativeTable.size()

    fun elementType(): ValType = nativeTable.elementType()

    fun limits(): TableLimits = nativeTable.limits()

    fun native(): NativeWasmTable = nativeTable
}

class WasmJsTag internal constructor(
    private val nativeTag: NativeWasmTag,
) {
    fun type(): FunctionType = nativeTag.type()

    fun native(): NativeWasmTag = nativeTag
}
