package uk.shusek.krwa.wasm.types

class StructType private constructor(fieldTypes: Array<FieldType>) {
    private val fieldTypes = fieldTypes.copyOf()

    fun fieldTypes(): Array<FieldType> = fieldTypes

    override fun equals(other: Any?): Boolean {
        if (other !is StructType) {
            return false
        }
        return fieldTypes.contentDeepEquals(other.fieldTypes)
    }

    override fun hashCode(): Int = fieldTypes.contentHashCode()

    class Builder {
        private val fieldTypes = ArrayList<FieldType>()

        fun addFieldType(fieldType: FieldType): Builder {
            fieldTypes.add(fieldType)
            return this
        }

        fun build(): StructType = StructType(fieldTypes.toTypedArray())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
