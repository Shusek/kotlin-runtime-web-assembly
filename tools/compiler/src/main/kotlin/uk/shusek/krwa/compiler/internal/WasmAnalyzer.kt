package uk.shusek.krwa.compiler.internal

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import kotlin.math.max
import uk.shusek.krwa.compiler.internal.CompilerUtil.hasTooManyParameters
import uk.shusek.krwa.compiler.internal.CompilerUtil.localType
import uk.shusek.krwa.compiler.internal.CompilerUtil.slotCount
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.CatchOpCode
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FieldType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.GlobalImport
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.TableImport
import uk.shusek.krwa.wasm.types.TagImport
import uk.shusek.krwa.wasm.types.ValType

internal class WasmAnalyzer(private val module: WasmModule) {
    internal class AnalysisResult
    internal constructor(
        private val instructions: List<CompilerInstruction>,
        private val maxTempSlots: Int,
        private val trySaveSlotTypes: List<ValType>,
    ) {
        fun instructions(): List<CompilerInstruction> = instructions

        fun maxTempSlots(): Int = maxTempSlots

        fun trySaveSlotTypes(): List<ValType> = trySaveSlotTypes
    }

    private val globalTypes: List<ValType> = getGlobalTypes(module)
    private val functionTypes: List<FunctionType> = getFunctionTypes(module)
    private val tableTypes: List<ValType> = getTableTypes(module)
    private val tagTypes: List<FunctionType> = getTagTypes(module)
    private val functionImports: Int = module.importSection().count(ExternalType.FUNCTION)
    private val tailCallFunctions: BooleanArray = scanTailCallFunctions()
    private val hasTailCalls: Boolean = anyTrue(tailCallFunctions)

    fun globalTypes(): List<ValType> = globalTypes

    fun functionTypes(): List<FunctionType> = functionTypes

    fun tailCallFunctions(): BooleanArray = tailCallFunctions

    fun tailCallTypes(): BooleanArray {
        @Suppress("UNCHECKED_CAST") val types = module.typeSection().types() as Array<FunctionType?>
        val result = BooleanArray(types.size)
        for (funcId in tailCallFunctions.indices) {
            if (!tailCallFunctions[funcId]) {
                continue
            }
            val funcTypeId = functionTypeIndex(funcId)
            for (typeId in types.indices) {
                if (types[typeId] == null) {
                    continue
                }
                if (
                    funcTypeId == typeId ||
                        ValType.heapTypeSubtype(funcTypeId, typeId, module.typeSection())
                ) {
                    result[typeId] = true
                }
            }
        }
        return result
    }

    fun hasTailCalls(): Boolean = hasTailCalls

    private fun scanTailCallFunctions(): BooleanArray {
        val totalFunctions = functionImports + module.functionSection().functionCount()
        val result = BooleanArray(totalFunctions)
        val funcCount = module.functionSection().functionCount()
        for (i in 0 until funcCount) {
            val body = module.codeSection().getFunctionBody(i)
            var exitBlockDepth = -1
            for (ins in body.instructions()) {
                if (exitBlockDepth >= 0) {
                    if (
                        ins.depth() > exitBlockDepth ||
                            (ins.opcode() != OpCode.ELSE && ins.opcode() != OpCode.END)
                    ) {
                        continue
                    }
                    exitBlockDepth = -1
                }
                when (ins.opcode()) {
                    OpCode.RETURN_CALL,
                    OpCode.RETURN_CALL_INDIRECT,
                    OpCode.RETURN_CALL_REF -> {
                        result[functionImports + i] = true
                    }

                    OpCode.UNREACHABLE,
                    OpCode.BR,
                    OpCode.BR_TABLE,
                    OpCode.RETURN -> {
                        exitBlockDepth = ins.depth()
                    }

                    else -> Unit
                }
                if (result[functionImports + i]) {
                    break
                }
            }
        }
        return result
    }

    private class TryCatchBlock(
        val ins: AnnotatedInstruction,
        val start: Long,
        val end: Long,
        val handler: Long,
        val after: Long,
        val afterCatch: LongArray,
    ) {
        var savedStackSlotBase: Int = 0
        var savedStackTypes: List<ValType>? = null
        var registered: Boolean = false
    }

