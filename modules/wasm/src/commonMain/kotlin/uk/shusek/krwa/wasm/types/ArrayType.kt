package uk.shusek.krwa.wasm.types

class ArrayType private constructor(private val fieldType: FieldType) {

    fun fieldType(): FieldType = fieldType

    override fun equals(other: Any?): Boolean {
        if (other !is ArrayType) {
            return false
        }
        return fieldType == other.fieldType
    }

    override fun hashCode(): Int = fieldType.hashCode()

    class Builder {
        private var fieldType: FieldType? = null

        fun withFieldType(fieldType: FieldType): Builder {
            this.fieldType = fieldType
            return this
        }

        fun build(): ArrayType = ArrayType(fieldType!!)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
