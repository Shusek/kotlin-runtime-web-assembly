package uk.shusek.krwa.wasm.types

class ElementSection private constructor(elements: List<Element>) :
    Section(SectionId.ELEMENT.toLong()) {
    private val elements = elements.toList()

    fun elements(): Array<Element> = elements.toTypedArray()

    fun elementCount(): Int = elements.size

    fun getElement(idx: Int): Element = elements[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ElementSection) {
            return false
        }
        return elements == other.elements
    }

    override fun hashCode(): Int = elements.hashCode()

    class Builder {
        private val elements = ArrayList<Element>()

        fun addElement(element: Element): Builder {
            elements.add(element)
            return this
        }

        fun build(): ElementSection = ElementSection(elements)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
