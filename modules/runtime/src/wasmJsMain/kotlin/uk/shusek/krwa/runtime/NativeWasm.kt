@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package uk.shusek.krwa.runtime

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.js
import kotlin.js.toJsNumber
import kotlin.js.unsafeCast
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.ArrayBufferView
import org.khronos.webgl.DataView
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.WasmWriter
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.GlobalImport
import uk.shusek.krwa.wasm.types.MemoryImport
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.TagImport
import uk.shusek.krwa.wasm.types.TableImport
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/**
 * Native browser WebAssembly execution path for Kotlin/Wasm browser builds.
 *
 * This API intentionally lives only in `wasmJsMain`: it delegates execution to the JavaScript
 * WebAssembly engine that the browser or Node runtime provides.
 */
class NativeWasmInstance
internal constructor(
    private val module: WasmModule,
    private val imports: NativeWasmImports,
    private val definedMemoryLimits: MemoryLimits? = null,
) {
    private lateinit var jsInstance: JsWebAssemblyInstance
    internal val references = NativeWasmReferences()
    private val exportsByName =
        buildMap {
            val exportSection = module.exportSection()
            for (i in 0 until exportSection.exportCount()) {
                val export = exportSection.getExport(i)
                put(export.name(), export)
            }
        }

    fun export(name: String): NativeWasmFunction {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.FUNCTION) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to FUNCTION"
            )
        }
        val jsFunction =
            getProperty(jsInstance.exports, name)
                ?: throw InvalidException("Native WebAssembly export $name is missing")
        return NativeWasmFunction(jsFunction, functionType(export.index()), references)
    }

    fun memory(name: String): NativeWasmMemory {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.MEMORY) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to MEMORY"
            )
        }
        val jsMemory =
            getProperty(jsInstance.exports, name)
                ?: throw InvalidException("Native WebAssembly memory export $name is missing")
        return NativeWasmMemory(jsMemory.unsafeCast(), memoryLimits(export.index()))
    }

    fun global(name: String): NativeWasmGlobal {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.GLOBAL) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to GLOBAL"
            )
        }
        val jsGlobal =
            getProperty(jsInstance.exports, name)
                ?: throw InvalidException("Native WebAssembly global export $name is missing")
        val type = globalType(export.index())
        return NativeWasmGlobal(jsGlobal.unsafeCast(), type.first, type.second, references)
    }

    fun table(name: String): NativeWasmTable {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.TABLE) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to TABLE"
            )
        }
        val jsTable =
            getProperty(jsInstance.exports, name)
                ?: throw InvalidException("Native WebAssembly table export $name is missing")
        val type = tableType(export.index())
        return NativeWasmTable(jsTable.unsafeCast(), type.first, type.second)
    }

    fun tag(name: String): NativeWasmTag {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.TAG) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to TAG"
            )
        }
        val jsTag =
            getProperty(jsInstance.exports, name)
                ?: throw InvalidException("Native WebAssembly tag export $name is missing")
        return NativeWasmTag(jsTag.unsafeCast(), tagType(export.index()))
    }

    fun exportType(name: String): FunctionType {
        val export =
            exportsByName[name]
                ?: throw InvalidException("Unknown export with name $name")
        if (export.exportType() != ExternalType.FUNCTION) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to FUNCTION"
            )
        }
        return functionType(export.index())
    }

    fun storeReference(value: JsAny?): Long = references.store(value)

    fun referenceValue(reference: Long): JsAny? = references.load(reference)

    internal fun bind(jsInstance: JsWebAssemblyInstance) {
        this.jsInstance = jsInstance
    }

    private fun functionType(functionIndex: Int): FunctionType {
        var importedFunctionIndex = 0
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val import = importSection.getImport(i)
            if (import.importType() == ExternalType.FUNCTION) {
                if (importedFunctionIndex == functionIndex) {
                    return module.typeSection().getType((import as FunctionImport).typeIndex())
                }
                importedFunctionIndex++
            }
        }
        val definedIndex = functionIndex - importedFunctionIndex
        if (definedIndex < 0 || definedIndex >= module.functionSection().functionCount()) {
            throw InvalidException("unknown function $functionIndex")
        }
        return module.functionSection().getFunctionType(definedIndex, module.typeSection())
    }

    private fun memoryLimits(memoryIndex: Int): MemoryLimits {
        var importedMemoryIndex = 0
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val import = importSection.getImport(i)
            if (import.importType() == ExternalType.MEMORY) {
                if (importedMemoryIndex == memoryIndex) {
                    return (import as MemoryImport).limits()
                }
                importedMemoryIndex++
            }
        }
        val definedIndex = memoryIndex - importedMemoryIndex
        val memorySection = module.memorySection()
        if (
            memorySection == null ||
                definedIndex < 0 ||
                definedIndex >= memorySection.memoryCount()
        ) {
            throw InvalidException("unknown memory $memoryIndex")
        }
        val declaredLimits = memorySection.getMemory(definedIndex).limits()
        return if (definedIndex == 0 && definedMemoryLimits != null) {
            declaredLimits.cappedBy(definedMemoryLimits)
        } else {
            declaredLimits
        }
    }

    private fun globalType(globalIndex: Int): Pair<ValType, MutabilityType> {
        var importedGlobalIndex = 0
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val import = importSection.getImport(i)
            if (import.importType() == ExternalType.GLOBAL) {
                if (importedGlobalIndex == globalIndex) {
                    val globalImport = import as GlobalImport
                    return globalImport.type() to globalImport.mutabilityType()
                }
                importedGlobalIndex++
            }
        }
        val definedIndex = globalIndex - importedGlobalIndex
        if (definedIndex < 0 || definedIndex >= module.globalSection().globalCount()) {
            throw InvalidException("unknown global $globalIndex")
        }
        val global = module.globalSection().getGlobal(definedIndex)
        return global.valueType() to global.mutabilityType()
    }

    private fun tableType(tableIndex: Int): Pair<ValType, TableLimits> {
        var importedTableIndex = 0
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val import = importSection.getImport(i)
            if (import.importType() == ExternalType.TABLE) {
                if (importedTableIndex == tableIndex) {
                    val tableImport = import as TableImport
                    return tableImport.entryType() to tableImport.limits()
                }
                importedTableIndex++
            }
        }
        val definedIndex = tableIndex - importedTableIndex
        if (definedIndex < 0 || definedIndex >= module.tableSection().tableCount()) {
            throw InvalidException("unknown table $tableIndex")
        }
        val table = module.tableSection().getTable(definedIndex)
        return table.elementType() to table.limits()
    }

    private fun tagType(tagIndex: Int): FunctionType {
        var importedTagIndex = 0
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val import = importSection.getImport(i)
            if (import.importType() == ExternalType.TAG) {
                if (importedTagIndex == tagIndex) {
                    return module.typeSection().getType((import as TagImport).tagType().typeIdx())
                }
                importedTagIndex++
            }
        }
        val definedIndex = tagIndex - importedTagIndex
        val tagSection = module.tagSection()
        if (tagSection == null || definedIndex < 0 || definedIndex >= tagSection.tagCount()) {
            throw InvalidException("unknown tag $tagIndex")
        }
        return module.typeSection().getType(tagSection.getTag(definedIndex).typeIdx())
    }

    companion object {
        fun instantiate(
            module: WasmModule,
            imports: NativeWasmImports = NativeWasmImports.empty(),
            memoryLimits: MemoryLimits? = null,
        ): NativeWasmInstance {
            val bytes =
                module.originalBytes()
                    ?: throw IllegalArgumentException(
                        "Native WebAssembly execution needs original module bytes. Parse the module from complete bytes with WasmParser.parse(bytes)."
                    )
            return instantiate(bytes, module, imports, memoryLimits)
        }

        fun instantiate(
            bytes: ByteArray,
            module: WasmModule,
            imports: NativeWasmImports = NativeWasmImports.empty(),
            memoryLimits: MemoryLimits? = null,
        ): NativeWasmInstance {
            validateImports(module, imports)
            val moduleBytes = bytes.withCappedDefinedMemory(memoryLimits)
            val nativeInstance = NativeWasmInstance(module, imports, memoryLimits)
            val compiledModule = compileWebAssemblyModule(moduleBytes.toUint8Array())
            val jsImports = imports.toJsImportObject(nativeInstance)
            nativeInstance.bind(instantiateWebAssemblyOrThrow(compiledModule, jsImports))
            return nativeInstance
        }

        private fun ByteArray.withCappedDefinedMemory(memoryLimits: MemoryLimits?): ByteArray {
            memoryLimits ?: return this
            if (memoryLimits.initialPages() > memoryLimits.maximumPages()) {
                throw NativeWasmRuntimeException("native WebAssembly memory limit is smaller than the initial memory size")
            }
            if (size < WasmHeaderSize || !startsWithWasmHeader()) {
                throw NativeWasmRuntimeException("native WebAssembly execution needs a complete module")
            }
            val writer = WasmWriter()
            var offset = WasmHeaderSize
            var rewritten = false
            while (offset < size) {
                val sectionId = this[offset++].toInt() and ByteMask
                val sectionSize = readVarUInt32(offset)
                offset = sectionSize.nextOffset
                val sectionStart = offset
                val sectionEnd = sectionStart + sectionSize.value
                if (sectionEnd > size) {
                    throw NativeWasmRuntimeException("native WebAssembly section length out of bounds")
                }
                val contents = copyOfRange(sectionStart, sectionEnd)
                writer.writeSection(
                    sectionId,
                    if (sectionId == WasmMemorySectionId) {
                        rewritten = true
                        contents.withCappedFirstMemory(memoryLimits)
                    } else {
                        contents
                    },
                )
                offset = sectionEnd
            }
            return if (rewritten) writer.bytes() else this
        }

        private fun ByteArray.withCappedFirstMemory(memoryLimits: MemoryLimits): ByteArray {
            var offset = 0
            val memoryCount = readVarUInt32(offset)
            offset = memoryCount.nextOffset
            if (memoryCount.value == 0) {
                return this
            }

            val out = Buffer()
            WasmWriter.writeVarUInt32(out, memoryCount.value)
            for (index in 0 until memoryCount.value) {
                val flags = readByteAt(offset++)
                flags.requireSupportedMemoryLimitsFlags()
                val initial = readVarUInt32(offset)
                offset = initial.nextOffset
                val declaredMaximum =
                    if (flags == MemoryLimitHasMaximum || flags == MemoryLimitSharedHasMaximum) {
                        val maximum = readVarUInt32(offset)
                        offset = maximum.nextOffset
                        maximum.value
                    } else {
                        MemoryLimits.MAX_PAGES
                    }

                if (index == 0) {
                    val cappedMaximum = minOf(declaredMaximum, memoryLimits.maximumPages())
                    if (initial.value > cappedMaximum) {
                        throw NativeWasmRuntimeException(
                            "native WebAssembly memory initial pages ${initial.value} exceed host limit $cappedMaximum"
                        )
                    }
                    out.writeByte(
                        if (flags == MemoryLimitSharedHasMaximum) {
                            MemoryLimitSharedHasMaximum
                        } else {
                            MemoryLimitHasMaximum
                        }.toByte(),
                    )
                    WasmWriter.writeVarUInt32(out, initial.value)
                    WasmWriter.writeVarUInt32(out, cappedMaximum)
                } else {
                    out.writeByte(flags.toByte())
                    WasmWriter.writeVarUInt32(out, initial.value)
                    if (flags == MemoryLimitHasMaximum || flags == MemoryLimitSharedHasMaximum) {
                        WasmWriter.writeVarUInt32(out, declaredMaximum)
                    }
                }
            }
            if (offset != size) {
                throw NativeWasmRuntimeException("native WebAssembly memory section has trailing bytes")
            }
            return out.readByteArray()
        }

        private fun ByteArray.startsWithWasmHeader(): Boolean =
            WasmParser.MAGIC_BYTES.indices.all { index -> this[index] == WasmParser.MAGIC_BYTES[index] } &&
                WasmParser.VERSION_BYTES.indices.all { index ->
                    this[WasmParser.MAGIC_BYTES.size + index] == WasmParser.VERSION_BYTES[index]
                }

        private fun ByteArray.readVarUInt32(offset: Int): VarUInt32 {
            var value = 0
            var shift = 0
            var currentOffset = offset
            while (true) {
                if (currentOffset >= size || shift > MaxVarUInt32Shift) {
                    throw NativeWasmRuntimeException("native WebAssembly varuint32 out of bounds")
                }
                val byte = readByteAt(currentOffset++)
                value = value or ((byte and VarUIntPayloadMask) shl shift)
                if ((byte and VarUIntContinuationMask) == 0) {
                    return VarUInt32(value, currentOffset)
                }
                shift += VarUIntShift
            }
        }

        private fun ByteArray.readByteAt(offset: Int): Int {
            if (offset !in indices) {
                throw NativeWasmRuntimeException("native WebAssembly byte out of bounds")
            }
            return this[offset].toInt() and ByteMask
        }

        private fun Int.requireSupportedMemoryLimitsFlags() {
            if (this != MemoryLimitNoMaximum &&
                this != MemoryLimitHasMaximum &&
                this != MemoryLimitSharedHasMaximum
            ) {
                throw NativeWasmRuntimeException("native WebAssembly memory has unsupported limits flags $this")
            }
        }

        private data class VarUInt32(val value: Int, val nextOffset: Int)

        private fun validateImports(module: WasmModule, imports: NativeWasmImports) {
            val importSection = module.importSection()
            for (i in 0 until importSection.importCount()) {
                val import = importSection.getImport(i)
                try {
                    when (import.importType()) {
                        ExternalType.FUNCTION ->
                            validateFunctionImport(module, import as FunctionImport, imports)
                        ExternalType.MEMORY ->
                            validateMemoryImport(import as MemoryImport, imports)
                        ExternalType.GLOBAL ->
                            validateGlobalImport(import as GlobalImport, imports)
                        ExternalType.TABLE ->
                            validateTableImport(import as TableImport, imports)
                        ExternalType.TAG ->
                            validateTagImport(module, import as TagImport, imports)
                    }
                } catch (e: UnlinkableException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw UnlinkableException(
                        "incompatible native import number: $i named ${import.module()}.${import.name()}",
                        e,
                    )
                }
            }
        }

        private fun unknownImport(indexName: String): Nothing =
            throw UnlinkableException("unknown native import, could not find import named $indexName")

        private fun validateFunctionImport(
            module: WasmModule,
            import: FunctionImport,
            imports: NativeWasmImports,
        ) {
            val functionType =
                imports.functionType(import.module(), import.name())
                    ?: unknownImport("${import.module()}.${import.name()}")
            val expectedType = module.typeSection().getType(import.typeIndex())
            if (functionType != expectedType) {
                throw UnlinkableException(
                    "incompatible native import type for function ${import.module()}.${import.name()}"
                )
            }
        }

        private fun validateMemoryImport(import: MemoryImport, imports: NativeWasmImports) {
            val memory =
                imports.memory(import.module(), import.name())
                    ?: unknownImport("${import.module()}.${import.name()}")
            val importMaxPages =
                if (import.limits().maximumPages() == MemoryLimits.MAX_PAGES) {
                    Memory.RUNTIME_MAX_PAGES
                } else {
                    import.limits().maximumPages()
                }
            if (
                memory.pages() < import.limits().initialPages() ||
                    memory.maximumPages() > importMaxPages ||
                    memory.shared() != import.limits().shared()
            ) {
                throw UnlinkableException(
                    "incompatible native import type for memory ${import.module()}.${import.name()}"
                )
            }
        }

        private fun validateGlobalImport(import: GlobalImport, imports: NativeWasmImports) {
            val global =
                imports.global(import.module(), import.name())
                    ?: unknownImport("${import.module()}.${import.name()}")
            val typesMatch =
                when (import.mutabilityType()) {
                    MutabilityType.Var -> import.type() == global.type()
                    MutabilityType.Const -> ValType.matches(global.type(), import.type())
                }
            if (!typesMatch || import.mutabilityType() != global.mutability()) {
                throw UnlinkableException(
                    "incompatible native import type for global ${import.module()}.${import.name()}"
                )
            }
        }

        private fun validateTableImport(import: TableImport, imports: NativeWasmImports) {
            val table =
                imports.table(import.module(), import.name())
                    ?: unknownImport("${import.module()}.${import.name()}")
            if (
                import.entryType() != table.elementType() ||
                    table.limits().min() < import.limits().min() ||
                    table.limits().max() > import.limits().max() ||
                    table.limits().shared() != import.limits().shared()
            ) {
                throw UnlinkableException(
                    "incompatible native import type for table ${import.module()}.${import.name()}"
                )
            }
        }

        private fun validateTagImport(
            module: WasmModule,
            import: TagImport,
            imports: NativeWasmImports,
        ) {
            val tag =
                imports.tag(import.module(), import.name())
                    ?: unknownImport("${import.module()}.${import.name()}")
            val expectedType = module.typeSection().getType(import.tagType().typeIdx())
            if (tag.type() != expectedType) {
                throw UnlinkableException(
                    "incompatible native import type for tag ${import.module()}.${import.name()}"
                )
            }
        }
    }
}

