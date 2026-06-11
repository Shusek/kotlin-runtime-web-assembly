package uk.shusek.krwa.wasm.types

class CodeSection
private constructor(functionBodies: List<FunctionBody>, private val requiresDataCount: Boolean) :
    Section(SectionId.CODE.toLong()) {
    private val functionBodies = functionBodies.toList()

    fun functionBodies(): Array<FunctionBody> = functionBodies.toTypedArray()

    fun functionBodyCount(): Int = functionBodies.size

    fun getFunctionBody(idx: Int): FunctionBody = functionBodies[idx]

    fun isRequiresDataCount(): Boolean = requiresDataCount

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CodeSection) {
            return false
        }
        return requiresDataCount == other.requiresDataCount &&
            functionBodies == other.functionBodies
    }

    override fun hashCode(): Int = functionBodies.hashCode() * 31 + requiresDataCount.hashCode()

    class Builder {
        private val functionBodies = ArrayList<FunctionBody>()
        private var requiresDataCount = false

        fun addFunctionBody(functionBody: FunctionBody): Builder {
            functionBodies.add(functionBody)
            return this
        }

        fun setRequiresDataCount(requiresDataCount: Boolean): Builder {
            this.requiresDataCount = requiresDataCount
            return this
        }

        fun build(): CodeSection = CodeSection(functionBodies, requiresDataCount)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
