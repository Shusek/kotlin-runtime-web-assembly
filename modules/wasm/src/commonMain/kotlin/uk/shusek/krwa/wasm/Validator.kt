package uk.shusek.krwa.wasm

import uk.shusek.krwa.wasm.types.*

// Heavily inspired by wazero
// https://github.com/tetratelabs/wazero/blob/5a8a053bff0ae795b264de9672016745cb842070/internal/wasm/func_validation.go
// control flow implementation follows:
// https://webassembly.github.io/spec/core/appendix/algorithm.html
internal class Validator(
    module: WasmModule,
    private val multiTable: Boolean,
    private val multiMemory: Boolean,
    private val localGlobalReferencesInConstantExpressions: Boolean,
) {
    private fun isNum(t: ValType): Boolean = t.isNumeric() || t == ValType.BOT

    private fun isRef(t: ValType): Boolean = t.isReference() || t == ValType.BOT

    private class CtrlFrame(
        val opCode: OpCode?,
        val startTypes: List<ValType>,
        val endTypes: List<ValType>,
        val height: Int,
        val initHeight: Int,
        var unreachable: Boolean,
        var hasElse: Boolean,
    )

    private val valTypeStack: MutableList<ValType> = mutableListOf()
    private val ctrlFrameStack: MutableList<CtrlFrame> = mutableListOf()
    private val initStack: MutableList<Int> = mutableListOf()
    private val errors: MutableList<InvalidException> = mutableListOf()

    private val module: WasmModule = module
    private val locals: MutableList<ValType> = mutableListOf()
    private val localsInitialized: MutableList<Boolean> = mutableListOf()
    private val globalImports: List<Global>
    private val functionImports: List<Int>
    private val tableImports: List<ValType>
    private val tagImports: List<TagType>
    private val memoryImports: Int
    private val declaredFunctions: Set<Int>

    init {
        val imports = this.module.importSection().imports().asList()

        globalImports =
            imports
                .filterIsInstance<GlobalImport>()
                .map { i -> Global(i.type(), i.mutabilityType(), listOf()) }

        functionImports =
            imports
                .filterIsInstance<FunctionImport>()
                .map { it.typeIndex() }

        tableImports =
            imports
                .filterIsInstance<TableImport>()
                .map { it.entryType() }

        memoryImports = this.module.importSection().count(ExternalType.MEMORY)

        declaredFunctions =
            this.module
                .elementSection()
                .elements()
                .flatMap { element -> element.initializers() }
                .flatMap { declaredFunctions(it) }
                .toSet()

        tagImports =
            imports
                .filterIsInstance<TagImport>()
                .map { it.tagType() }
    }

    private val typesWithDefaultValue =
        intArrayOf(
            ValType.ID.F64,
            ValType.ID.F32,
            ValType.ID.I64,
            ValType.ID.I32,
            ValType.ID.V128,
            ValType.ID.RefNull,
        )

    private fun declaredFunctions(init: List<Instruction>): List<Int> {
        if (init.isNotEmpty() && init[0].opcode() == OpCode.REF_FUNC) {
            val idx = init[0].operand(0).toInt()
            getFunctionType(idx)
            if (idx >= functionImports.size) {
                return listOf(idx)
            }
        }
        return emptyList()
    }

    private fun typeSection(): TypeSection {
        return module.typeSection()
    }

    private fun typeMatches(t1: ValType, t2: ValType): Boolean {
        return ValType.matches(t1, t2, typeSection())
    }

    private fun pushVal(valType: ValType) {
        valTypeStack.add(valType)
    }

    private fun popVal(): ValType {
        val frame = peekCtrl()
        if (valTypeStack.size == frame.height && frame.unreachable) {
            return ValType.BOT
        }
        if (valTypeStack.size == frame.height) {
            errors.add(
                InvalidException("type mismatch: instruction requires [i32] but stack has []")
            )
            return ValType.BOT
        }
        return valTypeStack.removeAt(valTypeStack.size - 1)
    }

    private fun popVal(expected: ValType): ValType {
        val actual = popVal()
        if (
            !typeMatches(actual, expected) &&
                !actual.equals(ValType.BOT) &&
                !expected.equals(ValType.BOT)
        ) {
            errors.add(
                InvalidException(
                    "type mismatch: instruction requires [" +
                        expected.toString().lowercase() +
                        "] but stack has [" +
                        actual.toString().lowercase() +
                        "]"
                )
            )
        }
        return actual
    }

    private fun popRef(): ValType {
        val actual = popVal()
        if (!isRef(actual)) {
            errors.add(
                InvalidException(
                    "type mismatch, popRef(), expected reference type" + " but got: " + actual
                )
            )
        }
        if (actual.equals(ValType.BOT)) {
            return ValType.RefBot
        }

        return actual
    }

    private fun pushVals(valTypes: List<ValType>) {
        for (t in valTypes) {
            pushVal(t)
        }
    }

    private fun popVals(valTypes: List<ValType>): List<ValType> {
        val popped = arrayOfNulls<ValType>(valTypes.size)
        for (i in valTypes.size - 1 downTo 0) {
            popped[i] = popVal(valTypes.get(i))
        }
        return popped.filterNotNull()
    }

    private fun getLocal(idx: Int): ValType {
        if (idx >= locals.size) {
            throw InvalidException("unknown local " + idx)
        }
        if (!localsInitialized.get(idx)) {
            errors.add(InvalidException("uninitialized local: index " + idx))
        }
        return getLocalType(idx)
    }

    private fun setLocal(idx: Int) {
        if (idx >= locals.size) {
            throw InvalidException("unknown local " + idx)
        }
        if (!localsInitialized.get(idx)) {
            initStack.add(idx)
            localsInitialized.set(idx, true)
        }
    }

    private fun resetLocals(height: Int) {
        while (initStack.size > height) {
            localsInitialized.set(initStack.removeAt(initStack.size - 1), false)
        }
    }

    private fun pushCtrl(opCode: OpCode?, inTypes: List<ValType>, out: List<ValType>) {
        val frame = CtrlFrame(opCode, inTypes, out, valTypeStack.size, initStack.size, false, false)
        pushCtrl(frame)
        pushVals(inTypes)
    }

    private fun pushCtrl(frame: CtrlFrame) {
        ctrlFrameStack.add(frame)
    }

    private fun popCtrl(): CtrlFrame {
        if (ctrlFrameStack.isEmpty()) {
            errors.add(InvalidException("type mismatch, control frame stack empty"))
        }
        val frame = peekCtrl()
        popVals(frame.endTypes)
        if (valTypeStack.size != frame.height) {
            errors.add(InvalidException("type mismatch, mismatching stack height"))
        }
        resetLocals(frame.initHeight)
        ctrlFrameStack.removeAt(ctrlFrameStack.size - 1)
        return frame
    }

    private fun peekCtrl(): CtrlFrame {
        return ctrlFrameStack.get(ctrlFrameStack.size - 1)
    }

    private fun getCtrl(n: Int): CtrlFrame {
        return ctrlFrameStack.get(ctrlFrameStack.size - 1 - n)
    }

    private fun labelTypes(frame: CtrlFrame): List<ValType> {
        return if (frame.opCode == OpCode.LOOP) frame.startTypes else frame.endTypes
    }

    private fun resetAtStackLimit() {
        val frame = peekCtrl()
        while (valTypeStack.size > frame.height) {
            valTypeStack.removeAt(valTypeStack.size - 1)
        }
    }

    private fun unreachable() {
        val frame = peekCtrl()
        resetAtStackLimit()
        frame.unreachable = true
    }

    private fun validateMemory(id: Int) {
        val totalMemories = (module.memorySection()?.memoryCount() ?: 0) + memoryImports
        if (id < 0 || id >= totalMemories) {
            throw InvalidException("unknown memory " + id)
        }
    }

    private fun validateMemAlign(current: Long, expected: Long) {
        if (current != expected) {
            throw InvalidException(
                "invalid memory alignement, current: " + current + ", expected: " + expected
            )
        }
    }

    private fun validateLane(id: Int, max: Int) {
        if (id < 0 || id >= max) {
            throw InvalidException("invalid lane index " + id + " max is " + max)
        }
    }

    private fun validateDataSegment(idx: Int) {
        if (idx < 0 || idx >= module.dataSection().dataSegmentCount()) {
            throw InvalidException("unknown data segment " + idx)
        }
    }

    private fun valType(id: Long): ValType {
        return ValType.builder().fromId(id).build().resolve(module.typeSection())
    }

    private fun valType(opcode: Int, typeIdx: Int): ValType {
        return ValType.builder()
            .withOpcode(opcode)
            .withTypeIdx(typeIdx)
            .build()
            .resolve(module.typeSection())
    }

    private fun getReturns(op: AnnotatedInstruction): List<ValType> {
        val typeId = op.operand(0)
        if (typeId == 0x40L) { // epsilon
            return listOf()
        }
        if (ValType.isValid(typeId)) {
            return listOf(valType(typeId))
        }
        return getType(typeId.toInt()).returns()
    }

    private fun getParams(op: AnnotatedInstruction): List<ValType> {
        val typeId = op.operand(0)
        if (typeId == 0x40L) { // epsilon
            return listOf()
        }
        if (ValType.isValid(typeId)) {
            return listOf()
        }
        if (typeId >= module.typeSection().subTypeCount()) {
            throw MalformedException("unexpected end")
        }
        return getType(typeId.toInt()).params()
    }

    private fun getLocalType(idx: Int): ValType {
        if (idx >= locals.size) {
            throw InvalidException("unknown local " + idx)
        }
        return locals.get(idx)
    }

    private fun getType(idx: Int): FunctionType {
        if (idx < 0 || idx >= module.typeSection().subTypeCount()) {
            throw InvalidException("unknown type " + idx)
        }
        return module.typeSection().getType(idx)
    }

    private fun getGlobal(idx: Int): Global {
        if (idx < 0 || idx >= globalImports.size + module.globalSection().globalCount()) {
            throw InvalidException("unknown global " + idx)
        }
        if (idx < globalImports.size) {
            return globalImports.get(idx)
        }
        return module.globalSection().getGlobal(idx - globalImports.size)
    }

    private fun getFunctionType(idx: Int): Int {
        if (idx < 0 || idx >= functionImports.size + module.functionSection().functionCount()) {
            throw InvalidException("unknown function " + idx)
        }
        if (idx < functionImports.size) {
            return functionImports.get(idx)
        }
        return module.functionSection().getFunctionType(idx - functionImports.size)
    }

    private fun getTableType(idx: Int): ValType {
        if (idx < 0 || idx >= tableImports.size + module.tableSection().tableCount()) {
            throw InvalidException("unknown table " + idx)
        }
        if (idx < tableImports.size) {
            return tableImports.get(idx)
        }
        return module.tableSection().getTable(idx - tableImports.size).elementType()
    }

    private fun getTagType(idx: Int): TagType {
        val tagCount = module.tagSection()?.tagCount() ?: 0
        if (idx < 0 || idx >= tagImports.size + tagCount) {
            throw InvalidException("unknown tag " + idx)
        }
        if (idx < tagImports.size) {
            return tagImports.get(idx)
        }
        return module.tagSection()!!.getTag(idx - tagImports.size)
    }

    private fun nonNullExnRef(): ValType =
        valType(ValType.ID.Ref, ValType.TypeIdxCode.EXN.code())

    private fun getElement(idx: Int): Element {
        if (idx < 0 || idx >= module.elementSection().elementCount()) {
            throw InvalidException("unknown elem segment " + idx)
        }
        return module.elementSection().getElement(idx)
    }

    // GC helpers

    private fun getSubType(idx: Int): SubType {
        if (idx < 0 || idx >= module.typeSection().subTypeCount()) {
            throw InvalidException("unknown type " + idx)
        }
        return module.typeSection().getSubType(idx)
    }

    private fun getStructType(idx: Int): StructType {
        val st = getSubType(idx)
        if (st.compType().structType() == null) {
            throw InvalidException("expected struct type at index " + idx)
        }
        return st.compType().structType()!!
    }

    private fun getArrayType(idx: Int): ArrayType {
        val st = getSubType(idx)
        if (st.compType().arrayType() == null) {
            throw InvalidException("expected array type at index " + idx)
        }
        return st.compType().arrayType()!!
    }

    private fun unpackStorageType(st: StorageType): ValType {
        if (st.packedType() != null) {
            return ValType.I32
        }
        return st.valType()!!
    }

    private fun unpackFieldType(ft: FieldType): ValType {
        return unpackStorageType(ft.storageType())
    }

    /**
     * Returns the "top" abstract heap type for a given heap type index. Concrete struct/array → any
     * concrete func → func abstract types → their top.
     */
    private fun topOfHeapType(heapTypeIdx: Int): Int {
        if (heapTypeIdx >= 0) {
            val st = getSubType(heapTypeIdx)
            if (st.compType().funcType() != null) {
                return ValType.TypeIdxCode.FUNC.code()
            }
            return ValType.TypeIdxCode.ANY.code()
        }
        when (heapTypeIdx) {
            -18,
            -15,
            -19,
            -21,
            -22,
            -20 -> {
                return ValType.TypeIdxCode.ANY.code()
            }
            -16,
            -13 -> {
                return ValType.TypeIdxCode.FUNC.code()
            }
            -17,
            -14 -> {
                return ValType.TypeIdxCode.EXTERN.code()
            }
            -23 -> {
                return ValType.TypeIdxCode.EXN.code()
            }
            else -> {
                return heapTypeIdx
            }
        }
    }

    internal fun validateModule() {
        if (module.functionSection().functionCount() != module.codeSection().functionBodyCount()) {
            throw MalformedException("function and code section have inconsistent lengths")
        }

        if (!multiTable && tableImports.size + module.tableSection().tableCount() > 1) {
            throw InvalidException("multiple tables")
        }

        if (!multiMemory && memoryImports + (module.memorySection()?.memoryCount() ?: 0) > 1) {
            throw InvalidException("multiple memories")
        }

        val dataCountSection = module.dataCountSection()
        if (
            dataCountSection != null &&
                dataCountSection.dataCount() != module.dataSection().dataSegmentCount()
        ) {
            throw MalformedException("data count and data section have inconsistent lengths")
        }

        module.startSection()?.let { startSection ->
            val index = startSection.startIndex()
            if (index < 0 || index > Int.MAX_VALUE) {
                throw InvalidException("unknown function " + index)
            }
            val type = getType(getFunctionType(index.toInt()))
            if (!type.params().isEmpty() || !type.returns().isEmpty()) {
                throw InvalidException("invalid start function, must have empty signature " + type)
            }
        }
    }

    internal fun validateData() {
        // Validate offsets.
        val maxGlobals = constantExpressionGlobalLimit()
        for (ds in module.dataSection().dataSegments()) {
            if (ds is ActiveDataSegment) {
                val ads = ds
                validateMemory(ads.index().toInt())
                validateConstantExpression(ads.offsetInstructions(), ValType.I32, maxGlobals)
            }
        }
    }

    internal fun validateTypes() {
        var subTypeBase = 0
        for (i in 0 until (module.typeSection().typeCount())) {
            val t = module.typeSection().getRecType(i)
            val groupSize = t.subTypes().size
            // The valid range is [0, subTypeBase + groupSize) - within the current
            // recursion group forward refs are allowed, outside they are not
            val validUpperBound = subTypeBase + groupSize
            var flatIdx = subTypeBase
            for (st in t.subTypes()) {
                val comp = st.compType()
                val funcType = comp.funcType()
                val structType = comp.structType()
                val arrayType = comp.arrayType()
                if (funcType != null) {
                    validateTypeRefs(funcType.params(), validUpperBound)
                    validateTypeRefs(funcType.returns(), validUpperBound)
                }
                if (structType != null) {
                    for (ft in structType.fieldTypes()) {
                        validateStorageTypeRefs(ft.storageType(), validUpperBound)
                    }
                }
                if (arrayType != null) {
                    validateStorageTypeRefs(arrayType.fieldType().storageType(), validUpperBound)
                }
                // Validate supertype references and subtype validity
                for (sup in st.typeIdx()) {
                    if (sup < 0 || sup >= validUpperBound) {
                        throw InvalidException("unknown type " + sup)
                    }
                    val superSt = module.typeSection().getSubType(sup)
                    if (superSt.isFinal()) {
                        throw InvalidException("sub type " + flatIdx + " does not match super type")
                    }
                    validateSubtypeMatch(flatIdx, st.compType(), superSt.compType())
                }
                flatIdx++
            }
            subTypeBase += groupSize
        }
    }

    private fun validateStorageTypeRefs(st: StorageType, validUpperBound: Int) {
        if (st.valType() != null) {
            validateTypeRefs(listOf(st.valType()!!), validUpperBound)
        }
    }

    private fun validateSubtypeMatch(flatIdx: Int, sub: CompType, sup: CompType) {
        // Both must be the same kind (func/struct/array)
        val subFunc = sub.funcType()
        val supFunc = sup.funcType()
        val subStruct = sub.structType()
        val supStruct = sup.structType()
        val subArray = sub.arrayType()
        val supArray = sup.arrayType()
        if (subFunc != null && supFunc != null) {
            validateFuncSubtype(flatIdx, subFunc, supFunc)
        } else if (subStruct != null && supStruct != null) {
            validateStructSubtype(flatIdx, subStruct, supStruct)
        } else if (subArray != null && supArray != null) {
            validateFieldSubtype(flatIdx, subArray.fieldType(), supArray.fieldType())
        } else {
            throw InvalidException("sub type " + flatIdx + " does not match super type")
        }
    }

    private fun validateFuncSubtype(flatIdx: Int, sub: FunctionType, sup: FunctionType) {
        // Contravariant params, covariant returns
        if (sub.params().size != sup.params().size || sub.returns().size != sup.returns().size) {
            throw InvalidException("sub type " + flatIdx + " does not match super type")
        }
        for (i in 0 until (sub.params().size)) {
            if (!typeMatches(sup.params().get(i), sub.params().get(i))) {
                throw InvalidException("sub type " + flatIdx + " does not match super type")
            }
        }
        for (i in 0 until (sub.returns().size)) {
            if (!typeMatches(sub.returns().get(i), sup.returns().get(i))) {
                throw InvalidException("sub type " + flatIdx + " does not match super type")
            }
        }
    }

    private fun validateStructSubtype(flatIdx: Int, sub: StructType, sup: StructType) {
        // Subtype must have at least as many fields
        if (sub.fieldTypes().size < sup.fieldTypes().size) {
            throw InvalidException("sub type " + flatIdx + " does not match super type")
        }
        // First N fields must match
        for (i in 0 until (sup.fieldTypes().size)) {
            validateFieldSubtype(flatIdx, sub.fieldTypes()[i], sup.fieldTypes()[i])
        }
    }

    private fun validateFieldSubtype(flatIdx: Int, sub: FieldType, sup: FieldType) {
        if (sub.mut() != sup.mut()) {
            throw InvalidException("sub type " + flatIdx + " does not match super type")
        }
        if (sub.mut() == MutabilityType.Var) {
            // Mutable fields must be invariant (both directions)
            if (
                !storageTypeMatches(sub.storageType(), sup.storageType()) ||
                    !storageTypeMatches(sup.storageType(), sub.storageType())
            ) {
                throw InvalidException("sub type " + flatIdx + " does not match super type")
            }
        } else {
            // Immutable fields are covariant
            if (!storageTypeMatches(sub.storageType(), sup.storageType())) {
                throw InvalidException("sub type " + flatIdx + " does not match super type")
            }
        }
    }

    private fun storageTypeMatches(sub: StorageType, sup: StorageType): Boolean {
        if (sub.packedType() != null || sup.packedType() != null) {
            return sub.packedType() == sup.packedType()
        }
        return typeMatches(sub.valType()!!, sup.valType()!!)
    }

    private fun validateTypeRefs(types: List<ValType>, validUpperBound: Int) {
        for (v in types) {
            if (v.isReference() && v.typeIdx() >= 0) {
                if (v.typeIdx() >= validUpperBound) {
                    throw InvalidException("unknown type " + v.typeIdx())
                }
            }
        }
    }

    internal fun validateTags() {
        for (tagType in module.tagSection()?.types() ?: emptyArray<TagType>()) {
            val type = module.typeSection().getType(tagType.typeIdx())
            if (type.returns().isNotEmpty()) {
                throw InvalidException("non-empty tag result type index: " + tagType.typeIdx())
            }
        }
    }

    internal fun validateTables() {
        val maxGlobals = constantExpressionGlobalLimit()
        for (i in 0 until (module.tableSection().tableCount())) {
            val t = module.tableSection().getTable(i)
            validateConstantExpression(t.initialize(), t.elementType(), maxGlobals)
        }
    }

    internal fun validateElements() {
        // Validate offsets.
        val totalFunctions =
            module.functionSection().functionCount().toLong() +
                module.importSection().count(ExternalType.FUNCTION).toLong()
        val maxGlobals = constantExpressionGlobalLimit()
        for (el in module.elementSection().elements()) {
            validateValueType(el.type())
            var expectedElementType = el.type()
            if (el is ActiveElement) {
                val ae = el
                expectedElementType = getTableType(ae.tableIndex())
                if (!typeMatches(ae.type(), expectedElementType)) {
                    throw InvalidException("type mismatch, active element doesn't match table type")
                }
                validateConstantExpression(ae.offset(), ValType.I32, maxGlobals)
            }
            for (initializers in el.initializers()) {
                for (init in initializers) {
                    if (init.opcode() == OpCode.REF_FUNC) {
                        val idx = init.operands()[0]
                        if (idx < 0 || idx >= totalFunctions) {
                            throw InvalidException("unknown function " + idx)
                        }
                    }
                }
                validateConstantExpression(initializers, expectedElementType, maxGlobals)
            }
        }
    }

    internal fun validateGlobals() {
        val globalSection = module.globalSection()
        for (i in 0 until (globalSection.globalCount())) {
            val g = globalSection.getGlobal(i)
            validateConstantExpression(
                g.initInstructions(),
                g.valueType(),
                constantExpressionGlobalLimit(i),
            )
        }
    }

    private fun constantExpressionGlobalLimit(currentGlobalIdx: Int? = null): Int =
        if (localGlobalReferencesInConstantExpressions) {
            if (currentGlobalIdx == null) {
                globalImports.size + module.globalSection().globalCount()
            } else {
                globalImports.size + currentGlobalIdx
            }
        } else {
            globalImports.size
        }

    private fun validateConstantExpression(
        expr: List<Instruction>,
        expectedType: ValType,
        maxGlobalIdx: Int,
    ) {
        validateValueType(expectedType)
        val allFuncCount = functionImports.size + module.functionSection().functionCount()
        val valTypeStack = ArrayDeque<ValType>()
        for (instruction in expr) {
            when (instruction.opcode()) {
                OpCode.I32_CONST -> {
                    valTypeStack.addLast(ValType.I32)
                }
                OpCode.I32_ADD,
                OpCode.I32_SUB,
                OpCode.I32_MUL -> {
                    rejectReferenceTypedArithmeticConstant(expectedType)
                    valTypeStack.removeLast()
                    valTypeStack.removeLast()
                    valTypeStack.addLast(ValType.I32)
                }
                OpCode.I64_CONST -> {
                    valTypeStack.addLast(ValType.I64)
                }
                OpCode.I64_ADD,
                OpCode.I64_SUB,
                OpCode.I64_MUL -> {
                    rejectReferenceTypedArithmeticConstant(expectedType)
                    valTypeStack.removeLast()
                    valTypeStack.removeLast()
                    valTypeStack.addLast(ValType.I64)
                }
                OpCode.F32_CONST -> {
                    valTypeStack.addLast(ValType.F32)
                }
                OpCode.F64_CONST -> {
                    valTypeStack.addLast(ValType.F64)
                }
                OpCode.V128_CONST -> {
                    valTypeStack.addLast(ValType.V128)
                }
                OpCode.REF_NULL -> {
                    val operand = instruction.operand(0).toInt()
                    valTypeStack.addLast(valType(ValType.ID.RefNull, operand))
                }
                OpCode.REF_FUNC -> {
                    val idx = instruction.operand(0).toInt()
                    valTypeStack.addLast(valType(ValType.ID.Ref, getFunctionType(idx)))
                    if (idx < 0 || idx > allFuncCount) {
                        throw InvalidException("unknown function " + idx)
                    }
                }
                OpCode.GLOBAL_GET -> {
                    val idx = instruction.operand(0).toInt()
                    if (idx < 0 || idx >= maxGlobalIdx) {
                        throw InvalidException(
                            "unknown global " +
                                idx +
                                ", initializer expression can only reference" +
                                constantExpressionGlobalReferenceDescription()
                        )
                    }
                    if (idx < globalImports.size) {
                        val global = globalImports.get(idx)
                        if (global.mutabilityType() != MutabilityType.Const) {
                            throw InvalidException(
                                "constant expression required, initializer expression" +
                                    " cannot reference a mutable global"
                            )
                        }
                        valTypeStack.addLast(global.valueType())
                    } else {
                        // Reference to a preceding module global
                        val moduleGlobalIdx = idx - globalImports.size
                        val global = module.globalSection().getGlobal(moduleGlobalIdx)
                        if (global.mutabilityType() != MutabilityType.Const) {
                            throw InvalidException(
                                "constant expression required, initializer expression" +
                                    " cannot reference a mutable global"
                            )
                        }
                        valTypeStack.addLast(global.valueType())
                    }
                }
                OpCode.REF_I31 -> {
                    valTypeStack.removeLast() // I32
                    valTypeStack.addLast(
                        ValType.builder()
                            .withOpcode(ValType.ID.Ref)
                            .withTypeIdx(ValType.TypeIdxCode.I31.code())
                            .build()
                    )
                }
                OpCode.STRUCT_NEW -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val st = getStructType(typeIdx)
                    for (f in 0 until (st.fieldTypes().size)) {
                        valTypeStack.removeLast()
                    }
                    valTypeStack.addLast(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.STRUCT_NEW_DEFAULT -> {
                    val typeIdx = instruction.operand(0).toInt()
                    getStructType(typeIdx)
                    valTypeStack.addLast(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW -> {
                    val typeIdx = instruction.operand(0).toInt()
                    getArrayType(typeIdx)
                    valTypeStack.removeLast() // length
                    valTypeStack.removeLast() // fill value
                    valTypeStack.addLast(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_DEFAULT -> {
                    val typeIdx = instruction.operand(0).toInt()
                    getArrayType(typeIdx)
                    valTypeStack.removeLast() // length
                    valTypeStack.addLast(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_FIXED -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val count = instruction.operand(1).toInt()
                    getArrayType(typeIdx)
                    for (e in 0 until (count)) {
                        valTypeStack.removeLast()
                    }
                    valTypeStack.addLast(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ANY_CONVERT_EXTERN -> {
                    valTypeStack.removeLast()
                    valTypeStack.addLast(ValType.AnyRef)
                }
                OpCode.EXTERN_CONVERT_ANY -> {
                    valTypeStack.removeLast()
                    valTypeStack.addLast(ValType.ExternRef)
                }
                OpCode.END -> {}
                else -> {
                    throw InvalidException(
                        "constant expression required, but non-constant instruction" +
                            " encountered: " +
                            instruction
                    )
                }
            }
        }

        if (valTypeStack.size < 1) {
            throw InvalidException("type mismatch, no constant expressions found")
        }
        if (valTypeStack.size != 1) {
            throw InvalidException("type mismatch, values remaining on the stack after evaluation")
        } else {
            val exprType = valTypeStack.removeLast()
            if (!typeMatches(exprType, expectedType)) {
                throw InvalidException("type mismatch")
            }
        }
    }

    private fun rejectReferenceTypedArithmeticConstant(expectedType: ValType) {
        if (expectedType.isReference()) {
            throw InvalidException(
                "constant expression required, reference initializer cannot use arithmetic"
            )
        }
    }

    private fun constantExpressionGlobalReferenceDescription(): String =
        if (localGlobalReferencesInConstantExpressions) {
            " an imported or preceding global"
        } else {
            " an imported global"
        }

    internal fun validateFunctions() {
        for (i in 0 until (module.codeSection().functionBodyCount())) {
            val body = module.codeSection().getFunctionBody(i)
            val idx = functionImports.size + i
            val type = getType(getFunctionType(idx))
            validateFunction(idx, body, type)
        }
    }

    private fun hasDefaultValue(t: ValType): Boolean {
        for (t2 in typesWithDefaultValue) {
            if (t.opcode() == t2) {
                return true
            }
        }

        return false
    }

    private fun validateValueType(valueType: ValType) {
        if (valueType.isReference() && valueType.typeIdx() >= 0) {
            val idx = valueType.typeIdx()
            if (idx >= module.typeSection().subTypeCount()) {
                throw InvalidException("unknown type " + idx)
            }
        }
    }

    internal fun validateFunction(funcIdx: Int, body: FunctionBody, functionType: FunctionType) {
        valTypeStack.clear()
        locals.clear()
        localsInitialized.clear()

        for (t in functionType.params()) {
            validateValueType(t)
            locals.add(t)
            localsInitialized.add(true)
        }

        for (t in body.localTypes()) {
            validateValueType(t)
            locals.add(t)
            localsInitialized.add(hasDefaultValue(t))
        }

        functionType.returns().forEach { validateValueType(it) }

        pushCtrl(null, ArrayList(), functionType.returns())

        for (i in 0 until (body.instructions().size)) {
            val op = body.instructions().get(i)

            // control flow instructions handling
            when (op.opcode()) {
                OpCode.UNREACHABLE -> {
                    unreachable()
                }
                OpCode.TRY_TABLE -> {
                    val t1 = getParams(op)
                    val t2 = getReturns(op)
                    popVals(t1)
                    // and now the catches
                    val catches = CatchOpCode.decode(op.operands())

                    for (idx in 0 until (catches.size)) {
                        val currentCatch = catches.get(idx)
                        if (ctrlFrameStack.size < currentCatch.label()) {
                            throw InvalidException("something something")
                        }
                        // push_ctrl(catch, [], label_types(ctrls[handler.label]))
                        // using THROW instead of CATCH ... doesn't matter as it's removed right
                        // after
                        pushCtrl(OpCode.THROW, listOf(), labelTypes(getCtrl(currentCatch.label())))
                        when (currentCatch.opcode()) {
                            CatchOpCode.CATCH -> {
                                val tagType =
                                    module
                                        .typeSection()
                                        .getType(getTagType(currentCatch.tag()).typeIdx())
                                pushVals(tagType.params())
                            }
                            CatchOpCode.CATCH_REF -> {
                                val tagType =
                                    module
                                        .typeSection()
                                        .getType(getTagType(currentCatch.tag()).typeIdx())
                                pushVals(tagType.params())
                                pushVal(nonNullExnRef())
                            }
                            CatchOpCode.CATCH_ALL -> {}
                            CatchOpCode.CATCH_ALL_REF -> {
                                pushVal(nonNullExnRef())
                            }
                        }
                        popCtrl()
                    }
                    pushCtrl(op.opcode(), t1, t2)
                }
                OpCode.THROW -> {
                    val tagNumber = op.operand(0).toInt()
                    if (
                        tagImports.size + (module.tagSection()?.tagCount() ?: 0) <= tagNumber
                    ) {
                        throw InvalidException("unknown tag " + tagNumber)
                    }
                    val type = module.typeSection().getType(getTagType(tagNumber).typeIdx())
                    popVals(type.params())
                    assert(type.returns().size == 0)
                    unreachable()
                }
                OpCode.THROW_REF -> {
                    popVal(ValType.ExnRef)
                    unreachable()
                }
                OpCode.IF,
                OpCode.LOOP,
                OpCode.BLOCK -> {
                    if (op.opcode() == OpCode.IF) {
                        popVal(ValType.I32)
                    }
                    val t1 = getParams(op)
                    val t2 = getReturns(op)
                    t2.forEach { validateValueType(it) }
                    popVals(t1)
                    pushCtrl(op.opcode(), t1, t2)
                }
                OpCode.END -> {
                    val frame = popCtrl()
                    if (
                        frame.opCode == OpCode.IF &&
                            !frame.hasElse &&
                            frame.startTypes.size != frame.endTypes.size
                    ) {
                        throw InvalidException("type mismatch, unbalanced if branches")
                    }
                    pushVals(frame.endTypes)
                }
                OpCode.ELSE -> {
                    val frame = popCtrl()
                    if (frame.opCode != OpCode.IF) {
                        throw InvalidException("else doesn't belong to if")
                    }
                    pushCtrl(op.opcode(), frame.startTypes, frame.endTypes)
                    peekCtrl().hasElse = true
                }
                OpCode.BR -> {
                    val n = op.operand(0).toInt()
                    popVals(labelTypes(getCtrl(n)))
                    unreachable()
                }
                OpCode.BR_IF -> {
                    popVal(ValType.I32)
                    val n = op.operand(0).toInt()
                    val labelTypes = labelTypes(getCtrl(n))
                    popVals(labelTypes)
                    pushVals(labelTypes)
                }
                OpCode.BR_TABLE -> {
                    popVal(ValType.I32)
                    val m = op.operand(op.operandCount() - 1).toInt()
                    if ((ctrlFrameStack.size - 1 - m) < 0) {
                        throw InvalidException("unknown label " + m)
                    }
                    val defaultBranchLabelTypes = labelTypes(getCtrl(m))
                    val arity = defaultBranchLabelTypes.size
                    for (idx in 0 until (op.operandCount() - 1)) {
                        val n = op.operand(idx).toInt()
                        val ctrlFrame =
                            try {
                                getCtrl(n)
                            } catch (e: IndexOutOfBoundsException) {
                                throw InvalidException("unknown label", e)
                            }
                        val labelTypes = labelTypes(ctrlFrame)
                        if (labelTypes.size != arity) {
                            throw InvalidException(
                                "type mismatch, mismatched arity in BR_TABLE for label " + n
                            )
                        }
                        pushVals(popVals(labelTypes))
                    }
                    popVals(defaultBranchLabelTypes)
                    unreachable()
                }
                OpCode.BR_ON_NULL -> {
                    val n = op.operand(0).toInt()
                    val rt = popRef()
                    val labelTypes = labelTypes(getCtrl(n))
                    popVals(labelTypes)
                    pushVals(labelTypes)
                    pushVal(valType(ValType.ID.Ref, rt.typeIdx()))
                }
                OpCode.BR_ON_NON_NULL -> {
                    val n = op.operand(0).toInt()
                    val rt = popRef()
                    pushVal(valType(ValType.ID.Ref, rt.typeIdx()))
                    val labelTypes = labelTypes(getCtrl(n))
                    popVals(labelTypes)
                    pushVals(labelTypes)
                    popVal()
                }
                OpCode.RETURN -> {
                    VALIDATE_RETURN()
                }
                OpCode.RETURN_CALL -> {
                    VALIDATE_CALL(op.operand(0).toInt(), true)
                    VALIDATE_RETURN()
                }
                OpCode.RETURN_CALL_INDIRECT -> {
                    VALIDATE_CALL_INDIRECT(op.operand(0), op.operand(1).toInt(), true)
                    VALIDATE_RETURN()
                }
                OpCode.RETURN_CALL_REF -> {
                    VALIDATE_CALL_REF(op.operand(0).toInt(), true)
                    VALIDATE_RETURN()
                }
                else -> {}
            }

            when (op.opcode()) {
                OpCode.MEMORY_COPY -> {
                    validateMemory(op.operand(0).toInt())
                    validateMemory(op.operand(1).toInt())
                }
                OpCode.MEMORY_FILL -> {
                    validateMemory(op.operand(0).toInt())
                }
                OpCode.MEMORY_INIT -> {
                    validateMemory(op.operand(1).toInt())
                    validateDataSegment(op.operand(0).toInt())
                }
                OpCode.MEMORY_SIZE,
                OpCode.MEMORY_GROW -> {
                    validateMemory(op.operand(0).toInt())
                }
                OpCode.I32_LOAD,
                OpCode.I32_LOAD8_U,
                OpCode.I32_LOAD8_S,
                OpCode.I32_LOAD16_U,
                OpCode.I32_LOAD16_S,
                OpCode.I64_LOAD,
                OpCode.I64_LOAD8_S,
                OpCode.I64_LOAD8_U,
                OpCode.I64_LOAD16_S,
                OpCode.I64_LOAD16_U,
                OpCode.I64_LOAD32_S,
                OpCode.I64_LOAD32_U,
                OpCode.F32_LOAD,
                OpCode.F64_LOAD,
                OpCode.I32_STORE,
                OpCode.I32_STORE8,
                OpCode.I32_STORE16,
                OpCode.I64_STORE,
                OpCode.I64_STORE8,
                OpCode.I64_STORE16,
                OpCode.I64_STORE32,
                OpCode.F32_STORE,
                OpCode.F64_STORE,
                OpCode.V128_STORE,
                OpCode.I32_ATOMIC_LOAD8_U,
                OpCode.I32_ATOMIC_LOAD16_U,
                OpCode.I64_ATOMIC_LOAD8_U,
                OpCode.I64_ATOMIC_LOAD16_U,
                OpCode.I64_ATOMIC_LOAD32_U,
                OpCode.I32_ATOMIC_STORE,
                OpCode.I32_ATOMIC_STORE8,
                OpCode.I32_ATOMIC_STORE16,
                OpCode.I64_ATOMIC_STORE,
                OpCode.I64_ATOMIC_STORE8,
                OpCode.I64_ATOMIC_STORE16,
                OpCode.I64_ATOMIC_STORE32,
                OpCode.I32_ATOMIC_LOAD,
                OpCode.I64_ATOMIC_LOAD,
                OpCode.I32_ATOMIC_RMW_ADD,
                OpCode.I32_ATOMIC_RMW_CMPXCHG,
                OpCode.I32_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I32_ATOMIC_RMW16_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW_CMPXCHG,
                OpCode.I64_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW16_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW32_CMPXCHG_U,
                OpCode.I32_ATOMIC_RMW_XCHG,
                OpCode.I32_ATOMIC_RMW_OR,
                OpCode.I32_ATOMIC_RMW_XOR,
                OpCode.I32_ATOMIC_RMW_SUB,
                OpCode.I32_ATOMIC_RMW_AND,
                OpCode.I32_ATOMIC_RMW8_ADD_U,
                OpCode.I32_ATOMIC_RMW8_XCHG_U,
                OpCode.I32_ATOMIC_RMW8_OR_U,
                OpCode.I32_ATOMIC_RMW8_XOR_U,
                OpCode.I32_ATOMIC_RMW8_AND_U,
                OpCode.I32_ATOMIC_RMW8_SUB_U,
                OpCode.I32_ATOMIC_RMW16_ADD_U,
                OpCode.I32_ATOMIC_RMW16_XCHG_U,
                OpCode.I32_ATOMIC_RMW16_OR_U,
                OpCode.I32_ATOMIC_RMW16_XOR_U,
                OpCode.I32_ATOMIC_RMW16_AND_U,
                OpCode.I32_ATOMIC_RMW16_SUB_U,
                OpCode.I64_ATOMIC_RMW_ADD,
                OpCode.I64_ATOMIC_RMW_XCHG,
                OpCode.I64_ATOMIC_RMW_OR,
                OpCode.I64_ATOMIC_RMW_XOR,
                OpCode.I64_ATOMIC_RMW_SUB,
                OpCode.I64_ATOMIC_RMW_AND,
                OpCode.I64_ATOMIC_RMW8_ADD_U,
                OpCode.I64_ATOMIC_RMW8_XCHG_U,
                OpCode.I64_ATOMIC_RMW8_OR_U,
                OpCode.I64_ATOMIC_RMW8_XOR_U,
                OpCode.I64_ATOMIC_RMW8_AND_U,
                OpCode.I64_ATOMIC_RMW8_SUB_U,
                OpCode.I64_ATOMIC_RMW16_ADD_U,
                OpCode.I64_ATOMIC_RMW16_XCHG_U,
                OpCode.I64_ATOMIC_RMW16_OR_U,
                OpCode.I64_ATOMIC_RMW16_XOR_U,
                OpCode.I64_ATOMIC_RMW16_AND_U,
                OpCode.I64_ATOMIC_RMW16_SUB_U,
                OpCode.I64_ATOMIC_RMW32_ADD_U,
                OpCode.I64_ATOMIC_RMW32_XCHG_U,
                OpCode.I64_ATOMIC_RMW32_OR_U,
                OpCode.I64_ATOMIC_RMW32_XOR_U,
                OpCode.I64_ATOMIC_RMW32_AND_U,
                OpCode.I64_ATOMIC_RMW32_SUB_U,
                OpCode.MEM_ATOMIC_NOTIFY,
                OpCode.MEM_ATOMIC_WAIT32,
                OpCode.MEM_ATOMIC_WAIT64 -> {
                    validateMemory(op.operand(2).toInt())
                }
                else -> {}
            }

            when (op.opcode()) {
                OpCode.ATOMIC_FENCE,
                OpCode.I32_ATOMIC_STORE8,
                OpCode.I64_ATOMIC_STORE8,
                OpCode.I32_ATOMIC_LOAD8_U,
                OpCode.I64_ATOMIC_LOAD8_U,
                OpCode.I32_ATOMIC_RMW8_ADD_U,
                OpCode.I32_ATOMIC_RMW8_XCHG_U,
                OpCode.I32_ATOMIC_RMW8_OR_U,
                OpCode.I32_ATOMIC_RMW8_XOR_U,
                OpCode.I32_ATOMIC_RMW8_AND_U,
                OpCode.I32_ATOMIC_RMW8_SUB_U,
                OpCode.I64_ATOMIC_RMW8_ADD_U,
                OpCode.I64_ATOMIC_RMW8_XCHG_U,
                OpCode.I64_ATOMIC_RMW8_OR_U,
                OpCode.I64_ATOMIC_RMW8_XOR_U,
                OpCode.I64_ATOMIC_RMW8_AND_U,
                OpCode.I64_ATOMIC_RMW8_SUB_U,
                OpCode.I32_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW8_CMPXCHG_U -> {
                    validateMemAlign(op.operand(0), 0x00)
                }
                OpCode.I32_ATOMIC_STORE16,
                OpCode.I64_ATOMIC_STORE16,
                OpCode.I32_ATOMIC_LOAD16_U,
                OpCode.I64_ATOMIC_LOAD16_U,
                OpCode.I32_ATOMIC_RMW16_ADD_U,
                OpCode.I32_ATOMIC_RMW16_XCHG_U,
                OpCode.I32_ATOMIC_RMW16_OR_U,
                OpCode.I32_ATOMIC_RMW16_XOR_U,
                OpCode.I32_ATOMIC_RMW16_AND_U,
                OpCode.I32_ATOMIC_RMW16_SUB_U,
                OpCode.I64_ATOMIC_RMW16_ADD_U,
                OpCode.I64_ATOMIC_RMW16_XCHG_U,
                OpCode.I64_ATOMIC_RMW16_OR_U,
                OpCode.I64_ATOMIC_RMW16_XOR_U,
                OpCode.I64_ATOMIC_RMW16_AND_U,
                OpCode.I64_ATOMIC_RMW16_SUB_U,
                OpCode.I32_ATOMIC_RMW16_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW16_CMPXCHG_U -> {
                    validateMemAlign(op.operand(0), 0x01)
                }
                OpCode.I32_ATOMIC_STORE,
                OpCode.I64_ATOMIC_STORE32,
                OpCode.I32_ATOMIC_LOAD,
                OpCode.I64_ATOMIC_LOAD32_U,
                OpCode.I32_ATOMIC_RMW_ADD,
                OpCode.I32_ATOMIC_RMW_XCHG,
                OpCode.I32_ATOMIC_RMW_OR,
                OpCode.I32_ATOMIC_RMW_XOR,
                OpCode.I32_ATOMIC_RMW_SUB,
                OpCode.I32_ATOMIC_RMW_AND,
                OpCode.I64_ATOMIC_RMW32_ADD_U,
                OpCode.I64_ATOMIC_RMW32_XCHG_U,
                OpCode.I64_ATOMIC_RMW32_OR_U,
                OpCode.I64_ATOMIC_RMW32_XOR_U,
                OpCode.I64_ATOMIC_RMW32_AND_U,
                OpCode.I64_ATOMIC_RMW32_SUB_U,
                OpCode.I32_ATOMIC_RMW_CMPXCHG,
                OpCode.I64_ATOMIC_RMW32_CMPXCHG_U,
                OpCode.MEM_ATOMIC_NOTIFY,
                OpCode.MEM_ATOMIC_WAIT32 -> {
                    validateMemAlign(op.operand(0), 0x02)
                }
                OpCode.I64_ATOMIC_STORE,
                OpCode.I64_ATOMIC_LOAD,
                OpCode.I64_ATOMIC_RMW_ADD,
                OpCode.I64_ATOMIC_RMW_XCHG,
                OpCode.I64_ATOMIC_RMW_OR,
                OpCode.I64_ATOMIC_RMW_XOR,
                OpCode.I64_ATOMIC_RMW_SUB,
                OpCode.I64_ATOMIC_RMW_AND,
                OpCode.I64_ATOMIC_RMW_CMPXCHG,
                OpCode.MEM_ATOMIC_WAIT64 -> {
                    validateMemAlign(op.operand(0), 0x03)
                }
                else -> {}
            }

            when (op.opcode()) {
                OpCode.V128_LOAD8_LANE,
                OpCode.V128_STORE8_LANE -> {
                    validateLane(op.operand(3).toInt(), 16)
                }
                OpCode.V128_LOAD16_LANE,
                OpCode.V128_STORE16_LANE -> {
                    validateLane(op.operand(3).toInt(), 8)
                }
                OpCode.V128_LOAD32_LANE,
                OpCode.V128_STORE32_LANE -> {
                    validateLane(op.operand(3).toInt(), 4)
                }
                OpCode.V128_LOAD64_LANE,
                OpCode.V128_STORE64_LANE -> {
                    validateLane(op.operand(3).toInt(), 2)
                }
                OpCode.I8x16_REPLACE_LANE,
                OpCode.I8x16_EXTRACT_LANE_S,
                OpCode.I8x16_EXTRACT_LANE_U -> {
                    validateLane(op.operand(0).toInt(), 16)
                }
                OpCode.I16x8_REPLACE_LANE,
                OpCode.I16x8_EXTRACT_LANE_S,
                OpCode.I16x8_EXTRACT_LANE_U -> {
                    validateLane(op.operand(0).toInt(), 8)
                }
                OpCode.I32x4_REPLACE_LANE,
                OpCode.F32x4_REPLACE_LANE,
                OpCode.I32x4_EXTRACT_LANE,
                OpCode.F32x4_EXTRACT_LANE -> {
                    validateLane(op.operand(0).toInt(), 4)
                }
                OpCode.I64x2_REPLACE_LANE,
                OpCode.F64x2_REPLACE_LANE,
                OpCode.I64x2_EXTRACT_LANE,
                OpCode.F64x2_EXTRACT_LANE -> {
                    validateLane(op.operand(0).toInt(), 2)
                }
                OpCode.I8x16_SHUFFLE -> {
                    val operands = Value.vecTo8(longArrayOf(op.operand(0), op.operand(1)))
                    for (j in 0 until (16)) {
                        validateLane(operands[j].toInt(), 32)
                    }
                }
                else -> {}
            }

            when (op.opcode()) {
                OpCode.NOP,
                OpCode.UNREACHABLE,
                OpCode.THROW,
                OpCode.THROW_REF,
                OpCode.TRY_TABLE,
                OpCode.LOOP,
                OpCode.BLOCK,
                OpCode.IF,
                OpCode.ELSE,
                OpCode.RETURN,
                OpCode.RETURN_CALL,
                OpCode.RETURN_CALL_INDIRECT,
                OpCode.RETURN_CALL_REF,
                OpCode.BR_IF,
                OpCode.BR_TABLE,
                OpCode.BR,
                OpCode.BR_ON_NULL,
                OpCode.BR_ON_NON_NULL,
                OpCode.END,
                OpCode.ATOMIC_FENCE -> {}
                OpCode.DATA_DROP -> {
                    validateDataSegment(op.operand(0).toInt())
                }
                OpCode.DROP -> {
                    val t = popVal()

                    // setting the type hint
                    if (t.opcode() == ValType.ID.V128) {
                        op.setOperand(0, ValType.ID.V128.toLong())
                    }
                }
                OpCode.MEM_ATOMIC_NOTIFY -> {
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.MEM_ATOMIC_WAIT32 -> {
                    popVal(ValType.I64)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.MEM_ATOMIC_WAIT64 -> {
                    popVal(ValType.I64)
                    popVal(ValType.I64)
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.I32_STORE,
                OpCode.I32_ATOMIC_STORE,
                OpCode.I32_STORE8,
                OpCode.I32_ATOMIC_STORE8,
                OpCode.I32_STORE16,
                OpCode.I32_ATOMIC_STORE16 -> {
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                }
                OpCode.I32_LOAD,
                OpCode.I32_LOAD8_U,
                OpCode.I32_ATOMIC_LOAD8_U,
                OpCode.I32_LOAD8_S,
                OpCode.I32_LOAD16_U,
                OpCode.I32_ATOMIC_LOAD16_U,
                OpCode.I32_LOAD16_S,
                OpCode.I32_CLZ,
                OpCode.I32_CTZ,
                OpCode.I32_POPCNT,
                OpCode.I32_EXTEND_8_S,
                OpCode.I32_EXTEND_16_S,
                OpCode.I32_EQZ,
                OpCode.MEMORY_GROW,
                OpCode.I32_ATOMIC_LOAD -> {
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.TABLE_SIZE,
                OpCode.I32_CONST,
                OpCode.MEMORY_SIZE -> {
                    pushVal(ValType.I32)
                }
                OpCode.I32_ATOMIC_RMW_CMPXCHG,
                OpCode.I32_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I32_ATOMIC_RMW16_CMPXCHG_U -> {
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.I64_ATOMIC_RMW_CMPXCHG,
                OpCode.I64_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW16_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW32_CMPXCHG_U -> {
                    popVal(ValType.I64)
                    popVal(ValType.I64)
                    popVal(ValType.I32)
                    pushVal(ValType.I64)
                }
                OpCode.I32_ADD,
                OpCode.I32_ATOMIC_RMW_ADD,
                OpCode.I32_ATOMIC_RMW_XCHG,
                OpCode.I32_ATOMIC_RMW_OR,
                OpCode.I32_ATOMIC_RMW_XOR,
                OpCode.I32_ATOMIC_RMW_SUB,
                OpCode.I32_ATOMIC_RMW_AND,
                OpCode.I32_SUB,
                OpCode.I32_MUL,
                OpCode.I32_DIV_S,
                OpCode.I32_DIV_U,
                OpCode.I32_REM_S,
                OpCode.I32_REM_U,
                OpCode.I32_AND,
                OpCode.I32_OR,
                OpCode.I32_XOR,
                OpCode.I32_EQ,
                OpCode.I32_NE,
                OpCode.I32_LT_S,
                OpCode.I32_LT_U,
                OpCode.I32_LE_S,
                OpCode.I32_LE_U,
                OpCode.I32_GT_S,
                OpCode.I32_GT_U,
                OpCode.I32_GE_S,
                OpCode.I32_GE_U,
                OpCode.I32_SHL,
                OpCode.I32_SHR_U,
                OpCode.I32_SHR_S,
                OpCode.I32_ROTL,
                OpCode.I32_ROTR,
                OpCode.I32_ATOMIC_RMW8_ADD_U,
                OpCode.I32_ATOMIC_RMW8_XCHG_U,
                OpCode.I32_ATOMIC_RMW8_OR_U,
                OpCode.I32_ATOMIC_RMW8_XOR_U,
                OpCode.I32_ATOMIC_RMW8_AND_U,
                OpCode.I32_ATOMIC_RMW8_SUB_U,
                OpCode.I32_ATOMIC_RMW16_ADD_U,
                OpCode.I32_ATOMIC_RMW16_XCHG_U,
                OpCode.I32_ATOMIC_RMW16_OR_U,
                OpCode.I32_ATOMIC_RMW16_XOR_U,
                OpCode.I32_ATOMIC_RMW16_AND_U,
                OpCode.I32_ATOMIC_RMW16_SUB_U -> {
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(ValType.I32)
                }
                OpCode.I32_WRAP_I64,
                OpCode.I64_EQZ -> {
                    popVal(ValType.I64)
                    pushVal(ValType.I32)
                }
                OpCode.I32_TRUNC_F32_S,
                OpCode.I32_TRUNC_F32_U,
                OpCode.I32_TRUNC_SAT_F32_S,
                OpCode.I32_TRUNC_SAT_F32_U,
                OpCode.I32_REINTERPRET_F32 -> {
                    popVal(ValType.F32)
                    pushVal(ValType.I32)
                }
                OpCode.I32_TRUNC_F64_S,
                OpCode.I32_TRUNC_F64_U,
                OpCode.I32_TRUNC_SAT_F64_S,
                OpCode.I32_TRUNC_SAT_F64_U -> {
                    popVal(ValType.F64)
                    pushVal(ValType.I32)
                }
                OpCode.I64_LOAD,
                OpCode.I64_LOAD8_S,
                OpCode.I64_LOAD8_U,
                OpCode.I64_ATOMIC_LOAD8_U,
                OpCode.I64_LOAD16_S,
                OpCode.I64_LOAD16_U,
                OpCode.I64_ATOMIC_LOAD16_U,
                OpCode.I64_LOAD32_S,
                OpCode.I64_LOAD32_U,
                OpCode.I64_ATOMIC_LOAD32_U,
                OpCode.I64_EXTEND_I32_U,
                OpCode.I64_EXTEND_I32_S,
                OpCode.I64_ATOMIC_LOAD -> {
                    popVal(ValType.I32)
                    pushVal(ValType.I64)
                }
                OpCode.I64_ATOMIC_RMW_ADD,
                OpCode.I64_ATOMIC_RMW_XCHG,
                OpCode.I64_ATOMIC_RMW_OR,
                OpCode.I64_ATOMIC_RMW_XOR,
                OpCode.I64_ATOMIC_RMW_SUB,
                OpCode.I64_ATOMIC_RMW_AND,
                OpCode.I64_ATOMIC_RMW8_ADD_U,
                OpCode.I64_ATOMIC_RMW8_XCHG_U,
                OpCode.I64_ATOMIC_RMW8_OR_U,
                OpCode.I64_ATOMIC_RMW8_XOR_U,
                OpCode.I64_ATOMIC_RMW8_AND_U,
                OpCode.I64_ATOMIC_RMW8_SUB_U,
                OpCode.I64_ATOMIC_RMW16_ADD_U,
                OpCode.I64_ATOMIC_RMW16_XCHG_U,
                OpCode.I64_ATOMIC_RMW16_OR_U,
                OpCode.I64_ATOMIC_RMW16_XOR_U,
                OpCode.I64_ATOMIC_RMW16_AND_U,
                OpCode.I64_ATOMIC_RMW16_SUB_U,
                OpCode.I64_ATOMIC_RMW32_ADD_U,
                OpCode.I64_ATOMIC_RMW32_XCHG_U,
                OpCode.I64_ATOMIC_RMW32_OR_U,
                OpCode.I64_ATOMIC_RMW32_XOR_U,
                OpCode.I64_ATOMIC_RMW32_AND_U,
                OpCode.I64_ATOMIC_RMW32_SUB_U -> {
                    popVal(ValType.I64)
                    popVal(ValType.I32)
                    pushVal(ValType.I64)
                }
                OpCode.I64_CONST -> {
                    pushVal(ValType.I64)
                }
                OpCode.I64_STORE,
                OpCode.I64_ATOMIC_STORE,
                OpCode.I64_STORE8,
                OpCode.I64_ATOMIC_STORE8,
                OpCode.I64_STORE16,
                OpCode.I64_ATOMIC_STORE16,
                OpCode.I64_STORE32,
                OpCode.I64_ATOMIC_STORE32 -> {
                    popVal(ValType.I64)
                    popVal(ValType.I32)
                }
                OpCode.I64_ADD,
                OpCode.I64_SUB,
                OpCode.I64_MUL,
                OpCode.I64_DIV_S,
                OpCode.I64_DIV_U,
                OpCode.I64_REM_S,
                OpCode.I64_REM_U,
                OpCode.I64_AND,
                OpCode.I64_OR,
                OpCode.I64_XOR,
                OpCode.I64_SHL,
                OpCode.I64_SHR_U,
                OpCode.I64_SHR_S,
                OpCode.I64_ROTL,
                OpCode.I64_ROTR -> {
                    popVal(ValType.I64)
                    popVal(ValType.I64)
                    pushVal(ValType.I64)
                }
                OpCode.I64_EQ,
                OpCode.I64_NE,
                OpCode.I64_LT_S,
                OpCode.I64_LT_U,
                OpCode.I64_LE_S,
                OpCode.I64_LE_U,
                OpCode.I64_GT_S,
                OpCode.I64_GT_U,
                OpCode.I64_GE_S,
                OpCode.I64_GE_U -> {
                    popVal(ValType.I64)
                    popVal(ValType.I64)
                    pushVal(ValType.I32)
                }
                OpCode.I64_CLZ,
                OpCode.I64_CTZ,
                OpCode.I64_POPCNT,
                OpCode.I64_EXTEND_8_S,
                OpCode.I64_EXTEND_16_S,
                OpCode.I64_EXTEND_32_S -> {
                    popVal(ValType.I64)
                    pushVal(ValType.I64)
                }
                OpCode.I64_REINTERPRET_F64,
                OpCode.I64_TRUNC_F64_S,
                OpCode.I64_TRUNC_F64_U,
                OpCode.I64_TRUNC_SAT_F64_S,
                OpCode.I64_TRUNC_SAT_F64_U -> {
                    popVal(ValType.F64)
                    pushVal(ValType.I64)
                }
                OpCode.I64_TRUNC_F32_S,
                OpCode.I64_TRUNC_F32_U,
                OpCode.I64_TRUNC_SAT_F32_S,
                OpCode.I64_TRUNC_SAT_F32_U -> {
                    popVal(ValType.F32)
                    pushVal(ValType.I64)
                }
                OpCode.F32_STORE -> {
                    popVal(ValType.F32)
                    popVal(ValType.I32)
                }
                OpCode.F32_CONST -> {
                    pushVal(ValType.F32)
                }
                OpCode.F32_LOAD,
                OpCode.F32_CONVERT_I32_S,
                OpCode.F32_CONVERT_I32_U,
                OpCode.F32_REINTERPRET_I32 -> {
                    popVal(ValType.I32)
                    pushVal(ValType.F32)
                }
                OpCode.F32_CONVERT_I64_S,
                OpCode.F32_CONVERT_I64_U -> {
                    popVal(ValType.I64)
                    pushVal(ValType.F32)
                }
                OpCode.F64_LOAD,
                OpCode.F64_CONVERT_I32_S,
                OpCode.F64_CONVERT_I32_U -> {
                    popVal(ValType.I32)
                    pushVal(ValType.F64)
                }
                OpCode.F64_CONVERT_I64_S,
                OpCode.F64_CONVERT_I64_U,
                OpCode.F64_REINTERPRET_I64 -> {
                    popVal(ValType.I64)
                    pushVal(ValType.F64)
                }
                OpCode.F64_PROMOTE_F32 -> {
                    popVal(ValType.F32)
                    pushVal(ValType.F64)
                }
                OpCode.F32_DEMOTE_F64 -> {
                    popVal(ValType.F64)
                    pushVal(ValType.F32)
                }
                OpCode.F32_SQRT,
                OpCode.F32_ABS,
                OpCode.F32_NEG,
                OpCode.F32_CEIL,
                OpCode.F32_FLOOR,
                OpCode.F32_TRUNC,
                OpCode.F32_NEAREST -> {
                    popVal(ValType.F32)
                    pushVal(ValType.F32)
                }
                OpCode.F32_ADD,
                OpCode.F32_SUB,
                OpCode.F32_MUL,
                OpCode.F32_DIV,
                OpCode.F32_MIN,
                OpCode.F32_MAX,
                OpCode.F32_COPYSIGN -> {
                    popVal(ValType.F32)
                    popVal(ValType.F32)
                    pushVal(ValType.F32)
                }
                OpCode.F32_EQ,
                OpCode.F32_NE,
                OpCode.F32_LT,
                OpCode.F32_LE,
                OpCode.F32_GT,
                OpCode.F32_GE -> {
                    popVal(ValType.F32)
                    popVal(ValType.F32)
                    pushVal(ValType.I32)
                }
                OpCode.F64_STORE -> {
                    popVal(ValType.F64)
                    popVal(ValType.I32)
                }
                OpCode.F64_CONST -> {
                    pushVal(ValType.F64)
                }
                OpCode.F64_SQRT,
                OpCode.F64_ABS,
                OpCode.F64_NEG,
                OpCode.F64_CEIL,
                OpCode.F64_FLOOR,
                OpCode.F64_TRUNC,
                OpCode.F64_NEAREST -> {
                    popVal(ValType.F64)
                    pushVal(ValType.F64)
                }
                OpCode.F64_ADD,
                OpCode.F64_SUB,
                OpCode.F64_MUL,
                OpCode.F64_DIV,
                OpCode.F64_MIN,
                OpCode.F64_MAX,
                OpCode.F64_COPYSIGN -> {
                    popVal(ValType.F64)
                    popVal(ValType.F64)
                    pushVal(ValType.F64)
                }
                OpCode.F64_EQ,
                OpCode.F64_NE,
                OpCode.F64_LT,
                OpCode.F64_LE,
                OpCode.F64_GT,
                OpCode.F64_GE -> {
                    popVal(ValType.F64)
                    popVal(ValType.F64)
                    pushVal(ValType.I32)
                }
                OpCode.LOCAL_SET -> {
                    val index = op.operand(0).toInt()
                    popVal(getLocalType(index))
                    setLocal(index)
                }
                OpCode.LOCAL_GET -> {
                    val index = op.operand(0).toInt()
                    getLocal(index)
                    pushVal(getLocalType(index))
                }
                OpCode.LOCAL_TEE -> {
                    val index = op.operand(0).toInt()
                    val actualType = popVal()
                    setLocal(index)
                    val localType = getLocalType(index)
                    if (!typeMatches(actualType, localType)) {
                        throw InvalidException(
                            "type mismatch: local_tee: " + actualType + " " + localType
                        )
                    }
                    pushVal(localType)
                }
                OpCode.GLOBAL_GET -> {
                    val global = getGlobal(op.operand(0).toInt())
                    pushVal(global.valueType())
                }
                OpCode.GLOBAL_SET -> {
                    val global = getGlobal(op.operand(0).toInt())
                    if (global.mutabilityType() == MutabilityType.Const) {
                        // global.wast in the origin spec and function references
                        // have exact same test that exact two different errors
                        // TOOD: figure out which one
                        throw InvalidException("global is immutable, immutable global")
                    }
                    popVal(global.valueType())
                }
                OpCode.CALL -> {
                    VALIDATE_CALL(op.operand(0).toInt(), false)
                }
                OpCode.CALL_INDIRECT -> {
                    VALIDATE_CALL_INDIRECT(op.operand(0), op.operand(1).toInt(), false)
                }
                OpCode.CALL_REF -> {
                    VALIDATE_CALL_REF(op.operand(0).toInt(), false)
                }
                OpCode.REF_NULL -> {
                    val operand = op.operand(0).toInt()
                    val type = valType(ValType.ID.RefNull, operand)
                    pushVal(type)
                }
                OpCode.REF_IS_NULL -> {
                    popRef()
                    pushVal(ValType.I32)
                }
                OpCode.REF_AS_NON_NULL -> {
                    val rt = popRef()
                    pushVal(valType(ValType.ID.Ref, rt.typeIdx()))
                }
                OpCode.REF_FUNC -> {
                    val idx = op.operand(0).toInt()
                    if (
                        idx == funcIdx // reference to self
                        && !declaredFunctions.contains(idx)
                    ) {
                        throw InvalidException("undeclared function reference")
                    }
                    pushVal(valType(ValType.ID.Ref, getFunctionType(idx)))
                }
                OpCode.SELECT -> {
                    popVal(ValType.I32)
                    val t1 = popVal()
                    val t2 = popVal()

                    // setting the type hint
                    if (
                        (t1.opcode() == ValType.ID.V128 && t2.opcode() == ValType.ID.V128) ||
                            (t1.opcode() == ValType.ID.V128 && t2.opcode() == ValType.ID.BOT) ||
                            (t1.opcode() == ValType.ID.BOT && t2.opcode() == ValType.ID.V128)
                    ) {
                        op.setOperand(0, ValType.ID.V128.toLong())
                        pushVal(ValType.V128)
                    }

                    if (!(isNum(t1) && isNum(t2))) {
                        throw InvalidException(
                            "type mismatch: select should have numeric arguments but they" +
                                " are " +
                                t1 +
                                " " +
                                t2
                        )
                    }
                    if (!t1.equals(t2) && !t1.equals(ValType.BOT) && !t2.equals(ValType.BOT)) {
                        throw InvalidException("type mismatch, in SELECT t1: " + t1 + ", t2: " + t2)
                    }
                    if (t1.equals(ValType.BOT)) {
                        pushVal(t2)
                    } else {
                        pushVal(t1)
                    }
                }
                OpCode.SELECT_T -> {
                    popVal(ValType.I32)
                    if (op.operands().isEmpty() || op.operands().size > 1) {
                        throw InvalidException("invalid result arity")
                    }
                    val t = valType(op.operand(0))
                    validateValueType(t)
                    popVal(t)
                    popVal(t)
                    pushVal(t)
                }
                OpCode.TABLE_COPY -> {
                    val table1 = getTableType(op.operand(1).toInt())
                    val table2 = getTableType(op.operand(0).toInt())

                    if (!typeMatches(table1, table2)) {
                        throw InvalidException(
                            "type mismatch, table 1 type: " + table1 + ", table 2 type: " + table2
                        )
                    }

                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                }
                OpCode.TABLE_INIT -> {
                    val table = getTableType(op.operand(1).toInt())
                    val elemIdx = op.operand(0).toInt()
                    val elem = getElement(elemIdx)

                    if (!typeMatches(elem.type(), table)) {
                        throw InvalidException(
                            "type mismatch, table type: " + table + ", elem type: " + elem.type()
                        )
                    }

                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                }
                OpCode.MEMORY_COPY,
                OpCode.MEMORY_FILL,
                OpCode.MEMORY_INIT -> {
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                }
                OpCode.TABLE_FILL -> {
                    popVal(ValType.I32)
                    popVal(getTableType(op.operand(0).toInt()))
                    popVal(ValType.I32)
                }
                OpCode.TABLE_GET -> {
                    popVal(ValType.I32)
                    pushVal(getTableType(op.operand(0).toInt()))
                }
                OpCode.TABLE_SET -> {
                    popVal(getTableType(op.operand(0).toInt()))
                    popVal(ValType.I32)
                }
                OpCode.TABLE_GROW -> {
                    popVal(ValType.I32)
                    popVal(getTableType(op.operand(0).toInt()))
                    pushVal(ValType.I32)
                }
                OpCode.ELEM_DROP -> {
                    val index = op.operand(0).toInt()
                    getElement(index)
                }
                OpCode.V128_LOAD,
                OpCode.V128_LOAD8x8_S,
                OpCode.V128_LOAD8x8_U,
                OpCode.V128_LOAD16x4_S,
                OpCode.V128_LOAD16x4_U,
                OpCode.V128_LOAD32x2_S,
                OpCode.V128_LOAD32x2_U,
                OpCode.V128_LOAD8_SPLAT,
                OpCode.V128_LOAD16_SPLAT,
                OpCode.V128_LOAD32_SPLAT,
                OpCode.V128_LOAD64_SPLAT,
                OpCode.V128_LOAD32_ZERO,
                OpCode.V128_LOAD64_ZERO,
                OpCode.I8x16_SPLAT,
                OpCode.I16x8_SPLAT,
                OpCode.I32x4_SPLAT -> {
                    popVal(ValType.I32)
                    pushVal(ValType.V128)
                }
                OpCode.F32x4_SPLAT -> {
                    popVal(ValType.F32)
                    pushVal(ValType.V128)
                }
                OpCode.I64x2_SPLAT -> {
                    popVal(ValType.I64)
                    pushVal(ValType.V128)
                }
                OpCode.F64x2_SPLAT -> {
                    popVal(ValType.F64)
                    pushVal(ValType.V128)
                }
                OpCode.V128_CONST -> {
                    pushVal(ValType.V128)
                }
                OpCode.V128_LOAD8_LANE,
                OpCode.V128_LOAD16_LANE,
                OpCode.V128_LOAD32_LANE,
                OpCode.V128_LOAD64_LANE -> {
                    popVal(ValType.V128)
                    popVal(ValType.I32)
                    pushVal(ValType.V128)
                }
                OpCode.I8x16_REPLACE_LANE,
                OpCode.I16x8_REPLACE_LANE,
                OpCode.I32x4_REPLACE_LANE,
                OpCode.I8x16_SHL,
                OpCode.I8x16_SHR_S,
                OpCode.I8x16_SHR_U,
                OpCode.I16x8_SHL,
                OpCode.I16x8_SHR_S,
                OpCode.I16x8_SHR_U,
                OpCode.I32x4_SHL,
                OpCode.I32x4_SHR_S,
                OpCode.I32x4_SHR_U,
                OpCode.I64x2_SHL,
                OpCode.I64x2_SHR_S,
                OpCode.I64x2_SHR_U -> {
                    popVal(ValType.I32)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.I64x2_REPLACE_LANE -> {
                    popVal(ValType.I64)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.F32x4_REPLACE_LANE -> {
                    popVal(ValType.F32)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.F64x2_REPLACE_LANE -> {
                    popVal(ValType.F64)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.I8x16_ALL_TRUE,
                OpCode.I8x16_BITMASK,
                OpCode.I16x8_ALL_TRUE,
                OpCode.I16x8_BITMASK,
                OpCode.I32x4_ALL_TRUE,
                OpCode.I32x4_BITMASK,
                OpCode.I64x2_ALL_TRUE,
                OpCode.I64x2_BITMASK,
                OpCode.I8x16_EXTRACT_LANE_S,
                OpCode.I8x16_EXTRACT_LANE_U,
                OpCode.I16x8_EXTRACT_LANE_S,
                OpCode.I16x8_EXTRACT_LANE_U,
                OpCode.I32x4_EXTRACT_LANE -> {
                    popVal(ValType.V128)
                    pushVal(ValType.I32)
                }
                OpCode.F32x4_EXTRACT_LANE -> {
                    popVal(ValType.V128)
                    pushVal(ValType.F32)
                }
                OpCode.I64x2_EXTRACT_LANE -> {
                    popVal(ValType.V128)
                    pushVal(ValType.I64)
                }
                OpCode.F64x2_EXTRACT_LANE -> {
                    popVal(ValType.V128)
                    pushVal(ValType.F64)
                }
                OpCode.I8x16_SHUFFLE,
                OpCode.I8x16_SWIZZLE,
                OpCode.I8x16_EQ,
                OpCode.I8x16_NE,
                OpCode.I8x16_LT_S,
                OpCode.I8x16_LT_U,
                OpCode.I8x16_GT_S,
                OpCode.I8x16_GT_U,
                OpCode.I8x16_LE_S,
                OpCode.I8x16_LE_U,
                OpCode.I8x16_GE_S,
                OpCode.I8x16_GE_U,
                OpCode.I8x16_MIN_S,
                OpCode.I8x16_MIN_U,
                OpCode.I8x16_MAX_S,
                OpCode.I8x16_MAX_U,
                OpCode.I8x16_AVGR_U,
                OpCode.I8x16_SUB,
                OpCode.I8x16_SUB_SAT_S,
                OpCode.I8x16_SUB_SAT_U,
                OpCode.I8x16_ADD,
                OpCode.I8x16_ADD_SAT_S,
                OpCode.I8x16_ADD_SAT_U,
                OpCode.I8x16_NARROW_I16x8_S,
                OpCode.I8x16_NARROW_I16x8_U,
                OpCode.I16x8_NE,
                OpCode.I16x8_EQ,
                OpCode.I16x8_ADD,
                OpCode.I16x8_ADD_SAT_S,
                OpCode.I16x8_ADD_SAT_U,
                OpCode.I16x8_SUB,
                OpCode.I16x8_SUB_SAT_S,
                OpCode.I16x8_SUB_SAT_U,
                OpCode.I16x8_MUL,
                OpCode.I16x8_LT_S,
                OpCode.I16x8_LT_U,
                OpCode.I16x8_GT_S,
                OpCode.I16x8_GT_U,
                OpCode.I16x8_LE_S,
                OpCode.I16x8_LE_U,
                OpCode.I16x8_GE_S,
                OpCode.I16x8_GE_U,
                OpCode.I16x8_MIN_S,
                OpCode.I16x8_MIN_U,
                OpCode.I16x8_MAX_S,
                OpCode.I16x8_MAX_U,
                OpCode.I16x8_AVGR_U,
                OpCode.I16x8_NARROW_I32x4_S,
                OpCode.I16x8_NARROW_I32x4_U,
                OpCode.I16x8_Q15MULR_SAT_S,
                OpCode.I16x8_EXTMUL_LOW_I8x16_S,
                OpCode.I16x8_EXTMUL_HIGH_I8x16_S,
                OpCode.I16x8_EXTMUL_LOW_I8x16_U,
                OpCode.I16x8_EXTMUL_HIGH_I8x16_U,
                OpCode.F32x4_NE,
                OpCode.F32x4_LT,
                OpCode.F32x4_GT,
                OpCode.F32x4_LE,
                OpCode.F32x4_GE,
                OpCode.F32x4_EQ,
                OpCode.F32x4_MUL,
                OpCode.F32x4_MIN,
                OpCode.F32x4_MAX,
                OpCode.F32x4_PMIN,
                OpCode.F32x4_PMAX,
                OpCode.F32x4_DIV,
                OpCode.F32x4_ADD,
                OpCode.F32x4_SUB,
                OpCode.I32x4_NE,
                OpCode.I32x4_EQ,
                OpCode.I32x4_ADD,
                OpCode.I32x4_SUB,
                OpCode.I32x4_MUL,
                OpCode.I32x4_MIN_S,
                OpCode.I32x4_MIN_U,
                OpCode.I32x4_MAX_S,
                OpCode.I32x4_MAX_U,
                OpCode.I32x4_LT_S,
                OpCode.I32x4_LT_U,
                OpCode.I32x4_LE_S,
                OpCode.I32x4_LE_U,
                OpCode.I32x4_GE_S,
                OpCode.I32x4_GE_U,
                OpCode.I32x4_GT_S,
                OpCode.I32x4_GT_U,
                OpCode.I32x4_DOT_I16x8_S,
                OpCode.I32x4_EXTMUL_LOW_I16x8_S,
                OpCode.I32x4_EXTMUL_HIGH_I16x8_S,
                OpCode.I32x4_EXTMUL_LOW_I16x8_U,
                OpCode.I32x4_EXTMUL_HIGH_I16x8_U,
                OpCode.I64x2_NE,
                OpCode.I64x2_EQ,
                OpCode.I64x2_LT_S,
                OpCode.I64x2_LE_S,
                OpCode.I64x2_GT_S,
                OpCode.I64x2_GE_S,
                OpCode.I64x2_ADD,
                OpCode.I64x2_SUB,
                OpCode.I64x2_MUL,
                OpCode.I64x2_EXTMUL_LOW_I32x4_S,
                OpCode.I64x2_EXTMUL_HIGH_I32x4_S,
                OpCode.I64x2_EXTMUL_LOW_I32x4_U,
                OpCode.I64x2_EXTMUL_HIGH_I32x4_U,
                OpCode.F64x2_NE,
                OpCode.F64x2_LT,
                OpCode.F64x2_GT,
                OpCode.F64x2_LE,
                OpCode.F64x2_GE,
                OpCode.F64x2_DIV,
                OpCode.F64x2_MAX,
                OpCode.F64x2_MIN,
                OpCode.F64x2_PMAX,
                OpCode.F64x2_PMIN,
                OpCode.F64x2_EQ,
                OpCode.F64x2_ADD,
                OpCode.F64x2_SUB,
                OpCode.F64x2_MUL,
                OpCode.V128_AND,
                OpCode.V128_ANDNOT,
                OpCode.V128_OR,
                OpCode.V128_XOR -> {
                    popVal(ValType.V128)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.I8x16_NEG,
                OpCode.I8x16_ABS,
                OpCode.I8x16_POPCNT,
                OpCode.I16x8_EXTADD_PAIRWISE_I8x16_S,
                OpCode.I16x8_EXTADD_PAIRWISE_I8x16_U,
                OpCode.I16x8_NEG,
                OpCode.I16x8_ABS,
                OpCode.I16x8_EXTEND_LOW_I8x16_S,
                OpCode.I16x8_EXTEND_HIGH_I8x16_S,
                OpCode.I16x8_EXTEND_LOW_I8x16_U,
                OpCode.I16x8_EXTEND_HIGH_I8x16_U,
                OpCode.I32x4_NEG,
                OpCode.I32x4_ABS,
                OpCode.I32x4_EXTEND_LOW_I16x8_S,
                OpCode.I32x4_EXTEND_HIGH_I16x8_S,
                OpCode.I32x4_EXTEND_LOW_I16x8_U,
                OpCode.I32x4_EXTEND_HIGH_I16x8_U,
                OpCode.I32x4_EXTADD_PAIRWISE_I16x8_S,
                OpCode.I32x4_EXTADD_PAIRWISE_I16x8_U,
                OpCode.I64x2_EXTEND_LOW_I32x4_S,
                OpCode.I64x2_EXTEND_HIGH_I32x4_S,
                OpCode.I64x2_EXTEND_LOW_I32x4_U,
                OpCode.I64x2_EXTEND_HIGH_I32x4_U,
                OpCode.F32x4_NEG,
                OpCode.F32x4_ABS,
                OpCode.F32x4_SQRT,
                OpCode.I32x4_TRUNC_SAT_F32X4_S,
                OpCode.I32x4_TRUNC_SAT_F32X4_U,
                OpCode.I32x4_TRUNC_SAT_F64x2_S_ZERO,
                OpCode.I32x4_TRUNC_SAT_F64x2_U_ZERO,
                OpCode.F32x4_CONVERT_I32x4_S,
                OpCode.F32x4_CONVERT_I32x4_U,
                OpCode.F32x4_CEIL,
                OpCode.F32x4_FLOOR,
                OpCode.F32x4_TRUNC,
                OpCode.F32x4_NEAREST,
                OpCode.F64x2_ABS,
                OpCode.F64x2_NEG,
                OpCode.F64x2_SQRT,
                OpCode.F64x2_CEIL,
                OpCode.F64x2_FLOOR,
                OpCode.F64x2_NEAREST,
                OpCode.F64x2_TRUNC,
                OpCode.F64x2_CONVERT_LOW_I32x4_S,
                OpCode.F64x2_CONVERT_LOW_I32x4_U,
                OpCode.F64x2_PROMOTE_LOW_F32x4,
                OpCode.F32x4_DEMOTE_LOW_F64x2_ZERO,
                OpCode.I64x2_NEG,
                OpCode.I64x2_ABS,
                OpCode.V128_NOT -> {
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.V128_BITSELECT -> {
                    popVal(ValType.V128)
                    popVal(ValType.V128)
                    popVal(ValType.V128)
                    pushVal(ValType.V128)
                }
                OpCode.V128_ANY_TRUE -> {
                    popVal(ValType.V128)
                    pushVal(ValType.I32)
                }
                OpCode.V128_STORE,
                OpCode.V128_STORE8_LANE,
                OpCode.V128_STORE16_LANE,
                OpCode.V128_STORE32_LANE,
                OpCode.V128_STORE64_LANE -> {
                    popVal(ValType.V128)
                    popVal(ValType.I32)
                }
                OpCode.V128_LOAD8_LANE,
                OpCode.V128_LOAD16_LANE,
                OpCode.V128_LOAD32_LANE,
                OpCode.V128_LOAD64_LANE -> {
                    popVal(ValType.V128)
                    popVal(ValType.I32)
                    pushVal(ValType.V128)
                }
                // GC opcodes
                OpCode.REF_EQ -> {
                    popVal(ValType.EqRef)
                    popVal(ValType.EqRef)
                    pushVal(ValType.I32)
                }
                OpCode.STRUCT_NEW -> {
                    val typeIdx = op.operand(0).toInt()
                    val st = getStructType(typeIdx)
                    for (fi in st.fieldTypes().size - 1 downTo 0) {
                        popVal(unpackFieldType(st.fieldTypes()[fi]))
                    }
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.STRUCT_NEW_DEFAULT -> {
                    val typeIdx = op.operand(0).toInt()
                    val st = getStructType(typeIdx)
                    for (ft in st.fieldTypes()) {
                        val t = unpackFieldType(ft)
                        if (!hasDefaultValue(t)) {
                            throw InvalidException("field type is not defaultable")
                        }
                    }
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.STRUCT_GET -> {
                    val typeIdx = op.operand(0).toInt()
                    val fieldIdx = op.operand(1).toInt()
                    val st = getStructType(typeIdx)
                    if (fieldIdx >= st.fieldTypes().size) {
                        throw InvalidException("unknown field " + fieldIdx)
                    }
                    val ft = st.fieldTypes()[fieldIdx]
                    if (ft.storageType().packedType() != null) {
                        throw InvalidException("field is packed")
                    }
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                    pushVal(unpackFieldType(ft))
                }
                OpCode.STRUCT_GET_S,
                OpCode.STRUCT_GET_U -> {
                    val typeIdx = op.operand(0).toInt()
                    val fieldIdx = op.operand(1).toInt()
                    val st = getStructType(typeIdx)
                    if (fieldIdx >= st.fieldTypes().size) {
                        throw InvalidException("unknown field " + fieldIdx)
                    }
                    val ft = st.fieldTypes()[fieldIdx]
                    if (ft.storageType().packedType() == null) {
                        throw InvalidException("field is unpacked")
                    }
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                    pushVal(ValType.I32)
                }
                OpCode.STRUCT_SET -> {
                    val typeIdx = op.operand(0).toInt()
                    val fieldIdx = op.operand(1).toInt()
                    val st = getStructType(typeIdx)
                    if (fieldIdx >= st.fieldTypes().size) {
                        throw InvalidException("unknown field " + fieldIdx)
                    }
                    val ft = st.fieldTypes()[fieldIdx]
                    if (ft.mut() != MutabilityType.Var) {
                        throw InvalidException("field is immutable")
                    }
                    popVal(unpackFieldType(ft))
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                }
                OpCode.ARRAY_NEW -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    popVal(ValType.I32)
                    popVal(unpackFieldType(at.fieldType()))
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_DEFAULT -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    val t = unpackFieldType(at.fieldType())
                    if (!hasDefaultValue(t)) {
                        throw InvalidException("array type is not defaultable")
                    }
                    popVal(ValType.I32)
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_FIXED -> {
                    val typeIdx = op.operand(0).toInt()
                    val n = op.operand(1).toInt()
                    val at = getArrayType(typeIdx)
                    val elemType = unpackFieldType(at.fieldType())
                    for (fi in 0 until (n)) {
                        popVal(elemType)
                    }
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_DATA -> {
                    val typeIdx = op.operand(0).toInt()
                    val dataIdx = op.operand(1).toInt()
                    val at = getArrayType(typeIdx)
                    validateDataSegment(dataIdx)
                    val t = unpackFieldType(at.fieldType())
                    if (!t.isNumeric() && t.opcode() != ValType.ID.V128) {
                        throw InvalidException("array type is not numeric or vector")
                    }
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_NEW_ELEM -> {
                    val typeIdx = op.operand(0).toInt()
                    val elemIdx = op.operand(1).toInt()
                    val at = getArrayType(typeIdx)
                    val elem = getElement(elemIdx)
                    val arrElem = unpackFieldType(at.fieldType())
                    if (!typeMatches(elem.type(), arrElem)) {
                        throw InvalidException("type mismatch")
                    }
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    pushVal(valType(ValType.ID.Ref, typeIdx))
                }
                OpCode.ARRAY_GET -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().storageType().packedType() != null) {
                        throw InvalidException("array is packed")
                    }
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                    pushVal(unpackFieldType(at.fieldType()))
                }
                OpCode.ARRAY_GET_S,
                OpCode.ARRAY_GET_U -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().storageType().packedType() == null) {
                        throw InvalidException("array is unpacked")
                    }
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                    pushVal(ValType.I32)
                }
                OpCode.ARRAY_SET -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().mut() != MutabilityType.Var) {
                        throw InvalidException("array is immutable")
                    }
                    popVal(unpackFieldType(at.fieldType()))
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                }
                OpCode.ARRAY_LEN -> {
                    popVal(ValType.ArrayRef)
                    pushVal(ValType.I32)
                }
                OpCode.ARRAY_FILL -> {
                    val typeIdx = op.operand(0).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().mut() != MutabilityType.Var) {
                        throw InvalidException("array is immutable")
                    }
                    popVal(ValType.I32)
                    popVal(unpackFieldType(at.fieldType()))
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                }
                OpCode.ARRAY_COPY -> {
                    val dstIdx = op.operand(0).toInt()
                    val srcIdx = op.operand(1).toInt()
                    val dstAt = getArrayType(dstIdx)
                    val srcAt = getArrayType(srcIdx)
                    if (dstAt.fieldType().mut() != MutabilityType.Var) {
                        throw InvalidException("array is immutable")
                    }
                    // Compare storage types directly (not unpacked)
                    // to distinguish packed types like i8 vs i16
                    if (!srcAt.fieldType().storageType().equals(dstAt.fieldType().storageType())) {
                        throw InvalidException("array types do not match")
                    }
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, srcIdx))
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, dstIdx))
                }
                OpCode.ARRAY_INIT_DATA -> {
                    val typeIdx = op.operand(0).toInt()
                    val dataIdx = op.operand(1).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().mut() != MutabilityType.Var) {
                        throw InvalidException("array is immutable")
                    }
                    validateDataSegment(dataIdx)
                    val t = unpackFieldType(at.fieldType())
                    if (!t.isNumeric() && t.opcode() != ValType.ID.V128) {
                        throw InvalidException("array type is not numeric or vector")
                    }
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                }
                OpCode.ARRAY_INIT_ELEM -> {
                    val typeIdx = op.operand(0).toInt()
                    val elemIdx = op.operand(1).toInt()
                    val at = getArrayType(typeIdx)
                    if (at.fieldType().mut() != MutabilityType.Var) {
                        throw InvalidException("array is immutable")
                    }
                    val elem = getElement(elemIdx)
                    val arrElem = unpackFieldType(at.fieldType())
                    if (!typeMatches(elem.type(), arrElem)) {
                        throw InvalidException("type mismatch")
                    }
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(ValType.I32)
                    popVal(valType(ValType.ID.RefNull, typeIdx))
                }
                OpCode.REF_I31 -> {
                    popVal(ValType.I32)
                    pushVal(
                        ValType.builder()
                            .withOpcode(ValType.ID.Ref)
                            .withTypeIdx(ValType.TypeIdxCode.I31.code())
                            .build()
                    )
                }
                OpCode.I31_GET_S,
                OpCode.I31_GET_U -> {
                    popVal(ValType.I31Ref)
                    pushVal(ValType.I32)
                }
                OpCode.REF_TEST,
                OpCode.REF_TEST_NULL -> {
                    val heapType = op.operand(0).toInt()
                    val topHt = topOfHeapType(heapType)
                    popVal(valType(ValType.ID.RefNull, topHt))
                    op.setOperand(1, topHt.toLong())
                    pushVal(ValType.I32)
                }
                OpCode.CAST_TEST,
                OpCode.CAST_TEST_NULL -> {
                    val heapType = op.operand(0).toInt()
                    val nullable = op.opcode() == OpCode.CAST_TEST_NULL
                    val topHt = topOfHeapType(heapType)
                    popVal(valType(ValType.ID.RefNull, topHt))
                    op.setOperand(1, topHt.toLong())
                    pushVal(valType(if (nullable) ValType.ID.RefNull else ValType.ID.Ref, heapType))
                }
                OpCode.BR_ON_CAST,
                OpCode.BR_ON_CAST_FAIL -> {
                    val flags = op.operand(0).toInt()
                    val n = op.operand(1).toInt()
                    val ht1 = op.operand(2).toInt()
                    val ht2 = op.operand(3).toInt()
                    val null1 = (flags and 1) != 0
                    val null2 = (flags and 2) != 0
                    val rt1 = valType(if (null1) ValType.ID.RefNull else ValType.ID.Ref, ht1)
                    val rt2 = valType(if (null2) ValType.ID.RefNull else ValType.ID.Ref, ht2)
                    // rt2 <: rt1 is required by the spec
                    if (!typeMatches(rt2, rt1)) {
                        throw InvalidException("type mismatch")
                    }
                    // diff_reftype: if rt2 is nullable, fallthrough is non-null rt1
                    val diffType =
                        valType(
                            if (null2) ValType.ID.Ref
                            else if (null1) ValType.ID.RefNull else ValType.ID.Ref,
                            ht1,
                        )
                    val labelTypes = labelTypes(getCtrl(n))
                    if (labelTypes.isEmpty()) {
                        throw InvalidException("type mismatch")
                    }
                    // The label's last type must match the branch type
                    val brType = if (op.opcode() == OpCode.BR_ON_CAST) rt2 else diffType
                    val lastLabel = labelTypes.get(labelTypes.size - 1)
                    if (!typeMatches(brType, lastLabel)) {
                        throw InvalidException("type mismatch")
                    }
                    val ts0 = labelTypes.subList(0, labelTypes.size - 1)
                    popVal(rt1)
                    popVals(ts0)
                    pushVals(ts0)
                    op.setOperand(4, topOfHeapType(ht1).toLong())
                    if (op.opcode() == OpCode.BR_ON_CAST) {
                        pushVal(diffType)
                    } else {
                        pushVal(rt2)
                    }
                }
                OpCode.ANY_CONVERT_EXTERN -> {
                    val rt = popRef()
                    val nullable =
                        rt.equals(ValType.BOT) || rt.equals(ValType.RefBot) || rt.isNullable()
                    pushVal(
                        valType(
                            if (nullable) ValType.ID.RefNull else ValType.ID.Ref,
                            ValType.TypeIdxCode.ANY.code(),
                        )
                    )
                }
                OpCode.EXTERN_CONVERT_ANY -> {
                    val rt = popRef()
                    val nullable =
                        rt.equals(ValType.BOT) || rt.equals(ValType.RefBot) || rt.isNullable()
                    pushVal(
                        valType(
                            if (nullable) ValType.ID.RefNull else ValType.ID.Ref,
                            ValType.TypeIdxCode.EXTERN.code(),
                        )
                    )
                }
                else -> {
                    throw IllegalArgumentException(
                        "Missing type validation opcode handling for " + op.opcode()
                    )
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw InvalidException(errors.joinToString(" - ") { it.message.orEmpty() })
        }

        // to satisfy the check mentioned in the NOTE
        // https://webassembly.github.io/spec/core/binary/modules.html#data-count-section
        if (module.codeSection().isRequiresDataCount() && module.dataCountSection() == null) {
            throw MalformedException("data count section required")
        }
    }

    private fun validateTailCall(funcReturnType: List<ValType>) {
        val expected = labelTypes(ctrlFrameStack.get(0))

        if (funcReturnType.size != expected.size) {
            throw InvalidException("type mismatch: return arity")
        }

        for (i in 0 until (funcReturnType.size)) {
            if (!typeMatches(funcReturnType.get(i), expected.get(i))) {
                throw InvalidException(
                    "type mismatch: tail call doesn't match frame type at index " + i
                )
            }
        }
    }

    private fun VALIDATE_CALL(funcId: Int, isReturn: Boolean) {
        val typeId = getFunctionType(funcId)
        val types = getType(typeId)
        for (j in types.params().size - 1 downTo 0) {
            popVal(types.params().get(j))
        }
        pushVals(types.returns())
        if (isReturn) {
            validateTailCall(types.returns())
        }
    }

    private fun VALIDATE_CALL_INDIRECT(typeId: Long, tableId: Int, isReturn: Boolean) {
        popVal(ValType.I32)
        val tableType = getTableType(tableId)
        if (!typeMatches(tableType, ValType.FuncRef)) {
            throw InvalidException(
                "type mismatch expected a table of FuncRefs buf found " + tableType
            )
        }
        val types = getType(typeId.toInt())
        for (j in types.params().size - 1 downTo 0) {
            popVal(types.params().get(j))
        }
        pushVals(types.returns())
        if (isReturn) {
            validateTailCall(types.returns())
        }
    }

    private fun VALIDATE_CALL_REF(typeId: Int, isReturn: Boolean) {
        val rt = popRef()
        val funcType = getType(typeId)
        popVals(funcType.params())
        pushVals(funcType.returns())

        if (isReturn) {
            validateTailCall(funcType.returns())
        }

        if (rt.typeIdx() != ValType.TypeIdxCode.BOT.code()) {
            val idx = rt.typeIdx()
            if (idx < 0) {
                // error
                throw InvalidException(
                    "type mismatch: call_ref should be called on a defined" +
                        " reference type, got operand: " +
                        idx
                )
            }
        }
    }

    private fun VALIDATE_RETURN() {
        popVals(labelTypes(ctrlFrameStack.get(0)))
        unreachable()
    }
}
