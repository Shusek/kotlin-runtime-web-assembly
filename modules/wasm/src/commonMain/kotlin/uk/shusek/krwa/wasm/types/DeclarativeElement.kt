package uk.shusek.krwa.wasm.types

/** A declarative element. A declarative element is not available at runtime. */
class DeclarativeElement(type: ValType, initializers: List<List<Instruction>>) :
    Element(type, initializers)