private fun MemoryLimits.cappedBy(cap: MemoryLimits): MemoryLimits =
    MemoryLimits(initialPages(), minOf(maximumPages(), cap.maximumPages()), shared())

class NativeWasmRuntimeException(message: String) : WasmEngineException(message)

/**
 * Runtime feature checks for the host JavaScript WebAssembly implementation.
 *
 * Browser availability can depend on deployment headers and engine version, especially for shared
 * memory, exception tags, and newer GC reference descriptors.
 */
object NativeWasmFeatures {
    fun available(): Boolean = hasNativeWebAssembly()

    fun supportsSharedMemory(): Boolean = supportsSharedWebAssemblyMemory()

    fun supportsExceptionTags(): Boolean = supportsWebAssemblyTags()

    fun supportsValueType(type: ValType): Boolean {
        val descriptor = wasmValueTypeOrNull(type) ?: return false
        return supportsWebAssemblyGlobalValueType(descriptor)
    }

    fun supportsTableElement(type: ValType): Boolean {
        val descriptor = wasmReferenceValueType(type) ?: return false
        return supportsWebAssemblyTableElement(descriptor)
    }

    fun supportsTag(type: FunctionType): Boolean =
        supportsExceptionTags() &&
            type.returns().isEmpty() &&
            type.params().all { supportsValueType(it) }
}

