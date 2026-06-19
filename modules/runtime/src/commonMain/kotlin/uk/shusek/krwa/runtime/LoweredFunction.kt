package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

internal class LoweredFunction private constructor(
    val opcodes: IntArray,
    val operands: LongArray,
    val operands2: IntArray,
    val operands3: IntArray,
    val labelTrue: IntArray,
    val labelFalse: IntArray,
    val brTableDepths: Array<IntArray?>,
    val brTableLabels: Array<IntArray?>,
) {
    companion object {
        const val NOP: Int = 0
        const val LOOP: Int = 1
        const val END: Int = 2
        const val BR_IF: Int = 3
        const val LOCAL_GET: Int = 4
        const val LOCAL_SET: Int = 5
        const val LOCAL_TEE: Int = 6
        const val I32_CONST: Int = 7
        const val I32_ADD: Int = 8
        const val I32_SUB: Int = 9
        const val I32_COUNTDOWN_BRANCH: Int = 10
        const val BLOCK: Int = 11
        const val IF: Int = 12
        const val ELSE: Int = 13
        const val BR: Int = 14
        const val CALL: Int = 15
        const val RETURN: Int = 16
        const val I32_LOAD: Int = 17
        const val I32_STORE: Int = 18
        const val I32_AND: Int = 19
        const val I32_OR: Int = 20
        const val I32_XOR: Int = 21
        const val I32_SHL: Int = 22
        const val I32_SHR_S: Int = 23
        const val I32_SHR_U: Int = 24
        const val I32_EQZ: Int = 25
        const val I32_EQ: Int = 26
        const val I32_NE: Int = 27
        const val I32_LT_S: Int = 28
        const val I32_LT_U: Int = 29
        const val I32_GT_S: Int = 30
        const val I32_GT_U: Int = 31
        const val I32_LE_S: Int = 32
        const val I32_LE_U: Int = 33
        const val I32_GE_S: Int = 34
        const val I32_GE_U: Int = 35
        const val I32_MUL: Int = 36
        const val I32_DIV_U: Int = 37
        const val I32_REM_S: Int = 38
        const val I32_LOAD8_U: Int = 39
        const val I32_LOAD16_S: Int = 40
        const val I32_LOAD16_U: Int = 41
        const val I32_STORE8: Int = 42
        const val I32_STORE16: Int = 43
        const val I64_CONST: Int = 44
        const val I64_LOAD: Int = 45
        const val I64_STORE: Int = 46
        const val I64_SUB: Int = 47
        const val SELECT: Int = 48
        const val GLOBAL_GET: Int = 49
        const val GLOBAL_SET: Int = 50
        const val F64_CONST: Int = 51
        const val F64_DIV: Int = 52
        const val F64_LT: Int = 53
        const val F64_GE: Int = 54
        const val F64_CONVERT_I64_U: Int = 55
        const val F64_CONVERT_I32_U: Int = 56
        const val I32_TRUNC_F64_U: Int = 57
        const val F32_DEMOTE_F64: Int = 58
        const val UNREACHABLE: Int = 59
        const val BR_TABLE: Int = 60
        const val LOCAL_GET_I32_CONST_I32_ADD: Int = 61
        const val LOCAL_GET_I32_CONST_I32_ADD_LOCAL_SET: Int = 62
        const val LOCAL_GET_I32_CONST_I32_ADD_LOCAL_TEE: Int = 63
        const val LOCAL_GET_I32_CONST_I32_AND: Int = 64
        const val LOCAL_GET_I32_CONST_I32_SHL: Int = 65
        const val LOCAL_GET_I32_CONST_I32_SHR_U: Int = 66
        const val LOCAL_SET_LOCAL_GET: Int = 67
        const val LOCAL_GET_LOCAL_SET: Int = 68
        const val I32_CONST_LOCAL_SET: Int = 69
        const val I32_ADD_LOCAL_SET: Int = 70
        const val I32_ADD_LOCAL_TEE: Int = 71
        const val LOCAL_GET_I32_LOAD: Int = 72
        const val LOCAL_GET_I32_LOAD8_U: Int = 73
        const val LOCAL_GET_I32_LOAD16_S: Int = 74
        const val LOCAL_GET_I32_LOAD16_U: Int = 75
        const val LOCAL_GET_I64_LOAD: Int = 76
        const val LOCAL_GET_I32_CONST_I32_AND_LOCAL_TEE: Int = 77
        const val LOCAL_GET_I32_CONST_I32_SHL_LOCAL_SET: Int = 78
        const val LOCAL_GET_LOCAL_GET_I32_LOAD8_U_I32_STORE8: Int = 79
        const val CURRENT_PARAMETERLESS_LOOP_DEPTH: Int = -1

        fun tryBuild(
            code: Array<AnnotatedInstruction>,
            localIdx: IntArray,
            localTypes: Array<ValType>,
        ): LoweredFunction? {
            val opcodes = IntArray(code.size)
            val operands = LongArray(code.size)
            val operands2 = IntArray(code.size)
            val operands3 = IntArray(code.size)
            val labelTrue = IntArray(code.size)
            val labelFalse = IntArray(code.size)
            val brTableDepths = arrayOfNulls<IntArray>(code.size)
            val brTableLabels = arrayOfNulls<IntArray>(code.size)
            val controlParameterlessLoopStack = BooleanArray(code.size + 1)

            var index = 0
            var controlDepth = 0
            while (index < code.size) {
                val countdownEnd =
                    tryBuildCountdownBranch(
                        code,
                        index,
                        localIdx,
                        localTypes,
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
                        code,
                        index,
                        localIdx,
                        localTypes,
                        opcodes,
                        operands,
                        operands2,
                        operands3,
                    )
                if (superInstructionEnd > index) {
                    index = superInstructionEnd
                    continue
                }

                val instruction = code[index]
                when (instruction.opcode()) {
                    OpCode.NOP -> {
                        opcodes[index] = NOP
                    }

                    OpCode.UNREACHABLE -> {
                        opcodes[index] = UNREACHABLE
                    }

                    OpCode.BLOCK -> {
                        opcodes[index] = BLOCK
                        controlParameterlessLoopStack[controlDepth] = false
                        controlDepth++
                    }

                    OpCode.LOOP -> {
                        if (instruction.operand(0).toInt() != 0x40) return null
                        opcodes[index] = LOOP
                        controlParameterlessLoopStack[controlDepth] = true
                        controlDepth++
                    }

                    OpCode.IF -> {
                        opcodes[index] = IF
                        labelTrue[index] = instruction.labelTrue()
                        labelFalse[index] = instruction.labelFalse()
                        controlParameterlessLoopStack[controlDepth] = false
                        controlDepth++
                    }

                    OpCode.ELSE -> {
                        opcodes[index] = ELSE
                        labelTrue[index] = instruction.labelTrue()
                    }

                    OpCode.END -> {
                        opcodes[index] = END
                        if (controlDepth > 0) controlDepth--
                    }

                    OpCode.BR -> {
                        opcodes[index] = BR
                        operands[index] = instruction.operand(0)
                        labelTrue[index] = instruction.labelTrue()
                    }

                    OpCode.BR_IF -> {
                        opcodes[index] = BR_IF
                        operands[index] = instruction.operand(0)
                        labelTrue[index] = instruction.labelTrue()
                        labelFalse[index] = instruction.labelFalse()
                    }

                    OpCode.BR_TABLE -> {
                        opcodes[index] = BR_TABLE
                        brTableDepths[index] =
                            IntArray(instruction.operandCount()) { operandIndex ->
                                instruction.operand(operandIndex).toInt()
                            }
                        brTableLabels[index] = instruction.labelTable().toIntArray()
                    }

                    OpCode.RETURN -> {
                        opcodes[index] = RETURN
                    }

                    OpCode.CALL -> {
                        opcodes[index] = CALL
                    }

                    OpCode.LOCAL_GET -> {
                        val slot = localSlot(instruction, localIdx, localTypes)
                        if (slot < 0) return null
                        opcodes[index] = LOCAL_GET
                        operands[index] = slot.toLong()
                    }

                    OpCode.LOCAL_SET -> {
                        val slot = localSlot(instruction, localIdx, localTypes)
                        if (slot < 0) return null
                        opcodes[index] = LOCAL_SET
                        operands[index] = slot.toLong()
                    }

                    OpCode.LOCAL_TEE -> {
                        val slot = localSlot(instruction, localIdx, localTypes)
                        if (slot < 0) return null
                        opcodes[index] = LOCAL_TEE
                        operands[index] = slot.toLong()
                    }

                    OpCode.I32_CONST -> {
                        opcodes[index] = I32_CONST
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.I64_CONST -> {
                        opcodes[index] = I64_CONST
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.F64_CONST -> {
                        opcodes[index] = F64_CONST
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.I32_ADD -> {
                        opcodes[index] = I32_ADD
                    }

                    OpCode.I32_SUB -> {
                        opcodes[index] = I32_SUB
                    }

                    OpCode.I32_MUL -> {
                        opcodes[index] = I32_MUL
                    }

                    OpCode.I32_AND -> {
                        opcodes[index] = I32_AND
                    }

                    OpCode.I32_OR -> {
                        opcodes[index] = I32_OR
                    }

                    OpCode.I32_XOR -> {
                        opcodes[index] = I32_XOR
                    }

                    OpCode.I32_SHL -> {
                        opcodes[index] = I32_SHL
                    }

                    OpCode.I32_SHR_S -> {
                        opcodes[index] = I32_SHR_S
                    }

                    OpCode.I32_SHR_U -> {
                        opcodes[index] = I32_SHR_U
                    }

                    OpCode.I32_EQZ -> {
                        opcodes[index] = I32_EQZ
                    }

                    OpCode.I32_EQ -> {
                        opcodes[index] = I32_EQ
                    }

                    OpCode.I32_NE -> {
                        opcodes[index] = I32_NE
                    }

                    OpCode.I32_LT_S -> {
                        opcodes[index] = I32_LT_S
                    }

                    OpCode.I32_LT_U -> {
                        opcodes[index] = I32_LT_U
                    }

                    OpCode.I32_GT_S -> {
                        opcodes[index] = I32_GT_S
                    }

                    OpCode.I32_GT_U -> {
                        opcodes[index] = I32_GT_U
                    }

                    OpCode.I32_LE_S -> {
                        opcodes[index] = I32_LE_S
                    }

                    OpCode.I32_LE_U -> {
                        opcodes[index] = I32_LE_U
                    }

                    OpCode.I32_GE_S -> {
                        opcodes[index] = I32_GE_S
                    }

                    OpCode.I32_GE_U -> {
                        opcodes[index] = I32_GE_U
                    }

                    OpCode.I32_DIV_U -> {
                        opcodes[index] = I32_DIV_U
                    }

                    OpCode.I32_REM_S -> {
                        opcodes[index] = I32_REM_S
                    }

                    OpCode.I64_SUB -> {
                        opcodes[index] = I64_SUB
                    }

                    OpCode.I32_LOAD -> {
                        predecodeMemoryInstruction(instruction, index, I32_LOAD, opcodes, operands, operands2)
                    }

                    OpCode.I32_LOAD8_U -> {
                        predecodeMemoryInstruction(instruction, index, I32_LOAD8_U, opcodes, operands, operands2)
                    }

                    OpCode.I32_LOAD16_S -> {
                        predecodeMemoryInstruction(instruction, index, I32_LOAD16_S, opcodes, operands, operands2)
                    }

                    OpCode.I32_LOAD16_U -> {
                        predecodeMemoryInstruction(instruction, index, I32_LOAD16_U, opcodes, operands, operands2)
                    }

                    OpCode.I64_LOAD -> {
                        predecodeMemoryInstruction(instruction, index, I64_LOAD, opcodes, operands, operands2)
                    }

                    OpCode.I32_STORE -> {
                        predecodeMemoryInstruction(instruction, index, I32_STORE, opcodes, operands, operands2)
                    }

                    OpCode.I32_STORE8 -> {
                        predecodeMemoryInstruction(instruction, index, I32_STORE8, opcodes, operands, operands2)
                    }

                    OpCode.I32_STORE16 -> {
                        predecodeMemoryInstruction(instruction, index, I32_STORE16, opcodes, operands, operands2)
                    }

                    OpCode.I64_STORE -> {
                        predecodeMemoryInstruction(instruction, index, I64_STORE, opcodes, operands, operands2)
                    }

                    OpCode.SELECT -> {
                        opcodes[index] = SELECT
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.GLOBAL_GET -> {
                        opcodes[index] = GLOBAL_GET
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.GLOBAL_SET -> {
                        opcodes[index] = GLOBAL_SET
                        operands[index] = instruction.operand(0)
                    }

                    OpCode.F64_DIV -> {
                        opcodes[index] = F64_DIV
                    }

                    OpCode.F64_LT -> {
                        opcodes[index] = F64_LT
                    }

                    OpCode.F64_GE -> {
                        opcodes[index] = F64_GE
                    }

                    OpCode.F64_CONVERT_I64_U -> {
                        opcodes[index] = F64_CONVERT_I64_U
                    }

                    OpCode.F64_CONVERT_I32_U -> {
                        opcodes[index] = F64_CONVERT_I32_U
                    }

                    OpCode.I32_TRUNC_F64_U -> {
                        opcodes[index] = I32_TRUNC_F64_U
                    }

                    OpCode.F32_DEMOTE_F64 -> {
                        opcodes[index] = F32_DEMOTE_F64
                    }

                    else -> return null
                }
                index++
            }

            return LoweredFunction(
                opcodes,
                operands,
                operands2,
                operands3,
                labelTrue,
                labelFalse,
                brTableDepths,
                brTableLabels,
            )
        }

        private fun tryBuildSuperInstruction(
            code: Array<AnnotatedInstruction>,
            index: Int,
            localIdx: IntArray,
            localTypes: Array<ValType>,
            opcodes: IntArray,
            operands: LongArray,
            operands2: IntArray,
            operands3: IntArray,
        ): Int {
            if (index + 3 < code.size &&
                code[index].opcode() == OpCode.LOCAL_GET &&
                code[index + 1].opcode() == OpCode.I32_CONST &&
                (
                    code[index + 3].opcode() == OpCode.LOCAL_SET ||
                        code[index + 3].opcode() == OpCode.LOCAL_TEE
                )
            ) {
                val getSlot = localSlot(code[index], localIdx, localTypes)
                val setSlot = localSlot(code[index + 3], localIdx, localTypes)
                if (getSlot < 0 || setSlot < 0) return index

                val loweredOpcode =
                    when (code[index + 2].opcode()) {
                        OpCode.I32_ADD ->
                            if (code[index + 3].opcode() == OpCode.LOCAL_SET) {
                                LOCAL_GET_I32_CONST_I32_ADD_LOCAL_SET
                            } else {
                                LOCAL_GET_I32_CONST_I32_ADD_LOCAL_TEE
                            }

                        OpCode.I32_AND ->
                            if (code[index + 3].opcode() == OpCode.LOCAL_TEE) {
                                LOCAL_GET_I32_CONST_I32_AND_LOCAL_TEE
                            } else {
                                -1
                            }

                        OpCode.I32_SHL ->
                            if (code[index + 3].opcode() == OpCode.LOCAL_SET) {
                                LOCAL_GET_I32_CONST_I32_SHL_LOCAL_SET
                            } else {
                                -1
                            }

                        else -> -1
                    }
                if (loweredOpcode < 0) return index
                opcodes[index] = loweredOpcode
                operands[index] = getSlot.toLong()
                operands2[index] = code[index + 1].operand(0).toInt()
                operands3[index] = setSlot
                return index + 4
            }

            if (index + 1 < code.size && code[index].opcode() == OpCode.LOCAL_GET) {
                val memoryOpcode =
                    when (code[index + 1].opcode()) {
                        OpCode.I32_LOAD -> LOCAL_GET_I32_LOAD
                        OpCode.I32_LOAD8_U -> LOCAL_GET_I32_LOAD8_U
                        OpCode.I32_LOAD16_S -> LOCAL_GET_I32_LOAD16_S
                        OpCode.I32_LOAD16_U -> LOCAL_GET_I32_LOAD16_U
                        OpCode.I64_LOAD -> LOCAL_GET_I64_LOAD
                        else -> -1
                    }
                if (memoryOpcode >= 0) {
                    val getSlot = localSlot(code[index], localIdx, localTypes)
                    if (getSlot < 0) return index
                    opcodes[index] = memoryOpcode
                    operands[index] = code[index + 1].operand(1)
                    operands2[index] = getSlot
                    operands3[index] = code[index + 1].operand(2).toInt()
                    return index + 2
                }
            }

            if (
                index + 3 < code.size &&
                    code[index].opcode() == OpCode.LOCAL_GET &&
                    code[index + 1].opcode() == OpCode.LOCAL_GET &&
                    code[index + 2].opcode() == OpCode.I32_LOAD8_U &&
                    code[index + 3].opcode() == OpCode.I32_STORE8
            ) {
                val loadInstruction = code[index + 2]
                val storeInstruction = code[index + 3]
                if (
                    loadInstruction.operand(1) == 0L &&
                        storeInstruction.operand(1) == 0L &&
                        loadInstruction.operand(2) == storeInstruction.operand(2)
                ) {
                    val dstSlot = localSlot(code[index], localIdx, localTypes)
                    val srcSlot = localSlot(code[index + 1], localIdx, localTypes)
                    if (dstSlot < 0 || srcSlot < 0) return index
                    opcodes[index] = LOCAL_GET_LOCAL_GET_I32_LOAD8_U_I32_STORE8
                    operands[index] = loadInstruction.operand(2)
                    operands2[index] = dstSlot
                    operands3[index] = srcSlot
                    return index + 4
                }
            }

            if (index + 2 < code.size && code[index].opcode() == OpCode.LOCAL_GET &&
                code[index + 1].opcode() == OpCode.I32_CONST
            ) {
                val getSlot = localSlot(code[index], localIdx, localTypes)
                if (getSlot < 0) return index

                val loweredOpcode =
                    when (code[index + 2].opcode()) {
                        OpCode.I32_ADD -> LOCAL_GET_I32_CONST_I32_ADD
                        OpCode.I32_AND -> LOCAL_GET_I32_CONST_I32_AND
                        OpCode.I32_SHL -> LOCAL_GET_I32_CONST_I32_SHL
                        OpCode.I32_SHR_U -> LOCAL_GET_I32_CONST_I32_SHR_U
                        else -> -1
                    }
                if (loweredOpcode >= 0) {
                    opcodes[index] = loweredOpcode
                    operands[index] = getSlot.toLong()
                    operands2[index] = code[index + 1].operand(0).toInt()
                    return index + 3
                }
            }

            if (index + 1 < code.size) {
                val first = code[index]
                val second = code[index + 1]
                when {
                    first.opcode() == OpCode.LOCAL_SET && second.opcode() == OpCode.LOCAL_GET -> {
                        val setSlot = localSlot(first, localIdx, localTypes)
                        val getSlot = localSlot(second, localIdx, localTypes)
                        if (setSlot < 0 || getSlot < 0) return index
                        opcodes[index] = LOCAL_SET_LOCAL_GET
                        operands[index] = setSlot.toLong()
                        operands2[index] = getSlot
                        return index + 2
                    }

                    first.opcode() == OpCode.LOCAL_GET && second.opcode() == OpCode.LOCAL_SET -> {
                        val getSlot = localSlot(first, localIdx, localTypes)
                        val setSlot = localSlot(second, localIdx, localTypes)
                        if (getSlot < 0 || setSlot < 0) return index
                        opcodes[index] = LOCAL_GET_LOCAL_SET
                        operands[index] = getSlot.toLong()
                        operands2[index] = setSlot
                        return index + 2
                    }

                    first.opcode() == OpCode.I32_CONST && second.opcode() == OpCode.LOCAL_SET -> {
                        val setSlot = localSlot(second, localIdx, localTypes)
                        if (setSlot < 0) return index
                        opcodes[index] = I32_CONST_LOCAL_SET
                        operands[index] = first.operand(0)
                        operands2[index] = setSlot
                        return index + 2
                    }

                    first.opcode() == OpCode.I32_ADD &&
                        (
                            second.opcode() == OpCode.LOCAL_SET ||
                                second.opcode() == OpCode.LOCAL_TEE
                        ) -> {
                        val setSlot = localSlot(second, localIdx, localTypes)
                        if (setSlot < 0) return index
                        opcodes[index] =
                            if (second.opcode() == OpCode.LOCAL_SET) {
                                I32_ADD_LOCAL_SET
                            } else {
                                I32_ADD_LOCAL_TEE
                            }
                        operands[index] = setSlot.toLong()
                        return index + 2
                    }
                }
            }

            return index
        }

        private fun predecodeMemoryInstruction(
            instruction: AnnotatedInstruction,
            index: Int,
            loweredOpcode: Int,
            opcodes: IntArray,
            operands: LongArray,
            operands2: IntArray,
        ) {
            opcodes[index] = loweredOpcode
            operands[index] = instruction.operand(1)
            operands2[index] = instruction.operand(2).toInt()
        }

        private fun tryBuildCountdownBranch(
            code: Array<AnnotatedInstruction>,
            index: Int,
            localIdx: IntArray,
            localTypes: Array<ValType>,
            opcodes: IntArray,
            operands: LongArray,
            operands2: IntArray,
            operands3: IntArray,
            labelTrue: IntArray,
            labelFalse: IntArray,
            hasCurrentParameterlessLoop: Boolean,
        ): Int {
            if (index + 4 >= code.size || code[index].opcode() != OpCode.LOCAL_GET) return index

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
                return index
            }

            val localIndex = code[index].operand(0).toInt()
            if (localIndex != teeInstruction.operand(0).toInt()) return index
            if (localTypes[localIndex] == ValType.V128) return index

            opcodes[index] = I32_COUNTDOWN_BRANCH
            operands[index] = localIdx[localIndex].toLong()
            operands2[index] = constInstruction.operand(0).toInt()
            val branchDepth = branchInstruction.operand(0).toInt()
            operands3[index] =
                if (branchDepth == 0 && hasCurrentParameterlessLoop) {
                    CURRENT_PARAMETERLESS_LOOP_DEPTH
                } else {
                    branchDepth
                }
            labelTrue[index] = branchInstruction.labelTrue()
            labelFalse[index] = branchInstruction.labelFalse()

            return index + 5
        }

        private fun localSlot(
            instruction: AnnotatedInstruction,
            localIdx: IntArray,
            localTypes: Array<ValType>,
        ): Int {
            val localIndex = instruction.operand(0).toInt()
            if (localTypes[localIndex] == ValType.V128) return -1
            return localIdx[localIndex]
        }
    }
}
