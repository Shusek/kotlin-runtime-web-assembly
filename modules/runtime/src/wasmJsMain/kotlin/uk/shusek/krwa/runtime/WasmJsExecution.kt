package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType

enum class WasmJsExecutionMode {
    AUTO,
    NATIVE,
    INTERPRETER,
}

enum class WasmJsBackend {
    NATIVE,
    INTERPRETER,
}

class WasmJsExecution
private constructor(
    val backend: WasmJsBackend,
    private val nativeInstance: NativeWasmInstance?,
    private val interpreterInstance: Instance?,
    val nativeFailure: Throwable?,
) {
    fun export(name: String): ExportFunction =
        nativeInstance?.let { native -> ExportFunction { args -> native.export(name).apply(*args) } }
            ?: interpreterInstance!!.export(name)

    fun exportType(name: String): FunctionType =
        nativeInstance?.exportType(name) ?: interpreterInstance!!.exportType(name)

    fun memory(name: String): Memory =
        nativeInstance?.memory(name) ?: interpreterInstance!!.exports().memory(name)

    fun global(name: String): WasmJsGlobal =
        nativeInstance?.let { WasmJsGlobal.native(it.global(name)) }
            ?: WasmJsGlobal.interpreter(interpreterInstance!!.exports().global(name))

    fun table(name: String): WasmJsTable =
        nativeInstance?.let { WasmJsTable.native(it.table(name)) }
            ?: WasmJsTable.interpreter(interpreterInstance!!.exports().table(name))

    fun tag(name: String): WasmJsTag =
        nativeInstance?.let { WasmJsTag.native(it.tag(name)) }
            ?: WasmJsTag.interpreter(interpreterInstance!!.exports().tag(name))

    fun nativeOrNull(): NativeWasmInstance? = nativeInstance

    fun interpreterOrNull(): Instance? = interpreterInstance

    companion object {
        fun instantiate(
            module: WasmModule,
            mode: WasmJsExecutionMode = WasmJsExecutionMode.AUTO,
        ): WasmJsExecution =
            instantiate(
                module = module,
                mode = mode,
                nativeImportsFactory = { NativeWasmImports.empty() },
                interpreterImports = ImportValues.empty(),
            )

        fun instantiate(
            module: WasmModule,
            imports: ImportValues,
            mode: WasmJsExecutionMode = WasmJsExecutionMode.AUTO,
        ): WasmJsExecution =
            instantiate(
                module = module,
                mode = mode,
                nativeImportsFactory = { NativeWasmImports.fromImportValues(imports) },
                interpreterImports = imports,
            )

        fun instantiate(
            module: WasmModule,
            nativeImports: NativeWasmImports,
            mode: WasmJsExecutionMode = WasmJsExecutionMode.AUTO,
        ): WasmJsExecution =
            instantiate(
                module = module,
                mode = mode,
                nativeImportsFactory = { nativeImports },
                interpreterImports = ImportValues.empty(),
            )

        fun instantiate(
            module: WasmModule,
            nativeImports: NativeWasmImports,
            interpreterImports: ImportValues,
            mode: WasmJsExecutionMode = WasmJsExecutionMode.AUTO,
        ): WasmJsExecution =
            instantiate(
                module = module,
                mode = mode,
                nativeImportsFactory = { nativeImports },
                interpreterImports = interpreterImports,
            )

        private fun instantiate(
            module: WasmModule,
            mode: WasmJsExecutionMode,
            nativeImportsFactory: () -> NativeWasmImports,
            interpreterImports: ImportValues,
        ): WasmJsExecution =
            when (mode) {
                WasmJsExecutionMode.NATIVE ->
                    native(module, nativeImportsFactory(), nativeFailure = null)
                WasmJsExecutionMode.INTERPRETER ->
                    interpreter(module, interpreterImports, nativeFailure = null)
                WasmJsExecutionMode.AUTO ->
                    if (!NativeWasmFeatures.available()) {
                        interpreter(module, interpreterImports, nativeFailure = null)
                    } else {
                        try {
                            native(module, nativeImportsFactory(), nativeFailure = null)
                        } catch (failure: Throwable) {
                            if (failure is NativeWasmRuntimeException) {
                                throw failure
                            }
                            interpreter(module, interpreterImports, nativeFailure = failure)
                        }
                    }
            }

        private fun native(
            module: WasmModule,
            imports: NativeWasmImports,
            nativeFailure: Throwable?,
        ): WasmJsExecution =
            WasmJsExecution(
                WasmJsBackend.NATIVE,
                NativeWasmInstance.instantiate(module, imports),
                null,
                nativeFailure,
            )

        private fun interpreter(
            module: WasmModule,
            imports: ImportValues,
            nativeFailure: Throwable?,
        ): WasmJsExecution =
            WasmJsExecution(
                WasmJsBackend.INTERPRETER,
                null,
                Instance.builder(module).withImportValues(imports).build(),
                nativeFailure,
            )
    }
}

class WasmJsGlobal
private constructor(
    private val nativeGlobal: NativeWasmGlobal?,
    private val interpreterGlobal: GlobalInstance?,
) {
    fun type(): ValType = nativeGlobal?.type() ?: interpreterGlobal!!.type

    fun mutability(): MutabilityType =
        nativeGlobal?.mutability() ?: interpreterGlobal!!.mutabilityType

    fun value(): Long = nativeGlobal?.value() ?: interpreterGlobal!!.value

    fun setValue(value: Long) {
        nativeGlobal?.let {
            it.setValue(value)
            return
        }
        val global = interpreterGlobal!!
        if (global.mutabilityType != MutabilityType.Var) {
            throw WasmEngineException("cannot set immutable WebAssembly global")
        }
        global.value = value
    }

    fun nativeOrNull(): NativeWasmGlobal? = nativeGlobal

    fun interpreterOrNull(): GlobalInstance? = interpreterGlobal

    internal companion object {
        fun native(global: NativeWasmGlobal): WasmJsGlobal = WasmJsGlobal(global, null)

        fun interpreter(global: GlobalInstance): WasmJsGlobal = WasmJsGlobal(null, global)
    }
}

class WasmJsTable
private constructor(
    private val nativeTable: NativeWasmTable?,
    private val interpreterTable: TableInstance?,
) {
    fun size(): Int = nativeTable?.size() ?: interpreterTable!!.size()

    fun elementType(): ValType = nativeTable?.elementType() ?: interpreterTable!!.elementType()

    fun limits(): TableLimits = nativeTable?.limits() ?: interpreterTable!!.limits()

    fun nativeOrNull(): NativeWasmTable? = nativeTable

    fun interpreterOrNull(): TableInstance? = interpreterTable

    internal companion object {
        fun native(table: NativeWasmTable): WasmJsTable = WasmJsTable(table, null)

        fun interpreter(table: TableInstance): WasmJsTable = WasmJsTable(null, table)
    }
}

class WasmJsTag
private constructor(
    private val nativeTag: NativeWasmTag?,
    private val interpreterTag: TagInstance?,
) {
    fun type(): FunctionType =
        nativeTag?.type()
            ?: interpreterTag!!.type()
            ?: throw WasmEngineException("WebAssembly tag type is not initialized")

    fun nativeOrNull(): NativeWasmTag? = nativeTag

    fun interpreterOrNull(): TagInstance? = interpreterTag

    internal companion object {
        fun native(tag: NativeWasmTag): WasmJsTag = WasmJsTag(tag, null)

        fun interpreter(tag: TagInstance): WasmJsTag = WasmJsTag(null, tag)
    }
}
