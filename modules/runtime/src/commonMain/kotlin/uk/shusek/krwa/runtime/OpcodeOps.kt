package uk.shusek.krwa.runtime

import uk.shusek.krwa.runtime.BitOps.Companion.FALSE
import uk.shusek.krwa.runtime.BitOps.Companion.TRUE
import uk.shusek.krwa.runtime.ConstantEvaluators.computeConstantValue
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.PassiveElement
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.withSign

/**
 * Common opcode helper logic shared by interpreter and JVM compiler facade.
 *
 * Note about parameter ordering: because of the JVM's calling convention, the parameters to a
 * method are ordered such that the last value pushed is the last argument to the method, i.e.,
 * method(tos - 2, tos - 1, tos).
 */
@Suppress("FunctionName", "unused")
object OpcodeOps {
    @OpCodeIdentifier(OpCode.I32_CLZ)
    fun I32_CLZ(tos: Int): Int = tos.countLeadingZeroBits()

    @OpCodeIdentifier(OpCode.I32_CTZ)
    fun I32_CTZ(tos: Int): Int = tos.countTrailingZeroBits()

    @OpCodeIdentifier(OpCode.I32_DIV_S)
    fun I32_DIV_S(a: Int, b: Int): Int {
        if (a == Int.MIN_VALUE && b == -1) {
            throw WasmRuntimeException("integer overflow")
        }
        if (b == 0) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return a / b
    }

    @OpCodeIdentifier(OpCode.I32_DIV_U)
    fun I32_DIV_U(a: Int, b: Int): Int {
        if (b == 0) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return (a.toUInt() / b.toUInt()).toInt()
    }

