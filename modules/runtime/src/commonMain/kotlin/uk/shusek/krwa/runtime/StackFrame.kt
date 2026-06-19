package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/**
 * Represents a frame, doesn't hold the stack, just local variables and the `pc` which is the
 * program counter in this function. Instead of keeping an absolute pointer to positions in code the
 * program counter is relative to the function and we store it here so we know where to resume when
 * we return from an inner function call. This also means it's not possible to set the program
 * counter to an instruction in another function on accident, as this is not allowed in the spec.
 * You can only jump to instructions within the function you are in and only specific places.
 */
class StackFrame {
    private val code: Array<AnnotatedInstruction>

    private val funcId: Int
    private var pc = 0
    private val locals: LongArray
    private val localTypes: Array<ValType>
    private val localIdx: IntArray
    private val layout: Layout
    private val instance: Instance

    private var ctrlStackSize = 0
    private var ctrlOpCodes = IntArray(MIN_CTRL_STACK_CAPACITY)
    private var ctrlStartValues = IntArray(MIN_CTRL_STACK_CAPACITY)
    private var ctrlEndValues = IntArray(MIN_CTRL_STACK_CAPACITY)
    private var ctrlHeights = IntArray(MIN_CTRL_STACK_CAPACITY)
    private var ctrlPcs = IntArray(MIN_CTRL_STACK_CAPACITY)

    constructor(
        instance: Instance,
        funcId: Int,
        args: LongArray,
    ) : this(instance, funcId, args, Layout(instance, emptyList(), emptyList(), emptyList()))

    constructor(
        instance: Instance,
        funcId: Int,
        args: LongArray,
        argsTypes: List<ValType>,
        localTypes: List<ValType>,
        code: List<AnnotatedInstruction>,
    ) : this(instance, funcId, args, Layout(instance, argsTypes, localTypes, code))

    constructor(
        instance: Instance,
        funcId: Int,
        args: LongArray,
        layout: Layout,
    ) {
        this.layout = layout
        this.code = layout.code
        this.instance = instance
        this.funcId = funcId
        this.locals = layout.defaultLocals.copyOf()
        args.copyInto(this.locals, endIndex = minOf(args.size, this.locals.size))
        this.localTypes = layout.localTypes
        this.localIdx = layout.localIdx
        ensureCtrlCapacity(layout.maxControlDepth + 1)
    }

    constructor(
        instance: Instance,
        funcId: Int,
        stack: MStack,
        argSlotCount: Int,
        layout: Layout,
    ) {
        this.layout = layout
        this.code = layout.code
        this.instance = instance
        this.funcId = funcId
        this.locals = layout.defaultLocals.copyOf()
        for (i in argSlotCount - 1 downTo 0) {
            locals[i] = stack.pop()
        }
        this.localTypes = layout.localTypes
        this.localIdx = layout.localIdx
        ensureCtrlCapacity(layout.maxControlDepth + 1)
    }

    fun reset(args: LongArray) {
        layout.defaultLocals.copyInto(locals)
        args.copyInto(locals, endIndex = minOf(args.size, locals.size))
        pc = 0
        ctrlStackSize = 0
    }

    fun reset(stack: MStack, argSlotCount: Int) {
        layout.defaultLocals.copyInto(locals)
        for (i in argSlotCount - 1 downTo 0) {
            locals[i] = stack.pop()
        }
        pc = 0
        ctrlStackSize = 0
    }

    fun funcId(): Int = funcId

    fun belongsTo(instance: Instance): Boolean = this.instance === instance

    fun localType(i: Int): ValType = localTypes[i]

    fun localIndexOf(idx: Int): Int = localIdx[idx]

    fun setLocal(i: Int, value: Long) {
        locals[i] = value
    }

    fun local(i: Int): Long = locals[i]

    internal fun localSlots(): LongArray = locals

    fun localSlotCount(): Int = locals.size

    fun localSlot(i: Int): Long = locals[i]

