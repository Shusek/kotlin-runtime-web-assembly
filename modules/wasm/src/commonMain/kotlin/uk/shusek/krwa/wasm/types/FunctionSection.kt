package uk.shusek.krwa.wasm.types

class FunctionSection private constructor(typeIndices: List<Int>) :
    Section(SectionId.FUNCTION.toLong()) {
    private val typeIndices = typeIndices.toList()

    fun getFunctionType(idx: Int): Int = typeIndices[idx]

    fun getFunctionType(idx: Int, typeSection: TypeSection): FunctionType =
        typeSection.getType(getFunctionType(idx))

    fun functionCount(): Int = typeIndices.size

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FunctionSection) {
            return false
        }
        return typeIndices == other.typeIndices
    }

    override fun hashCode(): Int = typeIndices.hashCode()

    class Builder {
        private val typeIndices = ArrayList<Int>()

        fun addFunctionType(typeIndex: Int): Builder {
            typeIndices.add(typeIndex)
            return this
        }

        fun build(): FunctionSection = FunctionSection(typeIndices)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
