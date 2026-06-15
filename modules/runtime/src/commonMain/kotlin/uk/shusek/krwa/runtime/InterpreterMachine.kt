package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.CatchOpCode
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.TypeSection
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/** This is responsible for holding and interpreting the Wasm code. */
open class InterpreterMachine(private val instance: Instance) : Machine {
    private enum class AtomicOp {
        ADD,
        SUB,
        AND,
        OR,
        XOR,
        XCHG,
    }

    private val stack = MStack()
    private var frameLayouts: Array<StackFrame.Layout?>? = null

    protected val callStack: ArrayDeque<StackFrame> = ArrayDeque()

    protected class Operands {
        private lateinit var instruction: Instruction

        fun reset(instruction: Instruction): Operands {
            this.instruction = instruction
            return this
        }

        fun get(index: Int): Long = instruction.operand(index)
    }

    private fun MStack.push(value: Int) {
        push(value.toLong())
    }

    private fun newStackFrame(
        targetInstance: Instance,
        funcId: Int,
        args: LongArray,
        type: FunctionType,
        func: FunctionBody,
    ): StackFrame {
        val cachedLayouts =
            if (targetInstance == instance) {
                frameLayouts ?: arrayOfNulls<StackFrame.Layout>(instance.functionCount()).also {
                    frameLayouts = it
                }
            } else {
                null
            }
        val layout =
            if (cachedLayouts != null && funcId >= 0 && funcId < cachedLayouts.size) {
                cachedLayouts[funcId]
                    ?: StackFrame.Layout(targetInstance, type.params(), func.localTypes(), func.instructions())
                        .also { cachedLayouts[funcId] = it }
            } else {
                StackFrame.Layout(targetInstance, type.params(), func.localTypes(), func.instructions())
            }
        return StackFrame(targetInstance, funcId, args, layout)
    }

    @Suppress("DoNotCallSuggester")
    protected open fun evalDefault(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        instruction: Instruction,
        operands: Operands,
    ) {
        throw RuntimeException("Machine doesn't recognize Instruction " + instruction)
    }

    override fun call(funcId: Int, args: LongArray): LongArray {
        return call(stack, instance, callStack, funcId, args, null, true)
    }

    protected open fun call(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        funcId: Int,
        args: LongArray,
        callType: FunctionType?,
        popResults: Boolean,
    ): LongArray {

        checkInterruption()
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)

        if (callType != null) {
            verifyIndirectCall(type, callType, instance.module().typeSection())
        }

        var func = instance.function(funcId)
        val callStackDepth = callStack.size
        try {
            if (func != null) {
                var stackFrame =
                    newStackFrame(instance, funcId, args, type, func)
                stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                checkCallStackDepth(callStack)
                callStack.addLast(stackFrame)

                try {
                    RuntimePlatform.runCatchingStackOverflow {
                        eval(stack, instance, callStack)
                    }
                } finally {
                    if (callStack.isNotEmpty() && callStack.last() == stackFrame) {
                        callStack.removeLast()
                    }
                }
            } else {
                var stackFrame = StackFrame(instance, funcId, args)
                stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                checkCallStackDepth(callStack)
                callStack.addLast(stackFrame)

                var imprt = instance.imports().function(funcId)

                try {
                    RuntimePlatform.runCatchingStackOverflow {
                        var results = imprt.handle()!!.apply(instance, args)
                        // a host function can return null or an array of ints
                        // which we will push onto the stack
                        if (results != null) {
                            for (result in results) {
                                stack.push(result)
                            }
                        }
                    }
                } catch (e: WasmException) {
                    THROW_REF(instance, instance.registerException(e), stack, stackFrame, callStack)
                } finally {
                    if (callStack.isNotEmpty() && callStack.last() == stackFrame) {
                        callStack.removeLast()
                    }
                }
            }
        } finally {
            while (callStack.size > callStackDepth) {
                callStack.removeLast()
            }
        }

        if (!popResults) {
            return LongArray(0)
        }

        if (type.returnSlotCount() == 0) {
            return LongArray(0)
        }
        if (stack.size() == 0) {
            return LongArray(0)
        }