    override fun toString(): String {
        val nameSection = instance.module().nameSection()
        var id = "[$funcId]"
        if (nameSection != null) {
            val functionName = nameSection.nameOfFunction(funcId)
            if (functionName != null) {
                id = "$functionName$id"
            }
        }
        return id + "\n\tpc=" + pc + " locals=" + locals.contentToString()
    }

    fun loadCurrentInstruction(): AnnotatedInstruction {
        return code[pc++]
    }

    fun loadNextNonNopInstruction(): AnnotatedInstruction {
        var instruction = code[pc++]
        while (instruction.opcode() == OpCode.NOP && pc < code.size) {
            instruction = code[pc++]
        }
        return instruction
    }

    internal fun loweredFunction(): LoweredFunction? = layout.loweredFunction

    internal fun pushInitialLocalGet(stack: MStack): Boolean {
        val localSlot = layout.initialLocalGetSlot
        if (localSlot < 0 || layout.loweredFunction != null) return false
        val value = local(localSlot)
        val castTargetHeapType = layout.initialCastTargetHeapType
        if (castTargetHeapType >= 0) {
            if (!instance.heapTypeMatch(
                    value,
                    layout.initialCastNullable,
                    castTargetHeapType,
                    layout.initialCastSourceHeapType,
                )
            ) {
                throw TrapException("cast failure")
            }
            locals[layout.initialCastLocalSlot] = value
            if (layout.initialCastKeepsStack) {
                stack.push(value)
            }
        } else {
            stack.push(value)
        }
        pc = layout.initialLocalGetNextPc
        return true
    }

    internal fun loadLoweredOpcode(loweredFunction: LoweredFunction): Int =
        loweredFunction.opcodes[pc++]

    internal fun loadLoweredIndex(): Int = pc++

    internal fun loweredPc(): Int = pc

    internal fun updateLoweredPc(newPc: Int) {
        pc = newPc
    }

    internal fun currentLoweredOperand(loweredFunction: LoweredFunction): Long =
        loweredFunction.operands[pc - 1]

    internal fun currentLoweredOperand2(loweredFunction: LoweredFunction): Int =
        loweredFunction.operands2[pc - 1]

    internal fun currentLoweredOperand3(loweredFunction: LoweredFunction): Int =
        loweredFunction.operands3[pc - 1]

    internal fun currentLoweredLabelTrue(loweredFunction: LoweredFunction): Int =
        loweredFunction.labelTrue[pc - 1]

    internal fun currentLoweredLabelFalse(loweredFunction: LoweredFunction): Int =
        loweredFunction.labelFalse[pc - 1]

    fun currentPc(): Int = pc - 1

    fun currentControlStartValues(): Int = layout.controlStartSlots[pc - 1]

    fun currentControlEndValues(): Int = layout.controlEndSlots[pc - 1]

    internal fun controlStartValuesAt(index: Int): Int = layout.controlStartSlots[index]

    internal fun controlEndValuesAt(index: Int): Int = layout.controlEndSlots[index]

    fun currentLiteralValue(): Long = layout.literalValues[pc - 1]

    fun currentLocalInfo(): Int = layout.localInfos[pc - 1]

    fun currentFusedCountdownBranchLocalSlot(): Int =
        layout.fusedCountdownBranchLocalSlots[pc - 1]

    fun currentFusedCountdownBranchConstant(): Int =
        layout.fusedCountdownBranchConstants[pc - 1]

    fun currentFusedCountdownBranchDepth(): Int =
        layout.fusedCountdownBranchDepths[pc - 1]

    fun currentFusedCountdownBranchTrueLabel(): Int =
        layout.fusedCountdownBranchTrueLabels[pc - 1]

    fun currentFusedCountdownBranchFalseLabel(): Int =
        layout.fusedCountdownBranchFalseLabels[pc - 1]

    fun hasFusedCountdownBranches(): Boolean = layout.hasFusedCountdownBranches

    fun currentFusedLocalSetNextLocalGetSlot(): Int =
        layout.fusedLocalSetNextLocalGetSlots[pc - 1]

    fun currentStructFieldIndex(): Int = layout.structFieldIndices[pc - 1]

    fun currentStructPackedMask(): Long = layout.structPackedMasks[pc - 1]

