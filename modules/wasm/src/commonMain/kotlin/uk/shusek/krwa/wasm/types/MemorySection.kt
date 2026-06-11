package uk.shusek.krwa.wasm.types

class MemorySection private constructor(memories: List<Memory>) :
    Section(SectionId.MEMORY.toLong()) {
    private val memories = memories.toList()

    fun memoryCount(): Int = memories.size

    fun getMemory(idx: Int): Memory = memories[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is MemorySection) {
            return false
        }
        return memories == other.memories
    }

    override fun hashCode(): Int = memories.hashCode()

    class Builder {
        private val memories = ArrayList<Memory>()

        fun addMemory(memory: Memory): Builder {
            memories.add(memory)
            return this
        }

        fun build(): MemorySection = MemorySection(memories)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
