package uk.shusek.krwa.wasm.types

class StorageType
private constructor(private val valType: ValType?, private val packedType: PackedType?) {
    init {
        requireExactlyOneNonNull(valType, packedType)
    }

    fun valType(): ValType? = valType

    fun packedType(): PackedType? = packedType

    fun byteSize(): Int {
        if (packedType != null) {
            return when (packedType) {
                PackedType.I8 -> 1
                PackedType.I16 -> 2
            }
        }
        return when (valType!!.opcode()) {
            0x7F,
            0x7D -> 4
            0x7E,
            0x7C -> 8
            else -> 4
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is StorageType) {
            return false
        }
        return valType == other.valType && packedType == other.packedType
    }

    override fun hashCode(): Int = (valType?.hashCode() ?: 0) * 31 + (packedType?.hashCode() ?: 0)

    class Builder {
        private var valType: ValType? = null
        private var packedType: PackedType? = null

        fun withValType(valType: ValType): Builder {
            this.valType = valType
            return this
        }

        fun withPackedType(packedType: PackedType): Builder {
            this.packedType = packedType
            return this
        }

        fun build(): StorageType = StorageType(valType, packedType)
    }

    companion object {
        private fun requireExactlyOneNonNull(a: Any?, b: Any?) {
            if ((if (a == null) 0 else 1) + (if (b == null) 0 else 1) != 1) {
                throw IllegalArgumentException("Exactly one field must be filled")
            }
        }

        fun builder(): Builder = Builder()
    }
}