    @OpCodeIdentifier(OpCode.I32_EQ)
    fun I32_EQ(b: Int, a: Int): Int = if (a == b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_EQZ)
    fun I32_EQZ(a: Int): Int = if (a == 0) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_EXTEND_8_S)
    fun I32_EXTEND_8_S(tos: Int): Int = tos.toByte().toInt()

    @OpCodeIdentifier(OpCode.I32_EXTEND_16_S)
    fun I32_EXTEND_16_S(tos: Int): Int = tos.toShort().toInt()

    @OpCodeIdentifier(OpCode.I32_GE_S)
    fun I32_GE_S(a: Int, b: Int): Int = if (a >= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_GE_U)
    fun I32_GE_U(a: Int, b: Int): Int = if (a.toUInt() >= b.toUInt()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_GT_S)
    fun I32_GT_S(a: Int, b: Int): Int = if (a > b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_GT_U)
    fun I32_GT_U(a: Int, b: Int): Int = if (a.toUInt() > b.toUInt()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_LE_S)
    fun I32_LE_S(a: Int, b: Int): Int = if (a <= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_LE_U)
    fun I32_LE_U(a: Int, b: Int): Int = if (a.toUInt() <= b.toUInt()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_LT_S)
    fun I32_LT_S(a: Int, b: Int): Int = if (a < b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_LT_U)
    fun I32_LT_U(a: Int, b: Int): Int = if (a.toUInt() < b.toUInt()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I32_NE)
    fun I32_NE(b: Int, a: Int): Int = if (a == b) FALSE else TRUE

    @OpCodeIdentifier(OpCode.I32_POPCNT)
    fun I32_POPCNT(tos: Int): Int = tos.countOneBits()

    @OpCodeIdentifier(OpCode.I32_REINTERPRET_F32)
    fun I32_REINTERPRET_F32(x: Float): Int = x.toRawBits()

    @OpCodeIdentifier(OpCode.I32_REM_S)
    fun I32_REM_S(a: Int, b: Int): Int {
        if (b == 0) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return a % b
    }

    @OpCodeIdentifier(OpCode.I32_REM_U)
    fun I32_REM_U(a: Int, b: Int): Int {
        if (b == 0) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return (a.toUInt() % b.toUInt()).toInt()
    }

    @OpCodeIdentifier(OpCode.I32_ROTR)
    fun I32_ROTR(v: Int, c: Int): Int = (v ushr c) or (v shl (32 - c))

    @OpCodeIdentifier(OpCode.I32_ROTL)
    fun I32_ROTL(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))

    @OpCodeIdentifier(OpCode.I32_TRUNC_F32_S)
    fun I32_TRUNC_F32_S(x: Float): Int {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        if (x < Int.MIN_VALUE || x >= Int.MAX_VALUE) {
            throw WasmRuntimeException("integer overflow")
        }
        return x.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_F32_U)
    fun I32_TRUNC_F32_U(x: Float): Int {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        val value = x.toLong()
        if (value < 0 || value >= 0xFFFFFFFFL) {
            throw WasmRuntimeException("integer overflow")
        }
        return value.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_F64_S)
    fun I32_TRUNC_F64_S(tos: Double): Int {
        if (tos.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        val value = tos.toLong()
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw WasmRuntimeException("integer overflow")
        }
        return value.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_F64_U)
    fun I32_TRUNC_F64_U(tos: Double): Int {
        if (tos.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        val value = tos.toLong()
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw WasmRuntimeException("integer overflow")
        }
        return value.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F32_S)
    fun I32_TRUNC_SAT_F32_S(x: Float): Int {
        if (x.isNaN()) {
            return 0
        }
        if (x < Int.MIN_VALUE) {
            return Int.MIN_VALUE
        }
        if (x > Int.MAX_VALUE) {
            return Int.MAX_VALUE
        }
        return x.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F32_U)
    fun I32_TRUNC_SAT_F32_U(x: Float): Int {
        if (x.isNaN() || x < 0) {
            return 0
        }
        if (x >= 0xFFFFFFFFL) {
            return -1
        }
        return x.toLong().toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F64_S)
    fun I32_TRUNC_SAT_F64_S(x: Double): Int {
        if (x.isNaN()) {
            return 0
        }
        if (x < Int.MIN_VALUE) {
            return Int.MIN_VALUE
        }
        if (x > Int.MAX_VALUE) {
            return Int.MAX_VALUE
        }
        return x.toInt()
    }

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F64_U)
    fun I32_TRUNC_SAT_F64_U(x: Double): Int {
        if (x.isNaN() || x < 0) {
            return 0
        }
        if (x > 0xFFFFFFFFL) {
            return -1
        }
        return x.toLong().toInt()
    }

    @OpCodeIdentifier(OpCode.I64_CLZ)
    fun I64_CLZ(tos: Long): Long = tos.countLeadingZeroBits().toLong()

    @OpCodeIdentifier(OpCode.I64_CTZ)
    fun I64_CTZ(tos: Long): Long = tos.countTrailingZeroBits().toLong()

    @OpCodeIdentifier(OpCode.I64_DIV_S)
    fun I64_DIV_S(a: Long, b: Long): Long {
        if (a == Long.MIN_VALUE && b == -1L) {
            throw WasmRuntimeException("integer overflow")
        }
        if (b == 0L) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return a / b
    }

    @OpCodeIdentifier(OpCode.I64_DIV_U)
    fun I64_DIV_U(a: Long, b: Long): Long {
        if (b == 0L) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return (a.toULong() / b.toULong()).toLong()
    }

    @OpCodeIdentifier(OpCode.I64_EQ)
    fun I64_EQ(b: Long, a: Long): Int = if (a == b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_EQZ)
    fun I64_EQZ(a: Long): Int = if (a == 0L) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_EXTEND_8_S)
    fun I64_EXTEND_8_S(tos: Long): Long = tos.toByte().toLong()

    @OpCodeIdentifier(OpCode.I64_EXTEND_16_S)
    fun I64_EXTEND_16_S(tos: Long): Long = tos.toShort().toLong()

    @OpCodeIdentifier(OpCode.I64_EXTEND_32_S)
    fun I64_EXTEND_32_S(tos: Long): Long = tos.toInt().toLong()

    @OpCodeIdentifier(OpCode.I64_EXTEND_I32_U)
    fun I64_EXTEND_I32_U(x: Int): Long = x.toUInt().toLong()

    @OpCodeIdentifier(OpCode.I64_GE_S)
    fun I64_GE_S(a: Long, b: Long): Int = if (a >= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_GE_U)
    fun I64_GE_U(a: Long, b: Long): Int = if (a.toULong() >= b.toULong()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_GT_S)
    fun I64_GT_S(a: Long, b: Long): Int = if (a > b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_GT_U)
    fun I64_GT_U(a: Long, b: Long): Int = if (a.toULong() > b.toULong()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_LE_S)
    fun I64_LE_S(a: Long, b: Long): Int = if (a <= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_LE_U)
    fun I64_LE_U(a: Long, b: Long): Int = if (a.toULong() <= b.toULong()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_LT_S)
    fun I64_LT_S(a: Long, b: Long): Int = if (a < b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_LT_U)
    fun I64_LT_U(a: Long, b: Long): Int = if (a.toULong() < b.toULong()) TRUE else FALSE

    @OpCodeIdentifier(OpCode.I64_NE)
    fun I64_NE(b: Long, a: Long): Int = if (a == b) FALSE else TRUE

    @OpCodeIdentifier(OpCode.I64_POPCNT)
    fun I64_POPCNT(tos: Long): Long = tos.countOneBits().toLong()

    @OpCodeIdentifier(OpCode.I64_REINTERPRET_F64)
    fun I64_REINTERPRET_F64(x: Double): Long = x.toRawBits()

    @OpCodeIdentifier(OpCode.I64_REM_S)
    fun I64_REM_S(a: Long, b: Long): Long {
        if (b == 0L) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return a % b
    }

    @OpCodeIdentifier(OpCode.I64_REM_U)
    fun I64_REM_U(a: Long, b: Long): Long {
        if (b == 0L) {
            throw WasmRuntimeException("integer divide by zero")
        }
        return (a.toULong() % b.toULong()).toLong()
    }

    @OpCodeIdentifier(OpCode.I64_ROTR)
    fun I64_ROTR(v: Long, c: Long): Long {
        val shift = c.toInt()
        return (v ushr shift) or (v shl (64 - shift))
    }

    @OpCodeIdentifier(OpCode.I64_ROTL)
    fun I64_ROTL(v: Long, c: Long): Long {
        val shift = c.toInt()
        return (v shl shift) or (v ushr (64 - shift))
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_F32_S)
    fun I64_TRUNC_F32_S(x: Float): Long {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        if (x < Long.MIN_VALUE || x >= Long.MAX_VALUE) {
            throw WasmRuntimeException("integer overflow")
        }
        return x.toLong()
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_F32_U)
    fun I64_TRUNC_F32_U(x: Float): Long {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        if (x >= 2 * Long.MAX_VALUE.toFloat()) {
            throw WasmRuntimeException("integer overflow")
        }

        if (x < Long.MAX_VALUE) {
            val value = x.toLong()
            if (value < 0) {
                throw WasmRuntimeException("integer overflow")
            }
            return value
        }

        // This works for getting the unsigned value because binary addition yields the correct
        // interpretation in both unsigned and 2's-complement, no matter which the operands are
        // considered to be.
        val value = Long.MAX_VALUE + (x - Long.MAX_VALUE.toFloat()).toLong() + 1

        // Java's comparison operators assume signed integers. In the case that we're in the range
        // of unsigned values where the sign bit is set, Java considers these values to be negative,
        // so we have to check for >= 0 to detect overflow.
        if (value >= 0) {
            throw WasmRuntimeException("integer overflow")
        }
        return value
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_F64_S)
    fun I64_TRUNC_F64_S(x: Double): Long {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        if (x == Long.MIN_VALUE.toDouble()) {
            return Long.MIN_VALUE
        }
        val value = x.toLong()
        if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
            throw WasmRuntimeException("integer overflow")
        }
        return value
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_F64_U)
    fun I64_TRUNC_F64_U(x: Double): Long {
        if (x.isNaN()) {
            throw WasmRuntimeException("invalid conversion to integer")
        }
        if (x >= 2 * Long.MAX_VALUE.toDouble()) {
            throw WasmRuntimeException("integer overflow")
        }

        if (x < Long.MAX_VALUE) {
            val value = x.toLong()
            if (value < 0) {
                throw WasmRuntimeException("integer overflow")
            }
            return value
        }

        // See I64_TRUNC_F32_U for notes on implementation. This is the double-based equivalent of
        // that.
        val value = Long.MAX_VALUE + (x - Long.MAX_VALUE.toDouble()).toLong() + 1
        if (value >= 0) {
            throw WasmRuntimeException("integer overflow")
        }
        return value
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F32_S)
    fun I64_TRUNC_SAT_F32_S(x: Float): Long {
        if (x.isNaN()) {
            return 0
        }
        if (x <= Long.MIN_VALUE) {
            return Long.MIN_VALUE
        }
        if (x >= Long.MAX_VALUE) {
            return Long.MAX_VALUE
        }
        return x.toLong()
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F32_U)
    fun I64_TRUNC_SAT_F32_U(x: Float): Long {
        if (x.isNaN() || x < 0) {
            return 0
        }
        if (x > U64_MAX_AS_DOUBLE) {
            return -1L
        }
        if (x < Long.MAX_VALUE) {
            return x.toLong()
        }

        // See I64_TRUNC_F32_U for notes on implementation. This is the double-based equivalent of
        // that.
        val value = Long.MAX_VALUE + (x - Long.MAX_VALUE.toDouble()).toLong() + 1
        if (value >= 0) {
            throw WasmRuntimeException("integer overflow")
        }
        return value
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F64_S)
    fun I64_TRUNC_SAT_F64_S(x: Double): Long {
        if (x.isNaN()) {
            return 0
        }
        if (x <= Long.MIN_VALUE) {
            return Long.MIN_VALUE
        }
        if (x >= Long.MAX_VALUE) {
            return Long.MAX_VALUE
        }
        return x.toLong()
    }

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F64_U)
    fun I64_TRUNC_SAT_F64_U(x: Double): Long {
        if (x.isNaN() || x < 0) {
            return 0L
        }
        if (x > U64_MAX_AS_DOUBLE) {
            return -1L
        }
        if (x < Long.MAX_VALUE) {
            return x.toLong()
        }

        // See I64_TRUNC_F32_U for notes on implementation. This is the double-based equivalent of
        // that.
        val value = Long.MAX_VALUE + (x - Long.MAX_VALUE.toDouble()).toLong() + 1
        if (value >= 0) {
            throw WasmRuntimeException("integer overflow")
        }
        return value
    }

    @OpCodeIdentifier(OpCode.F32_ABS) fun F32_ABS(x: Float): Float = abs(x)

    @OpCodeIdentifier(OpCode.F32_CEIL)
    fun F32_CEIL(x: Float): Float = ceil(x.toDouble()).toFloat()

    @OpCodeIdentifier(OpCode.F32_CONVERT_I32_S)
    fun F32_CONVERT_I32_S(x: Int): Float = x.toFloat()

    @OpCodeIdentifier(OpCode.F32_CONVERT_I32_U)
    fun F32_CONVERT_I32_U(x: Int): Float = x.toUInt().toFloat()

    @OpCodeIdentifier(OpCode.F32_CONVERT_I64_S)
    fun F32_CONVERT_I64_S(x: Long): Float = x.toFloat()

    @OpCodeIdentifier(OpCode.F32_CONVERT_I64_U)
    fun F32_CONVERT_I64_U(x: Long): Float {
        if (x >= 0) {
            return x.toFloat()
        }
        return ((x ushr 1) or (x and 1L)).toFloat() * 2.0f
    }

    @OpCodeIdentifier(OpCode.F32_COPYSIGN)
    fun F32_COPYSIGN(a: Float, b: Float): Float {
        if (b == 0xFFC00000L.toFloat()) {
            return a.withSign(-1.0f)
        }
        if (b == 0x7FC00000L.toFloat()) {
            return a.withSign(+1.0f)
        }
        return a.withSign(b)
    }

    @OpCodeIdentifier(OpCode.F32_EQ)
    fun F32_EQ(a: Float, b: Float): Int = if (a == b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F32_FLOOR)
    fun F32_FLOOR(x: Float): Float = floor(x.toDouble()).toFloat()

    @OpCodeIdentifier(OpCode.F32_GE)
    fun F32_GE(a: Float, b: Float): Int = if (a >= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F32_GT)
    fun F32_GT(a: Float, b: Float): Int = if (a > b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F32_LE)
    fun F32_LE(a: Float, b: Float): Int = if (a <= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F32_LT)
    fun F32_LT(a: Float, b: Float): Int = if (a < b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F32_MAX)
    fun F32_MAX(a: Float, b: Float): Float = max(a, b)

    @OpCodeIdentifier(OpCode.F32_MIN)
    fun F32_MIN(a: Float, b: Float): Float = min(a, b)

    @OpCodeIdentifier(OpCode.F32_NE)
    fun F32_NE(a: Float, b: Float): Int = if (a == b) FALSE else TRUE

    @OpCodeIdentifier(OpCode.F32_NEAREST)
    fun F32_NEAREST(x: Float): Float = nearestEven(x.toDouble()).toFloat()

    @OpCodeIdentifier(OpCode.F32_REINTERPRET_I32)
    fun F32_REINTERPRET_I32(x: Int): Float = Float.fromBits(x)

    @OpCodeIdentifier(OpCode.F32_SQRT)
    fun F32_SQRT(x: Float): Float = sqrt(x.toDouble()).toFloat()

    @OpCodeIdentifier(OpCode.F32_TRUNC)
    fun F32_TRUNC(x: Float): Float =
        (if (x < 0) ceil(x.toDouble()) else floor(x.toDouble())).toFloat()

    @OpCodeIdentifier(OpCode.F64_ABS) fun F64_ABS(x: Double): Double = abs(x)

    @OpCodeIdentifier(OpCode.F64_CEIL) fun F64_CEIL(x: Double): Double = ceil(x)

    @OpCodeIdentifier(OpCode.F64_CONVERT_I32_S)
    fun F64_CONVERT_I32_S(x: Int): Double = x.toDouble()

    @OpCodeIdentifier(OpCode.F64_CONVERT_I32_U)
    fun F64_CONVERT_I32_U(x: Int): Double = x.toUInt().toDouble()

    @OpCodeIdentifier(OpCode.F64_CONVERT_I64_S)
    fun F64_CONVERT_I64_S(x: Long): Double = x.toDouble()

    @OpCodeIdentifier(OpCode.F64_CONVERT_I64_U)
    fun F64_CONVERT_I64_U(tos: Long): Double {
        if (tos >= 0) {
            return tos.toDouble()
        }
        return ((tos ushr 1) or (tos and 1L)).toDouble() * 2.0
    }

    @OpCodeIdentifier(OpCode.F64_COPYSIGN)
    fun F64_COPYSIGN(a: Double, b: Double): Double {
        if (b == F64_POS_NAN_SENTINEL.toDouble()) {
            return a.withSign(-1.0)
        }
        if (b == F64_NEG_NAN_SENTINEL.toDouble()) {
            return a.withSign(+1.0)
        }
        return a.withSign(b)
    }

    @OpCodeIdentifier(OpCode.F64_EQ)
    fun F64_EQ(a: Double, b: Double): Int = if (a == b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F64_FLOOR) fun F64_FLOOR(x: Double): Double = floor(x)

    @OpCodeIdentifier(OpCode.F64_GE)
    fun F64_GE(a: Double, b: Double): Int = if (a >= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F64_GT)
    fun F64_GT(a: Double, b: Double): Int = if (a > b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F64_LE)
    fun F64_LE(a: Double, b: Double): Int = if (a <= b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F64_LT)
    fun F64_LT(a: Double, b: Double): Int = if (a < b) TRUE else FALSE

    @OpCodeIdentifier(OpCode.F64_MAX)
    fun F64_MAX(a: Double, b: Double): Double = max(a, b)

    @OpCodeIdentifier(OpCode.F64_MIN)
    fun F64_MIN(a: Double, b: Double): Double = min(a, b)

    @OpCodeIdentifier(OpCode.F64_NE)
    fun F64_NE(a: Double, b: Double): Int = if (a == b) FALSE else TRUE

    @OpCodeIdentifier(OpCode.F64_NEAREST)
    fun F64_NEAREST(x: Double): Double = nearestEven(x)

    @OpCodeIdentifier(OpCode.F64_REINTERPRET_I64)
    fun F64_REINTERPRET_I64(x: Long): Double = Double.fromBits(x)

    @OpCodeIdentifier(OpCode.F64_SQRT) fun F64_SQRT(x: Double): Double = sqrt(x)

    @OpCodeIdentifier(OpCode.F64_TRUNC)
    fun F64_TRUNC(x: Double): Double = if (x < 0) ceil(x) else floor(x)

    fun TABLE_GET(instance: Instance, tableIndex: Int, index: Int): Int {
        val table = instance.table(tableIndex)
        if (index < 0 || index >= table.limits().max() || index >= table.size()) {
            throw WasmRuntimeException("out of bounds table access")
        }
        return table.ref(index)
    }

    fun TABLE_FILL(instance: Instance, tableIndex: Int, size: Int, value: Int, offset: Int) {
        val endL = offset.toLong() + size.toLong()
        val table = instance.table(tableIndex)

        if (size < 0 || offset < 0 || endL > table.size().toLong()) {
            throw WasmRuntimeException("out of bounds table access")
        }

        val end = endL.toInt()
        for (i in offset until end) {
            table.setRef(i, value, instance)
        }
    }

    fun TABLE_COPY(
        instance: Instance,
        srcTableIndex: Int,
        dstTableIndex: Int,
        size: Int,
        s: Int,
        d: Int,
    ) {
        val src = instance.table(srcTableIndex)
        val dest = instance.table(dstTableIndex)
        var sourceIndex = s
        var destIndex = d

        if (
            size < 0 ||
                (sourceIndex < 0 || size + sourceIndex > src.size()) ||
                (destIndex < 0 || size + destIndex > dest.size())
        ) {
            throw WasmRuntimeException("out of bounds table access")
        }

        for (i in size - 1 downTo 0) {
            if (destIndex <= sourceIndex) {
                val value = src.ref(sourceIndex)
                val inst = src.instance(sourceIndex)
                dest.setRef(destIndex, value, inst)
                sourceIndex++
                destIndex++
            } else {
                val value = src.ref(sourceIndex + i)
                val inst = src.instance(sourceIndex + i)
                dest.setRef(destIndex + i, value, inst)
            }
        }
    }

    fun TABLE_INIT(
        instance: Instance,
        tableidx: Int,
        elementidx: Int,
        size: Int,
        elemidx: Int,
        offset: Int,
    ) {
        val endL = offset.toLong() + size.toLong()
        val table = instance.table(tableidx)
        val elementCount = instance.elementCount()
        val currentElement = instance.elementOrNull(elementidx)
        val currentElementCount =
            if (currentElement is PassiveElement) currentElement.elementCount() else 0
        val isOutOfBounds =
            size < 0 ||
                offset < 0 ||
                elemidx < 0 ||
                elementidx > elementCount ||
                (size > 0 && currentElement !is PassiveElement) ||
                elemidx.toLong() + size.toLong() > currentElementCount.toLong() ||
                endL > table.size().toLong()

        if (isOutOfBounds) {
            throw WasmRuntimeException("out of bounds table access")
        }
        if (size == 0) {
            return
        }

        var currentElemidx = elemidx
        val end = endL.toInt()
        for (i in offset until end) {
            val elem = instance.element(elementidx)
            val value =
                boxForTable(
                    computeConstantValue(instance, elem.initializers()[currentElemidx++])[0],
                    instance,
                )
            if (table.elementType() == ValType.FuncRef) {
                if (value > instance.functionCount()) {
                    throw WasmRuntimeException("out of bounds table access")
                }
                table.setRef(i, value, instance)
            } else {
                table.setRef(i, value, instance)
            }
        }
    }

    /**
     * Converts a stack long value to an int suitable for table storage. i31 values (tagged longs)
     * are boxed as WasmI31Ref GC refs so the tag is preserved. For non-GC values (funcref,
     * externref), this is a no-op cast to int.
     */
    fun boxForTable(stackValue: Long, instance: Instance): Int {
        if (Value.isI31(stackValue)) {
            val i31Ref = WasmI31Ref(Value.decodeI31U(stackValue))
            return instance.registerGcRef(i31Ref)
        }
        return stackValue.toInt()
    }

    /**
     * Converts an int from table storage to a stack long value, unboxing i31 refs. Only performs GC
     * ref lookup for GC-typed tables (anyref, eqref, etc.) to avoid overhead for funcref tables in
     * non-GC modules.
     */
    fun unboxFromTable(tableValue: Int, instance: Instance, elementType: ValType): Long {
        if (
            tableValue != Value.REF_NULL_VALUE &&
                tableValue >= 0 &&
                elementType != ValType.FuncRef &&
                elementType != ValType.ExternRef
        ) {
            val gcRef = instance.gcRef(tableValue)
            if (gcRef is WasmI31Ref) {
                return Value.encodeI31(gcRef.value())
            }
        }
        return tableValue.toLong()
    }

    @OpCodeIdentifier(OpCode.ATOMIC_FENCE)
    fun ATOMIC_FENCE() = Unit

    private fun nearestEven(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || x == 0.0) {
            return x
        }

        val floorValue = floor(x)
        val diff = x - floorValue
        val rounded =
            when {
                diff < 0.5 -> floorValue
                diff > 0.5 -> floorValue + 1.0
                floorValue % 2.0 == 0.0 -> floorValue
                else -> floorValue + 1.0
            }
        return if (rounded == 0.0) rounded.withSign(x) else rounded
    }

    private const val U64_MAX_AS_DOUBLE = 18_446_744_073_709_551_615.0
    private const val F64_POS_NAN_SENTINEL = -18014398509481984L
    private const val F64_NEG_NAN_SENTINEL = 0x7FC0000000000000L
}
