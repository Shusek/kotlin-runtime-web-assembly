package uk.shusek.krwa.wasm.types

class Memory(limits: MemoryLimits) {
    private val limits = limits

    fun limits(): MemoryLimits = limits

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Memory) {
            return false
        }
        return limits == other.limits
    }

    override fun hashCode(): Int = limits.hashCode()
}
