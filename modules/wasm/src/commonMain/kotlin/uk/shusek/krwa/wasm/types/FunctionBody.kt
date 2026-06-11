package uk.shusek.krwa.wasm.types

class FunctionBody(locals: List<ValType>, instructions: List<AnnotatedInstruction>) {
    private val locals = locals.toList()
    private val instructions = instructions.toList()

    fun localTypes(): List<ValType> = locals

    fun instructions(): List<AnnotatedInstruction> = instructions

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FunctionBody) {
            return false
        }
        return locals == other.locals && instructions == other.instructions
    }

    override fun hashCode(): Int = locals.hashCode() * 31 + instructions.hashCode()
}
