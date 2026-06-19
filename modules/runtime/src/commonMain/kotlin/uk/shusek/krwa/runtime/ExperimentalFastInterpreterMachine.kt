package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.Value
import uk.shusek.krwa.wasm.types.ValType

/**
 * Experimental standalone interpreter backend.
 *
 * This intentionally does not subclass [InterpreterMachine]. It owns its stack and frame model so
 * it can evolve independently from the standard interpreter hot path.
 */
class ExperimentalFastInterpreterMachine(private val instance: Instance) : ResumableMachine {
    private val stack = FastValueStack()
    private val activeFrames = ArrayList<FastFrame>()
    private val continuationStates = HashMap<WasmContinuation, FastContinuationState>()
    private val loweredFunctions = HashMap<Int, FastFunction>()
    private val unsupportedLoweredFunctions = HashSet<Int>()
    private var currentContinuationReturnSlotCount = 0

    override fun call(funcId: Int, args: LongArray): LongArray {
        val previousReturnSlotCount = currentContinuationReturnSlotCount
        currentContinuationReturnSlotCount = instance.type(instance.functionType(funcId)).returnSlotCount()
        try {
            return callFunction(funcId, args)
        } finally {
            currentContinuationReturnSlotCount = previousReturnSlotCount
        }
    }

    override fun resume(continuation: WasmContinuation): LongArray {
        val state =
            continuationStates.remove(continuation)
                ?: throw WasmEngineException("continuation was not captured by ExperimentalFastInterpreterMachine")
        if (activeFrames.isNotEmpty()) {
            throw WasmEngineException("cannot resume while this machine is already executing")
        }

        val previousReturnSlotCount = currentContinuationReturnSlotCount
        currentContinuationReturnSlotCount = state.returnSlotCount
        stack.restore(state.stackValues)
        activeFrames.addAll(state.frames.map { it.snapshot() })
        try {
            return resumeActiveFrames(state.returnSlotCount)
        } catch (suspended: WasmExecutionSuspended) {
            captureSuspension(suspended)
            throw suspended
        } finally {
            activeFrames.clear()
            stack.discardToSize(0)
            currentContinuationReturnSlotCount = previousReturnSlotCount
        }
    }

    private fun callFunction(funcId: Int, args: LongArray): LongArray {
        val type = instance.type(instance.functionType(funcId))
        if (args.size != type.paramSlotCount()) {
            throw WasmEngineException(
                "function $funcId expected ${type.paramSlotCount()} argument slots, got ${args.size}"
            )
        }

        val body = instance.function(funcId)
        if (body == null) {
            try {
                return callImport(funcId, args)
            } catch (suspended: WasmExecutionSuspended) {
                captureSuspension(suspended)
                throw suspended
            }
        }

        val stackBase = stack.size()
        val frame = FastFrame(type, body, args, loweredFunction(funcId, type, body))
        activeFrames.add(frame)
        try {
            execute(frame)
            return stack.popResults(type.returnSlotCount())
        } catch (suspended: WasmExecutionSuspended) {
            captureSuspension(suspended)
            throw suspended
        } finally {
            if (activeFrames.lastOrNull() === frame) {
                activeFrames.removeAt(activeFrames.lastIndex)
            }
            stack.discardToSize(stackBase)
        }
    }

    private fun callImport(funcId: Int, args: LongArray): LongArray {
        val import = instance.imports().function(funcId)
        val handle =
            import.handle()
                ?: throw WasmEngineException("imported function has no host handle: ${import.module()}.${import.name()}")
        return handle.apply(instance, args) ?: LongArray(0)
    }

    private fun loweredFunction(
        funcId: Int,
        type: FunctionType,
        body: FunctionBody,
    ): FastFunction? {
        loweredFunctions[funcId]?.let { return it }
        if (funcId in unsupportedLoweredFunctions) return null
        val lowered = FastFunction.lower(type, body)
        if (lowered == null) {
            unsupportedLoweredFunctions.add(funcId)
            return null
        }
        loweredFunctions[funcId] = lowered
        return lowered
    }

    private fun resumeActiveFrames(returnSlotCount: Int): LongArray {
        if (activeFrames.isEmpty()) {
            return stack.popResults(returnSlotCount)
        }
        while (activeFrames.isNotEmpty()) {
            val frame = activeFrames.last()
            execute(frame)
            if (activeFrames.lastOrNull() === frame) {
                activeFrames.removeAt(activeFrames.lastIndex)
            }
            val results = stack.popResults(frame.returnSlotCount)
            if (activeFrames.isEmpty()) {
                return results
            }
            for (result in results) {
                stack.push(result)
            }
        }
        return LongArray(0)
    }

    private fun captureSuspension(suspended: WasmExecutionSuspended) {
        if (suspended.continuation != null) return
        for (result in suspended.resumeResults) {
            stack.push(result)
        }
        suspended.continuation =
            WasmContinuation(
                stackValues = LongArray(0),
                callStackFrames = emptyList(),
                returnSlotCount = currentContinuationReturnSlotCount,
            ).also { continuation ->
                continuationStates[continuation] =
                    FastContinuationState(
                        stack.snapshot(),
                        activeFrames.map { it.snapshot() },
                        currentContinuationReturnSlotCount,
                    )
            }
    }

    private fun execute(frame: FastFrame) {
        val lowered = frame.loweredFunction()
        if (lowered != null) {
            executeLowered(frame, lowered)
        } else {
            executeAnnotated(frame)
        }
    }

