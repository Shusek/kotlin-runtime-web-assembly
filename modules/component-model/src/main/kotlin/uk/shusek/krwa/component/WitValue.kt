package uk.shusek.krwa.component

class WitValue private constructor() {
    companion object {
        @ComponentModelJvmStatic
        fun record(vararg fields: Any?): Map<String, Any?> {
            if ((fields.size % 2) != 0) {
                throw IllegalArgumentException("record fields must be name/value pairs")
            }
            val result = linkedMapOf<String, Any?>()
            var index = 0
            while (index < fields.size) {
                result[fields[index]?.toString() ?: "null"] = fields[index + 1]
                index += 2
            }
            return result
        }

        @ComponentModelJvmStatic
        fun flags(vararg enabled: String): Map<String, Boolean> {
            val result = linkedMapOf<String, Boolean>()
            for (label in enabled) {
                result[label] = true
            }
            return result
        }

        @ComponentModelJvmStatic
        fun variant(label: String): Variant = Variant.create(label, null, false)

        @ComponentModelJvmStatic
        fun variant(label: String, value: Any?): Variant = Variant.create(label, value, true)

        @ComponentModelJvmStatic fun none(): Variant = variant("none")

        @ComponentModelJvmStatic fun some(value: Any?): Variant = variant("some", value)

        @ComponentModelJvmStatic fun ok(value: Any?): Variant = variant("ok", value)

        @ComponentModelJvmStatic fun err(value: Any?): Variant = variant("err", value)
    }

    class Variant private constructor(val label: String, val value: Any?, val hasValue: Boolean) {
        internal companion object {
            fun create(label: String, value: Any?, hasValue: Boolean): Variant =
                Variant(label, value, hasValue)
        }

        fun label(): String = label

        fun value(): Any? = value

        fun hasValue(): Boolean = hasValue

        override fun toString(): String = if (hasValue) "$label($value)" else label
    }
}
