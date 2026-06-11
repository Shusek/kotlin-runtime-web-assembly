package uk.shusek.krwa.wasm.types

/** An element, used to initialize table ranges. */
abstract class Element(type: ValType, initializers: List<List<Instruction>>) {
    private val type = requireNotNull(type) { "type" }
    private val initializers = initializers.map { it.toList() }

    /** @return the type of the element values */
    fun type(): ValType = type

    /**
     * @return the list of instruction lists which are used to initialize each element in the range
     */
    fun initializers(): List<List<Instruction>> = initializers

    /** @return the number of elements defined by this section */
    fun elementCount(): Int = initializers().size

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Element) {
            return false
        }
        return type == other.type && initializers == other.initializers
    }

    override fun hashCode(): Int = type.hashCode() * 31 + initializers.hashCode()
}
