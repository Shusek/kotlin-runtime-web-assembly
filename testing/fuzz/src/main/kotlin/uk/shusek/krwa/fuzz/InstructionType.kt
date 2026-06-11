package uk.shusek.krwa.fuzz

enum class InstructionType(private val value: String) {
    NUMERIC("numeric"),
    VECTOR("vector"),
    CONTROL("control"),
    MEMORY("memory"),
    REFERENCE("reference"),
    PARAMETRIC("parametric"),
    VARIABLE("variable"),
    TABLE("table");

    fun value(): String = value

    companion object {
        private val byValue: Map<String, InstructionType> = entries.associateBy { it.value() }

        @JvmStatic fun byValue(value: String): InstructionType? = byValue[value]
    }
}