    fun currentStructPackedSignExtend(): Boolean = layout.structPackedSignExtend[pc - 1]

    fun currentCallFunctionId(): Int = layout.callFunctionIds[pc - 1]

    fun currentCallType(): FunctionType = layout.callTypes[pc - 1]!!

    fun currentCallBody(): FunctionBody? = layout.callBodies[pc - 1]

    fun terminated(): Boolean = pc >= code.size

    fun pushCtrl(ctrlFrame: CtrlFrame) {
        pushCtrl(
            ctrlFrame.opCode,
            ctrlFrame.startValues,
            ctrlFrame.endValues,
            ctrlFrame.height,
            ctrlFrame.pc,
        )
    }

    fun pushCtrl(opcode: OpCode, startValues: Int, returnValues: Int, height: Int) {
        pushCtrl(opcode, startValues, returnValues, height, 0)
    }

    fun pushCtrl(opcode: OpCode, startValues: Int, returnValues: Int, height: Int, pc: Int) {
        ensureCtrlCapacity(ctrlStackSize + 1)
        ctrlOpCodes[ctrlStackSize] = opcode.ordinal
        ctrlStartValues[ctrlStackSize] = startValues
        ctrlEndValues[ctrlStackSize] = returnValues
        ctrlHeights[ctrlStackSize] = height
        ctrlPcs[ctrlStackSize] = pc
        ctrlStackSize += 1
    }

    fun pushCtrlPreallocated(opcode: OpCode, startValues: Int, returnValues: Int, height: Int) {
        ctrlOpCodes[ctrlStackSize] = opcode.ordinal
        ctrlStartValues[ctrlStackSize] = startValues
        ctrlEndValues[ctrlStackSize] = returnValues
        ctrlHeights[ctrlStackSize] = height
        ctrlPcs[ctrlStackSize] = 0
        ctrlStackSize += 1
    }

    fun ctrlStackSize(): Int = ctrlStackSize

    internal fun snapshot(): StackFrame {
        val snapshot = StackFrame(instance, funcId, LongArray(0), layout)
        locals.copyInto(snapshot.locals)
        snapshot.pc = pc
        snapshot.ctrlStackSize = ctrlStackSize
        snapshot.ctrlOpCodes = ctrlOpCodes.copyOf()
        snapshot.ctrlStartValues = ctrlStartValues.copyOf()
        snapshot.ctrlEndValues = ctrlEndValues.copyOf()
        snapshot.ctrlHeights = ctrlHeights.copyOf()
        snapshot.ctrlPcs = ctrlPcs.copyOf()
        return snapshot
    }

    fun popCtrl(): CtrlFrame {
        ctrlStackSize -= 1
        return ctrlFrameAt(ctrlStackSize)
    }

    fun popCtrl(n: Int): CtrlFrame {
        val targetIndex = ctrlStackSize - n - 1
        val ctrlFrame = ctrlFrameAt(targetIndex)
        ctrlStackSize = targetIndex
        return ctrlFrame
    }

    fun popCtrlTillCall(): CtrlFrame {
        val ctrlFrame = ctrlFrameAt(CALL_CTRL_INDEX)
        ctrlStackSize = CALL_CTRL_INDEX
        return ctrlFrame
    }

    fun popCtrlAndTransfer(stack: MStack) {
        ctrlStackSize -= 1
        doControlTransfer(
            ctrlStartValues[ctrlStackSize],
            ctrlEndValues[ctrlStackSize],
            ctrlHeights[ctrlStackSize],
            stack,
        )
    }

    fun popCtrlTillCallAndTransfer(stack: MStack) {
        doControlTransfer(
            ctrlStartValues[CALL_CTRL_INDEX],
            ctrlEndValues[CALL_CTRL_INDEX],
            ctrlHeights[CALL_CTRL_INDEX],
            stack,
        )
        ctrlStackSize = CALL_CTRL_INDEX
    }