    private fun executeLowered(frame: FastFrame, function: FastFunction) {
        val opcodes = function.opcodes
        val operands = function.operands
        val operands2 = function.operands2
        val operands3 = function.operands3
        val labelTrue = function.labelTrue
        val labelFalse = function.labelFalse
        val locals = frame.localsArray()
        var values = stack.valuesArray()
        var sp = stack.rawCount()
        var pc = frame.currentPc()
        while (pc < opcodes.size) {
            val index = pc++
            val opcode = opcodes[index]
            when (opcode) {
                FAST_NOP -> Unit
                FAST_UNREACHABLE -> throw TrapException("Trapped on unreachable instruction")
                FAST_BLOCK,
                FAST_LOOP -> frame.pushControl(opcode, operands[index], sp)
                FAST_IF -> {
                    val pred = values[--sp]
                    frame.pushControl(FAST_IF, operands[index], sp)
                    if (pred == 0L) {
                        pc = labelFalse[index]
                    }
                }
                FAST_ELSE -> pc = labelTrue[index]
                FAST_END -> {
                    stack.setRawCount(sp)
                    if (!frame.endControl(stack)) {
                        frame.updatePc(pc)
                        values = stack.valuesArray()
                        sp = stack.rawCount()
                        return
                    }
                    values = stack.valuesArray()
                    sp = stack.rawCount()
                }
                FAST_RETURN -> {
                    frame.updatePc(pc)
                    stack.setRawCount(sp)
                    return
                }
                FAST_BR -> {
                    stack.setRawCount(sp)
                    frame.branch(operands[index].toInt(), stack)
                    values = stack.valuesArray()
                    sp = stack.rawCount()
                    pc = labelTrue[index]
                }
                FAST_BR_IF -> {
                    val pred = values[--sp].toInt()
                    if (pred == 0) {
                        pc = labelFalse[index]
                    } else {
                        stack.setRawCount(sp)
                        frame.branch(operands[index].toInt(), stack)
                        values = stack.valuesArray()
                        sp = stack.rawCount()
                        pc = labelTrue[index]
                    }
                }
                FAST_CALL -> {
                    frame.updatePc(pc)
                    stack.setRawCount(sp)
                    callFromStack(operands[index].toInt())
                    values = stack.valuesArray()
                    sp = stack.rawCount()
                }
                FAST_DROP -> sp--
                FAST_LOCAL_GET -> {
                    if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                    values[sp++] = locals[operands[index].toInt()]
                }
                FAST_LOCAL_SET -> locals[operands[index].toInt()] = values[--sp]
                FAST_LOCAL_TEE -> locals[operands[index].toInt()] = values[sp - 1]
                FAST_I32_CONST -> {
                    if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                    values[sp++] = operands[index].toInt().toLong()
                }
                FAST_I64_CONST -> {
                    if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                    values[sp++] = operands[index]
                }
                FAST_I32_ADD -> {
                    val b = values[--sp].toInt()
                    val a = values[--sp].toInt()
                    values[sp++] = (a + b).toLong()
                }
                FAST_I32_SUB -> {
                    val b = values[--sp].toInt()
                    val a = values[--sp].toInt()
                    values[sp++] = (a - b).toLong()
                }
                FAST_I32_MUL -> {
                    val b = values[--sp].toInt()
                    val a = values[--sp].toInt()
                    values[sp++] = (a * b).toLong()
                }
                FAST_I32_CONST_LOCAL_SET -> {
                    locals[operands2[index]] = operands[index].toInt().toLong()
                    pc = index + 2
                }
                FAST_I32_ADD_LOCAL_SET -> {
                    val b = values[--sp].toInt()
                    val a = values[--sp].toInt()
                    locals[operands[index].toInt()] = (a + b).toLong()
                    pc = index + 2
                }
                FAST_I32_ADD_LOCAL_TEE -> {
                    val b = values[--sp].toInt()
                    val a = values[--sp].toInt()
                    val value = a + b
                    locals[operands[index].toInt()] = value.toLong()
                    if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                    values[sp++] = value.toLong()
                    pc = index + 2
                }
                FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_SET -> {
                    val value = locals[operands[index].toInt()].toInt() + locals[operands2[index]].toInt()
                    locals[operands3[index]] = value.toLong()
                    pc = index + 4
                }
                FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_TEE -> {
                    val value = locals[operands[index].toInt()].toInt() + locals[operands2[index]].toInt()
                    locals[operands3[index]] = value.toLong()
                    if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                    values[sp++] = value.toLong()
                    pc = index + 4
                }
                FAST_I32_COUNTDOWN_BRANCH -> {
                    val localSlot = operands[index].toInt()
                    val value = locals[localSlot].toInt() - operands2[index]
                    locals[localSlot] = value.toLong()
                    if (value == 0) {
                        pc = labelFalse[index]
                    } else {
                        val branchDepth = operands3[index]
                        if (branchDepth == FAST_CURRENT_PARAMETERLESS_LOOP_DEPTH) {
                            sp = frame.currentControlHeight()
                        } else {
                            stack.setRawCount(sp)
                            frame.branch(branchDepth, stack)
                            values = stack.valuesArray()
                            sp = stack.rawCount()
                        }
                        pc = labelTrue[index]
                    }
                }
                else -> {
                    when (opcode) {
                        FAST_SELECT -> {
                            val pred = values[--sp].toInt()
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = if (pred == 0) b else a
                        }
                        FAST_GLOBAL_GET -> {
                            val global = instance.global(operands[index].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = global.valueLow
                            if (global.type == ValType.V128) {
                                if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                                values[sp++] = global.valueHigh
                            }
                        }
                        FAST_GLOBAL_SET -> {
                            val global = instance.global(operands[index].toInt())
                            if (global.type == ValType.V128) {
                                val high = values[--sp]
                                val low = values[--sp]
                                global.valueLow = low
                                global.valueHigh = high
                            } else {
                                global.value = values[--sp]
                            }
                        }
                        FAST_F64_CONST -> {
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = operands[index]
                        }
                        FAST_I32_DIV_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = OpcodeOps.I32_DIV_S(a, b).toLong()
                        }
                        FAST_I32_DIV_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = OpcodeOps.I32_DIV_U(a, b).toLong()
                        }
                        FAST_I32_REM_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = OpcodeOps.I32_REM_S(a, b).toLong()
                        }
                        FAST_I32_REM_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = OpcodeOps.I32_REM_U(a, b).toLong()
                        }
                        FAST_I32_AND -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a and b).toLong()
                        }
                        FAST_I32_OR -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a or b).toLong()
                        }
                        FAST_I32_XOR -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a xor b).toLong()
                        }
                        FAST_I32_SHL -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a shl b).toLong()
                        }
                        FAST_I32_SHR_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a shr b).toLong()
                        }
                        FAST_I32_SHR_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = (a ushr b).toLong()
                        }
                        FAST_I32_EQZ -> values[sp - 1] = if (values[sp - 1].toInt() == 0) TRUE else FALSE
                        FAST_I32_EQ -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a == b) TRUE else FALSE
                        }
                        FAST_I32_NE -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a != b) TRUE else FALSE
                        }
                        FAST_I32_LT_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a < b) TRUE else FALSE
                        }
                        FAST_I32_LT_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a.toUInt() < b.toUInt()) TRUE else FALSE
                        }
                        FAST_I32_GT_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a > b) TRUE else FALSE
                        }
                        FAST_I32_GT_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a.toUInt() > b.toUInt()) TRUE else FALSE
                        }
                        FAST_I32_LE_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a <= b) TRUE else FALSE
                        }
                        FAST_I32_LE_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a.toUInt() <= b.toUInt()) TRUE else FALSE
                        }
                        FAST_I32_GE_S -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a >= b) TRUE else FALSE
                        }
                        FAST_I32_GE_U -> {
                            val b = values[--sp].toInt()
                            val a = values[--sp].toInt()
                            values[sp++] = if (a.toUInt() >= b.toUInt()) TRUE else FALSE
                        }
                        FAST_I64_ADD -> {
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = a + b
                        }
                        FAST_I64_SUB -> {
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = a - b
                        }
                        FAST_I64_MUL -> {
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = a * b
                        }
                        FAST_I64_EQZ -> values[sp - 1] = if (values[sp - 1] == 0L) TRUE else FALSE
                        FAST_I64_EQ -> {
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = if (a == b) TRUE else FALSE
                        }
                        FAST_I64_NE -> {
                            val b = values[--sp]
                            val a = values[--sp]
                            values[sp++] = if (a != b) TRUE else FALSE
                        }
                        FAST_I32_LOAD -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readI32(address)
                        }
                        FAST_I32_LOAD8_S -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readI8(address)
                        }
                        FAST_I32_LOAD8_U -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readU8(address)
                        }
                        FAST_I32_LOAD16_S -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readI16(address)
                        }
                        FAST_I32_LOAD16_U -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readU16(address)
                        }
                        FAST_I64_LOAD -> {
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            if (sp >= values.size) values = stack.ensureCapacityAndGet(sp + 1)
                            values[sp++] = instance.memory(operands2[index]).readI64(address)
                        }
                        FAST_I32_STORE -> {
                            val value = values[--sp].toInt()
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            instance.memory(operands2[index]).writeI32(address, value)
                        }
                        FAST_I32_STORE8 -> {
                            val value = values[--sp].toByte()
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            instance.memory(operands2[index]).writeByte(address, value)
                        }
                        FAST_I32_STORE16 -> {
                            val value = values[--sp].toShort()
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            instance.memory(operands2[index]).writeShort(address, value)
                        }
                        FAST_I64_STORE -> {
                            val value = values[--sp]
                            val address = loweredMemoryAddress(operands[index], values[--sp].toInt())
                            instance.memory(operands2[index]).writeLong(address, value)
                        }
                        else -> throw ExperimentalFastInterpreterUnsupportedException("lowered opcode $opcode")
                    }
                }
            }
        }
        stack.setRawCount(sp)
        frame.updatePc(pc)
    }

    private fun executeAnnotated(frame: FastFrame) {
        while (!frame.terminated()) {
            val instruction = frame.nextInstruction()
            when (instruction.opcode()) {
                OpCode.NOP -> Unit
                OpCode.UNREACHABLE -> throw TrapException("Trapped on unreachable instruction")
                OpCode.BLOCK,
                OpCode.LOOP -> frame.pushControl(instruction, stack)
                OpCode.IF -> frame.pushIf(instruction, stack)
                OpCode.ELSE -> frame.jumpTo(instruction.labelTrue())
                OpCode.END -> {
                    if (!frame.endControl(stack)) {
                        return
                    }
                }
                OpCode.RETURN -> return
                OpCode.BR -> frame.branchTo(instruction.operand(0).toInt(), instruction.labelTrue(), stack)
                OpCode.BR_IF -> {
                    val pred = stack.popI32()
                    if (pred == 0) {
                        frame.jumpTo(instruction.labelFalse())
                    } else {
                        frame.branchTo(instruction.operand(0).toInt(), instruction.labelTrue(), stack)
                    }
                }
                OpCode.BR_TABLE -> {
                    val pred = stack.popI32()
                    val defaultTarget = instruction.operandCount() - 1
                    val target =
                        if (pred < 0 || pred >= defaultTarget) {
                            defaultTarget
                        } else {
                            pred
                        }
                    frame.branchTo(
                        instruction.operand(target).toInt(),
                        instruction.labelTable()[target],
                        stack,
                    )
                }
                OpCode.CALL -> callFromStack(instruction.operand(0).toInt())
                OpCode.DROP -> stack.pop()
                OpCode.SELECT -> select()
                OpCode.LOCAL_GET -> frame.pushLocal(instruction.operand(0).toInt(), stack)
                OpCode.LOCAL_SET -> frame.popLocal(instruction.operand(0).toInt(), stack)
                OpCode.LOCAL_TEE -> frame.teeLocal(instruction.operand(0).toInt(), stack)
                OpCode.GLOBAL_GET -> globalGet(instruction.operand(0).toInt())
                OpCode.GLOBAL_SET -> globalSet(instruction.operand(0).toInt())
                OpCode.I32_CONST,
                OpCode.I64_CONST,
                OpCode.F64_CONST -> stack.push(instruction.operand(0))
                OpCode.I32_ADD -> stack.push(stack.popI32() + stack.popI32())
                OpCode.I32_SUB -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(a - b)
                }
                OpCode.I32_MUL -> stack.push(stack.popI32() * stack.popI32())
                OpCode.I32_DIV_S -> binaryI32 { a, b -> OpcodeOps.I32_DIV_S(a, b) }
                OpCode.I32_DIV_U -> binaryI32 { a, b -> OpcodeOps.I32_DIV_U(a, b) }
                OpCode.I32_REM_S -> binaryI32 { a, b -> OpcodeOps.I32_REM_S(a, b) }
                OpCode.I32_REM_U -> binaryI32 { a, b -> OpcodeOps.I32_REM_U(a, b) }
                OpCode.I32_AND -> stack.push(stack.popI32() and stack.popI32())
                OpCode.I32_OR -> stack.push(stack.popI32() or stack.popI32())
                OpCode.I32_XOR -> stack.push(stack.popI32() xor stack.popI32())
                OpCode.I32_SHL -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(a shl b)
                }
                OpCode.I32_SHR_S -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(a shr b)
                }
                OpCode.I32_SHR_U -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(a ushr b)
                }
                OpCode.I32_EQZ -> stack.push(if (stack.popI32() == 0) TRUE else FALSE)
                OpCode.I32_EQ -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a == b) TRUE else FALSE)
                }
                OpCode.I32_NE -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a != b) TRUE else FALSE)
                }
                OpCode.I32_LT_S -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a < b) TRUE else FALSE)
                }
                OpCode.I32_LT_U -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a.toUInt() < b.toUInt()) TRUE else FALSE)
                }
                OpCode.I32_GT_S -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a > b) TRUE else FALSE)
                }
                OpCode.I32_GT_U -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a.toUInt() > b.toUInt()) TRUE else FALSE)
                }
                OpCode.I32_LE_S -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a <= b) TRUE else FALSE)
                }
                OpCode.I32_LE_U -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a.toUInt() <= b.toUInt()) TRUE else FALSE)
                }
                OpCode.I32_GE_S -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a >= b) TRUE else FALSE)
                }
                OpCode.I32_GE_U -> {
                    val b = stack.popI32()
                    val a = stack.popI32()
                    stack.push(if (a.toUInt() >= b.toUInt()) TRUE else FALSE)
                }
                OpCode.I64_ADD -> stack.push(stack.pop() + stack.pop())
                OpCode.I64_SUB -> {
                    val b = stack.pop()
                    val a = stack.pop()
                    stack.push(a - b)
                }
                OpCode.I64_MUL -> stack.push(stack.pop() * stack.pop())
                OpCode.I64_EQZ -> stack.push(if (stack.pop() == 0L) TRUE else FALSE)
                OpCode.I64_EQ -> {
                    val b = stack.pop()
                    val a = stack.pop()
                    stack.push(if (a == b) TRUE else FALSE)
                }
                OpCode.I64_NE -> {
                    val b = stack.pop()
                    val a = stack.pop()
                    stack.push(if (a != b) TRUE else FALSE)
                }
                OpCode.F64_DIV -> {
                    val b = Value.longToDouble(stack.pop())
                    val a = Value.longToDouble(stack.pop())
                    stack.push(Value.doubleToLong(a / b))
                }
                OpCode.F64_LT -> {
                    val b = Value.longToDouble(stack.pop())
                    val a = Value.longToDouble(stack.pop())
                    stack.push(OpcodeOps.F64_LT(a, b))
                }
                OpCode.F64_GE -> {
                    val b = Value.longToDouble(stack.pop())
                    val a = Value.longToDouble(stack.pop())
                    stack.push(OpcodeOps.F64_GE(a, b))
                }
                OpCode.F64_CONVERT_I64_U -> {
                    stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I64_U(stack.pop())))
                }
                OpCode.F64_CONVERT_I32_U -> {
                    stack.push(Value.doubleToLong(OpcodeOps.F64_CONVERT_I32_U(stack.popI32())))
                }
                OpCode.I32_TRUNC_F64_U -> {
                    stack.push(OpcodeOps.I32_TRUNC_F64_U(Value.longToDouble(stack.pop())))
                }
                OpCode.F32_DEMOTE_F64 -> {
                    stack.push(Value.floatToLong(Value.longToDouble(stack.pop()).toFloat()))
                }
                OpCode.I32_LOAD -> stack.push(memory(instruction).readI32(memoryAddress(instruction)))
                OpCode.I32_LOAD8_S -> stack.push(memory(instruction).readI8(memoryAddress(instruction)))
                OpCode.I32_LOAD8_U -> stack.push(memory(instruction).readU8(memoryAddress(instruction)))
                OpCode.I32_LOAD16_S -> stack.push(memory(instruction).readI16(memoryAddress(instruction)))
                OpCode.I32_LOAD16_U -> stack.push(memory(instruction).readU16(memoryAddress(instruction)))
                OpCode.I64_LOAD -> stack.push(memory(instruction).readI64(memoryAddress(instruction)))
                OpCode.I32_STORE -> {
                    val value = stack.popI32()
                    memory(instruction).writeI32(memoryAddress(instruction), value)
                }
                OpCode.I32_STORE8 -> {
                    val value = stack.pop().toByte()
                    memory(instruction).writeByte(memoryAddress(instruction), value)
                }
                OpCode.I32_STORE16 -> {
                    val value = stack.pop().toShort()
                    memory(instruction).writeShort(memoryAddress(instruction), value)
                }
                OpCode.I64_STORE -> {
                    val value = stack.pop()
                    memory(instruction).writeLong(memoryAddress(instruction), value)
                }
                OpCode.ARRAY_NEW_DATA -> arrayNewData(instruction)
                else -> throw ExperimentalFastInterpreterUnsupportedException(instruction.opcode())
            }
        }
    }

    private fun binaryI32(operation: (Int, Int) -> Int) {
        val b = stack.popI32()
        val a = stack.popI32()
        stack.push(operation(a, b))
    }

    private fun callFromStack(funcId: Int) {
        val type = instance.type(instance.functionType(funcId))
        val args = stack.popResults(type.paramSlotCount())
        for (result in callFunction(funcId, args)) {
            stack.push(result)
        }
    }

    private fun select() {
        val pred = stack.popI32()
        val b = stack.pop()
        val a = stack.pop()
        stack.push(if (pred == 0) b else a)
    }

    private fun globalGet(index: Int) {
        val global = instance.global(index)
        stack.push(global.valueLow)
        if (global.type == ValType.V128) {
            stack.push(global.valueHigh)
        }
    }

    private fun globalSet(index: Int) {
        val global = instance.global(index)
        if (global.type == ValType.V128) {
            val high = stack.pop()
            val low = stack.pop()
            global.valueLow = low
            global.valueHigh = high
        } else {
            global.value = stack.pop()
        }
    }

    private fun memory(instruction: AnnotatedInstruction): Memory =
        instance.memory(memoryIndex(instruction))

    private fun memoryIndex(instruction: AnnotatedInstruction): Int =
        if (instruction.operandCount() > 2) instruction.operand(2).toInt() else 0

    private fun memoryAddress(instruction: AnnotatedInstruction): Int {
        val offset = instruction.operand(1)
        val address = stack.popI32()
        return loweredMemoryAddress(offset, address)
    }

    private fun loweredMemoryAddress(offset: Long, address: Int): Int {
        val ptr = offset + address
        if (offset < 0 || address < 0 || ptr < 0 || ptr > Int.MAX_VALUE) {
            throw WasmRuntimeException("out of bounds memory access")
        }
        return ptr.toInt()
    }

    private fun arrayNewData(instruction: AnnotatedInstruction) {
        val typeIndex = instruction.operand(0).toInt()
        val dataIndex = instruction.operand(1).toInt()
        val length = stack.popI32()
        val offset = stack.popI32()
        val arrayType = instance.module().typeSection().getSubType(typeIndex).compType().arrayType()!!
        val elementSize = arrayType.fieldType().storageType().byteSize()
        val data = instance.dataSegmentData(dataIndex)
        if (offset < 0 || length < 0 || offset + length * elementSize > data.size) {
            throw TrapException("out of bounds memory access")
        }

        val elements = LongArray(length)
        for (index in 0 until length) {
            elements[index] = readFromData(data, offset + index * elementSize, elementSize)
        }
        stack.push(instance.registerGcRef(WasmArray(typeIndex, elements)))
    }

    private fun readFromData(data: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (index in 0 until size) {
            value = value or ((data[offset + index].toInt() and 0xFF).toLong() shl (index * 8))
        }
        return value
    }

    private data class FastContinuationState(
        val stackValues: LongArray,
        val frames: List<FastFrame>,
        val returnSlotCount: Int,
    )

    private class FastFunction(
        val opcodes: IntArray,
        val operands: LongArray,
        val operands2: IntArray,
        val operands3: IntArray,
        val labelTrue: IntArray,
        val labelFalse: IntArray,
        val localTypes: List<ValType>,
        val localSlots: IntArray,
        val localSlotCount: Int,
    ) {
        companion object {
            fun lower(type: FunctionType, body: FunctionBody): FastFunction? {
                val instructions = body.instructions()
                val opcodes = IntArray(instructions.size)
                val operands = LongArray(instructions.size)
                val operands2 = IntArray(instructions.size)
                val operands3 = IntArray(instructions.size)
                val labelTrue = IntArray(instructions.size)
                val labelFalse = IntArray(instructions.size)
                val localTypes = type.params() + body.localTypes()
                val localSlots = localSlotsFor(localTypes)
                val controlParameterlessLoopStack = BooleanArray(instructions.size + 1)

                var index = 0
                var controlDepth = 0
                while (index < instructions.size) {
                    val countdownEnd =
                        tryBuildCountdownBranch(
                            instructions,
                            index,
                            localTypes,
                            localSlots,
                            opcodes,
                            operands,
                            operands2,
                            operands3,
                            labelTrue,
                            labelFalse,
                            controlDepth > 0 && controlParameterlessLoopStack[controlDepth - 1],
                        )
                    if (countdownEnd > index) {
                        index = countdownEnd
                        continue
                    }

                    val superInstructionEnd =
                        tryBuildSuperInstruction(
                            instructions,
                            index,
                            localTypes,
                            localSlots,
                            opcodes,
                            operands,
                            operands2,
                            operands3,
                        )
                    if (superInstructionEnd > index) {
                        index = superInstructionEnd
                        continue
                    }

                    val instruction = instructions[index]
                    val opcode = fastOpcode(instruction.opcode())
                    if (opcode == FAST_UNSUPPORTED) {
                        return null
                    }
                    opcodes[index] = opcode
                    operands[index] =
                        when (opcode) {
                            FAST_BLOCK,
                            FAST_LOOP,
                            FAST_IF -> loweredBlockType(instruction)
                            FAST_LOCAL_GET,
                            FAST_LOCAL_SET,
                            FAST_LOCAL_TEE -> {
                                val slot = localSlot(instruction, localTypes, localSlots)
                                if (slot < 0) return null
                                slot.toLong()
                            }
                            FAST_BR,
                            FAST_BR_IF,
                            FAST_CALL,
                            FAST_GLOBAL_GET,
                            FAST_GLOBAL_SET,
                            FAST_I32_CONST,
                            FAST_I64_CONST,
                            FAST_F64_CONST -> instruction.operand(0)
                            FAST_I32_LOAD,
                            FAST_I32_LOAD8_S,
                            FAST_I32_LOAD8_U,
                            FAST_I32_LOAD16_S,
                            FAST_I32_LOAD16_U,
                            FAST_I64_LOAD,
                            FAST_I32_STORE,
                            FAST_I32_STORE8,
                            FAST_I32_STORE16,
                            FAST_I64_STORE -> instruction.operand(1)
                            else -> 0
                        }
                    operands2[index] =
                        when (opcode) {
                            FAST_I32_LOAD,
                            FAST_I32_LOAD8_S,
                            FAST_I32_LOAD8_U,
                            FAST_I32_LOAD16_S,
                            FAST_I32_LOAD16_U,
                            FAST_I64_LOAD,
                            FAST_I32_STORE,
                            FAST_I32_STORE8,
                            FAST_I32_STORE16,
                            FAST_I64_STORE ->
                                if (instruction.operandCount() > 2) instruction.operand(2).toInt() else 0
                            else -> operands2[index]
                        }
                    labelTrue[index] =
                        when (opcode) {
                            FAST_ELSE,
                            FAST_BR,
                            FAST_BR_IF -> instruction.labelTrue()
                            else -> 0
                        }
                    labelFalse[index] =
                        if (opcode == FAST_IF || opcode == FAST_BR_IF) {
                            instruction.labelFalse()
                        } else {
                            0
                        }
                    when (opcode) {
                        FAST_BLOCK,
                        FAST_IF -> {
                            controlParameterlessLoopStack[controlDepth] = false
                            controlDepth++
                        }
                        FAST_LOOP -> {
                            controlParameterlessLoopStack[controlDepth] =
                                operands[index].toInt() == EMPTY_BLOCK_TYPE
                            controlDepth++
                        }
                        FAST_END -> {
                            if (controlDepth > 0) controlDepth--
                        }
                    }
                    index++
                }

                return FastFunction(
                    opcodes,
                    operands,
                    operands2,
                    operands3,
                    labelTrue,
                    labelFalse,
                    localTypes,
                    localSlots,
                    ValType.sizeOf(localTypes),
                )
            }

            private fun tryBuildSuperInstruction(
                instructions: List<AnnotatedInstruction>,
                index: Int,
                localTypes: List<ValType>,
                localSlots: IntArray,
                opcodes: IntArray,
                operands: LongArray,
                operands2: IntArray,
                operands3: IntArray,
            ): Int {
                if (
                    index + 3 < instructions.size &&
                        instructions[index].opcode() == OpCode.LOCAL_GET &&
                        instructions[index + 1].opcode() == OpCode.LOCAL_GET &&
                        instructions[index + 2].opcode() == OpCode.I32_ADD &&
                        (
                            instructions[index + 3].opcode() == OpCode.LOCAL_SET ||
                                instructions[index + 3].opcode() == OpCode.LOCAL_TEE
                        )
                ) {
                    val firstSlot = localSlot(instructions[index], localTypes, localSlots)
                    val secondSlot = localSlot(instructions[index + 1], localTypes, localSlots)
                    val targetSlot = localSlot(instructions[index + 3], localTypes, localSlots)
                    if (firstSlot < 0 || secondSlot < 0 || targetSlot < 0) return index
                    opcodes[index] =
                        if (instructions[index + 3].opcode() == OpCode.LOCAL_SET) {
                            FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_SET
                        } else {
                            FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_TEE
                        }
                    operands[index] = firstSlot.toLong()
                    operands2[index] = secondSlot
                    operands3[index] = targetSlot
                    return index + 4
                }

                if (index + 1 < instructions.size) {
                    val first = instructions[index]
                    val second = instructions[index + 1]
                    when {
                        first.opcode() == OpCode.I32_CONST && second.opcode() == OpCode.LOCAL_SET -> {
                            val targetSlot = localSlot(second, localTypes, localSlots)
                            if (targetSlot < 0) return index
                            opcodes[index] = FAST_I32_CONST_LOCAL_SET
                            operands[index] = first.operand(0)
                            operands2[index] = targetSlot
                            return index + 2
                        }

                        first.opcode() == OpCode.I32_ADD &&
                            (
                                second.opcode() == OpCode.LOCAL_SET ||
                                    second.opcode() == OpCode.LOCAL_TEE
                            ) -> {
                            val targetSlot = localSlot(second, localTypes, localSlots)
                            if (targetSlot < 0) return index
                            opcodes[index] =
                                if (second.opcode() == OpCode.LOCAL_SET) {
                                    FAST_I32_ADD_LOCAL_SET
                                } else {
                                    FAST_I32_ADD_LOCAL_TEE
                                }
                            operands[index] = targetSlot.toLong()
                            return index + 2
                        }
                    }
                }

                return index
            }

            private fun tryBuildCountdownBranch(
                instructions: List<AnnotatedInstruction>,
                index: Int,
                localTypes: List<ValType>,
                localSlots: IntArray,
                opcodes: IntArray,
                operands: LongArray,
                operands2: IntArray,
                operands3: IntArray,
                labelTrue: IntArray,
                labelFalse: IntArray,
                hasCurrentParameterlessLoop: Boolean,
            ): Int {
                if (index + 4 >= instructions.size || instructions[index].opcode() != OpCode.LOCAL_GET) return index

                val constInstruction = instructions[index + 1]
                val subInstruction = instructions[index + 2]
                val teeInstruction = instructions[index + 3]
                val branchInstruction = instructions[index + 4]
                if (
                    constInstruction.opcode() != OpCode.I32_CONST ||
                        subInstruction.opcode() != OpCode.I32_SUB ||
                        teeInstruction.opcode() != OpCode.LOCAL_TEE ||
                        branchInstruction.opcode() != OpCode.BR_IF
                ) {
                    return index
                }

                if (instructions[index].operand(0).toInt() != teeInstruction.operand(0).toInt()) return index
                val localSlot = localSlot(instructions[index], localTypes, localSlots)
                if (localSlot < 0) return index

                opcodes[index] = FAST_I32_COUNTDOWN_BRANCH
                operands[index] = localSlot.toLong()
                operands2[index] = constInstruction.operand(0).toInt()
                val branchDepth = branchInstruction.operand(0).toInt()
                operands3[index] =
                    if (branchDepth == 0 && hasCurrentParameterlessLoop) {
                        FAST_CURRENT_PARAMETERLESS_LOOP_DEPTH
                    } else {
                        branchDepth
                    }
                labelTrue[index] = branchInstruction.labelTrue()
                labelFalse[index] = branchInstruction.labelFalse()
                return index + 5
            }

            private fun loweredBlockType(instruction: AnnotatedInstruction): Long =
                if (instruction.operandCount() == 0) EMPTY_BLOCK_TYPE.toLong() else instruction.operand(0)

            private fun localSlot(
                instruction: AnnotatedInstruction,
                localTypes: List<ValType>,
                localSlots: IntArray,
            ): Int {
                val localIndex = instruction.operand(0).toInt()
                if (localIndex < 0 || localIndex >= localTypes.size) return -1
                if (slotWidth(localTypes[localIndex]) != 1) return -1
                return localSlots[localIndex]
            }
        }
    }

    private class FastFrame private constructor(
        private val loweredFunction: FastFunction?,
        private val instructions: List<AnnotatedInstruction>?,
        private val localTypes: List<ValType>,
        private val localSlots: IntArray,
        private val locals: LongArray,
        private val controlStack: FastControlStack,
        private var pc: Int,
        val returnSlotCount: Int,
    ) {
        constructor(
            type: FunctionType,
            body: FunctionBody,
            args: LongArray,
            loweredFunction: FastFunction?,
        ) : this(
            loweredFunction,
            if (loweredFunction == null) body.instructions() else null,
            loweredFunction?.localTypes ?: (type.params() + body.localTypes()),
            loweredFunction?.localSlots ?: localSlotsFor(type.params() + body.localTypes()),
            LongArray(loweredFunction?.localSlotCount ?: ValType.sizeOf(type.params() + body.localTypes())),
            FastControlStack(),
            0,
            type.returnSlotCount(),
        ) {
            args.copyInto(locals, endIndex = args.size)
        }

        fun loweredFunction(): FastFunction? = loweredFunction

        fun terminated(): Boolean = pc >= (loweredFunction?.opcodes?.size ?: instructions!!.size)

        fun currentPc(): Int = pc

        fun updatePc(newPc: Int) {
            pc = newPc
        }

        fun localsArray(): LongArray = locals

        fun snapshot(): FastFrame =
            FastFrame(
                loweredFunction,
                instructions,
                localTypes,
                localSlots.copyOf(),
                locals.copyOf(),
                controlStack.snapshot(),
                pc,
                returnSlotCount,
            )

        fun nextInstruction(): AnnotatedInstruction = instructions!![pc++]

        fun nextIndex(): Int = pc++

        fun jumpTo(newPc: Int) {
            pc = newPc
        }

        fun pushControl(instruction: AnnotatedInstruction, stack: FastValueStack) {
            pushControl(fastOpcode(instruction.opcode()), blockType(instruction), stack)
        }

        fun pushControl(opcode: Int, blockType: Long, stack: FastValueStack) {
            pushControl(opcode, blockType, stack.size())
        }

        fun pushControl(opcode: Int, blockType: Long, stackHeight: Int) {
            val startSlots = controlStartSlots(blockType)
            val endSlots = controlEndSlots(blockType)
            controlStack.push(opcode, startSlots, endSlots, stackHeight - startSlots)
        }

        fun pushIf(instruction: AnnotatedInstruction, stack: FastValueStack) {
            pushIf(blockType(instruction), instruction.labelTrue(), instruction.labelFalse(), stack)
        }

        fun pushIf(blockType: Long, trueLabel: Int, falseLabel: Int, stack: FastValueStack) {
            val pred = stack.pop()
            pushControl(FAST_IF, blockType, stack)
            jumpTo(if (pred == 0L) falseLabel else trueLabel)
        }

        fun endControl(stack: FastValueStack): Boolean {
            if (!controlStack.popOrNull()) return false
            stack.transferTo(controlStack.selectedHeight(), controlStack.selectedEndSlots())
            return true
        }

        fun branchTo(depth: Int, label: Int, stack: FastValueStack) {
            branch(depth, stack)
            jumpTo(label)
        }

        fun branch(depth: Int, stack: FastValueStack) {
            controlStack.branchTo(depth)
            if (controlStack.selectedOpcode() == FAST_LOOP) {
                stack.transferTo(controlStack.selectedHeight(), controlStack.selectedStartSlots())
            }
        }

        fun branchToCurrentParameterlessLoop(stack: FastValueStack) {
            stack.discardToSize(controlStack.peekTopHeight())
        }

        fun currentControlHeight(): Int = controlStack.peekTopHeight()

        fun pushLocal(index: Int, stack: FastValueStack) {
            val slot = localSlot(index)
            when (slotWidth(localTypes[index])) {
                1 -> stack.push(locals[slot])
                2 -> {
                    stack.push(locals[slot])
                    stack.push(locals[slot + 1])
                }
                else -> unsupportedLocal(index)
            }
        }

        fun popLocal(index: Int, stack: FastValueStack) {
            val slot = localSlot(index)
            when (slotWidth(localTypes[index])) {
                1 -> locals[slot] = stack.pop()
                2 -> {
                    val high = stack.pop()
                    val low = stack.pop()
                    locals[slot] = low
                    locals[slot + 1] = high
                }
                else -> unsupportedLocal(index)
            }
        }

        fun teeLocal(index: Int, stack: FastValueStack) {
            val slot = localSlot(index)
            when (slotWidth(localTypes[index])) {
                1 -> locals[slot] = stack.peek()
                2 -> {
                    locals[slot] = stack.peek(1)
                    locals[slot + 1] = stack.peek(0)
                }
                else -> unsupportedLocal(index)
            }
        }

        private fun localSlot(index: Int): Int {
            if (index < 0 || index >= localSlots.size) {
                throw WasmRuntimeException("unknown local $index")
            }
            return localSlots[index]
        }

        private fun unsupportedLocal(index: Int): Nothing =
            throw WasmEngineException("unsupported local slot width for local $index")

        private fun controlStartSlots(blockType: Long): Int =
            when (blockType.toInt()) {
                EMPTY_BLOCK_TYPE -> 0
                ValType.ID.I32,
                ValType.ID.I64,
                ValType.ID.F32,
                ValType.ID.F64 -> 0
                ValType.ID.V128 -> 0
                else -> unsupportedBlockType(blockType)
            }

        private fun controlEndSlots(blockType: Long): Int =
            when (blockType.toInt()) {
                EMPTY_BLOCK_TYPE -> 0
                ValType.ID.I32,
                ValType.ID.I64,
                ValType.ID.F32,
                ValType.ID.F64 -> 1
                ValType.ID.V128 -> 2
                else -> unsupportedBlockType(blockType)
            }

        private fun blockType(instruction: AnnotatedInstruction): Long =
            if (instruction.operandCount() == 0) EMPTY_BLOCK_TYPE.toLong() else instruction.operand(0)

        private fun unsupportedBlockType(blockType: Long): Nothing =
            throw ExperimentalFastInterpreterUnsupportedException(
                "block type $blockType"
            )

        private class FastControlStack {
            private var opcodes = IntArray(16)
            private var startSlots = IntArray(16)
            private var endSlots = IntArray(16)
            private var heights = IntArray(16)
            private var count = 0
            private var selectedOpcode = 0
            private var selectedStartSlots = 0
            private var selectedEndSlots = 0
            private var selectedHeight = 0

            fun push(opcode: Int, startSlots: Int, endSlots: Int, height: Int) {
                ensureCapacity(count + 1)
                opcodes[count] = opcode
                this.startSlots[count] = startSlots
                this.endSlots[count] = endSlots
                heights[count] = height
                count++
            }

            fun popOrNull(): Boolean {
                if (count == 0) return false
                select(--count)
                return true
            }

            fun branchTo(depth: Int) {
                val targetIndex = count - depth - 1
                if (targetIndex < 0 || targetIndex >= count) {
                    throw WasmRuntimeException("unknown branch depth $depth")
                }
                select(targetIndex)
                count = targetIndex + 1
            }

            fun peekTopHeight(): Int {
                if (count == 0) throw WasmRuntimeException("control stack underflow")
                return heights[count - 1]
            }

            fun selectedOpcode(): Int = selectedOpcode

            fun selectedStartSlots(): Int = selectedStartSlots

            fun selectedEndSlots(): Int = selectedEndSlots

            fun selectedHeight(): Int = selectedHeight

            fun snapshot(): FastControlStack {
                val copy = FastControlStack()
                copy.opcodes = opcodes.copyOf()
                copy.startSlots = startSlots.copyOf()
                copy.endSlots = endSlots.copyOf()
                copy.heights = heights.copyOf()
                copy.count = count
                return copy
            }

            private fun select(index: Int) {
                selectedOpcode = opcodes[index]
                selectedStartSlots = startSlots[index]
                selectedEndSlots = endSlots[index]
                selectedHeight = heights[index]
            }

            private fun ensureCapacity(capacity: Int) {
                if (capacity <= opcodes.size) return
                val newSize = opcodes.size * 2
                opcodes = opcodes.copyOf(newSize)
                startSlots = startSlots.copyOf(newSize)
                endSlots = endSlots.copyOf(newSize)
                heights = heights.copyOf(newSize)
            }
        }
    }

    private class FastValueStack {
        private var values = LongArray(64)
        private var count = 0

        fun size(): Int = count

        fun rawCount(): Int = count

        fun setRawCount(count: Int) {
            this.count = count
        }

        fun valuesArray(): LongArray = values

        fun ensureCapacityAndGet(capacity: Int): LongArray {
            ensureCapacity(capacity)
            return values
        }

        fun push(value: Long) {
            ensureCapacity(count + 1)
            values[count++] = value
        }

        fun push(value: Int) {
            push(value.toLong())
        }

        fun pop(): Long {
            if (count == 0) throw WasmRuntimeException("value stack underflow")
            return values[--count]
        }

        fun popI32(): Int = pop().toInt()

        fun peek(depth: Int = 0): Long {
            val index = count - depth - 1
            if (index < 0) throw WasmRuntimeException("value stack underflow")
            return values[index]
        }

        fun popResults(slotCount: Int): LongArray {
            val results = LongArray(slotCount)
            for (index in slotCount - 1 downTo 0) {
                results[index] = pop()
            }
            return results
        }

        fun transferTo(height: Int, slotCount: Int) {
            val values = popResults(slotCount)
            discardToSize(height)
            for (value in values) {
                push(value)
            }
        }

        fun snapshot(): LongArray = values.copyOf(count)

        fun restore(snapshot: LongArray) {
            ensureCapacity(snapshot.size)
            snapshot.copyInto(values)
            count = snapshot.size
        }

        fun discardToSize(size: Int) {
            count = size
        }

        private fun ensureCapacity(capacity: Int) {
            if (capacity <= values.size) return
            values = values.copyOf(values.size * 2)
        }
    }

    companion object {
        private const val TRUE = 1L
        private const val FALSE = 0L
        private const val EMPTY_BLOCK_TYPE = 0x40

        private const val FAST_UNSUPPORTED = -1
        private const val FAST_NOP = 0
        private const val FAST_UNREACHABLE = 1
        private const val FAST_BLOCK = 2
        private const val FAST_LOOP = 3
        private const val FAST_IF = 4
        private const val FAST_ELSE = 5
        private const val FAST_END = 6
        private const val FAST_RETURN = 7
        private const val FAST_BR = 8
        private const val FAST_BR_IF = 9
        private const val FAST_CALL = 10
        private const val FAST_DROP = 11
        private const val FAST_LOCAL_GET = 12
        private const val FAST_LOCAL_SET = 13
        private const val FAST_LOCAL_TEE = 14
        private const val FAST_I32_CONST = 15
        private const val FAST_I64_CONST = 16
        private const val FAST_I32_ADD = 17
        private const val FAST_I32_SUB = 18
        private const val FAST_I32_MUL = 19
        private const val FAST_I32_CONST_LOCAL_SET = 20
        private const val FAST_I32_ADD_LOCAL_SET = 21
        private const val FAST_I32_ADD_LOCAL_TEE = 22
        private const val FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_SET = 23
        private const val FAST_LOCAL_GET_LOCAL_GET_I32_ADD_LOCAL_TEE = 24
        private const val FAST_I32_COUNTDOWN_BRANCH = 25
        private const val FAST_SELECT = 26
        private const val FAST_GLOBAL_GET = 27
        private const val FAST_GLOBAL_SET = 28
        private const val FAST_F64_CONST = 29
        private const val FAST_I32_DIV_S = 30
        private const val FAST_I32_DIV_U = 31
        private const val FAST_I32_REM_S = 32
        private const val FAST_I32_REM_U = 33
        private const val FAST_I32_AND = 34
        private const val FAST_I32_OR = 35
        private const val FAST_I32_XOR = 36
        private const val FAST_I32_SHL = 37
        private const val FAST_I32_SHR_S = 38
        private const val FAST_I32_SHR_U = 39
        private const val FAST_I32_EQZ = 40
        private const val FAST_I32_EQ = 41
        private const val FAST_I32_NE = 42
        private const val FAST_I32_LT_S = 43
        private const val FAST_I32_LT_U = 44
        private const val FAST_I32_GT_S = 45
        private const val FAST_I32_GT_U = 46
        private const val FAST_I32_LE_S = 47
        private const val FAST_I32_LE_U = 48
        private const val FAST_I32_GE_S = 49
        private const val FAST_I32_GE_U = 50
        private const val FAST_I64_ADD = 51
        private const val FAST_I64_SUB = 52
        private const val FAST_I64_MUL = 53
        private const val FAST_I64_EQZ = 54
        private const val FAST_I64_EQ = 55
        private const val FAST_I64_NE = 56
        private const val FAST_I32_LOAD = 57
        private const val FAST_I32_LOAD8_S = 58
        private const val FAST_I32_LOAD8_U = 59
        private const val FAST_I32_LOAD16_S = 60
        private const val FAST_I32_LOAD16_U = 61
        private const val FAST_I64_LOAD = 62
        private const val FAST_I32_STORE = 63
        private const val FAST_I32_STORE8 = 64
        private const val FAST_I32_STORE16 = 65
        private const val FAST_I64_STORE = 66
        private const val FAST_CURRENT_PARAMETERLESS_LOOP_DEPTH = -1

        fun factory(): (Instance) -> Machine = ::ExperimentalFastInterpreterMachine

        private fun fastOpcode(opcode: OpCode): Int =
            when (opcode) {
                OpCode.NOP -> FAST_NOP
                OpCode.UNREACHABLE -> FAST_UNREACHABLE
                OpCode.BLOCK -> FAST_BLOCK
                OpCode.LOOP -> FAST_LOOP
                OpCode.IF -> FAST_IF
                OpCode.ELSE -> FAST_ELSE
                OpCode.END -> FAST_END
                OpCode.RETURN -> FAST_RETURN
                OpCode.BR -> FAST_BR
                OpCode.BR_IF -> FAST_BR_IF
                OpCode.CALL -> FAST_CALL
                OpCode.DROP -> FAST_DROP
                OpCode.SELECT -> FAST_SELECT
                OpCode.LOCAL_GET -> FAST_LOCAL_GET
                OpCode.LOCAL_SET -> FAST_LOCAL_SET
                OpCode.LOCAL_TEE -> FAST_LOCAL_TEE
                OpCode.GLOBAL_GET -> FAST_GLOBAL_GET
                OpCode.GLOBAL_SET -> FAST_GLOBAL_SET
                OpCode.I32_CONST -> FAST_I32_CONST
                OpCode.I64_CONST -> FAST_I64_CONST
                OpCode.F64_CONST -> FAST_F64_CONST
                OpCode.I32_ADD -> FAST_I32_ADD
                OpCode.I32_SUB -> FAST_I32_SUB
                OpCode.I32_MUL -> FAST_I32_MUL
                OpCode.I32_DIV_S -> FAST_I32_DIV_S
                OpCode.I32_DIV_U -> FAST_I32_DIV_U
                OpCode.I32_REM_S -> FAST_I32_REM_S
                OpCode.I32_REM_U -> FAST_I32_REM_U
                OpCode.I32_AND -> FAST_I32_AND
                OpCode.I32_OR -> FAST_I32_OR
                OpCode.I32_XOR -> FAST_I32_XOR
                OpCode.I32_SHL -> FAST_I32_SHL
                OpCode.I32_SHR_S -> FAST_I32_SHR_S
                OpCode.I32_SHR_U -> FAST_I32_SHR_U
                OpCode.I32_EQZ -> FAST_I32_EQZ
                OpCode.I32_EQ -> FAST_I32_EQ
                OpCode.I32_NE -> FAST_I32_NE
                OpCode.I32_LT_S -> FAST_I32_LT_S
                OpCode.I32_LT_U -> FAST_I32_LT_U
                OpCode.I32_GT_S -> FAST_I32_GT_S
                OpCode.I32_GT_U -> FAST_I32_GT_U
                OpCode.I32_LE_S -> FAST_I32_LE_S
                OpCode.I32_LE_U -> FAST_I32_LE_U
                OpCode.I32_GE_S -> FAST_I32_GE_S
                OpCode.I32_GE_U -> FAST_I32_GE_U
                OpCode.I64_ADD -> FAST_I64_ADD
                OpCode.I64_SUB -> FAST_I64_SUB
                OpCode.I64_MUL -> FAST_I64_MUL
                OpCode.I64_EQZ -> FAST_I64_EQZ
                OpCode.I64_EQ -> FAST_I64_EQ
                OpCode.I64_NE -> FAST_I64_NE
                OpCode.I32_LOAD -> FAST_I32_LOAD
                OpCode.I32_LOAD8_S -> FAST_I32_LOAD8_S
                OpCode.I32_LOAD8_U -> FAST_I32_LOAD8_U
                OpCode.I32_LOAD16_S -> FAST_I32_LOAD16_S
                OpCode.I32_LOAD16_U -> FAST_I32_LOAD16_U
                OpCode.I64_LOAD -> FAST_I64_LOAD
                OpCode.I32_STORE -> FAST_I32_STORE
                OpCode.I32_STORE8 -> FAST_I32_STORE8
                OpCode.I32_STORE16 -> FAST_I32_STORE16
                OpCode.I64_STORE -> FAST_I64_STORE
                else -> FAST_UNSUPPORTED
            }

        private fun localSlotsFor(localTypes: List<ValType>): IntArray {
            val localSlots = IntArray(localTypes.size)
            var slot = 0
            for (index in localTypes.indices) {
                val width = slotWidth(localTypes[index])
                localSlots[index] = slot
                slot += width
            }
            return localSlots
        }

        private fun slotWidth(type: ValType): Int =
            if (type.opcode() == ValType.ID.V128) 2 else 1
    }
}

class ExperimentalFastInterpreterUnsupportedException : WasmEngineException {
    constructor(opcode: OpCode) :
        super("experimental fast interpreter does not support opcode $opcode yet")

    constructor(feature: String) :
        super("experimental fast interpreter does not support $feature yet")
}

/**
 * Opts this instance into the standalone experimental interpreter backend.
 *
 * The default interpreter is unchanged unless this is called.
 */
fun Instance.Builder.withExperimentalFastInterpreter(): Instance.Builder =
    withMachineFactory(ExperimentalFastInterpreterMachine.factory())
