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

            var hasCountdownBranch = false
            var index = 0
            var loopDepth = 0
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
                        loopDepth > 0,
                    )
                if (countdownEnd > index) {
                    hasCountdownBranch = true
                    index = countdownEnd
                    continue
                }

                val instruction = code[index]
                when (instruction.opcode()) {
                    OpCode.NOP -> {
                        opcodes[index] = NOP
                    }

                    OpCode.LOOP -> {
                        if (instruction.operand(0).toInt() != 0x40) return null
                        opcodes[index] = LOOP
                        loopDepth++
                    }

                    OpCode.END -> {
                        opcodes[index] = END
                        if (loopDepth > 0) loopDepth--
                    }

                    OpCode.BR_IF -> {
                        opcodes[index] = BR_IF
                        operands[index] = instruction.operand(0)
                        labelTrue[index] = instruction.labelTrue()
                        labelFalse[index] = instruction.labelFalse()
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

                    OpCode.I32_ADD -> {
                        opcodes[index] = I32_ADD
                    }

                    OpCode.I32_SUB -> {
                        opcodes[index] = I32_SUB
                    }

                    else -> return null
                }
                index++
            }

            if (!hasCountdownBranch) return null
            return LoweredFunction(opcodes, operands, operands2, operands3, labelTrue, labelFalse)
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
