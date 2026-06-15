package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.AnnotatedInstruction
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

    private val ctrlStack = ArrayList<CtrlFrame>()

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
    }

    fun reset(args: LongArray) {
        for (i in locals.indices) {
            setLocal(i, args[i])
        }
        pc = 0
    }

    fun funcId(): Int = funcId

    fun localType(i: Int): ValType = localTypes[i]

    fun localIndexOf(idx: Int): Int = localIdx[idx]

    fun setLocal(i: Int, value: Long) {
        locals[i] = value
    }

    fun local(i: Int): Long = locals[i]

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

    fun currentPc(): Int = pc - 1

    fun currentControlStartValues(): Int = layout.controlStartSlots[pc - 1]

    fun currentControlEndValues(): Int = layout.controlEndSlots[pc - 1]

    fun currentLocalSlot(): Int = layout.localSlots[pc - 1]

    fun currentLocalIsV128(): Boolean = layout.localIsV128[pc - 1]

    fun currentStructFieldIndex(): Int = layout.structFieldIndices[pc - 1]

    fun currentStructPackedMask(): Long = layout.structPackedMasks[pc - 1]

    fun currentStructPackedSignExtend(): Boolean = layout.structPackedSignExtend[pc - 1]

    fun terminated(): Boolean = pc >= code.size

    fun pushCtrl(ctrlFrame: CtrlFrame) {
        ctrlStack.add(ctrlFrame)
    }

    fun pushCtrl(opcode: OpCode, startValues: Int, returnValues: Int, height: Int) {
        ctrlStack.add(CtrlFrame(opcode, startValues, returnValues, height))
    }

    fun pushCtrl(opcode: OpCode, startValues: Int, returnValues: Int, height: Int, pc: Int) {
        ctrlStack.add(CtrlFrame(opcode, startValues, returnValues, height, pc))
    }

    fun ctrlStackSize(): Int = ctrlStack.size

    fun popCtrl(): CtrlFrame = ctrlStack.removeAt(ctrlStack.size - 1)

    fun popCtrl(n: Int): CtrlFrame {
        var mostRecentCallHeight = ctrlStack.size
        while (true) {
            if (ctrlStack[--mostRecentCallHeight].opCode == OpCode.CALL) {
                break
            }
        }
        val finalHeight = ctrlStack.size - (mostRecentCallHeight + n + 1)
        var ctrlFrame: CtrlFrame? = null
        while (ctrlStack.size > finalHeight) {
            ctrlFrame = popCtrl()
        }
        return ctrlFrame!!
    }

    fun popCtrlTillCall(): CtrlFrame {
        while (true) {
            val ctrlFrame = popCtrl()
            if (ctrlFrame.opCode == OpCode.CALL) {
                return ctrlFrame
            }
        }
    }

    fun jumpTo(newPc: Int) {
        pc = newPc
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
        val localSlots: IntArray
        val localIsV128: BooleanArray
        val structFieldIndices: IntArray
        val structPackedMasks: LongArray
        val structPackedSignExtend: BooleanArray

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
            localSlots = IntArray(this.code.size)
            localIsV128 = BooleanArray(this.code.size)
            structFieldIndices = IntArray(this.code.size)
            structPackedMasks = LongArray(this.code.size)
            structPackedSignExtend = BooleanArray(this.code.size)
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
                        localSlots[i] = localIdx[localIndex]
                        localIsV128[i] = localTypes[localIndex] == ValType.V128
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

                    else -> {}
                }
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
            val endResults = ctrlFrame.startValues + ctrlFrame.endValues
            if (endResults == 0) {
                stack.discardToSize(ctrlFrame.height)
                return
            }
            val returns = LongArray(endResults)
            for (i in returns.indices) {
                if (stack.size() > 0) {
                    returns[i] = stack.pop()
                }
            }

            while (stack.size() > ctrlFrame.height) {
                stack.pop()
            }

            for (i in returns.indices) {
                val value = returns[returns.size - 1 - i]
                stack.push(value)
            }
        }
    }
}