        var totalResults = type.returnSlotCount()
        var results = LongArray(totalResults)
        for (i in totalResults - 1 downTo 0) {
            results[i] = stack.pop()
        }
        return results
    }

    private fun checkCallStackDepth(callStack: ArrayDeque<StackFrame>) {
        if (callStack.size >= MAX_CALL_STACK_DEPTH) {
            throw WasmEngineException("call stack exhausted")
        }
    }

    protected fun instance(): Instance {
        return instance
    }

    protected fun stack(): MStack {
        return stack
    }

    protected open fun isInterrupted(): Boolean = false

    private fun usesOperandWrapper(opcode: OpCode): Boolean =
        when (opcode) {
            OpCode.NOP,
            OpCode.LOOP,
            OpCode.BLOCK,
            OpCode.TRY_TABLE,
            OpCode.IF,
            OpCode.ELSE,
            OpCode.BR,
            OpCode.BR_IF,
            OpCode.BR_TABLE,
            OpCode.BR_ON_NULL,
            OpCode.BR_ON_NON_NULL,
            OpCode.END,
            OpCode.RETURN,
            OpCode.RETURN_CALL_REF,
            OpCode.LOCAL_GET,
            OpCode.LOCAL_SET,
            OpCode.LOCAL_TEE,
            OpCode.STRUCT_GET,
            OpCode.STRUCT_GET_S,
            OpCode.STRUCT_GET_U,
            -> false

            else -> true
        }

    protected open fun eval(stack: MStack, instance: Instance, callStack: ArrayDeque<StackFrame>) {
        var frame = callStack.last()
        val operands = Operands()
        var gcPoll = 0
        val executionListener = instance.executionListener()

        while (!frame.terminated() && frame.ctrlStackSize() > 0) {
            var instruction = frame.loadCurrentInstruction()
            var opcode = instruction.opcode()
            if (usesOperandWrapper(opcode)) {
                operands.reset(instruction)
            }
            executionListener?.onExecution(instruction, stack)
            gcPoll++
            if (gcPoll == GC_POLL_INTERVAL) {
                instance.gcSafePoint(stack, callStack)
                gcPoll = 0
            }
            when (opcode) {
                OpCode.UNREACHABLE -> {
                    throw TrapException("Trapped on unreachable instruction")
                }
                OpCode.NOP -> {}
                OpCode.LOOP,
                OpCode.BLOCK -> {
                    BLOCK(frame, stack, instruction)
                }
                OpCode.TRY_TABLE -> {
                    TRY_TABLE(frame, stack, instruction, frame.currentPc())
                }
                OpCode.IF -> {
                    IF(frame, stack, instruction)
                }
                OpCode.ELSE -> {
                    frame.jumpTo(instruction.labelTrue())
                }
                OpCode.BR -> {
                    BR(frame, stack, instruction)
                }
                OpCode.BR_IF -> {
                    BR_IF(frame, stack, instruction)
                }
                OpCode.BR_TABLE -> {
                    BR_TABLE(frame, stack, instruction)
                }
                OpCode.BR_ON_NULL -> {
                    BR_ON_NULL(frame, stack, instruction)
                }
                OpCode.BR_ON_NON_NULL -> {
                    BR_ON_NON_NULL(frame, stack, instruction)
                }
                OpCode.END -> {
                    var ctrlFrame = frame.popCtrl()
                    StackFrame.doControlTransfer(ctrlFrame, stack)

                    // if this is the last end, then we're done with
                    // the function
                    if (instruction.depth() == 0) {
                        frame = completeFrame(frame, callStack) ?: return
                    }
                }
                OpCode.RETURN -> {
                    // RETURN doesn't pass through the END
                    var ctrlFrame = frame.popCtrlTillCall()
                    StackFrame.doControlTransfer(ctrlFrame, stack)
                    frame = completeFrame(frame, callStack) ?: return
                }
                OpCode.RETURN_CALL -> {
                    // swap in place the current frame
                    frame = RETURN_CALL(stack, instance, callStack, operands, frame)
                }
                OpCode.RETURN_CALL_INDIRECT -> {
                    // swap in place the current frame
                    frame = RETURN_CALL_INDIRECT(stack, instance, callStack, operands, frame)
                }
                OpCode.RETURN_CALL_REF -> {
                    // swap in place the current frame
                    frame = RETURN_CALL_REF(stack, instance, callStack, frame)
                }
                OpCode.THROW -> {
                    var tagNumber = operands.get(0).toInt()
                    var tag = instance.tag(tagNumber)
                    var type = instance.type(tag.tagType().typeIdx())

                    var args = extractArgsForParams(stack, type)
                    var exception = WasmException(instance, tagNumber, args)
                    var exceptionIdx = instance.registerException(exception)
                    frame = THROW_REF(instance, exceptionIdx, stack, frame, callStack)
                }
                OpCode.THROW_REF -> {
                    var exceptionIdx = stack.pop().toInt()
                    frame = THROW_REF(instance, exceptionIdx, stack, frame, callStack)
                }
                OpCode.CALL_INDIRECT -> {
                    frame = CALL_INDIRECT(stack, instance, callStack, operands) ?: frame
                }
                OpCode.DROP -> {
                    DROP(stack, operands)
                }
                OpCode.SELECT -> {
                    SELECT(stack, operands)
                }
                OpCode.SELECT_T -> {
                    SELECT_T(stack, operands)
                }
                OpCode.LOCAL_GET -> {
                    LOCAL_GET(stack, frame)
                }
                OpCode.LOCAL_SET -> {
                    LOCAL_SET(stack, frame)
                }
                OpCode.LOCAL_TEE -> {
                    LOCAL_TEE(stack, frame)
                }
                OpCode.GLOBAL_GET -> {
                    GLOBAL_GET(stack, instance, operands)
                }
                OpCode.GLOBAL_SET -> {
                    GLOBAL_SET(stack, instance, operands)
                }
                OpCode.TABLE_GET -> {
                    TABLE_GET(stack, instance, operands)
                }
                OpCode.TABLE_SET -> {
                    TABLE_SET(stack, instance, operands)
                }
                OpCode.I32_LOAD -> {
                    I32_LOAD(stack, instance, operands)
                }
                OpCode.I64_LOAD -> {
                    I64_LOAD(stack, instance, operands)
                }
                OpCode.F32_LOAD -> {
                    F32_LOAD(stack, instance, operands)
                }
                OpCode.F64_LOAD -> {
                    F64_LOAD(stack, instance, operands)
                }
                OpCode.I32_LOAD8_S -> {
                    I32_LOAD8_S(stack, instance, operands)
                }
                OpCode.I64_LOAD8_S -> {
                    I64_LOAD8_S(stack, instance, operands)
                }
                OpCode.I32_LOAD8_U -> {
                    I32_LOAD8_U(stack, instance, operands)
                }
                OpCode.I64_LOAD8_U -> {
                    I64_LOAD8_U(stack, instance, operands)
                }
                OpCode.I32_LOAD16_S -> {
                    I32_LOAD16_S(stack, instance, operands)
                }
                OpCode.I64_LOAD16_S -> {
                    I64_LOAD16_S(stack, instance, operands)
                }
                OpCode.I32_LOAD16_U -> {
                    I32_LOAD16_U(stack, instance, operands)
                }
                OpCode.I64_LOAD16_U -> {
                    I64_LOAD16_U(stack, instance, operands)
                }
                OpCode.I64_LOAD32_S -> {
                    I64_LOAD32_S(stack, instance, operands)
                }
                OpCode.I64_LOAD32_U -> {
                    I64_LOAD32_U(stack, instance, operands)
                }
                OpCode.I32_STORE -> {
                    I32_STORE(stack, instance, operands)
                }
                OpCode.I32_STORE16,
                OpCode.I64_STORE16 -> {
                    I64_STORE16(stack, instance, operands)
                }
                OpCode.I64_STORE -> {
                    I64_STORE(stack, instance, operands)
                }
                OpCode.F32_STORE -> {
                    F32_STORE(stack, instance, operands)
                }
                OpCode.F64_STORE -> {
                    F64_STORE(stack, instance, operands)
                }
                OpCode.MEMORY_GROW -> {
                    MEMORY_GROW(stack, instance, operands)
                }
                OpCode.MEMORY_FILL -> {
                    MEMORY_FILL(stack, instance, operands)
                }
                OpCode.I32_STORE8,
                OpCode.I64_STORE8 -> {
                    I64_STORE8(stack, instance, operands)
                }
                OpCode.I64_STORE32 -> {
                    I64_STORE32(stack, instance, operands)
                }
                OpCode.MEMORY_SIZE -> {
                    MEMORY_SIZE(stack, instance, operands)
                }
                OpCode.I32_CONST -> {
                    stack.push(operands.get(0))
                }
                OpCode.I64_CONST -> {
                    stack.push(operands.get(0))
                }
                OpCode.F32_CONST -> {
                    stack.push(operands.get(0))
                }
                OpCode.F64_CONST -> {
                    stack.push(operands.get(0))
                }
                OpCode.I32_EQ -> {
                    I32_EQ(stack)
                }
                OpCode.I64_EQ -> {
                    I64_EQ(stack)
                }
                OpCode.I32_NE -> {
                    I32_NE(stack)
                }
                OpCode.I64_NE -> {
                    I64_NE(stack)
                }
                OpCode.I32_EQZ -> {
                    I32_EQZ(stack)
                }
                OpCode.I64_EQZ -> {
                    I64_EQZ(stack)
                }
                OpCode.I32_LT_S -> {
                    I32_LT_S(stack)
                }
                OpCode.I32_LT_U -> {
                    I32_LT_U(stack)
                }
                OpCode.I64_LT_S -> {
                    I64_LT_S(stack)
                }
                OpCode.I64_LT_U -> {
                    I64_LT_U(stack)
                }
                OpCode.I32_GT_S -> {
                    I32_GT_S(stack)
                }
                OpCode.I32_GT_U -> {
                    I32_GT_U(stack)
                }
                OpCode.I64_GT_S -> {
                    I64_GT_S(stack)
                }
                OpCode.I64_GT_U -> {
                    I64_GT_U(stack)
                }
                OpCode.I32_GE_S -> {
                    I32_GE_S(stack)
                }
                OpCode.I32_GE_U -> {
                    I32_GE_U(stack)
                }
                OpCode.I64_GE_U -> {
                    I64_GE_U(stack)
                }
                OpCode.I64_GE_S -> {
                    I64_GE_S(stack)
                }
                OpCode.I32_LE_S -> {
                    I32_LE_S(stack)
                }
                OpCode.I32_LE_U -> {
                    I32_LE_U(stack)
                }
                OpCode.I64_LE_S -> {
                    I64_LE_S(stack)
                }
                OpCode.I64_LE_U -> {
                    I64_LE_U(stack)
                }
                OpCode.F32_EQ -> {
                    F32_EQ(stack)
                }
                OpCode.F64_EQ -> {
                    F64_EQ(stack)
                }
                OpCode.I32_CLZ -> {
                    I32_CLZ(stack)
                }
                OpCode.I32_CTZ -> {
                    I32_CTZ(stack)
                }
                OpCode.I32_POPCNT -> {
                    I32_POPCNT(stack)
                }
                OpCode.I32_ADD -> {
                    I32_ADD(stack)
                }
                OpCode.I64_ADD -> {
                    I64_ADD(stack)
                }
                OpCode.I32_SUB -> {
                    I32_SUB(stack)
                }
                OpCode.I64_SUB -> {
                    I64_SUB(stack)
                }
                OpCode.I32_MUL -> {
                    I32_MUL(stack)
                }
                OpCode.I64_MUL -> {
                    I64_MUL(stack)
                }
                OpCode.I32_DIV_S -> {
                    I32_DIV_S(stack)
                }
                OpCode.I32_DIV_U -> {
                    I32_DIV_U(stack)
                }
                OpCode.I64_DIV_S -> {
                    I64_DIV_S(stack)
                }
                OpCode.I64_DIV_U -> {
                    I64_DIV_U(stack)
                }
                OpCode.I32_REM_S -> {
                    I32_REM_S(stack)
                }
                OpCode.I32_REM_U -> {
                    I32_REM_U(stack)
                }
                OpCode.I64_AND -> {
                    I64_AND(stack)
                }
                OpCode.I64_OR -> {
                    I64_OR(stack)
                }
                OpCode.I64_XOR -> {
                    I64_XOR(stack)
                }
                OpCode.I64_SHL -> {
                    I64_SHL(stack)
                }
                OpCode.I64_SHR_S -> {
                    I64_SHR_S(stack)
                }
                OpCode.I64_SHR_U -> {
                    I64_SHR_U(stack)
                }
                OpCode.I64_REM_S -> {
                    I64_REM_S(stack)
                }
                OpCode.I64_REM_U -> {
                    I64_REM_U(stack)
                }
                OpCode.I64_ROTL -> {
                    I64_ROTL(stack)
                }
                OpCode.I64_ROTR -> {
                    I64_ROTR(stack)
                }
                OpCode.I64_CLZ -> {
                    I64_CLZ(stack)
                }
                OpCode.I64_CTZ -> {
                    I64_CTZ(stack)
                }
                OpCode.I64_POPCNT -> {
                    I64_POPCNT(stack)
                }
                OpCode.F32_NEG -> {
                    F32_NEG(stack)
                }
                OpCode.F64_NEG -> {
                    F64_NEG(stack)
                }
                OpCode.CALL -> {
                    frame = CALL(operands) ?: frame
                }
                OpCode.CALL_REF -> {
                    frame = CALL_REF() ?: frame
                }
                OpCode.I32_AND -> {
                    I32_AND(stack)
                }
                OpCode.I32_OR -> {
                    I32_OR(stack)
                }
                OpCode.I32_XOR -> {
                    I32_XOR(stack)
                }
                OpCode.I32_SHL -> {
                    I32_SHL(stack)
                }
                OpCode.I32_SHR_S -> {
                    I32_SHR_S(stack)
                }
                OpCode.I32_SHR_U -> {
                    I32_SHR_U(stack)
                }
                OpCode.I32_ROTL -> {
                    I32_ROTL(stack)
                }
                OpCode.I32_ROTR -> {
                    I32_ROTR(stack)
                }
                OpCode.F32_ADD -> {
                    F32_ADD(stack)
                }
                OpCode.F64_ADD -> {
                    F64_ADD(stack)
                }
                OpCode.F32_SUB -> {
                    F32_SUB(stack)
                }
                OpCode.F64_SUB -> {
                    F64_SUB(stack)
                }
                OpCode.F32_MUL -> {
                    F32_MUL(stack)
                }
                OpCode.F64_MUL -> {
                    F64_MUL(stack)
                }
                OpCode.F32_DIV -> {
                    F32_DIV(stack)
                }
                OpCode.F64_DIV -> {
                    F64_DIV(stack)
                }
                OpCode.F32_MIN -> {
                    F32_MIN(stack)
                }
                OpCode.F64_MIN -> {
                    F64_MIN(stack)
                }
                OpCode.F32_MAX -> {
                    F32_MAX(stack)
                }
                OpCode.F64_MAX -> {
                    F64_MAX(stack)
                }
                OpCode.F32_SQRT -> {
                    F32_SQRT(stack)
                }
                OpCode.F64_SQRT -> {
                    F64_SQRT(stack)
                }
                OpCode.F32_FLOOR -> {
                    F32_FLOOR(stack)
                }
                OpCode.F64_FLOOR -> {
                    F64_FLOOR(stack)
                }
                OpCode.F32_CEIL -> {
                    F32_CEIL(stack)
                }
                OpCode.F64_CEIL -> {
                    F64_CEIL(stack)
                }
                OpCode.F32_TRUNC -> {
                    F32_TRUNC(stack)
                }
                OpCode.F64_TRUNC -> {
                    F64_TRUNC(stack)
                }
                OpCode.F32_NEAREST -> {
                    F32_NEAREST(stack)
                }
                OpCode.F64_NEAREST -> {
                    F64_NEAREST(stack)
                    // For the extend_* operations, note that java
                    // automatically does this when casting from
                    // smaller to larger primitives
                }
                OpCode.I32_EXTEND_8_S -> {
                    I32_EXTEND_8_S(stack)
                }
                OpCode.I32_EXTEND_16_S -> {
                    I32_EXTEND_16_S(stack)
                }
                OpCode.I64_EXTEND_8_S -> {
                    I64_EXTEND_8_S(stack)
                }
                OpCode.I64_EXTEND_16_S -> {
                    I64_EXTEND_16_S(stack)
                }
                OpCode.I64_EXTEND_32_S -> {
                    I64_EXTEND_32_S(stack)
                }
                OpCode.F64_CONVERT_I64_U -> {
                    F64_CONVERT_I64_U(stack)
                }
                OpCode.F64_CONVERT_I32_U -> {
                    F64_CONVERT_I32_U(stack)
                }
                OpCode.F64_CONVERT_I32_S -> {
                    F64_CONVERT_I32_S(stack)
                }
                OpCode.F64_PROMOTE_F32 -> {
                    F64_PROMOTE_F32(stack)
                }
                OpCode.F64_REINTERPRET_I64 -> {
                    F64_REINTERPRET_I64(stack)
                }
                OpCode.I64_TRUNC_F64_S -> {
                    I64_TRUNC_F64_S(stack)
                }
                OpCode.I32_WRAP_I64 -> {
                    I32_WRAP_I64(stack)
                }
                OpCode.I64_EXTEND_I32_S -> {
                    I64_EXTEND_I32_S(stack)
                }
                OpCode.I64_EXTEND_I32_U -> {
                    I64_EXTEND_I32_U(stack)
                }
                OpCode.I32_REINTERPRET_F32 -> {
                    I32_REINTERPRET_F32(stack)
                }
                OpCode.I64_REINTERPRET_F64 -> {
                    I64_REINTERPRET_F64(stack)
                }
                OpCode.F32_REINTERPRET_I32 -> {
                    F32_REINTERPRET_I32(stack)
                }
                OpCode.F32_COPYSIGN -> {
                    F32_COPYSIGN(stack)
                }
                OpCode.F32_ABS -> {
                    F32_ABS(stack)
                }
                OpCode.F64_COPYSIGN -> {
                    F64_COPYSIGN(stack)
                }
                OpCode.F64_ABS -> {
                    F64_ABS(stack)
                }
                OpCode.F32_NE -> {
                    F32_NE(stack)
                }
                OpCode.F64_NE -> {
                    F64_NE(stack)
                }
                OpCode.F32_LT -> {
                    F32_LT(stack)
                }
                OpCode.F64_LT -> {
                    F64_LT(stack)
                }
                OpCode.F32_LE -> {
                    F32_LE(stack)
                }
                OpCode.F64_LE -> {
                    F64_LE(stack)
                }
                OpCode.F32_GE -> {
                    F32_GE(stack)
                }
                OpCode.F64_GE -> {
                    F64_GE(stack)
                }
                OpCode.F32_GT -> {
                    F32_GT(stack)
                }
                OpCode.F64_GT -> {
                    F64_GT(stack)
                }
                OpCode.F32_DEMOTE_F64 -> {
                    F32_DEMOTE_F64(stack)
                }
                OpCode.F32_CONVERT_I32_S -> {
                    F32_CONVERT_I32_S(stack)
                }
                OpCode.I32_TRUNC_F32_S -> {
                    I32_TRUNC_F32_S(stack)
                }
                OpCode.I32_TRUNC_SAT_F32_S -> {
                    I32_TRUNC_SAT_F32_S(stack)
                }
                OpCode.I32_TRUNC_SAT_F32_U -> {
                    I32_TRUNC_SAT_F32_U(stack)
                }
                OpCode.I32_TRUNC_SAT_F64_S -> {
                    I32_TRUNC_SAT_F64_S(stack)
                }
                OpCode.I32_TRUNC_SAT_F64_U -> {
                    I32_TRUNC_SAT_F64_U(stack)
                }
                OpCode.F32_CONVERT_I32_U -> {
                    F32_CONVERT_I32_U(stack)
                }
                OpCode.I32_TRUNC_F32_U -> {
                    I32_TRUNC_F32_U(stack)
                }
                OpCode.F32_CONVERT_I64_S -> {
                    F32_CONVERT_I64_S(stack)
                }
                OpCode.F32_CONVERT_I64_U -> {
                    F32_CONVERT_I64_U(stack)
                }
                OpCode.F64_CONVERT_I64_S -> {
                    F64_CONVERT_I64_S(stack)
                }
                OpCode.I64_TRUNC_F32_U -> {
                    I64_TRUNC_F32_U(stack)
                }
                OpCode.I64_TRUNC_F64_U -> {
                    I64_TRUNC_F64_U(stack)
                }
                OpCode.I64_TRUNC_SAT_F32_S -> {
                    I64_TRUNC_SAT_F32_S(stack)
                }
                OpCode.I64_TRUNC_SAT_F32_U -> {
                    I64_TRUNC_SAT_F32_U(stack)
                }
                OpCode.I64_TRUNC_SAT_F64_S -> {
                    I64_TRUNC_SAT_F64_S(stack)
                }
                OpCode.I64_TRUNC_SAT_F64_U -> {
                    I64_TRUNC_SAT_F64_U(stack)
                }
                OpCode.I32_TRUNC_F64_S -> {
                    I32_TRUNC_F64_S(stack)
                }
                OpCode.I32_TRUNC_F64_U -> {
                    I32_TRUNC_F64_U(stack)
                }
                OpCode.I64_TRUNC_F32_S -> {
                    I64_TRUNC_F32_S(stack)
                }
                OpCode.MEMORY_INIT -> {
                    MEMORY_INIT(stack, instance, operands)
                }
                OpCode.TABLE_INIT -> {
                    TABLE_INIT(stack, instance, operands)
                }
                OpCode.DATA_DROP -> {
                    DATA_DROP(instance, operands)
                }
                OpCode.MEMORY_COPY -> {
                    MEMORY_COPY(stack, instance, operands)
                }
                OpCode.TABLE_COPY -> {
                    TABLE_COPY(stack, instance, operands)
                }
                OpCode.TABLE_FILL -> {
                    TABLE_FILL(stack, instance, operands)
                }
                OpCode.TABLE_SIZE -> {
                    TABLE_SIZE(stack, instance, operands)
                }
                OpCode.TABLE_GROW -> {
                    TABLE_GROW(stack, instance, operands)
                }
                OpCode.REF_FUNC -> {
                    stack.push(operands.get(0))
                }
                OpCode.REF_NULL -> {
                    REF_NULL(stack)
                }
                OpCode.REF_IS_NULL -> {
                    REF_IS_NULL(stack)
                }
                OpCode.REF_AS_NON_NULL -> {
                    REF_AS_NON_NULL(stack)
                }
                OpCode.ELEM_DROP -> {
                    ELEM_DROP(instance, operands)
                    // Threads proposal:
                }
                OpCode.I32_ATOMIC_LOAD -> {
                    I32_ATOMIC_LOAD(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_LOAD -> {
                    I64_ATOMIC_LOAD(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_LOAD8_U -> {
                    I64_ATOMIC_LOAD8_U(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_LOAD8_U -> {
                    I32_ATOMIC_LOAD8_U(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_LOAD16_U -> {
                    I32_ATOMIC_LOAD16_U(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_LOAD16_U -> {
                    I64_ATOMIC_LOAD16_U(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_LOAD32_U -> {
                    I64_ATOMIC_LOAD32_U(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_STORE -> {
                    I32_ATOMIC_STORE(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_STORE -> {
                    I64_ATOMIC_STORE(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_STORE8,
                OpCode.I64_ATOMIC_STORE8 -> {
                    I64_ATOMIC_STORE8(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_STORE16,
                OpCode.I64_ATOMIC_STORE16 -> {
                    I64_ATOMIC_STORE16(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_STORE32 -> {
                    I64_ATOMIC_STORE32(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_RMW_ADD -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.ADD)
                }
                OpCode.I32_ATOMIC_RMW_SUB -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.SUB)
                }
                OpCode.I32_ATOMIC_RMW_AND -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.AND)
                }
                OpCode.I32_ATOMIC_RMW_OR -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.OR)
                }
                OpCode.I32_ATOMIC_RMW_XOR -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.XOR)
                }
                OpCode.I32_ATOMIC_RMW_XCHG -> {
                    I32_ATOMIC_RMW(stack, instance, operands, AtomicOp.XCHG)
                }
                OpCode.I32_ATOMIC_RMW_CMPXCHG -> {
                    I32_ATOMIC_RMW_CMPXCHG(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_RMW_ADD -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.ADD)
                }
                OpCode.I64_ATOMIC_RMW_SUB -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.SUB)
                }
                OpCode.I64_ATOMIC_RMW_AND -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.AND)
                }
                OpCode.I64_ATOMIC_RMW_OR -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.OR)
                }
                OpCode.I64_ATOMIC_RMW_XOR -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.XOR)
                }
                OpCode.I64_ATOMIC_RMW_XCHG -> {
                    I64_ATOMIC_RMW(stack, instance, operands, AtomicOp.XCHG)
                }
                OpCode.I64_ATOMIC_RMW_CMPXCHG -> {
                    I64_ATOMIC_RMW_CMPXCHG(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_RMW8_ADD_U,
                OpCode.I64_ATOMIC_RMW8_ADD_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.ADD)
                }
                OpCode.I32_ATOMIC_RMW8_SUB_U,
                OpCode.I64_ATOMIC_RMW8_SUB_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.SUB)
                }
                OpCode.I32_ATOMIC_RMW8_AND_U,
                OpCode.I64_ATOMIC_RMW8_AND_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.AND)
                }
                OpCode.I32_ATOMIC_RMW8_OR_U,
                OpCode.I64_ATOMIC_RMW8_OR_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.OR)
                }
                OpCode.I32_ATOMIC_RMW8_XOR_U,
                OpCode.I64_ATOMIC_RMW8_XOR_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.XOR)
                }
                OpCode.I32_ATOMIC_RMW8_XCHG_U,
                OpCode.I64_ATOMIC_RMW8_XCHG_U -> {
                    I64_ATOMIC_RMW8_U(stack, instance, operands, AtomicOp.XCHG)
                }
                OpCode.I32_ATOMIC_RMW8_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW8_CMPXCHG_U -> {
                    I64_ATOMIC_RMW8_CMPXCHG_U(stack, instance, operands)
                }
                OpCode.I32_ATOMIC_RMW16_ADD_U,
                OpCode.I64_ATOMIC_RMW16_ADD_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.ADD)
                }
                OpCode.I32_ATOMIC_RMW16_SUB_U,
                OpCode.I64_ATOMIC_RMW16_SUB_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.SUB)
                }
                OpCode.I32_ATOMIC_RMW16_AND_U,
                OpCode.I64_ATOMIC_RMW16_AND_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.AND)
                }
                OpCode.I32_ATOMIC_RMW16_OR_U,
                OpCode.I64_ATOMIC_RMW16_OR_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.OR)
                }
                OpCode.I32_ATOMIC_RMW16_XOR_U,
                OpCode.I64_ATOMIC_RMW16_XOR_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.XOR)
                }
                OpCode.I32_ATOMIC_RMW16_XCHG_U,
                OpCode.I64_ATOMIC_RMW16_XCHG_U -> {
                    I64_ATOMIC_RMW16_U(stack, instance, operands, AtomicOp.XCHG)
                }
                OpCode.I32_ATOMIC_RMW16_CMPXCHG_U,
                OpCode.I64_ATOMIC_RMW16_CMPXCHG_U -> {
                    I64_ATOMIC_RMW16_CMPXCHG_U(stack, instance, operands)
                }
                OpCode.I64_ATOMIC_RMW32_ADD_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.ADD)
                }
                OpCode.I64_ATOMIC_RMW32_SUB_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.SUB)
                }
                OpCode.I64_ATOMIC_RMW32_AND_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.AND)
                }
                OpCode.I64_ATOMIC_RMW32_OR_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.OR)
                }
                OpCode.I64_ATOMIC_RMW32_XOR_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.XOR)
                }
                OpCode.I64_ATOMIC_RMW32_XCHG_U -> {
                    I64_ATOMIC_RMW32_U(stack, instance, operands, AtomicOp.XCHG)
                }
                OpCode.I64_ATOMIC_RMW32_CMPXCHG_U -> {
                    I64_ATOMIC_RMW32_CMPXCHG_U(stack, instance, operands)
                }
                OpCode.MEM_ATOMIC_WAIT32 -> {
                    MEM_ATOMIC_WAIT32(stack, instance, operands)
                }
                OpCode.MEM_ATOMIC_WAIT64 -> {
                    MEM_ATOMIC_WAIT64(stack, instance, operands)
                }
                OpCode.MEM_ATOMIC_NOTIFY -> {
                    MEM_ATOMIC_NOTIFY(stack, instance, operands)
                }
                OpCode.ATOMIC_FENCE -> {
                    ATOMIC_FENCE(instance)
                    // GC opcodes
                }
                OpCode.REF_EQ -> {
                    REF_EQ(stack)
                }
                OpCode.REF_I31 -> {
                    REF_I31(stack)
                }
                OpCode.I31_GET_S -> {
                    I31_GET_S(stack)
                }
                OpCode.I31_GET_U -> {
                    I31_GET_U(stack)
                }
                OpCode.STRUCT_NEW -> {
                    STRUCT_NEW(stack, instance, operands)
                }
                OpCode.STRUCT_NEW_DEFAULT -> {
                    STRUCT_NEW_DEFAULT(stack, instance, operands)
                }
                OpCode.STRUCT_GET,
                OpCode.STRUCT_GET_S,
                OpCode.STRUCT_GET_U -> {
                    STRUCT_GET(stack, instance, frame)
                }
                OpCode.STRUCT_SET -> {
                    STRUCT_SET(stack, instance, operands)
                }
                OpCode.ARRAY_NEW -> {
                    ARRAY_NEW(stack, instance, operands)
                }
                OpCode.ARRAY_NEW_DEFAULT -> {
                    ARRAY_NEW_DEFAULT(stack, instance, operands)
                }
                OpCode.ARRAY_NEW_FIXED -> {
                    ARRAY_NEW_FIXED(stack, instance, operands)
                }
                OpCode.ARRAY_NEW_DATA -> {
                    ARRAY_NEW_DATA(stack, instance, operands)
                }
                OpCode.ARRAY_NEW_ELEM -> {
                    ARRAY_NEW_ELEM(stack, instance, operands)
                }
                OpCode.ARRAY_GET,
                OpCode.ARRAY_GET_S,
                OpCode.ARRAY_GET_U -> {
                    ARRAY_GET(stack, instance, operands, opcode)
                }
                OpCode.ARRAY_SET -> {
                    ARRAY_SET(stack, instance, operands)
                }
                OpCode.ARRAY_LEN -> {
                    ARRAY_LEN(stack, instance)
                }
                OpCode.ARRAY_FILL -> {
                    ARRAY_FILL(stack, instance, operands)
                }
                OpCode.ARRAY_COPY -> {
                    ARRAY_COPY(stack, instance)
                }
                OpCode.ARRAY_INIT_DATA -> {
                    ARRAY_INIT_DATA(stack, instance, operands)
                }
                OpCode.ARRAY_INIT_ELEM -> {
                    ARRAY_INIT_ELEM(stack, instance, operands)
                }
                OpCode.REF_TEST,
                OpCode.REF_TEST_NULL -> {
                    REF_TEST(stack, instance, operands, opcode)
                }
                OpCode.CAST_TEST,
                OpCode.CAST_TEST_NULL -> {
                    CAST_TEST(stack, instance, operands, opcode)
                }
                OpCode.BR_ON_CAST -> {
                    BR_ON_CAST(stack, instance, frame, instruction, operands)
                }
                OpCode.BR_ON_CAST_FAIL -> {
                    BR_ON_CAST_FAIL(stack, instance, frame, instruction, operands)
                }
                OpCode.ANY_CONVERT_EXTERN,
                OpCode.EXTERN_CONVERT_ANY -> {
                    // Identity operation at runtime: the value representation is the same
                    // for externref and anyref. No wrapping needed.
                }
                else -> {
                    evalDefault(stack, instance, callStack, instruction, operands)
                }
            }
        }
    }

    private fun completeFrame(
        frame: StackFrame,
        callStack: ArrayDeque<StackFrame>,
    ): StackFrame? {
        if (callStack.isNotEmpty() && callStack.last() == frame) {
            callStack.removeLast()
        }
        return if (callStack.isEmpty()) null else callStack.last()
    }

    private fun I32_GE_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_GE_U(a, b))
    }

    private fun I64_GT_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_GT_U(a, b))
    }

    private fun I32_GE_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_GE_S(a, b))
    }

    private fun I64_GE_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_GE_U(a, b))
    }

    private fun I64_GE_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_GE_S(a, b))
    }

    private fun I32_LE_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_LE_S(a, b))
    }

    private fun I32_LE_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_LE_U(a, b))
    }

    private fun I64_LE_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_LE_S(a, b))
    }

    private fun I64_LE_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_LE_U(a, b))
    }

    private fun F32_EQ(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_EQ(a, b))
    }

    private fun F64_EQ(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_EQ(a, b))
    }

    private fun I32_CLZ(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(OpcodeOps.I32_CLZ(tos))
    }

    private fun I32_CTZ(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(OpcodeOps.I32_CTZ(tos))
    }

    private fun I32_POPCNT(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(OpcodeOps.I32_POPCNT(tos))
    }

    private fun I32_ADD(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(a + b)
    }

    private fun I64_ADD(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a + b)
    }

    private fun I32_SUB(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(b - a)
    }

    private fun I64_SUB(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(b - a)
    }

    private fun I32_MUL(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a * b)
    }

    private fun I64_MUL(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a * b)
    }

    private fun I32_DIV_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_DIV_S(a, b))
    }

    private fun I32_DIV_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_DIV_U(a, b))
    }

    private fun I64_EXTEND_8_S(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_EXTEND_8_S(tos))
    }

    private fun I64_EXTEND_16_S(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_EXTEND_16_S(tos))
    }

    private fun I64_EXTEND_32_S(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_EXTEND_32_S(tos))
    }

    private fun F64_CONVERT_I64_U(stack: MStack) {
        var tos = stack.pop()
        stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I64_U(tos)))
    }

    private fun F64_CONVERT_I32_U(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I32_U(tos)))
    }

    private fun F64_CONVERT_I32_S(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I32_S(tos)))
    }

    private fun I32_EXTEND_8_S(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(OpcodeOps.I32_EXTEND_8_S(tos))
    }

    private fun F64_NEAREST(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_NEAREST(value)))
    }

    private fun F32_NEAREST(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_NEAREST(value)))
    }

    private fun F64_TRUNC(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_TRUNC(value)))
    }

    private fun F64_CEIL(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_CEIL(value)))
    }

    private fun F32_CEIL(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_CEIL(value)))
    }

    private fun F64_FLOOR(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_FLOOR(value)))
    }

    private fun F32_FLOOR(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_FLOOR(value)))
    }

    private fun F64_SQRT(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_SQRT(value)))
    }

    private fun F32_SQRT(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_SQRT(value)))
    }

    private fun F64_MAX(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_MAX(a, b)))
    }

    private fun F32_MAX(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_MAX(a, b)))
    }

    private fun F64_MIN(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_MIN(a, b)))
    }

    private fun F32_MIN(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_MIN(a, b)))
    }

    private fun F64_DIV(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(b / a))
    }

    private fun F32_DIV(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(b / a))
    }

    private fun F64_MUL(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(b * a))
    }

    private fun F32_MUL(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(b * a))
    }

    private fun F64_SUB(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(b - a))
    }

    private fun F32_SUB(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(b - a))
    }

    private fun F64_ADD(stack: MStack) {
        var a = Value.longToDouble(stack.pop())
        var b = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(a + b))
    }

    private fun F32_ADD(stack: MStack) {
        var a = Value.longToFloat(stack.pop())
        var b = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(a + b))
    }

    private fun I32_ROTR(stack: MStack) {
        var c = stack.pop().toInt()
        var v = stack.pop().toInt()
        stack.push(OpcodeOps.I32_ROTR(v, c))
    }

    private fun I32_ROTL(stack: MStack) {
        var c = stack.pop().toInt()
        var v = stack.pop().toInt()
        stack.push(OpcodeOps.I32_ROTL(v, c))
    }

    private fun I32_SHR_U(stack: MStack) {
        var c = stack.pop().toInt()
        var v = stack.pop().toInt()
        stack.push(v ushr c.toInt())
    }

    private fun I32_SHR_S(stack: MStack) {
        var c = stack.pop().toInt()
        var v = stack.pop().toInt()
        stack.push(v shr c.toInt())
    }

    private fun I32_SHL(stack: MStack) {
        var c = stack.pop().toInt()
        var v = stack.pop().toInt()
        stack.push(v shl c.toInt())
    }

    private fun I32_XOR(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(a xor b)
    }

    private fun I32_OR(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(a or b)
    }

    private fun I32_AND(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(a and b)
    }

    private fun I64_POPCNT(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_POPCNT(tos))
    }

    private fun I64_CTZ(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_CTZ(tos))
    }

    private fun I64_CLZ(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_CLZ(tos))
    }

    private fun I64_ROTR(stack: MStack) {
        var c = stack.pop()
        var v = stack.pop()
        stack.push(OpcodeOps.I64_ROTR(v, c))
    }

    private fun I64_ROTL(stack: MStack) {
        var c = stack.pop()
        var v = stack.pop()
        stack.push(OpcodeOps.I64_ROTL(v, c))
    }

    private fun I64_REM_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_REM_U(a, b))
    }

    private fun I64_REM_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_REM_S(a, b))
    }

    private fun I64_SHR_U(stack: MStack) {
        var c = stack.pop()
        var v = stack.pop()
        stack.push(v ushr c.toInt())
    }

    private fun I64_SHR_S(stack: MStack) {
        var c = stack.pop()
        var v = stack.pop()
        stack.push(v shr c.toInt())
    }

    private fun I64_SHL(stack: MStack) {
        var c = stack.pop()
        var v = stack.pop()
        stack.push(v shl c.toInt())
    }

    private fun I64_XOR(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a xor b)
    }

    private fun I64_OR(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a or b)
    }

    private fun I64_AND(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a and b)
    }

    private fun I32_REM_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_REM_U(a, b))
    }

    private fun I32_REM_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_REM_S(a, b))
    }

    private fun I64_DIV_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_DIV_U(a, b))
    }

    private fun I64_DIV_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_DIV_S(a, b))
    }

    private fun I64_GT_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_GT_S(a, b))
    }

    private fun I32_GT_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_GT_U(a, b))
    }

    private fun I32_GT_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_GT_S(a, b))
    }

    private fun I64_LT_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_LT_U(a, b))
    }

    private fun I64_LT_S(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_LT_S(a, b))
    }

    private fun I32_LT_U(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_LT_U(a, b))
    }

    private fun I32_LT_S(stack: MStack) {
        var b = stack.pop().toInt()
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_LT_S(a, b))
    }

    private fun I64_EQZ(stack: MStack) {
        var a = stack.pop()
        stack.push(OpcodeOps.I64_EQZ(a))
    }

    private fun I32_EQZ(stack: MStack) {
        var a = stack.pop().toInt()
        stack.push(OpcodeOps.I32_EQZ(a))
    }

    private fun I64_NE(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(OpcodeOps.I64_NE(a, b))
    }

    private fun I32_NE(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(OpcodeOps.I32_NE(a, b))
    }

    private fun I64_EQ(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(OpcodeOps.I64_EQ(a, b))
    }

    private fun I32_EQ(stack: MStack) {
        var a = stack.pop().toInt()
        var b = stack.pop().toInt()
        stack.push(OpcodeOps.I32_EQ(a, b))
    }

    private fun MEMORY_SIZE(stack: MStack, instance: Instance, operands: Operands) {
        var sz = instance.memory(operands.get(0).toInt()).pages()
        stack.push(sz)
    }

    private fun I64_STORE32(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop()
        var ptr = (operands.get(1) + stack.pop().toInt()).toInt()
        instance.memory(operands.get(2).toInt()).writeI32(ptr, value.toInt())
    }

    private fun I64_STORE8(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toByte()
        var ptr = (operands.get(1) + stack.pop().toInt()).toInt()
        instance.memory(operands.get(2).toInt()).writeByte(ptr, value)
    }

    private fun F64_PROMOTE_F32(stack: MStack) {
        var tos = stack.pop()
        stack.push(Float.fromBits(tos.toInt()).toDouble().toRawBits())
    }

    private fun F64_REINTERPRET_I64(stack: MStack) {
        var tos = stack.pop()
        stack.push(Value.doubleToLong(OpcodeOps.F64_REINTERPRET_I64(tos)))
    }

    private fun I32_WRAP_I64(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(tos)
    }

    private fun I64_EXTEND_I32_S(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(tos)
    }

    private fun I64_EXTEND_I32_U(stack: MStack) {
        var tos = stack.pop()
        stack.push(OpcodeOps.I64_EXTEND_I32_U(tos.toInt()))
    }

    private fun I32_REINTERPRET_F32(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I32_REINTERPRET_F32(tos))
    }

    private fun I64_REINTERPRET_F64(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I64_REINTERPRET_F64(tos))
    }

    private fun F32_REINTERPRET_I32(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(Value.floatToLong(OpcodeOps.F32_REINTERPRET_I32(tos)))
    }

    private fun F32_DEMOTE_F64(stack: MStack) {
        var value = Value.longToDouble(stack.pop())

        stack.push(Value.floatToLong(value.toFloat()))
    }

    private fun F32_CONVERT_I32_S(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(Value.floatToLong(OpcodeOps.F32_CONVERT_I32_S(tos)))
    }

    private fun I32_EXTEND_16_S(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(OpcodeOps.I32_EXTEND_16_S(tos))
    }

    private fun I64_TRUNC_F64_S(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_F64_S(tos))
    }

    private fun F32_COPYSIGN(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_COPYSIGN(a, b)))
    }

    private fun F32_ABS(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_ABS(value)))
    }

    private fun F64_ABS(stack: MStack) {
        var value = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_ABS(value)))
    }

    private fun F32_NE(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_NE(a, b))
    }

    private fun F64_NE(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_NE(a, b))
    }

    private fun F32_LT(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_LT(a, b))
    }

    private fun F64_LT(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_LT(a, b))
    }

    private fun F32_LE(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_LE(a, b))
    }

    private fun F64_LE(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_LE(a, b))
    }

    private fun F32_GE(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_GE(a, b))
    }

    private fun F64_GE(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_GE(a, b))
    }

    private fun F32_GT(stack: MStack) {
        var b = Value.longToFloat(stack.pop())
        var a = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.F32_GT(a, b))
    }

    private fun F64_GT(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.F64_GT(a, b))
    }

    private fun F32_CONVERT_I32_U(stack: MStack) {
        var tos = stack.pop().toInt()
        stack.push(Value.floatToLong(OpcodeOps.F32_CONVERT_I32_U(tos)))
    }

    private fun F32_CONVERT_I64_S(stack: MStack) {
        var tos = stack.pop()
        stack.push(Value.floatToLong(OpcodeOps.F32_CONVERT_I64_S(tos)))
    }

    private fun REF_NULL(stack: MStack) {
        stack.push(Value.REF_NULL_VALUE)
    }

    private fun ELEM_DROP(instance: Instance, operands: Operands) {
        var x = operands.get(0).toInt()
        instance.setElement(x, null)
    }

    private fun REF_IS_NULL(stack: MStack) {
        var value = stack.pop()
        stack.push(if (value == Value.REF_NULL_VALUE.toLong()) Value.TRUE else Value.FALSE)
    }

    private fun REF_AS_NON_NULL(stack: MStack) {
        var value = stack.pop()
        if (value == Value.REF_NULL_VALUE.toLong()) {
            throw TrapException("Trapped on ref_as_non_null on null reference")
        }
        stack.push(value)
    }

    private fun DATA_DROP(instance: Instance, operands: Operands) {
        var segment = operands.get(0).toInt()
        instance.dropDataSegment(segment)
    }

    private fun F64_CONVERT_I64_S(stack: MStack) {
        var tos = stack.pop()
        stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I64_S(tos)))
    }

    private fun TABLE_GROW(stack: MStack, instance: Instance, operands: Operands) {
        var tableidx = operands.get(0).toInt()
        var table = instance.table(tableidx)

        var size = stack.pop().toInt()
        var value = OpcodeOps.boxForTable(stack.pop(), instance)

        var res = table.grow(size, value, instance)
        stack.push(res)
    }

    private fun TABLE_SIZE(stack: MStack, instance: Instance, operands: Operands) {
        var tableidx = operands.get(0).toInt()
        var table = instance.table(tableidx)

        stack.push(table.size())
    }

    private fun TABLE_FILL(stack: MStack, instance: Instance, operands: Operands) {
        var tableidx = operands.get(0).toInt()

        var size = stack.pop().toInt()
        var value = OpcodeOps.boxForTable(stack.pop(), instance)
        var offset = stack.pop().toInt()

        OpcodeOps.TABLE_FILL(instance, tableidx, size, value, offset)
    }

    private fun TABLE_COPY(stack: MStack, instance: Instance, operands: Operands) {
        var tableidxSrc = operands.get(1).toInt()
        var tableidxDst = operands.get(0).toInt()

        var size = stack.pop().toInt()
        var s = stack.pop().toInt()
        var d = stack.pop().toInt()

        OpcodeOps.TABLE_COPY(instance, tableidxSrc, tableidxDst, size, s, d)
    }

    private fun MEMORY_COPY(stack: MStack, instance: Instance, operands: Operands) {
        var size = stack.pop().toInt()
        var offset = stack.pop().toInt()
        var destination = stack.pop().toInt()
        var dstMem = instance.memory(operands.get(0).toInt())
        var srcMem = instance.memory(operands.get(1).toInt())
        if (dstMem == srcMem) {
            dstMem.copy(destination, offset, size)
        } else {
            dstMem.write(destination, srcMem.readBytes(offset, size))
        }
    }

    private fun TABLE_INIT(stack: MStack, instance: Instance, operands: Operands) {
        var tableidx = operands.get(1).toInt()
        var elementidx = operands.get(0).toInt()

        var size = stack.pop().toInt()
        var elemidx = stack.pop().toInt()
        var offset = stack.pop().toInt()

        OpcodeOps.TABLE_INIT(instance, tableidx, elementidx, size, elemidx, offset)
    }

    private fun MEMORY_INIT(stack: MStack, instance: Instance, operands: Operands) {
        var segmentId = operands.get(0).toInt()
        var memidx = operands.get(1).toInt()
        var size = stack.pop().toInt()
        var offset = stack.pop().toInt()
        var destination = stack.pop().toInt()
        instance.memory(memidx).initPassiveSegment(segmentId, destination, offset, size)
    }

    private fun I64_TRUNC_F32_S(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_F32_S(tos))
    }

    private fun I32_TRUNC_F64_U(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_F64_U(tos))
    }

    private fun I32_TRUNC_F64_S(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_F64_S(tos))
    }

    private fun I64_TRUNC_SAT_F64_U(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_SAT_F64_U(tos))
    }

    private fun I64_TRUNC_SAT_F64_S(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_SAT_F64_S(tos))
    }

    private fun I64_TRUNC_SAT_F32_U(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_SAT_F32_U(tos))
    }

    private fun I64_TRUNC_SAT_F32_S(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_SAT_F32_S(tos))
    }

    private fun I64_TRUNC_F64_U(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_F64_U(tos))
    }

    private fun I64_TRUNC_F32_U(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I64_TRUNC_F32_U(tos))
    }

    private fun F32_CONVERT_I64_U(stack: MStack) {
        var tos = stack.pop()
        stack.push(Value.floatToLong(OpcodeOps.F32_CONVERT_I64_U(tos)))
    }

    private fun I32_TRUNC_F32_U(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_F32_U(tos))
    }

    private fun I32_TRUNC_SAT_F64_U(stack: MStack) {
        var tos = Double.fromBits(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_SAT_F64_U(tos))
    }

    private fun I32_TRUNC_SAT_F64_S(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_SAT_F64_S(tos))
    }

    private fun I32_TRUNC_SAT_F32_U(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_SAT_F32_U(tos))
    }

    private fun I32_TRUNC_SAT_F32_S(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_SAT_F32_S(tos))
    }

    private fun I32_TRUNC_F32_S(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(OpcodeOps.I32_TRUNC_F32_S(tos))
    }

    private fun F64_COPYSIGN(stack: MStack) {
        var b = Value.longToDouble(stack.pop())
        var a = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(OpcodeOps.F64_COPYSIGN(a, b)))
    }

    private fun F32_TRUNC(stack: MStack) {
        var value = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(OpcodeOps.F32_TRUNC(value)))
    }

    protected open fun CALL(operands: Operands): StackFrame? {
        var funcId = operands.get(0).toInt()
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)
        // given a list of param types, let's pop those params off the stack
        // and pass as args to the function call
        var args = extractArgsForParams(stack, type)
        return pushOrCallFunction(instance, funcId, args, type)
    }

    private fun CALL_REF(): StackFrame? {
        var funcId = stack.pop().toInt()
        if (funcId == Value.REF_NULL_VALUE) {
            throw TrapException("Trapped on call_ref on null function reference")
        }
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)
        // given a list of param types, let's pop those params off the stack
        // and pass as args to the function call
        var args = extractArgsForParams(stack, type)
        return pushOrCallFunction(instance, funcId, args, type)
    }

    private fun F64_NEG(stack: MStack) {
        var tos = Value.longToDouble(stack.pop())
        stack.push(Value.doubleToLong(-tos))
    }

    private fun F32_NEG(stack: MStack) {
        var tos = Value.longToFloat(stack.pop())
        stack.push(Value.floatToLong(-tos))
    }

    private fun MEMORY_FILL(stack: MStack, instance: Instance, operands: Operands) {
        var size = stack.pop().toInt()
        var value = stack.pop().toByte()
        var offset = stack.pop().toInt()
        var end = (size + offset)
        instance.memory(operands.get(0).toInt()).fill(value, offset, end)
    }

    private fun MEMORY_GROW(stack: MStack, instance: Instance, operands: Operands) {
        var size = stack.pop().toInt()
        var nPages = instance.memory(operands.get(0).toInt()).grow(size)
        stack.push(nPages)
    }

    protected fun readMemPtr(stack: MStack, operands: Operands): Int {
        var address = stack.pop().toInt()
        if (operands.get(1) < 0 || operands.get(1) >= Int.MAX_VALUE || address < 0) {
            throw WasmRuntimeException("out of bounds memory access")
        }

        return (operands.get(1) + address).toInt()
    }

    private fun F64_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = Value.longToDouble(stack.pop())
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).writeF64(ptr, value)
    }

    private fun F32_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = Value.longToFloat(stack.pop())
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).writeF32(ptr, value)
    }

    private fun I64_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop()
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).writeLong(ptr, value)
    }

    private fun I64_STORE16(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toShort()
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).writeShort(ptr, value)
    }

    private fun I32_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).writeI32(ptr, value)
    }

    private fun I64_LOAD32_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        // Unsigned i32 loads are widened to Long to preserve values above Int.MAX_VALUE.
        var value = instance.memory(operands.get(2).toInt()).readU32(ptr)
        stack.push(value)
    }

    private fun I64_LOAD32_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI32(ptr)
        stack.push(value)
    }

    private fun I64_LOAD16_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readU16(ptr)
        stack.push(value)
    }

    private fun I32_LOAD16_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readU16(ptr)
        stack.push(value)
    }

    private fun I64_LOAD16_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI16(ptr)
        stack.push(value)
    }

    private fun I32_LOAD16_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI16(ptr)
        stack.push(value)
    }

    private fun I64_LOAD8_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readU8(ptr)
        stack.push(value)
    }

    private fun I32_LOAD8_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readU8(ptr)
        stack.push(value)
    }

    private fun I64_LOAD8_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI8(ptr)
        stack.push(value)
    }

    private fun I32_LOAD8_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI8(ptr)
        stack.push(value)
    }

    private fun F64_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readF64(ptr)
        stack.push(value)
    }

    private fun F32_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readF32(ptr)
        stack.push(value)
    }

    private fun I64_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI64(ptr)
        stack.push(value)
    }

    private fun I32_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readI32(ptr)
        stack.push(value)
    }

    private fun TABLE_SET(stack: MStack, instance: Instance, operands: Operands) {
        var idx = operands.get(0).toInt()
        var table = instance.table(idx)

        var value = OpcodeOps.boxForTable(stack.pop(), instance)
        var i = stack.pop().toInt()
        table.setRef(i, value, instance)
    }

    private fun TABLE_GET(stack: MStack, instance: Instance, operands: Operands) {
        var idx = operands.get(0).toInt()
        var table = instance.table(idx)
        var i = stack.pop().toInt()
        var ref = OpcodeOps.TABLE_GET(instance, idx, i)
        stack.push(OpcodeOps.unboxFromTable(ref, instance, table.elementType()))
    }

    private fun GLOBAL_SET(stack: MStack, instance: Instance, operands: Operands) {
        var id = operands.get(0).toInt()
        if (instance.global(id).type != ValType.V128) {
            var value = stack.pop()
            instance.global(id).value = value
        } else {
            var high = stack.pop()
            var low = stack.pop()
            instance.global(id).valueLow = low
            instance.global(id).valueHigh = high
        }
    }

    private fun GLOBAL_GET(stack: MStack, instance: Instance, operands: Operands) {
        var idx = operands.get(0).toInt()

        stack.push(instance.global(idx).valueLow)
        if (instance.global(idx).type == ValType.V128) {
            stack.push(instance.global(idx).valueHigh)
        }
    }

    private fun DROP(stack: MStack, operands: Operands) {
        if (operands.get(0) == ValType.ID.V128.toLong()) {
            stack.pop()
        }
        stack.pop()
    }

    private fun SELECT(stack: MStack, operands: Operands) {
        var pred = stack.pop().toInt()
        if (operands.get(0) == ValType.ID.V128.toLong()) {
            var b1 = stack.pop()
            var b2 = stack.pop()
            var a1 = stack.pop()
            var a2 = stack.pop()
            if (pred == 0) {
                stack.push(b2)
                stack.push(b1)
            } else {
                stack.push(a2)
                stack.push(a1)
            }
        } else {
            var b = stack.pop()
            var a = stack.pop()
            if (pred == 0) {
                stack.push(b)
            } else {
                stack.push(a)
            }
        }
    }

    private fun SELECT_T(stack: MStack, operands: Operands) {
        var pred = stack.pop().toInt()
        var typeId = operands.get(0)

        if (typeId == ValType.V128.id()) {
            var b1 = stack.pop()
            var b2 = stack.pop()
            var a1 = stack.pop()
            var a2 = stack.pop()
            if (pred == 0) {
                stack.push(b2)
                stack.push(b1)
            } else {
                stack.push(a2)
                stack.push(a1)
            }
        } else {
            var b = stack.pop()
            var a = stack.pop()
            if (pred == 0) {
                stack.push(b)
            } else {
                stack.push(a)
            }
        }
    }

    private fun LOCAL_GET(stack: MStack, currentStackFrame: StackFrame) {
        val i = currentStackFrame.currentLocalSlot()
        if (currentStackFrame.currentLocalIsV128()) {
            stack.push(currentStackFrame.local(i))
            stack.push(currentStackFrame.local(i + 1))
        } else {
            stack.push(currentStackFrame.local(i))
        }
    }

    private fun LOCAL_SET(stack: MStack, currentStackFrame: StackFrame) {
        val i = currentStackFrame.currentLocalSlot()
        if (currentStackFrame.currentLocalIsV128()) {
            currentStackFrame.setLocal(i, stack.pop())
            currentStackFrame.setLocal(i + 1, stack.pop())
        } else {
            currentStackFrame.setLocal(i, stack.pop())
        }
    }

    private fun LOCAL_TEE(stack: MStack, currentStackFrame: StackFrame) {
        // here we peek instead of pop, leaving it on the stack
        val i = currentStackFrame.currentLocalSlot()
        if (currentStackFrame.currentLocalIsV128()) {
            val tmp = stack.pop()
            currentStackFrame.setLocal(i, tmp)
            currentStackFrame.setLocal(i + 1, stack.peek())
            stack.push(tmp)
        } else {
            currentStackFrame.setLocal(i, stack.peek())
        }
    }

    private fun I32_ATOMIC_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var value = instance.memory(operands.get(2).toInt()).atomicReadInt(ptr)
        stack.push(value)
    }

    private fun I64_ATOMIC_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var value = instance.memory(operands.get(2).toInt()).atomicReadLong(ptr)
        stack.push(value)
    }

    private fun I64_ATOMIC_LOAD8_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).atomicReadByte(ptr)
        stack.push(value.toUByte().toLong())
    }

    private fun I32_ATOMIC_LOAD8_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).atomicReadByte(ptr)
        stack.push(value.toUByte().toLong())
    }

    private fun I32_ATOMIC_LOAD16_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var value = instance.memory(operands.get(2).toInt()).atomicReadShort(ptr)
        stack.push(value.toUShort().toLong())
    }

    private fun I64_ATOMIC_LOAD16_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var value = instance.memory(operands.get(2).toInt()).atomicReadShort(ptr)
        stack.push(value.toUShort().toLong())
    }

    private fun I64_ATOMIC_LOAD32_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var value = instance.memory(operands.get(2).toInt()).atomicReadInt(ptr)
        stack.push(value.toUInt().toLong())
    }

    private fun I32_ATOMIC_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        instance.memory(operands.get(2).toInt()).atomicWriteInt(ptr, value)
    }

    private fun I64_ATOMIC_STORE8(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toByte()
        var ptr = readMemPtr(stack, operands)
        instance.memory(operands.get(2).toInt()).atomicWriteByte(ptr, value)
    }

    private fun I64_ATOMIC_STORE16(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop().toShort()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        instance.memory(operands.get(2).toInt()).atomicWriteShort(ptr, value)
    }

    private fun I64_ATOMIC_STORE32(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        instance.memory(operands.get(2).toInt()).atomicWriteInt(ptr, value.toInt())
    }

    private fun I64_ATOMIC_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var value = stack.pop()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        instance.memory(operands.get(2).toInt()).atomicWriteLong(ptr, value)
    }

    private fun I32_ATOMIC_RMW(
        stack: MStack,
        instance: Instance,
        operands: Operands,
        op: AtomicOp,
    ) {
        var operand = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        val oldVal =
            when (op) {
                AtomicOp.ADD -> instance.memory(operands.get(2).toInt()).atomicAddInt(ptr, operand)
                AtomicOp.SUB -> instance.memory(operands.get(2).toInt()).atomicAddInt(ptr, -operand)
                AtomicOp.AND -> instance.memory(operands.get(2).toInt()).atomicAndInt(ptr, operand)
                AtomicOp.OR -> instance.memory(operands.get(2).toInt()).atomicOrInt(ptr, operand)
                AtomicOp.XOR -> instance.memory(operands.get(2).toInt()).atomicXorInt(ptr, operand)
                AtomicOp.XCHG ->
                    instance.memory(operands.get(2).toInt()).atomicXchgInt(ptr, operand)
            }
        stack.push(oldVal)
    }

    private fun I32_ATOMIC_RMW_CMPXCHG(stack: MStack, instance: Instance, operands: Operands) {
        var replacement = stack.pop().toInt() // c3
        var expected = stack.pop().toInt() // c2
        var ptr = readMemPtr(stack, operands) // i
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var oldVal =
            instance.memory(operands.get(2).toInt()).atomicCmpxchgInt(ptr, expected, replacement)
        stack.push(oldVal)
    }

    private fun I64_ATOMIC_RMW(
        stack: MStack,
        instance: Instance,
        operands: Operands,
        op: AtomicOp,
    ) {
        var operand = stack.pop()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        val oldVal =
            when (op) {
                AtomicOp.ADD -> instance.memory(operands.get(2).toInt()).atomicAddLong(ptr, operand)
                AtomicOp.SUB ->
                    instance.memory(operands.get(2).toInt()).atomicAddLong(ptr, -operand)
                AtomicOp.AND -> instance.memory(operands.get(2).toInt()).atomicAndLong(ptr, operand)
                AtomicOp.OR -> instance.memory(operands.get(2).toInt()).atomicOrLong(ptr, operand)
                AtomicOp.XOR -> instance.memory(operands.get(2).toInt()).atomicXorLong(ptr, operand)
                AtomicOp.XCHG ->
                    instance.memory(operands.get(2).toInt()).atomicXchgLong(ptr, operand)
            }
        stack.push(oldVal)
    }

    private fun I64_ATOMIC_RMW_CMPXCHG(stack: MStack, instance: Instance, operands: Operands) {
        var replacement = stack.pop()
        var expected = stack.pop()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var oldVal =
            instance.memory(operands.get(2).toInt()).atomicCmpxchgLong(ptr, expected, replacement)
        stack.push(oldVal)
    }

    private fun I64_ATOMIC_RMW8_U(
        stack: MStack,
        instance: Instance,
        operands: Operands,
        op: AtomicOp,
    ) {
        var operand = stack.pop().toByte()
        var ptr = readMemPtr(stack, operands)
        val oldVal =
            when (op) {
                AtomicOp.ADD -> instance.memory(operands.get(2).toInt()).atomicAddByte(ptr, operand)
                AtomicOp.SUB ->
                    instance.memory(operands.get(2).toInt()).atomicAddByte(ptr, (-operand).toByte())
                AtomicOp.AND -> instance.memory(operands.get(2).toInt()).atomicAndByte(ptr, operand)
                AtomicOp.OR -> instance.memory(operands.get(2).toInt()).atomicOrByte(ptr, operand)
                AtomicOp.XOR -> instance.memory(operands.get(2).toInt()).atomicXorByte(ptr, operand)
                AtomicOp.XCHG ->
                    instance.memory(operands.get(2).toInt()).atomicXchgByte(ptr, operand)
            }
        stack.push(oldVal.toUByte().toLong())
    }

    private fun I64_ATOMIC_RMW8_CMPXCHG_U(stack: MStack, instance: Instance, operands: Operands) {
        var replacement = stack.pop().toByte()
        var expected = stack.pop().toByte()
        var ptr = readMemPtr(stack, operands)
        var oldVal =
            instance.memory(operands.get(2).toInt()).atomicCmpxchgByte(ptr, expected, replacement)
        stack.push(oldVal.toUByte().toLong())
    }

    private fun I64_ATOMIC_RMW16_U(
        stack: MStack,
        instance: Instance,
        operands: Operands,
        op: AtomicOp,
    ) {
        var operand = stack.pop().toShort()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        val oldVal =
            when (op) {
                AtomicOp.ADD ->
                    instance.memory(operands.get(2).toInt()).atomicAddShort(ptr, operand)
                AtomicOp.SUB ->
                    instance
                        .memory(operands.get(2).toInt())
                        .atomicAddShort(ptr, (-operand).toShort())
                AtomicOp.AND ->
                    instance.memory(operands.get(2).toInt()).atomicAndShort(ptr, operand)
                AtomicOp.OR -> instance.memory(operands.get(2).toInt()).atomicOrShort(ptr, operand)
                AtomicOp.XOR ->
                    instance.memory(operands.get(2).toInt()).atomicXorShort(ptr, operand)
                AtomicOp.XCHG ->
                    instance.memory(operands.get(2).toInt()).atomicXchgShort(ptr, operand)
            }
        stack.push(oldVal.toUShort().toLong())
    }

    private fun I64_ATOMIC_RMW16_CMPXCHG_U(stack: MStack, instance: Instance, operands: Operands) {
        var replacement = stack.pop().toShort()
        var expected = stack.pop().toShort()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var oldVal =
            instance.memory(operands.get(2).toInt()).atomicCmpxchgShort(ptr, expected, replacement)
        stack.push(oldVal.toUShort().toLong())
    }

    private fun I64_ATOMIC_RMW32_U(
        stack: MStack,
        instance: Instance,
        operands: Operands,
        op: AtomicOp,
    ) {
        var operand = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        val oldVal =
            when (op) {
                AtomicOp.ADD -> instance.memory(operands.get(2).toInt()).atomicAddInt(ptr, operand)
                AtomicOp.SUB -> instance.memory(operands.get(2).toInt()).atomicAddInt(ptr, -operand)
                AtomicOp.AND -> instance.memory(operands.get(2).toInt()).atomicAndInt(ptr, operand)
                AtomicOp.OR -> instance.memory(operands.get(2).toInt()).atomicOrInt(ptr, operand)
                AtomicOp.XOR -> instance.memory(operands.get(2).toInt()).atomicXorInt(ptr, operand)
                AtomicOp.XCHG ->
                    instance.memory(operands.get(2).toInt()).atomicXchgInt(ptr, operand)
            }
        stack.push(oldVal.toUInt().toLong())
    }

    private fun I64_ATOMIC_RMW32_CMPXCHG_U(stack: MStack, instance: Instance, operands: Operands) {
        var replacement = stack.pop().toInt()
        var expected = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var oldVal =
            instance.memory(operands.get(2).toInt()).atomicCmpxchgInt(ptr, expected, replacement)
        stack.push(oldVal.toUInt().toLong())
    }

    private fun MEM_ATOMIC_WAIT32(stack: MStack, instance: Instance, operands: Operands) {
        var timeout = stack.pop()
        var expected = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var result = instance.memory(operands.get(2).toInt()).atomicWait(ptr, expected, timeout)
        stack.push(result)
    }

    private fun MEM_ATOMIC_WAIT64(stack: MStack, instance: Instance, operands: Operands) {
        var timeout = stack.pop()
        var expected = stack.pop()
        var ptr = readMemPtr(stack, operands)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        var result = instance.memory(operands.get(2).toInt()).atomicWait(ptr, expected, timeout)
        stack.push(result)
    }

    private fun MEM_ATOMIC_NOTIFY(stack: MStack, instance: Instance, operands: Operands) {
        var maxThreads = stack.pop().toInt()
        var ptr = readMemPtr(stack, operands)
        var result = instance.memory(operands.get(2).toInt()).atomicNotify(ptr, maxThreads)
        stack.push(result)
    }

    private fun ATOMIC_FENCE(instance: Instance) {
        instance.memory(0).atomicFence()
    }

    private fun RETURN_CALL(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        operands: Operands,
        currentStackFrame: StackFrame,
    ): StackFrame {
        var funcId = operands.get(0).toInt()
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)
        var func = instance.function(funcId)
        var args = extractArgsForParams(stack, type)

        // optimizing when the tail call happens in the same function
        if (currentStackFrame.funcId() == funcId) {
            var ctrlFrame = currentStackFrame.popCtrlTillCall()
            StackFrame.doControlTransfer(ctrlFrame, stack)
            currentStackFrame.reset(args)
            currentStackFrame.pushCtrl(ctrlFrame)
            return currentStackFrame
        } else {
            var fromCallStack = callStack.isNotEmpty()
            var ctrlFrame =
                if (fromCallStack) callStack.removeLast().popCtrlTillCall()
                else currentStackFrame.popCtrlTillCall()
            StackFrame.doControlTransfer(ctrlFrame, stack)

            if (func != null) {
                var newFrame =
                    newStackFrame(instance, funcId, args, type, func)
                newFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                if (fromCallStack) {
                    callStack.addLast(newFrame)
                }
                return newFrame
            } else {
                var newFrame = StackFrame(instance, funcId, args)
                newFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                callStack.addLast(newFrame)

                var imprt = instance.imports().function(funcId)

                try {
                    var results = imprt.handle()!!.apply(instance, args)
                    // a host function can return null or an array of ints
                    // which we will push onto the stack
                    if (results != null) {
                        for (result in results) {
                            stack.push(result)
                        }
                    }
                } catch (e: WasmException) {
                    THROW_REF(instance, instance.registerException(e), stack, newFrame, callStack)
                }
                if (fromCallStack) {
                    callStack.addLast(newFrame)
                }
                return newFrame
            }
        }
    }

    private fun RETURN_CALL_INDIRECT(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        operands: Operands,
        currentStackFrame: StackFrame,
    ): StackFrame {
        var tableIdx = operands.get(1).toInt()
        var table = instance.table(tableIdx)

        var typeId = operands.get(0).toInt()
        var funcTableIdx = stack.pop().toInt()

        var funcId = table.requiredRef(funcTableIdx)
        var refInstance = table.instance(funcTableIdx) ?: instance
        var type = refInstance.type(typeId)

        // Verify type match using nominal type indices
        var actualTypeIdx = refInstance.functionType(funcId)
        verifyIndirectCallByTypeIdx(actualTypeIdx, typeId, refInstance.module().typeSection())

        val refMachine = refInstance.getMachine()
        if (refInstance != instance && refMachine::class != instance.getMachine()::class) {
            throw WasmEngineException(
                "Indirect tail-call to a different Machine implementation is not supported: " +
                    refMachine::class
            )
        }

        var args = extractArgsForParams(stack, type)

        // optimizing when the tail call happens in the same function
        if (currentStackFrame.funcId() == funcId) {
            var ctrlFrame = currentStackFrame.popCtrlTillCall()
            StackFrame.doControlTransfer(ctrlFrame, stack)
            currentStackFrame.reset(args)
            currentStackFrame.pushCtrl(ctrlFrame)
            return currentStackFrame
        } else {
            var func = instance.function(funcId)
            var fromCallStack = callStack.isNotEmpty()

            if (func != null) {
                var ctrlFrame =
                    if (fromCallStack) callStack.removeLast().popCtrlTillCall()
                    else currentStackFrame.popCtrlTillCall()
                StackFrame.doControlTransfer(ctrlFrame, stack)
                var newFrame =
                    newStackFrame(instance, funcId, args, type, func)
                newFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                if (fromCallStack) {
                    callStack.addLast(newFrame)
                }
                return newFrame
            } else {
                var newFrame = StackFrame(instance, funcId, args)
                newFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                callStack.addLast(newFrame)

                var imprt = instance.imports().function(funcId)

                try {
                    var results = imprt.handle()!!.apply(instance, args)
                    // a host function can return null or an array of ints
                    // which we will push onto the stack
                    if (results != null) {
                        for (result in results) {
                            stack.push(result)
                        }
                    }
                } catch (e: WasmException) {
                    THROW_REF(instance, instance.registerException(e), stack, newFrame, callStack)
                }
                if (fromCallStack) {
                    callStack.addLast(newFrame)
                }
                return newFrame
            }
        }
    }

    private fun RETURN_CALL_REF(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        currentStackFrame: StackFrame,
    ): StackFrame {
        var funcId = stack.pop().toInt()
        if (funcId == Value.REF_NULL_VALUE) {
            throw TrapException("Trapped on call_ref on null function reference")
        }
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)
        var func = instance.function(funcId)!!
        // given a list of param types, let's pop those params off the stack
        // and pass as args to the function call
        var args = extractArgsForParams(stack, type)

        // optimizing when the tail call happens in the same function
        if (currentStackFrame.funcId() == funcId) {
            var ctrlFrame = currentStackFrame.popCtrlTillCall()
            StackFrame.doControlTransfer(ctrlFrame, stack)
            currentStackFrame.reset(args)
            currentStackFrame.pushCtrl(ctrlFrame)
            return currentStackFrame
        } else {
            var ctrlFrame = callStack.removeLast()
            StackFrame.doControlTransfer(ctrlFrame.popCtrlTillCall(), stack)
            var newFrame =
                newStackFrame(instance, funcId, args, type, func)
            newFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
            callStack.addLast(newFrame)
            return newFrame
        }
    }

    private fun CALL_INDIRECT(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        operands: Operands,
    ): StackFrame? {
        var tableIdx = operands.get(1).toInt()
        var table = instance.table(tableIdx)

        var typeId = operands.get(0).toInt()
        var funcTableIdx = stack.pop().toInt()

        var funcId = table.requiredRef(funcTableIdx)
        var refInstance = table.instance(funcTableIdx) ?: instance
        var type = refInstance.type(typeId)

        // Verify type match using nominal type indices
        var actualTypeIdx = refInstance.functionType(funcId)
        verifyIndirectCallByTypeIdx(actualTypeIdx, typeId, refInstance.module().typeSection())

        // given a list of param types, let's pop those params off the stack
        // and pass as args to the function call
        var args = extractArgsForParams(stack, type)
        if (useCurrentInstanceInterpreter(instance, refInstance, funcId)) {
            return pushOrCallFunction(refInstance, funcId, args, type)
        } else {
            checkInterruption()
            var results = refInstance.getMachine().call(funcId, args)
            for (result in results) {
                stack.push(result)
            }
            return null
        }
    }

    private fun pushOrCallFunction(
        targetInstance: Instance,
        funcId: Int,
        args: LongArray,
        type: FunctionType,
    ): StackFrame? {
        val func = targetInstance.function(funcId)
        if (func == null) {
            call(stack, targetInstance, callStack, funcId, args, type, false)
            return null
        }
        val stackFrame =
            newStackFrame(targetInstance, funcId, args, type, func)
        stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
        checkCallStackDepth(callStack)
        callStack.addLast(stackFrame)
        return stackFrame
    }

    protected open fun useCurrentInstanceInterpreter(
        instance: Instance,
        refInstance: Instance,
        funcId: Int,
    ): Boolean {
        return refInstance.equals(instance)
    }

    protected fun THROW_REF(
        instance: Instance,
        exceptionIdx: Int,
        stack: MStack,
        initialFrame: StackFrame,
        callStack: ArrayDeque<StackFrame>,
    ): StackFrame {
        var exception = instance.exn(exceptionIdx)!!
        var frame = initialFrame
        var found = false
        while (!found) {
            while (frame.ctrlStackSize() > 0) {
                var ctrlFrame = frame.popCtrl()
                if (ctrlFrame.opCode != OpCode.TRY_TABLE) {
                    continue
                }

                frame.jumpTo(ctrlFrame.pc)
                var tryInst = frame.loadCurrentInstruction()

                var catches = tryInst.catches()!!
                var i = 0
                while (i < catches.size && !found) {
                    var currentCatch = catches.get(i)

                    // verify import compatibility
                    var compatibleImport = false
                    if (
                        (currentCatch.opcode() == CatchOpCode.CATCH ||
                            currentCatch.opcode() == CatchOpCode.CATCH_REF)
                    ) {
                        var currentCatchTag = instance.tag(currentCatch.tag())
                        var exceptionTag = exception.instance().tag(exception.tagIdx())

                        // if it's an import we verify the compatibility
                        if (
                            currentCatch.tag() < instance.imports().tagCount() &&
                                currentCatchTag.type()!!.paramsMatch(exceptionTag.type()!!) &&
                                currentCatchTag.type()!!.returnsMatch(exceptionTag.type()!!)
                        ) {
                            compatibleImport = true
                        } else if (exceptionTag != currentCatchTag) {
                            // if it's not an import the tag should be the same
                            i++
                            continue
                        }
                    }

                    when (currentCatch.opcode()) {
                        CatchOpCode.CATCH -> {
                            if (currentCatch.tag() == exception.tagIdx() || compatibleImport) {
                                found = true
                                for (arg in exception.args()) {
                                    stack.push(arg)
                                }
                            }
                        }
                        CatchOpCode.CATCH_REF -> {
                            if (currentCatch.tag() == exception.tagIdx() || compatibleImport) {
                                found = true
                                for (arg in exception.args()) {
                                    stack.push(arg)
                                }
                                stack.push(exceptionIdx)
                            }
                        }
                        CatchOpCode.CATCH_ALL -> {
                            found = true
                        }
                        CatchOpCode.CATCH_ALL_REF -> {
                            found = true
                            stack.push(exceptionIdx)
                        }
                    }

                    if (found) {
                        // BR l
                        ctrlJump(frame, stack, currentCatch.label())
                        frame.jumpTo(currentCatch.resolvedLabel())
                        return frame
                    }
                    i++
                }
            }
            if (!found) {
                if (callStack.isEmpty()) {
                    throw exception
                }
                // Only pop if the current frame is on the callStack
                // in CompilerInterpreterMachine.CALL() the frame may be
                // an ad-hoc StackFrame that was never pushed.
                if (callStack.last() == frame) {
                    callStack.removeLast()
                }
                if (callStack.isEmpty()) {
                    throw exception
                }
                frame = callStack.last() // keep catcher on callStack
            }
        }
        throw RuntimeException("unreacheable")
    }

    private fun BLOCK(
        frame: StackFrame,
        stack: MStack,
        instruction: AnnotatedInstruction,
    ) {
        val paramsSize = frame.currentControlStartValues()
        val returnsSize = frame.currentControlEndValues()
        frame.pushCtrl(instruction.opcode(), paramsSize, returnsSize, stack.size() - paramsSize)
    }

    private fun TRY_TABLE(
        frame: StackFrame,
        stack: MStack,
        instruction: AnnotatedInstruction,
        pc: Int,
    ) {
        val paramsSize = frame.currentControlStartValues()
        val returnsSize = frame.currentControlEndValues()
        frame.pushCtrl(instruction.opcode(), paramsSize, returnsSize, stack.size() - paramsSize, pc)
    }

    private fun IF(
        frame: StackFrame,
        stack: MStack,
        instruction: AnnotatedInstruction,
    ) {
        val predValue = stack.pop()
        val paramsSize = frame.currentControlStartValues()
        val returnsSize = frame.currentControlEndValues()
        frame.pushCtrl(instruction.opcode(), paramsSize, returnsSize, stack.size() - paramsSize)

        frame.jumpTo(if (predValue == 0L) instruction.labelFalse() else instruction.labelTrue())
    }

    private fun ctrlJump(frame: StackFrame, stack: MStack, n: Int) {
        var ctrlFrame = frame.popCtrl(n)
        frame.pushCtrl(ctrlFrame)
        // a LOOP jumps back to the first instruction without passing through an END
        if (ctrlFrame.opCode == OpCode.LOOP) {
            StackFrame.doControlTransfer(ctrlFrame, stack)
        }
    }

    private fun BR(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        checkInterruption()
        ctrlJump(frame, stack, instruction.operand(0).toInt())
        frame.jumpTo(instruction.labelTrue())
    }

    private fun BR_TABLE(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        checkInterruption()
        var pred = stack.pop().toInt()

        var defaultIdx = instruction.operandCount() - 1
        if (pred < 0 || pred >= defaultIdx) {
            // choose default
            ctrlJump(frame, stack, instruction.operand(defaultIdx).toInt())
            frame.jumpTo(instruction.labelTable().get(defaultIdx))
        } else {
            ctrlJump(frame, stack, instruction.operand(pred).toInt())
            frame.jumpTo(instruction.labelTable().get(pred))
        }
    }

    private fun BR_IF(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        checkInterruption()
        var pred = stack.pop().toInt()

        if (pred == 0) {
            frame.jumpTo(instruction.labelFalse())
        } else {
            ctrlJump(frame, stack, instruction.operand(0).toInt())
            frame.jumpTo(instruction.labelTrue())
        }
    }

    private fun BR_ON_NULL(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            BR(frame, stack, instruction)
        } else {
            stack.push(ref)
        }
    }

    private fun BR_ON_NON_NULL(
        frame: StackFrame,
        stack: MStack,
        instruction: AnnotatedInstruction,
    ) {
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            // do nothing
        } else {
            stack.push(ref)
            BR(frame, stack, instruction)
        }
    }

    protected fun extractArgsForParams(stack: MStack, params: List<ValType>): LongArray {
        return extractArgsForParamSlotCount(stack, ValType.sizeOf(params))
    }

    protected fun extractArgsForParams(stack: MStack, type: FunctionType): LongArray {
        return extractArgsForParamSlotCount(stack, type.paramSlotCount())
    }

    private fun extractArgsForParamSlotCount(stack: MStack, slotCount: Int): LongArray {
        if (slotCount == 0) {
            return Value.EMPTY_VALUES
        }
        var args = LongArray(slotCount)
        for (i in 0 until (args.size)) {
            args[args.size - i - 1] = stack.pop()
        }
        return args
    }

    private fun functionTypeMatch(
        actual: FunctionType,
        expected: FunctionType,
        ts: TypeSection,
    ): Boolean {
        if (
            actual.params().size != expected.params().size ||
                actual.returns().size != expected.returns().size
        ) {
            return false
        }

        for (i in 0 until (actual.params().size)) {
            var actualParam = actual.params().get(i)
            var expectedParam = expected.params().get(i)

            // Contravariant: expected.param <: actual.param
            if (!ValType.matches(expectedParam, actualParam, ts)) {
                return false
            }
        }

        for (i in 0 until (actual.returns().size)) {
            var actualReturn = actual.returns().get(i)
            var expectedReturn = expected.returns().get(i)

            // Covariant: actual.return <: expected.return
            if (!ValType.matches(actualReturn, expectedReturn, ts)) {
                return false
            }
        }

        return true
    }

    protected fun verifyIndirectCall(
        actual: FunctionType,
        expected: FunctionType,
        ts: TypeSection,
    ) {
        if (!functionTypeMatch(actual, expected, ts)) {
            throw WasmEngineException("indirect call type mismatch")
        }
    }

    protected fun verifyIndirectCallByTypeIdx(
        actualTypeIdx: Int,
        expectedTypeIdx: Int,
        ts: TypeSection,
    ) {
        if (
            actualTypeIdx != expectedTypeIdx &&
                !ValType.heapTypeSubtype(actualTypeIdx, expectedTypeIdx, ts)
        ) {
            throw WasmEngineException("indirect call type mismatch")
        }
    }

    /**
     * Terminate WASM execution if requested. This is called at the start of each call and at any
     * potentially backwards branches. Forward branches and other non-branch instructions are not
     * checked, as the execution will run until it eventually reaches a termination point.
     */
    private fun checkInterruption() {
        if (isInterrupted()) {
            throw WasmInterruptedException("Thread interrupted")
        }
    }

    // ===== GC opcode implementations =====
    private fun REF_EQ(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(if (a == b) Value.TRUE else Value.FALSE)
    }

    private fun REF_I31(stack: MStack) {
        var value = stack.pop().toInt()
        stack.push(Value.encodeI31(value))
    }

    private fun I31_GET_S(stack: MStack) {
        var ref = stack.pop()
        if (ref == Value.REF_NULL_VALUE.toLong()) {
            throw TrapException("null i31 reference")
        }
        stack.push(Value.decodeI31S(ref))
    }

    private fun I31_GET_U(stack: MStack) {
        var ref = stack.pop()
        if (ref == Value.REF_NULL_VALUE.toLong()) {
            throw TrapException("null i31 reference")
        }
        stack.push(Value.decodeI31U(ref))
    }

    private fun STRUCT_NEW(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        var fields = LongArray(st.fieldTypes().size)
        // Pop fields in reverse order (last field on top)
        for (i in fields.size - 1 downTo 0) {
            fields[i] = stack.pop()
        }
        var struct = WasmStruct(typeIdx, fields)
        stack.push(instance.registerGcRef(struct))
    }

    private fun STRUCT_NEW_DEFAULT(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        var fields = LongArray(st.fieldTypes().size)
        // Default values: 0 for numeric, Value.REF_NULL_VALUE for references
        for (i in 0 until (fields.size)) {
            var ft = st.fieldTypes()[i]
            val valType = ft.storageType().valType()
            if (valType != null && valType.isReference()) {
                fields[i] = Value.REF_NULL_VALUE.toLong()
            }
            // numeric types default to 0 (already zero-initialized)
        }
        var struct = WasmStruct(typeIdx, fields)
        stack.push(instance.registerGcRef(struct))
    }

    private fun STRUCT_GET(stack: MStack, instance: Instance, currentStackFrame: StackFrame) {
        val fieldIdx = currentStackFrame.currentStructFieldIndex()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        var struct = instance.gcRefUnchecked(ref) as WasmStruct
        var value = struct.field(fieldIdx)
        val packedMask = currentStackFrame.currentStructPackedMask()
        if (packedMask != 0L) {
            if (currentStackFrame.currentStructPackedSignExtend()) {
                value =
                    when (packedMask) {
                        0xFFL -> value.toByte().toLong()
                        0xFFFFL -> value.toShort().toLong()
                        else -> value
                    }
            } else {
                value = value and packedMask
            }
        }
        stack.push(value)
    }

    private fun STRUCT_SET(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var fieldIdx = operands.get(1).toInt()
        var value = stack.pop()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        var struct = instance.gcRef(ref) as WasmStruct
        var st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        var ft = st.fieldTypes()[fieldIdx]
        val packedType = ft.storageType().packedType()
        if (packedType != null) {
            value = value and packedType.mask()
        }
        struct.setField(fieldIdx, value)
    }

    private fun ARRAY_NEW(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var len = stack.pop().toInt()
        var initVal = stack.pop()
        var elems = LongArray(len)
        elems.fill(initVal)
        var arr = WasmArray(typeIdx, elems)
        stack.push(instance.registerGcRef(arr))
    }

    private fun ARRAY_NEW_DEFAULT(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var len = stack.pop().toInt()
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        var elems = LongArray(len)
        if (
            at.fieldType().storageType().valType() != null &&
                at.fieldType().storageType().valType()!!.isReference()
        ) {
            elems.fill(Value.REF_NULL_VALUE.toLong())
        }
        var arr = WasmArray(typeIdx, elems)
        stack.push(instance.registerGcRef(arr))
    }

    private fun ARRAY_NEW_FIXED(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var len = operands.get(1).toInt()
        var elems = LongArray(len)
        for (i in len - 1 downTo 0) {
            elems[i] = stack.pop()
        }
        var arr = WasmArray(typeIdx, elems)
        stack.push(instance.registerGcRef(arr))
    }

    private fun ARRAY_NEW_DATA(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var dataIdx = operands.get(1).toInt()
        var len = stack.pop().toInt()
        var offset = stack.pop().toInt()
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        var elemSize = at.fieldType().storageType().byteSize()
        var data = instance.dataSegmentData(dataIdx)
        if (offset + len * elemSize > data.size) {
            throw TrapException("out of bounds memory access")
        }
        var elems = LongArray(len)
        for (i in 0 until (len)) {
            var byteOff = offset + i * elemSize
            elems[i] = readFromData(data, byteOff, elemSize)
        }
        var arr = WasmArray(typeIdx, elems)
        stack.push(instance.registerGcRef(arr))
    }

    private fun ARRAY_NEW_ELEM(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var elemIdx = operands.get(1).toInt()
        var len = stack.pop().toInt()
        var offset = stack.pop().toInt()
        var element = instance.elementOrNull(elemIdx)
        if (element == null || offset + len > element.elementCount()) {
            throw TrapException("out of bounds table access")
        }
        var elems = LongArray(len)
        for (i in 0 until (len)) {
            var init = element.initializers().get(offset + i)
            elems[i] = ConstantEvaluators.computeConstantValue(instance, init)[0]
        }
        var arr = WasmArray(typeIdx, elems)
        stack.push(instance.registerGcRef(arr))
    }

    private fun ARRAY_GET(stack: MStack, instance: Instance, operands: Operands, opcode: OpCode) {
        var typeIdx = operands.get(0).toInt()
        var idx = stack.pop().toInt()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        var value = arr.get(idx)
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            if (opcode == OpCode.ARRAY_GET_S) {
                value = packedType.signExtend(value)
            } else {
                value = value and packedType.mask()
            }
        }
        stack.push(value)
    }

    private fun ARRAY_SET(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var value = stack.pop()
        var idx = stack.pop().toInt()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            value = value and packedType.mask()
        }
        arr.set(idx, value)
    }

    private fun ARRAY_LEN(stack: MStack, instance: Instance) {
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        stack.push(arr.length())
    }

    private fun ARRAY_FILL(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var len = stack.pop().toInt()
        var value = stack.pop()
        var offset = stack.pop().toInt()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        if (offset + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            value = value and packedType.mask()
        }
        for (i in 0 until (len)) {
            arr.set(offset + i, value)
        }
    }

    private fun ARRAY_COPY(stack: MStack, instance: Instance) {
        // operands 0 and 1 are dst/src type indices (used for validation, not needed at runtime)
        var len = stack.pop().toInt()
        var srcOffset = stack.pop().toInt()
        var srcRef = stack.pop().toInt()
        var dstOffset = stack.pop().toInt()
        var dstRef = stack.pop().toInt()
        if (dstRef == Value.REF_NULL_VALUE || srcRef == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var dst = instance.gcRef(dstRef) as WasmArray
        var src = instance.gcRef(srcRef) as WasmArray
        if (dstOffset + len > dst.length() || srcOffset + len > src.length()) {
            throw TrapException("out of bounds array access")
        }
        // Handle overlapping copies
        if (dstOffset <= srcOffset) {
            for (i in 0 until (len)) {
                dst.set(dstOffset + i, src.get(srcOffset + i))
            }
        } else {
            for (i in len - 1 downTo 0) {
                dst.set(dstOffset + i, src.get(srcOffset + i))
            }
        }
    }

    private fun ARRAY_INIT_DATA(stack: MStack, instance: Instance, operands: Operands) {
        var typeIdx = operands.get(0).toInt()
        var dataIdx = operands.get(1).toInt()
        var len = stack.pop().toInt()
        var srcOffset = stack.pop().toInt()
        var dstOffset = stack.pop().toInt()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        var at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        var elemSize = at.fieldType().storageType().byteSize()
        var data = instance.dataSegmentData(dataIdx)
        if (dstOffset + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        if (srcOffset + len * elemSize > data.size) {
            throw TrapException("out of bounds memory access")
        }
        for (i in 0 until (len)) {
            var byteOff = srcOffset + i * elemSize
            arr.set(dstOffset + i, readFromData(data, byteOff, elemSize))
        }
    }

    private fun ARRAY_INIT_ELEM(stack: MStack, instance: Instance, operands: Operands) {
        // operand 0 is the type index (used for validation, not needed at runtime)
        var elemIdx = operands.get(1).toInt()
        var len = stack.pop().toInt()
        var srcOffset = stack.pop().toInt()
        var dstOffset = stack.pop().toInt()
        var ref = stack.pop().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        var arr = instance.gcRef(ref) as WasmArray
        var element = instance.elementOrNull(elemIdx)
        if (dstOffset + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        // Dropped segments have element count 0
        var elementCount = element?.elementCount() ?: 0
        if (srcOffset + len > elementCount) {
            throw TrapException("out of bounds table access")
        }
        if (len == 0) {
            return
        }
        for (i in 0 until (len)) {
            var init = element!!.initializers().get(srcOffset + i)
            arr.set(dstOffset + i, ConstantEvaluators.computeConstantValue(instance, init)[0])
        }
    }

    private fun REF_TEST(stack: MStack, instance: Instance, operands: Operands, opcode: OpCode) {
        var heapType = operands.get(0).toInt()
        var sourceHeapType = operands.get(1).toInt()
        var ref = stack.pop()
        var nullable = (opcode == OpCode.REF_TEST_NULL)
        stack.push(
            if (instance.heapTypeMatch(ref, nullable, heapType, sourceHeapType)) Value.TRUE
            else Value.FALSE
        )
    }

    private fun CAST_TEST(stack: MStack, instance: Instance, operands: Operands, opcode: OpCode) {
        var heapType = operands.get(0).toInt()
        var sourceHeapType = operands.get(1).toInt()
        var ref = stack.pop()
        var nullable = (opcode == OpCode.CAST_TEST_NULL)
        if (!instance.heapTypeMatch(ref, nullable, heapType, sourceHeapType)) {
            throw TrapException("cast failure")
        }
        stack.push(ref)
    }

    private fun BR_ON_CAST(
        stack: MStack,
        instance: Instance,
        frame: StackFrame,
        instruction: AnnotatedInstruction,
        operands: Operands,
    ) {
        var flags = operands.get(0).toInt()
        var ht2 = operands.get(3).toInt()
        var sourceHeapType = operands.get(4).toInt()
        var null2 = (flags and 2) != 0
        var ref = stack.pop()
        if (instance.heapTypeMatch(ref, null2, ht2, sourceHeapType)) {
            stack.push(ref)
            ctrlJump(frame, stack, operands.get(1).toInt())
            frame.jumpTo(instruction.labelTrue())
        } else {
            stack.push(ref)
        }
    }

    private fun BR_ON_CAST_FAIL(
        stack: MStack,
        instance: Instance,
        frame: StackFrame,
        instruction: AnnotatedInstruction,
        operands: Operands,
    ) {
        var flags = operands.get(0).toInt()
        var ht2 = operands.get(3).toInt()
        var sourceHeapType = operands.get(4).toInt()
        var null2 = (flags and 2) != 0
        var ref = stack.pop()
        if (!instance.heapTypeMatch(ref, null2, ht2, sourceHeapType)) {
            stack.push(ref)
            ctrlJump(frame, stack, operands.get(1).toInt())
            frame.jumpTo(instruction.labelTrue())
        } else {
            stack.push(ref)
        }
    }

    private fun readFromData(data: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until (size)) {
            value = value or ((data[offset + i].toInt() and 0xFF).toLong() shl (i * 8))
        }
        return value
    }

    private companion object {
        const val GC_POLL_INTERVAL: Int = 1024
        const val MAX_CALL_STACK_DEPTH: Int = 16_384
    }
}
