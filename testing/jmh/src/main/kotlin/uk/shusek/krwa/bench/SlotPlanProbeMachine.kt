package uk.shusek.krwa.bench

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.OpcodeOps
import uk.shusek.krwa.runtime.WasmRuntimeException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

/**
 * CoreMark-only slot-plan interpreter prototype.
 *
 * This lives in the benchmark source set on purpose. It measures the Chasm-shaped execution model:
 * predecode local.get/const into operand sources and dispatch only the consuming operations.
 */
internal class SlotPlanProbeMachine(
    private val instance: Instance,
) : Machine {
    private var plans: Array<SlotPlan?>? = null
    private lateinit var bodies: Array<FunctionBody?>
    private lateinit var types: Array<FunctionType>
    private lateinit var slotPools: Array<ArrayDeque<LongArray>>
    private val stackPool = ArrayDeque<LongArray>()
    private val memory0 by lazy { instance.memory(0) }

    override fun call(funcId: Int, args: LongArray): LongArray {
        initializeCaches()
        return executeFunction(funcId, args)
    }

    private fun initializeCaches() {
        if (plans != null) return
        val functionCount = instance.functionCount()
        plans = arrayOfNulls(functionCount)
        bodies = Array(functionCount) { index -> instance.function(index) }
        types = Array(functionCount) { index -> instance.type(instance.functionType(index)) }
        slotPools = Array(functionCount) { ArrayDeque() }
    }

    private fun executeFunction(funcId: Int, args: LongArray): LongArray {
        val body = bodies[funcId] ?: return callImport(funcId, args)
        val type = types[funcId]
        val plan = plan(funcId, type, body)
        val slots = borrowSlots(funcId, plan.slotCount)
        val stack = borrowStack()
        args.copyInto(slots, endIndex = args.size)

        val ctrlOps = IntArray(plan.maxControlDepth + 2)
        val ctrlStart = IntArray(plan.maxControlDepth + 2)
        val ctrlEnd = IntArray(plan.maxControlDepth + 2)
        val ctrlHeight = IntArray(plan.maxControlDepth + 2)
        var ctrlSize = 1
        ctrlOps[0] = CTRL_CALL
        ctrlEnd[0] = plan.returnCount

        var sp = 0
        var pc = 0
        try {
            while (pc < plan.ops.size) {
                when (plan.ops[pc]) {
                    OP_MATERIALIZE -> {
                        val kind = plan.src0Kind[pc]
                        val value = plan.src0[pc]
                        val materialized =
                            when (kind) {
                                SRC_SLOT -> slots[value.toInt()]
                                SRC_CONST -> value
                                SRC_STACK -> stack[--sp]
                                else -> error("bad source kind $kind")
                            }
                        stack[sp++] = materialized
                        pc++
                    }
                    OP_MATERIALIZE_SLOT -> {
                        stack[sp++] = slots[plan.src0[pc].toInt()]
                        pc++
                    }
                    OP_MATERIALIZE_CONST -> {
                        stack[sp++] = plan.src0[pc]
                        pc++
                    }
                    OP_UNREACHABLE -> throw WasmRuntimeException("unreachable")
                    OP_BLOCK,
                    OP_LOOP -> {
                        ctrlOps[ctrlSize] = plan.mode[pc]
                        ctrlStart[ctrlSize] = plan.target0[pc]
                        ctrlEnd[ctrlSize] = plan.target1[pc]
                        ctrlHeight[ctrlSize] = sp - plan.target0[pc]
                        ctrlSize++
                        pc++
                    }
                    OP_IF -> {
                        var sp0 = sp
                        val pred =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        sp = sp0
                        ctrlOps[ctrlSize] = CTRL_IF
                        ctrlStart[ctrlSize] = plan.controlStart[pc]
                        ctrlEnd[ctrlSize] = plan.controlEnd[pc]
                        ctrlHeight[ctrlSize] = sp - plan.controlStart[pc]
                        ctrlSize++
                        pc = if (pred == 0) plan.target0[pc] else plan.target1[pc]
                    }
                    OP_ELSE -> pc = plan.target0[pc]
                    OP_END -> {
                        ctrlSize--
                        sp = transferStack(stack, sp, ctrlHeight[ctrlSize], ctrlStart[ctrlSize], ctrlEnd[ctrlSize])
                        if (ctrlSize == 0) {
                            return popResults(stack, sp, plan.returnCount)
                        }
                        pc++
                    }
                    OP_BR -> {
                        val branch = branchTo(plan.mode[pc], ctrlOps, ctrlStart, ctrlEnd, ctrlHeight, ctrlSize, stack, sp)
                        ctrlSize = branch.ctrlSize
                        sp = branch.sp
                        pc = plan.target0[pc]
                    }
                    OP_BR_IF -> {
                        var sp0 = sp
                        val pred =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        sp = sp0
                        if (pred == 0) {
                            pc = plan.target0[pc]
                        } else {
                            val branch =
                                branchTo(plan.mode[pc], ctrlOps, ctrlStart, ctrlEnd, ctrlHeight, ctrlSize, stack, sp)
                            ctrlSize = branch.ctrlSize
                            sp = branch.sp
                            pc = plan.target1[pc]
                        }
                    }
                    OP_BR_TABLE -> {
                        var sp0 = sp
                        val pred =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        sp = sp0
                        val tableIndex = plan.target0[pc]
                        val table = plan.tables[tableIndex]
                        val depths = plan.tableDepths[tableIndex]
                        val target =
                            if (pred < 0 || pred >= table.size - 1) {
                                table.size - 1
                            } else {
                                pred
                            }
                        val branch = branchTo(depths[target], ctrlOps, ctrlStart, ctrlEnd, ctrlHeight, ctrlSize, stack, sp)
                        ctrlSize = branch.ctrlSize
                        sp = branch.sp
                        pc = table[target]
                    }
                    OP_RETURN -> {
                        sp = transferStack(stack, sp, ctrlHeight[0], ctrlStart[0], ctrlEnd[0])
                        return popResults(stack, sp, plan.returnCount)
                    }
                    OP_CALL -> {
                        val target = plan.target0[pc]
                        val argCount = plan.target1[pc]
                        val callArgs = LongArray(argCount)
                        for (index in 0 until argCount) {
                            callArgs[index] = stack[sp - argCount + index]
                        }
                        sp -= argCount
                        val results = executeFunction(target, callArgs)
                        for (result in results) {
                            stack[sp++] = result
                        }
                        pc++
                    }
                    OP_SET_SLOT -> {
                        var sp0 = sp
                        val stored =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }
                        slots[plan.dst[pc]] = stored
                        sp = sp0
                        pc++
                    }
                    OP_SET_SLOT_SLOT -> {
                        slots[plan.dst[pc]] = slots[plan.src0[pc].toInt()]
                        pc++
                    }
                    OP_SET_SLOT_CONST -> {
                        slots[plan.dst[pc]] = plan.src0[pc]
                        pc++
                    }
                    OP_GLOBAL_GET -> {
                        slots[plan.dst[pc]] = instance.global(plan.target0[pc]).valueLow
                        pc++
                    }
                    OP_GLOBAL_SET -> {
                        var sp0 = sp
                        instance.global(plan.target0[pc]).value =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }
                        sp = sp0
                        pc++
                    }
                    OP_I32_BIN -> {
                        var sp0 = sp
                        val right =
                            when (plan.src1Kind[pc]) {
                                SRC_SLOT -> slots[plan.src1[pc].toInt()]
                                SRC_CONST -> plan.src1[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src1Kind[pc]}")
                            }.toInt()
                        val left =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                I32_ADD -> left + right
                                I32_SUB -> left - right
                                I32_MUL -> left * right
                                I32_DIV_U -> OpcodeOps.I32_DIV_U(left, right)
                                I32_REM_S -> OpcodeOps.I32_REM_S(left, right)
                                I32_AND -> left and right
                                I32_OR -> left or right
                                I32_XOR -> left xor right
                                I32_SHL -> left shl right
                                I32_SHR_S -> left shr right
                                I32_SHR_U -> left ushr right
                                I32_EQ -> if (left == right) 1 else 0
                                I32_NE -> if (left != right) 1 else 0
                                I32_LT_S -> if (left < right) 1 else 0
                                I32_LT_U -> if (left.toUInt() < right.toUInt()) 1 else 0
                                I32_GT_S -> if (left > right) 1 else 0
                                I32_GT_U -> if (left.toUInt() > right.toUInt()) 1 else 0
                                I32_LE_S -> if (left <= right) 1 else 0
                                I32_LE_U -> if (left.toUInt() <= right.toUInt()) 1 else 0
                                I32_GE_S -> if (left >= right) 1 else 0
                                I32_GE_U -> if (left.toUInt() >= right.toUInt()) 1 else 0
                                else -> error("bad i32 op ${plan.mode[pc]}")
                            }.toLong()
                        sp = sp0
                        pc++
                    }
                    OP_I32_BIN_SLOT_CONST -> {
                        val left = slots[plan.src0[pc].toInt()].toInt()
                        val right = plan.src1[pc].toInt()
                        slots[plan.dst[pc]] = i32Binary(plan.mode[pc], left, right)
                        pc++
                    }
                    OP_I32_BIN_SLOT_SLOT -> {
                        val left = slots[plan.src0[pc].toInt()].toInt()
                        val right = slots[plan.src1[pc].toInt()].toInt()
                        slots[plan.dst[pc]] = i32Binary(plan.mode[pc], left, right)
                        pc++
                    }
                    OP_I64_BIN -> {
                        var sp0 = sp
                        val right =
                            when (plan.src1Kind[pc]) {
                                SRC_SLOT -> slots[plan.src1[pc].toInt()]
                                SRC_CONST -> plan.src1[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src1Kind[pc]}")
                            }
                        val left =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                I64_SUB -> left - right
                                else -> error("bad i64 op ${plan.mode[pc]}")
                            }
                        sp = sp0
                        pc++
                    }
                    OP_F64_BIN -> {
                        var sp0 = sp
                        val right =
                            Double.fromBits(
                                when (plan.src1Kind[pc]) {
                                    SRC_SLOT -> slots[plan.src1[pc].toInt()]
                                    SRC_CONST -> plan.src1[pc]
                                    SRC_STACK -> stack[--sp0]
                                    else -> error("bad source kind ${plan.src1Kind[pc]}")
                                }
                            )
                        val left =
                            Double.fromBits(
                                when (plan.src0Kind[pc]) {
                                    SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                    SRC_CONST -> plan.src0[pc]
                                    SRC_STACK -> stack[--sp0]
                                    else -> error("bad source kind ${plan.src0Kind[pc]}")
                                }
                            )
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                F64_DIV -> (left / right).toRawBits()
                                F64_LT -> OpcodeOps.F64_LT(left, right).toLong()
                                F64_GE -> OpcodeOps.F64_GE(left, right).toLong()
                                else -> error("bad f64 op ${plan.mode[pc]}")
                            }
                        sp = sp0
                        pc++
                    }
                    OP_UNARY -> {
                        var sp0 = sp
                        val value =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                UN_I32_EQZ -> if (value.toInt() == 0) TRUE else FALSE
                                UN_F64_CONVERT_I64_U -> OpcodeOps.F64_CONVERT_I64_U(value).toRawBits()
                                UN_F64_CONVERT_I32_U -> OpcodeOps.F64_CONVERT_I32_U(value.toInt()).toRawBits()
                                UN_I32_TRUNC_F64_U -> OpcodeOps.I32_TRUNC_F64_U(Double.fromBits(value)).toLong()
                                UN_F32_DEMOTE_F64 -> Double.fromBits(value).toFloat().toRawBits().toLong()
                                else -> error("bad unary op ${plan.mode[pc]}")
                            }
                        sp = sp0
                        pc++
                    }
                    OP_LOAD -> {
                        var sp0 = sp
                        val address =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        val memory = memory(plan.target0[pc])
                        val ptr = ptr(address, plan.src1[pc])
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                LOAD_I32 -> memory.readI32(ptr).toInt().toLong()
                                LOAD_I32_8_U -> memory.readU8(ptr)
                                LOAD_I32_16_S -> memory.readI16(ptr)
                                LOAD_I32_16_U -> memory.readU16(ptr)
                                LOAD_I64 -> memory.readI64(ptr)
                                else -> error("bad load op ${plan.mode[pc]}")
                            }
                        sp = sp0
                        pc++
                    }
                    OP_LOAD_SLOT -> {
                        val address = slots[plan.src0[pc].toInt()].toInt()
                        val memory = memory(plan.target0[pc])
                        val ptr = ptr(address, plan.src1[pc])
                        slots[plan.dst[pc]] =
                            when (plan.mode[pc]) {
                                LOAD_I32 -> memory.readI32(ptr).toInt().toLong()
                                LOAD_I32_8_U -> memory.readU8(ptr)
                                LOAD_I32_16_S -> memory.readI16(ptr)
                                LOAD_I32_16_U -> memory.readU16(ptr)
                                LOAD_I64 -> memory.readI64(ptr)
                                else -> error("bad load op ${plan.mode[pc]}")
                            }
                        pc++
                    }
                    OP_STORE -> {
                        var sp0 = sp
                        val value =
                            when (plan.src1Kind[pc]) {
                                SRC_SLOT -> slots[plan.src1[pc].toInt()]
                                SRC_CONST -> plan.src1[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src1Kind[pc]}")
                            }
                        val address =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }.toInt()
                        val memory = memory(plan.target0[pc])
                        val ptr = ptr(address, plan.src2[pc])
                        when (plan.mode[pc]) {
                            STORE_I32 -> memory.writeI32(ptr, value.toInt())
                            STORE_I32_8 -> memory.writeByte(ptr, value.toByte())
                            STORE_I32_16 -> memory.writeShort(ptr, value.toShort())
                            STORE_I64 -> memory.writeLong(ptr, value)
                            else -> error("bad store op ${plan.mode[pc]}")
                        }
                        sp = sp0
                        pc++
                    }
                    OP_SELECT -> {
                        var sp0 = sp
                        val pred =
                            when (plan.src2Kind[pc]) {
                                SRC_SLOT -> slots[plan.src2[pc].toInt()]
                                SRC_CONST -> plan.src2[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src2Kind[pc]}")
                            }.toInt()
                        val b =
                            when (plan.src1Kind[pc]) {
                                SRC_SLOT -> slots[plan.src1[pc].toInt()]
                                SRC_CONST -> plan.src1[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src1Kind[pc]}")
                            }
                        val a =
                            when (plan.src0Kind[pc]) {
                                SRC_SLOT -> slots[plan.src0[pc].toInt()]
                                SRC_CONST -> plan.src0[pc]
                                SRC_STACK -> stack[--sp0]
                                else -> error("bad source kind ${plan.src0Kind[pc]}")
                            }
                        slots[plan.dst[pc]] = if (pred == 0) b else a
                        sp = sp0
                        pc++
                    }
                    else -> error("bad slot plan op ${plan.ops[pc]}")
                }
            }
            return popResults(stack, sp, plan.returnCount)
        } finally {
            recycleSlots(funcId, slots)
            recycleStack(stack)
        }
    }

    private fun plan(funcId: Int, type: FunctionType, body: FunctionBody): SlotPlan {
        val plans = plans!!
        plans[funcId]?.let { return it }
        return SlotPlanner(type, body).build().also { plans[funcId] = it }
    }

    private inner class SlotPlanner(
        private val type: FunctionType,
        private val body: FunctionBody,
    ) {
        private val raw = body.instructions().toTypedArray()
        private val localSlotCount = ValType.sizeOf(type.params() + body.localTypes())
        private val localSlots = localSlots(type.params() + body.localTypes())
        private val rawToPlan = IntArray(raw.size + 1)
        private val op = ArrayList<Int>()
        private val mode = ArrayList<Int>()
        private val dst = ArrayList<Int>()
        private val s0k = ArrayList<Int>()
        private val s1k = ArrayList<Int>()
        private val s2k = ArrayList<Int>()
        private val s0 = ArrayList<Long>()
        private val s1 = ArrayList<Long>()
        private val s2 = ArrayList<Long>()
        private val t0 = ArrayList<Int>()
        private val t1 = ArrayList<Int>()
        private val controlStart = ArrayList<Int>()
        private val controlEnd = ArrayList<Int>()
        private val tables = ArrayList<IntArray>()
        private val tableDepths = ArrayList<IntArray>()
        private val patchTargets = ArrayList<PatchTarget>()
        private val abstractStack = ArrayList<Source>()
        private val branchTargets = branchTargets(raw)
        private var nextTempSlot = localSlotCount
        private var currentRawIndex = 0
        private var controlDepth = 1
        private var maxControlDepth = 1

        fun build(): SlotPlan {
            var index = 0
            while (index < raw.size) {
                if (branchTargets[index]) {
                    materializeStack()
                }
                rawToPlan[index] = op.size
                currentRawIndex = index
                val instruction = raw[index]
                val opcode = instruction.opcode()
                val next = raw.getOrNull(index + 1)
                val nextLocalSlot =
                    if (
                        next != null &&
                            !branchTargets[index + 1] &&
                            (next.opcode() == OpCode.LOCAL_SET || next.opcode() == OpCode.LOCAL_TEE)
                    ) {
                        localSlot(next.operand(0).toInt())
                    } else {
                        -1
                    }
                val nextIsTee = next?.opcode() == OpCode.LOCAL_TEE

                when {
                    opcode == OpCode.LOCAL_GET -> {
                        abstractStack += Source(SRC_SLOT, localSlot(instruction.operand(0).toInt()).toLong())
                        index++
                    }
                    opcode.isConst() -> {
                        abstractStack += Source(SRC_CONST, instruction.operand(0))
                        index++
                    }
                    opcode.isI32Binary() -> {
                        val right = popSource()
                        val left = popSource()
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addBinary(OP_I32_BIN, opcode.i32Mode(), destination, left, right)
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode == OpCode.I64_SUB -> {
                        val right = popSource()
                        val left = popSource()
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addBinary(OP_I64_BIN, I64_SUB, destination, left, right)
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode.isF64Binary() -> {
                        val right = popSource()
                        val left = popSource()
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addBinary(OP_F64_BIN, opcode.f64Mode(), destination, left, right)
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode.isUnary() -> {
                        val source = popSource()
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addUnary(opcode.unaryMode(), destination, source)
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode.isLoad() -> {
                        val address = popSource()
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addLoad(opcode.loadMode(), destination, address, instruction.operand(2).toInt(), instruction.operand(1))
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode.isStore() -> {
                        val value = popSource()
                        val address = popSource()
                        addStore(opcode.storeMode(), address, value, instruction.operand(2).toInt(), instruction.operand(1))
                        index++
                    }
                    opcode == OpCode.LOCAL_SET -> {
                        val slot = localSlot(instruction.operand(0).toInt())
                        val source = popSource()
                        materializeBeforeLocalWrite(slot)
                        addSet(slot, source)
                        index++
                    }
                    opcode == OpCode.LOCAL_TEE -> {
                        val slot = localSlot(instruction.operand(0).toInt())
                        val source = popSource()
                        materializeBeforeLocalWrite(slot)
                        addSet(slot, source)
                        abstractStack += Source(SRC_SLOT, slot.toLong())
                        index++
                    }
                    opcode == OpCode.SELECT -> {
                        val pred = popSource()
                        val b = popSource()
                        val a = popSource()
                        val destination = nextTemp()
                        addSelect(destination, a, b, pred)
                        abstractStack += Source(SRC_SLOT, destination.toLong())
                        index++
                    }
                    opcode == OpCode.BLOCK -> {
                        materializeStack()
                        addControl(OP_BLOCK, CTRL_BLOCK, instruction)
                        pushControlDepth()
                        index++
                    }
                    opcode == OpCode.LOOP -> {
                        materializeStack()
                        addControl(OP_LOOP, CTRL_LOOP, instruction)
                        pushControlDepth()
                        index++
                    }
                    opcode == OpCode.IF -> {
                        val pred = popSource()
                        materializeStack()
                        val opIndex = addIf(pred, instruction)
                        patchTargets += PatchTarget(opIndex, TARGET0)
                        patchTargets += PatchTarget(opIndex, TARGET1)
                        pushControlDepth()
                        index++
                    }
                    opcode == OpCode.BR_IF -> {
                        val pred = popSource()
                        materializeStack()
                        val opIndex =
                            addBranch(
                                OP_BR_IF,
                                instruction.operand(0).toInt(),
                                pred,
                                instruction.labelFalse(),
                                instruction.labelTrue(),
                            )
                        patchTargets += PatchTarget(opIndex, TARGET0)
                        patchTargets += PatchTarget(opIndex, TARGET1)
                        index++
                    }
                    opcode == OpCode.BR_TABLE -> {
                        val pred = popSource()
                        materializeStack()
                        val tableIndex = tables.size
                        tables += instruction.labelTable().toIntArray()
                        tableDepths += IntArray(instruction.operandCount()) { operandIndex ->
                            instruction.operand(operandIndex).toInt()
                        }
                        addOp(OP_BR_TABLE, 0, -1, pred, NO_SOURCE, NO_SOURCE, tableIndex, 0, 0, 0)
                        patchTargets += PatchTarget(op.lastIndex, TABLE_TARGET)
                        index++
                    }
                    opcode == OpCode.BR -> {
                        materializeStack()
                        addJump(OP_BR, instruction.operand(0).toInt(), instruction.labelTrue())
                        patchTargets += PatchTarget(op.lastIndex, TARGET0)
                        index++
                    }
                    opcode == OpCode.ELSE -> {
                        materializeStack()
                        addJump(OP_ELSE, 0, instruction.labelTrue())
                        patchTargets += PatchTarget(op.lastIndex, TARGET0)
                        index++
                    }
                    opcode == OpCode.END -> {
                        materializeStack()
                        rawToPlan[index] = op.size
                        addOp(OP_END, 0, -1, NO_SOURCE, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                        if (controlDepth > 1) controlDepth--
                        index++
                    }
                    opcode == OpCode.RETURN -> {
                        materializeStack()
                        addOp(OP_RETURN, 0, -1, NO_SOURCE, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                        index++
                    }
                    opcode == OpCode.CALL -> {
                        materializeStack()
                        val target = instruction.operand(0).toInt()
                        val targetType = types[target]
                        addOp(OP_CALL, 0, -1, NO_SOURCE, NO_SOURCE, NO_SOURCE, target, targetType.paramSlotCount(), 0, 0)
                        repeat(targetType.returnSlotCount()) {
                            abstractStack += Source(SRC_STACK, 0)
                        }
                        index++
                    }
                    opcode == OpCode.GLOBAL_GET -> {
                        val destination = destinationSlot(nextLocalSlot)
                        materializeBeforeLocalWrite(nextLocalSlot)
                        addOp(OP_GLOBAL_GET, 0, destination, NO_SOURCE, NO_SOURCE, NO_SOURCE, instruction.operand(0).toInt(), 0, 0, 0)
                        pushDestination(destination, nextLocalSlot, nextIsTee)
                        if (nextLocalSlot >= 0) {
                            rawToPlan[index + 1] = op.size
                            index += 2
                        } else {
                            index++
                        }
                    }
                    opcode == OpCode.GLOBAL_SET -> {
                        addOp(OP_GLOBAL_SET, 0, -1, popSource(), NO_SOURCE, NO_SOURCE, instruction.operand(0).toInt(), 0, 0, 0)
                        index++
                    }
                    opcode == OpCode.UNREACHABLE -> {
                        materializeStack()
                        addOp(OP_UNREACHABLE, 0, -1, NO_SOURCE, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                        index++
                    }
                    opcode == OpCode.NOP -> index++
                    else -> throw WasmEngineException("SlotPlanProbe unsupported opcode $opcode at raw=$index")
                }
            }

            currentRawIndex = raw.size
            materializeStack()
            rawToPlan[raw.size] = op.size
            patchTargets()
            return SlotPlan(
                ops = op.toIntArray(),
                mode = mode.toIntArray(),
                dst = dst.toIntArray(),
                src0Kind = s0k.toIntArray(),
                src1Kind = s1k.toIntArray(),
                src2Kind = s2k.toIntArray(),
                src0 = s0.toLongArray(),
                src1 = s1.toLongArray(),
                src2 = s2.toLongArray(),
                target0 = t0.toIntArray(),
                target1 = t1.toIntArray(),
                controlStart = controlStart.toIntArray(),
                controlEnd = controlEnd.toIntArray(),
                tables = tables.toTypedArray(),
                tableDepths = tableDepths.toTypedArray(),
                slotCount = nextTempSlot,
                returnCount = type.returnSlotCount(),
                maxControlDepth = maxControlDepth,
            )
        }

        private fun addSet(slot: Int, source: Source) {
            when (source.kind) {
                SRC_SLOT -> addOp(OP_SET_SLOT_SLOT, 0, slot, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                SRC_CONST -> addOp(OP_SET_SLOT_CONST, 0, slot, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                else -> addOp(OP_SET_SLOT, 0, slot, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
            }
        }

        private fun addBinary(opcode: Int, modeValue: Int, destination: Int, left: Source, right: Source) {
            when {
                opcode == OP_I32_BIN && left.kind == SRC_SLOT && right.kind == SRC_CONST ->
                    addOp(OP_I32_BIN_SLOT_CONST, modeValue, destination, left, right, NO_SOURCE, 0, 0, 0, 0)
                opcode == OP_I32_BIN && left.kind == SRC_SLOT && right.kind == SRC_SLOT ->
                    addOp(OP_I32_BIN_SLOT_SLOT, modeValue, destination, left, right, NO_SOURCE, 0, 0, 0, 0)
                else ->
                    addOp(opcode, modeValue, destination, left, right, NO_SOURCE, 0, 0, 0, 0)
            }
        }

        private fun addUnary(mode: Int, destination: Int, source: Source) {
            addOp(OP_UNARY, mode, destination, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
        }

        private fun addLoad(mode: Int, destination: Int, address: Source, memoryIndex: Int, offset: Long) {
            val opcode =
                if (address.kind == SRC_SLOT) {
                    OP_LOAD_SLOT
                } else {
                    OP_LOAD
                }
            addOp(opcode, mode, destination, address, Source(SRC_CONST, offset), NO_SOURCE, memoryIndex, 0, 0, 0)
        }

        private fun addStore(mode: Int, address: Source, value: Source, memoryIndex: Int, offset: Long) {
            addOp(OP_STORE, mode, -1, address, value, Source(SRC_CONST, offset), memoryIndex, 0, 0, 0)
        }

        private fun addSelect(destination: Int, a: Source, b: Source, pred: Source) {
            addOp(OP_SELECT, 0, destination, a, b, pred, 0, 0, 0, 0)
        }

        private fun addControl(opcode: Int, controlOpcode: Int, instruction: AnnotatedInstruction) {
            addOp(
                opcode,
                controlOpcode,
                -1,
                NO_SOURCE,
                NO_SOURCE,
                NO_SOURCE,
                controlParamSlotCount(instruction),
                controlReturnSlotCount(instruction),
                0,
                0,
            )
        }

        private fun addIf(pred: Source, instruction: AnnotatedInstruction): Int {
            addOp(
                OP_IF,
                CTRL_IF,
                -1,
                pred,
                NO_SOURCE,
                NO_SOURCE,
                instruction.labelFalse(),
                instruction.labelTrue(),
                controlParamSlotCount(instruction),
                controlReturnSlotCount(instruction),
            )
            return op.lastIndex
        }

        private fun addBranch(opcode: Int, depth: Int, pred: Source, falseRaw: Int, trueRaw: Int): Int {
            addOp(opcode, depth, -1, pred, NO_SOURCE, NO_SOURCE, falseRaw, trueRaw, 0, 0)
            return op.lastIndex
        }

        private fun addJump(opcode: Int, depth: Int, rawTarget: Int) {
            addOp(opcode, depth, -1, NO_SOURCE, NO_SOURCE, NO_SOURCE, rawTarget, 0, 0, 0)
        }

        private fun addOp(
            opcode: Int,
            modeValue: Int,
            destination: Int,
            source0: Source,
            source1: Source,
            source2: Source,
            targetA: Int,
            targetB: Int,
            controlStartValue: Int,
            controlEndValue: Int,
        ) {
            op += opcode
            mode += modeValue
            dst += destination
            s0k += source0.kind
            s1k += source1.kind
            s2k += source2.kind
            s0 += source0.value
            s1 += source1.value
            s2 += source2.value
            t0 += targetA
            t1 += targetB
            controlStart += controlStartValue
            controlEnd += controlEndValue
        }

        private fun materializeBeforeLocalWrite(slot: Int) {
            if (slot < 0) return
            if (abstractStack.any { it.kind == SRC_SLOT && it.value.toInt() == slot }) {
                materializeStack()
            }
        }

        private fun materializeStack() {
            if (abstractStack.isEmpty()) return
            for (source in abstractStack) {
                when (source.kind) {
                    SRC_SLOT -> addOp(OP_MATERIALIZE_SLOT, 0, -1, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                    SRC_CONST -> addOp(OP_MATERIALIZE_CONST, 0, -1, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                    else -> addOp(OP_MATERIALIZE, 0, -1, source, NO_SOURCE, NO_SOURCE, 0, 0, 0, 0)
                }
            }
            abstractStack.clear()
        }

        private fun popSource(): Source =
            if (abstractStack.isEmpty()) {
                Source(SRC_STACK, 0)
            } else {
                abstractStack.removeAt(abstractStack.lastIndex)
            }

        private fun destinationSlot(nextLocalSlot: Int): Int =
            if (nextLocalSlot >= 0) nextLocalSlot else nextTemp()

        private fun nextTemp(): Int = nextTempSlot++

        private fun pushDestination(destination: Int, nextLocalSlot: Int, nextIsTee: Boolean) {
            if (nextLocalSlot < 0 || nextIsTee) {
                abstractStack += Source(SRC_SLOT, destination.toLong())
            }
        }

        private fun localSlot(localIndex: Int): Int {
            if (localIndex !in localSlots.indices) {
                throw WasmEngineException("SlotPlanProbe unsupported local index $localIndex")
            }
            return localSlots[localIndex]
        }

        private fun pushControlDepth() {
            controlDepth++
            if (controlDepth > maxControlDepth) maxControlDepth = controlDepth
        }

        private fun patchTargets() {
            for (patch in patchTargets) {
                when (patch.kind) {
                    TARGET0 -> t0[patch.opIndex] = rawToPlan[t0[patch.opIndex]]
                    TARGET1 -> t1[patch.opIndex] = rawToPlan[t1[patch.opIndex]]
                    TABLE_TARGET -> {
                        val table = tables[t0[patch.opIndex]]
                        for (index in table.indices) {
                            table[index] = rawToPlan[table[index]]
                        }
                    }
                }
            }
        }

        private fun branchTargets(instructions: Array<AnnotatedInstruction>): BooleanArray {
            val targets = BooleanArray(instructions.size + 1)
            for (instruction in instructions) {
                fun mark(index: Int) {
                    if (index in targets.indices) targets[index] = true
                }
                when (instruction.opcode()) {
                    OpCode.IF,
                    OpCode.BR_IF -> {
                        mark(instruction.labelFalse())
                        mark(instruction.labelTrue())
                    }
                    OpCode.ELSE,
                    OpCode.BR -> mark(instruction.labelTrue())
                    OpCode.BR_TABLE -> {
                        for (target in instruction.labelTable()) {
                            mark(target)
                        }
                    }
                    else -> {}
                }
            }
            return targets
        }
    }

    private fun borrowSlots(funcId: Int, size: Int): LongArray {
        val pool = slotPools[funcId]
        return if (pool.isEmpty()) {
            LongArray(size)
        } else {
            pool.removeLast().also { it.fill(0L, 0, size) }
        }
    }

    private fun recycleSlots(funcId: Int, slots: LongArray) {
        val pool = slotPools[funcId]
        if (pool.size < MAX_REUSABLE_FRAMES_PER_FUNCTION) {
            pool.addLast(slots)
        }
    }

    private fun borrowStack(): LongArray =
        if (stackPool.isEmpty()) LongArray(STACK_CAPACITY) else stackPool.removeLast()

    private fun recycleStack(stack: LongArray) {
        if (stackPool.size < MAX_REUSABLE_STACKS) {
            stackPool.addLast(stack)
        }
    }

    private fun callImport(funcId: Int, args: LongArray): LongArray {
        val import = instance.imports().function(funcId)
        val handle = import.handle() ?: throw WasmEngineException("imported function has no host handle")
        return handle.apply(instance, args) ?: LongArray(0)
    }

    private fun memory(index: Int) =
        if (index == 0) memory0 else instance.memory(index)

    private fun ptr(address: Int, offset: Long): Int {
        val ptr = offset + address
        if (offset < 0 || offset >= Int.MAX_VALUE || address < 0 || ptr < 0 || ptr >= Int.MAX_VALUE) {
            throw WasmRuntimeException("out of bounds memory access")
        }
        return ptr.toInt()
    }

    private fun i32Binary(mode: Int, left: Int, right: Int): Long =
        when (mode) {
            I32_ADD -> left + right
            I32_SUB -> left - right
            I32_MUL -> left * right
            I32_DIV_U -> OpcodeOps.I32_DIV_U(left, right)
            I32_REM_S -> OpcodeOps.I32_REM_S(left, right)
            I32_AND -> left and right
            I32_OR -> left or right
            I32_XOR -> left xor right
            I32_SHL -> left shl right
            I32_SHR_S -> left shr right
            I32_SHR_U -> left ushr right
            I32_EQ -> if (left == right) 1 else 0
            I32_NE -> if (left != right) 1 else 0
            I32_LT_S -> if (left < right) 1 else 0
            I32_LT_U -> if (left.toUInt() < right.toUInt()) 1 else 0
            I32_GT_S -> if (left > right) 1 else 0
            I32_GT_U -> if (left.toUInt() > right.toUInt()) 1 else 0
            I32_LE_S -> if (left <= right) 1 else 0
            I32_LE_U -> if (left.toUInt() <= right.toUInt()) 1 else 0
            I32_GE_S -> if (left >= right) 1 else 0
            I32_GE_U -> if (left.toUInt() >= right.toUInt()) 1 else 0
            else -> error("bad i32 op $mode")
        }.toLong()

    private fun popResults(stack: LongArray, sp: Int, count: Int): LongArray {
        if (count == 0) return LongArray(0)
        val results = LongArray(count)
        for (index in 0 until count) {
            results[index] = stack[sp - count + index]
        }
        return results
    }

    private fun branchTo(
        depth: Int,
        ctrlOps: IntArray,
        ctrlStart: IntArray,
        ctrlEnd: IntArray,
        ctrlHeight: IntArray,
        ctrlSize: Int,
        stack: LongArray,
        sp: Int,
    ): BranchResult {
        val targetIndex = ctrlSize - depth - 1
        var newSp = sp
        if (ctrlOps[targetIndex] == CTRL_LOOP) {
            newSp = transferStack(stack, sp, ctrlHeight[targetIndex], ctrlStart[targetIndex], ctrlEnd[targetIndex])
        }
        return BranchResult(targetIndex + 1, newSp)
    }

    private fun transferStack(
        stack: LongArray,
        sp: Int,
        height: Int,
        startValues: Int,
        endValues: Int,
    ): Int {
        val endResults = startValues + endValues
        return when (endResults) {
            0 -> height
            1 -> {
                stack[height] = stack[sp - 1]
                height + 1
            }
            2 -> {
                stack[height] = stack[sp - 2]
                stack[height + 1] = stack[sp - 1]
                height + 2
            }
            else -> {
                val start = sp - endResults
                for (index in 0 until endResults) {
                    stack[height + index] = stack[start + index]
                }
                height + endResults
            }
        }
    }

    private fun controlParamSlotCount(instruction: AnnotatedInstruction): Int {
        val typeId = instruction.operand(0).toInt()
        if (typeId == 0x40) return 0
        if (ValType.isValid(typeId.toLong())) return 0
        return instance.type(typeId).paramSlotCount()
    }

    private fun controlReturnSlotCount(instruction: AnnotatedInstruction): Int {
        val typeId = instruction.operand(0).toInt()
        if (typeId == 0x40) return 0
        if (ValType.isValid(typeId.toLong())) {
            return if (typeId.toLong() == ValType.V128.id()) 2 else 1
        }
        return instance.type(typeId).returnSlotCount()
    }

    private fun localSlots(localTypes: List<ValType>): IntArray {
        val slots = IntArray(localTypes.size)
        var slot = 0
        for (index in localTypes.indices) {
            slots[index] = slot
            slot += if (localTypes[index] == ValType.V128) 2 else 1
        }
        return slots
    }

    private data class Source(val kind: Int, val value: Long)

    private data class PatchTarget(val opIndex: Int, val kind: Int)

    private data class BranchResult(val ctrlSize: Int, val sp: Int)

    private data class SlotPlan(
        val ops: IntArray,
        val mode: IntArray,
        val dst: IntArray,
        val src0Kind: IntArray,
        val src1Kind: IntArray,
        val src2Kind: IntArray,
        val src0: LongArray,
        val src1: LongArray,
        val src2: LongArray,
        val target0: IntArray,
        val target1: IntArray,
        val controlStart: IntArray,
        val controlEnd: IntArray,
        val tables: Array<IntArray>,
        val tableDepths: Array<IntArray>,
        val slotCount: Int,
        val returnCount: Int,
        val maxControlDepth: Int,
    )

    private fun OpCode.isConst(): Boolean =
        this == OpCode.I32_CONST ||
            this == OpCode.I64_CONST ||
            this == OpCode.F32_CONST ||
            this == OpCode.F64_CONST

    private fun OpCode.isI32Binary(): Boolean =
        this == OpCode.I32_ADD ||
            this == OpCode.I32_SUB ||
            this == OpCode.I32_MUL ||
            this == OpCode.I32_DIV_U ||
            this == OpCode.I32_REM_S ||
            this == OpCode.I32_AND ||
            this == OpCode.I32_OR ||
            this == OpCode.I32_XOR ||
            this == OpCode.I32_SHL ||
            this == OpCode.I32_SHR_S ||
            this == OpCode.I32_SHR_U ||
            this == OpCode.I32_EQ ||
            this == OpCode.I32_NE ||
            this == OpCode.I32_LT_S ||
            this == OpCode.I32_LT_U ||
            this == OpCode.I32_GT_S ||
            this == OpCode.I32_GT_U ||
            this == OpCode.I32_LE_S ||
            this == OpCode.I32_LE_U ||
            this == OpCode.I32_GE_S ||
            this == OpCode.I32_GE_U

    private fun OpCode.isF64Binary(): Boolean =
        this == OpCode.F64_DIV ||
            this == OpCode.F64_LT ||
            this == OpCode.F64_GE

    private fun OpCode.isUnary(): Boolean =
        this == OpCode.I32_EQZ ||
            this == OpCode.F64_CONVERT_I64_U ||
            this == OpCode.F64_CONVERT_I32_U ||
            this == OpCode.I32_TRUNC_F64_U ||
            this == OpCode.F32_DEMOTE_F64

    private fun OpCode.isLoad(): Boolean =
        this == OpCode.I32_LOAD ||
            this == OpCode.I32_LOAD8_U ||
            this == OpCode.I32_LOAD16_S ||
            this == OpCode.I32_LOAD16_U ||
            this == OpCode.I64_LOAD

    private fun OpCode.isStore(): Boolean =
        this == OpCode.I32_STORE ||
            this == OpCode.I32_STORE8 ||
            this == OpCode.I32_STORE16 ||
            this == OpCode.I64_STORE

    private fun OpCode.i32Mode(): Int =
        when (this) {
            OpCode.I32_ADD -> I32_ADD
            OpCode.I32_SUB -> I32_SUB
            OpCode.I32_MUL -> I32_MUL
            OpCode.I32_DIV_U -> I32_DIV_U
            OpCode.I32_REM_S -> I32_REM_S
            OpCode.I32_AND -> I32_AND
            OpCode.I32_OR -> I32_OR
            OpCode.I32_XOR -> I32_XOR
            OpCode.I32_SHL -> I32_SHL
            OpCode.I32_SHR_S -> I32_SHR_S
            OpCode.I32_SHR_U -> I32_SHR_U
            OpCode.I32_EQ -> I32_EQ
            OpCode.I32_NE -> I32_NE
            OpCode.I32_LT_S -> I32_LT_S
            OpCode.I32_LT_U -> I32_LT_U
            OpCode.I32_GT_S -> I32_GT_S
            OpCode.I32_GT_U -> I32_GT_U
            OpCode.I32_LE_S -> I32_LE_S
            OpCode.I32_LE_U -> I32_LE_U
            OpCode.I32_GE_S -> I32_GE_S
            OpCode.I32_GE_U -> I32_GE_U
            else -> error("not i32 binary $this")
        }

    private fun OpCode.f64Mode(): Int =
        when (this) {
            OpCode.F64_DIV -> F64_DIV
            OpCode.F64_LT -> F64_LT
            OpCode.F64_GE -> F64_GE
            else -> error("not f64 binary $this")
        }

    private fun OpCode.unaryMode(): Int =
        when (this) {
            OpCode.I32_EQZ -> UN_I32_EQZ
            OpCode.F64_CONVERT_I64_U -> UN_F64_CONVERT_I64_U
            OpCode.F64_CONVERT_I32_U -> UN_F64_CONVERT_I32_U
            OpCode.I32_TRUNC_F64_U -> UN_I32_TRUNC_F64_U
            OpCode.F32_DEMOTE_F64 -> UN_F32_DEMOTE_F64
            else -> error("not unary $this")
        }

    private fun OpCode.loadMode(): Int =
        when (this) {
            OpCode.I32_LOAD -> LOAD_I32
            OpCode.I32_LOAD8_U -> LOAD_I32_8_U
            OpCode.I32_LOAD16_S -> LOAD_I32_16_S
            OpCode.I32_LOAD16_U -> LOAD_I32_16_U
            OpCode.I64_LOAD -> LOAD_I64
            else -> error("not load $this")
        }

    private fun OpCode.storeMode(): Int =
        when (this) {
            OpCode.I32_STORE -> STORE_I32
            OpCode.I32_STORE8 -> STORE_I32_8
            OpCode.I32_STORE16 -> STORE_I32_16
            OpCode.I64_STORE -> STORE_I64
            else -> error("not store $this")
        }

    private companion object {
        private const val TRUE = 1L
        private const val FALSE = 0L
        private const val STACK_CAPACITY = 4096
        private const val MAX_REUSABLE_FRAMES_PER_FUNCTION = 64
        private const val MAX_REUSABLE_STACKS = 256

        private const val SRC_NONE = 0
        private const val SRC_SLOT = 1
        private const val SRC_CONST = 2
        private const val SRC_STACK = 3
        private val NO_SOURCE = Source(SRC_NONE, 0)

        private const val TARGET0 = 0
        private const val TARGET1 = 1
        private const val TABLE_TARGET = 2

        private const val CTRL_CALL = 1
        private const val CTRL_BLOCK = 2
        private const val CTRL_LOOP = 3
        private const val CTRL_IF = 4

        private const val OP_MATERIALIZE = 1
        private const val OP_UNREACHABLE = 2
        private const val OP_ELSE = 3
        private const val OP_BR = 4
        private const val OP_IF = 5
        private const val OP_BR_IF = 6
        private const val OP_BR_TABLE = 7
        private const val OP_RETURN = 8
        private const val OP_CALL = 9
        private const val OP_SET_SLOT = 10
        private const val OP_GLOBAL_GET = 11
        private const val OP_GLOBAL_SET = 12
        private const val OP_I32_BIN = 13
        private const val OP_I64_BIN = 14
        private const val OP_F64_BIN = 15
        private const val OP_UNARY = 16
        private const val OP_LOAD = 17
        private const val OP_STORE = 18
        private const val OP_SELECT = 19
        private const val OP_BLOCK = 20
        private const val OP_LOOP = 21
        private const val OP_END = 22
        private const val OP_MATERIALIZE_SLOT = 23
        private const val OP_MATERIALIZE_CONST = 24
        private const val OP_SET_SLOT_SLOT = 25
        private const val OP_SET_SLOT_CONST = 26
        private const val OP_I32_BIN_SLOT_CONST = 27
        private const val OP_I32_BIN_SLOT_SLOT = 28
        private const val OP_LOAD_SLOT = 29

        private const val I32_ADD = 1
        private const val I32_SUB = 2
        private const val I32_MUL = 3
        private const val I32_DIV_U = 4
        private const val I32_REM_S = 5
        private const val I32_AND = 6
        private const val I32_OR = 7
        private const val I32_XOR = 8
        private const val I32_SHL = 9
        private const val I32_SHR_S = 10
        private const val I32_SHR_U = 11
        private const val I32_EQ = 12
        private const val I32_NE = 13
        private const val I32_LT_S = 14
        private const val I32_LT_U = 15
        private const val I32_GT_S = 16
        private const val I32_GT_U = 17
        private const val I32_LE_S = 18
        private const val I32_LE_U = 19
        private const val I32_GE_S = 20
        private const val I32_GE_U = 21

        private const val I64_SUB = 1

        private const val F64_DIV = 1
        private const val F64_LT = 2
        private const val F64_GE = 3

        private const val UN_I32_EQZ = 1
        private const val UN_F64_CONVERT_I64_U = 2
        private const val UN_F64_CONVERT_I32_U = 3
        private const val UN_I32_TRUNC_F64_U = 4
        private const val UN_F32_DEMOTE_F64 = 5

        private const val LOAD_I32 = 1
        private const val LOAD_I32_8_U = 2
        private const val LOAD_I32_16_S = 3
        private const val LOAD_I32_16_U = 4
        private const val LOAD_I64 = 5

        private const val STORE_I32 = 1
        private const val STORE_I32_8 = 2
        private const val STORE_I32_16 = 3
        private const val STORE_I64 = 4
    }
}
