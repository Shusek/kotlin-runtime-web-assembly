package uk.shusek.krwa.wasm.types

/**
 * A passive element. A passive element can be copied into a table using the `table.init`
 * instruction.
 */
class PassiveElement(type: ValType, initializers: List<List<Instruction>>) :
    Element(type, initializers)
