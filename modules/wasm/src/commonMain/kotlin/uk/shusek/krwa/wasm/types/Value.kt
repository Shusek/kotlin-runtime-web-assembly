package uk.shusek.krwa.wasm.types

open class Value {
    private val type: ValType
    private val data: Long

    @Deprecated("use Value(ValType, long)")
    constructor(type: ValueType, value: Long) {
        this.type = type.toValType()
        data = value
    }

    constructor(type: ValType, value: Long) {
        this.type = type
        data = value
    }

    fun raw(): Long = data

    fun type(): ValType = type

    fun asInt(): Int {
        assert(type == ValType.I32)
        return data.toInt()
    }

    fun asLong(): Long {
        assert(type == ValType.I64)
        return data
    }

    fun asFloat(): Float {
        assert(type == ValType.F32)
        return longToFloat(data)
    }

    fun asDouble(): Double {
        assert(type == ValType.F64)
        return longToDouble(data)
    }

    override fun toString(): String =
        when (type.opcode()) {
            ValType.ID.I32 -> "${data.toInt()}@i32"
            ValType.ID.I64 -> "$data@i64"
            ValType.ID.F32 -> "${longToFloat(data)}@f32"
            ValType.ID.F64 -> "${longToDouble(data)}@f64"
            ValType.ID.V128 -> "$data@v128"
            ValType.ID.Ref -> "ref[${data.toInt()}]"
            ValType.ID.RefNull -> "refnull[${data.toInt()}]"
            else -> throw AssertionError("Unhandled type: $type")
        }

