package uk.shusek.krwa.fuzz

import java.util.Locale

class InstructionTypes(private val types: Set<InstructionType>) {
    constructor(vararg types: InstructionType) : this(setOf(*types))

    override fun toString(): String = types.joinToString(",") { it.value() }

    companion object {
        @JvmStatic
        fun fromString(values: String): InstructionTypes =
            InstructionTypes(
                values
                    .trim()
                    .split(",")
                    .map { value ->
                        InstructionType.byValue(value.lowercase(Locale.ROOT))
                            ?: throw RuntimeException("Cannot find a matching type for $value")
                    }
                    .toSet()
            )
    }
}
