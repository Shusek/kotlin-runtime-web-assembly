package uk.shusek.krwa.wasm.types

open class Instruction(private val address: Int, private val opcode: OpCode, operands: LongArray) {
    private val operands = if (operands.isEmpty()) EMPTY_OPERANDS else operands.copyOf()

    fun address(): Int = address

    fun opcode(): OpCode = opcode

    fun operands(): LongArray = operands.copyOf()

    fun operandCount(): Int = operands.size

    fun operand(index: Int): Long = operands[index]

    fun setOperand(index: Int, value: Long) {
        operands[index] = value
    }

    override fun toString(): String {
        val result = "0x" + address.toUInt().toString(16).uppercase().padStart(8, '0') + ": "
        return if (operands.isNotEmpty()) {
            result + opcode + " " + operands.contentToString()
        } else {
            result + opcode.toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Instruction) {
            return false
        }
        return address == other.address &&
            opcode == other.opcode &&
            operands.contentEquals(other.operands)
    }

    override fun hashCode(): Int {
        var result = address
        result = 31 * result + opcode.hashCode()
        result = 31 * result + operands.contentHashCode()
        return result
    }

    companion object {
        val EMPTY_OPERANDS: LongArray = LongArray(0)
    }
}