class NativeWasmFunction
internal constructor(
    private val jsFunction: JsAny,
    private val type: FunctionType,
    private val references: NativeWasmReferences,
) {
    fun apply(vararg args: Long): LongArray {
        if (args.size != type.params().size) {
            throw WasmEngineException(
                "wrong argument count, expected ${type.params().size}, got ${args.size}"
            )
        }
        val jsArgs = JsArray<JsAny?>()
        for (i in args.indices) {
            jsArgs[i] = rawValueToJs(type.params()[i], args[i], references)
        }
        val result = callFunction(jsFunction, jsArgs)
        return jsResultToRawValues(type.returns(), result, references)
    }
}

fun interface NativeWasmHostFunction {
    fun apply(instance: NativeWasmInstance, args: LongArray): LongArray?
}

class NativeWasmImports
private constructor(
    private val functions: List<FunctionBinding>,
    private val memories: List<MemoryBinding>,
    private val globals: List<GlobalBinding>,
    private val tables: List<TableBinding>,
    private val tags: List<TagBinding>,
) {
    fun function(module: String, name: String): NativeWasmHostFunction? =
        functions.firstOrNull { it.module == module && it.name == name }?.function

    fun memory(module: String, name: String): NativeWasmMemory? =
        memories.firstOrNull { it.module == module && it.name == name }?.memory

    fun global(module: String, name: String): NativeWasmGlobal? =
        globals.firstOrNull { it.module == module && it.name == name }?.global

    fun table(module: String, name: String): NativeWasmTable? =
        tables.firstOrNull { it.module == module && it.name == name }?.table

    fun tag(module: String, name: String): NativeWasmTag? =
        tags.firstOrNull { it.module == module && it.name == name }?.tag

    internal fun functionType(module: String, name: String): FunctionType? =
        functions.firstOrNull { it.module == module && it.name == name }?.type()

    internal fun toJsImportObject(instance: NativeWasmInstance): JsAny {
        val root = emptyObject()
        for (binding in functions) {
            val moduleObject = ensureImportModule(root, binding.module)
            setProperty(moduleObject, binding.name, binding.toJsFunction(instance))
        }
        for (binding in memories) {
            val moduleObject = ensureImportModule(root, binding.module)
            setProperty(moduleObject, binding.name, binding.memory.jsMemory)
        }
        for (binding in globals) {
            val moduleObject = ensureImportModule(root, binding.module)
            setProperty(moduleObject, binding.name, binding.global.jsGlobal)
        }
        for (binding in tables) {
            val moduleObject = ensureImportModule(root, binding.module)
            setProperty(moduleObject, binding.name, binding.table.jsTable)
        }
        for (binding in tags) {
            val moduleObject = ensureImportModule(root, binding.module)
            setProperty(moduleObject, binding.name, binding.tag.jsTag)
        }
        return root
    }

    class Builder {
        private val functions = ArrayList<FunctionBinding>()
        private val memories = ArrayList<MemoryBinding>()
        private val globals = ArrayList<GlobalBinding>()
        private val tables = ArrayList<TableBinding>()
        private val tags = ArrayList<TagBinding>()

        fun addFunction(
            module: String,
            name: String,
            type: FunctionType,
            function: NativeWasmHostFunction,
        ): Builder {
            functions.add(FunctionBinding(module, name, type, function))
            return this
        }

        fun addFunction(
            function: ImportFunction,
            instance: Instance? = function.sourceInstance(),
        ): Builder {
            val handle =
                function.handle()
                    ?: throw IllegalArgumentException(
                        "native WebAssembly import ${function.module()}.${function.name()} has no function handle"
                    )
            return addFunction(
                function.module(),
                function.name(),
                function.functionType(),
                NativeWasmHostFunction { _, args ->
                    val hostInstance =
                        instance
                            ?: throw WasmEngineException(
                                "native WebAssembly import ${function.module()}.${function.name()} needs a KRWA Instance. " +
                                    "Pass one to addFunction or use NativeWasmHostFunction."
                            )
                    handle.apply(hostInstance, args)
                },
            )
        }

        fun addImportValues(
            importValues: ImportValues,
            instance: Instance? = null,
        ): Builder {
            for (i in 0 until importValues.functionCount()) {
                val function = importValues.function(i)
                addFunction(function, function.sourceInstance() ?: instance)
            }
            for (i in 0 until importValues.memoryCount()) {
                val memory = importValues.memory(i)
                addMemory(memory)
            }
            rejectCommonNativeImports(importValues)
            return this
        }

        fun addMemory(
            module: String,
            name: String,
            memory: NativeWasmMemory,
        ): Builder {
            memories.add(MemoryBinding(module, name, memory))
            return this
        }

        fun addMemory(memory: ImportMemory): Builder {
            val nativeMemory =
                memory.memory() as? NativeWasmMemory
                    ?: throw IllegalArgumentException(
                        "native WebAssembly import ${memory.module()}.${memory.name()} needs NativeWasmMemory"
                    )
            return addMemory(memory.module(), memory.name(), nativeMemory)
        }

        fun addGlobal(
            module: String,
            name: String,
            global: NativeWasmGlobal,
        ): Builder {
            globals.add(GlobalBinding(module, name, global))
            return this
        }

        fun addTable(
            module: String,
            name: String,
            table: NativeWasmTable,
        ): Builder {
            tables.add(TableBinding(module, name, table))
            return this
        }

        fun addTag(
            module: String,
            name: String,
            tag: NativeWasmTag,
        ): Builder {
            tags.add(TagBinding(module, name, tag))
            return this
        }

        fun build(): NativeWasmImports =
            NativeWasmImports(
                functions.toList(),
                memories.toList(),
                globals.toList(),
                tables.toList(),
                tags.toList(),
            )

        private fun rejectCommonNativeImports(importValues: ImportValues) {
            if (importValues.globalCount() > 0) {
                val global = importValues.global(0)
                throw IllegalArgumentException(
                    "native WebAssembly imports need NativeWasmGlobal for ${global.module()}.${global.name()}"
                )
            }
            if (importValues.tableCount() > 0) {
                val table = importValues.table(0)
                throw IllegalArgumentException(
                    "native WebAssembly imports need NativeWasmTable for ${table.module()}.${table.name()}"
                )
            }
            if (importValues.tagCount() > 0) {
                val tag = importValues.tag(0)
                throw IllegalArgumentException(
                    "native WebAssembly imports need NativeWasmTag for ${tag.module()}.${tag.name()}"
                )
            }
        }
    }

    internal class FunctionBinding(
        val module: String,
        val name: String,
        private val type: FunctionType,
        val function: NativeWasmHostFunction,
    ) {
        fun type(): FunctionType = type

        fun toJsFunction(instance: NativeWasmInstance): JsAny =
            hostFunction { jsArgs ->
                val args = jsArgsToRawValues(type.params(), jsArgs, instance.references)
                val result = function.apply(instance, args) ?: LongArray(0)
                rawResultsToJs(type.returns(), result, instance.references)
            }
    }

    private class MemoryBinding(
        val module: String,
        val name: String,
        val memory: NativeWasmMemory,
    )

    private class GlobalBinding(
        val module: String,
        val name: String,
        val global: NativeWasmGlobal,
    )

    private class TableBinding(
        val module: String,
        val name: String,
        val table: NativeWasmTable,
    )

    private class TagBinding(
        val module: String,
        val name: String,
        val tag: NativeWasmTag,
    )

    companion object {
        fun builder(): Builder = Builder()

        fun empty(): NativeWasmImports = Builder().build()

        fun fromImportValues(
            importValues: ImportValues,
            instance: Instance? = null,
        ): NativeWasmImports =
            builder()
                .addImportValues(importValues, instance)
                .build()
    }
}

