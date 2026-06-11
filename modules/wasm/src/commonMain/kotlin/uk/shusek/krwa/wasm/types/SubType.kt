package uk.shusek.krwa.wasm.types

class SubType
private constructor(
    typeIdx: IntArray,
    private val compType: CompType,
    private val isFinal: Boolean,
) {
    private val typeIdx = typeIdx.copyOf()

    fun typeIdx(): IntArray = typeIdx

    fun compType(): CompType = compType

    fun isFinal(): Boolean = isFinal

    override fun equals(other: Any?): Boolean {
        if (other !is SubType) {
            return false
        }
        return isFinal == other.isFinal &&
            typeIdx.contentEquals(other.typeIdx) &&
            compType == other.compType
    }

    override fun hashCode(): Int =
        ((isFinal.hashCode() * 31 + typeIdx.contentHashCode()) * 31 + compType.hashCode())

    class Builder {
        private var typeIdx: IntArray? = null
        private var compType: CompType? = null
        private var isFinal = false

        fun withTypeIdx(typeIdx: IntArray): Builder {
            this.typeIdx = typeIdx
            return this
        }

        fun withCompType(compType: CompType): Builder {
            this.compType = compType
            return this
        }

        fun withFinal(isFinal: Boolean): Builder {
            this.isFinal = isFinal
            return this
        }

        fun build(): SubType = SubType(typeIdx!!, compType!!, isFinal)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