    fun analyze(funcId: Int): AnalysisResult {
        val functionType = functionTypes[funcId]
        val body = module.codeSection().getFunctionBody(funcId - functionImports)
        val stack = TypeStack(module.typeSection())
        var nextLabel = body.instructions().size
        var trySaveSlotOffset = 0
        val trySaveSlotTypes = ArrayList<ValType>()
        val result = ArrayList<CompilerInstruction>()
        val tryCatchBlockInstructions = ArrayList<CompilerInstruction>()
        val labels = HashSet<Int>()
        val tryCatchBlocks = HashMap<Int, TryCatchBlock>()

        for (idx in body.instructions().size - 1 downTo 0) {
            val ins = body.instructions()[idx]

            if (ins.labelTrue() != AnnotatedInstruction.UNDEFINED_LABEL) {
                labels.add(ins.labelTrue())
            }
            if (ins.labelFalse() != AnnotatedInstruction.UNDEFINED_LABEL) {
                labels.add(ins.labelFalse())
            }
            labels.addAll(ins.labelTable())
            labels.addAll(ins.catches()?.map { it.resolvedLabel() } ?: emptyList())

            if (
                ins.opcode() == OpCode.TRY_TABLE &&
                    body.instructions()[idx + 1].opcode() != OpCode.END
            ) {
                val start = (nextLabel++).toLong()
                val end = (nextLabel++).toLong()
                val handle = (nextLabel++).toLong()
                val after = (nextLabel++).toLong()

                val afterCatchLabels = LongArray(ins.catches()!!.size)
                for (i in ins.catches()!!.indices) {
                    afterCatchLabels[i] = (nextLabel++).toLong()
                }

                val block = TryCatchBlock(ins, start, end, handle, after, afterCatchLabels)
                tryCatchBlocks[ins.address()] = block
            }
        }

        stack.enterScope(
            TypeStack.FUNCTION_SCOPE,
            FunctionType.of(emptyList(), functionType.returns()),
        )

        var exitBlockDepth = -1
        var idx = 0
        while (idx < body.instructions().size) {
            val ins = body.instructions()[idx]

            if (labels.contains(idx)) {
                result.add(CompilerInstruction(CompilerOpCode.LABEL, idx.toLong()))
            }

            if (exitBlockDepth >= 0) {
                if (
                    ins.depth() > exitBlockDepth ||
                        (ins.opcode() != OpCode.ELSE && ins.opcode() != OpCode.END)
                ) {
                    idx++
                    continue
                }

                exitBlockDepth = -1
                if (ins.opcode() == OpCode.END) {
                    if (ins.scope()!!.opcode() == OpCode.TRY_TABLE) {
                        val tryCatchBlock = tryCatchBlocks.remove(ins.scope()!!.address())
                        if (tryCatchBlock != null && tryCatchBlock.registered) {
                            analyzeTryCatchEnd(result, tryCatchBlock)
                        }
                    }
                    stack.scopeRestore()
                }
            }

            when (ins.opcode()) {
                OpCode.NOP -> Unit
                OpCode.UNREACHABLE -> {
                    exitBlockDepth = ins.depth()
                    result.add(CompilerInstruction(CompilerOpCode.TRAP))
                }

                OpCode.BLOCK,
                OpCode.LOOP -> {
                    stack.enterScope(ins.scope()!!, blockType(ins))
                }

                OpCode.RETURN -> {
                    exitBlockDepth = ins.depth()
                    for (type in reversed(functionType.returns())) {
                        stack.pop(type)
                    }
                    result.add(
                        CompilerInstruction(CompilerOpCode.RETURN, *ids(functionType.returns()))
                    )
                }

                OpCode.RETURN_CALL -> {
                    val calleeId = ins.operand(0).toInt()
                    val calleeType = functionTypes[calleeId]
                    for (type in reversed(calleeType.params())) {
                        stack.pop(type)
                    }
                    result.add(CompilerInstruction(CompilerOpCode.RETURN_CALL, *ins.operands()))
                    exitBlockDepth = ins.depth()
                }

                OpCode.RETURN_CALL_INDIRECT -> {
                    stack.pop(ValType.I32)
                    val calleeType = module.typeSection().getType(ins.operand(0).toInt())
                    for (type in reversed(calleeType.params())) {
                        stack.pop(type)
                    }
                    result.add(
                        CompilerInstruction(CompilerOpCode.RETURN_CALL_INDIRECT, *ins.operands())
                    )
                    exitBlockDepth = ins.depth()
                }

                OpCode.IF -> {
                    stack.pop(ValType.I32)
                    stack.enterScope(ins.scope()!!, blockType(ins))
                    if (body.instructions()[ins.labelFalse() - 1].opcode() == OpCode.ELSE) {
                        stack.pushTypes()
                    }
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, ins.labelFalse().toLong()))
                }

                OpCode.ELSE -> {
                    stack.popTypes()
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                }

                OpCode.BR -> {
                    exitBlockDepth = ins.depth()
                    unwindStack(functionType, body, ins, ins.labelTrue(), stack)?.let(result::add)
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                }

                OpCode.BR_IF -> {
                    stack.pop(ValType.I32)
                    val ifUnwind = unwindStack(functionType, body, ins, ins.labelTrue(), stack)
                    if (ifUnwind != null) {
                        result.add(
                            CompilerInstruction(CompilerOpCode.IFEQ, ins.labelFalse().toLong())
                        )
                        result.add(ifUnwind)
                        result.add(
                            CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong())
                        )
                    } else {
                        result.add(
                            CompilerInstruction(CompilerOpCode.IFNE, ins.labelTrue().toLong())
                        )
                    }
                }

                OpCode.BR_TABLE -> {
                    exitBlockDepth = ins.depth()
                    stack.pop(ValType.I32)
                    if (ins.labelTable().size == 1) {
                        result.add(CompilerInstruction(CompilerOpCode.DROP, ValType.I32.id()))
                        unwindStack(functionType, body, ins, ins.labelTable()[0], stack)
                            ?.let(result::add)
                        result.add(
                            CompilerInstruction(CompilerOpCode.GOTO, ins.labelTable()[0].toLong())
                        )
                        idx++
                        continue
                    }

                    val unwinds = ArrayList<CompilerInstruction>()
                    val targets = HashMap<Int, Int>()
                    for (target in ins.labelTable()) {
                        if (!targets.containsKey(target)) {
                            var label = target
                            val unwind = unwindStack(functionType, body, ins, target, stack)
                            if (unwind != null) {
                                label = nextLabel
                                nextLabel++
                                unwinds.add(
                                    CompilerInstruction(CompilerOpCode.LABEL, label.toLong())
                                )
                                unwinds.add(unwind)
                                unwinds.add(
                                    CompilerInstruction(CompilerOpCode.GOTO, target.toLong())
                                )
                            }
                            targets[target] = label
                        }
                    }

                    val operands = ins.labelTable().map { targets[it]!!.toLong() }.toLongArray()
                    result.add(CompilerInstruction(CompilerOpCode.SWITCH, *operands))
                    result.addAll(unwinds)
                }

                OpCode.TRY_TABLE -> {
                    if (body.instructions()[idx + 1].opcode() == OpCode.END) {
                        idx += 2
                        continue
                    }

                    val tryCatchBlock = tryCatchBlocks[ins.address()]!!
                    val allTypes = ArrayList(stack.types())
                    allTypes.reverse()
                    val paramCount = blockType(ins).params().size
                    val belowCount = allTypes.size - paramCount

                    if (belowCount > 0) {
                        val saveSlotBase = trySaveSlotOffset
                        val belowTypes = allTypes.subList(0, belowCount)
                        for (t in belowTypes) {
                            trySaveSlotOffset += slotCount(t)
                        }
                        trySaveSlotTypes.addAll(belowTypes)
                        tryCatchBlock.savedStackSlotBase = saveSlotBase
                        tryCatchBlock.savedStackTypes = ArrayList(belowTypes)

                        val saveOperands = LongArray(2 + allTypes.size)
                        saveOperands[0] = saveSlotBase.toLong()
                        saveOperands[1] = belowCount.toLong()
                        for (i in allTypes.indices) {
                            saveOperands[i + 2] = allTypes[i].id()
                        }
                        result.add(
                            CompilerInstruction(CompilerOpCode.TRY_SAVE_STACK, *saveOperands)
                        )
                    }

                    stack.enterScope(ins.scope()!!, blockType(ins))
                    tryCatchBlock.registered = true
                    tryCatchBlockInstructions.add(
                        CompilerInstruction(
                            CompilerOpCode.TRY_CATCH_BLOCK,
                            tryCatchBlock.start,
                            tryCatchBlock.end,
                            tryCatchBlock.handler,
                        )
                    )
                    result.add(CompilerInstruction(CompilerOpCode.LABEL, tryCatchBlock.start))
                }