    fun branchTo(n: Int, stack: MStack) {
        val targetIndex = ctrlStackSize - n - 1
        val opCode = ctrlOpCodes[targetIndex]
        ctrlStackSize = targetIndex + 1
        if (opCode == LOOP_OPCODE) {
            doControlTransfer(
                ctrlStartValues[targetIndex],
                ctrlEndValues[targetIndex],
                ctrlHeights[targetIndex],
                stack,
            )
        }
    }

    fun branchToCurrentParameterlessLoopUnchecked(stack: MStack) {
        val targetIndex = ctrlStackSize - 1
        stack.shrinkToSize(ctrlHeights[targetIndex])
    }

    fun jumpTo(newPc: Int) {
        pc = newPc
    }

    private fun ctrlFrameAt(index: Int): CtrlFrame =
        CtrlFrame(
            OpCode.entries[ctrlOpCodes[index]],
            ctrlStartValues[index],
            ctrlEndValues[index],
            ctrlHeights[index],
            ctrlPcs[index],
        )

    private fun ensureCtrlCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= ctrlOpCodes.size) return

        val newCapacity = ctrlOpCodes.size shl 1
        ctrlOpCodes = ctrlOpCodes.copyOf(newCapacity)
        ctrlStartValues = ctrlStartValues.copyOf(newCapacity)
        ctrlEndValues = ctrlEndValues.copyOf(newCapacity)
        ctrlHeights = ctrlHeights.copyOf(newCapacity)
        ctrlPcs = ctrlPcs.copyOf(newCapacity)
    }

    class Layout(
        instance: Instance,
        argsTypes: List<ValType>,
        bodyLocalTypes: List<ValType>,
        code: List<AnnotatedInstruction>,
    ) {
        val code: Array<AnnotatedInstruction> = code.toTypedArray()
        val localTypes: Array<ValType>
        val localIdx: IntArray
        val defaultLocals: LongArray
        val controlStartSlots: IntArray
        val controlEndSlots: IntArray
        val literalValues: LongArray
        val localInfos: IntArray
        val fusedCountdownBranchLocalSlots: IntArray
        val fusedCountdownBranchConstants: IntArray
        val fusedCountdownBranchDepths: IntArray
        val fusedCountdownBranchTrueLabels: IntArray
        val fusedCountdownBranchFalseLabels: IntArray
        var hasFusedCountdownBranches = false
            private set
        val fusedLocalSetNextLocalGetSlots: IntArray
        val structFieldIndices: IntArray
        val structPackedMasks: LongArray
        val structPackedSignExtend: BooleanArray
        val callFunctionIds: IntArray
        val callTypes: Array<FunctionType?>
        val callBodies: Array<FunctionBody?>
        val initialLocalGetSlot: Int
        val initialLocalGetNextPc: Int
        val initialCastTargetHeapType: Int
        val initialCastSourceHeapType: Int
        val initialCastNullable: Boolean
        val initialCastLocalSlot: Int
        val initialCastKeepsStack: Boolean
        val maxControlDepth: Int
        internal val loweredFunction: LoweredFunction?

        init {
            val argsSlotCount = ValType.sizeOf(argsTypes)
            val localSlotCount = ValType.sizeOf(bodyLocalTypes)
            defaultLocals = LongArray(argsSlotCount + localSlotCount)
            val localsSize = argsTypes.size + bodyLocalTypes.size
            localTypes =
                Array(localsSize) { idx ->
                    if (idx < argsTypes.size) {
                        argsTypes[idx]
                    } else {
                        bodyLocalTypes[idx - argsTypes.size]
                    }
            }
            localIdx = IntArray(localsSize)

            var slot = argsSlotCount
            for (i in bodyLocalTypes.indices) {
                val type = bodyLocalTypes[i]
                if (type != ValType.V128) {
                    defaultLocals[slot] = Value.zero(type)
                    slot += 1
                } else {
                    defaultLocals[slot] = Value.zero(ValType.I64)
                    defaultLocals[slot + 1] = Value.zero(ValType.I64)
                    slot += 2
                }
            }

            slot = 0
            for (i in localTypes.indices) {
                localIdx[i] = slot
                if (localTypes[i] != ValType.V128) {
                    slot += 1
                } else {
                    slot += 2
                }
            }

            controlStartSlots = IntArray(this.code.size)
            controlEndSlots = IntArray(this.code.size)
            literalValues = LongArray(this.code.size)
            localInfos = IntArray(this.code.size)
            fusedCountdownBranchLocalSlots = IntArray(this.code.size) { -1 }
            fusedCountdownBranchConstants = IntArray(this.code.size)
            fusedCountdownBranchDepths = IntArray(this.code.size)
            fusedCountdownBranchTrueLabels = IntArray(this.code.size)
            fusedCountdownBranchFalseLabels = IntArray(this.code.size)
            fusedLocalSetNextLocalGetSlots = IntArray(this.code.size) { -1 }
            structFieldIndices = IntArray(this.code.size)
            structPackedMasks = LongArray(this.code.size)
            structPackedSignExtend = BooleanArray(this.code.size)
            callFunctionIds = IntArray(this.code.size)
            callTypes = arrayOfNulls(this.code.size)
            callBodies = arrayOfNulls(this.code.size)
            val controlParameterlessLoopStack = BooleanArray(this.code.size + 1)
            var controlDepth = 0
            var maxControlDepth = 0
            for (i in this.code.indices) {
                val instruction = this.code[i]
                when (instruction.opcode()) {
                    OpCode.BLOCK,
                    OpCode.LOOP,
                    OpCode.IF,
                    OpCode.TRY_TABLE -> {
                        controlStartSlots[i] = controlParamSlotCount(instance, instruction)
                        controlEndSlots[i] = controlReturnSlotCount(instance, instruction)
                    }

                    OpCode.LOCAL_GET,
                    OpCode.LOCAL_SET,
                    OpCode.LOCAL_TEE,
                    -> {
                        val localIndex = instruction.operand(0).toInt()
                        val slot = localIdx[localIndex]
                        localInfos[i] =
                            if (localTypes[localIndex] == ValType.V128) {
                                -slot - 1
                            } else {
                                slot
                            }
                    }

                    OpCode.I32_CONST,
                    OpCode.I64_CONST,
                    OpCode.F32_CONST,
                    OpCode.F64_CONST,
                    -> {
                        literalValues[i] = instruction.operand(0)
                    }

                    OpCode.STRUCT_GET,
                    OpCode.STRUCT_GET_S,
                    OpCode.STRUCT_GET_U,
                    -> {
                        val typeIndex = instruction.operand(0).toInt()
                        val fieldIndex = instruction.operand(1).toInt()
                        structFieldIndices[i] = fieldIndex

                        val structType =
                            instance.module().typeSection().getSubType(typeIndex).compType().structType()!!
                        val packedType =
                            structType.fieldTypes()[fieldIndex].storageType().packedType()
                        if (packedType != null) {
                            structPackedMasks[i] = packedType.mask()
                            structPackedSignExtend[i] = instruction.opcode() == OpCode.STRUCT_GET_S
                        }
                    }

                    OpCode.CALL -> {
                        val funcId = instruction.operand(0).toInt()
                        callFunctionIds[i] = funcId
                        callTypes[i] = instance.type(instance.functionType(funcId))
                        callBodies[i] = instance.function(funcId)
                    }

                    else -> {}
                }
                predecodeFusedCountdownBranch(
                    i,
                    controlDepth > 0 && controlParameterlessLoopStack[controlDepth - 1],
                )
                predecodeFusedLocalSetNextLocalGet(i)
                when (instruction.opcode()) {
                    OpCode.BLOCK,
                    OpCode.LOOP,
                    OpCode.IF,
                    OpCode.TRY_TABLE,
                    -> {
                        controlParameterlessLoopStack[controlDepth] =
                            instruction.opcode() == OpCode.LOOP &&
                            instruction.operand(0).toInt() == 0x40
                        controlDepth++
                        if (controlDepth > maxControlDepth) {
                            maxControlDepth = controlDepth
                        }
                    }

                    OpCode.END -> {
                        if (controlDepth > 0) controlDepth--
                    }

                    else -> {}
                }
            }
            this.maxControlDepth = maxControlDepth
            loweredFunction = LoweredFunction.tryBuild(this.code, localIdx, localTypes)
            val initialLocalGet = predecodeInitialLocalGet()
            initialLocalGetSlot = initialLocalGet.localSlot
            initialLocalGetNextPc = initialLocalGet.nextPc
            initialCastTargetHeapType = initialLocalGet.castTargetHeapType
            initialCastSourceHeapType = initialLocalGet.castSourceHeapType
            initialCastNullable = initialLocalGet.castNullable
            initialCastLocalSlot = initialLocalGet.castLocalSlot
            initialCastKeepsStack = initialLocalGet.castKeepsStack
        }

        private fun predecodeFusedCountdownBranch(index: Int, hasCurrentParameterlessLoop: Boolean) {
            if (index + 4 >= code.size || code[index].opcode() != OpCode.LOCAL_GET) return

            val constInstruction = code[index + 1]
            val subInstruction = code[index + 2]
            val teeInstruction = code[index + 3]
            val branchInstruction = code[index + 4]
            if (
                constInstruction.opcode() != OpCode.I32_CONST ||
                    subInstruction.opcode() != OpCode.I32_SUB ||
                    teeInstruction.opcode() != OpCode.LOCAL_TEE ||
                    branchInstruction.opcode() != OpCode.BR_IF
            ) {
                return
            }

            val localIndex = code[index].operand(0).toInt()
            if (localIndex != teeInstruction.operand(0).toInt()) return
            if (localTypes[localIndex] == ValType.V128) return

            fusedCountdownBranchLocalSlots[index] = localIdx[localIndex]
            hasFusedCountdownBranches = true
            fusedCountdownBranchConstants[index] = constInstruction.operand(0).toInt()
            val branchDepth = branchInstruction.operand(0).toInt()
            fusedCountdownBranchDepths[index] =
                if (branchDepth == 0 && hasCurrentParameterlessLoop) {
                    LoweredFunction.CURRENT_PARAMETERLESS_LOOP_DEPTH
                } else {
                    branchDepth
                }
            fusedCountdownBranchTrueLabels[index] = branchInstruction.labelTrue()
            fusedCountdownBranchFalseLabels[index] = branchInstruction.labelFalse()
        }

        private fun predecodeFusedLocalSetNextLocalGet(index: Int) {
            if (index + 1 >= code.size || code[index].opcode() != OpCode.LOCAL_SET) return
            if (code[index + 1].opcode() != OpCode.LOCAL_GET) return
            if (isFusedCountdownBranchStart(index + 1)) return

            val setLocalIndex = code[index].operand(0).toInt()
            val getLocalIndex = code[index + 1].operand(0).toInt()
            if (localTypes[setLocalIndex] == ValType.V128) return
            if (localTypes[getLocalIndex] == ValType.V128) return

            fusedLocalSetNextLocalGetSlots[index] = localIdx[getLocalIndex]
        }

        private fun isFusedCountdownBranchStart(index: Int): Boolean {
            if (index + 4 >= code.size || code[index].opcode() != OpCode.LOCAL_GET) return false
            if (code[index + 1].opcode() != OpCode.I32_CONST) return false
            if (code[index + 2].opcode() != OpCode.I32_SUB) return false
            if (code[index + 3].opcode() != OpCode.LOCAL_TEE) return false
            if (code[index + 4].opcode() != OpCode.BR_IF) return false

            val localIndex = code[index].operand(0).toInt()
            if (localIndex != code[index + 3].operand(0).toInt()) return false
            return localTypes[localIndex] != ValType.V128
        }

        private fun predecodeInitialLocalGet(): InitialLocalPath {
            var index = 0
            while (index < code.size && code[index].opcode() == OpCode.NOP) {
                index++
            }
            if (index >= code.size || code[index].opcode() != OpCode.LOCAL_GET) return InitialLocalPath.None
            if (isFusedCountdownBranchStart(index)) return InitialLocalPath.None

            val info = localInfos[index]
            if (info < 0) return InitialLocalPath.None
            val nextIndex = index + 1
            if (nextIndex + 1 < code.size &&
                (
                    code[nextIndex].opcode() == OpCode.CAST_TEST ||
                        code[nextIndex].opcode() == OpCode.CAST_TEST_NULL
                ) &&
                (
                    code[nextIndex + 1].opcode() == OpCode.LOCAL_TEE ||
                        code[nextIndex + 1].opcode() == OpCode.LOCAL_SET
                )
            ) {
                val targetLocalIndex = code[nextIndex + 1].operand(0).toInt()
                val targetLocalSlot = localIdx[targetLocalIndex]
                if (targetLocalSlot >= 0 && localTypes[targetLocalIndex] != ValType.V128) {
                    return InitialLocalPath(
                        localSlot = info,
                        nextPc = nextIndex + 2,
                        castTargetHeapType = code[nextIndex].operand(0).toInt(),
                        castSourceHeapType = code[nextIndex].operand(1).toInt(),
                        castNullable = code[nextIndex].opcode() == OpCode.CAST_TEST_NULL,
                        castLocalSlot = targetLocalSlot,
                        castKeepsStack = code[nextIndex + 1].opcode() == OpCode.LOCAL_TEE,
                    )
                }
            }
            return InitialLocalPath(
                localSlot = info,
                nextPc = index + 1,
                castTargetHeapType = -1,
                castSourceHeapType = 0,
                castNullable = false,
                castLocalSlot = -1,
                castKeepsStack = false,
            )
        }

        private data class InitialLocalPath(
            val localSlot: Int,
            val nextPc: Int,
            val castTargetHeapType: Int,
            val castSourceHeapType: Int,
            val castNullable: Boolean,
            val castLocalSlot: Int,
            val castKeepsStack: Boolean,
        ) {
            companion object {
                val None = InitialLocalPath(
                    localSlot = -1,
                    nextPc = 0,
                    castTargetHeapType = -1,
                    castSourceHeapType = 0,
                    castNullable = false,
                    castLocalSlot = -1,
                    castKeepsStack = false,
                )
            }
        }

        private fun controlParamSlotCount(instance: Instance, instruction: AnnotatedInstruction): Int {
            val typeId = instruction.operand(0).toInt()
            if (typeId == 0x40) {
                return 0
            }
            if (ValType.isValid(typeId.toLong())) {
                return 0
            }
            return instance.type(typeId).paramSlotCount()
        }

        private fun controlReturnSlotCount(instance: Instance, instruction: AnnotatedInstruction): Int {
            val typeId = instruction.operand(0).toInt()
            if (typeId == 0x40) {
                return 0
            }
            if (ValType.isValid(typeId.toLong())) {
                return if (typeId.toLong() == ValType.V128.id()) 2 else 1
            }
            return instance.type(typeId).returnSlotCount()
        }

    }

    companion object {
        fun doControlTransfer(ctrlFrame: CtrlFrame, stack: MStack) {
            doControlTransfer(ctrlFrame.startValues, ctrlFrame.endValues, ctrlFrame.height, stack)
        }

        private fun doControlTransfer(startValues: Int, endValues: Int, height: Int, stack: MStack) {
            val endResults = startValues + endValues
            when (endResults) {
                0 -> {
                    stack.shrinkToSize(height)
                }

                1 -> {
                    stack.discardToSizeKeepingTop(height)
                }

                2 -> {
                    stack.discardToSizeKeepingTop2(height)
                }

                else -> {
                    val returns = LongArray(endResults)
                    for (i in returns.indices) {
                        if (stack.size() > 0) {
                            returns[i] = stack.pop()
                        }
                    }

                    stack.shrinkToSize(height)

                    for (i in returns.indices) {
                        val value = returns[returns.size - 1 - i]
                        stack.push(value)
                    }
                }
            }
        }

        private const val MIN_CTRL_STACK_CAPACITY = 8
        private const val CALL_CTRL_INDEX = 0
        private val CALL_OPCODE = OpCode.CALL.ordinal
        private val LOOP_OPCODE = OpCode.LOOP.ordinal
    }
}
