package uk.shusek.krwa.wasm.types

class CompType
private constructor(
    private val arrayType: ArrayType?,
    private val structType: StructType?,
    private val funcType: FunctionType?,
) {
    init {
        requireExactlyOneNonNull(arrayType, structType, funcType)
    }

    fun funcType(): FunctionType? = funcType

    fun structType(): StructType? = structType

    fun arrayType(): ArrayType? = arrayType

    override fun equals(other: Any?): Boolean {
        if (other !is CompType) {
            return false
        }
        return arrayType == other.arrayType &&
            structType == other.structType &&
            funcType == other.funcType
    }

    override fun hashCode(): Int =
        ((arrayType?.hashCode() ?: 0) * 31 + (structType?.hashCode() ?: 0)) * 31 +
            (funcType?.hashCode() ?: 0)

    class Builder {
        private var arrayType: ArrayType? = null
        private var structType: StructType? = null
        private var funcType: FunctionType? = null

        fun withArrayType(arrayType: ArrayType): Builder {
            this.arrayType = arrayType
            return this
        }

        fun withStructType(structType: StructType): Builder {
            this.structType = structType
            return this
        }

        fun withFuncType(funcType: FunctionType): Builder {
            this.funcType = funcType
            return this
        }

        fun build(): CompType = CompType(arrayType, structType, funcType)
    }

    companion object {
        private fun requireExactlyOneNonNull(a: Any?, b: Any?, c: Any?) {
            if (
                (if (a == null) 0 else 1) + (if (b == null) 0 else 1) + (if (c == null) 0 else 1) !=
                    1
            ) {
                throw IllegalArgumentException("Exactly one field must be filled")
            }
        }

        fun builder(): Builder = Builder()
    }
}