                OpCode.END -> {
                    if (ins.scope()!!.opcode() == OpCode.TRY_TABLE) {
                        val tryCatchBlock = tryCatchBlocks.remove(ins.scope()!!.address())
                        if (tryCatchBlock != null) {
                            analyzeTryCatchEnd(result, tryCatchBlock)
                        }
                    }
                    stack.exitScope(ins.scope()!!)
                }

                OpCode.THROW -> {
                    result.add(
                        CompilerInstruction(CompilerOpCode.of(OpCode.THROW), *ins.operands())
                    )
                    exitBlockDepth = ins.depth()
                }

                OpCode.THROW_REF -> {
                    result.add(
                        CompilerInstruction(CompilerOpCode.of(OpCode.THROW_REF), *ins.operands())
                    )
                    exitBlockDepth = ins.depth()
                }

                OpCode.CALL_REF -> {
                    stack.popRef()
                    val typeIdx = ins.operand(0).toInt()
                    val callRefType = module.typeSection().getType(typeIdx)
                    updateStack(stack, callRefType)
                    result.add(CompilerInstruction(CompilerOpCode.CALL_REF, *ins.operands()))
                }

                OpCode.RETURN_CALL_REF -> {
                    stack.popRef()
                    val typeIdx = ins.operand(0).toInt()
                    val callRefType = module.typeSection().getType(typeIdx)
                    for (type in reversed(callRefType.params())) {
                        stack.pop(type)
                    }
                    result.add(CompilerInstruction(CompilerOpCode.RETURN_CALL_REF, *ins.operands()))
                    exitBlockDepth = ins.depth()
                }

