package uk.shusek.krwa.wasm.types

class TagSection private constructor(tags: List<TagType>) : Section(SectionId.TAG.toLong()) {
    private val tags = tags.toList()

    fun types(): Array<TagType> = tags.toTypedArray()

    fun tagCount(): Int = tags.size

    fun getTag(idx: Int): TagType = tags[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is TagSection) {
            return false
        }
        return tags == other.tags
    }

    override fun hashCode(): Int = tags.hashCode()

    class Builder {
        private val tags = ArrayList<TagType>()

        fun addTagType(tagType: TagType): Builder {
            tags.add(tagType)
            return this
        }

        fun build(): TagSection = TagSection(tags)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