class NativeWasmTag
internal constructor(
    internal val jsTag: JsWebAssemblyTag,
    private val type: FunctionType,
) {
    fun type(): FunctionType = type

    fun throwException(instance: NativeWasmInstance, vararg args: Long): Nothing {
        if (args.size != type.params().size) {
            throw WasmEngineException(
                "wrong exception argument count, expected ${type.params().size}, got ${args.size}"
            )
        }
        val jsArgs = JsArray<JsAny?>()
        for (i in args.indices) {
            jsArgs[i] = rawValueToJs(type.params()[i], args[i], instance.references)
        }
        throwWebAssemblyException(jsTag, jsArgs)
    }

    companion object {
        fun create(type: FunctionType): NativeWasmTag =
            NativeWasmTag(createWebAssemblyTag(wasmTagParameters(type)), type)
    }
}

class NativeWasmTable
internal constructor(
    internal val jsTable: JsWebAssemblyTable,
    private val type: ValType,
    private val limits: TableLimits,
) {
    fun size(): Int = getTableLength(jsTable)

    fun get(index: Int): JsAny? = getTableValue(jsTable, index)

    fun set(index: Int, value: JsAny?) {
        setTableValue(jsTable, index, value)
    }

    fun grow(size: Int, value: JsAny? = null): Int =
        try {
            growTable(jsTable, size, value)
        } catch (_: Throwable) {
            -1
        }

    fun elementType(): ValType = type

    fun limits(): TableLimits = limits

    companion object {
        fun create(type: ValType, limits: TableLimits): NativeWasmTable =
            NativeWasmTable(
                createWebAssemblyTable(
                    wasmTableElement(type),
                    limits.min().toInt(),
                    if (limits.max() == TableLimits.LIMIT_MAX) {
                        -1
                    } else {
                        limits.max().toInt()
                    },
                ),
                type,
                limits,
            )
    }
}

class NativeWasmGlobal
internal constructor(
    internal val jsGlobal: JsWebAssemblyGlobal,
    private val type: ValType,
    private val mutability: MutabilityType,
    private val references: NativeWasmReferences = NativeWasmReferences(),
) {
    fun type(): ValType = type

    fun mutability(): MutabilityType = mutability

    fun value(): Long = jsValueToRaw(type, getGlobalValue(jsGlobal), references)

    fun jsValue(): JsAny? = getGlobalValue(jsGlobal)

    fun setValue(value: Long) {
        if (mutability != MutabilityType.Var) {
            throw WasmEngineException("cannot set immutable native WebAssembly global")
        }
        setGlobalValue(jsGlobal, rawValueToJs(type, value, references))
    }

    fun setJsValue(value: JsAny?) {
        if (mutability != MutabilityType.Var) {
            throw WasmEngineException("cannot set immutable native WebAssembly global")
        }
        if (!isBridgeableReferenceType(type)) {
            throw WasmEngineException("$type is not a native WebAssembly reference global")
        }
        setGlobalValue(jsGlobal, value)
    }

    companion object {
        fun create(
            type: ValType,
            mutability: MutabilityType,
            value: Long,
        ): NativeWasmGlobal {
            val references = NativeWasmReferences()
            return NativeWasmGlobal(
                createWebAssemblyGlobal(
                    wasmValueType(type),
                    mutability == MutabilityType.Var,
                    rawValueToJs(type, value, references),
                ),
                type,
                mutability,
                references,
            )
        }

        fun createReference(
            type: ValType,
            mutability: MutabilityType,
            value: JsAny?,
        ): NativeWasmGlobal {
            if (!isBridgeableReferenceType(type)) {
                throw WasmEngineException("$type is not a native WebAssembly reference global")
            }
            return NativeWasmGlobal(
                createWebAssemblyGlobal(
                    wasmValueType(type),
                    mutability == MutabilityType.Var,
                    value,
                ),
                type,
                mutability,
            )
        }
    }
}