                OpCode.BR_ON_NULL -> {
                    val ref = stack.peek()
                    stack.pop(ref)
                    result.add(CompilerInstruction(CompilerOpCode.BR_ON_NULL_CHECK))
                    val notNullLabel = nextLabel++
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, notNullLabel.toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.DROP, ref.id()))
                    unwindStack(functionType, body, ins, ins.labelTrue(), stack)?.let(result::add)
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.LABEL, notNullLabel.toLong()))
                    stack.push(valType(ValType.ID.Ref, ref.typeIdx()))
                }

                OpCode.BR_ON_NON_NULL -> {
                    val ref = stack.peek()
                    stack.pop(ref)
                    result.add(CompilerInstruction(CompilerOpCode.BR_ON_NON_NULL_CHECK))
                    val nullLabel = nextLabel++
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, nullLabel.toLong()))
                    stack.push(ref)
                    val brUnwind = unwindStack(functionType, body, ins, ins.labelTrue(), stack)
                    stack.pop(ref)
                    brUnwind?.let(result::add)
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.LABEL, nullLabel.toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.DROP, ref.id()))
                }

                OpCode.BR_ON_CAST -> {
                    val ref = stack.peek()
                    stack.pop(ref)
                    val flags = ins.operand(0).toInt()
                    val ht1 = ins.operand(2).toInt()
                    val ht2 = ins.operand(3).toInt()
                    val null1 = flags and 1 != 0
                    val null2 = flags and 2 != 0
                    val rt2 = valType(if (null2) ValType.ID.RefNull else ValType.ID.Ref, ht2)
                    val diffType =
                        valType(
                            if (null2) {
                                ValType.ID.Ref
                            } else if (null1) {
                                ValType.ID.RefNull
                            } else {
                                ValType.ID.Ref
                            },
                            ht1,
                        )
                    val sourceHeapType = if (ins.operandCount() > 4) ins.operand(4).toInt() else ht1
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.BR_ON_CAST_CHECK,
                            if (null2) 1L else 0L,
                            ht2.toLong(),
                            sourceHeapType.toLong(),
                        )
                    )
                    val noMatchLabel = nextLabel++
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, noMatchLabel.toLong()))
                    stack.push(rt2)
                    val brUnwind = unwindStack(functionType, body, ins, ins.labelTrue(), stack)
                    stack.pop(rt2)
                    brUnwind?.let(result::add)
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.LABEL, noMatchLabel.toLong()))
                    stack.push(diffType)
                }

                OpCode.BR_ON_CAST_FAIL -> {
                    val ref = stack.peek()
                    stack.pop(ref)
                    val flags = ins.operand(0).toInt()
                    val ht1 = ins.operand(2).toInt()
                    val ht2 = ins.operand(3).toInt()
                    val null1 = flags and 1 != 0
                    val null2 = flags and 2 != 0
                    val rt2 = valType(if (null2) ValType.ID.RefNull else ValType.ID.Ref, ht2)
                    val diffType =
                        valType(
                            if (null2) {
                                ValType.ID.Ref
                            } else if (null1) {
                                ValType.ID.RefNull
                            } else {
                                ValType.ID.Ref
                            },
                            ht1,
                        )
                    val sourceHeapType = if (ins.operandCount() > 4) ins.operand(4).toInt() else ht1
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.BR_ON_CAST_FAIL_CHECK,
                            if (null2) 1L else 0L,
                            ht2.toLong(),
                            sourceHeapType.toLong(),
                        )
                    )
                    val matchLabel = nextLabel++
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, matchLabel.toLong()))
                    stack.push(diffType)
                    val brUnwind = unwindStack(functionType, body, ins, ins.labelTrue(), stack)
                    stack.pop(diffType)
                    brUnwind?.let(result::add)
                    result.add(CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue().toLong()))
                    result.add(CompilerInstruction(CompilerOpCode.LABEL, matchLabel.toLong()))
                    stack.push(rt2)
                }

                OpCode.SELECT,
                OpCode.SELECT_T -> {
                    stack.pop(ValType.I32)
                    val selectType = stack.peek()
                    stack.pop(selectType)
                    if (selectType.isReference()) {
                        stack.popRef()
                    } else {
                        stack.pop(selectType)
                    }
                    stack.push(selectType)
                    result.add(CompilerInstruction(CompilerOpCode.SELECT, selectType.id()))
                }

                OpCode.DROP -> {
                    val dropType = stack.peek()
                    stack.pop(dropType)
                    result.add(CompilerInstruction(CompilerOpCode.DROP, dropType.id()))
                }

                OpCode.LOCAL_TEE -> {
                    val teeType = stack.peek()
                    stack.pop(teeType)
                    stack.push(teeType)
                    val teeOperands = longArrayOf(ins.operand(0), teeType.id())
                    result.add(CompilerInstruction(CompilerOpCode.LOCAL_TEE, *teeOperands))
                }

                OpCode.CAST_TEST -> {
                    val srcType = stack.peek()
                    stack.popRef()
                    val heapType = ins.operand(0).toInt()
                    stack.push(valType(ValType.ID.Ref, heapType))
                    val castOperands = longArrayOf(heapType.toLong(), srcType.typeIdx().toLong())
                    result.add(CompilerInstruction(CompilerOpCode.CAST_TEST, *castOperands))
                }

                OpCode.CAST_TEST_NULL -> {
                    val srcType = stack.peek()
                    stack.popRef()
                    val heapType = ins.operand(0).toInt()
                    stack.push(valType(ValType.ID.RefNull, heapType))
                    val castNullOperands =
                        longArrayOf(heapType.toLong(), srcType.typeIdx().toLong())
                    result.add(
                        CompilerInstruction(CompilerOpCode.CAST_TEST_NULL, *castNullOperands)
                    )
                }

                else -> analyzeSimple(result, stack, ins, functionType, body)
            }
            idx++
        }

        for (type in reversed(functionType.returns())) {
            stack.pop(type)
        }
        result.add(CompilerInstruction(CompilerOpCode.RETURN, *ids(functionType.returns())))

        stack.verifyEmpty()

        tryCatchBlockInstructions.reverse()
        result.addAll(0, tryCatchBlockInstructions)

        return AnalysisResult(result, computeMaxTempSlots(result), ArrayList(trySaveSlotTypes))
    }

    private fun computeMaxTempSlots(instructions: List<CompilerInstruction>): Int {
        var maxSlots = 0
        for (ins in instructions) {
            when (ins.opcode()) {
                CompilerOpCode.DROP_KEEP -> {
                    val keepStart = ins.operand(0).toInt() + 1
                    var slots = 0
                    for (i in keepStart until ins.operandCount()) {
                        slots += slotCount(ins.operand(i))
                    }
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.TRY_SAVE_STACK -> {
                    val belowCount = ins.operand(1).toInt()
                    val totalCount = ins.operandCount() - 2
                    var slots = 0
                    for (i in belowCount until totalCount) {
                        slots += slotCount(ins.operand(i + 2))
                    }
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.CATCH_START -> {
                    maxSlots = max(maxSlots, 2)
                }

                CompilerOpCode.THROW -> {
                    val type = tagTypes[ins.operand(0).toInt()]
                    maxSlots = max(maxSlots, type.params().sumOf { slotCount(it) })
                }

                CompilerOpCode.CALL -> {
                    val type = functionTypes[ins.operand(0).toInt()]
                    if (hasTooManyParameters(type)) {
                        maxSlots = max(maxSlots, type.params().sumOf { slotCount(it) })
                    }
                }

                CompilerOpCode.CALL_INDIRECT -> {
                    val type = module.typeSection().getType(ins.operand(0).toInt())
                    if (hasTooManyParameters(type)) {
                        maxSlots = max(maxSlots, type.params().sumOf { slotCount(it) })
                    }
                }

                CompilerOpCode.CALL_REF -> {
                    val type = module.typeSection().getType(ins.operand(0).toInt())
                    var slots = 1
                    if (hasTooManyParameters(type)) {
                        slots = max(slots, type.params().sumOf { slotCount(it) })
                    }
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.STRUCT_NEW -> {
                    val typeIdx = ins.operand(0).toInt()
                    val structType =
                        module.typeSection().getSubType(typeIdx).compType().structType()!!
                    val slots = structType.fieldTypes().sumOf { slotCount(unpackFieldType(it)) }
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.ARRAY_NEW_FIXED -> {
                    val typeIdx = ins.operand(0).toInt()
                    val len = ins.operand(1).toInt()
                    val arrayType = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                    val elemType = unpackFieldType(arrayType.fieldType())
                    maxSlots = max(maxSlots, len * slotCount(elemType))
                }

                CompilerOpCode.RETURN_CALL_INDIRECT -> {
                    val type = module.typeSection().getType(ins.operand(0).toInt())
                    val slots = type.params().sumOf { slotCount(it) } + 1
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.RETURN_CALL_REF -> {
                    val type = module.typeSection().getType(ins.operand(0).toInt())
                    val slots = type.params().sumOf { slotCount(it) } + 1
                    maxSlots = max(maxSlots, slots)
                }

                CompilerOpCode.RETURN_CALL -> {
                    val calleeType = functionTypes[ins.operand(0).toInt()]
                    val slots = calleeType.params().sumOf { slotCount(it) }
                    maxSlots = max(maxSlots, slots)
                }

                else -> Unit
            }
        }
        return maxSlots
    }

    private fun analyzeTryCatchEnd(
        result: MutableList<CompilerInstruction>,
        tryCatchBlock: TryCatchBlock,
    ) {
        result.add(CompilerInstruction(CompilerOpCode.LABEL, tryCatchBlock.end))
        result.add(CompilerInstruction(CompilerOpCode.GOTO, tryCatchBlock.after))
        result.add(CompilerInstruction(CompilerOpCode.LABEL, tryCatchBlock.handler))
        result.add(CompilerInstruction(CompilerOpCode.CATCH_START))

        val savedStackTypes = tryCatchBlock.savedStackTypes
        if (!savedStackTypes.isNullOrEmpty()) {
            val operands = LongArray(1 + savedStackTypes.size)
            operands[0] = tryCatchBlock.savedStackSlotBase.toLong()
            for (i in savedStackTypes.indices) {
                operands[i + 1] = savedStackTypes[i].id()
            }
            result.add(CompilerInstruction(CompilerOpCode.TRY_RESTORE_STACK, *operands))
        }

        val catches = tryCatchBlock.ins.catches()!!
        for (i in catches.indices) {
            val catchCondition = catches[i]
            val afterCatchLabel = tryCatchBlock.afterCatch[i]

            when (catchCondition.opcode()) {
                CatchOpCode.CATCH -> {
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.CATCH_COMPARE_TAG,
                            catchCondition.tag().toLong(),
                        )
                    )
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, afterCatchLabel))
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.CATCH_UNBOX_PARAMS,
                            catchCondition.tag().toLong(),
                        )
                    )
                }

                CatchOpCode.CATCH_REF -> {
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.CATCH_COMPARE_TAG,
                            catchCondition.tag().toLong(),
                        )
                    )
                    result.add(CompilerInstruction(CompilerOpCode.IFEQ, afterCatchLabel))
                    result.add(
                        CompilerInstruction(
                            CompilerOpCode.CATCH_UNBOX_PARAMS,
                            catchCondition.tag().toLong(),
                        )
                    )
                    result.add(CompilerInstruction(CompilerOpCode.CATCH_REGISTER_EXCEPTION))
                }

                CatchOpCode.CATCH_ALL -> Unit
                CatchOpCode.CATCH_ALL_REF -> {
                    result.add(CompilerInstruction(CompilerOpCode.CATCH_REGISTER_EXCEPTION))
                }
            }
            result.add(
                CompilerInstruction(CompilerOpCode.GOTO, catchCondition.resolvedLabel().toLong())
            )
            result.add(CompilerInstruction(CompilerOpCode.LABEL, afterCatchLabel))
        }

        result.add(CompilerInstruction(CompilerOpCode.CATCH_END))
        result.add(CompilerInstruction(CompilerOpCode.LABEL, tryCatchBlock.after))
    }

    private fun analyzeSimple(
        out: MutableList<CompilerInstruction>,
        stack: TypeStack,
        ins: Instruction,
        functionType: FunctionType,
        body: FunctionBody,
    ) {
        when (ins.opcode()) {
            OpCode.I32_CLZ,
            OpCode.I32_CTZ,
            OpCode.I32_EQZ,
            OpCode.I32_EXTEND_16_S,
            OpCode.I32_EXTEND_8_S,
            OpCode.I32_LOAD16_S,
            OpCode.I32_LOAD16_U,
            OpCode.I32_LOAD8_S,
            OpCode.I32_LOAD8_U,
            OpCode.I32_LOAD,
            OpCode.I32_POPCNT,
            OpCode.MEMORY_GROW,
            OpCode.I32_ATOMIC_LOAD,
            OpCode.I32_ATOMIC_LOAD8_U,
            OpCode.I32_ATOMIC_LOAD16_U -> {
                stack.pop(ValType.I32)
                stack.push(ValType.I32)
            }

            OpCode.F32_CONVERT_I32_S,
            OpCode.F32_CONVERT_I32_U,
            OpCode.F32_LOAD,
            OpCode.F32_REINTERPRET_I32 -> {
                stack.pop(ValType.I32)
                stack.push(ValType.F32)
            }

            OpCode.F32_ABS,
            OpCode.F32_CEIL,
            OpCode.F32_FLOOR,
            OpCode.F32_NEAREST,
            OpCode.F32_NEG,
            OpCode.F32_SQRT,
            OpCode.F32_TRUNC -> {
                stack.pop(ValType.F32)
                stack.push(ValType.F32)
            }

            OpCode.I32_REINTERPRET_F32,
            OpCode.I32_TRUNC_F32_S,
            OpCode.I32_TRUNC_F32_U,
            OpCode.I32_TRUNC_SAT_F32_S,
            OpCode.I32_TRUNC_SAT_F32_U -> {
                stack.pop(ValType.F32)
                stack.push(ValType.I32)
            }

            OpCode.I32_WRAP_I64,
            OpCode.I64_EQZ -> {
                stack.pop(ValType.I64)
                stack.push(ValType.I32)
            }

            OpCode.F32_CONVERT_I64_S,
            OpCode.F32_CONVERT_I64_U -> {
                stack.pop(ValType.I64)
                stack.push(ValType.F32)
            }

            OpCode.F32_DEMOTE_F64 -> {
                stack.pop(ValType.F64)
                stack.push(ValType.F32)
            }

            OpCode.I32_TRUNC_F64_S,
            OpCode.I32_TRUNC_F64_U,
            OpCode.I32_TRUNC_SAT_F64_S,
            OpCode.I32_TRUNC_SAT_F64_U -> {
                stack.pop(ValType.F64)
                stack.push(ValType.I32)
            }

            OpCode.I32_ADD,
            OpCode.I32_AND,
            OpCode.I32_DIV_S,
            OpCode.I32_DIV_U,
            OpCode.I32_EQ,
            OpCode.I32_GE_S,
            OpCode.I32_GE_U,
            OpCode.I32_GT_S,
            OpCode.I32_GT_U,
            OpCode.I32_LE_S,
            OpCode.I32_LE_U,
            OpCode.I32_LT_S,
            OpCode.I32_LT_U,
            OpCode.I32_MUL,
            OpCode.I32_NE,
            OpCode.I32_OR,
            OpCode.I32_REM_S,
            OpCode.I32_REM_U,
            OpCode.I32_ROTL,
            OpCode.I32_ROTR,
            OpCode.I32_SHL,
            OpCode.I32_SHR_S,
            OpCode.I32_SHR_U,
            OpCode.I32_SUB,
            OpCode.I32_XOR,
            OpCode.I32_ATOMIC_RMW_ADD,
            OpCode.I32_ATOMIC_RMW_SUB,
            OpCode.I32_ATOMIC_RMW_AND,
            OpCode.I32_ATOMIC_RMW_OR,
            OpCode.I32_ATOMIC_RMW_XOR,
            OpCode.I32_ATOMIC_RMW_XCHG,
            OpCode.MEM_ATOMIC_NOTIFY,
            OpCode.I32_ATOMIC_RMW8_ADD_U,
            OpCode.I32_ATOMIC_RMW8_SUB_U,
            OpCode.I32_ATOMIC_RMW8_AND_U,
            OpCode.I32_ATOMIC_RMW8_OR_U,
            OpCode.I32_ATOMIC_RMW8_XOR_U,
            OpCode.I32_ATOMIC_RMW8_XCHG_U,
            OpCode.I32_ATOMIC_RMW16_ADD_U,
            OpCode.I32_ATOMIC_RMW16_SUB_U,
            OpCode.I32_ATOMIC_RMW16_AND_U,
            OpCode.I32_ATOMIC_RMW16_OR_U,
            OpCode.I32_ATOMIC_RMW16_XOR_U,
            OpCode.I32_ATOMIC_RMW16_XCHG_U -> {
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.push(ValType.I32)
            }

            OpCode.I64_EQ,
            OpCode.I64_GE_S,
            OpCode.I64_GE_U,
            OpCode.I64_GT_S,
            OpCode.I64_GT_U,
            OpCode.I64_LE_S,
            OpCode.I64_LE_U,
            OpCode.I64_LT_S,
            OpCode.I64_LT_U,
            OpCode.I64_NE -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I64)
                stack.push(ValType.I32)
            }

            OpCode.F32_ADD,
            OpCode.F32_COPYSIGN,
            OpCode.F32_DIV,
            OpCode.F32_MAX,
            OpCode.F32_MIN,
            OpCode.F32_MUL,
            OpCode.F32_SUB -> {
                stack.pop(ValType.F32)
                stack.pop(ValType.F32)
                stack.push(ValType.F32)
            }

            OpCode.F32_EQ,
            OpCode.F32_GE,
            OpCode.F32_GT,
            OpCode.F32_LE,
            OpCode.F32_LT,
            OpCode.F32_NE -> {
                stack.pop(ValType.F32)
                stack.pop(ValType.F32)
                stack.push(ValType.I32)
            }

            OpCode.F64_EQ,
            OpCode.F64_GE,
            OpCode.F64_GT,
            OpCode.F64_LE,
            OpCode.F64_LT,
            OpCode.F64_NE -> {
                stack.pop(ValType.F64)
                stack.pop(ValType.F64)
                stack.push(ValType.I32)
            }

            OpCode.I64_CLZ,
            OpCode.I64_CTZ,
            OpCode.I64_EXTEND_16_S,
            OpCode.I64_EXTEND_32_S,
            OpCode.I64_EXTEND_8_S,
            OpCode.I64_POPCNT -> {
                stack.pop(ValType.I64)
                stack.push(ValType.I64)
            }

            OpCode.I64_REINTERPRET_F64,
            OpCode.I64_TRUNC_F64_S,
            OpCode.I64_TRUNC_F64_U,
            OpCode.I64_TRUNC_SAT_F64_S,
            OpCode.I64_TRUNC_SAT_F64_U -> {
                stack.pop(ValType.F64)
                stack.push(ValType.I64)
            }

            OpCode.F64_TRUNC,
            OpCode.F64_SQRT,
            OpCode.F64_NEAREST,
            OpCode.F64_ABS,
            OpCode.F64_CEIL,
            OpCode.F64_FLOOR,
            OpCode.F64_NEG -> {
                stack.pop(ValType.F64)
                stack.push(ValType.F64)
            }

            OpCode.F64_CONVERT_I64_S,
            OpCode.F64_CONVERT_I64_U,
            OpCode.F64_REINTERPRET_I64 -> {
                stack.pop(ValType.I64)
                stack.push(ValType.F64)
            }

            OpCode.I64_EXTEND_I32_S,
            OpCode.I64_EXTEND_I32_U,
            OpCode.I64_LOAD16_S,
            OpCode.I64_LOAD16_U,
            OpCode.I64_LOAD32_S,
            OpCode.I64_LOAD32_U,
            OpCode.I64_LOAD8_S,
            OpCode.I64_LOAD8_U,
            OpCode.I64_LOAD,
            OpCode.I64_ATOMIC_LOAD,
            OpCode.I64_ATOMIC_LOAD8_U,
            OpCode.I64_ATOMIC_LOAD16_U,
            OpCode.I64_ATOMIC_LOAD32_U -> {
                stack.pop(ValType.I32)
                stack.push(ValType.I64)
            }

            OpCode.I64_TRUNC_F32_S,
            OpCode.I64_TRUNC_F32_U,
            OpCode.I64_TRUNC_SAT_F32_S,
            OpCode.I64_TRUNC_SAT_F32_U -> {
                stack.pop(ValType.F32)
                stack.push(ValType.I64)
            }

            OpCode.F64_CONVERT_I32_S,
            OpCode.F64_CONVERT_I32_U,
            OpCode.F64_LOAD -> {
                stack.pop(ValType.I32)
                stack.push(ValType.F64)
            }

            OpCode.F64_PROMOTE_F32 -> {
                stack.pop(ValType.F32)
                stack.push(ValType.F64)
            }

            OpCode.I64_ADD,
            OpCode.I64_AND,
            OpCode.I64_DIV_S,
            OpCode.I64_DIV_U,
            OpCode.I64_MUL,
            OpCode.I64_OR,
            OpCode.I64_REM_S,
            OpCode.I64_REM_U,
            OpCode.I64_ROTL,
            OpCode.I64_ROTR,
            OpCode.I64_SHL,
            OpCode.I64_SHR_S,
            OpCode.I64_SHR_U,
            OpCode.I64_SUB,
            OpCode.I64_XOR -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I64)
                stack.push(ValType.I64)
            }

            OpCode.F64_ADD,
            OpCode.F64_COPYSIGN,
            OpCode.F64_DIV,
            OpCode.F64_MAX,
            OpCode.F64_MIN,
            OpCode.F64_MUL,
            OpCode.F64_SUB -> {
                stack.pop(ValType.F64)
                stack.pop(ValType.F64)
                stack.push(ValType.F64)
            }

            OpCode.I32_STORE,
            OpCode.I32_STORE8,
            OpCode.I32_STORE16,
            OpCode.I32_ATOMIC_STORE,
            OpCode.I32_ATOMIC_STORE8,
            OpCode.I32_ATOMIC_STORE16 -> {
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
            }

            OpCode.F32_STORE -> {
                stack.pop(ValType.F32)
                stack.pop(ValType.I32)
            }

            OpCode.I64_STORE,
            OpCode.I64_STORE8,
            OpCode.I64_STORE16,
            OpCode.I64_STORE32,
            OpCode.I64_ATOMIC_STORE,
            OpCode.I64_ATOMIC_STORE8,
            OpCode.I64_ATOMIC_STORE16,
            OpCode.I64_ATOMIC_STORE32 -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I32)
            }

            OpCode.F64_STORE -> {
                stack.pop(ValType.F64)
                stack.pop(ValType.I32)
            }

            OpCode.I32_CONST,
            OpCode.MEMORY_SIZE,
            OpCode.TABLE_SIZE -> {
                stack.push(ValType.I32)
            }

            OpCode.F32_CONST -> {
                stack.push(ValType.F32)
            }

            OpCode.I64_CONST -> {
                stack.push(ValType.I64)
            }

            OpCode.F64_CONST -> {
                stack.push(ValType.F64)
            }

            OpCode.REF_FUNC -> {
                val funcIdx = ins.operand(0).toInt()
                val typeIdx = functionTypeIndex(funcIdx)
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.REF_NULL -> {
                stack.push(
                    ValType.builder()
                        .withOpcode(ValType.ID.RefNull)
                        .withTypeIdx(ins.operand(0).toInt())
                        .build()
                        .resolve(module.typeSection())
                )
            }

            OpCode.REF_IS_NULL -> {
                stack.popRef()
                stack.push(ValType.I32)
            }

            OpCode.MEMORY_COPY,
            OpCode.MEMORY_FILL,
            OpCode.MEMORY_INIT,
            OpCode.TABLE_COPY,
            OpCode.TABLE_INIT -> {
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
            }

            OpCode.TABLE_FILL -> {
                stack.pop(ValType.I32)
                stack.pop(stack.peek())
                stack.pop(ValType.I32)
            }

            OpCode.TABLE_GET -> {
                stack.pop(ValType.I32)
                stack.push(tableTypes[ins.operand(0).toInt()])
            }

            OpCode.TABLE_GROW -> {
                stack.pop(ValType.I32)
                stack.pop(tableTypes[ins.operand(0).toInt()])
                stack.push(ValType.I32)
            }

            OpCode.TABLE_SET -> {
                stack.pop(tableTypes[ins.operand(0).toInt()])
                stack.pop(ValType.I32)
            }

            OpCode.CALL -> {
                updateStack(stack, functionTypes[ins.operand(0).toInt()])
            }

            OpCode.CALL_INDIRECT -> {
                stack.pop(ValType.I32)
                updateStack(stack, module.typeSection().getType(ins.operand(0).toInt()))
            }

            OpCode.GLOBAL_SET,
            OpCode.LOCAL_SET -> {
                stack.pop(stack.peek())
            }

            OpCode.LOCAL_GET -> {
                stack.push(localType(functionType, body, ins.operand(0).toInt()))
            }

            OpCode.GLOBAL_GET -> {
                stack.push(globalTypes[ins.operand(0).toInt()])
            }

            OpCode.ATOMIC_FENCE,
            OpCode.DATA_DROP,
            OpCode.ELEM_DROP -> Unit

            OpCode.I32_ATOMIC_RMW_CMPXCHG,
            OpCode.I32_ATOMIC_RMW8_CMPXCHG_U,
            OpCode.I32_ATOMIC_RMW16_CMPXCHG_U -> {
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.push(ValType.I32)
            }

            OpCode.I64_ATOMIC_RMW_ADD,
            OpCode.I64_ATOMIC_RMW_SUB,
            OpCode.I64_ATOMIC_RMW_AND,
            OpCode.I64_ATOMIC_RMW_OR,
            OpCode.I64_ATOMIC_RMW_XOR,
            OpCode.I64_ATOMIC_RMW_XCHG,
            OpCode.I64_ATOMIC_RMW8_ADD_U,
            OpCode.I64_ATOMIC_RMW8_SUB_U,
            OpCode.I64_ATOMIC_RMW8_AND_U,
            OpCode.I64_ATOMIC_RMW8_OR_U,
            OpCode.I64_ATOMIC_RMW8_XOR_U,
            OpCode.I64_ATOMIC_RMW8_XCHG_U,
            OpCode.I64_ATOMIC_RMW16_ADD_U,
            OpCode.I64_ATOMIC_RMW16_SUB_U,
            OpCode.I64_ATOMIC_RMW16_AND_U,
            OpCode.I64_ATOMIC_RMW16_OR_U,
            OpCode.I64_ATOMIC_RMW16_XOR_U,
            OpCode.I64_ATOMIC_RMW16_XCHG_U,
            OpCode.I64_ATOMIC_RMW32_ADD_U,
            OpCode.I64_ATOMIC_RMW32_SUB_U,
            OpCode.I64_ATOMIC_RMW32_AND_U,
            OpCode.I64_ATOMIC_RMW32_OR_U,
            OpCode.I64_ATOMIC_RMW32_XOR_U,
            OpCode.I64_ATOMIC_RMW32_XCHG_U -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I32)
                stack.push(ValType.I64)
            }

            OpCode.I64_ATOMIC_RMW_CMPXCHG,
            OpCode.I64_ATOMIC_RMW8_CMPXCHG_U,
            OpCode.I64_ATOMIC_RMW16_CMPXCHG_U,
            OpCode.I64_ATOMIC_RMW32_CMPXCHG_U -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I64)
                stack.pop(ValType.I32)
                stack.push(ValType.I64)
            }

            OpCode.MEM_ATOMIC_WAIT32 -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.push(ValType.I32)
            }

            OpCode.MEM_ATOMIC_WAIT64 -> {
                stack.pop(ValType.I64)
                stack.pop(ValType.I64)
                stack.pop(ValType.I32)
                stack.push(ValType.I32)
            }

            OpCode.REF_EQ -> {
                stack.pop(ValType.EqRef)
                stack.pop(ValType.EqRef)
                stack.push(ValType.I32)
            }

            OpCode.REF_AS_NON_NULL -> {
                val rt = stack.peek()
                stack.popRef()
                stack.push(valType(ValType.ID.Ref, rt.typeIdx()))
            }

            OpCode.STRUCT_NEW -> {
                val typeIdx = ins.operand(0).toInt()
                val st = module.typeSection().getSubType(typeIdx).compType().structType()!!
                for (i in st.fieldTypes().size - 1 downTo 0) {
                    stack.pop(unpackFieldType(st.fieldTypes()[i]))
                }
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.STRUCT_NEW_DEFAULT -> {
                val typeIdx = ins.operand(0).toInt()
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.STRUCT_GET -> {
                val typeIdx = ins.operand(0).toInt()
                val fieldIdx = ins.operand(1).toInt()
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
                val st = module.typeSection().getSubType(typeIdx).compType().structType()!!
                stack.push(unpackFieldType(st.fieldTypes()[fieldIdx]))
            }

            OpCode.STRUCT_GET_S,
            OpCode.STRUCT_GET_U -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
                stack.push(ValType.I32)
            }

            OpCode.STRUCT_SET -> {
                val typeIdx = ins.operand(0).toInt()
                val fieldIdx = ins.operand(1).toInt()
                val st = module.typeSection().getSubType(typeIdx).compType().structType()!!
                stack.pop(unpackFieldType(st.fieldTypes()[fieldIdx]))
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
            }

            OpCode.ARRAY_NEW -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                val at = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                stack.pop(unpackFieldType(at.fieldType()))
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.ARRAY_NEW_DEFAULT -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.ARRAY_NEW_FIXED -> {
                val typeIdx = ins.operand(0).toInt()
                val len = ins.operand(1).toInt()
                val at = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                val elemType = unpackFieldType(at.fieldType())
                for (i in 0 until len) {
                    stack.pop(elemType)
                }
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.ARRAY_NEW_DATA,
            OpCode.ARRAY_NEW_ELEM -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.push(valType(ValType.ID.Ref, typeIdx))
            }

            OpCode.ARRAY_GET -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
                val at = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                stack.push(unpackFieldType(at.fieldType()))
            }

            OpCode.ARRAY_GET_S,
            OpCode.ARRAY_GET_U -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
                stack.push(ValType.I32)
            }

            OpCode.ARRAY_SET -> {
                val typeIdx = ins.operand(0).toInt()
                val at = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                stack.pop(unpackFieldType(at.fieldType()))
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
            }

            OpCode.ARRAY_LEN -> {
                stack.pop(ValType.ArrayRef)
                stack.push(ValType.I32)
            }

            OpCode.ARRAY_FILL -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                val at = module.typeSection().getSubType(typeIdx).compType().arrayType()!!
                stack.pop(unpackFieldType(at.fieldType()))
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
            }

            OpCode.ARRAY_COPY -> {
                val dstTypeIdx = ins.operand(0).toInt()
                val srcTypeIdx = ins.operand(1).toInt()
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, srcTypeIdx))
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, dstTypeIdx))
            }

            OpCode.ARRAY_INIT_DATA,
            OpCode.ARRAY_INIT_ELEM -> {
                val typeIdx = ins.operand(0).toInt()
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.pop(ValType.I32)
                stack.pop(valType(ValType.ID.RefNull, typeIdx))
            }

            OpCode.REF_TEST,
            OpCode.REF_TEST_NULL -> {
                stack.popRef()
                stack.push(ValType.I32)
            }

            OpCode.REF_I31 -> {
                stack.pop(ValType.I32)
                stack.push(
                    ValType.builder()
                        .withOpcode(ValType.ID.Ref)
                        .withTypeIdx(ValType.TypeIdxCode.I31.code())
                        .build()
                )
            }

            OpCode.I31_GET_S,
            OpCode.I31_GET_U -> {
                stack.popRef()
                stack.push(ValType.I32)
            }

            OpCode.ANY_CONVERT_EXTERN -> {
                stack.popRef()
                stack.push(
                    ValType.builder()
                        .withOpcode(ValType.ID.Ref)
                        .withTypeIdx(ValType.TypeIdxCode.ANY.code())
                        .build()
                )
            }

            OpCode.EXTERN_CONVERT_ANY -> {
                stack.popRef()
                stack.push(
                    ValType.builder()
                        .withOpcode(ValType.ID.Ref)
                        .withTypeIdx(ValType.TypeIdxCode.EXTERN.code())
                        .build()
                )
            }

            else -> throw WasmEngineException("Unhandled opcode: ${ins.opcode()}")
        }
        out.add(CompilerInstruction(CompilerOpCode.of(ins.opcode()), *ins.operands()))
    }

    private fun updateStack(stack: TypeStack, functionType: FunctionType) {
        for (type in reversed(functionType.params())) {
            stack.pop(type)
        }
        for (type in functionType.returns()) {
            stack.push(type)
        }
    }

    private fun unwindStack(
        functionType: FunctionType,
        body: FunctionBody,
        ins: AnnotatedInstruction,
        label: Int,
        stack: TypeStack,
    ): CompilerInstruction? {
        var forward = true

        var target = body.instructions()[label]
        if (target.address() <= ins.address()) {
            target = body.instructions()[label - 1]
            forward = false
        }
        var scope = target.scope()!!

        val blockType =
            if (scope.opcode() == OpCode.END) {
                scope = TypeStack.FUNCTION_SCOPE
                functionType
            } else {
                blockType(scope)
            }

        val types = if (forward) blockType.returns() else blockType.params()
        val keep = types.size

        val scopeSize = stack.scopeStackSize(scope) ?: return null
        var drop = stack.types().size - scopeSize

        if (forward) {
            drop -= types.size
        }

        if (drop <= 0) {
            return null
        }

        val operands = ArrayList<Long>()
        operands.add(drop.toLong())

        val dropKeepTypes = ArrayList(stack.types().take(drop + keep))
        dropKeepTypes.reverse()
        for (type in dropKeepTypes) {
            operands.add(type.id())
        }

        return CompilerInstruction(CompilerOpCode.DROP_KEEP, *operands.toLongArray())
    }

    private fun blockType(ins: Instruction): FunctionType {
        val typeId = ins.operand(0)
        if (typeId == 0x40L) {
            return FunctionType.empty()
        }
        if (ValType.isValid(typeId)) {
            return FunctionType.returning(
                ValType.builder().fromId(typeId).build().resolve(module.typeSection())
            )
        }
        return module.typeSection().getType(typeId.toInt())
    }

    internal fun functionTypeIndex(funcIdx: Int): Int {
        if (funcIdx < functionImports) {
            val imports = module.importSection().imports().filterIsInstance<FunctionImport>()
            return imports[funcIdx].typeIndex()
        }
        return module.functionSection().getFunctionType(funcIdx - functionImports)
    }

    private fun valType(opcode: Int, typeIdx: Int): ValType =
        ValType.builder()
            .withOpcode(opcode)
            .withTypeIdx(typeIdx)
            .build()
            .resolve(module.typeSection())

    companion object {
        private fun anyTrue(array: BooleanArray): Boolean = array.any { it }

        private fun getGlobalTypes(module: WasmModule): List<ValType> {
            val importedGlobals =
                module.importSection().imports().filterIsInstance<GlobalImport>().map { it.type() }

            val globals = module.globalSection()
            val moduleGlobals = List(globals.globalCount()) { globals.getGlobal(it).valueType() }

            return importedGlobals + moduleGlobals
        }

        private fun getFunctionTypes(module: WasmModule): List<FunctionType> {
            val importedFunctions =
                module.importSection().imports().filterIsInstance<FunctionImport>().map { function
                    ->
                    module.typeSection().getType(function.typeIndex())
                }

            val functions = module.functionSection()
            val moduleFunctions =
                List(functions.functionCount()) {
                    functions.getFunctionType(it, module.typeSection())
                }

            return importedFunctions + moduleFunctions
        }

        private fun getTableTypes(module: WasmModule): List<ValType> {
            val importedTables =
                module.importSection().imports().filterIsInstance<TableImport>().map {
                    it.entryType()
                }

            val tables = module.tableSection()
            val moduleTables = List(tables.tableCount()) { tables.getTable(it).elementType() }

            return importedTables + moduleTables
        }

        private fun getTagTypes(module: WasmModule): List<FunctionType> {
            val importedTags =
                module.importSection().imports().filterIsInstance<TagImport>().map {
                    module.typeSection().getType(it.tagType().typeIdx())
                }

            val tags = module.tagSection()
            val moduleTags =
                if (tags == null) {
                    emptyList()
                } else {
                    List(tags.tagCount()) { module.typeSection().getType(tags.getTag(it).typeIdx()) }
                }

            return importedTags + moduleTags
        }

        private fun <T> reversed(list: List<T>): List<T> {
            if (list.size <= 1) {
                return list
            }
            return ArrayList(list).apply { reverse() }
        }

        private fun ids(types: List<ValType>): LongArray = types.map { it.id() }.toLongArray()

        private fun unpackFieldType(ft: FieldType): ValType {
            if (ft.storageType().packedType() != null) {
                return ValType.I32
            }
            return ft.storageType().valType()!!
        }
    }
}
