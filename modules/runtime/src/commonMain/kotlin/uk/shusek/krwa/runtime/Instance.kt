package uk.shusek.krwa.runtime

import uk.shusek.krwa.runtime.ConstantEvaluators.computeConstantInstance
import uk.shusek.krwa.runtime.ConstantEvaluators.computeConstantValue
import uk.shusek.krwa.runtime.internal.GcRefStore
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ActiveDataSegment
import uk.shusek.krwa.wasm.types.ActiveElement
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.Element
import uk.shusek.krwa.wasm.types.Export
import uk.shusek.krwa.wasm.types.ExportSection
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Global
import uk.shusek.krwa.wasm.types.GlobalImport
import uk.shusek.krwa.wasm.types.Import
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.MemoryImport
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.MemorySection
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.PassiveDataSegment
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableImport
import uk.shusek.krwa.wasm.types.TagImport
import uk.shusek.krwa.wasm.types.TagSection
import uk.shusek.krwa.wasm.types.TagType
import uk.shusek.krwa.wasm.types.TypeSection
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/**
 * A live WebAssembly instance.
 *
 * Instances and resources obtained from them are not safe for concurrent use. Embedders should
 * serialize calls per instance. [close] is idempotent; execution access and previously obtained
 * exported functions or memories fail fast after the instance has been closed.
 */