@Suppress("OVERRIDE_DEPRECATION")
class NativeWasmMemory
internal constructor(
    internal val jsMemory: JsWebAssemblyMemory,
    private val limits: MemoryLimits,
) : Memory {
    private val maximumPages =
        if (limits.maximumPages() == MemoryLimits.MAX_PAGES) {
            Memory.RUNTIME_MAX_PAGES
        } else {
            minOf(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES)
        }

    override fun pages(): Int = jsMemory.buffer.byteLength / Memory.PAGE_SIZE

    override fun grow(size: Int): Int =
        try {
            jsMemory.grow(size)
        } catch (_: Throwable) {
            -1
        }

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = maximumPages

    override fun shared(): Boolean = limits.shared()

    override fun lock(address: Int): Any {
        throw UnsupportedOperationException("NativeWasmMemory does not expose address locks.")
    }

    override fun waitOn(address: Int, expected: Int, timeout: Long): Int {
        if (!shared()) {
            throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
        }
        checkAtomicAccess(address, Int.SIZE_BYTES)
        return nativeAtomics {
            atomicWaitI32(jsMemory.buffer, address, expected, timeout)
        }
    }

    override fun waitOn(address: Int, expected: Long, timeout: Long): Int {
        if (!shared()) {
            throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
        }
        checkAtomicAccess(address, Long.SIZE_BYTES)
        return nativeAtomics {
            atomicWaitI64(jsMemory.buffer, address, expected, timeout)
        }
    }

    override fun notify(address: Int, maxThreads: Int): Int {
        if (!shared()) {
            return 0
        }
        checkAtomicAccess(address, Int.SIZE_BYTES)
        return nativeAtomics {
            atomicNotify(jsMemory.buffer, address, maxThreads)
        }
    }

    override fun initialize(
        instance: Instance,
        dataSegments: Array<uk.shusek.krwa.wasm.types.DataSegment>?,
    ) {
        // Native WebAssembly instantiation applies active data segments.
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        throw UnsupportedOperationException(
            "Passive segment initialization is owned by the native WebAssembly instance."
        )
    }

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        checkBounds(offset, size, data.size)
        checkBounds(addr, size, sizeInBytes())
        val view = Uint8Array(jsMemory.buffer, addr, size)
        for (i in 0 until size) {
            view[i] = data[offset + i]
        }
    }

    override fun read(addr: Int): Byte {
        checkBounds(addr, 1, sizeInBytes())
        return Uint8Array(jsMemory.buffer, addr, 1)[0]
    }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkBounds(addr, len, sizeInBytes())
        val view = Uint8Array(jsMemory.buffer, addr, len)
        return ByteArray(len) { i -> view[i] }
    }

    override fun writeI32(addr: Int, data: Int) {
        view().setInt32(addr, data, true)
    }

    override fun readInt(addr: Int): Int = view().getInt32(addr, true)

    override fun writeLong(addr: Int, data: Long) {
        writeI32(addr, data.toInt())
        writeI32(addr + 4, (data ushr 32).toInt())
    }

    override fun readLong(addr: Int): Long {
        val low = readInt(addr).toLong() and 0xFFFF_FFFFL
        val high = readInt(addr + 4).toLong()
        return low or (high shl 32)
    }

    override fun writeShort(addr: Int, data: Short) {
        view().setInt16(addr, data, true)
    }

    override fun readShort(addr: Int): Short = view().getInt16(addr, true)

    override fun readU16(addr: Int): Long = view().getUint16(addr, true).toLong() and 0xFFFFL

    override fun writeByte(addr: Int, data: Byte) {
        view().setInt8(addr, data)
    }

    override fun writeF32(addr: Int, data: Float) {
        view().setFloat32(addr, data, true)
    }

    override fun readF32(addr: Int): Long = readFloat(addr).toRawBits().toLong()

    override fun readFloat(addr: Int): Float = view().getFloat32(addr, true)

    override fun writeF64(addr: Int, data: Double) {
        view().setFloat64(addr, data, true)
    }

    override fun readDouble(addr: Int): Double = view().getFloat64(addr, true)

    override fun readF64(addr: Int): Long = readDouble(addr).toRawBits()

    override fun zero() {
        fill(0, 0, sizeInBytes())
    }

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        checkBounds(fromIndex, toIndex - fromIndex, sizeInBytes())
        val view = Uint8Array(jsMemory.buffer, fromIndex, toIndex - fromIndex)
        for (i in 0 until view.length) {
            view[i] = value
        }
    }

    override fun drop(segment: Int) {
        throw UnsupportedOperationException(
            "Data segment lifecycle is owned by the native WebAssembly instance."
        )
    }

    override fun atomicReadInt(addr: Int): Int {
        if (!shared()) {
            return readInt(addr)
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicLoadI32(jsMemory.buffer, addr) }
    }

    override fun atomicReadLong(addr: Int): Long {
        if (!shared()) {
            return readLong(addr)
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicLoadI64(jsMemory.buffer, addr) }
    }

    override fun atomicReadShort(addr: Int): Short {
        if (!shared()) {
            return readShort(addr)
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicLoadI16(jsMemory.buffer, addr).toShort() }
    }

    override fun atomicReadByte(addr: Int): Byte {
        if (!shared()) {
            return read(addr)
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicLoadI8(jsMemory.buffer, addr).toByte() }
    }

    override fun atomicWriteInt(addr: Int, value: Int) {
        if (!shared()) {
            writeI32(addr, value)
            return
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        nativeAtomics { atomicStoreI32(jsMemory.buffer, addr, value) }
    }

    override fun atomicWriteLong(addr: Int, value: Long) {
        if (!shared()) {
            writeLong(addr, value)
            return
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        nativeAtomics { atomicStoreI64(jsMemory.buffer, addr, value) }
    }

    override fun atomicWriteShort(addr: Int, value: Short) {
        if (!shared()) {
            writeShort(addr, value)
            return
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        nativeAtomics { atomicStoreI16(jsMemory.buffer, addr, value.toInt()) }
    }

    override fun atomicWriteByte(addr: Int, value: Byte) {
        if (!shared()) {
            writeByte(addr, value)
            return
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        nativeAtomics { atomicStoreI8(jsMemory.buffer, addr, value.toInt()) }
    }

    override fun atomicAddInt(addr: Int, delta: Int): Int {
        if (!shared()) {
            return updateInt(addr) { it + delta }
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicAddI32(jsMemory.buffer, addr, delta) }
    }

    override fun atomicAndInt(addr: Int, mask: Int): Int {
        if (!shared()) {
            return updateInt(addr) { it and mask }
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicAndI32(jsMemory.buffer, addr, mask) }
    }

    override fun atomicOrInt(addr: Int, mask: Int): Int {
        if (!shared()) {
            return updateInt(addr) { it or mask }
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicOrI32(jsMemory.buffer, addr, mask) }
    }

    override fun atomicXorInt(addr: Int, mask: Int): Int {
        if (!shared()) {
            return updateInt(addr) { it xor mask }
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicXorI32(jsMemory.buffer, addr, mask) }
    }

    override fun atomicXchgInt(addr: Int, value: Int): Int {
        if (!shared()) {
            return updateInt(addr) { value }
        }
        checkAtomicAccess(addr, Int.SIZE_BYTES)
        return nativeAtomics { atomicExchangeI32(jsMemory.buffer, addr, value) }
    }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int {
        if (shared()) {
            checkAtomicAccess(addr, Int.SIZE_BYTES)
            return nativeAtomics {
                atomicCompareExchangeI32(jsMemory.buffer, addr, expected, replacement)
            }
        }
        val old = readInt(addr)
        if (old == expected) {
            writeI32(addr, replacement)
        }
        return old
    }

    override fun atomicAddLong(addr: Int, delta: Long): Long {
        if (!shared()) {
            return updateLong(addr) { it + delta }
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicAddI64(jsMemory.buffer, addr, delta) }
    }

    override fun atomicAndLong(addr: Int, mask: Long): Long {
        if (!shared()) {
            return updateLong(addr) { it and mask }
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicAndI64(jsMemory.buffer, addr, mask) }
    }

    override fun atomicOrLong(addr: Int, mask: Long): Long {
        if (!shared()) {
            return updateLong(addr) { it or mask }
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicOrI64(jsMemory.buffer, addr, mask) }
    }

    override fun atomicXorLong(addr: Int, mask: Long): Long {
        if (!shared()) {
            return updateLong(addr) { it xor mask }
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicXorI64(jsMemory.buffer, addr, mask) }
    }

    override fun atomicXchgLong(addr: Int, value: Long): Long {
        if (!shared()) {
            return updateLong(addr) { value }
        }
        checkAtomicAccess(addr, Long.SIZE_BYTES)
        return nativeAtomics { atomicExchangeI64(jsMemory.buffer, addr, value) }
    }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long {
        if (shared()) {
            checkAtomicAccess(addr, Long.SIZE_BYTES)
            return nativeAtomics {
                atomicCompareExchangeI64(jsMemory.buffer, addr, expected, replacement)
            }
        }
        val old = readLong(addr)
        if (old == expected) {
            writeLong(addr, replacement)
        }
        return old
    }

    override fun atomicAddShort(addr: Int, delta: Short): Short {
        if (!shared()) {
            return updateShort(addr) { (it + delta).toShort() }
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicAddI16(jsMemory.buffer, addr, delta.toInt()).toShort() }
    }

    override fun atomicAndShort(addr: Int, mask: Short): Short {
        if (!shared()) {
            return updateShort(addr) { (it.toInt() and mask.toInt()).toShort() }
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicAndI16(jsMemory.buffer, addr, mask.toInt()).toShort() }
    }

    override fun atomicOrShort(addr: Int, mask: Short): Short {
        if (!shared()) {
            return updateShort(addr) { (it.toInt() or mask.toInt()).toShort() }
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicOrI16(jsMemory.buffer, addr, mask.toInt()).toShort() }
    }

    override fun atomicXorShort(addr: Int, mask: Short): Short {
        if (!shared()) {
            return updateShort(addr) { (it.toInt() xor mask.toInt()).toShort() }
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicXorI16(jsMemory.buffer, addr, mask.toInt()).toShort() }
    }

    override fun atomicXchgShort(addr: Int, value: Short): Short {
        if (!shared()) {
            return updateShort(addr) { value }
        }
        checkAtomicAccess(addr, Short.SIZE_BYTES)
        return nativeAtomics { atomicExchangeI16(jsMemory.buffer, addr, value.toInt()).toShort() }
    }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short {
        if (shared()) {
            checkAtomicAccess(addr, Short.SIZE_BYTES)
            return nativeAtomics {
                atomicCompareExchangeI16(
                    jsMemory.buffer,
                    addr,
                    expected.toInt(),
                    replacement.toInt(),
                ).toShort()
            }
        }
        val old = readShort(addr)
        if (old == expected) {
            writeShort(addr, replacement)
        }
        return old
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte {
        if (!shared()) {
            return updateByte(addr) { (it + delta).toByte() }
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicAddI8(jsMemory.buffer, addr, delta.toInt()).toByte() }
    }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte {
        if (!shared()) {
            return updateByte(addr) { (it.toInt() and mask.toInt()).toByte() }
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicAndI8(jsMemory.buffer, addr, mask.toInt()).toByte() }
    }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte {
        if (!shared()) {
            return updateByte(addr) { (it.toInt() or mask.toInt()).toByte() }
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicOrI8(jsMemory.buffer, addr, mask.toInt()).toByte() }
    }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte {
        if (!shared()) {
            return updateByte(addr) { (it.toInt() xor mask.toInt()).toByte() }
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicXorI8(jsMemory.buffer, addr, mask.toInt()).toByte() }
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte {
        if (!shared()) {
            return updateByte(addr) { value }
        }
        checkAtomicAccess(addr, Byte.SIZE_BYTES)
        return nativeAtomics { atomicExchangeI8(jsMemory.buffer, addr, value.toInt()).toByte() }
    }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte {
        if (shared()) {
            checkAtomicAccess(addr, Byte.SIZE_BYTES)
            return nativeAtomics {
                atomicCompareExchangeI8(
                    jsMemory.buffer,
                    addr,
                    expected.toInt(),
                    replacement.toInt(),
                ).toByte()
            }
        }
        val old = read(addr)
        if (old == expected) {
            writeByte(addr, replacement)
        }
        return old
    }

    private fun updateInt(addr: Int, block: (Int) -> Int): Int {
        val old = readInt(addr)
        writeI32(addr, block(old))
        return old
    }

    private fun updateLong(addr: Int, block: (Long) -> Long): Long {
        val old = readLong(addr)
        writeLong(addr, block(old))
        return old
    }

    private fun updateShort(addr: Int, block: (Short) -> Short): Short {
        val old = readShort(addr)
        writeShort(addr, block(old))
        return old
    }

    private fun updateByte(addr: Int, block: (Byte) -> Byte): Byte {
        val old = read(addr)
        writeByte(addr, block(old))
        return old
    }

    private inline fun <T> nativeAtomics(block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            throw WasmEngineException("Native WebAssembly Atomics operation failed.", e)
        }

    private fun checkAtomicAccess(addr: Int, size: Int) {
        checkBounds(addr, size, sizeInBytes())
        if (addr % size != 0) {
            throw WasmRuntimeException("unaligned atomic memory access")
        }
    }

    private fun view(): DataView = DataView(jsMemory.buffer, 0, jsMemory.buffer.byteLength)

    private fun sizeInBytes(): Int = jsMemory.buffer.byteLength

    private fun checkBounds(offset: Int, size: Int, length: Int) {
        if (
            offset < 0 ||
                size < 0 ||
                offset > length ||
                offset + size < offset ||
                offset + size > length
        ) {
            throw WasmRuntimeException("out of bounds memory access")
        }
    }

    companion object {
        fun create(limits: MemoryLimits): NativeWasmMemory =
            NativeWasmMemory(
                createWebAssemblyMemory(
                    limits.initialPages(),
                    if (limits.maximumPages() == MemoryLimits.MAX_PAGES) {
                        -1
                    } else {
                        limits.maximumPages()
                    },
                    limits.shared(),
                ),
                limits,
            )
    }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val bytes = Uint8Array(size)
    for (i in indices) {
        bytes[i] = this[i]
    }
    return bytes
}

private const val WasmHeaderSize = 8
private const val WasmMemorySectionId = 5
private const val MemoryLimitNoMaximum = 0x00
private const val MemoryLimitHasMaximum = 0x01
private const val MemoryLimitSharedHasMaximum = 0x03
private const val ByteMask = 0xFF
private const val VarUIntPayloadMask = 0x7F
private const val VarUIntContinuationMask = 0x80
private const val VarUIntShift = 7
private const val MaxVarUInt32Shift = 28

internal class NativeWasmReferences {
    private var nextReference = 1L
    private val references = mutableMapOf<Long, JsAny?>()

    fun store(value: JsAny?): Long {
        if (value == null) {
            return Value.REF_NULL_VALUE.toLong()
        }
        for ((reference, existing) in references) {
            if (jsStrictEquals(existing, value)) {
                return reference
            }
        }
        val reference = nextReference++
        references[reference] = value
        return reference
    }

    fun load(reference: Long): JsAny? {
        if (reference == Value.REF_NULL_VALUE.toLong()) {
            return null
        }
        if (!references.containsKey(reference)) {
            throw WasmEngineException("unknown native WebAssembly reference: $reference")
        }
        return references[reference]
    }
}

private fun jsArgsToRawValues(
    types: List<ValType>,
    jsArgs: JsArray<JsAny?>,
    references: NativeWasmReferences,
): LongArray {
    if (jsArgs.length != types.size) {
        throw WasmEngineException(
            "wrong argument count, expected ${types.size}, got ${jsArgs.length}"
        )
    }
    return LongArray(types.size) { i -> jsValueToRaw(types[i], jsArgs[i], references) }
}

private fun jsResultToRawValues(
    types: List<ValType>,
    result: JsAny?,
    references: NativeWasmReferences,
): LongArray =
    LongArray(types.size) { i -> jsValueToRaw(types[i], resultValueAt(result, i), references) }

private fun rawResultsToJs(
    types: List<ValType>,
    values: LongArray,
    references: NativeWasmReferences,
): JsAny? {
    if (values.size != types.size) {
        throw WasmEngineException("wrong result count, expected ${types.size}, got ${values.size}")
    }
    return when (types.size) {
        0 -> undefined()
        1 -> rawValueToJs(types[0], values[0], references)
        else -> {
            val results = JsArray<JsAny?>()
            for (i in types.indices) {
                results[i] = rawValueToJs(types[i], values[i], references)
            }
            results
        }
    }
}

private fun jsValueToRaw(
    type: ValType,
    value: JsAny?,
    references: NativeWasmReferences,
): Long =
    when (type.opcode()) {
        ValType.ID.I32 -> jsValueToInt(value).toLong()
        ValType.ID.I64 -> jsValueToLong(value)
        ValType.ID.F32 -> jsValueToDouble(value).toFloat().toRawBits().toLong()
        ValType.ID.F64 -> jsValueToDouble(value).toRawBits()
        else ->
            if (isBridgeableReferenceType(type)) {
                references.store(value)
            } else {
                throw WasmEngineException("native browser engine does not yet bridge $type values")
            }
    }

private fun rawValueToJs(
    type: ValType,
    value: Long,
    references: NativeWasmReferences,
): JsAny? =
    when (type.opcode()) {
        ValType.ID.I32 -> value.toInt().toJsNumber()
        ValType.ID.I64 -> longToJsBigInt(value)
        ValType.ID.F32 -> Float.fromBits(value.toInt()).toDouble().toJsNumber()
        ValType.ID.F64 -> Double.fromBits(value).toJsNumber()
        else ->
            if (isBridgeableReferenceType(type)) {
                references.load(value)
            } else {
                throw WasmEngineException("native browser engine does not yet bridge $type values")
            }
    }

internal external class JsWebAssemblyModule : JsAny

internal external class JsWebAssemblyInstance : JsAny {
    val exports: JsAny
}

internal external class JsWebAssemblyMemory : JsAny {
    val buffer: ArrayBuffer

    fun grow(delta: Int): Int
}

internal external class JsWebAssemblyGlobal : JsAny

internal external class JsWebAssemblyTable : JsAny

internal external class JsWebAssemblyTag : JsAny

@Suppress("UNUSED_PARAMETER")
private fun compileWebAssemblyModule(bytes: ArrayBufferView): JsWebAssemblyModule =
    js("new WebAssembly.Module(bytes)")

@Suppress("UNUSED_PARAMETER")
private fun instantiateWebAssembly(
    module: JsWebAssemblyModule,
    imports: JsAny,
): JsAny =
    js(
        """
        (() => {
            try {
                return { ok: true, instance: new WebAssembly.Instance(module, imports) };
            } catch (error) {
                return {
                    ok: false,
                    runtimeError:
                        typeof WebAssembly === "object" &&
                            typeof WebAssembly.RuntimeError === "function" &&
                            error instanceof WebAssembly.RuntimeError,
                    message: error && error.message ? String(error.message) : String(error),
                };
            }
        })()
        """
    )

private fun instantiateWebAssemblyOrThrow(
    module: JsWebAssemblyModule,
    imports: JsAny,
): JsWebAssemblyInstance {
    val result = instantiateWebAssembly(module, imports)
    if (instantiationResultOk(result)) {
        return instantiationResultInstance(result)
    }
    val message = instantiationResultMessage(result)
    if (instantiationResultRuntimeError(result)) {
        throw NativeWasmRuntimeException(message)
    }
    throw WasmEngineException(message)
}

@Suppress("UNUSED_PARAMETER")
private fun instantiationResultOk(result: JsAny): Boolean = js("result.ok === true")

@Suppress("UNUSED_PARAMETER")
private fun instantiationResultRuntimeError(result: JsAny): Boolean =
    js("result.runtimeError === true")

@Suppress("UNUSED_PARAMETER")
private fun instantiationResultMessage(result: JsAny): String = js("result.message")

@Suppress("UNUSED_PARAMETER")
private fun instantiationResultInstance(result: JsAny): JsWebAssemblyInstance =
    js("result.instance")

@Suppress("UNUSED_PARAMETER")
private fun createWebAssemblyMemory(
    initial: Int,
    maximum: Int,
    shared: Boolean,
): JsWebAssemblyMemory =
    js(
        """
        (() => {
            const descriptor = { initial: initial };
            if (maximum >= 0) descriptor.maximum = maximum;
            if (shared) descriptor.shared = true;
            return new WebAssembly.Memory(descriptor);
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun createWebAssemblyGlobal(
    valueType: String,
    mutable: Boolean,
    value: JsAny?,
): JsWebAssemblyGlobal =
    js("new WebAssembly.Global({ value: valueType, mutable: mutable }, value)")

@Suppress("UNUSED_PARAMETER")
private fun createWebAssemblyTable(
    element: String,
    initial: Int,
    maximum: Int,
): JsWebAssemblyTable =
    js(
        """
        (() => {
            const descriptor = { element: element, initial: initial };
            if (maximum >= 0) descriptor.maximum = maximum;
            return new WebAssembly.Table(descriptor);
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun createWebAssemblyTag(parameters: JsArray<JsAny?>): JsWebAssemblyTag =
    js("new WebAssembly.Tag({ parameters: Array.from(parameters) })")

@Suppress("UNUSED_PARAMETER")
private fun throwWebAssemblyException(tag: JsWebAssemblyTag, values: JsArray<JsAny?>): Nothing =
    js("(() => { throw new WebAssembly.Exception(tag, Array.from(values)); })()")

@Suppress("UNUSED_PARAMETER")
private fun callFunction(fn: JsAny, args: JsArray<JsAny?>): JsAny? =
    js("fn(...args)")

@Suppress("UNUSED_PARAMETER")
private fun hostFunction(callback: (JsArray<JsAny?>) -> JsAny?): JsAny =
    js("(...args) => callback(args)")

private fun emptyObject(): JsAny = js("({})")

@Suppress("UNUSED_PARAMETER")
private fun getProperty(obj: JsAny, name: String): JsAny? =
    js("Object.prototype.hasOwnProperty.call(obj, name) ? obj[name] : null")

@Suppress("UNUSED_PARAMETER")
private fun setProperty(obj: JsAny, name: String, value: JsAny?) {
    js("obj[name] = value;")
}

@Suppress("UNUSED_PARAMETER")
private fun setStringArrayValue(array: JsArray<JsAny?>, index: Int, value: String) {
    js("array[index] = value;")
}

@Suppress("UNUSED_PARAMETER")
private fun ensureImportModule(root: JsAny, name: String): JsAny =
    js("root[name] || (root[name] = {})")

@Suppress("UNUSED_PARAMETER")
private fun resultValueAt(result: JsAny?, index: Int): JsAny? =
    js("Array.isArray(result) ? result[index] : (index === 0 ? result : undefined)")

@Suppress("UNUSED_PARAMETER")
private fun jsValueToInt(value: JsAny?): Int = js("value | 0")

@Suppress("UNUSED_PARAMETER")
private fun jsValueToLong(value: JsAny?): Long = js("BigInt.asIntN(64, value)")

@Suppress("UNUSED_PARAMETER")
private fun jsValueToDouble(value: JsAny?): Double = js("Number(value)")

@Suppress("UNUSED_PARAMETER")
private fun longToJsBigInt(value: Long): JsAny = js("value")

@Suppress("UNUSED_PARAMETER")
private fun jsStrictEquals(left: JsAny?, right: JsAny?): Boolean = js("left === right")

@Suppress("UNUSED_PARAMETER")
private fun atomicLoadI32(buffer: ArrayBuffer, addr: Int): Int =
    js("Atomics.load(new Int32Array(buffer), addr >> 2)")

@Suppress("UNUSED_PARAMETER")
private fun atomicLoadI64(buffer: ArrayBuffer, addr: Int): Long =
    js("BigInt.asIntN(64, Atomics.load(new BigInt64Array(buffer), addr >> 3))")

@Suppress("UNUSED_PARAMETER")
private fun atomicLoadI16(buffer: ArrayBuffer, addr: Int): Int =
    js("Atomics.load(new Int16Array(buffer), addr >> 1)")

@Suppress("UNUSED_PARAMETER")
private fun atomicLoadI8(buffer: ArrayBuffer, addr: Int): Int =
    js("Atomics.load(new Int8Array(buffer), addr)")

@Suppress("UNUSED_PARAMETER")
private fun atomicStoreI32(buffer: ArrayBuffer, addr: Int, value: Int) {
    js("Atomics.store(new Int32Array(buffer), addr >> 2, value);")
}

@Suppress("UNUSED_PARAMETER")
private fun atomicStoreI64(buffer: ArrayBuffer, addr: Int, value: Long) {
    js("Atomics.store(new BigInt64Array(buffer), addr >> 3, value);")
}

@Suppress("UNUSED_PARAMETER")
private fun atomicStoreI16(buffer: ArrayBuffer, addr: Int, value: Int) {
    js("Atomics.store(new Int16Array(buffer), addr >> 1, value);")
}

@Suppress("UNUSED_PARAMETER")
private fun atomicStoreI8(buffer: ArrayBuffer, addr: Int, value: Int) {
    js("Atomics.store(new Int8Array(buffer), addr, value);")
}

@Suppress("UNUSED_PARAMETER")
private fun atomicAddI32(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.add(new Int32Array(buffer), addr >> 2, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicAddI64(buffer: ArrayBuffer, addr: Int, value: Long): Long =
    js("BigInt.asIntN(64, Atomics.add(new BigInt64Array(buffer), addr >> 3, value))")

@Suppress("UNUSED_PARAMETER")
private fun atomicAddI16(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.add(new Int16Array(buffer), addr >> 1, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicAddI8(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.add(new Int8Array(buffer), addr, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicAndI32(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.and(new Int32Array(buffer), addr >> 2, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicAndI64(buffer: ArrayBuffer, addr: Int, value: Long): Long =
    js("BigInt.asIntN(64, Atomics.and(new BigInt64Array(buffer), addr >> 3, value))")

@Suppress("UNUSED_PARAMETER")
private fun atomicAndI16(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.and(new Int16Array(buffer), addr >> 1, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicAndI8(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.and(new Int8Array(buffer), addr, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicOrI32(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.or(new Int32Array(buffer), addr >> 2, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicOrI64(buffer: ArrayBuffer, addr: Int, value: Long): Long =
    js("BigInt.asIntN(64, Atomics.or(new BigInt64Array(buffer), addr >> 3, value))")

@Suppress("UNUSED_PARAMETER")
private fun atomicOrI16(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.or(new Int16Array(buffer), addr >> 1, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicOrI8(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.or(new Int8Array(buffer), addr, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicXorI32(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.xor(new Int32Array(buffer), addr >> 2, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicXorI64(buffer: ArrayBuffer, addr: Int, value: Long): Long =
    js("BigInt.asIntN(64, Atomics.xor(new BigInt64Array(buffer), addr >> 3, value))")

@Suppress("UNUSED_PARAMETER")
private fun atomicXorI16(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.xor(new Int16Array(buffer), addr >> 1, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicXorI8(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.xor(new Int8Array(buffer), addr, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicExchangeI32(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.exchange(new Int32Array(buffer), addr >> 2, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicExchangeI64(buffer: ArrayBuffer, addr: Int, value: Long): Long =
    js("BigInt.asIntN(64, Atomics.exchange(new BigInt64Array(buffer), addr >> 3, value))")

@Suppress("UNUSED_PARAMETER")
private fun atomicExchangeI16(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.exchange(new Int16Array(buffer), addr >> 1, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicExchangeI8(buffer: ArrayBuffer, addr: Int, value: Int): Int =
    js("Atomics.exchange(new Int8Array(buffer), addr, value)")

@Suppress("UNUSED_PARAMETER")
private fun atomicCompareExchangeI32(
    buffer: ArrayBuffer,
    addr: Int,
    expected: Int,
    replacement: Int,
): Int =
    js("Atomics.compareExchange(new Int32Array(buffer), addr >> 2, expected, replacement)")

@Suppress("UNUSED_PARAMETER")
private fun atomicCompareExchangeI64(
    buffer: ArrayBuffer,
    addr: Int,
    expected: Long,
    replacement: Long,
): Long =
    js(
        "BigInt.asIntN(64, Atomics.compareExchange(new BigInt64Array(buffer), addr >> 3, expected, replacement))"
    )

@Suppress("UNUSED_PARAMETER")
private fun atomicCompareExchangeI16(
    buffer: ArrayBuffer,
    addr: Int,
    expected: Int,
    replacement: Int,
): Int =
    js("Atomics.compareExchange(new Int16Array(buffer), addr >> 1, expected, replacement)")

@Suppress("UNUSED_PARAMETER")
private fun atomicCompareExchangeI8(
    buffer: ArrayBuffer,
    addr: Int,
    expected: Int,
    replacement: Int,
): Int =
    js("Atomics.compareExchange(new Int8Array(buffer), addr, expected, replacement)")

@Suppress("UNUSED_PARAMETER")
private fun atomicWaitI32(buffer: ArrayBuffer, addr: Int, expected: Int, timeout: Long): Int =
    js(
        """
        (() => {
            const timeoutMs = timeout < 0n ? undefined : Number(timeout) / 1000000;
            const result = Atomics.wait(new Int32Array(buffer), addr >> 2, expected, timeoutMs);
            return result === "ok" ? 0 : result === "not-equal" ? 1 : 2;
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun atomicWaitI64(buffer: ArrayBuffer, addr: Int, expected: Long, timeout: Long): Int =
    js(
        """
        (() => {
            const timeoutMs = timeout < 0n ? undefined : Number(timeout) / 1000000;
            const result = Atomics.wait(new BigInt64Array(buffer), addr >> 3, expected, timeoutMs);
            return result === "ok" ? 0 : result === "not-equal" ? 1 : 2;
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun atomicNotify(buffer: ArrayBuffer, addr: Int, maxThreads: Int): Int =
    js("Atomics.notify(new Int32Array(buffer), addr >> 2, maxThreads < 0 ? Infinity : maxThreads)")

private fun undefined(): JsAny? = js("undefined")

private fun wasmValueType(type: ValType): String =
    wasmValueTypeOrNull(type)
        ?: throw WasmEngineException("native browser engine does not yet bridge $type values")

private fun wasmValueTypeOrNull(type: ValType): String? =
    when (type.opcode()) {
        ValType.ID.I32 -> "i32"
        ValType.ID.I64 -> "i64"
        ValType.ID.F32 -> "f32"
        ValType.ID.F64 -> "f64"
        else -> wasmReferenceValueType(type)
    }

private fun wasmTagParameters(type: FunctionType): JsArray<JsAny?> {
    if (type.returns().isNotEmpty()) {
        throw WasmEngineException("native WebAssembly tags must not have return values")
    }
    val parameters = JsArray<JsAny?>()
    for (i in type.params().indices) {
        setStringArrayValue(parameters, i, wasmValueType(type.params()[i]))
    }
    return parameters
}

private fun isBridgeableReferenceType(type: ValType): Boolean =
    wasmReferenceValueType(type) != null

private fun wasmReferenceValueType(type: ValType): String? =
    if (type.opcode() != ValType.ID.Ref && type.opcode() != ValType.ID.RefNull) {
        null
    } else {
        when {
            type.typeIdx() >= 0 -> "anyfunc"
            type.typeIdx() == ValType.TypeIdxCode.FUNC.code() -> "anyfunc"
            type.typeIdx() == ValType.TypeIdxCode.EXTERN.code() -> "externref"
            type.typeIdx() == ValType.TypeIdxCode.ANY.code() -> "anyref"
            type.typeIdx() == ValType.TypeIdxCode.EQ.code() -> "eqref"
            type.typeIdx() == ValType.TypeIdxCode.I31.code() -> "i31ref"
            type.typeIdx() == ValType.TypeIdxCode.STRUCT.code() -> "structref"
            type.typeIdx() == ValType.TypeIdxCode.ARRAY.code() -> "arrayref"
            type.typeIdx() == ValType.TypeIdxCode.EXN.code() -> "exnref"
            else -> null
        }
    }

private fun wasmTableElement(type: ValType): String =
    wasmReferenceValueType(type)
        ?: throw WasmEngineException("native browser engine does not yet bridge $type tables")

@Suppress("UNUSED_PARAMETER")
private fun getGlobalValue(global: JsWebAssemblyGlobal): JsAny? = js("global.value")

@Suppress("UNUSED_PARAMETER")
private fun setGlobalValue(global: JsWebAssemblyGlobal, value: JsAny?) {
    js("global.value = value;")
}

@Suppress("UNUSED_PARAMETER")
private fun getTableLength(table: JsWebAssemblyTable): Int = js("table.length")

@Suppress("UNUSED_PARAMETER")
private fun getTableValue(table: JsWebAssemblyTable, index: Int): JsAny? = js("table.get(index)")

@Suppress("UNUSED_PARAMETER")
private fun setTableValue(table: JsWebAssemblyTable, index: Int, value: JsAny?) {
    js("table.set(index, value);")
}

@Suppress("UNUSED_PARAMETER")
private fun growTable(table: JsWebAssemblyTable, size: Int, value: JsAny?): Int =
    js("value === null || value === undefined ? table.grow(size) : table.grow(size, value)")

@Suppress("UNUSED_PARAMETER")
private fun hasNativeWebAssembly(): Boolean =
    js(
        """
        typeof WebAssembly === "object" &&
            typeof WebAssembly.Module === "function" &&
            typeof WebAssembly.Instance === "function"
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun supportsSharedWebAssemblyMemory(): Boolean =
    js(
        """
        (() => {
            try {
                if (typeof SharedArrayBuffer === "undefined") return false;
                if (typeof WebAssembly !== "object" || typeof WebAssembly.Memory !== "function") {
                    return false;
                }
                new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true });
                return true;
            } catch (_) {
                return false;
            }
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun supportsWebAssemblyTags(): Boolean =
    js(
        """
        (() => {
            try {
                if (typeof WebAssembly !== "object" || typeof WebAssembly.Tag !== "function") {
                    return false;
                }
                new WebAssembly.Tag({ parameters: [] });
                return true;
            } catch (_) {
                return false;
            }
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun supportsWebAssemblyGlobalValueType(type: String): Boolean =
    js(
        """
        (() => {
            try {
                if (typeof WebAssembly !== "object" || typeof WebAssembly.Global !== "function") {
                    return false;
                }
                const initial =
                    type === "i64" ? 0n :
                    type === "i32" || type === "f32" || type === "f64" ? 0 :
                    null;
                new WebAssembly.Global({ value: type, mutable: true }, initial);
                return true;
            } catch (_) {
                return false;
            }
        })()
        """
    )

@Suppress("UNUSED_PARAMETER")
private fun supportsWebAssemblyTableElement(element: String): Boolean =
    js(
        """
        (() => {
            try {
                if (typeof WebAssembly !== "object" || typeof WebAssembly.Table !== "function") {
                    return false;
                }
                new WebAssembly.Table({ element, initial: 0 });
                return true;
            } catch (_) {
                return false;
            }
        })()
        """
    )
