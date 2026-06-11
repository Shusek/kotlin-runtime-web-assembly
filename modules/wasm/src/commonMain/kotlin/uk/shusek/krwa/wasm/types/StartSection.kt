package uk.shusek.krwa.wasm.types

class StartSection private constructor(private val startIndex: Long) :
    Section(SectionId.START.toLong()) {

    fun startIndex(): Long = startIndex

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is StartSection) {
            return false
        }
        return startIndex == other.startIndex
    }

    override fun hashCode(): Int = startIndex.hashCode()

    class Builder {
        private var startIndex = 0L

        fun setStartIndex(startIndex: Long): Builder {
            this.startIndex = startIndex
            return this
        }

        fun build(): StartSection = StartSection(startIndex)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
