package uk.shusek.krwa.wasm.types

class RecType private constructor(subTypes: Array<SubType>) {
    private val subTypes = subTypes.copyOf()

    fun subTypes(): Array<SubType> = subTypes

    fun isLegacy(): Boolean = subTypes.size == 1 && subTypes[0].compType().funcType() != null

    fun legacy(): FunctionType {
        assert(subTypes.size == 1)
        return subTypes[0].compType().funcType()!!
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RecType) {
            return false
        }
        return subTypes.contentDeepEquals(other.subTypes)
    }

    override fun hashCode(): Int = subTypes.contentHashCode()

    class Builder {
        private var subTypes: Array<SubType>? = null

        fun withSubTypes(subTypes: Array<SubType>): Builder {
            this.subTypes = subTypes
            return this
        }

        fun build(): RecType = RecType(subTypes!!)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
