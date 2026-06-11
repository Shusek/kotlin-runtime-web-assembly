package uk.shusek.krwa.wasm.types

/**
 * An active element. An active element copies its elements into a table during initialization of
 * that table.
 */
class ActiveElement(
    type: ValType,
    initializers: List<List<Instruction>>,
    private val tableIndex: Int,
    offset: List<Instruction>,
) : Element(type, initializers) {
    private val offset = offset.toList()

    /** @return the table to actively initialize */
    fun tableIndex(): Int = tableIndex

    /** @return a constant expression defining the offset into the table */
    fun offset(): List<Instruction> = offset
}
