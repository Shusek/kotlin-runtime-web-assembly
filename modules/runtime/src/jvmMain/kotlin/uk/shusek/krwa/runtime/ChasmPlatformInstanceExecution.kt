package uk.shusek.krwa.runtime

import io.github.charlietap.chasm.embedding.dsl.ValueTypeListBuilder
import io.github.charlietap.chasm.embedding.dsl.imports
import io.github.charlietap.chasm.embedding.error.ChasmError
import io.github.charlietap.chasm.embedding.invoke as chasmInvoke
import io.github.charlietap.chasm.embedding.instance as chasmInstance
import io.github.charlietap.chasm.embedding.module as chasmModule
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Instance as ChasmInstance
import io.github.charlietap.chasm.embedding.shapes.Store as ChasmStore
import io.github.charlietap.chasm.embedding.store as chasmStore
import io.github.charlietap.chasm.host.HostFunctionException
import io.github.charlietap.chasm.runtime.value.ExecutionValue
import io.github.charlietap.chasm.runtime.value.NumberValue
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

internal class ChasmPlatformInstanceExecution
private constructor(
    private val module: WasmModule,
    private val hostInstance: Instance,
    private val store: ChasmStore,
    private val instance: ChasmInstance,
) : PlatformInstanceExecution {
    override val backend: ExecutionBackend = ExecutionBackend.CHASM

    override fun export(name: String): ExportFunction {
        val type = exportType(name)
        return ExportFunction { args ->
            val result =
                chasmInvoke(store, instance, name, toChasmValues(args, type.params()))
                    .orThrow("invoke export '$name'")
            toKrwaValues(result, type.returns())
        }
    }

    override fun exportType(name: String): FunctionType {
        val export = functionExport(name)
        return hostInstance.type(hostInstance.functionType(export.index()))
    }

    override fun memory(name: String): Memory {
        memoryExport(name)
        throw WasmEngineException("Chasm backend does not expose KRWA Memory views yet")
    }

    override fun memory(index: Int): Memory? {
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            if (export.exportType() == ExternalType.MEMORY && export.index() == index) {
                throw WasmEngineException("Chasm backend does not expose KRWA Memory views yet")
            }
        }
        return null
    }

    private fun functionExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val export = findExport(name)
        if (export.exportType() != ExternalType.FUNCTION) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to ${ExternalType.FUNCTION}"
            )
        }
        return export
    }

    private fun memoryExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val export = findExport(name)
        if (export.exportType() != ExternalType.MEMORY) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to ${ExternalType.MEMORY}"
            )
        }
        return export
    }

    private fun findExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            if (export.name() == name) {
                return export
            }
        }
        throw InvalidException("Unknown export with name $name")
    }

    companion object {
        fun create(
            module: WasmModule,
            imports: ImportValues,
            hostInstance: Instance,
        ): PlatformInstanceExecution {
            requireSupportedImports(module, imports)

            val bytes =
                module.originalBytes()
                    ?: throw WasmEngineException("Chasm backend requires original Wasm module bytes")
            val store = chasmStore()
            val chasmImports =
                imports(store) {
                    for (i in 0 until imports.functionCount()) {
                        val function = imports.function(i)
                        val handle =
                            function.handle()
                                ?: throw WasmEngineException(
                                    "Chasm backend requires host function handles for ${function.module()}.${function.name()}"
                                )
                        function {
                            moduleName = function.module()
                            entityName = function.name()
                            type {
                                params { function.paramTypes().forEach { addKrwaType(it) } }
                                results { function.returnTypes().forEach { addKrwaType(it) } }
                            }
                            reference { values ->
                                try {
                                    val args = toKrwaValues(values, function.paramTypes())
                                    val result = handle.apply(hostInstance, args) ?: LongArray(0)
                                    toChasmValues(result, function.returnTypes())
                                } catch (failure: Exception) {
                                    throw HostFunctionException(
                                        failure.message ?: failure.javaClass.simpleName
                                    )
                                }
                            }
                        }
                    }
                }

            val decodedModule = chasmModule(bytes).orThrow("decode module")
            val chasmInstance =
                chasmInstance(store, decodedModule, chasmImports)
                    .orThrow("instantiate module")
            return ChasmPlatformInstanceExecution(module, hostInstance, store, chasmInstance)
        }

        private fun requireSupportedImports(
            module: WasmModule,
            imports: ImportValues,
        ) {
            val importSection = module.importSection()
            if (
                importSection.count(ExternalType.GLOBAL) != 0 ||
                    importSection.count(ExternalType.MEMORY) != 0 ||
                    importSection.count(ExternalType.TABLE) != 0 ||
                    importSection.count(ExternalType.TAG) != 0 ||
                    imports.globalCount() != 0 ||
                    imports.memoryCount() != 0 ||
                    imports.tableCount() != 0 ||
                    imports.tagCount() != 0
            ) {
                throw WasmEngineException(
                    "Chasm backend currently supports function imports only"
                )
            }
        }

        private fun ValueTypeListBuilder.addKrwaType(type: ValType) {
            when (type.opcode()) {
                ValType.ID.I32 -> i32()
                ValType.ID.I64 -> i64()
                ValType.ID.F32 -> f32()
                ValType.ID.F64 -> f64()
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }
        }

        private fun toChasmValues(
            values: LongArray,
            types: List<ValType>,
        ): List<ExecutionValue> {
            if (values.size != types.size) {
                throw WasmEngineException("Expected ${types.size} values, got ${values.size}")
            }
            return values.indices.map { idx -> toChasmValue(values[idx], types[idx]) }
        }

        private fun toChasmValue(
            value: Long,
            type: ValType,
        ): ExecutionValue =
            when (type.opcode()) {
                ValType.ID.I32 -> NumberValue.I32(value.toInt())
                ValType.ID.I64 -> NumberValue.I64(value)
                ValType.ID.F32 -> NumberValue.F32(Float.fromBits(value.toInt()))
                ValType.ID.F64 -> NumberValue.F64(Double.fromBits(value))
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }

        private fun toKrwaValues(
            values: List<ExecutionValue>,
            types: List<ValType>,
        ): LongArray {
            if (values.size != types.size) {
                throw WasmEngineException("Expected ${types.size} values, got ${values.size}")
            }
            return LongArray(values.size) { idx -> toKrwaValue(values[idx], types[idx]) }
        }

        private fun toKrwaValue(
            value: ExecutionValue,
            type: ValType,
        ): Long =
            when (type.opcode()) {
                ValType.ID.I32 ->
                    (value as? NumberValue.I32)?.value?.toLong()
                        ?: unexpectedValue(value, type)
                ValType.ID.I64 ->
                    (value as? NumberValue.I64)?.value
                        ?: unexpectedValue(value, type)
                ValType.ID.F32 ->
                    (value as? NumberValue.F32)?.value?.toRawBits()?.toLong()
                        ?: unexpectedValue(value, type)
                ValType.ID.F64 ->
                    (value as? NumberValue.F64)?.value?.toRawBits()
                        ?: unexpectedValue(value, type)
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }

        private fun unexpectedValue(
            value: ExecutionValue,
            type: ValType,
        ): Nothing =
            throw WasmEngineException("Expected Chasm value of type $type, got $value")

        private fun <S> ChasmResult<S, out ChasmError>.orThrow(action: String): S =
            when (this) {
                is ChasmResult.Success -> result
                is ChasmResult.Error ->
                    throw WasmEngineException("Chasm $action failed: ${error.error}")
            }
    }
}
