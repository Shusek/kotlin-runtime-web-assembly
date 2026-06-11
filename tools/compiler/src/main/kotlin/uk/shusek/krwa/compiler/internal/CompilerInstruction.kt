package uk.shusek.krwa.compiler.internal

import java.util.Arrays
import java.util.stream.LongStream

internal class CompilerInstruction {
    private val opcode: CompilerOpCode
    private val operands: LongArray

    constructor(opcode: CompilerOpCode) : this(opcode, *EMPTY)

    constructor(opcode: CompilerOpCode, vararg operands: Long) {
        this.opcode = opcode
        this.operands = operands
    }

    fun opcode(): CompilerOpCode = opcode

    fun operands(): LongStream = Arrays.stream(operands)

    fun operandCount(): Int = operands.size

    fun operand(index: Int): Long = operands[index]

    override fun toString(): String =
        when (operands.size) {
            0 -> opcode.toString()
            1 -> "$opcode ${operands[0]}"
            else -> "$opcode ${Arrays.toString(operands)}"
        }

    fun labelTargets(): LongArray =
        when (opcode) {
            CompilerOpCode.GOTO,
            CompilerOpCode.IFEQ,
            CompilerOpCode.IFNE,
            CompilerOpCode.SWITCH,
            CompilerOpCode.TRY_CATCH_BLOCK -> operands
            else -> EMPTY
        }

    companion object {
        @JvmField val EMPTY: LongArray = LongArray(0)
    }
}
