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
open class InterpreterMachine(private val instance: Instance) : ResumableMachine {
    private enum class AtomicOp {
        ADD,
        SUB,
        AND,
        OR,
        XOR,
        XCHG,
    }

    private val stack = MStack()
    private val usesPeriodicInterruptionPolling = RuntimePlatform.usesPeriodicInterruptionPolling()
    private var frameLayouts: Array<StackFrame.Layout?>? = null
    private var framePools: Array<ArrayDeque<StackFrame>?>? = null
    private var fastFunctionKinds: IntArray? = null
    private var fastFunctionOperands: IntArray? = null
    private var fastFunctionOperands2: IntArray? = null
    private var fastFunctionOperands3: IntArray? = null
    private var fastFunctionOperands4: IntArray? = null
    private var fastFunctionOperands5: IntArray? = null
    private var fastFunctionOperands6: IntArray? = null
    private var fastFunctionOperands7: IntArray? = null
    private var fastFunctionOperands8: IntArray? = null
    private var fastFunctionOperands9: IntArray? = null
    private var fastFunctionOperands10: IntArray? = null
    private var fastFunctionOperands11: IntArray? = null
    private var fastFunctionOperands12: IntArray? = null
    private var fastFunctionOperands13: IntArray? = null
    private var fastFunctionLongOperands: LongArray? = null
    private val jsonKeyStringCacheRefs = IntArray(JSON_KEY_STRING_CACHE_SIZE) { Value.REF_NULL_VALUE }
    private val jsonKeyStringCacheHashes = IntArray(JSON_KEY_STRING_CACHE_SIZE)
    private val jsonKeyStringCacheLengths = IntArray(JSON_KEY_STRING_CACHE_SIZE)

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
        val pooledFrame = takeReusableStackFrame(targetInstance, funcId)
        if (pooledFrame != null) {
            pooledFrame.reset(args)
            return pooledFrame
        }
        val layout = stackFrameLayout(targetInstance, funcId, type, func)
        return StackFrame(targetInstance, funcId, args, layout)
    }

    private fun newStackFrameFromStack(
        targetInstance: Instance,
        funcId: Int,
        type: FunctionType,
        func: FunctionBody,
    ): StackFrame {
        val pooledFrame = takeReusableStackFrame(targetInstance, funcId)
        if (pooledFrame != null) {
            pooledFrame.reset(stack, type.paramSlotCount())
            return pooledFrame
        }
        val layout = stackFrameLayout(targetInstance, funcId, type, func)
        return StackFrame(targetInstance, funcId, stack, type.paramSlotCount(), layout)
    }

    private fun takeReusableStackFrame(targetInstance: Instance, funcId: Int): StackFrame? {
        if (targetInstance !== instance) return null
        val pools = framePools ?: return null
        if (funcId < 0 || funcId >= pools.size) return null
        val pool = pools[funcId] ?: return null
        return if (pool.isEmpty()) null else pool.removeLast()
    }

    private fun recycleStackFrame(frame: StackFrame) {
        if (!frame.belongsTo(instance)) return
        val funcId = frame.funcId()
        if (funcId < 0 || funcId >= instance.functionCount()) return
        val pools =
            framePools ?: arrayOfNulls<ArrayDeque<StackFrame>>(instance.functionCount()).also {
                framePools = it
            }
        val pool = pools[funcId] ?: ArrayDeque<StackFrame>().also { pools[funcId] = it }
        if (pool.size < MAX_REUSABLE_FRAMES_PER_FUNCTION) {
            pool.addLast(frame)
        }
    }

    private fun stackFrameLayout(
        targetInstance: Instance,
        funcId: Int,
        type: FunctionType,
        func: FunctionBody,
    ): StackFrame.Layout {
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
        return layout
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

    override fun resume(continuation: WasmContinuation): LongArray {
        checkInterruption()
        val stackBaseDepth = stack.size()
        val callStackBaseDepth = callStack.size
        var suspended = false
        stack.restoreFrom(stackBaseDepth, continuation.stackValues)
        for (frame in continuation.callStackFrames) {
            callStack.addLast(frame.snapshot())
        }
        try {
            RuntimePlatform.runCatchingStackOverflow {
                eval(stack, instance, callStack)
            }
        } catch (e: WasmExecutionSuspended) {
            suspended = true
            captureSuspension(
                e,
                stack,
                callStack,
                stackBaseDepth,
                callStackBaseDepth,
                continuation.returnSlotCount,
            )
            throw e
        } finally {
            while (callStack.size > callStackBaseDepth) {
                callStack.removeLast()
            }
            if (suspended) {
                stack.discardToSize(stackBaseDepth)
            }
        }

        val results = popResults(stack, continuation.returnSlotCount)
        stack.discardToSize(stackBaseDepth)
        return results
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
        val stackDepth = stack.size()
        val callStackDepth = callStack.size
        var suspended = false
        try {
            if (func != null) {
                var stackFrame =
                    newStackFrame(instance, funcId, args, type, func)
                stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
                pushInitialLocalGetIfAvailable(instance, stackFrame)
                checkCallStackDepth(callStack)
                callStack.addLast(stackFrame)

                var suspendedInFrame = false
                try {
                    RuntimePlatform.runCatchingStackOverflow {
                        eval(stack, instance, callStack)
                    }
                } catch (e: WasmExecutionSuspended) {
                    suspendedInFrame = true
                    throw e
                } finally {
                    if (!suspendedInFrame && callStack.isNotEmpty() && callStack.last() == stackFrame) {
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
        } catch (e: WasmExecutionSuspended) {
            suspended = true
            if (func != null) {
                captureSuspension(
                    e,
                    stack,
                    callStack,
                    stackDepth,
                    callStackDepth,
                    type.returnSlotCount(),
                )
            }
            throw e
        } finally {
            while (callStack.size > callStackDepth) {
                callStack.removeLast()
            }
            if (suspended) {
                stack.discardToSize(stackDepth)
            }
        }

        if (!popResults) {
            return LongArray(0)
        }

        return popResults(stack, type.returnSlotCount())
    }

    private fun captureSuspension(
        suspension: WasmExecutionSuspended,
        stack: MStack,
        callStack: ArrayDeque<StackFrame>,
        stackBaseDepth: Int,
        callStackBaseDepth: Int,
        returnSlotCount: Int,
    ) {
        if (suspension.continuation != null) return
        for (result in suspension.resumeResults) {
            stack.push(result)
        }
        val frameSnapshots = ArrayList<StackFrame>(callStack.size - callStackBaseDepth)
        var index = 0
        for (frame in callStack) {
            if (index >= callStackBaseDepth) {
                frameSnapshots.add(frame.snapshot())
            }
            index++
        }
        suspension.continuation =
            WasmContinuation(
                stack.snapshotFrom(stackBaseDepth),
                frameSnapshots,
                returnSlotCount,
            )
    }

    private fun popResults(stack: MStack, totalResults: Int): LongArray {
        if (totalResults == 0) return LongArray(0)
        if (stack.size() == 0) return LongArray(0)
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
            OpCode.CALL,
            OpCode.I32_CONST,
            OpCode.I64_CONST,
            OpCode.F32_CONST,
            OpCode.F64_CONST,
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
        val canUseFastLocalPaths = executionListener == null
        var loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
        var hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()

        while (!frame.terminated() && frame.ctrlStackSize() > 0) {
            if (loweredFunction != null) {
                frame = evalLowered(frame, loweredFunction, stack, callStack) ?: return
                loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                continue
            }

            var instruction =
                if (canUseFastLocalPaths) {
                    frame.loadNextNonNopInstruction()
                } else {
                    frame.loadCurrentInstruction()
                }
            var opcode = instruction.opcode()
            if (usesOperandWrapper(opcode)) {
                operands.reset(instruction)
            }
            executionListener?.onExecution(instruction, stack)
            gcPoll++
            if (gcPoll == GC_POLL_INTERVAL) {
                if (usesPeriodicInterruptionPolling) {
                    checkInterruption()
                }
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
                    frame.popCtrlAndTransfer(stack)

                    // if this is the last end, then we're done with
                    // the function
                    if (frame.ctrlStackSize() == 0) {
                        frame = completeFrame(frame, callStack) ?: return
                        loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                        hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                    }
                }
                OpCode.RETURN -> {
                    // RETURN doesn't pass through the END
                    frame.popCtrlTillCallAndTransfer(stack)
                    frame = completeFrame(frame, callStack) ?: return
                    loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                    hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                }
                OpCode.RETURN_CALL -> {
                    // swap in place the current frame
                    frame = RETURN_CALL(stack, instance, callStack, operands, frame)
                    loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                    hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                }
                OpCode.RETURN_CALL_INDIRECT -> {
                    // swap in place the current frame
                    frame = RETURN_CALL_INDIRECT(stack, instance, callStack, operands, frame)
                    loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                    hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                }
                OpCode.RETURN_CALL_REF -> {
                    // swap in place the current frame
                    frame = RETURN_CALL_REF(stack, instance, callStack, frame)
                    loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                    hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
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
                    val nextFrame = CALL_INDIRECT(stack, instance, callStack, operands)
                    if (nextFrame != null) {
                        frame = nextFrame
                        loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                        hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                    }
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
                    if (
                        !hasFusedCountdownBranches ||
                            !FUSED_COUNTDOWN_BRANCH(stack, frame)
                    ) {
                        LOCAL_GET(stack, frame)
                    }
                }
                OpCode.LOCAL_SET -> {
                    LOCAL_SET(stack, frame, canUseFastLocalPaths)
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
                    stack.push(frame.currentLiteralValue())
                }
                OpCode.I64_CONST -> {
                    stack.push(frame.currentLiteralValue())
                }
                OpCode.F32_CONST -> {
                    stack.push(frame.currentLiteralValue())
                }
                OpCode.F64_CONST -> {
                    stack.push(frame.currentLiteralValue())
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
                    val nextFrame = CALL(frame)
                    if (nextFrame != null) {
                        frame = nextFrame
                        loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                        hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                    }
                }
                OpCode.CALL_REF -> {
                    val nextFrame = CALL_REF()
                    if (nextFrame != null) {
                        frame = nextFrame
                        loweredFunction = if (canUseFastLocalPaths) frame.loweredFunction() else null
                        hasFusedCountdownBranches = canUseFastLocalPaths && frame.hasFusedCountdownBranches()
                    }
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

    private fun evalLowered(
        frame: StackFrame,
        loweredFunction: LoweredFunction,
        stack: MStack,
        callStack: ArrayDeque<StackFrame>,
    ): StackFrame? {
        var currentFrame = frame
        var gcPoll = 0

        while (!currentFrame.terminated() && currentFrame.ctrlStackSize() > 0) {
            when (currentFrame.loadLoweredOpcode(loweredFunction)) {
                LoweredFunction.NOP -> {}
                LoweredFunction.LOOP -> {
                    currentFrame.pushCtrl(OpCode.LOOP, 0, 0, stack.size())
                }
                LoweredFunction.END -> {
                    currentFrame.popCtrlAndTransfer(stack)
                    if (currentFrame.ctrlStackSize() == 0) {
                        return completeFrame(currentFrame, callStack)
                    }
                }
                LoweredFunction.BR_IF -> {
                    val pred = stack.pop().toInt()
                    if (pred == 0) {
                        currentFrame.jumpTo(currentFrame.currentLoweredLabelFalse(loweredFunction))
                    } else {
                        currentFrame.branchTo(currentFrame.currentLoweredOperand(loweredFunction).toInt(), stack)
                        currentFrame.jumpTo(currentFrame.currentLoweredLabelTrue(loweredFunction))
                    }
                }
                LoweredFunction.LOCAL_GET -> {
                    stack.push(currentFrame.localSlots()[currentFrame.currentLoweredOperand(loweredFunction).toInt()])
                }
                LoweredFunction.LOCAL_SET -> {
                    currentFrame.localSlots()[currentFrame.currentLoweredOperand(loweredFunction).toInt()] =
                        stack.pop()
                }
                LoweredFunction.LOCAL_TEE -> {
                    currentFrame.localSlots()[currentFrame.currentLoweredOperand(loweredFunction).toInt()] =
                        stack.peek()
                }
                LoweredFunction.I32_CONST -> {
                    stack.push(currentFrame.currentLoweredOperand(loweredFunction))
                }
                LoweredFunction.I32_ADD -> {
                    stack.i32Add()
                }
                LoweredFunction.I32_SUB -> {
                    stack.i32Sub()
                }
                LoweredFunction.I32_COUNTDOWN_BRANCH -> {
                    val locals = currentFrame.localSlots()
                    val localSlot = currentFrame.currentLoweredOperand(loweredFunction).toInt()
                    val value =
                        locals[localSlot].toInt() -
                            currentFrame.currentLoweredOperand2(loweredFunction)
                    locals[localSlot] = value.toLong()
                    if (value == 0) {
                        currentFrame.jumpTo(currentFrame.currentLoweredLabelFalse(loweredFunction))
                    } else {
                        val branchDepth = currentFrame.currentLoweredOperand3(loweredFunction)
                        if (branchDepth == LoweredFunction.CURRENT_PARAMETERLESS_LOOP_DEPTH) {
                            currentFrame.branchToCurrentParameterlessLoopUnchecked(stack)
                        } else {
                            currentFrame.branchTo(branchDepth, stack)
                        }
                        currentFrame.jumpTo(currentFrame.currentLoweredLabelTrue(loweredFunction))
                    }
                }
                else -> error("Unsupported lowered opcode")
            }

            gcPoll++
            if (gcPoll == GC_POLL_INTERVAL) {
                checkInterruption()
                instance.gcSafePoint(stack, callStack)
                gcPoll = 0
            }
        }

        return if (callStack.isEmpty()) null else callStack.last()
    }

    private fun completeFrame(
        frame: StackFrame,
        callStack: ArrayDeque<StackFrame>,
    ): StackFrame? {
        if (callStack.isNotEmpty() && callStack.last() == frame) {
            val completedFrame = callStack.removeLast()
            if (callStack.isNotEmpty()) {
                recycleStackFrame(completedFrame)
            }
        }
        return if (callStack.isEmpty()) null else callStack.last()
    }

    private fun I32_GE_U(stack: MStack) {
        stack.i32GeU()
    }

    private fun I64_GT_U(stack: MStack) {
        var b = stack.pop()
        var a = stack.pop()
        stack.push(OpcodeOps.I64_GT_U(a, b))
    }

    private fun I32_GE_S(stack: MStack) {
        stack.i32GeS()
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
        stack.i32LeS()
    }

    private fun I32_LE_U(stack: MStack) {
        stack.i32LeU()
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
        stack.i32Add()
    }

    private fun I64_ADD(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(a + b)
    }

    private fun I32_SUB(stack: MStack) {
        stack.i32Sub()
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
        stack.i32GtU()
    }

    private fun I32_GT_S(stack: MStack) {
        stack.i32GtS()
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
        stack.i32LtU()
    }

    private fun I32_LT_S(stack: MStack) {
        stack.i32LtS()
    }

    private fun I64_EQZ(stack: MStack) {
        var a = stack.pop()
        stack.push(OpcodeOps.I64_EQZ(a))
    }

    private fun I32_EQZ(stack: MStack) {
        stack.i32Eqz()
    }

    private fun I64_NE(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(OpcodeOps.I64_NE(a, b))
    }

    private fun I32_NE(stack: MStack) {
        stack.i32Ne()
    }

    private fun I64_EQ(stack: MStack) {
        var a = stack.pop()
        var b = stack.pop()
        stack.push(OpcodeOps.I64_EQ(a, b))
    }

    private fun I32_EQ(stack: MStack) {
        stack.i32Eq()
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
        return pushOrCallFunctionFromStack(instance, funcId, type)
    }

    protected open fun CALL(currentStackFrame: StackFrame): StackFrame? {
        val funcId = currentStackFrame.currentCallFunctionId()
        val type = currentStackFrame.currentCallType()
        val func = currentStackFrame.currentCallBody()
        if (func == null) {
            val args = extractArgsForParams(stack, type)
            call(stack, instance, callStack, funcId, args, type, false)
            return null
        }
        if (instance.executionListener() == null && tryApplyFastFunction(instance, funcId, func)) {
            return null
        }
        val stackFrame =
            newStackFrameFromStack(instance, funcId, type, func)
        stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
        pushInitialLocalGetIfAvailable(instance, stackFrame)
        checkCallStackDepth(callStack)
        callStack.addLast(stackFrame)
        return stackFrame
    }

    private fun CALL_REF(): StackFrame? {
        var funcId = stack.pop().toInt()
        if (funcId == Value.REF_NULL_VALUE) {
            throw TrapException("Trapped on call_ref on null function reference")
        }
        var typeId = instance.functionType(funcId)
        var type = instance.type(typeId)
        return pushOrCallFunctionFromStack(instance, funcId, type)
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
        val info = currentStackFrame.currentLocalInfo()
        val locals = currentStackFrame.localSlots()
        if (info < 0) {
            val i = -info - 1
            stack.push(locals[i])
            stack.push(locals[i + 1])
        } else {
            stack.push(locals[info])
        }
    }

    private fun FUSED_COUNTDOWN_BRANCH(stack: MStack, currentStackFrame: StackFrame): Boolean {
        val localSlot = currentStackFrame.currentFusedCountdownBranchLocalSlot()
        if (localSlot < 0) return false

        val locals = currentStackFrame.localSlots()
        val value =
            locals[localSlot].toInt() -
                currentStackFrame.currentFusedCountdownBranchConstant()
        locals[localSlot] = value.toLong()
        if (!usesPeriodicInterruptionPolling) {
            checkInterruption()
        }
        if (value == 0) {
            currentStackFrame.jumpTo(currentStackFrame.currentFusedCountdownBranchFalseLabel())
        } else {
            val branchDepth = currentStackFrame.currentFusedCountdownBranchDepth()
            if (branchDepth == LoweredFunction.CURRENT_PARAMETERLESS_LOOP_DEPTH) {
                currentStackFrame.branchToCurrentParameterlessLoopUnchecked(stack)
            } else {
                currentStackFrame.branchTo(branchDepth, stack)
            }
            currentStackFrame.jumpTo(currentStackFrame.currentFusedCountdownBranchTrueLabel())
        }
        return true
    }

    private fun LOCAL_SET(stack: MStack, currentStackFrame: StackFrame, allowFusion: Boolean) {
        val info = currentStackFrame.currentLocalInfo()
        val locals = currentStackFrame.localSlots()
        if (info < 0) {
            val i = -info - 1
            locals[i] = stack.pop()
            locals[i + 1] = stack.pop()
        } else {
            locals[info] = stack.pop()
            if (allowFusion) {
                val fusedSlot = currentStackFrame.currentFusedLocalSetNextLocalGetSlot()
                if (fusedSlot >= 0) {
                    stack.push(locals[fusedSlot])
                    currentStackFrame.jumpTo(currentStackFrame.currentPc() + 2)
                }
            }
        }
    }

    private fun LOCAL_TEE(stack: MStack, currentStackFrame: StackFrame) {
        // here we peek instead of pop, leaving it on the stack
        val info = currentStackFrame.currentLocalInfo()
        val locals = currentStackFrame.localSlots()
        if (info < 0) {
            val i = -info - 1
            val tmp = stack.pop()
            locals[i] = tmp
            locals[i + 1] = stack.peek()
            stack.push(tmp)
        } else {
            locals[info] = stack.peek()
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
                pushInitialLocalGetIfAvailable(instance, newFrame)
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
                } catch (e: WasmExecutionSuspended) {
                    if (callStack.isNotEmpty() && callStack.last() == newFrame) {
                        callStack.removeLast()
                    }
                    throw e
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
                pushInitialLocalGetIfAvailable(instance, newFrame)
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
                } catch (e: WasmExecutionSuspended) {
                    if (callStack.isNotEmpty() && callStack.last() == newFrame) {
                        callStack.removeLast()
                    }
                    throw e
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
            pushInitialLocalGetIfAvailable(instance, newFrame)
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

        if (useCurrentInstanceInterpreter(instance, refInstance, funcId)) {
            return pushOrCallFunctionFromStack(refInstance, funcId, type)
        } else {
            val args = extractArgsForParams(stack, type)
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
        pushInitialLocalGetIfAvailable(targetInstance, stackFrame)
        checkCallStackDepth(callStack)
        callStack.addLast(stackFrame)
        return stackFrame
    }

    private fun pushOrCallFunctionFromStack(
        targetInstance: Instance,
        funcId: Int,
        type: FunctionType,
    ): StackFrame? {
        val func = targetInstance.function(funcId)
        return pushOrCallFunctionFromStack(targetInstance, funcId, type, func)
    }

    private fun pushOrCallFunctionFromStack(
        targetInstance: Instance,
        funcId: Int,
        type: FunctionType,
        func: FunctionBody?,
    ): StackFrame? {
        if (func == null) {
            val args = extractArgsForParams(stack, type)
            call(stack, targetInstance, callStack, funcId, args, type, false)
            return null
        }
        if (targetInstance.executionListener() == null && tryApplyFastFunction(targetInstance, funcId, func)) {
            return null
        }
        val stackFrame =
            newStackFrameFromStack(targetInstance, funcId, type, func)
        stackFrame.pushCtrl(OpCode.CALL, 0, type.returnSlotCount(), stack.size())
        pushInitialLocalGetIfAvailable(targetInstance, stackFrame)
        checkCallStackDepth(callStack)
        callStack.addLast(stackFrame)
        return stackFrame
    }

    private fun tryApplyFastFunction(targetInstance: Instance, funcId: Int, func: FunctionBody): Boolean {
        if (targetInstance !== instance) return false

        val kind = fastFunctionKind(funcId, func)
        when (kind) {
            FAST_FUNCTION_NONE -> return false
            FAST_FUNCTION_IDENTITY -> return true
            FAST_FUNCTION_I64_EXTEND_I32_S -> {
                stack.i64ExtendI32STop()
                return true
            }
            FAST_FUNCTION_I32_WRAP_I64 -> {
                stack.i32WrapI64Top()
                return true
            }
            FAST_FUNCTION_I32_AND_CONST -> {
                stack.push(stack.pop().toInt() and fastFunctionOperands!![funcId])
                return true
            }
            FAST_FUNCTION_GLOBAL_GET -> {
                val global = targetInstance.global(fastFunctionOperands!![funcId])
                stack.push(global.valueLow)
                if (global.type == ValType.V128) {
                    stack.push(global.valueHigh)
                }
                return true
            }
            FAST_FUNCTION_GLOBAL_GET_LOW -> {
                stack.push(targetInstance.global(fastFunctionOperands!![funcId]).valueLow)
                return true
            }
            FAST_FUNCTION_INIT_FLAG_DONE -> {
                if (targetInstance.global(fastFunctionOperands!![funcId]).valueLow == 0L) {
                    return false
                }
                return true
            }
            FAST_FUNCTION_LAZY_GLOBAL_GET_NON_NULL -> {
                val value = targetInstance.global(fastFunctionOperands!![funcId]).valueLow
                if (value.toInt() == Value.REF_NULL_VALUE) {
                    return false
                }
                stack.push(value)
                return true
            }
            FAST_FUNCTION_INIT_THEN_GLOBAL_GET -> {
                val initFuncId = fastFunctionOperands!![funcId]
                val initFunc = targetInstance.function(initFuncId) ?: return false
                if (!tryApplyFastFunction(targetInstance, initFuncId, initFunc)) {
                    return false
                }
                stack.push(targetInstance.global(fastFunctionOperands2!![funcId]).valueLow)
                return true
            }
            FAST_FUNCTION_REF_EQ -> {
                val otherRef = stack.pop()
                val selfRef = stack.pop()
                stack.push(if (selfRef == otherRef) Value.TRUE else Value.FALSE)
                return true
            }
            FAST_FUNCTION_STRING_EQUALS -> {
                val values = stack.array()
                val stackSize = stack.size()
                val selfRef = values[stackSize - 2].toInt()
                val otherRef = values[stackSize - 1].toInt()
                val result =
                    fastStringEquals(
                        targetInstance,
                        selfRef,
                        otherRef,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                        fastFunctionOperands3!![funcId],
                        fastFunctionOperands4!![funcId],
                        fastFunctionOperands5!![funcId],
                        fastFunctionOperands6!![funcId],
                    ) ?: return false
                stack.pop()
                stack.pop()
                stack.push(if (result) Value.TRUE else Value.FALSE)
                return true
            }
            FAST_FUNCTION_MEMORY_LOAD8_S -> {
                val address = stack.pop().toInt()
                val offset = fastFunctionOperands!![funcId]
                if (offset < 0 || address < 0) {
                    throw WasmRuntimeException("out of bounds memory access")
                }
                stack.push(targetInstance.memory(fastFunctionOperands2!![funcId]).readI8(address + offset))
                return true
            }
            FAST_FUNCTION_MEMORY_LOAD8_U -> {
                val address = stack.pop().toInt()
                val offset = fastFunctionOperands!![funcId]
                if (offset < 0 || address < 0) {
                    throw WasmRuntimeException("out of bounds memory access")
                }
                stack.push(targetInstance.memory(fastFunctionOperands2!![funcId]).readU8(address + offset))
                return true
            }
            FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_S,
            FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_U,
            -> {
                val initFuncId = fastFunctionOperands!![funcId]
                val initFunc = targetInstance.function(initFuncId) ?: return false
                if (!tryApplyFastFunction(targetInstance, initFuncId, initFunc)) {
                    return false
                }
                val address = stack.pop().toInt()
                val offset = fastFunctionOperands2!![funcId]
                if (offset < 0 || address < 0) {
                    throw WasmRuntimeException("out of bounds memory access")
                }
                val memory = targetInstance.memory(fastFunctionOperands3!![funcId])
                stack.push(
                    if (kind == FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_U) {
                        memory.readU8(address + offset)
                    } else {
                        memory.readI8(address + offset)
                    },
                )
                return true
            }
            FAST_FUNCTION_MEMORY_STORE8 -> {
                val value = stack.pop().toByte()
                val address = stack.pop().toInt()
                val offset = fastFunctionOperands!![funcId]
                if (offset < 0 || address < 0) {
                    throw WasmRuntimeException("out of bounds memory access")
                }
                targetInstance.memory(fastFunctionOperands2!![funcId]).writeByte(address + offset, value)
                return true
            }
            FAST_FUNCTION_INIT_THEN_MEMORY_STORE8 -> {
                val initFuncId = fastFunctionOperands!![funcId]
                val initFunc = targetInstance.function(initFuncId) ?: return false
                if (!tryApplyFastFunction(targetInstance, initFuncId, initFunc)) {
                    return false
                }
                val value = stack.pop().toByte()
                val address = stack.pop().toInt()
                val offset = fastFunctionOperands2!![funcId]
                if (offset < 0 || address < 0) {
                    throw WasmRuntimeException("out of bounds memory access")
                }
                targetInstance.memory(fastFunctionOperands3!![funcId]).writeByte(address + offset, value)
                return true
            }
            FAST_FUNCTION_BUFFER_GET_ZERO -> {
                if (stack.peek() != 0L) {
                    return false
                }
                val values = stack.array()
                val bufferRef = values[stack.size() - 2].toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.fields[fastFunctionOperands2!![funcId]] <= 0L) {
                    return false
                }
                val segmentRef = buffer.fields[fastFunctionOperands!![funcId]].toInt()
                if (segmentRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val segment = targetInstance.gcRefUnchecked(segmentRef) as? WasmStruct ?: return false
                val wrapperRef = segment.fields[fastFunctionOperands3!![funcId]].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct ?: return false
                val arrayRef = wrapper.fields[fastFunctionOperands5!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val array = targetInstance.gcRef(arrayRef) as? WasmArray ?: return false
                val effectiveIndex = segment.fields[fastFunctionOperands4!![funcId]].toInt()
                if (effectiveIndex < 0 || effectiveIndex >= array.length()) {
                    return false
                }
                var value = array.get(effectiveIndex)
                when (fastFunctionOperands6!![funcId]) {
                    0xFF -> value = value.toByte().toLong()
                    0xFFFF -> value = value.toShort().toLong()
                }
                stack.pop()
                stack.pop()
                stack.push(value)
                return true
            }
            FAST_FUNCTION_BUFFER_SKIP_ONE -> {
                if (stack.peek() != 1L) {
                    return false
                }
                val values = stack.array()
                val bufferRef = values[stack.size() - 2].toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.fields[fastFunctionOperands2!![funcId]] <= 0L) {
                    return false
                }
                val segmentRef = buffer.fields[fastFunctionOperands!![funcId]].toInt()
                if (segmentRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val segment = targetInstance.gcRefUnchecked(segmentRef) as? WasmStruct ?: return false
                val positionField = fastFunctionOperands3!![funcId]
                val position = segment.fields[positionField].toInt()
                val limit = segment.fields[fastFunctionOperands4!![funcId]].toInt()
                if (position < 0 || position >= limit - 1) {
                    return false
                }
                buffer.fields[fastFunctionOperands2!![funcId]] = buffer.fields[fastFunctionOperands2!![funcId]] - 1L
                segment.fields[positionField] = (position + 1).toLong()
                stack.pop()
                stack.pop()
                return true
            }
            FAST_FUNCTION_BUFFER_REQUIRE_ONE -> {
                if (stack.peek() != 1L) {
                    return false
                }
                val values = stack.array()
                val bufferRef = values[stack.size() - 2].toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.fields[fastFunctionOperands!![funcId]] < 1L) {
                    return false
                }
                stack.pop()
                stack.pop()
                return true
            }
            FAST_FUNCTION_REAL_SOURCE_REQUIRE_ONE -> {
                if (stack.peek() != 1L) {
                    return false
                }
                val values = stack.array()
                val sourceRef = values[stack.size() - 2].toInt()
                if (sourceRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val source = targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct ?: return false
                if (source.fields[fastFunctionOperands!![funcId]] != 0L) {
                    return false
                }
                val bufferRef = source.fields[fastFunctionOperands2!![funcId]].toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.fields[fastFunctionOperands3!![funcId]] < 1L) {
                    return false
                }
                stack.pop()
                stack.pop()
                return true
            }
            FAST_FUNCTION_BUFFER_READ_UTF8_CODE_POINT_ASCII -> {
                val bufferRef = stack.peek().toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.fields[fastFunctionOperands2!![funcId]] <= 0L) {
                    return false
                }
                val segmentRef = buffer.fields[fastFunctionOperands!![funcId]].toInt()
                if (segmentRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val segment = targetInstance.gcRefUnchecked(segmentRef) as? WasmStruct ?: return false
                val positionField = fastFunctionOperands4!![funcId]
                val position = segment.fields[positionField].toInt()
                val limit = segment.fields[fastFunctionOperands7!![funcId]].toInt()
                if (position < 0 || position >= limit - 1) {
                    return false
                }
                val wrapperRef = segment.fields[fastFunctionOperands3!![funcId]].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct ?: return false
                val arrayRef = wrapper.fields[fastFunctionOperands5!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val array = targetInstance.gcRef(arrayRef) as? WasmArray ?: return false
                if (position >= array.length()) {
                    return false
                }
                var value = array.get(position)
                when (fastFunctionOperands6!![funcId]) {
                    0xFF -> value = value.toByte().toLong()
                    0xFFFF -> value = value.toShort().toLong()
                }
                val byte = value.toInt()
                if ((byte and 0x80) != 0) {
                    return false
                }
                buffer.fields[fastFunctionOperands2!![funcId]] = buffer.fields[fastFunctionOperands2!![funcId]] - 1L
                segment.fields[positionField] = (position + 1).toLong()
                stack.pop()
                stack.push(byte)
                return true
            }
            FAST_FUNCTION_REAL_SOURCE_READ_CODE_POINT_ASCII -> {
                val sourceRef = stack.peek().toInt()
                val byte = tryReadRealSourceCodePointAscii(targetInstance, funcId, sourceRef)
                if (byte < 0) return false
                stack.pop()
                stack.push(byte)
                return true
            }
            FAST_FUNCTION_IO_SERIAL_READER_NEXT_CODE_POINT_ASCII -> {
                val readerRef = stack.peek().toInt()
                if (readerRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val reader = targetInstance.gcRefUnchecked(readerRef) as? WasmStruct ?: return false
                if (reader.typeIdx() != fastFunctionOperands!![funcId]) {
                    return false
                }
                val sourceField = fastFunctionOperands2!![funcId]
                val sourceRef = reader.fields[sourceField].toInt()
                if (sourceRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val readFuncId = fastFunctionOperands3!![funcId]
                val byte = tryReadRealSourceCodePointAscii(targetInstance, readFuncId, sourceRef)
                if (byte < 0) return false
                stack.pop()
                stack.push(byte)
                return true
            }
            FAST_FUNCTION_READER_JSON_LEXER_SKIP_WHITESPACES_NON_WS -> {
                val lexerRef = stack.peek()
                if (!targetInstance.heapTypeMatch(
                        lexerRef,
                        true,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                    )
                ) {
                    throw TrapException("cast failure")
                }
                val lexerRefInt = lexerRef.toInt()
                if (lexerRefInt == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val lexer = targetInstance.gcRefUnchecked(lexerRefInt) as? WasmStruct ?: return false
                val positionField = fastFunctionOperands3!![funcId]
                val vtableField = fastFunctionOperands4!![funcId]
                if (lexer.fieldCount() <= maxOf(positionField, vtableField)) {
                    return false
                }
                val position = lexer.fields[positionField].toInt()
                if (position < 0) {
                    return false
                }
                val vtableRef = lexer.fields[vtableField].toInt()
                if (vtableRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val vtable = targetInstance.gcRefUnchecked(vtableRef) as? WasmStruct ?: return false
                val sourceGetterField = fastFunctionOperands5!![funcId]
                if (vtable.fieldCount() <= sourceGetterField) {
                    return false
                }
                val sourceGetterFuncId = vtable.fields[sourceGetterField].toInt()
                val sourceGetterFunc = targetInstance.function(sourceGetterFuncId) ?: return false
                if (!tryApplyFastFunction(targetInstance, sourceGetterFuncId, sourceGetterFunc)) {
                    return false
                }

                val sourceRef = stack.peek().toInt()
                val source =
                    if (sourceRef == Value.REF_NULL_VALUE) {
                        null
                    } else {
                        targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct
                    }
                val sourceArrayField = fastFunctionOperands6!![funcId]
                val sourceLengthField = fastFunctionOperands8!![funcId]
                if (source == null || source.fieldCount() <= maxOf(sourceArrayField, sourceLengthField)) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                if (position >= source.fields[sourceLengthField].toInt()) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                val wrapperRef = source.fields[sourceArrayField].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct
                val charArrayField = fastFunctionOperands7!![funcId]
                if (wrapper == null || wrapper.fieldCount() <= charArrayField) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                val arrayRef = wrapper.fields[charArrayField].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                val array = targetInstance.gcRef(arrayRef) as? WasmArray
                if (array == null || position >= array.length()) {
                    stack.replaceTop(lexerRef)
                    return false
                }
                val char = array.get(position).toInt() and 0xFFFF
                if (char == 32 || char == 10 || char == 13 || char == 9) {
                    stack.replaceTop(lexerRef)
                    return false
                }

                stack.replaceTop(position.toLong())
                return true
            }
            FAST_FUNCTION_READER_JSON_LEXER_INDEX_OF_ARRAY_SEQUENCE -> {
                val values = stack.array()
                val stackSize = stack.size()
                val lexerRef = values[stackSize - 3]
                if (!targetInstance.heapTypeMatch(
                        lexerRef,
                        true,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                    )
                ) {
                    throw TrapException("cast failure")
                }
                val lexerRefInt = lexerRef.toInt()
                if (lexerRefInt == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val lexer = targetInstance.gcRefUnchecked(lexerRefInt) as? WasmStruct ?: return false
                val vtableField = fastFunctionOperands3!![funcId]
                if (lexer.fieldCount() <= vtableField) {
                    return false
                }
                val vtableRef = lexer.fields[vtableField].toInt()
                if (vtableRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val vtable = targetInstance.gcRefUnchecked(vtableRef) as? WasmStruct ?: return false
                val sourceGetterField = fastFunctionOperands4!![funcId]
                if (vtable.fieldCount() <= sourceGetterField) {
                    return false
                }
                val sourceGetterFuncId = vtable.fields[sourceGetterField].toInt()
                val sourceGetterFunc = targetInstance.function(sourceGetterFuncId) ?: return false
                stack.push(lexerRef)
                if (!tryApplyFastFunction(targetInstance, sourceGetterFuncId, sourceGetterFunc)) {
                    stack.pop()
                    return false
                }

                val sourceRef = stack.peek().toInt()
                val source =
                    if (sourceRef == Value.REF_NULL_VALUE) {
                        null
                    } else {
                        targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct
                    }
                val sourceArrayField = fastFunctionOperands5!![funcId]
                val sourceLengthField = fastFunctionOperands6!![funcId]
                if (source == null || source.fieldCount() <= maxOf(sourceArrayField, sourceLengthField)) {
                    stack.pop()
                    return false
                }
                val sourceLength = source.fields[sourceLengthField].toInt()
                val start = values[stackSize - 1].toInt()
                if (start >= sourceLength) {
                    stack.pop()
                    stack.pop()
                    stack.pop()
                    stack.pop()
                    stack.push(-1)
                    return true
                }
                if (start < 0) {
                    throw TrapException("out of bounds array access")
                }
                val wrapperRef = source.fields[sourceArrayField].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    stack.pop()
                    return false
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct
                val charArrayField = fastFunctionOperands7!![funcId]
                if (wrapper == null || wrapper.fieldCount() <= charArrayField) {
                    stack.pop()
                    return false
                }
                val arrayRef = wrapper.fields[charArrayField].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    stack.pop()
                    return false
                }
                val array = targetInstance.gcRef(arrayRef) as? WasmArray
                if (array == null || sourceLength > array.length()) {
                    stack.pop()
                    return false
                }

                val targetChar = values[stackSize - 2].toInt()
                var index = start
                var result = -1
                while (index < sourceLength) {
                    if ((array.get(index).toInt() and 0xFFFF) == targetChar) {
                        result = index
                        break
                    }
                    index++
                }
                stack.pop()
                stack.pop()
                stack.pop()
                stack.pop()
                stack.push(result)
                return true
            }
            FAST_FUNCTION_BUFFER_EXHAUSTED -> {
                val bufferRef = stack.peek().toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.typeIdx() != fastFunctionOperands!![funcId]) {
                    return false
                }
                val sizeField = fastFunctionOperands2!![funcId]
                stack.pop()
                stack.push(if (buffer.fields[sizeField] == 0L) 1 else 0)
                return true
            }
            FAST_FUNCTION_REAL_SOURCE_EXHAUSTED_NONEMPTY -> {
                val sourceRef = stack.peek().toInt()
                if (sourceRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val source = targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct ?: return false
                if (source.typeIdx() != fastFunctionOperands!![funcId]) {
                    return false
                }
                val closedField = fastFunctionOperands2!![funcId]
                val bufferField = fastFunctionOperands3!![funcId]
                if (source.fields[closedField] != 0L) {
                    return false
                }
                val bufferRef = source.fields[bufferField].toInt()
                if (bufferRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return false
                if (buffer.typeIdx() != fastFunctionOperands4!![funcId]) {
                    return false
                }
                val sizeField = fastFunctionOperands5!![funcId]
                if (buffer.fields[sizeField] <= 0L) {
                    return false
                }
                stack.pop()
                stack.push(0)
                return true
            }
            FAST_FUNCTION_IO_SERIAL_READER_EXHAUSTED_NONEMPTY -> {
                val readerRef = stack.peek().toInt()
                if (readerRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val reader = targetInstance.gcRefUnchecked(readerRef) as? WasmStruct ?: return false
                if (reader.typeIdx() != fastFunctionOperands!![funcId]) {
                    return false
                }
                val sourceField = fastFunctionOperands2!![funcId]
                val sourceRef = reader.fields[sourceField].toInt()
                if (sourceRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val vtableRef = fastInterfaceVtableRef(
                    targetInstance,
                    fastFunctionOperands3!![funcId],
                    sourceRef,
                    fastFunctionLongOperands!![funcId],
                ) ?: return false
                val vtable = targetInstance.gcRefUnchecked(vtableRef) as? WasmStruct ?: return false
                if (vtable.typeIdx() != fastFunctionOperands4!![funcId]) {
                    return false
                }
                val methodField = fastFunctionOperands5!![funcId]
                val targetFuncId = vtable.fields[methodField].toInt()
                val targetFunc = targetInstance.function(targetFuncId) ?: return false
                if (fastFunctionKind(targetFuncId, targetFunc) != FAST_FUNCTION_REAL_SOURCE_EXHAUSTED_NONEMPTY) {
                    return false
                }
                stack.pop()
                stack.push(sourceRef)
                if (tryApplyFastFunction(targetInstance, targetFuncId, targetFunc)) {
                    return true
                }
                stack.pop()
                stack.push(readerRef)
                return false
            }
            FAST_FUNCTION_STRUCT_GET -> {
                val ref = stack.pop().toInt()
                if (ref == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val struct = targetInstance.gcRefUnchecked(ref) as WasmStruct
                stack.push(struct.fields[fastFunctionOperands2!![funcId]])
                return true
            }
            FAST_FUNCTION_CAST_STRUCT_GET -> {
                val ref = stack.pop()
                if (!targetInstance.heapTypeMatch(
                        ref,
                        true,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                    )
                ) {
                    throw TrapException("cast failure")
                }
                if (ref.toInt() == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val struct = targetInstance.gcRefUnchecked(ref.toInt()) as WasmStruct
                stack.push(struct.fields[fastFunctionOperands3!![funcId]])
                return true
            }
            FAST_FUNCTION_ARRAY_WRAPPER_GET,
            FAST_FUNCTION_ARRAY_WRAPPER_GET_S,
            FAST_FUNCTION_ARRAY_WRAPPER_GET_U -> {
                val index = stack.pop().toInt()
                val wrapperRef = stack.pop().toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as WasmStruct
                val arrayRef = wrapper.fields[fastFunctionOperands!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val array = targetInstance.gcRef(arrayRef) as WasmArray
                if (index < 0 || index >= array.length()) {
                    throw TrapException("out of bounds array access")
                }
                var value = array.get(index)
                val packedMask = fastFunctionOperands2!![funcId]
                if (packedMask != 0) {
                    value =
                        when (kind) {
                            FAST_FUNCTION_ARRAY_WRAPPER_GET_S ->
                                when (packedMask) {
                                    0xFF -> value.toByte().toLong()
                                    0xFFFF -> value.toShort().toLong()
                                    else -> value
                                }
                            FAST_FUNCTION_ARRAY_WRAPPER_GET_U -> value and packedMask.toLong()
                            else -> value
                        }
                }
                stack.push(value)
                return true
            }
            FAST_FUNCTION_ARRAY_WRAPPER_SET -> {
                var value = stack.pop()
                val index = stack.pop().toInt()
                val wrapperRef = stack.pop().toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as WasmStruct
                val arrayRef = wrapper.fields[fastFunctionOperands!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val array = targetInstance.gcRef(arrayRef) as WasmArray
                if (index < 0 || index >= array.length()) {
                    throw TrapException("out of bounds array access")
                }
                val packedMask = fastFunctionOperands2!![funcId]
                if (packedMask != 0) {
                    value = value and packedMask.toLong()
                }
                array.set(index, value)
                return true
            }
            FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET,
            FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_S,
            FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_U -> {
                val index = stack.pop().toInt()
                val ownerRef = stack.pop().toInt()
                if (ownerRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val owner = targetInstance.gcRefUnchecked(ownerRef) as WasmStruct
                val wrapperRef = owner.fields[fastFunctionOperands!![funcId]].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as WasmStruct
                val arrayRef = wrapper.fields[fastFunctionOperands3!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val array = targetInstance.gcRef(arrayRef) as WasmArray
                val offset = owner.fields[fastFunctionOperands2!![funcId]].toInt()
                val effectiveIndex = offset + index
                if (effectiveIndex < 0 || effectiveIndex >= array.length()) {
                    throw TrapException("out of bounds array access")
                }
                var value = array.get(effectiveIndex)
                val packedMask = fastFunctionOperands4!![funcId]
                if (packedMask != 0) {
                    value =
                        when (kind) {
                            FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_S ->
                                when (packedMask) {
                                    0xFF -> value.toByte().toLong()
                                    0xFFFF -> value.toShort().toLong()
                                    else -> value
                                }
                            FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_U -> value and packedMask.toLong()
                            else -> value
                        }
                }
                stack.push(value)
                return true
            }
            FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET,
            FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_S,
            FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_U -> {
                val index = stack.pop().toInt()
                val ownerRef = stack.pop()
                if (!targetInstance.heapTypeMatch(
                        ownerRef,
                        true,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                    )
                ) {
                    throw TrapException("cast failure")
                }
                if (ownerRef.toInt() == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val owner = targetInstance.gcRefUnchecked(ownerRef.toInt()) as WasmStruct
                val wrapperRef = owner.fields[fastFunctionOperands3!![funcId]].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as WasmStruct
                val arrayRef = wrapper.fields[fastFunctionOperands4!![funcId]].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val array = targetInstance.gcRef(arrayRef) as WasmArray
                if (index < 0 || index >= array.length()) {
                    throw TrapException("out of bounds array access")
                }
                var value = array.get(index)
                val packedMask = fastFunctionOperands5!![funcId]
                if (packedMask != 0) {
                    value =
                        when (kind) {
                            FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_S ->
                                when (packedMask) {
                                    0xFF -> value.toByte().toLong()
                                    0xFFFF -> value.toShort().toLong()
                                    else -> value
                                }
                            FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_U -> value and packedMask.toLong()
                            else -> value
                        }
                }
                stack.push(value)
                return true
            }
            FAST_FUNCTION_ARRAY_ANY_INDEX_OF_VALUE -> {
                val value = stack.pop()
                val arrayRef = stack.pop().toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val array = targetInstance.gcRef(arrayRef) as WasmArray
                for (index in 0 until array.length()) {
                    if (array.get(index) == value) {
                        stack.push(index)
                        return true
                    }
                }
                stack.push(-1)
                return true
            }
            FAST_FUNCTION_INTERFACE_VTABLE_GET -> {
                val interfaceId = stack.pop()
                val objectRef = stack.pop().toInt()
                if (objectRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val obj = targetInstance.gcRefUnchecked(objectRef) as WasmStruct
                val vtablesRef = obj.fields[fastFunctionOperands!![funcId]].toInt()
                val typeInfoRef = obj.fields[fastFunctionOperands2!![funcId]].toInt()
                if (typeInfoRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val typeInfo = targetInstance.gcRefUnchecked(typeInfoRef) as WasmStruct
                val interfaceIdsRef = typeInfo.fields[fastFunctionOperands3!![funcId]].toInt()
                if (interfaceIdsRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val interfaceIds = targetInstance.gcRef(interfaceIdsRef) as WasmArray
                var vtableIndex = -1
                for (index in 0 until interfaceIds.length()) {
                    if (interfaceIds.get(index) == interfaceId) {
                        vtableIndex = index
                        break
                    }
                }
                if (vtablesRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val vtables = targetInstance.gcRef(vtablesRef) as WasmArray
                if (vtableIndex < 0 || vtableIndex >= vtables.length()) {
                    throw TrapException("out of bounds array access")
                }
                stack.push(vtables.get(vtableIndex))
                return true
            }
            FAST_FUNCTION_ARRAY_AS_SEQUENCE_SUBSTRING -> {
                val values = stack.array()
                val stackSize = stack.size()
                val sequenceRef = values[stackSize - 3].toInt()
                if (sequenceRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val sequence = targetInstance.gcRefUnchecked(sequenceRef) as? WasmStruct ?: return false
                val sequenceArrayField = fastFunctionOperands!![funcId]
                val sequenceLengthField = fastFunctionOperands2!![funcId]
                if (sequence.fieldCount() <= maxOf(sequenceArrayField, sequenceLengthField)) {
                    return false
                }
                val charArrayRef = sequence.fields[sequenceArrayField].toInt()
                if (charArrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val charArray = targetInstance.gcRefUnchecked(charArrayRef) as? WasmStruct ?: return false
                val charArrayArrayField = fastFunctionOperands3!![funcId]
                if (charArray.fieldCount() <= charArrayArrayField) {
                    return false
                }
                val sourceArrayRef = charArray.fields[charArrayArrayField].toInt()
                if (sourceArrayRef == Value.REF_NULL_VALUE) {
                    throw TrapException("null array reference")
                }
                val sourceArray = targetInstance.gcRef(sourceArrayRef) as? WasmArray ?: return false
                val start = values[stackSize - 2].toInt()
                val end = values[stackSize - 1].toInt()
                val sequenceLength = sequence.fields[sequenceLengthField].toInt()
                val clampedEnd = if (end <= sequenceLength) end else sequenceLength
                if (start < 0 || start > clampedEnd || clampedEnd > sourceArray.length()) {
                    return false
                }

                val length = clampedEnd - start
                val elements = LongArray(length)
                sourceArray.copyInto(elements, endIndex = clampedEnd, startIndex = start)
                val resultArrayRef =
                    targetInstance.registerGcRef(WasmArray(fastFunctionOperands4!![funcId], elements))
                val stringFields = LongArray(7)
                stringFields[0] = targetInstance.global(fastFunctionOperands6!![funcId]).valueLow
                stringFields[1] = targetInstance.global(fastFunctionOperands7!![funcId]).valueLow
                stringFields[2] = targetInstance.global(fastFunctionOperands8!![funcId]).valueLow
                stringFields[3] = 0
                stringFields[4] = Value.REF_NULL_VALUE.toLong()
                stringFields[5] = length.toLong()
                stringFields[6] = resultArrayRef.toLong()

                stack.pop()
                stack.pop()
                stack.pop()
                stack.push(targetInstance.registerGcRef(WasmStruct(fastFunctionOperands5!![funcId], stringFields)))
                return true
            }
            FAST_FUNCTION_READER_JSON_LEXER_CONSUME_KEY_STRING -> {
                val lexerRef = stack.peek()
                if (!targetInstance.heapTypeMatch(
                        lexerRef,
                        true,
                        fastFunctionOperands!![funcId],
                        fastFunctionOperands2!![funcId],
                    )
                ) {
                    throw TrapException("cast failure")
                }
                val lexerRefInt = lexerRef.toInt()
                if (lexerRefInt == Value.REF_NULL_VALUE) {
                    throw TrapException("null structure reference")
                }
                val lexer = targetInstance.gcRefUnchecked(lexerRefInt) as? WasmStruct ?: return false
                val currentPositionField = fastFunctionOperands8!![funcId]
                val vtableField = fastFunctionOperands3!![funcId]
                if (lexer.fieldCount() <= maxOf(currentPositionField, vtableField)) {
                    return false
                }
                var position = lexer.fields[currentPositionField].toInt()
                if (position < 0) {
                    return false
                }
                val sourceRef = fastReaderJsonLexerSourceRef(
                    targetInstance,
                    lexer,
                    vtableField,
                    fastFunctionOperands4!![funcId],
                ) ?: return false
                val source = targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct ?: return false
                val sourceArrayField = fastFunctionOperands5!![funcId]
                val sourceLengthField = fastFunctionOperands6!![funcId]
                if (source.fieldCount() <= maxOf(sourceArrayField, sourceLengthField)) {
                    return false
                }
                val sourceLength = source.fields[sourceLengthField].toInt()
                if (sourceLength < 0 || position >= sourceLength) {
                    return false
                }
                val wrapperRef = source.fields[sourceArrayField].toInt()
                if (wrapperRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct ?: return false
                val charArrayField = fastFunctionOperands7!![funcId]
                if (wrapper.fieldCount() <= charArrayField) {
                    return false
                }
                val arrayRef = wrapper.fields[charArrayField].toInt()
                if (arrayRef == Value.REF_NULL_VALUE) {
                    return false
                }
                val array = targetInstance.gcRef(arrayRef) as? WasmArray ?: return false
                if (sourceLength > array.length()) {
                    return false
                }

                while (position < sourceLength) {
                    val char = array.get(position).toInt() and 0xFFFF
                    if (char == 32 || char == 10 || char == 13 || char == 9) {
                        position++
                    } else {
                        break
                    }
                }
                if (position >= sourceLength || (array.get(position).toInt() and 0xFFFF) != 34) {
                    return false
                }

                val start = position + 1
                var end = start
                while (end < sourceLength) {
                    val char = array.get(end).toInt() and 0xFFFF
                    if (char == 34) {
                        val stringRef = registerStringFromCharArray(
                            targetInstance,
                            array,
                            start,
                            end,
                            fastFunctionOperands9!![funcId],
                            fastFunctionOperands10!![funcId],
                            fastFunctionOperands11!![funcId],
                            fastFunctionOperands12!![funcId],
                            fastFunctionOperands13!![funcId],
                        ) ?: return false
                        lexer.fields[currentPositionField] = (end + 1).toLong()
                        stack.pop()
                        stack.push(stringRef)
                        return true
                    }
                    if (char == 92) {
                        return false
                    }
                    end++
                }
                return false
            }
            else -> return false
        }
    }

    private fun fastReaderJsonLexerSourceRef(
        targetInstance: Instance,
        lexer: WasmStruct,
        vtableField: Int,
        sourceGetterField: Int,
    ): Int? {
        val vtableRef = lexer.fields[vtableField].toInt()
        if (vtableRef == Value.REF_NULL_VALUE) {
            return null
        }
        val vtable = targetInstance.gcRefUnchecked(vtableRef) as? WasmStruct ?: return null
        if (vtable.fieldCount() <= sourceGetterField) {
            return null
        }
        val sourceGetterFuncId = vtable.fields[sourceGetterField].toInt()
        val sourceGetterFunc = targetInstance.function(sourceGetterFuncId) ?: return null
        if (fastFunctionKind(sourceGetterFuncId, sourceGetterFunc) != FAST_FUNCTION_CAST_STRUCT_GET) {
            return null
        }
        val sourceField = fastFunctionOperands3!![sourceGetterFuncId]
        if (lexer.fieldCount() <= sourceField) {
            return null
        }
        val sourceRef = lexer.fields[sourceField].toInt()
        if (sourceRef == Value.REF_NULL_VALUE) {
            return null
        }
        return sourceRef
    }

    private fun registerStringFromCharArray(
        targetInstance: Instance,
        sourceArray: WasmArray,
        start: Int,
        end: Int,
        resultArrayType: Int,
        stringType: Int,
        global0: Int,
        global1: Int,
        global2: Int,
    ): Int? {
        if (start < 0 || start > end || end > sourceArray.length()) {
            return null
        }

        val length = end - start
        var hash = 0
        for (index in start until end) {
            hash = (hash shl 5) - hash + (sourceArray.get(index).toInt() and 0xFFFF)
        }
        val cacheIndex = (hash * JSON_KEY_STRING_CACHE_MAGIC) ushr (32 - JSON_KEY_STRING_CACHE_BITS)
        val cachedRef = jsonKeyStringCacheRefs[cacheIndex]
        if (cachedRef != Value.REF_NULL_VALUE &&
            jsonKeyStringCacheHashes[cacheIndex] == hash &&
            jsonKeyStringCacheLengths[cacheIndex] == length &&
            cachedStringMatchesSourceChars(targetInstance, cachedRef, sourceArray, start, end)
        ) {
            return cachedRef
        }

        val elements = LongArray(length)
        sourceArray.copyInto(elements, endIndex = end, startIndex = start)
        val resultArrayRef = targetInstance.registerGcRef(WasmArray(resultArrayType, elements))
        val stringFields = LongArray(7)
        stringFields[0] = targetInstance.global(global0).valueLow
        stringFields[1] = targetInstance.global(global1).valueLow
        stringFields[2] = targetInstance.global(global2).valueLow
        stringFields[3] = hash.toLong()
        stringFields[4] = Value.REF_NULL_VALUE.toLong()
        stringFields[5] = length.toLong()
        stringFields[6] = resultArrayRef.toLong()

        val stringRef = targetInstance.registerGcRef(WasmStruct(stringType, stringFields))
        jsonKeyStringCacheRefs[cacheIndex] = stringRef
        jsonKeyStringCacheHashes[cacheIndex] = hash
        jsonKeyStringCacheLengths[cacheIndex] = length
        return stringRef
    }

    private fun cachedStringMatchesSourceChars(
        targetInstance: Instance,
        cachedRef: Int,
        sourceArray: WasmArray,
        start: Int,
        end: Int,
    ): Boolean {
        val cached = targetInstance.gcRef(cachedRef) as? WasmStruct ?: return false
        if (cached.fieldCount() <= STRING_CHARS_FIELD) {
            return false
        }
        if (cached.fields[STRING_LEFT_FIELD].toInt() != Value.REF_NULL_VALUE) {
            return false
        }
        val length = end - start
        if (cached.fields[STRING_LENGTH_FIELD].toInt() != length) {
            return false
        }
        val cachedArrayRef = cached.fields[STRING_CHARS_FIELD].toInt()
        if (cachedArrayRef == Value.REF_NULL_VALUE) {
            return false
        }
        val cachedArray = targetInstance.gcRef(cachedArrayRef) as? WasmArray ?: return false
        if (length > cachedArray.length()) {
            return false
        }
        var sourceIndex = start
        var cachedIndex = 0
        while (sourceIndex < end) {
            if ((sourceArray.get(sourceIndex).toInt() and 0xFFFF) !=
                (cachedArray.get(cachedIndex).toInt() and 0xFFFF)
            ) {
                return false
            }
            sourceIndex++
            cachedIndex++
        }
        return true
    }

    private fun fastStringEquals(
        targetInstance: Instance,
        selfRef: Int,
        otherRef: Int,
        stringType: Int,
        sourceHeapType: Int,
        hashField: Int,
        lengthField: Int,
        leftField: Int,
        charsField: Int,
    ): Boolean? {
        if (otherRef == Value.REF_NULL_VALUE) {
            return false
        }
        if (selfRef == otherRef) {
            return true
        }
        if (!targetInstance.heapTypeMatch(otherRef.toLong(), false, stringType, sourceHeapType)) {
            return false
        }
        if (selfRef == Value.REF_NULL_VALUE ||
            !targetInstance.heapTypeMatch(selfRef.toLong(), false, stringType, sourceHeapType)
        ) {
            return null
        }

        val self = targetInstance.gcRefUnchecked(selfRef) as? WasmStruct ?: return null
        val other = targetInstance.gcRefUnchecked(otherRef) as? WasmStruct ?: return null
        val maxField = maxOf(hashField, lengthField, leftField, charsField)
        if (self.fieldCount() <= maxField || other.fieldCount() <= maxField) {
            return null
        }

        val length = self.fields[lengthField].toInt()
        if (length != other.fields[lengthField].toInt()) {
            return false
        }
        if (length < 0) {
            return null
        }

        val selfHash = self.fields[hashField].toInt()
        val otherHash = other.fields[hashField].toInt()
        if (selfHash != otherHash && selfHash != 0 && otherHash != 0) {
            return false
        }

        if (self.fields[leftField].toInt() != Value.REF_NULL_VALUE ||
            other.fields[leftField].toInt() != Value.REF_NULL_VALUE
        ) {
            return null
        }

        val selfCharsRef = self.fields[charsField].toInt()
        val otherCharsRef = other.fields[charsField].toInt()
        if (selfCharsRef == Value.REF_NULL_VALUE || otherCharsRef == Value.REF_NULL_VALUE) {
            return null
        }
        val selfChars = targetInstance.gcRef(selfCharsRef) as? WasmArray ?: return null
        val otherChars = targetInstance.gcRef(otherCharsRef) as? WasmArray ?: return null
        if (length > selfChars.length() || length > otherChars.length()) {
            return null
        }

        for (index in 0 until length) {
            if ((selfChars.get(index).toInt() and 0xFFFF) !=
                (otherChars.get(index).toInt() and 0xFFFF)
            ) {
                return false
            }
        }
        return true
    }

    private fun tryReadRealSourceCodePointAscii(
        targetInstance: Instance,
        funcId: Int,
        sourceRef: Int,
    ): Int {
        if (sourceRef == Value.REF_NULL_VALUE) {
            return -1
        }
        val source = targetInstance.gcRefUnchecked(sourceRef) as? WasmStruct ?: return -1
        if (source.typeIdx() != fastFunctionOperands10!![funcId]) {
            return -1
        }
        val closedField = fastFunctionOperands!![funcId]
        val sourceBufferField = fastFunctionOperands2!![funcId]
        if (source.fields[closedField] != 0L) {
            return -1
        }
        val bufferRef = source.fields[sourceBufferField].toInt()
        if (bufferRef == Value.REF_NULL_VALUE) {
            return -1
        }
        val buffer = targetInstance.gcRefUnchecked(bufferRef) as? WasmStruct ?: return -1
        val bufferHeadField = fastFunctionOperands3!![funcId]
        val bufferSizeField = fastFunctionOperands4!![funcId]
        if (buffer.fieldCount() <= maxOf(bufferHeadField, bufferSizeField)) {
            return -1
        }
        if (buffer.fields[bufferSizeField] <= 0L) {
            return -1
        }
        val segmentRef = buffer.fields[bufferHeadField].toInt()
        if (segmentRef == Value.REF_NULL_VALUE) {
            return -1
        }
        val segment = targetInstance.gcRefUnchecked(segmentRef) as? WasmStruct ?: return -1
        val wrapperField = fastFunctionOperands5!![funcId]
        val positionField = fastFunctionOperands6!![funcId]
        val limitField = fastFunctionOperands9!![funcId]
        if (segment.fieldCount() <= maxOf(wrapperField, positionField, limitField)) {
            return -1
        }
        val position = segment.fields[positionField].toInt()
        val limit = segment.fields[limitField].toInt()
        if (position < 0 || position >= limit - 1) {
            return -1
        }
        val wrapperRef = segment.fields[wrapperField].toInt()
        if (wrapperRef == Value.REF_NULL_VALUE) {
            return -1
        }
        val wrapper = targetInstance.gcRefUnchecked(wrapperRef) as? WasmStruct ?: return -1
        val arrayField = fastFunctionOperands7!![funcId]
        if (wrapper.fieldCount() <= arrayField) {
            return -1
        }
        val arrayRef = wrapper.fields[arrayField].toInt()
        if (arrayRef == Value.REF_NULL_VALUE) {
            return -1
        }
        val array = targetInstance.gcRef(arrayRef) as? WasmArray ?: return -1
        if (position >= array.length()) {
            return -1
        }
        var value = array.get(position)
        when (fastFunctionOperands8!![funcId]) {
            0xFF -> value = value.toByte().toLong()
            0xFFFF -> value = value.toShort().toLong()
        }
        val byte = value.toInt()
        if ((byte and 0x80) != 0) {
            return -1
        }
        buffer.fields[bufferSizeField] = buffer.fields[bufferSizeField] - 1L
        segment.fields[positionField] = (position + 1).toLong()
        return byte
    }

    private fun fastInterfaceVtableRef(
        targetInstance: Instance,
        vtableFuncId: Int,
        objectRef: Int,
        interfaceId: Long,
    ): Int? {
        val vtableFunc = targetInstance.function(vtableFuncId) ?: return null
        if (fastFunctionKind(vtableFuncId, vtableFunc) != FAST_FUNCTION_INTERFACE_VTABLE_GET) {
            return null
        }
        val obj = targetInstance.gcRefUnchecked(objectRef) as? WasmStruct ?: return null
        val vtablesField = fastFunctionOperands!![vtableFuncId]
        val typeInfoField = fastFunctionOperands2!![vtableFuncId]
        if (obj.fieldCount() <= maxOf(vtablesField, typeInfoField)) {
            return null
        }
        val vtablesRef = obj.fields[vtablesField].toInt()
        val typeInfoRef = obj.fields[typeInfoField].toInt()
        if (vtablesRef == Value.REF_NULL_VALUE || typeInfoRef == Value.REF_NULL_VALUE) {
            return null
        }
        val typeInfo = targetInstance.gcRefUnchecked(typeInfoRef) as? WasmStruct ?: return null
        val interfaceIdsField = fastFunctionOperands3!![vtableFuncId]
        if (typeInfo.fieldCount() <= interfaceIdsField) {
            return null
        }
        val interfaceIdsRef = typeInfo.fields[interfaceIdsField].toInt()
        if (interfaceIdsRef == Value.REF_NULL_VALUE) {
            return null
        }
        val interfaceIds = targetInstance.gcRef(interfaceIdsRef) as? WasmArray ?: return null
        var vtableIndex = -1
        for (index in 0 until interfaceIds.length()) {
            if (interfaceIds.get(index) == interfaceId) {
                vtableIndex = index
                break
            }
        }
        if (vtableIndex < 0) {
            return null
        }
        val vtables = targetInstance.gcRef(vtablesRef) as? WasmArray ?: return null
        if (vtableIndex >= vtables.length()) {
            return null
        }
        return vtables.get(vtableIndex).toInt()
    }

    private fun fastFunctionKind(funcId: Int, func: FunctionBody): Int {
        val kinds =
            fastFunctionKinds ?: IntArray(instance.functionCount()).also {
                fastFunctionKinds = it
                fastFunctionOperands = IntArray(it.size)
                fastFunctionOperands2 = IntArray(it.size)
                fastFunctionOperands3 = IntArray(it.size)
                fastFunctionOperands4 = IntArray(it.size)
                fastFunctionOperands5 = IntArray(it.size)
                fastFunctionOperands6 = IntArray(it.size)
                fastFunctionOperands7 = IntArray(it.size)
                fastFunctionOperands8 = IntArray(it.size)
                fastFunctionOperands9 = IntArray(it.size)
                fastFunctionOperands10 = IntArray(it.size)
                fastFunctionOperands11 = IntArray(it.size)
                fastFunctionOperands12 = IntArray(it.size)
                fastFunctionOperands13 = IntArray(it.size)
                fastFunctionLongOperands = LongArray(it.size)
            }

        val cached = kinds[funcId]
        if (cached != FAST_FUNCTION_UNKNOWN) return cached

        val kind = analyzeFastFunction(funcId, func)
        kinds[funcId] = kind
        return kind
    }

    private fun analyzeFastFunction(funcId: Int, func: FunctionBody): Int {
        val type = instance.type(instance.functionType(funcId))
        val instructions = func.instructions()
        if (instructions.size == 3 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.RETURN &&
            instructions[2].opcode() == OpCode.END
        ) {
            return FAST_FUNCTION_IDENTITY
        }

        if (instructions.size == 4 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[2].opcode() == OpCode.RETURN &&
            instructions[3].opcode() == OpCode.END
        ) {
            when (instructions[1].opcode()) {
                OpCode.I64_EXTEND_I32_S -> return FAST_FUNCTION_I64_EXTEND_I32_S
                OpCode.I32_WRAP_I64 -> return FAST_FUNCTION_I32_WRAP_I64
                else -> {}
            }
        }

        if (instructions.size == 5 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.I32_CONST &&
            instructions[2].opcode() == OpCode.I32_AND &&
            instructions[3].opcode() == OpCode.RETURN &&
            instructions[4].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            return FAST_FUNCTION_I32_AND_CONST
        }

        if (instructions.size == 3 &&
            type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.GLOBAL_GET &&
            instructions[1].opcode() == OpCode.RETURN &&
            instructions[2].opcode() == OpCode.END
        ) {
            val globalId = instructions[0].operand(0).toInt()
            fastFunctionOperands!![funcId] = globalId
            return if (instance.global(globalId).type == ValType.V128) {
                FAST_FUNCTION_GLOBAL_GET
            } else {
                FAST_FUNCTION_GLOBAL_GET_LOW
            }
        }

        if (type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 0 &&
            instructions.size >= 4 &&
            instructions[0].opcode() == OpCode.GLOBAL_GET &&
            instructions[1].opcode() == OpCode.IF &&
            instructions[2].opcode() == OpCode.RETURN &&
            instructions[3].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[0].operand(0).toInt()
            return FAST_FUNCTION_INIT_FLAG_DONE
        }

        if (type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 0 &&
            instructions.size >= 5 &&
            instructions[0].opcode() == OpCode.GLOBAL_GET &&
            instructions[1].opcode() == OpCode.I32_EQZ &&
            instructions[2].opcode() == OpCode.IF
        ) {
            fastFunctionOperands!![funcId] = instructions[0].operand(0).toInt()
            return FAST_FUNCTION_INIT_FLAG_DONE
        }

        if (type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 0 &&
            instructions.size >= 5 &&
            instructions[0].opcode() == OpCode.GLOBAL_GET &&
            instructions[1].opcode() == OpCode.IF &&
            instructions[2].opcode() == OpCode.ELSE
        ) {
            fastFunctionOperands!![funcId] = instructions[0].operand(0).toInt()
            return FAST_FUNCTION_INIT_FLAG_DONE
        }

        if (type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 1 &&
            instructions.size >= 6 &&
            instructions[0].opcode() == OpCode.GLOBAL_GET &&
            instructions[1].opcode() == OpCode.REF_IS_NULL &&
            instructions[2].opcode() == OpCode.IF &&
            instructions[instructions.size - 3].opcode() == OpCode.GLOBAL_GET &&
            instructions[instructions.size - 3].operand(0).toInt() == instructions[0].operand(0).toInt() &&
            instructions[instructions.size - 2].opcode() == OpCode.RETURN &&
            instructions[instructions.size - 1].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[0].operand(0).toInt()
            return FAST_FUNCTION_LAZY_GLOBAL_GET_NON_NULL
        }

        if (instructions.size == 4 &&
            type.paramSlotCount() == 0 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.CALL &&
            instructions[1].opcode() == OpCode.GLOBAL_GET &&
            instructions[2].opcode() == OpCode.RETURN &&
            instructions[3].opcode() == OpCode.END
        ) {
            val initFuncId = instructions[0].operand(0).toInt()
            val initFunc = instance.function(initFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(initFuncId, initFunc) != FAST_FUNCTION_INIT_FLAG_DONE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = initFuncId
            fastFunctionOperands2!![funcId] = instructions[1].operand(0).toInt()
            return FAST_FUNCTION_INIT_THEN_GLOBAL_GET
        }

        if (instructions.size == 3 &&
            type.params().size == 1 &&
            type.params()[0] == ValType.I32 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            (instructions[1].opcode() == OpCode.I32_LOAD8_S || instructions[1].opcode() == OpCode.I32_LOAD8_U) &&
            instructions[2].opcode() == OpCode.END
        ) {
            val offset = instructions[1].operand(1)
            if (offset < 0 || offset >= Int.MAX_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = offset.toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(2).toInt()
            return if (instructions[1].opcode() == OpCode.I32_LOAD8_U) {
                FAST_FUNCTION_MEMORY_LOAD8_U
            } else {
                FAST_FUNCTION_MEMORY_LOAD8_S
            }
        }

        if (instructions.size == 4 &&
            type.params().size == 1 &&
            type.params()[0] == ValType.I32 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.CALL &&
            instructions[1].opcode() == OpCode.LOCAL_GET &&
            instructions[1].operand(0).toInt() == 0 &&
            (instructions[2].opcode() == OpCode.I32_LOAD8_S || instructions[2].opcode() == OpCode.I32_LOAD8_U) &&
            instructions[3].opcode() == OpCode.END
        ) {
            val initFuncId = instructions[0].operand(0).toInt()
            val initFunc = instance.function(initFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(initFuncId, initFunc) != FAST_FUNCTION_INIT_FLAG_DONE) {
                return FAST_FUNCTION_NONE
            }
            val offset = instructions[2].operand(1)
            if (offset < 0 || offset >= Int.MAX_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = initFuncId
            fastFunctionOperands2!![funcId] = offset.toInt()
            fastFunctionOperands3!![funcId] = instructions[2].operand(2).toInt()
            return if (instructions[2].opcode() == OpCode.I32_LOAD8_U) {
                FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_U
            } else {
                FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_S
            }
        }

        if (instructions.size == 4 &&
            type.params().size == 2 &&
            type.params()[0] == ValType.I32 &&
            type.params()[1] == ValType.I32 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.LOCAL_GET &&
            instructions[1].operand(0).toInt() == 1 &&
            instructions[2].opcode() == OpCode.I32_STORE8 &&
            instructions[3].opcode() == OpCode.END
        ) {
            val offset = instructions[2].operand(1)
            if (offset < 0 || offset >= Int.MAX_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = offset.toInt()
            fastFunctionOperands2!![funcId] = instructions[2].operand(2).toInt()
            return FAST_FUNCTION_MEMORY_STORE8
        }

        if (instructions.size == 5 &&
            type.params().size == 2 &&
            type.params()[0] == ValType.I32 &&
            type.params()[1] == ValType.I32 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.CALL &&
            instructions[1].opcode() == OpCode.LOCAL_GET &&
            instructions[1].operand(0).toInt() == 0 &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 1 &&
            instructions[3].opcode() == OpCode.I32_STORE8 &&
            instructions[4].opcode() == OpCode.END
        ) {
            val initFuncId = instructions[0].operand(0).toInt()
            val initFunc = instance.function(initFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(initFuncId, initFunc) != FAST_FUNCTION_INIT_FLAG_DONE) {
                return FAST_FUNCTION_NONE
            }
            val offset = instructions[3].operand(1)
            if (offset < 0 || offset >= Int.MAX_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = initFuncId
            fastFunctionOperands2!![funcId] = offset.toInt()
            fastFunctionOperands3!![funcId] = instructions[3].operand(2).toInt()
            return FAST_FUNCTION_INIT_THEN_MEMORY_STORE8
        }

        if (instructions.size == 5 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.LOCAL_GET &&
            instructions[1].operand(0).toInt() == 1 &&
            instructions[2].opcode() == OpCode.REF_EQ &&
            instructions[3].opcode() == OpCode.RETURN &&
            instructions[4].opcode() == OpCode.END
        ) {
            return FAST_FUNCTION_REF_EQ
        }

        if (instructions.size == 148 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 1 &&
            instructions[4].opcode() == OpCode.REF_IS_NULL &&
            instructions[9].opcode() == OpCode.LOCAL_GET &&
            instructions[9].operand(0).toInt() == 1 &&
            instructions[10].opcode() == OpCode.LOCAL_GET &&
            instructions[11].opcode() == OpCode.REF_EQ &&
            instructions[21].opcode() == OpCode.LOCAL_GET &&
            instructions[21].operand(0).toInt() == 1 &&
            instructions[22].opcode() == OpCode.REF_TEST &&
            instructions[39].opcode() == OpCode.LOCAL_GET &&
            instructions[39].operand(0).toInt() == 2 &&
            instructions[40].opcode() == OpCode.STRUCT_GET &&
            instructions[42].opcode() == OpCode.LOCAL_GET &&
            instructions[42].operand(0).toInt() == 3 &&
            instructions[43].opcode() == OpCode.STRUCT_GET &&
            instructions[53].opcode() == OpCode.LOCAL_GET &&
            instructions[53].operand(0).toInt() == 2 &&
            instructions[54].opcode() == OpCode.STRUCT_GET &&
            instructions[56].opcode() == OpCode.LOCAL_GET &&
            instructions[56].operand(0).toInt() == 1 &&
            instructions[57].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[58].opcode() == OpCode.STRUCT_GET &&
            instructions[84].opcode() == OpCode.LOCAL_GET &&
            instructions[84].operand(0).toInt() == 2 &&
            instructions[85].opcode() == OpCode.CALL &&
            instructions[87].opcode() == OpCode.LOCAL_GET &&
            instructions[87].operand(0).toInt() == 1 &&
            instructions[88].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[89].opcode() == OpCode.CALL &&
            instructions[117].opcode() == OpCode.LOCAL_GET &&
            instructions[118].opcode() == OpCode.LOCAL_GET &&
            instructions[119].opcode() == OpCode.ARRAY_GET_U &&
            instructions[120].opcode() == OpCode.LOCAL_GET &&
            instructions[121].opcode() == OpCode.LOCAL_GET &&
            instructions[122].opcode() == OpCode.ARRAY_GET_U &&
            instructions[145].opcode() == OpCode.I32_CONST &&
            instructions[145].operand(0).toInt() == 1 &&
            instructions[146].opcode() == OpCode.RETURN &&
            instructions[147].opcode() == OpCode.END
        ) {
            if (instructions[1].operand(0).toInt() != instructions[22].operand(0).toInt() ||
                instructions[1].operand(0).toInt() != instructions[57].operand(0).toInt() ||
                instructions[1].operand(0).toInt() != instructions[88].operand(0).toInt() ||
                instructions[1].operand(1).toInt() != instructions[22].operand(1).toInt() ||
                instructions[1].operand(1).toInt() != instructions[57].operand(1).toInt() ||
                instructions[1].operand(1).toInt() != instructions[88].operand(1).toInt() ||
                instructions[40].operand(1).toInt() != instructions[43].operand(1).toInt() ||
                instructions[54].operand(1).toInt() != instructions[58].operand(1).toInt() ||
                instructions[85].operand(0).toInt() != instructions[89].operand(0).toInt()
            ) {
                return FAST_FUNCTION_NONE
            }
            val getCharsFuncId = instructions[85].operand(0).toInt()
            val getCharsFunc = instance.function(getCharsFuncId) ?: return FAST_FUNCTION_NONE
            val getCharsInstructions = getCharsFunc.instructions()
            if (getCharsInstructions.size != 12 ||
                getCharsInstructions[0].opcode() != OpCode.LOCAL_GET ||
                getCharsInstructions[0].operand(0).toInt() != 0 ||
                getCharsInstructions[1].opcode() != OpCode.STRUCT_GET ||
                getCharsInstructions[2].opcode() != OpCode.REF_IS_NULL ||
                getCharsInstructions[3].opcode() != OpCode.I32_EQZ ||
                getCharsInstructions[4].opcode() != OpCode.IF ||
                getCharsInstructions[5].opcode() != OpCode.LOCAL_GET ||
                getCharsInstructions[5].operand(0).toInt() != 0 ||
                getCharsInstructions[6].opcode() != OpCode.CALL ||
                getCharsInstructions[7].opcode() != OpCode.END ||
                getCharsInstructions[8].opcode() != OpCode.LOCAL_GET ||
                getCharsInstructions[8].operand(0).toInt() != 0 ||
                getCharsInstructions[9].opcode() != OpCode.STRUCT_GET ||
                getCharsInstructions[10].opcode() != OpCode.RETURN ||
                getCharsInstructions[11].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[54].operand(1).toInt()
            fastFunctionOperands4!![funcId] = instructions[40].operand(1).toInt()
            fastFunctionOperands5!![funcId] = getCharsInstructions[1].operand(1).toInt()
            fastFunctionOperands6!![funcId] = getCharsInstructions[9].operand(1).toInt()
            return FAST_FUNCTION_STRING_EQUALS
        }

        if (instructions.size == 4 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.RETURN &&
            instructions[3].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            return FAST_FUNCTION_STRUCT_GET
        }

        if (instructions.size == 6 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.RETURN &&
            instructions[5].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[3].operand(1).toInt()
            return FAST_FUNCTION_CAST_STRUCT_GET
        }

        if (instructions.size == 6 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 1 &&
            (
                instructions[3].opcode() == OpCode.ARRAY_GET ||
                    instructions[3].opcode() == OpCode.ARRAY_GET_S ||
                    instructions[3].opcode() == OpCode.ARRAY_GET_U
            ) &&
            instructions[4].opcode() == OpCode.RETURN &&
            instructions[5].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[1].operand(1).toInt()
            val arrayTypeIdx = instructions[3].operand(0).toInt()
            val arrayType = instance.module().typeSection().getSubType(arrayTypeIdx).compType().arrayType()!!
            fastFunctionOperands2!![funcId] =
                arrayType.fieldType().storageType().packedType()?.mask()?.toInt() ?: 0
            return when (instructions[3].opcode()) {
                OpCode.ARRAY_GET_S -> FAST_FUNCTION_ARRAY_WRAPPER_GET_S
                OpCode.ARRAY_GET_U -> FAST_FUNCTION_ARRAY_WRAPPER_GET_U
                else -> FAST_FUNCTION_ARRAY_WRAPPER_GET
            }
        }

        if (instructions.size == 7 &&
            type.params().size == 3 &&
            type.paramSlotCount() == 3 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 1 &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 2 &&
            instructions[4].opcode() == OpCode.ARRAY_SET &&
            instructions[5].opcode() == OpCode.NOP &&
            instructions[6].opcode() == OpCode.END
        ) {
            fastFunctionOperands!![funcId] = instructions[1].operand(1).toInt()
            val arrayTypeIdx = instructions[4].operand(0).toInt()
            val arrayType = instance.module().typeSection().getSubType(arrayTypeIdx).compType().arrayType()!!
            fastFunctionOperands2!![funcId] =
                arrayType.fieldType().storageType().packedType()?.mask()?.toInt() ?: 0
            return FAST_FUNCTION_ARRAY_WRAPPER_SET
        }

        if (instructions.size == 9 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 0 &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.LOCAL_GET &&
            instructions[4].operand(0).toInt() == 1 &&
            instructions[5].opcode() == OpCode.I32_ADD &&
            instructions[6].opcode() == OpCode.CALL &&
            instructions[7].opcode() == OpCode.RETURN &&
            instructions[8].opcode() == OpCode.END
        ) {
            val calledFuncId = instructions[6].operand(0).toInt()
            val calledFunc = instance.function(calledFuncId) ?: return FAST_FUNCTION_NONE
            val calledKind = fastFunctionKind(calledFuncId, calledFunc)
            if (calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET &&
                calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET_S &&
                calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET_U
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands2!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands3!![funcId] = fastFunctionOperands!![calledFuncId]
            fastFunctionOperands4!![funcId] = fastFunctionOperands2!![calledFuncId]
            return when (calledKind) {
                FAST_FUNCTION_ARRAY_WRAPPER_GET_S -> FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_S
                FAST_FUNCTION_ARRAY_WRAPPER_GET_U -> FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_U
                else -> FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET
            }
        }

        if (instructions.size == 8 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.LOCAL_GET &&
            instructions[4].operand(0).toInt() == 1 &&
            instructions[5].opcode() == OpCode.CALL &&
            instructions[6].opcode() == OpCode.RETURN &&
            instructions[7].opcode() == OpCode.END
        ) {
            val calledFuncId = instructions[5].operand(0).toInt()
            val calledFunc = instance.function(calledFuncId) ?: return FAST_FUNCTION_NONE
            val calledKind = fastFunctionKind(calledFuncId, calledFunc)
            if (calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET &&
                calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET_S &&
                calledKind != FAST_FUNCTION_ARRAY_WRAPPER_GET_U
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands4!![funcId] = fastFunctionOperands!![calledFuncId]
            fastFunctionOperands5!![funcId] = fastFunctionOperands2!![calledFuncId]
            return when (calledKind) {
                FAST_FUNCTION_ARRAY_WRAPPER_GET_S -> FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_S
                FAST_FUNCTION_ARRAY_WRAPPER_GET_U -> FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_U
                else -> FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET
            }
        }

        if (instructions.size == 40 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.ARRAY_LEN &&
            instructions[2].opcode() == OpCode.LOCAL_SET &&
            instructions[3].opcode() == OpCode.I32_CONST &&
            instructions[3].operand(0).toInt() == 0 &&
            instructions[4].opcode() == OpCode.LOCAL_SET &&
            instructions[5].opcode() == OpCode.LOOP &&
            instructions[6].opcode() == OpCode.BLOCK &&
            instructions[7].opcode() == OpCode.LOCAL_GET &&
            instructions[8].opcode() == OpCode.LOCAL_GET &&
            instructions[9].opcode() == OpCode.I32_LT_S &&
            instructions[10].opcode() == OpCode.I32_EQZ &&
            instructions[11].opcode() == OpCode.BR_IF &&
            instructions[12].opcode() == OpCode.LOCAL_GET &&
            instructions[12].operand(0).toInt() == 0 &&
            instructions[13].opcode() == OpCode.LOCAL_GET &&
            instructions[14].opcode() == OpCode.ARRAY_GET &&
            instructions[15].opcode() == OpCode.LOCAL_TEE &&
            instructions[16].opcode() == OpCode.LOCAL_GET &&
            instructions[16].operand(0).toInt() == 1 &&
            instructions[17].opcode() == OpCode.I64_EQ &&
            instructions[18].opcode() == OpCode.IF &&
            instructions[19].opcode() == OpCode.LOCAL_GET &&
            instructions[20].opcode() == OpCode.RETURN &&
            instructions[21].opcode() == OpCode.END &&
            instructions[22].opcode() == OpCode.LOCAL_GET &&
            instructions[23].opcode() == OpCode.LOCAL_TEE &&
            instructions[24].opcode() == OpCode.LOCAL_SET &&
            instructions[25].opcode() == OpCode.BLOCK &&
            instructions[26].opcode() == OpCode.NOP &&
            instructions[27].opcode() == OpCode.LOCAL_GET &&
            instructions[28].opcode() == OpCode.LOCAL_TEE &&
            instructions[29].opcode() == OpCode.I32_CONST &&
            instructions[29].operand(0).toInt() == 1 &&
            instructions[30].opcode() == OpCode.I32_ADD &&
            instructions[31].opcode() == OpCode.BR &&
            instructions[32].opcode() == OpCode.END &&
            instructions[33].opcode() == OpCode.LOCAL_SET &&
            instructions[34].opcode() == OpCode.BR &&
            instructions[35].opcode() == OpCode.END &&
            instructions[36].opcode() == OpCode.END &&
            instructions[37].opcode() == OpCode.I32_CONST &&
            instructions[37].operand(0).toInt() == -1 &&
            instructions[38].opcode() == OpCode.RETURN &&
            instructions[39].opcode() == OpCode.END
        ) {
            return FAST_FUNCTION_ARRAY_ANY_INDEX_OF_VALUE
        }

        if (instructions.size == 13 &&
            type.params().size == 1 &&
            type.params()[0] == ValType.I32 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.LOCAL_SET &&
            instructions[2].opcode() == OpCode.BLOCK &&
            instructions[3].opcode() == OpCode.NOP &&
            instructions[4].opcode() == OpCode.LOCAL_GET &&
            instructions[4].operand(0).toInt() == instructions[1].operand(0).toInt() &&
            instructions[5].opcode() == OpCode.LOCAL_TEE &&
            instructions[6].opcode() == OpCode.CALL &&
            instructions[7].opcode() == OpCode.BR &&
            instructions[7].operand(0).toInt() == 0 &&
            instructions[8].opcode() == OpCode.END &&
            instructions[9].opcode() == OpCode.CALL &&
            instructions[10].opcode() == OpCode.I32_LOAD8_S &&
            instructions[11].opcode() == OpCode.RETURN &&
            instructions[12].opcode() == OpCode.END
        ) {
            val firstCalledFuncId = instructions[6].operand(0).toInt()
            val firstCalledFunc = instance.function(firstCalledFuncId) ?: return FAST_FUNCTION_NONE
            val secondCalledFuncId = instructions[9].operand(0).toInt()
            val secondCalledFunc = instance.function(secondCalledFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(firstCalledFuncId, firstCalledFunc) != FAST_FUNCTION_IDENTITY ||
                fastFunctionKind(secondCalledFuncId, secondCalledFunc) != FAST_FUNCTION_IDENTITY
            ) {
                return FAST_FUNCTION_NONE
            }
            val offset = instructions[10].operand(1)
            if (offset < 0 || offset >= Int.MAX_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = offset.toInt()
            fastFunctionOperands2!![funcId] = instructions[10].operand(2).toInt()
            return FAST_FUNCTION_MEMORY_LOAD8_S
        }

        if (instructions.size == 266 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[6].opcode() == OpCode.LOCAL_GET &&
            instructions[6].operand(0).toInt() == 1 &&
            instructions[7].opcode() == OpCode.LOCAL_GET &&
            instructions[7].operand(0).toInt() == 0 &&
            instructions[8].opcode() == OpCode.CALL &&
            instructions[39].opcode() == OpCode.LOCAL_GET &&
            instructions[39].operand(0).toInt() == 1 &&
            instructions[40].opcode() == OpCode.I64_CONST &&
            instructions[40].operand(0) == 0L &&
            instructions[41].opcode() == OpCode.I64_EQ &&
            instructions[42].opcode() == OpCode.IF &&
            instructions[43].opcode() == OpCode.LOCAL_GET &&
            instructions[43].operand(0).toInt() == 0 &&
            instructions[44].opcode() == OpCode.STRUCT_GET &&
            instructions[45].opcode() == OpCode.LOCAL_TEE &&
            instructions[46].opcode() == OpCode.REF_IS_NULL &&
            instructions[53].opcode() == OpCode.I32_CONST &&
            instructions[53].operand(0).toInt() == 0 &&
            instructions[54].opcode() == OpCode.CALL &&
            instructions[55].opcode() == OpCode.RETURN &&
            instructions[56].opcode() == OpCode.END
        ) {
            val sizeFuncId = instructions[8].operand(0).toInt()
            val sizeFunc = instance.function(sizeFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(sizeFuncId, sizeFunc) != FAST_FUNCTION_STRUCT_GET) {
                return FAST_FUNCTION_NONE
            }
            val sizeType = instance.type(instance.functionType(sizeFuncId))
            if (sizeType.returnSlotCount() != 1 ||
                sizeType.returns().size != 1 ||
                sizeType.returns()[0] != ValType.I64
            ) {
                return FAST_FUNCTION_NONE
            }

            val segmentGetFuncId = instructions[54].operand(0).toInt()
            val segmentGetFunc = instance.function(segmentGetFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(segmentGetFuncId, segmentGetFunc) != FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_S) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = instructions[44].operand(1).toInt()
            fastFunctionOperands2!![funcId] = fastFunctionOperands2!![sizeFuncId]
            fastFunctionOperands3!![funcId] = fastFunctionOperands!![segmentGetFuncId]
            fastFunctionOperands4!![funcId] = fastFunctionOperands2!![segmentGetFuncId]
            fastFunctionOperands5!![funcId] = fastFunctionOperands3!![segmentGetFuncId]
            fastFunctionOperands6!![funcId] = fastFunctionOperands4!![segmentGetFuncId]
            return FAST_FUNCTION_BUFFER_GET_ZERO
        }

        if (instructions.size == 157 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_SET &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 1 &&
            instructions[52].opcode() == OpCode.LOCAL_GET &&
            instructions[52].operand(0).toInt() == 1 &&
            instructions[54].opcode() == OpCode.LOOP &&
            instructions[56].opcode() == OpCode.LOCAL_GET &&
            instructions[57].opcode() == OpCode.I64_CONST &&
            instructions[57].operand(0) == 0L &&
            instructions[58].opcode() == OpCode.I64_GT_S &&
            instructions[61].opcode() == OpCode.LOCAL_GET &&
            instructions[62].opcode() == OpCode.STRUCT_GET &&
            instructions[87].opcode() == OpCode.LOCAL_GET &&
            instructions[88].opcode() == OpCode.STRUCT_GET &&
            instructions[89].opcode() == OpCode.LOCAL_GET &&
            instructions[90].opcode() == OpCode.STRUCT_GET &&
            instructions[91].opcode() == OpCode.I32_SUB &&
            instructions[124].opcode() == OpCode.LOCAL_GET &&
            instructions[125].opcode() == OpCode.LOCAL_GET &&
            instructions[126].opcode() == OpCode.STRUCT_GET &&
            instructions[129].opcode() == OpCode.I64_SUB &&
            instructions[130].opcode() == OpCode.STRUCT_SET &&
            instructions[136].opcode() == OpCode.LOCAL_GET &&
            instructions[137].opcode() == OpCode.LOCAL_TEE &&
            instructions[138].opcode() == OpCode.LOCAL_GET &&
            instructions[139].opcode() == OpCode.STRUCT_GET &&
            instructions[141].opcode() == OpCode.I32_ADD &&
            instructions[142].opcode() == OpCode.STRUCT_SET &&
            instructions[143].opcode() == OpCode.LOCAL_GET &&
            instructions[144].opcode() == OpCode.STRUCT_GET &&
            instructions[145].opcode() == OpCode.LOCAL_GET &&
            instructions[146].opcode() == OpCode.STRUCT_GET &&
            instructions[147].opcode() == OpCode.I32_EQ &&
            instructions[148].opcode() == OpCode.IF &&
            instructions[149].opcode() == OpCode.LOCAL_GET &&
            instructions[150].opcode() == OpCode.CALL &&
            instructions[151].opcode() == OpCode.END
        ) {
            val positionField = instructions[90].operand(1).toInt()
            val limitField = instructions[88].operand(1).toInt()
            val sizeField = instructions[126].operand(1).toInt()
            if (instructions[139].operand(1).toInt() != positionField ||
                instructions[142].operand(1).toInt() != positionField ||
                instructions[144].operand(1).toInt() != positionField ||
                instructions[146].operand(1).toInt() != limitField ||
                instructions[130].operand(1).toInt() != sizeField
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[62].operand(1).toInt()
            fastFunctionOperands2!![funcId] = sizeField
            fastFunctionOperands3!![funcId] = positionField
            fastFunctionOperands4!![funcId] = limitField
            return FAST_FUNCTION_BUFFER_SKIP_ONE
        }

        if (instructions.size == 74 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_SET &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 1 &&
            instructions[4].opcode() == OpCode.I64_CONST &&
            instructions[4].operand(0) == 0L &&
            instructions[5].opcode() == OpCode.I64_GE_S &&
            instructions[40].opcode() == OpCode.LOCAL_GET &&
            instructions[41].opcode() == OpCode.CALL &&
            instructions[42].opcode() == OpCode.LOCAL_GET &&
            instructions[42].operand(0).toInt() == 1 &&
            instructions[43].opcode() == OpCode.I64_LT_S &&
            instructions[44].opcode() == OpCode.IF &&
            instructions[72].opcode() == OpCode.NOP &&
            instructions[73].opcode() == OpCode.END
        ) {
            val sizeFuncId = instructions[41].operand(0).toInt()
            val sizeFunc = instance.function(sizeFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(sizeFuncId, sizeFunc) != FAST_FUNCTION_STRUCT_GET) {
                return FAST_FUNCTION_NONE
            }
            val sizeType = instance.type(instance.functionType(sizeFuncId))
            if (sizeType.returnSlotCount() != 1 ||
                sizeType.returns().size != 1 ||
                sizeType.returns()[0] != ValType.I64
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = fastFunctionOperands2!![sizeFuncId]
            return FAST_FUNCTION_BUFFER_REQUIRE_ONE
        }

        if (instructions.size == 25 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 0 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 1 &&
            instructions[4].opcode() == OpCode.CALL &&
            instructions[5].opcode() == OpCode.I32_EQZ &&
            instructions[6].opcode() == OpCode.IF &&
            instructions[23].opcode() == OpCode.NOP &&
            instructions[24].opcode() == OpCode.END
        ) {
            val requestFuncId = instructions[4].operand(0).toInt()
            val requestFunc = instance.function(requestFuncId) ?: return FAST_FUNCTION_NONE
            val requestType = instance.type(instance.functionType(requestFuncId))
            val requestInstructions = requestFunc.instructions()
            if (requestInstructions.size != 121 ||
                requestType.params().size != 2 ||
                requestType.paramSlotCount() != 2 ||
                requestType.returnSlotCount() != 1 ||
                requestType.returns().size != 1 ||
                requestType.returns()[0] != ValType.I32 ||
                requestInstructions[0].opcode() != OpCode.LOCAL_GET ||
                requestInstructions[0].operand(0).toInt() != 0 ||
                requestInstructions[1].opcode() != OpCode.CAST_TEST_NULL ||
                requestInstructions[2].opcode() != OpCode.LOCAL_TEE ||
                requestInstructions[8].opcode() != OpCode.STRUCT_GET_S ||
                requestInstructions[80].opcode() != OpCode.LOCAL_GET ||
                requestInstructions[81].opcode() != OpCode.STRUCT_GET ||
                requestInstructions[82].opcode() != OpCode.CALL ||
                requestInstructions[83].opcode() != OpCode.LOCAL_GET ||
                requestInstructions[83].operand(0).toInt() != 1 ||
                requestInstructions[84].opcode() != OpCode.I64_LT_S ||
                requestInstructions[118].opcode() != OpCode.I32_CONST ||
                requestInstructions[118].operand(0).toInt() != 1 ||
                requestInstructions[119].opcode() != OpCode.RETURN ||
                requestInstructions[120].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            val sizeFuncId = requestInstructions[82].operand(0).toInt()
            val sizeFunc = instance.function(sizeFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(sizeFuncId, sizeFunc) != FAST_FUNCTION_STRUCT_GET) {
                return FAST_FUNCTION_NONE
            }
            val sizeType = instance.type(instance.functionType(sizeFuncId))
            if (sizeType.returnSlotCount() != 1 ||
                sizeType.returns().size != 1 ||
                sizeType.returns()[0] != ValType.I64
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = requestInstructions[8].operand(1).toInt()
            fastFunctionOperands2!![funcId] = requestInstructions[81].operand(1).toInt()
            fastFunctionOperands3!![funcId] = fastFunctionOperands2!![sizeFuncId]
            fastFunctionOperands4!![funcId] = instructions[1].operand(0).toInt()
            return FAST_FUNCTION_REAL_SOURCE_REQUIRE_ONE
        }

        if (instructions.size == 328 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.I64_CONST &&
            instructions[1].operand(0) == 1L &&
            instructions[2].opcode() == OpCode.CALL &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 0 &&
            instructions[4].opcode() == OpCode.I64_CONST &&
            instructions[4].operand(0) == 0L &&
            instructions[5].opcode() == OpCode.CALL &&
            instructions[8].opcode() == OpCode.I32_CONST &&
            instructions[8].operand(0).toInt() == 128 &&
            instructions[23].opcode() == OpCode.I32_EQ &&
            instructions[24].opcode() == OpCode.IF &&
            instructions[293].opcode() == OpCode.LOCAL_GET &&
            instructions[293].operand(0).toInt() == 0 &&
            instructions[294].opcode() == OpCode.LOCAL_GET &&
            instructions[294].operand(0).toInt() == 3 &&
            instructions[296].opcode() == OpCode.CALL &&
            instructions[326].opcode() == OpCode.RETURN &&
            instructions[327].opcode() == OpCode.END
        ) {
            val requireFuncId = instructions[2].operand(0).toInt()
            val requireFunc = instance.function(requireFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(requireFuncId, requireFunc) != FAST_FUNCTION_BUFFER_REQUIRE_ONE) {
                return FAST_FUNCTION_NONE
            }
            val getFuncId = instructions[5].operand(0).toInt()
            val getFunc = instance.function(getFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(getFuncId, getFunc) != FAST_FUNCTION_BUFFER_GET_ZERO) {
                return FAST_FUNCTION_NONE
            }
            val skipFuncId = instructions[296].operand(0).toInt()
            val skipFunc = instance.function(skipFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(skipFuncId, skipFunc) != FAST_FUNCTION_BUFFER_SKIP_ONE) {
                return FAST_FUNCTION_NONE
            }
            if (fastFunctionOperands!![requireFuncId] != fastFunctionOperands2!![getFuncId] ||
                fastFunctionOperands2!![skipFuncId] != fastFunctionOperands2!![getFuncId] ||
                fastFunctionOperands3!![skipFuncId] != fastFunctionOperands4!![getFuncId]
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = fastFunctionOperands!![getFuncId]
            fastFunctionOperands2!![funcId] = fastFunctionOperands2!![getFuncId]
            fastFunctionOperands3!![funcId] = fastFunctionOperands3!![getFuncId]
            fastFunctionOperands4!![funcId] = fastFunctionOperands4!![getFuncId]
            fastFunctionOperands5!![funcId] = fastFunctionOperands5!![getFuncId]
            fastFunctionOperands6!![funcId] = fastFunctionOperands6!![getFuncId]
            fastFunctionOperands7!![funcId] = fastFunctionOperands4!![skipFuncId]
            return FAST_FUNCTION_BUFFER_READ_UTF8_CODE_POINT_ASCII
        }

        if (instructions.size == 83 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.REF_TEST &&
            instructions[2].opcode() == OpCode.IF &&
            instructions[5].opcode() == OpCode.CALL &&
            instructions[8].opcode() == OpCode.LOCAL_GET &&
            instructions[8].operand(0).toInt() == 0 &&
            instructions[9].opcode() == OpCode.I64_CONST &&
            instructions[9].operand(0) == 1L &&
            instructions[15].opcode() == OpCode.CALL_REF &&
            instructions[24].opcode() == OpCode.CALL &&
            instructions[73].opcode() == OpCode.LOCAL_GET &&
            instructions[73].operand(0).toInt() == 0 &&
            instructions[80].opcode() == OpCode.CALL &&
            instructions[81].opcode() == OpCode.RETURN &&
            instructions[82].opcode() == OpCode.END
        ) {
            val commonReadFuncId = instructions[80].operand(0).toInt()
            val commonReadFunc = instance.function(commonReadFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(commonReadFuncId, commonReadFunc) !=
                FAST_FUNCTION_BUFFER_READ_UTF8_CODE_POINT_ASCII
            ) {
                return FAST_FUNCTION_NONE
            }

            var requireFuncId = -1
            for (candidateFuncId in 0 until instance.functionCount()) {
                if (candidateFuncId == funcId) {
                    continue
                }
                val candidateFunc = instance.function(candidateFuncId) ?: continue
                if (fastFunctionKind(candidateFuncId, candidateFunc) == FAST_FUNCTION_REAL_SOURCE_REQUIRE_ONE) {
                    requireFuncId = candidateFuncId
                    break
                }
            }
            if (requireFuncId < 0 ||
                fastFunctionOperands3!![requireFuncId] != fastFunctionOperands2!![commonReadFuncId]
            ) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = fastFunctionOperands!![requireFuncId]
            fastFunctionOperands2!![funcId] = fastFunctionOperands2!![requireFuncId]
            fastFunctionOperands3!![funcId] = fastFunctionOperands!![commonReadFuncId]
            fastFunctionOperands4!![funcId] = fastFunctionOperands2!![commonReadFuncId]
            fastFunctionOperands5!![funcId] = fastFunctionOperands3!![commonReadFuncId]
            fastFunctionOperands6!![funcId] = fastFunctionOperands4!![commonReadFuncId]
            fastFunctionOperands7!![funcId] = fastFunctionOperands5!![commonReadFuncId]
            fastFunctionOperands8!![funcId] = fastFunctionOperands6!![commonReadFuncId]
            fastFunctionOperands9!![funcId] = fastFunctionOperands7!![commonReadFuncId]
            fastFunctionOperands10!![funcId] = fastFunctionOperands4!![requireFuncId]
            return FAST_FUNCTION_REAL_SOURCE_READ_CODE_POINT_ASCII
        }

        if (instructions.size == 7 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.CALL &&
            instructions[5].opcode() == OpCode.RETURN &&
            instructions[6].opcode() == OpCode.END
        ) {
            val readFuncId = instructions[4].operand(0).toInt()
            val readFunc = instance.function(readFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(readFuncId, readFunc) != FAST_FUNCTION_REAL_SOURCE_READ_CODE_POINT_ASCII) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands3!![funcId] = readFuncId
            return FAST_FUNCTION_IO_SERIAL_READER_NEXT_CODE_POINT_ASCII
        }

        if (instructions.size == 13 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.LOCAL_TEE &&
            instructions[5].opcode() == OpCode.LOCAL_GET &&
            instructions[5].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[6].opcode() == OpCode.I64_CONST &&
            instructions[7].opcode() == OpCode.CALL &&
            instructions[8].opcode() == OpCode.CAST_TEST &&
            instructions[9].opcode() == OpCode.STRUCT_GET &&
            instructions[10].opcode() == OpCode.CALL_REF &&
            instructions[11].opcode() == OpCode.RETURN &&
            instructions[12].opcode() == OpCode.END
        ) {
            val vtableFuncId = instructions[7].operand(0).toInt()
            val vtableFunc = instance.function(vtableFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(vtableFuncId, vtableFunc) != FAST_FUNCTION_INTERFACE_VTABLE_GET) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands3!![funcId] = vtableFuncId
            fastFunctionOperands4!![funcId] = instructions[8].operand(0).toInt()
            fastFunctionOperands5!![funcId] = instructions[9].operand(1).toInt()
            fastFunctionLongOperands!![funcId] = instructions[6].operand(0)
            return FAST_FUNCTION_IO_SERIAL_READER_EXHAUSTED_NONEMPTY
        }

        if (instructions.size == 90 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.LOCAL_SET &&
            instructions[5].opcode() == OpCode.LOOP &&
            instructions[10].opcode() == OpCode.LOCAL_GET &&
            instructions[10].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[11].opcode() == OpCode.LOCAL_GET &&
            instructions[11].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[12].opcode() == OpCode.LOCAL_GET &&
            instructions[12].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[13].opcode() == OpCode.STRUCT_GET &&
            instructions[14].opcode() == OpCode.STRUCT_GET &&
            instructions[15].opcode() == OpCode.CALL_REF &&
            instructions[16].opcode() == OpCode.LOCAL_TEE &&
            instructions[16].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[17].opcode() == OpCode.I32_CONST &&
            instructions[17].operand(0).toInt() == -1 &&
            instructions[18].opcode() == OpCode.I32_EQ &&
            instructions[22].opcode() == OpCode.LOCAL_GET &&
            instructions[22].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[23].opcode() == OpCode.LOCAL_GET &&
            instructions[23].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[24].opcode() == OpCode.STRUCT_GET &&
            instructions[25].opcode() == OpCode.STRUCT_GET &&
            instructions[26].opcode() == OpCode.CALL_REF &&
            instructions[27].opcode() == OpCode.LOCAL_GET &&
            instructions[27].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[28].opcode() == OpCode.CALL &&
            instructions[84].opcode() == OpCode.LOCAL_GET &&
            instructions[84].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[85].opcode() == OpCode.LOCAL_GET &&
            instructions[85].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[86].opcode() == OpCode.STRUCT_SET &&
            instructions[87].opcode() == OpCode.LOCAL_GET &&
            instructions[87].operand(0).toInt() == instructions[4].operand(0).toInt() &&
            instructions[88].opcode() == OpCode.RETURN &&
            instructions[89].opcode() == OpCode.END
        ) {
            val sequenceGetFuncId = instructions[28].operand(0).toInt()
            val sequenceGetFunc = instance.function(sequenceGetFuncId) ?: return FAST_FUNCTION_NONE
            val sequenceGetInstructions = sequenceGetFunc.instructions()
            if (sequenceGetInstructions.size != 8 ||
                sequenceGetInstructions[1].opcode() != OpCode.CAST_TEST_NULL ||
                sequenceGetInstructions[3].opcode() != OpCode.STRUCT_GET ||
                sequenceGetInstructions[5].opcode() != OpCode.CALL
            ) {
                return FAST_FUNCTION_NONE
            }
            val charArrayGetFuncId = sequenceGetInstructions[5].operand(0).toInt()
            val charArrayGetFunc = instance.function(charArrayGetFuncId) ?: return FAST_FUNCTION_NONE
            val charArrayGetInstructions = charArrayGetFunc.instructions()
            if (charArrayGetInstructions.size != 6 ||
                charArrayGetInstructions[1].opcode() != OpCode.STRUCT_GET ||
                charArrayGetInstructions[3].opcode() != OpCode.ARRAY_GET_U
            ) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands4!![funcId] = instructions[24].operand(1).toInt()
            fastFunctionOperands5!![funcId] = instructions[25].operand(1).toInt()
            fastFunctionOperands6!![funcId] = sequenceGetInstructions[3].operand(1).toInt()
            fastFunctionOperands7!![funcId] = charArrayGetInstructions[1].operand(1).toInt()
            fastFunctionOperands8!![funcId] = fastFunctionOperands6!![funcId] + 1
            return FAST_FUNCTION_READER_JSON_LEXER_SKIP_WHITESPACES_NON_WS
        }

        if (instructions.size == 46 &&
            type.params().size == 3 &&
            type.paramSlotCount() == 3 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[4].opcode() == OpCode.STRUCT_GET &&
            instructions[5].opcode() == OpCode.STRUCT_GET &&
            instructions[6].opcode() == OpCode.CALL_REF &&
            instructions[7].opcode() == OpCode.LOCAL_SET &&
            instructions[8].opcode() == OpCode.LOCAL_GET &&
            instructions[8].operand(0).toInt() == 2 &&
            instructions[10].opcode() == OpCode.LOCAL_GET &&
            instructions[10].operand(0).toInt() == instructions[7].operand(0).toInt() &&
            instructions[11].opcode() == OpCode.STRUCT_GET &&
            instructions[13].opcode() == OpCode.LOCAL_GET &&
            instructions[14].opcode() == OpCode.LOCAL_GET &&
            instructions[15].opcode() == OpCode.I32_LT_S &&
            instructions[20].opcode() == OpCode.LOCAL_GET &&
            instructions[22].opcode() == OpCode.LOCAL_GET &&
            instructions[23].opcode() == OpCode.I32_CONST &&
            instructions[23].operand(0).toInt() == 1 &&
            instructions[24].opcode() == OpCode.I32_ADD &&
            instructions[26].opcode() == OpCode.LOCAL_GET &&
            instructions[26].operand(0).toInt() == instructions[7].operand(0).toInt() &&
            instructions[27].opcode() == OpCode.LOCAL_GET &&
            instructions[28].opcode() == OpCode.CALL &&
            instructions[29].opcode() == OpCode.LOCAL_GET &&
            instructions[29].operand(0).toInt() == 1 &&
            instructions[30].opcode() == OpCode.I32_EQ &&
            instructions[32].opcode() == OpCode.LOCAL_GET &&
            instructions[33].opcode() == OpCode.RETURN &&
            instructions[43].opcode() == OpCode.I32_CONST &&
            instructions[43].operand(0).toInt() == -1 &&
            instructions[44].opcode() == OpCode.RETURN &&
            instructions[45].opcode() == OpCode.END
        ) {
            val sequenceGetFuncId = instructions[28].operand(0).toInt()
            val sequenceGetFunc = instance.function(sequenceGetFuncId) ?: return FAST_FUNCTION_NONE
            val sequenceGetInstructions = sequenceGetFunc.instructions()
            if (sequenceGetInstructions.size != 8 ||
                sequenceGetInstructions[0].opcode() != OpCode.LOCAL_GET ||
                sequenceGetInstructions[0].operand(0).toInt() != 0 ||
                sequenceGetInstructions[1].opcode() != OpCode.CAST_TEST_NULL ||
                sequenceGetInstructions[2].opcode() != OpCode.LOCAL_TEE ||
                sequenceGetInstructions[3].opcode() != OpCode.STRUCT_GET ||
                sequenceGetInstructions[4].opcode() != OpCode.LOCAL_GET ||
                sequenceGetInstructions[4].operand(0).toInt() != 1 ||
                sequenceGetInstructions[5].opcode() != OpCode.CALL ||
                sequenceGetInstructions[6].opcode() != OpCode.RETURN ||
                sequenceGetInstructions[7].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            val charArrayGetFuncId = sequenceGetInstructions[5].operand(0).toInt()
            val charArrayGetFunc = instance.function(charArrayGetFuncId) ?: return FAST_FUNCTION_NONE
            val charArrayGetInstructions = charArrayGetFunc.instructions()
            if (charArrayGetInstructions.size != 6 ||
                charArrayGetInstructions[0].opcode() != OpCode.LOCAL_GET ||
                charArrayGetInstructions[0].operand(0).toInt() != 0 ||
                charArrayGetInstructions[1].opcode() != OpCode.STRUCT_GET ||
                charArrayGetInstructions[2].opcode() != OpCode.LOCAL_GET ||
                charArrayGetInstructions[2].operand(0).toInt() != 1 ||
                charArrayGetInstructions[3].opcode() != OpCode.ARRAY_GET_U ||
                charArrayGetInstructions[4].opcode() != OpCode.RETURN ||
                charArrayGetInstructions[5].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[4].operand(1).toInt()
            fastFunctionOperands4!![funcId] = instructions[5].operand(1).toInt()
            fastFunctionOperands5!![funcId] = sequenceGetInstructions[3].operand(1).toInt()
            fastFunctionOperands6!![funcId] = instructions[11].operand(1).toInt()
            fastFunctionOperands7!![funcId] = charArrayGetInstructions[1].operand(1).toInt()
            return FAST_FUNCTION_READER_JSON_LEXER_INDEX_OF_ARRAY_SEQUENCE
        }

        if (instructions.size == 221 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.I32_CONST &&
            instructions[3].operand(0).toInt() == 34 &&
            instructions[4].opcode() == OpCode.LOCAL_GET &&
            instructions[4].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[5].opcode() == OpCode.STRUCT_GET &&
            instructions[6].opcode() == OpCode.STRUCT_GET &&
            instructions[7].opcode() == OpCode.CALL_REF &&
            instructions[8].opcode() == OpCode.LOCAL_GET &&
            instructions[8].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[9].opcode() == OpCode.STRUCT_GET &&
            instructions[10].opcode() == OpCode.LOCAL_SET &&
            instructions[11].opcode() == OpCode.LOCAL_GET &&
            instructions[11].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[12].opcode() == OpCode.I32_CONST &&
            instructions[12].operand(0).toInt() == 34 &&
            instructions[13].opcode() == OpCode.LOCAL_GET &&
            instructions[13].operand(0).toInt() == instructions[10].operand(0).toInt() &&
            instructions[14].opcode() == OpCode.LOCAL_GET &&
            instructions[14].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[15].opcode() == OpCode.STRUCT_GET &&
            instructions[15].operand(1).toInt() == instructions[5].operand(1).toInt() &&
            instructions[16].opcode() == OpCode.STRUCT_GET &&
            instructions[17].opcode() == OpCode.CALL_REF &&
            instructions[18].opcode() == OpCode.LOCAL_TEE &&
            instructions[19].opcode() == OpCode.I32_CONST &&
            instructions[19].operand(0).toInt() == -1 &&
            instructions[20].opcode() == OpCode.I32_EQ &&
            instructions[163].opcode() == OpCode.LOCAL_GET &&
            instructions[163].operand(0).toInt() == instructions[10].operand(0).toInt() &&
            instructions[164].opcode() == OpCode.LOCAL_TEE &&
            instructions[165].opcode() == OpCode.LOCAL_GET &&
            instructions[165].operand(0).toInt() == instructions[18].operand(0).toInt() &&
            instructions[166].opcode() == OpCode.I32_LT_S &&
            instructions[177].opcode() == OpCode.LOCAL_GET &&
            instructions[177].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[178].opcode() == OpCode.LOCAL_GET &&
            instructions[178].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[179].opcode() == OpCode.STRUCT_GET &&
            instructions[179].operand(1).toInt() == instructions[5].operand(1).toInt() &&
            instructions[180].opcode() == OpCode.STRUCT_GET &&
            instructions[181].opcode() == OpCode.CALL_REF &&
            instructions[182].opcode() == OpCode.LOCAL_GET &&
            instructions[183].opcode() == OpCode.CALL &&
            instructions[184].opcode() == OpCode.I32_CONST &&
            instructions[184].operand(0).toInt() == 92 &&
            instructions[185].opcode() == OpCode.I32_EQ &&
            instructions[207].opcode() == OpCode.LOCAL_GET &&
            instructions[207].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[208].opcode() == OpCode.LOCAL_GET &&
            instructions[208].operand(0).toInt() == instructions[18].operand(0).toInt() &&
            instructions[209].opcode() == OpCode.I32_CONST &&
            instructions[209].operand(0).toInt() == 1 &&
            instructions[210].opcode() == OpCode.I32_ADD &&
            instructions[211].opcode() == OpCode.STRUCT_SET &&
            instructions[211].operand(1).toInt() == instructions[9].operand(1).toInt() &&
            instructions[212].opcode() == OpCode.LOCAL_GET &&
            instructions[212].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[213].opcode() == OpCode.LOCAL_GET &&
            instructions[213].operand(0).toInt() == instructions[10].operand(0).toInt() &&
            instructions[214].opcode() == OpCode.LOCAL_GET &&
            instructions[214].operand(0).toInt() == instructions[18].operand(0).toInt() &&
            instructions[215].opcode() == OpCode.LOCAL_GET &&
            instructions[215].operand(0).toInt() == instructions[2].operand(0).toInt() &&
            instructions[216].opcode() == OpCode.STRUCT_GET &&
            instructions[216].operand(1).toInt() == instructions[5].operand(1).toInt() &&
            instructions[217].opcode() == OpCode.STRUCT_GET &&
            instructions[218].opcode() == OpCode.CALL_REF &&
            instructions[219].opcode() == OpCode.RETURN &&
            instructions[220].opcode() == OpCode.END
        ) {
            val arrayAsSequenceGetFuncId = instructions[183].operand(0).toInt()
            val arrayAsSequenceGetFunc = instance.function(arrayAsSequenceGetFuncId) ?: return FAST_FUNCTION_NONE
            val arrayAsSequenceGetInstructions = arrayAsSequenceGetFunc.instructions()
            if (arrayAsSequenceGetInstructions.size != 8 ||
                arrayAsSequenceGetInstructions[0].opcode() != OpCode.LOCAL_GET ||
                arrayAsSequenceGetInstructions[1].opcode() != OpCode.CAST_TEST_NULL ||
                arrayAsSequenceGetInstructions[2].opcode() != OpCode.LOCAL_TEE ||
                arrayAsSequenceGetInstructions[3].opcode() != OpCode.STRUCT_GET ||
                arrayAsSequenceGetInstructions[4].opcode() != OpCode.LOCAL_GET ||
                arrayAsSequenceGetInstructions[5].opcode() != OpCode.CALL ||
                arrayAsSequenceGetInstructions[6].opcode() != OpCode.RETURN ||
                arrayAsSequenceGetInstructions[7].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            val charArrayGetFuncId = arrayAsSequenceGetInstructions[5].operand(0).toInt()
            val charArrayGetFunc = instance.function(charArrayGetFuncId) ?: return FAST_FUNCTION_NONE
            val charArrayGetInstructions = charArrayGetFunc.instructions()
            if (charArrayGetInstructions.size != 6 ||
                charArrayGetInstructions[0].opcode() != OpCode.LOCAL_GET ||
                charArrayGetInstructions[1].opcode() != OpCode.STRUCT_GET ||
                charArrayGetInstructions[2].opcode() != OpCode.LOCAL_GET ||
                charArrayGetInstructions[3].opcode() != OpCode.ARRAY_GET_U ||
                charArrayGetInstructions[4].opcode() != OpCode.RETURN ||
                charArrayGetInstructions[5].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }

            val sourceArrayField = arrayAsSequenceGetInstructions[3].operand(1).toInt()
            val charArrayField = charArrayGetInstructions[1].operand(1).toInt()
            var substringFuncId = -1
            for (candidateFuncId in 0 until instance.functionCount()) {
                if (candidateFuncId == funcId) continue
                val candidateFunc = instance.function(candidateFuncId) ?: continue
                if (fastFunctionKind(candidateFuncId, candidateFunc) == FAST_FUNCTION_ARRAY_AS_SEQUENCE_SUBSTRING &&
                    fastFunctionOperands!![candidateFuncId] == sourceArrayField &&
                    fastFunctionOperands3!![candidateFuncId] == charArrayField
                ) {
                    substringFuncId = candidateFuncId
                    break
                }
            }
            if (substringFuncId < 0) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[5].operand(1).toInt()
            fastFunctionOperands4!![funcId] = instructions[180].operand(1).toInt()
            fastFunctionOperands5!![funcId] = sourceArrayField
            fastFunctionOperands6!![funcId] = fastFunctionOperands2!![substringFuncId]
            fastFunctionOperands7!![funcId] = charArrayField
            fastFunctionOperands8!![funcId] = instructions[9].operand(1).toInt()
            fastFunctionOperands9!![funcId] = fastFunctionOperands4!![substringFuncId]
            fastFunctionOperands10!![funcId] = fastFunctionOperands5!![substringFuncId]
            fastFunctionOperands11!![funcId] = fastFunctionOperands6!![substringFuncId]
            fastFunctionOperands12!![funcId] = fastFunctionOperands7!![substringFuncId]
            fastFunctionOperands13!![funcId] = fastFunctionOperands8!![substringFuncId]
            return FAST_FUNCTION_READER_JSON_LEXER_CONSUME_KEY_STRING
        }

        if (instructions.size == 27 &&
            type.params().size == 3 &&
            type.paramSlotCount() == 3 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 1 &&
            instructions[3].opcode() == OpCode.LOCAL_GET &&
            instructions[3].operand(0).toInt() == 2 &&
            instructions[4].opcode() == OpCode.LOCAL_SET &&
            instructions[5].opcode() == OpCode.LOCAL_GET &&
            instructions[5].operand(0).toInt() == 0 &&
            instructions[6].opcode() == OpCode.STRUCT_GET &&
            instructions[7].opcode() == OpCode.LOCAL_SET &&
            instructions[14].opcode() == OpCode.LOCAL_GET &&
            instructions[15].opcode() == OpCode.LOCAL_GET &&
            instructions[16].opcode() == OpCode.I32_LE_S &&
            instructions[24].opcode() == OpCode.CALL &&
            instructions[25].opcode() == OpCode.RETURN &&
            instructions[26].opcode() == OpCode.END
        ) {
            val concatToStringFuncId = instructions[24].operand(0).toInt()
            val concatToStringFunc = instance.function(concatToStringFuncId) ?: return FAST_FUNCTION_NONE
            val concatInstructions = concatToStringFunc.instructions()
            if (concatInstructions.size != 50 ||
                concatInstructions[5].opcode() != OpCode.CALL ||
                concatInstructions[6].opcode() != OpCode.CALL ||
                concatInstructions[11].opcode() != OpCode.ARRAY_NEW_DEFAULT ||
                concatInstructions[14].opcode() != OpCode.STRUCT_GET ||
                concatInstructions[41].opcode() != OpCode.ARRAY_COPY ||
                concatInstructions[47].opcode() != OpCode.CALL ||
                concatInstructions[48].opcode() != OpCode.RETURN ||
                concatInstructions[49].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            val createStringFuncId = concatInstructions[47].operand(0).toInt()
            val createStringFunc = instance.function(createStringFuncId) ?: return FAST_FUNCTION_NONE
            val createStringInstructions = createStringFunc.instructions()
            if (createStringInstructions.size != 11 ||
                createStringInstructions[0].opcode() != OpCode.GLOBAL_GET ||
                createStringInstructions[1].opcode() != OpCode.GLOBAL_GET ||
                createStringInstructions[2].opcode() != OpCode.GLOBAL_GET ||
                createStringInstructions[3].opcode() != OpCode.I32_CONST ||
                createStringInstructions[3].operand(0).toInt() != 0 ||
                createStringInstructions[4].opcode() != OpCode.REF_NULL ||
                createStringInstructions[5].opcode() != OpCode.LOCAL_GET ||
                createStringInstructions[6].opcode() != OpCode.ARRAY_LEN ||
                createStringInstructions[7].opcode() != OpCode.LOCAL_GET ||
                createStringInstructions[8].opcode() != OpCode.STRUCT_NEW ||
                createStringInstructions[9].opcode() != OpCode.RETURN ||
                createStringInstructions[10].opcode() != OpCode.END
            ) {
                return FAST_FUNCTION_NONE
            }
            val stringTypeIdx = createStringInstructions[8].operand(0).toInt()
            val stringFieldCount =
                instance.module().typeSection().getSubType(stringTypeIdx).compType().structType()!!.fieldTypes().size
            if (stringFieldCount != 7) {
                return FAST_FUNCTION_NONE
            }

            fastFunctionOperands!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands2!![funcId] = instructions[6].operand(1).toInt()
            fastFunctionOperands3!![funcId] = concatInstructions[14].operand(1).toInt()
            fastFunctionOperands4!![funcId] = concatInstructions[11].operand(0).toInt()
            fastFunctionOperands5!![funcId] = stringTypeIdx
            fastFunctionOperands6!![funcId] = createStringInstructions[0].operand(0).toInt()
            fastFunctionOperands7!![funcId] = createStringInstructions[1].operand(0).toInt()
            fastFunctionOperands8!![funcId] = createStringInstructions[2].operand(0).toInt()
            return FAST_FUNCTION_ARRAY_AS_SEQUENCE_SUBSTRING
        }

        if (instructions.size == 8 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[2].opcode() == OpCode.LOCAL_TEE &&
            instructions[3].opcode() == OpCode.CALL &&
            instructions[4].opcode() == OpCode.I64_CONST &&
            instructions[4].operand(0) == 0L &&
            instructions[5].opcode() == OpCode.I64_EQ &&
            instructions[6].opcode() == OpCode.RETURN &&
            instructions[7].opcode() == OpCode.END
        ) {
            val sizeFuncId = instructions[3].operand(0).toInt()
            val sizeFunc = instance.function(sizeFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(sizeFuncId, sizeFunc) != FAST_FUNCTION_STRUCT_GET) {
                return FAST_FUNCTION_NONE
            }
            val sizeType = instance.type(instance.functionType(sizeFuncId))
            if (sizeType.returnSlotCount() != 1 ||
                sizeType.returns().size != 1 ||
                sizeType.returns()[0] != ValType.I64
            ) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = fastFunctionOperands2!![sizeFuncId]
            return FAST_FUNCTION_BUFFER_EXHAUSTED
        }

        if (instructions.size == 74 &&
            type.params().size == 1 &&
            type.paramSlotCount() == 1 &&
            type.returnSlotCount() == 1 &&
            type.returns().size == 1 &&
            type.returns()[0] == ValType.I32 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.CAST_TEST_NULL &&
            instructions[8].opcode() == OpCode.STRUCT_GET_S &&
            instructions[41].opcode() == OpCode.LOCAL_GET &&
            instructions[41].operand(0).toInt() == 1 &&
            instructions[42].opcode() == OpCode.STRUCT_GET &&
            instructions[43].opcode() == OpCode.CALL &&
            instructions[44].opcode() == OpCode.IF &&
            instructions[45].opcode() == OpCode.LOCAL_GET &&
            instructions[45].operand(0).toInt() == 1 &&
            instructions[46].opcode() == OpCode.STRUCT_GET &&
            instructions[57].opcode() == OpCode.I64_CONST &&
            instructions[57].operand(0) == 1L &&
            instructions[68].opcode() == OpCode.I64_EQ &&
            instructions[69].opcode() == OpCode.ELSE &&
            instructions[70].opcode() == OpCode.I32_CONST &&
            instructions[70].operand(0).toInt() == 0 &&
            instructions[72].opcode() == OpCode.RETURN &&
            instructions[73].opcode() == OpCode.END
        ) {
            val bufferExhaustedFuncId = instructions[43].operand(0).toInt()
            val bufferExhaustedFunc = instance.function(bufferExhaustedFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(bufferExhaustedFuncId, bufferExhaustedFunc) != FAST_FUNCTION_BUFFER_EXHAUSTED) {
                return FAST_FUNCTION_NONE
            }
            val closedField = instructions[8].operand(1).toInt()
            val bufferField = instructions[42].operand(1).toInt()
            if (instructions[46].operand(1).toInt() == bufferField) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(0).toInt()
            fastFunctionOperands2!![funcId] = closedField
            fastFunctionOperands3!![funcId] = bufferField
            fastFunctionOperands4!![funcId] = fastFunctionOperands!![bufferExhaustedFuncId]
            fastFunctionOperands5!![funcId] = fastFunctionOperands2!![bufferExhaustedFuncId]
            return FAST_FUNCTION_REAL_SOURCE_EXHAUSTED_NONEMPTY
        }

        if (instructions.size == 10 &&
            type.params().size == 2 &&
            type.paramSlotCount() == 2 &&
            type.returnSlotCount() == 1 &&
            instructions[0].opcode() == OpCode.LOCAL_GET &&
            instructions[0].operand(0).toInt() == 0 &&
            instructions[1].opcode() == OpCode.STRUCT_GET &&
            instructions[2].opcode() == OpCode.LOCAL_GET &&
            instructions[2].operand(0).toInt() == 0 &&
            instructions[3].opcode() == OpCode.STRUCT_GET &&
            instructions[4].opcode() == OpCode.STRUCT_GET &&
            instructions[5].opcode() == OpCode.LOCAL_GET &&
            instructions[5].operand(0).toInt() == 1 &&
            instructions[6].opcode() == OpCode.CALL &&
            instructions[7].opcode() == OpCode.ARRAY_GET &&
            instructions[8].opcode() == OpCode.RETURN &&
            instructions[9].opcode() == OpCode.END
        ) {
            val calledFuncId = instructions[6].operand(0).toInt()
            val calledFunc = instance.function(calledFuncId) ?: return FAST_FUNCTION_NONE
            if (fastFunctionKind(calledFuncId, calledFunc) != FAST_FUNCTION_ARRAY_ANY_INDEX_OF_VALUE) {
                return FAST_FUNCTION_NONE
            }
            fastFunctionOperands!![funcId] = instructions[1].operand(1).toInt()
            fastFunctionOperands2!![funcId] = instructions[3].operand(1).toInt()
            fastFunctionOperands3!![funcId] = instructions[4].operand(1).toInt()
            return FAST_FUNCTION_INTERFACE_VTABLE_GET
        }

        return FAST_FUNCTION_NONE
    }

    private fun pushInitialLocalGetIfAvailable(targetInstance: Instance, stackFrame: StackFrame) {
        if (targetInstance.executionListener() == null) {
            stackFrame.pushInitialLocalGet(stack)
        }
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
        frame.branchTo(n, stack)
    }

    private fun BR(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        if (!usesPeriodicInterruptionPolling) {
            checkInterruption()
        }
        ctrlJump(frame, stack, instruction.operand(0).toInt())
        frame.jumpTo(instruction.labelTrue())
    }

    private fun BR_TABLE(frame: StackFrame, stack: MStack, instruction: AnnotatedInstruction) {
        if (!usesPeriodicInterruptionPolling) {
            checkInterruption()
        }
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
        if (!usesPeriodicInterruptionPolling) {
            checkInterruption()
        }
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
     * Terminate WASM execution if requested. JVM checks hot branches directly for thread
     * interruption, while platforms without cheap thread interruption can poll periodically from the
     * interpreter loop.
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
        var ref = stack.peek().toInt()
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        var struct = instance.gcRefUnchecked(ref) as WasmStruct
        var value = struct.fields[fieldIdx]
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
        stack.replaceTop(value)
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
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            val arr =
                when (packedType.ID()) {
                    0x78 -> WasmArray(typeIdx, ByteArray(len))
                    0x77 -> WasmArray(typeIdx, ShortArray(len))
                    else -> error("Unsupported packed array type")
                }
            stack.push(instance.registerGcRef(arr))
            return
        }
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
        const val MAX_REUSABLE_FRAMES_PER_FUNCTION: Int = 64
        const val FAST_FUNCTION_UNKNOWN: Int = 0
        const val FAST_FUNCTION_NONE: Int = 1
        const val FAST_FUNCTION_IDENTITY: Int = 2
        const val FAST_FUNCTION_I64_EXTEND_I32_S: Int = 3
        const val FAST_FUNCTION_I32_WRAP_I64: Int = 4
        const val FAST_FUNCTION_GLOBAL_GET: Int = 5
        const val FAST_FUNCTION_STRUCT_GET: Int = 6
        const val FAST_FUNCTION_ARRAY_WRAPPER_GET: Int = 7
        const val FAST_FUNCTION_ARRAY_WRAPPER_GET_S: Int = 8
        const val FAST_FUNCTION_ARRAY_WRAPPER_GET_U: Int = 9
        const val FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET: Int = 10
        const val FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_S: Int = 11
        const val FAST_FUNCTION_OFFSET_ARRAY_WRAPPER_GET_U: Int = 12
        const val FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET: Int = 13
        const val FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_S: Int = 14
        const val FAST_FUNCTION_CAST_ARRAY_WRAPPER_GET_U: Int = 15
        const val FAST_FUNCTION_ARRAY_ANY_INDEX_OF_VALUE: Int = 16
        const val FAST_FUNCTION_INTERFACE_VTABLE_GET: Int = 17
        const val FAST_FUNCTION_ARRAY_WRAPPER_SET: Int = 18
        const val FAST_FUNCTION_CAST_STRUCT_GET: Int = 19
        const val FAST_FUNCTION_I32_AND_CONST: Int = 20
        const val FAST_FUNCTION_GLOBAL_GET_LOW: Int = 21
        const val FAST_FUNCTION_MEMORY_LOAD8_S: Int = 22
        const val FAST_FUNCTION_BUFFER_GET_ZERO: Int = 23
        const val FAST_FUNCTION_BUFFER_SKIP_ONE: Int = 24
        const val FAST_FUNCTION_BUFFER_REQUIRE_ONE: Int = 25
        const val FAST_FUNCTION_REAL_SOURCE_REQUIRE_ONE: Int = 26
        const val FAST_FUNCTION_BUFFER_READ_UTF8_CODE_POINT_ASCII: Int = 27
        const val FAST_FUNCTION_REAL_SOURCE_READ_CODE_POINT_ASCII: Int = 28
        const val FAST_FUNCTION_IO_SERIAL_READER_NEXT_CODE_POINT_ASCII: Int = 29
        const val FAST_FUNCTION_BUFFER_EXHAUSTED: Int = 30
        const val FAST_FUNCTION_REAL_SOURCE_EXHAUSTED_NONEMPTY: Int = 31
        const val FAST_FUNCTION_IO_SERIAL_READER_EXHAUSTED_NONEMPTY: Int = 32
        const val FAST_FUNCTION_READER_JSON_LEXER_SKIP_WHITESPACES_NON_WS: Int = 33
        const val FAST_FUNCTION_INIT_FLAG_DONE: Int = 34
        const val FAST_FUNCTION_LAZY_GLOBAL_GET_NON_NULL: Int = 35
        const val FAST_FUNCTION_INIT_THEN_GLOBAL_GET: Int = 36
        const val FAST_FUNCTION_READER_JSON_LEXER_INDEX_OF_ARRAY_SEQUENCE: Int = 37
        const val FAST_FUNCTION_REF_EQ: Int = 38
        const val FAST_FUNCTION_ARRAY_AS_SEQUENCE_SUBSTRING: Int = 39
        const val FAST_FUNCTION_READER_JSON_LEXER_CONSUME_KEY_STRING: Int = 40
        const val FAST_FUNCTION_MEMORY_LOAD8_U: Int = 41
        const val FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_S: Int = 42
        const val FAST_FUNCTION_INIT_THEN_MEMORY_LOAD8_U: Int = 43
        const val FAST_FUNCTION_MEMORY_STORE8: Int = 44
        const val FAST_FUNCTION_INIT_THEN_MEMORY_STORE8: Int = 45
        const val FAST_FUNCTION_STRING_EQUALS: Int = 46
        const val JSON_KEY_STRING_CACHE_BITS: Int = 6
        const val JSON_KEY_STRING_CACHE_SIZE: Int = 1 shl JSON_KEY_STRING_CACHE_BITS
        const val JSON_KEY_STRING_CACHE_MAGIC: Int = -1640531527
        const val STRING_LEFT_FIELD: Int = 4
        const val STRING_LENGTH_FIELD: Int = 5
        const val STRING_CHARS_FIELD: Int = 6
    }
}
