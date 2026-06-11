package uk.shusek.krwa.wasm.types

class ActiveDataSegment(
    private val idx: Long,
    offsetInstructions: List<Instruction>,
    data: ByteArray,
) : DataSegment(data) {
    private val offsetInstructions = offsetInstructions.toList()

    fun index(): Long = idx

    fun offsetInstructions(): List<Instruction> = offsetInstructions

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ActiveDataSegment) {
            return false
        }
        if (!super.equals(other)) {
            return false
        }
        return idx == other.idx && offsetInstructions == other.offsetInstructions
    }

    override fun hashCode(): Int =
        (super.hashCode() * 19 + idx.hashCode()) * 19 + offsetInstructions.hashCode()
}