    final override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other !is Value) {
            return false
        }
        return type.id() == other.type.id() && data == other.data
    }

    final override fun hashCode(): Int = type.id().hashCode() * 31 + data.hashCode()

    companion object {
        val TRUE: Long = 1L

        val FALSE: Long = 0L

        val REF_NULL_VALUE: Int = -1

        val EMPTY_VALUES: LongArray = LongArray(0)

        private const val I31_TAG: Long = 0x7FFFFFFF_00000000L
        private const val I31_MASK: Long = 0x7FFFFFFFL

        fun encodeI31(value: Int): Long = I31_TAG or (value.toLong() and I31_MASK)

        fun decodeI31S(encoded: Long): Int {
            val raw = (encoded and I31_MASK).toInt()
            return raw shl 1 shr 1
        }

        fun decodeI31U(encoded: Long): Int = (encoded and I31_MASK).toInt()

        fun isI31(value: Long): Boolean = value and 0xFFFFFFFF_00000000UL.toLong() == I31_TAG

        fun floatToLong(data: Float): Long = data.toRawBits().toLong()

        fun longToFloat(data: Long): Float = Float.fromBits(data.toInt())

        fun doubleToLong(data: Double): Long = data.toRawBits()

        fun longToDouble(data: Long): Double = Double.fromBits(data)

        fun fromFloat(data: Float): Value = f32(floatToLong(data))

        fun fromDouble(data: Double): Value = f64(doubleToLong(data))

        fun i32(data: Int): Value = i32(data.toLong())

        fun i32(data: Long): Value = Value(ValType.I32, data)

        fun i64(data: Long): Value = Value(ValType.I64, data)

        fun f32(data: Long): Value = Value(ValType.F32, data)

        fun f64(data: Long): Value = Value(ValType.F64, data)

        fun externRef(data: Long): Value = Value(ValType.ExternRef, data)

        fun funcRef(data: Long): Value = Value(ValType.FuncRef, data)

        fun vecTo8(values: LongArray): ByteArray {
            val result = ByteArray(values.size * 8)
            var valueIdx = 0
            var i = 0
            while (i < result.size) {
                val value = values[valueIdx++]
                result[i] = (value and 0xFFL).toByte()
                result[++i] = (value shr 8 and 0xFFL).toByte()
                result[++i] = (value shr 16 and 0xFFL).toByte()
                result[++i] = (value shr 24 and 0xFFL).toByte()
                result[++i] = (value shr 32 and 0xFFL).toByte()
                result[++i] = (value shr 40 and 0xFFL).toByte()
                result[++i] = (value shr 48 and 0xFFL).toByte()
                result[++i] = (value shr 56 and 0xFFL).toByte()
                i++
            }
            return result
        }

        fun bytesToVec(bytes: ByteArray): LongArray {
            val result = LongArray(bytes.size / 8)
            var valueIdx = 0
            var i = 0
            while (i < bytes.size) {
                result[valueIdx++] =
                    byteToUnsignedLong(bytes[i]) + (byteToUnsignedLong(bytes[++i]) shl 8) or
                        (byteToUnsignedLong(bytes[++i]) shl 16) or
                        (byteToUnsignedLong(bytes[++i]) shl 24) or
                        (byteToUnsignedLong(bytes[++i]) shl 32) or
                        (byteToUnsignedLong(bytes[++i]) shl 40) or
                        (byteToUnsignedLong(bytes[++i]) shl 48) or
                        (byteToUnsignedLong(bytes[++i]) shl 56)
                i++
            }
            return result
        }

        fun vecTo16(values: LongArray): IntArray {
            val result = IntArray(values.size * 4)
            var valueIdx = 0
            var i = 0
            while (i < result.size) {
                val value = values[valueIdx++]
                result[i] = (value and 0xFFFFL).toInt()
                result[++i] = (value shr 16 and 0xFFFFL).toInt()
                result[++i] = (value shr 32 and 0xFFFFL).toInt()
                result[++i] = (value shr 48 and 0xFFFFL).toInt()
                i++
            }
            return result
        }

        fun vecTo32(values: LongArray): LongArray {
            val result = LongArray(values.size * 2)
            var valueIdx = 0
            var i = 0
            while (i < result.size) {
                val value = values[valueIdx++]
                result[i] = value and 0xFFFFFFFFL
                result[++i] = value shr 32 and 0xFFFFFFFFL
                i++
            }
            return result
        }

        fun vecToF32(values: LongArray): FloatArray {
            val result = FloatArray(values.size * 2)
            var valueIdx = 0
            var i = 0
            while (i < result.size) {
                val value = values[valueIdx++]
                result[i] = Float.fromBits((value and 0xFFFFFFFFL).toInt())
                result[++i] = Float.fromBits((value shr 32 and 0xFFFFFFFFL).toInt())
                i++
            }
            return result
        }

        fun vecToF64(values: LongArray): DoubleArray {
            val result = DoubleArray(values.size)
            for (i in result.indices) {
                result[i] = Double.fromBits(values[i])
            }
            return result
        }

        fun i8ToVec(vararg vec: LongArray): LongArray {
            val result = LongArray(vec.size * 2)
            var resultIdx = 0
            for (v in vec) {
                result[resultIdx++] =
                    (v[0] and 0xFFL) or
                        (v[1] and 0xFFL shl 8) or
                        (v[2] and 0xFFL shl 16) or
                        (v[3] and 0xFFL shl 24) or
                        (v[4] and 0xFFL shl 32) or
                        (v[5] and 0xFFL shl 40) or
                        (v[6] and 0xFFL shl 48) or
                        (v[7] and 0xFFL shl 56)
                result[resultIdx++] =
                    (v[8] and 0xFFL) or
                        (v[9] and 0xFFL shl 8) or
                        (v[10] and 0xFFL shl 16) or
                        (v[11] and 0xFFL shl 24) or
                        (v[12] and 0xFFL shl 32) or
                        (v[13] and 0xFFL shl 40) or
                        (v[14] and 0xFFL shl 48) or
                        (v[15] and 0xFFL shl 56)
            }
            return result
        }

        fun i16ToVec(vararg vec: LongArray): LongArray {
            val result = LongArray(vec.size * 2)
            var resultIdx = 0
            for (v in vec) {
                result[resultIdx++] =
                    (v[0] and 0xFFFFL) or
                        (v[1] and 0xFFFFL shl 16) or
                        (v[2] and 0xFFFFL shl 32) or
                        (v[3] and 0xFFFFL shl 48)
                result[resultIdx++] =
                    (v[4] and 0xFFFFL) or
                        (v[5] and 0xFFFFL shl 16) or
                        (v[6] and 0xFFFFL shl 32) or
                        (v[7] and 0xFFFFL shl 48)
            }
            return result
        }

        fun i32ToVec(vararg vec: LongArray): LongArray {
            val result = LongArray(vec.size * 2)
            var resultIdx = 0
            for (v in vec) {
                result[resultIdx++] = (v[1] and 0xFFFF_FFFFL shl 32) or (v[0] and 0xFFFF_FFFFL)
                result[resultIdx++] = (v[3] and 0xFFFF_FFFFL shl 32) or (v[2] and 0xFFFF_FFFFL)
            }
            return result
        }

        fun i64ToVec(vararg vec: LongArray): LongArray {
            val result = LongArray(vec.size * 2)
            var resultIdx = 0
            for (v in vec) {
                result[resultIdx++] = v[0]
                result[resultIdx++] = v[1]
            }
            return result
        }

        fun f32ToVec(vararg vec: LongArray): LongArray = i32ToVec(*vec)

        fun f64ToVec(vararg vec: LongArray): LongArray = i64ToVec(*vec)

        fun zero(valType: ValType): Long =
            when (valType.opcode()) {
                ValType.ID.I32,
                ValType.ID.F32,
                ValType.ID.I64,
                ValType.ID.F64 -> 0L
                ValType.ID.ExnRef,
                ValType.ID.Ref,
                ValType.ID.RefNull -> REF_NULL_VALUE.toLong()
                else ->
                    throw IllegalArgumentException("Can't create a zero value for type $valType")
            }

        private fun byteToUnsignedLong(value: Byte): Long = value.toLong() and 0xFFL
    }
}
