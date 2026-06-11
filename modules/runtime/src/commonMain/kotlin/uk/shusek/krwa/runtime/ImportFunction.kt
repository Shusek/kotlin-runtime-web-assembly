@file:Suppress("DEPRECATION")

package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.ValueType

open class ImportFunction : ImportValue {
    private val module: String
    private val name: String
    private val paramTypes: List<ValType>
    private val returnTypes: List<ValType>
    private val handle: WasmFunctionHandle?
    private val sourceInstance: Instance?

    constructor(
        module: String,
        name: String,
        type: FunctionType,
        handle: WasmFunctionHandle?,
    ) : this(module, name, type, handle, null)

    constructor(
        module: String,
        name: String,
        type: FunctionType,
        handle: WasmFunctionHandle?,
        sourceInstance: Instance?,
    ) {
        this.module = module
        this.name = name
        this.paramTypes = type.params()
        this.returnTypes = type.returns()
        this.handle = handle
        this.sourceInstance = sourceInstance
    }

    @Deprecated("Use ImportFunction(String, String, FunctionType, WasmFunctionHandle).")
    @Suppress("DEPRECATION")
    constructor(
        module: String,
        name: String,
        paramTypes: List<*>,
        returnTypes: List<*>,
        handle: WasmFunctionHandle?,
    ) : this(module, name, FunctionType.of(convert(paramTypes), convert(returnTypes)), handle)

    fun handle(): WasmFunctionHandle? = handle

    override fun module(): String = module

    override fun name(): String = name

    override fun type(): ImportValue.Type = ImportValue.Type.FUNCTION

    fun paramTypes(): List<ValType> = paramTypes

    fun returnTypes(): List<ValType> = returnTypes

    fun functionType(): FunctionType = FunctionType.of(paramTypes, returnTypes)

    fun sourceInstance(): Instance? = sourceInstance

    companion object {
        @Deprecated("Use FunctionType with ValType lists directly.")
        fun convert(objs: List<*>): List<ValType> {
            val result = ArrayList<ValType>(objs.size)
            for (value in objs) {
                when (value) {
                    is ValType -> result.add(value)
                    is ValueType -> result.add(value.toValType())
                    else ->
                        throw IllegalArgumentException(
                            "Expected ValueType or ValType, but got: " +
                                value
                        )
                }
            }
            return result
        }

        /**
         * Creates an [ImportFunction] that wraps an exported function from an instance, suitable
         * for linking into another module's imports via the store.
         *
         * @param moduleName the import module name to register under
         * @param fieldName the import field name to register under
         * @param instance the source instance that exports the function
         * @param exportName the name of the export in the source instance
         */
        fun exportAsImport(
            moduleName: String,
            fieldName: String,
            instance: Instance,
            exportName: String,
        ): ImportFunction {
            val function = instance.export(exportName)
            val functionType = instance.exportType(exportName)
            return ImportFunction(
                moduleName,
                fieldName,
                functionType,
                WasmFunctionHandle { _, args -> function.apply(*args) },
                instance,
            )
        }
    }
}