class Instance
private constructor(
    private val module: WasmModule,
    private val wasmtimeExecutionConfig: WasmtimeExecutionConfig?,
    globalInitializers: Array<Global>,
    private val memories: Array<Memory>,
    private val dataSegments: Array<DataSegment>,
    functions: Array<FunctionBody>,
    types: Array<FunctionType>,
    functionTypes: IntArray,
    private val imports: ImportValues,
    tableDefinitions: Array<Table>,
    elements: Array<Element>,
    tagTypes: Array<TagType>?,
    exports: Map<String, Export>,
    initialize: Boolean,
    start: Boolean,
) : AutoCloseable {
    private val functions: Array<FunctionBody> = functions.copyOf()
    private val globalInitializers: Array<Global> = globalInitializers.copyOf()
    private val globals: Array<GlobalInstance?> = arrayOfNulls(globalInitializers.size)
    private val types: Array<FunctionType> = types.copyOf()
    private val functionTypes: IntArray = functionTypes.copyOf()
    private val tables: Array<TableInstance?> = arrayOfNulls(tableDefinitions.size)
    private val elements: Array<Element?> = Array(elements.size) { idx -> elements[idx] }
    private val tags: Array<TagInstance> =
        tagTypes?.let { Array(it.size) { idx -> TagInstance(it[idx]) } } ?: emptyArray()
    private val exports: Map<String, Export> = exports
    private val fluentExports = Exports.create(this)
    private var platformExecution: PlatformInstanceExecution? = null
    private var resolvedExecutionBackend: ExecutionBackend = ExecutionBackend.AUTO
    private var closed: Boolean = false
    private val exnRefs: MutableMap<Int, WasmException> = HashMap()
    private var nextExnRef: Int = 0
    private val gcRefs = GcRefStore(this)

    private var tailCallPending: TailCallPending? = null

    fun wasmtimeExecutionConfig(): WasmtimeExecutionConfig? = wasmtimeExecutionConfig

    fun isClosed(): Boolean = closed

    internal fun ensureOpen() {
        check(!closed) { "WebAssembly instance is closed" }
    }

    override fun close() {
        if (closed) return
        closed = true
        val execution = platformExecution
        platformExecution = null
        execution?.close()
    }

    private class TailCallPending(val funcId: Int, val args: LongArray)

    init {
        if (tagTypes != null) {
            for (i in tags.indices) {
                tags[i].setType(types[tagTypes[i].typeIdx()])
            }
        }

        for (i in tableDefinitions.indices) {
            val rawValue = computeConstantValue(this, tableDefinitions[i].initialize())[0]
            val initValue = OpcodeOps.boxForTable(rawValue, this)
            tables[i] =
                TableInstance(tableDefinitions[i], initValue)
        }

        if (initialize) {
            initialize(start)
        }
    }

    fun initialize(start: Boolean): Instance {
        ensureOpen()
        // Globals must be initialized before element/data segments, because segment offsets can
        // reference local globals via global.get.
        for (i in globalInitializers.indices) {
            val global = globalInitializers[i]
            val values = computeConstantValue(this, global.initInstructions())
            globals[i] =
                GlobalInstance(
                    values[0],
                    if (values.size > 1) values[1] else 0,
                    global.valueType(),
                    global.mutabilityType(),
                )
            globals[i]!!.instance = this
        }

        for (element in elements) {
            if (element is ActiveElement) {
                val table = table(element.tableIndex())
                val offset = computeConstantValue(this, element.offset())[0].toInt()
                val initializers: List<List<Instruction>> = element.initializers()
                if (
                    offset > table.limits().min() || offset + initializers.size - 1 >= table.size()
                ) {
                    throw UninstantiableException("out of bounds table access")
                }
                for (i in initializers.indices) {
                    val init = initializers[i]
                    val index = offset + i
                    val value = computeConstantValue(this, init)
                    val inst = computeConstantInstance(this, init)

                    assert(element.type().isReference())
                    table.setRef(index, OpcodeOps.boxForTable(value[0], this), inst)
                }
            }
        }

        val importedMemCount = imports.memoryCount()
        val definedMemCount = memories.size
        val totalMemCount = importedMemCount + definedMemCount

        if (totalMemCount > 0) {
            for (i in 0 until definedMemCount) {
                memories[i].zero()
                memories[i].initialize(this, dataSegments, importedMemCount + i)
            }
            for (i in 0 until importedMemCount) {
                imports.memory(i).memory()!!.initialize(this, dataSegments, i)
            }
        } else if (dataSegments.any { it is ActiveDataSegment }) {
            for (dataSegment in dataSegments) {
                if (dataSegment is ActiveDataSegment) {
                    throw InvalidException("unknown memory ${dataSegment.index()}")
                }
            }
            throw InvalidException("unknown memory")
        }

        val startFunction = exports[START_FUNCTION_NAME]
        if (startFunction != null && start) {
            try {
                export(START_FUNCTION_NAME).apply()
            } catch (_: ExecutionCompletedException) {
                // return
            }
        }

        // Safe point: wasm stack is empty after init.
        gcSafePoint()

        return this
    }

    fun exportType(name: String): FunctionType {
        ensureOpen()
        return platformExecution?.exportType(name) ?: type(functionType(exports[name]!!.index()))
    }

    fun executionBackend(): ExecutionBackend = resolvedExecutionBackend

    class Exports private constructor(private val instance: Instance) {
        private fun getExport(type: ExternalType, name: String): Export {
            instance.ensureOpen()
            val export = instance.exports[name]
            if (export == null) {
                throw InvalidException("Unknown export with name $name")
            } else if (export.exportType() != type) {
                throw InvalidException(
                    "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to $type"
                )
            }
            return export
        }

        fun function(name: String): ExportFunction {
            instance.ensureOpen()
            instance.platformExecution?.let { execution ->
                val function = execution.export(name)
                return ExportFunction { args ->
                    instance.ensureOpen()
                    function.apply(*args)
                }
            }
            getExport(ExternalType.FUNCTION, name)
            throw WasmEngineException("platform WebAssembly execution is not available")
        }

        fun global(name: String): GlobalInstance {
            val export = getExport(ExternalType.GLOBAL, name)
            return instance.global(export.index())
        }

        fun table(name: String): TableInstance {
            val export = getExport(ExternalType.TABLE, name)
            return instance.table(export.index())
        }

        fun tag(name: String): TagInstance {
            val export = getExport(ExternalType.TAG, name)
            return instance.tag(export.index())
        }

        fun memory(name: String): Memory {
            instance.ensureOpen()
            instance.platformExecution?.let { return instance.guardMemory(it.memory(name)) }
            val export = getExport(ExternalType.MEMORY, name)
            return instance.memory(export.index())
        }

        internal companion object {
            fun create(instance: Instance): Exports = Exports(instance)
        }
    }

    fun exports(): Exports {
        ensureOpen()
        return fluentExports
    }

    fun export(name: String): ExportFunction = fluentExports.function(name)

    fun function(idx: Int): FunctionBody? {
        ensureOpen()
        if (idx < 0 || idx >= functions.size + imports.functionCount()) {
            throw InvalidException("unknown function $idx")
        } else if (idx < imports.functionCount()) {
            return null
        }
        return functions[idx - imports.functionCount()]
    }

    fun functionCount(): Int = imports.functionCount() + functions.size

    fun globalCount(): Int = imports.globalCount() + globals.size

    fun tableCount(): Int = imports.tableCount() + tables.size

    fun memory(): Memory {
        ensureOpen()
        platformExecution?.let {
            return guardMemory(
                it.memory(0)
                ?: throw InvalidException("native WebAssembly memory 0 is not exported")
            )
        }
        if (imports.memoryCount() > 0) {
            return guardMemory(imports.memory(0).memory()!!)
        }
        if (memories.isNotEmpty()) {
            return guardMemory(memories[0])
        }
        return jvmNull()
    }

    fun memory(index: Int): Memory {
        ensureOpen()
        platformExecution?.let {
            return guardMemory(
                it.memory(index)
                ?: throw InvalidException("native WebAssembly memory $index is not exported")
            )
        }
        val totalMemories = memories.size + imports.memoryCount()
        if (index < 0 || index >= totalMemories) {
            throw InvalidException("unknown memory $index")
        }
        if (index < imports.memoryCount()) {
            return guardMemory(imports.memory(index).memory()!!)
        }
        return guardMemory(memories[index - imports.memoryCount()])
    }

    private fun guardMemory(memory: Memory): Memory = InstanceMemory(this, memory)

    fun dataSegmentData(idx: Int): ByteArray = dataSegments[idx].data()

    fun dropDataSegment(idx: Int) {
        ensureOpen()
        dataSegments[idx] = PassiveDataSegment.EMPTY
    }

    fun global(idx: Int): GlobalInstance {
        ensureOpen()
        if (idx < imports.globalCount()) {
            return imports.global(idx).instance()
        }
        val i = idx - imports.globalCount()
        if (i < 0 || i >= globals.size) {
            throw InvalidException("unknown global $idx")
        }
        return globals[i] ?: throw InvalidException("unknown global $idx")
    }

    fun type(idx: Int): FunctionType {
        if (idx >= types.size) {
            throw InvalidException("unknown type $idx")
        }
        return types[idx]
    }

    fun functionType(idx: Int): Int {
        if (idx >= functionTypes.size) {
            throw InvalidException("unknown function $idx")
        }
        return functionTypes[idx]
    }

    fun imports(): ImportValues = imports

    fun module(): WasmModule = module

    fun table(idx: Int): TableInstance {
        ensureOpen()
        if (idx < 0 || idx >= tables.size + imports.tableCount()) {
            throw InvalidException("unknown table $idx")
        }
        if (idx < imports.tableCount()) {
            return imports.table(idx).table()
        }
        return tables[idx - imports.tableCount()]!!
    }

    fun elementOrNull(idx: Int): Element? {
        ensureOpen()
        if (idx < 0 || idx >= elements.size) {
            throw InvalidException("unknown elem segment $idx")
        }
        return elements[idx]
    }

    fun element(idx: Int): Element {
        return elementOrNull(idx) ?: throw InvalidException("unknown elem segment $idx")
    }

    fun elementCount(): Int = elements.size

    fun setElement(idx: Int, value: Element?) {
        ensureOpen()
        elements[idx] = value
    }

    fun tag(idx: Int): TagInstance {
        ensureOpen()
        if (idx < imports.tagCount()) {
            return imports.tag(idx).tag()
        }
        return tags[idx - imports.tagCount()]
    }

    fun tagCount(): Int = tags.size

    fun registerException(ex: WasmException): Int {
        ensureOpen()
        val ref = nextExnRef++
        exnRefs[ref] = ex
        return ref
    }

    fun exn(idx: Int): WasmException? {
        ensureOpen()
        return exnRefs[idx]
    }

    fun array(idx: Int): LongArray? {
        ensureOpen()
        val gcRef = gcRefs.get(idx)
        if (gcRef is WasmArray) {
            return gcRef.elements()
        }
        return null
    }

    fun registerGcRef(ref: WasmGcRef): Int {
        ensureOpen()
        return gcRefs.put(ref)
    }

    fun gcRef(idx: Int): WasmGcRef? {
        ensureOpen()
        return gcRefs.get(idx)
    }

    internal fun gcRefUnchecked(idx: Int): WasmGcRef = gcRefs.getUnchecked(idx)

    fun heapTypeMatch(
        ref: Long,
        nullable: Boolean,
        targetHeapType: Int,
        sourceHeapType: Int,
    ): Boolean {
        ensureOpen()
        if (ref == Value.REF_NULL_VALUE.toLong()) {
            return nullable
        }
        // Bottom types never match non-null values.
        if (
            targetHeapType == ValType.TypeIdxCode.NONE.code() ||
                targetHeapType == ValType.TypeIdxCode.NOFUNC.code() ||
                targetHeapType == ValType.TypeIdxCode.NOEXTERN.code()
        ) {
            return false
        }
        // For abstract func/extern targets: the validator guarantees the operand is in the correct
        // hierarchy, so any non-null value matches.
        if (
            targetHeapType == ValType.TypeIdxCode.FUNC.code() ||
                targetHeapType == ValType.TypeIdxCode.EXTERN.code()
        ) {
            return true
        }
        // Dispatch based on source hierarchy.
        if (sourceHeapType == ValType.TypeIdxCode.FUNC.code()) {
            val funcTypeIdx = functionType(ref.toInt())
            return heapTypeSubOf(funcTypeIdx, targetHeapType)
        }
        // ANY hierarchy: i31, struct, array, or internalized externref.
        if (Value.isI31(ref)) {
            return heapTypeSubOf(ValType.TypeIdxCode.I31.code(), targetHeapType)
        }
        val gc = gcRef(ref.toInt())
        if (gc != null) {
            return heapTypeSubOf(gc.typeIdx(), targetHeapType)
        }
        // Internalized externref (via any.convert_extern).
        return targetHeapType == ValType.TypeIdxCode.ANY.code()
    }

    private fun heapTypeSubOf(actual: Int, target: Int): Boolean {
        if (actual == target) {
            return true
        }
        return ValType.heapTypeSubtype(actual, target, module.typeSection())
    }

    // Preserve the Java API behavior: modules without memory expose null.
    @Suppress("UNCHECKED_CAST") private fun <T> jvmNull(): T = null as T

    /** Epoch-based GC safe point. Call when the wasm stack is guaranteed empty. */
    fun gcSafePoint() {
        ensureOpen()
        gcRefs.safePoint()
    }

    fun isTailCallPending(): Boolean = tailCallPending != null

    fun tailCallFuncId(): Int = tailCallPending!!.funcId

    fun tailCallArgs(): LongArray = tailCallPending!!.args

    fun setTailCall(funcId: Int, args: LongArray) {
        ensureOpen()
        tailCallPending = TailCallPending(funcId, args)
    }

    fun clearTailCall() {
        ensureOpen()
        tailCallPending = null
    }

    class Builder
    private constructor(
        private val module: WasmModule,
        private val defaultMemoryFactory: (MemoryLimits) -> Memory,
    ) {
        private var memoryLimits: MemoryLimits? = null
        private var importValues: ImportValues? = null
        private var executionBackend: ExecutionBackend = ExecutionBackend.AUTO
        private var wasmtimeExecutionConfig: WasmtimeExecutionConfig? = null
        private var executionPolicyApplied: Boolean = false

        fun withMemoryLimits(limits: MemoryLimits): Builder {
            check(!executionPolicyApplied) {
                "memory limits cannot be combined with an atomic execution policy"
            }
            memoryLimits = limits
            return this
        }

        fun withImportValues(importValues: ImportValues): Builder {
            this.importValues = importValues
            return this
        }

        fun withExecutionBackend(backend: ExecutionBackend): Builder {
            check(!executionPolicyApplied) {
                "execution backend cannot be combined with an atomic execution policy"
            }
            executionBackend = backend
            return this
        }

        fun withWasmtimeExecutionConfig(config: WasmtimeExecutionConfig?): Builder {
            check(!executionPolicyApplied) {
                "Wasmtime configuration cannot be combined with an atomic execution policy"
            }
            wasmtimeExecutionConfig = config
            return this
        }

        /** Applies one complete engine and resource policy, replacing prior backend settings. */
        fun withExecutionPolicy(policy: WasmExecutionPolicy): Builder {
            when (policy) {
                is WasmExecutionPolicy.Automatic -> {
                    executionBackend = ExecutionBackend.AUTO
                    memoryLimits = policy.maxMemoryBytes?.let(module::cappedFirstMemoryLimits)
                    wasmtimeExecutionConfig = policy.maxMemoryBytes?.let { maxMemoryBytes ->
                        WasmtimeExecutionConfig(maxMemoryBytes = maxMemoryBytes)
                    }
                }

                is WasmExecutionPolicy.HostWebAssembly -> {
                    executionBackend = ExecutionBackend.NATIVE
                    memoryLimits = policy.maxMemoryBytes?.let(module::cappedFirstMemoryLimits)
                    wasmtimeExecutionConfig = null
                }

                is WasmExecutionPolicy.Wasmtime -> {
                    executionBackend = ExecutionBackend.PULLEY
                    memoryLimits = null
                    wasmtimeExecutionConfig = policy.config
                }
            }
            executionPolicyApplied = true
            return this
        }

        private fun checkExternalFunctionSignature(
            import: FunctionImport,
            function: ImportFunction,
        ): Boolean =
            try {
                validateExternalFunctionSignature(import, function)
                true
            } catch (_: UnlinkableException) {
                false
            }

        private fun validateExternalFunctionSignature(
            import: FunctionImport,
            function: ImportFunction,
        ) {
            // Use cross-module canonical type matching when source instance is available.
            if (function.sourceInstance() != null) {
                val src = function.sourceInstance()!!
                val srcExports = src.module().exportSection()
                for (i in 0 until srcExports.exportCount()) {
                    val export = srcExports.getExport(i)
                    if (
                        export.name() == function.name() &&
                            export.exportType() == ExternalType.FUNCTION
                    ) {
                        val typeIdx = src.functionType(export.index())
                        if (
                            !crossModuleTypeSubtype(
                                src.module().typeSection(),
                                typeIdx,
                                module.typeSection(),
                                import.typeIndex(),
                            )
                        ) {
                            throw UnlinkableException(
                                "incompatible import type for host function " +
                                    function.module() +
                                    "." +
                                    function.name()
                            )
                        }
                        return
                    }
                }
            }

            // Fallback: structural comparison for host-provided functions.
            val expectedType = module.typeSection().getType(import.typeIndex())
            if (function.functionType() != expectedType) {
                throw UnlinkableException(
                    "incompatible import type for host function " +
                        function.module() +
                        "." +
                        function.name()
                )
            }
        }

        /**
         * Check if exportTypeIdx from exportTs is a subtype of importTypeIdx from importTs. Walks
         * up the export type's declared supertype chain checking canonical equivalence.
         */
        private fun crossModuleTypeSubtype(
            exportTs: TypeSection,
            exportTypeIdx: Int,
            importTs: TypeSection,
            importTypeIdx: Int,
        ): Boolean {
            var currentIdx = exportTypeIdx
            while (currentIdx >= 0) {
                if (
                    TypeSection.crossModuleCanonicallyEquivalent(
                        exportTs,
                        currentIdx,
                        importTs,
                        importTypeIdx,
                    )
                ) {
                    return true
                }
                val subType = exportTs.getSubType(currentIdx)
                val supers = subType.typeIdx()
                if (supers.isEmpty()) {
                    break
                }
                currentIdx = supers[0]
            }
            return false
        }

        private fun checkHostGlobalType(import: GlobalImport, global: ImportGlobal): Boolean =
            try {
                validateHostGlobalType(import, global)
                true
            } catch (_: UnlinkableException) {
                false
            }

        private fun validateHostGlobalType(import: GlobalImport, global: ImportGlobal) {
            val typesMatch =
                when (import.mutabilityType()) {
                    MutabilityType.Var -> import.type() == global.instance().type
                    MutabilityType.Const -> ValType.matches(global.instance().type, import.type())
                }

            if (!typesMatch || import.mutabilityType() != global.instance().mutabilityType) {
                throw UnlinkableException("incompatible import type")
            }
        }

        private fun checkHostTagType(import: TagImport, tag: ImportTag): Boolean =
            try {
                validateHostTagType(import, tag)
                true
            } catch (_: UnlinkableException) {
                false
            }

        private fun validateHostTagType(import: TagImport, tag: ImportTag) {
            val expectedType = module.typeSection().getType(import.tagType().typeIdx())
            val gotType = tag.tag().type()!!
            if (
                expectedType.params().size != gotType.params().size ||
                    expectedType.returns().size != gotType.returns().size
            ) {
                throw UnlinkableException(
                    "incompatible import type for tag ${tag.module()}.${tag.name()}"
                )
            }
            for (j in expectedType.params().indices) {
                val expected = expectedType.params()[j]
                val got = gotType.params()[j]
                if (expected != got) {
                    throw UnlinkableException(
                        "incompatible import type for tag ${tag.module()}.${tag.name()}"
                    )
                }
            }
            for (j in expectedType.returns().indices) {
                val expected = expectedType.returns()[j]
                val got = gotType.returns()[j]
                if (expected != got) {
                    throw UnlinkableException(
                        "incompatible import type for tag ${tag.module()}.${tag.name()}"
                    )
                }
            }
        }

        private fun checkHostTableType(import: TableImport, table: ImportTable): Boolean =
            try {
                validateHostTableType(import, table)
                true
            } catch (_: UnlinkableException) {
                false
            }

        private fun validateHostTableType(import: TableImport, table: ImportTable) {
            val minExpected = table.table().limits().min()
            val maxExpected = table.table().limits().max()
            val minCurrent = import.limits().min()
            val maxCurrent = import.limits().max()
            if (import.entryType() != table.table().elementType()) {
                throw UnlinkableException("incompatible import type")
            } else if (
                minExpected < minCurrent ||
                    maxExpected > maxCurrent ||
                    table.table().limits().shared() != import.limits().shared()
            ) {
                throw UnlinkableException(
                    "incompatible import type, non-compatible limits, expected: " +
                        import.limits() +
                        ", current: " +
                        table.table().limits() +
                        " on table: " +
                        table.module() +
                        "." +
                        table.name()
                )
            }
        }

        private fun validateHostMemoryType(import: MemoryImport, memory: ImportMemory) {
            // Notice we do not compare to m.memory().initialPages() because m might have grown in
            // the meantime. Instead, we use the current number of pages.
            val hostMemory = memory.memory()!!
            val hostMemCurrentPages = hostMemory.pages()
            val hostMemMaxPages = hostMemory.maximumPages()
            val importInitialPages = import.limits().initialPages()
            val importMaxPages =
                if (import.limits().maximumPages() == MemoryLimits.MAX_PAGES) {
                    Memory.RUNTIME_MAX_PAGES
                } else {
                    import.limits().maximumPages()
                }

            // HostMem bounds [x,y] must be within the import bounds [a, b]; i.e., a <= x, y >= b.
            // In other words, the bounds are not valid when:
            // - HostMem current number of pages cannot be less than the import lower bound.
            // - HostMem upper bound cannot be larger than the given upper bound.
            if (
                hostMemCurrentPages < importInitialPages ||
                    hostMemMaxPages > importMaxPages ||
                    hostMemory.shared() != import.limits().shared()
            ) {
                throw UnlinkableException(
                    "incompatible import type, non-compatible limits, import: " +
                        import.limits() +
                        ", host initial pages: " +
                        hostMemory.initialPages() +
                        ", host max pages: " +
                        hostMemory.maximumPages() +
                        ", host shared: " +
                        hostMemory.shared() +
                        " on memory: " +
                        memory.module() +
                        "." +
                        memory.name()
                )
            }
        }

        private fun validateNegativeImportType(
            moduleName: String,
            name: String,
            external: Array<out ImportValue>,
        ) {
            for (value in external) {
                if (value.module() == moduleName && value.name() == name) {
                    throw UnlinkableException("incompatible import type")
                }
            }
        }

        private fun validateNegativeImportType(
            moduleName: String,
            name: String,
            type: ExternalType,
            importValues: ImportValues,
        ) {
            when (type) {
                ExternalType.FUNCTION -> {
                    validateNegativeImportType(moduleName, name, importValues.globals())
                    validateNegativeImportType(moduleName, name, importValues.memories())
                    validateNegativeImportType(moduleName, name, importValues.tables())
                    validateNegativeImportType(moduleName, name, importValues.tags())
                }
                ExternalType.GLOBAL -> {
                    validateNegativeImportType(moduleName, name, importValues.functions())
                    validateNegativeImportType(moduleName, name, importValues.memories())
                    validateNegativeImportType(moduleName, name, importValues.tables())
                    validateNegativeImportType(moduleName, name, importValues.tags())
                }
                ExternalType.MEMORY -> {
                    validateNegativeImportType(moduleName, name, importValues.functions())
                    validateNegativeImportType(moduleName, name, importValues.globals())
                    validateNegativeImportType(moduleName, name, importValues.tables())
                    validateNegativeImportType(moduleName, name, importValues.tags())
                }
                ExternalType.TABLE -> {
                    validateNegativeImportType(moduleName, name, importValues.functions())
                    validateNegativeImportType(moduleName, name, importValues.globals())
                    validateNegativeImportType(moduleName, name, importValues.memories())
                    validateNegativeImportType(moduleName, name, importValues.tags())
                }
                ExternalType.TAG -> {
                    validateNegativeImportType(moduleName, name, importValues.functions())
                    validateNegativeImportType(moduleName, name, importValues.globals())
                    validateNegativeImportType(moduleName, name, importValues.memories())
                    validateNegativeImportType(moduleName, name, importValues.tables())
                }
            }
        }

        private fun mapHostImports(
            imports: Array<Import>,
            importValues: ImportValues,
        ): ImportValues {
            val hostFuncs = ArrayList<ImportFunction>()
            val hostGlobals = ArrayList<ImportGlobal>()
            val hostMems = ArrayList<ImportMemory>()
            val hostTags = ArrayList<ImportTag>()
            val hostTables = ArrayList<ImportTable>()
            val names = imports.map { it.module() + "." + it.name() }

            for (impIdx in imports.indices) {
                val import = imports[impIdx]
                val name = import.module() + "." + import.name()
                val aliasesCount = names.count { it == name }
                var aliasNum = 0
                var found = false
                validateNegativeImportType(
                    import.module(),
                    import.name(),
                    import.importType(),
                    importValues,
                )
                val checkName = { value: ImportValue ->
                    import.module() == value.module() && import.name() == value.name()
                }

                when (import.importType()) {
                    ExternalType.FUNCTION -> {
                        for (j in 0 until importValues.functionCount()) {
                            val function = importValues.function(j)
                            if (checkName(function)) {
                                if (aliasesCount == 1 || ++aliasNum == aliasesCount) {
                                    validateExternalFunctionSignature(
                                        import as FunctionImport,
                                        function,
                                    )
                                } else if (
                                    !checkExternalFunctionSignature(
                                        import as FunctionImport,
                                        function,
                                    )
                                ) {
                                    continue
                                }
                                hostFuncs.add(function)
                                found = true
                                break
                            }
                        }
                    }
                    ExternalType.GLOBAL -> {
                        for (j in 0 until importValues.globalCount()) {
                            val global = importValues.global(j)
                            if (checkName(global)) {
                                if (aliasesCount == 1 || ++aliasNum == aliasesCount) {
                                    validateHostGlobalType(import as GlobalImport, global)
                                } else if (!checkHostGlobalType(import as GlobalImport, global)) {
                                    continue
                                }
                                hostGlobals.add(global)
                                found = true
                                break
                            }
                        }
                    }
                    ExternalType.MEMORY -> {
                        for (j in 0 until importValues.memoryCount()) {
                            val memory = importValues.memory(j)
                            if (checkName(memory)) {
                                validateHostMemoryType(import as MemoryImport, memory)
                                hostMems.add(memory)
                                found = true
                                break
                            }
                        }
                    }
                    ExternalType.TABLE -> {
                        for (j in 0 until importValues.tableCount()) {
                            val table = importValues.table(j)
                            if (checkName(table)) {
                                if (aliasesCount == 1 || ++aliasNum == aliasesCount) {
                                    validateHostTableType(import as TableImport, table)
                                } else if (!checkHostTableType(import as TableImport, table)) {
                                    continue
                                }
                                hostTables.add(table)
                                found = true
                                break
                            }
                        }
                    }
                    ExternalType.TAG -> {
                        for (j in 0 until importValues.tagCount()) {
                            val tag = importValues.tag(j)
                            if (checkName(tag)) {
                                if (aliasesCount == 1 || ++aliasNum == aliasesCount) {
                                    validateHostTagType(import as TagImport, tag)
                                } else if (!checkHostTagType(import as TagImport, tag)) {
                                    continue
                                }
                                hostTags.add(tag)
                                found = true
                                break
                            }
                        }
                    }
                }

                if (!found) {
                    throw UnlinkableException(
                        "unknown import, could not find host function for import number: $impIdx named $name"
                    )
                }
            }

            return ImportValues.builder()
                .addFunction(*hostFuncs.toTypedArray())
                .addGlobal(*hostGlobals.toTypedArray())
                .addMemory(*hostMems.toTypedArray())
                .addTable(*hostTables.toTypedArray())
                .addTag(*hostTags.toTypedArray())
                .build()
        }

        private fun genExports(exportSection: ExportSection): Map<String, Export> {
            val exports = HashMap<String, Export>()
            val count = exportSection.exportCount()
            for (i in 0 until count) {
                val export = exportSection.getExport(i)
                if (exports.containsKey(export.name())) {
                    throw InvalidException("duplicate export name ${export.name()}")
                }
                exports[export.name()] = export
            }
            return exports
        }

        fun build(): Instance {
            val hostInstance = buildHostInstance(initializeInstance = true, startInstance = false)
            try {
                val platformExecution =
                    RuntimePlatform.createPlatformExecution(
                        module,
                        hostInstance.imports(),
                        executionBackend,
                        hostInstance,
                        memoryLimits,
                    )
                        ?: throw WasmEngineException(
                            "${executionBackend.name.lowercase()} WebAssembly execution is not available"
                        )
                hostInstance.platformExecution = platformExecution
                hostInstance.resolvedExecutionBackend = platformExecution.backend
                val startFunction = hostInstance.exports[START_FUNCTION_NAME]
                if (startFunction != null) {
                    try {
                        hostInstance.export(START_FUNCTION_NAME).apply()
                    } catch (_: ExecutionCompletedException) {
                        // return
                    }
                }
                hostInstance.gcSafePoint()
                return hostInstance
            } catch (failure: Throwable) {
                hostInstance.close()
                throw failure
            }
        }

        private fun buildHostInstance(
            initializeInstance: Boolean,
            startInstance: Boolean,
        ): Instance {
            val exports = genExports(module.exportSection())
            val globalInitializers = module.globalSection().globals()
            val dataSegments = module.dataSection().dataSegments()
            val types = module.typeSection().types()
            val numFuncTypes =
                module.functionSection().functionCount() +
                    module.importSection().count(ExternalType.FUNCTION)
            val functions = module.codeSection().functionBodies()
            val functionTypes = IntArray(numFuncTypes)
            var funcIdx = 0

            val importCount = module.importSection().importCount()
            val imports = arrayOfNulls<Import>(importCount)
            for (i in 0 until importCount) {
                val import = module.importSection().getImport(i)
                if (import.importType() == ExternalType.FUNCTION) {
                    val type = (import as FunctionImport).typeIndex()
                    if (type >= module.typeSection().subTypeCount()) {
                        throw InvalidException("unknown type")
                    }
                    functionTypes[funcIdx++] = type
                }
                imports[i] = import
            }

            val mappedHostImports =
                mapHostImports(imports.requireNoNulls(), importValues ?: ImportValues.empty())

            for (i in 0 until module.functionSection().functionCount()) {
                functionTypes[funcIdx++] = module.functionSection().getFunctionType(i)
            }

            val tableLength = module.tableSection().tableCount()
            val tables = Array(tableLength) { i -> module.tableSection().getTable(i) }
            val elements = module.elementSection().elements()

            var memories = emptyArray<Memory>()
            module.memorySection()?.let { memSection ->
                memories =
                    Array(memSection.memoryCount()) { i ->
                        val limits = memSection.getMemory(i).limits()
                        val effectiveLimits = if (i == 0) memoryLimits ?: limits else limits
                        defaultMemoryFactory(effectiveLimits)
                    }
            }

            for (export in exports.values) {
                when (export.exportType()) {
                    ExternalType.FUNCTION -> {
                        if (
                            export.index() >=
                                module.functionSection().functionCount() +
                                    mappedHostImports.functionCount()
                        ) {
                            throw InvalidException("unknown function ${export.index()}")
                        }
                    }
                    ExternalType.GLOBAL -> {
                        if (
                            export.index() >=
                                module.globalSection().globalCount() +
                                    mappedHostImports.globalCount()
                        ) {
                            throw InvalidException("unknown global ${export.index()}")
                        }
                    }
                    ExternalType.TABLE -> {
                        if (
                            export.index() >=
                                module.tableSection().tableCount() + mappedHostImports.tableCount()
                        ) {
                            throw InvalidException("unknown table ${export.index()}")
                        }
                    }
                    ExternalType.MEMORY -> {
                        val memoryCount = module.memorySection()?.memoryCount() ?: 0
                        if (export.index() >= memoryCount + mappedHostImports.memoryCount()) {
                            throw InvalidException("unknown memory $export")
                        }
                    }
                    ExternalType.TAG -> {
                        // Tags are validated through imports and tag access.
                    }
                }
            }

            return Instance(
                module,
                wasmtimeExecutionConfig,
                globalInitializers,
                memories,
                dataSegments,
                functions,
                types,
                functionTypes,
                mappedHostImports,
                tables,
                elements,
                module.tagSection()?.types(),
                exports,
                initializeInstance,
                startInstance,
            )
        }

        companion object {
            internal fun create(module: WasmModule): Builder =
                Builder(
                    module,
                    RuntimePlatform.defaultMemoryFactory(),
                )

            internal fun create(
                module: WasmModule,
                defaultMemoryFactory: (MemoryLimits) -> Memory,
            ): Builder = Builder(module, defaultMemoryFactory)
        }
    }

    companion object {
        const val START_FUNCTION_NAME: String = "_start"

        @RuntimeJvmStatic
        fun builder(module: WasmModule): Builder = Builder.create(module)
    }
}

private fun WasmModule.cappedFirstMemoryLimits(maxMemoryBytes: Long): MemoryLimits {
    val maxPages = (maxMemoryBytes / Memory.PAGE_SIZE.toLong()).toInt()
    val memory = memorySection()?.getMemory(0) ?: return MemoryLimits(0, maxPages)
    val declaredLimits = memory.limits()
    return MemoryLimits(
        declaredLimits.initialPages(),
        minOf(declaredLimits.maximumPages(), maxPages),
        declaredLimits.shared(),
    )
}
