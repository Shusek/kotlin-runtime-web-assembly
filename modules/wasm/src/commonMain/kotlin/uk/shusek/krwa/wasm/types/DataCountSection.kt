package uk.shusek.krwa.wasm.types

class DataCountSection private constructor(private val dataCount: Int) :
    Section(SectionId.DATA_COUNT.toLong()) {

    fun dataCount(): Int = dataCount

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DataCountSection) {
            return false
        }
        return dataCount == other.dataCount
    }

    override fun hashCode(): Int = dataCount

    class Builder {
        private var dataCount = 0

        fun withDataCount(dataCount: Int): Builder {
            this.dataCount = dataCount
            return this
        }

        fun build(): DataCountSection = DataCountSection(dataCount)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
