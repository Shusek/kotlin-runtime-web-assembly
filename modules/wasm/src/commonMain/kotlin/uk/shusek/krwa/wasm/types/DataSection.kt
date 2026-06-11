package uk.shusek.krwa.wasm.types

class DataSection private constructor(dataSegments: List<DataSegment>) :
    Section(SectionId.DATA.toLong()) {
    private val dataSegments = dataSegments.toList()

    fun dataSegments(): Array<DataSegment> = dataSegments.toTypedArray()

    fun dataSegmentCount(): Int = dataSegments.size

    fun getDataSegment(idx: Int): DataSegment = dataSegments[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DataSection) {
            return false
        }
        return dataSegments == other.dataSegments
    }

    override fun hashCode(): Int = dataSegments.hashCode()

    class Builder {
        private val dataSegments = ArrayList<DataSegment>()

        fun addDataSegment(dataSegment: DataSegment): Builder {
            dataSegments.add(dataSegment)
            return this
        }

        fun build(): DataSection = DataSection(dataSegments)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
