package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

/** JVM static facade used by the bytecode compiler. Common runtime code should use OpcodeOps. */
@Suppress("FunctionName", "unused")
object OpcodeImpl {
    @OpCodeIdentifier(OpCode.I32_CLZ)
    @JvmStatic
    fun I32_CLZ(tos: Int): Int = OpcodeOps.I32_CLZ(tos)

    @OpCodeIdentifier(OpCode.I32_CTZ)
    @JvmStatic
    fun I32_CTZ(tos: Int): Int = OpcodeOps.I32_CTZ(tos)

    @OpCodeIdentifier(OpCode.I32_DIV_S)
    @JvmStatic
    fun I32_DIV_S(a: Int, b: Int): Int = OpcodeOps.I32_DIV_S(a, b)

    @OpCodeIdentifier(OpCode.I32_DIV_U)
    @JvmStatic
    fun I32_DIV_U(a: Int, b: Int): Int = OpcodeOps.I32_DIV_U(a, b)

    @OpCodeIdentifier(OpCode.I32_EQ)
    @JvmStatic
    fun I32_EQ(b: Int, a: Int): Int = OpcodeOps.I32_EQ(b, a)

    @OpCodeIdentifier(OpCode.I32_EQZ)
    @JvmStatic
    fun I32_EQZ(a: Int): Int = OpcodeOps.I32_EQZ(a)

    @OpCodeIdentifier(OpCode.I32_EXTEND_8_S)
    @JvmStatic
    fun I32_EXTEND_8_S(tos: Int): Int = OpcodeOps.I32_EXTEND_8_S(tos)

    @OpCodeIdentifier(OpCode.I32_EXTEND_16_S)
    @JvmStatic
    fun I32_EXTEND_16_S(tos: Int): Int = OpcodeOps.I32_EXTEND_16_S(tos)

    @OpCodeIdentifier(OpCode.I32_GE_S)
    @JvmStatic
    fun I32_GE_S(a: Int, b: Int): Int = OpcodeOps.I32_GE_S(a, b)

    @OpCodeIdentifier(OpCode.I32_GE_U)
    @JvmStatic
    fun I32_GE_U(a: Int, b: Int): Int = OpcodeOps.I32_GE_U(a, b)

    @OpCodeIdentifier(OpCode.I32_GT_S)
    @JvmStatic
    fun I32_GT_S(a: Int, b: Int): Int = OpcodeOps.I32_GT_S(a, b)

    @OpCodeIdentifier(OpCode.I32_GT_U)
    @JvmStatic
    fun I32_GT_U(a: Int, b: Int): Int = OpcodeOps.I32_GT_U(a, b)

    @OpCodeIdentifier(OpCode.I32_LE_S)
    @JvmStatic
    fun I32_LE_S(a: Int, b: Int): Int = OpcodeOps.I32_LE_S(a, b)

    @OpCodeIdentifier(OpCode.I32_LE_U)
    @JvmStatic
    fun I32_LE_U(a: Int, b: Int): Int = OpcodeOps.I32_LE_U(a, b)

    @OpCodeIdentifier(OpCode.I32_LT_S)
    @JvmStatic
    fun I32_LT_S(a: Int, b: Int): Int = OpcodeOps.I32_LT_S(a, b)

    @OpCodeIdentifier(OpCode.I32_LT_U)
    @JvmStatic
    fun I32_LT_U(a: Int, b: Int): Int = OpcodeOps.I32_LT_U(a, b)

    @OpCodeIdentifier(OpCode.I32_NE)
    @JvmStatic
    fun I32_NE(b: Int, a: Int): Int = OpcodeOps.I32_NE(b, a)

    @OpCodeIdentifier(OpCode.I32_POPCNT)
    @JvmStatic
    fun I32_POPCNT(tos: Int): Int = OpcodeOps.I32_POPCNT(tos)

    @OpCodeIdentifier(OpCode.I32_REINTERPRET_F32)
    @JvmStatic
    fun I32_REINTERPRET_F32(x: Float): Int = OpcodeOps.I32_REINTERPRET_F32(x)

    @OpCodeIdentifier(OpCode.I32_REM_S)
    @JvmStatic
    fun I32_REM_S(a: Int, b: Int): Int = OpcodeOps.I32_REM_S(a, b)

    @OpCodeIdentifier(OpCode.I32_REM_U)
    @JvmStatic
    fun I32_REM_U(a: Int, b: Int): Int = OpcodeOps.I32_REM_U(a, b)

    @OpCodeIdentifier(OpCode.I32_ROTR)
    @JvmStatic
    fun I32_ROTR(v: Int, c: Int): Int = OpcodeOps.I32_ROTR(v, c)

    @OpCodeIdentifier(OpCode.I32_ROTL)
    @JvmStatic
    fun I32_ROTL(v: Int, c: Int): Int = OpcodeOps.I32_ROTL(v, c)

    @OpCodeIdentifier(OpCode.I32_TRUNC_F32_S)
    @JvmStatic
    fun I32_TRUNC_F32_S(x: Float): Int = OpcodeOps.I32_TRUNC_F32_S(x)

    @OpCodeIdentifier(OpCode.I32_TRUNC_F32_U)
    @JvmStatic
    fun I32_TRUNC_F32_U(x: Float): Int = OpcodeOps.I32_TRUNC_F32_U(x)

    @OpCodeIdentifier(OpCode.I32_TRUNC_F64_S)
    @JvmStatic
    fun I32_TRUNC_F64_S(tos: Double): Int = OpcodeOps.I32_TRUNC_F64_S(tos)

    @OpCodeIdentifier(OpCode.I32_TRUNC_F64_U)
    @JvmStatic
    fun I32_TRUNC_F64_U(tos: Double): Int = OpcodeOps.I32_TRUNC_F64_U(tos)

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F32_S)
    @JvmStatic
    fun I32_TRUNC_SAT_F32_S(x: Float): Int = OpcodeOps.I32_TRUNC_SAT_F32_S(x)

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F32_U)
    @JvmStatic
    fun I32_TRUNC_SAT_F32_U(x: Float): Int = OpcodeOps.I32_TRUNC_SAT_F32_U(x)

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F64_S)
    @JvmStatic
    fun I32_TRUNC_SAT_F64_S(x: Double): Int = OpcodeOps.I32_TRUNC_SAT_F64_S(x)

    @OpCodeIdentifier(OpCode.I32_TRUNC_SAT_F64_U)
    @JvmStatic
    fun I32_TRUNC_SAT_F64_U(x: Double): Int = OpcodeOps.I32_TRUNC_SAT_F64_U(x)

    @OpCodeIdentifier(OpCode.I64_CLZ)
    @JvmStatic
    fun I64_CLZ(tos: Long): Long = OpcodeOps.I64_CLZ(tos)

    @OpCodeIdentifier(OpCode.I64_CTZ)
    @JvmStatic
    fun I64_CTZ(tos: Long): Long = OpcodeOps.I64_CTZ(tos)

    @OpCodeIdentifier(OpCode.I64_DIV_S)
    @JvmStatic
    fun I64_DIV_S(a: Long, b: Long): Long = OpcodeOps.I64_DIV_S(a, b)

    @OpCodeIdentifier(OpCode.I64_DIV_U)
    @JvmStatic
    fun I64_DIV_U(a: Long, b: Long): Long = OpcodeOps.I64_DIV_U(a, b)

    @OpCodeIdentifier(OpCode.I64_EQ)
    @JvmStatic
    fun I64_EQ(b: Long, a: Long): Int = OpcodeOps.I64_EQ(b, a)

    @OpCodeIdentifier(OpCode.I64_EQZ)
    @JvmStatic
    fun I64_EQZ(a: Long): Int = OpcodeOps.I64_EQZ(a)

    @OpCodeIdentifier(OpCode.I64_EXTEND_8_S)
    @JvmStatic
    fun I64_EXTEND_8_S(tos: Long): Long = OpcodeOps.I64_EXTEND_8_S(tos)

    @OpCodeIdentifier(OpCode.I64_EXTEND_16_S)
    @JvmStatic
    fun I64_EXTEND_16_S(tos: Long): Long = OpcodeOps.I64_EXTEND_16_S(tos)

    @OpCodeIdentifier(OpCode.I64_EXTEND_32_S)
    @JvmStatic
    fun I64_EXTEND_32_S(tos: Long): Long = OpcodeOps.I64_EXTEND_32_S(tos)

    @OpCodeIdentifier(OpCode.I64_EXTEND_I32_U)
    @JvmStatic
    fun I64_EXTEND_I32_U(x: Int): Long = OpcodeOps.I64_EXTEND_I32_U(x)

    @OpCodeIdentifier(OpCode.I64_GE_S)
    @JvmStatic
    fun I64_GE_S(a: Long, b: Long): Int = OpcodeOps.I64_GE_S(a, b)

    @OpCodeIdentifier(OpCode.I64_GE_U)
    @JvmStatic
    fun I64_GE_U(a: Long, b: Long): Int = OpcodeOps.I64_GE_U(a, b)

    @OpCodeIdentifier(OpCode.I64_GT_S)
    @JvmStatic
    fun I64_GT_S(a: Long, b: Long): Int = OpcodeOps.I64_GT_S(a, b)

    @OpCodeIdentifier(OpCode.I64_GT_U)
    @JvmStatic
    fun I64_GT_U(a: Long, b: Long): Int = OpcodeOps.I64_GT_U(a, b)

    @OpCodeIdentifier(OpCode.I64_LE_S)
    @JvmStatic
    fun I64_LE_S(a: Long, b: Long): Int = OpcodeOps.I64_LE_S(a, b)

    @OpCodeIdentifier(OpCode.I64_LE_U)
    @JvmStatic
    fun I64_LE_U(a: Long, b: Long): Int = OpcodeOps.I64_LE_U(a, b)

    @OpCodeIdentifier(OpCode.I64_LT_S)
    @JvmStatic
    fun I64_LT_S(a: Long, b: Long): Int = OpcodeOps.I64_LT_S(a, b)

    @OpCodeIdentifier(OpCode.I64_LT_U)
    @JvmStatic
    fun I64_LT_U(a: Long, b: Long): Int = OpcodeOps.I64_LT_U(a, b)

    @OpCodeIdentifier(OpCode.I64_NE)
    @JvmStatic
    fun I64_NE(b: Long, a: Long): Int = OpcodeOps.I64_NE(b, a)

    @OpCodeIdentifier(OpCode.I64_POPCNT)
    @JvmStatic
    fun I64_POPCNT(tos: Long): Long = OpcodeOps.I64_POPCNT(tos)

    @OpCodeIdentifier(OpCode.I64_REINTERPRET_F64)
    @JvmStatic
    fun I64_REINTERPRET_F64(x: Double): Long = OpcodeOps.I64_REINTERPRET_F64(x)

    @OpCodeIdentifier(OpCode.I64_REM_S)
    @JvmStatic
    fun I64_REM_S(a: Long, b: Long): Long = OpcodeOps.I64_REM_S(a, b)

    @OpCodeIdentifier(OpCode.I64_REM_U)
    @JvmStatic
    fun I64_REM_U(a: Long, b: Long): Long = OpcodeOps.I64_REM_U(a, b)

    @OpCodeIdentifier(OpCode.I64_ROTR)
    @JvmStatic
    fun I64_ROTR(v: Long, c: Long): Long = OpcodeOps.I64_ROTR(v, c)

    @OpCodeIdentifier(OpCode.I64_ROTL)
    @JvmStatic
    fun I64_ROTL(v: Long, c: Long): Long = OpcodeOps.I64_ROTL(v, c)

    @OpCodeIdentifier(OpCode.I64_TRUNC_F32_S)
    @JvmStatic
    fun I64_TRUNC_F32_S(x: Float): Long = OpcodeOps.I64_TRUNC_F32_S(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_F32_U)
    @JvmStatic
    fun I64_TRUNC_F32_U(x: Float): Long = OpcodeOps.I64_TRUNC_F32_U(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_F64_S)
    @JvmStatic
    fun I64_TRUNC_F64_S(x: Double): Long = OpcodeOps.I64_TRUNC_F64_S(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_F64_U)
    @JvmStatic
    fun I64_TRUNC_F64_U(x: Double): Long = OpcodeOps.I64_TRUNC_F64_U(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F32_S)
    @JvmStatic
    fun I64_TRUNC_SAT_F32_S(x: Float): Long = OpcodeOps.I64_TRUNC_SAT_F32_S(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F32_U)
    @JvmStatic
    fun I64_TRUNC_SAT_F32_U(x: Float): Long = OpcodeOps.I64_TRUNC_SAT_F32_U(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F64_S)
    @JvmStatic
    fun I64_TRUNC_SAT_F64_S(x: Double): Long = OpcodeOps.I64_TRUNC_SAT_F64_S(x)

    @OpCodeIdentifier(OpCode.I64_TRUNC_SAT_F64_U)
    @JvmStatic
    fun I64_TRUNC_SAT_F64_U(x: Double): Long = OpcodeOps.I64_TRUNC_SAT_F64_U(x)

    @OpCodeIdentifier(OpCode.F32_ABS)
    @JvmStatic
    fun F32_ABS(x: Float): Float = OpcodeOps.F32_ABS(x)

    @OpCodeIdentifier(OpCode.F32_CEIL)
    @JvmStatic
    fun F32_CEIL(x: Float): Float = OpcodeOps.F32_CEIL(x)

    @OpCodeIdentifier(OpCode.F32_CONVERT_I32_S)
    @JvmStatic
    fun F32_CONVERT_I32_S(x: Int): Float = OpcodeOps.F32_CONVERT_I32_S(x)

    @OpCodeIdentifier(OpCode.F32_CONVERT_I32_U)
    @JvmStatic
    fun F32_CONVERT_I32_U(x: Int): Float = OpcodeOps.F32_CONVERT_I32_U(x)

    @OpCodeIdentifier(OpCode.F32_CONVERT_I64_S)
    @JvmStatic
    fun F32_CONVERT_I64_S(x: Long): Float = OpcodeOps.F32_CONVERT_I64_S(x)

    @OpCodeIdentifier(OpCode.F32_CONVERT_I64_U)
    @JvmStatic
    fun F32_CONVERT_I64_U(x: Long): Float = OpcodeOps.F32_CONVERT_I64_U(x)

    @OpCodeIdentifier(OpCode.F32_COPYSIGN)
    @JvmStatic
    fun F32_COPYSIGN(a: Float, b: Float): Float = OpcodeOps.F32_COPYSIGN(a, b)

    @OpCodeIdentifier(OpCode.F32_EQ)
    @JvmStatic
    fun F32_EQ(a: Float, b: Float): Int = OpcodeOps.F32_EQ(a, b)

    @OpCodeIdentifier(OpCode.F32_FLOOR)
    @JvmStatic
    fun F32_FLOOR(x: Float): Float = OpcodeOps.F32_FLOOR(x)

    @OpCodeIdentifier(OpCode.F32_GE)
    @JvmStatic
    fun F32_GE(a: Float, b: Float): Int = OpcodeOps.F32_GE(a, b)

    @OpCodeIdentifier(OpCode.F32_GT)
    @JvmStatic
    fun F32_GT(a: Float, b: Float): Int = OpcodeOps.F32_GT(a, b)

    @OpCodeIdentifier(OpCode.F32_LE)
    @JvmStatic
    fun F32_LE(a: Float, b: Float): Int = OpcodeOps.F32_LE(a, b)

    @OpCodeIdentifier(OpCode.F32_LT)
    @JvmStatic
    fun F32_LT(a: Float, b: Float): Int = OpcodeOps.F32_LT(a, b)

    @OpCodeIdentifier(OpCode.F32_MAX)
    @JvmStatic
    fun F32_MAX(a: Float, b: Float): Float = OpcodeOps.F32_MAX(a, b)

    @OpCodeIdentifier(OpCode.F32_MIN)
    @JvmStatic
    fun F32_MIN(a: Float, b: Float): Float = OpcodeOps.F32_MIN(a, b)

    @OpCodeIdentifier(OpCode.F32_NE)
    @JvmStatic
    fun F32_NE(a: Float, b: Float): Int = OpcodeOps.F32_NE(a, b)

    @OpCodeIdentifier(OpCode.F32_NEAREST)
    @JvmStatic
    fun F32_NEAREST(x: Float): Float = OpcodeOps.F32_NEAREST(x)

    @OpCodeIdentifier(OpCode.F32_REINTERPRET_I32)
    @JvmStatic
    fun F32_REINTERPRET_I32(x: Int): Float = OpcodeOps.F32_REINTERPRET_I32(x)

    @OpCodeIdentifier(OpCode.F32_SQRT)
    @JvmStatic
    fun F32_SQRT(x: Float): Float = OpcodeOps.F32_SQRT(x)

    @OpCodeIdentifier(OpCode.F32_TRUNC)
    @JvmStatic
    fun F32_TRUNC(x: Float): Float = OpcodeOps.F32_TRUNC(x)

    @OpCodeIdentifier(OpCode.F64_ABS)
    @JvmStatic
    fun F64_ABS(x: Double): Double = OpcodeOps.F64_ABS(x)

    @OpCodeIdentifier(OpCode.F64_CEIL)
    @JvmStatic
    fun F64_CEIL(x: Double): Double = OpcodeOps.F64_CEIL(x)

    @OpCodeIdentifier(OpCode.F64_CONVERT_I32_S)
    @JvmStatic
    fun F64_CONVERT_I32_S(x: Int): Double = OpcodeOps.F64_CONVERT_I32_S(x)

    @OpCodeIdentifier(OpCode.F64_CONVERT_I32_U)
    @JvmStatic
    fun F64_CONVERT_I32_U(x: Int): Double = OpcodeOps.F64_CONVERT_I32_U(x)

    @OpCodeIdentifier(OpCode.F64_CONVERT_I64_S)
    @JvmStatic
    fun F64_CONVERT_I64_S(x: Long): Double = OpcodeOps.F64_CONVERT_I64_S(x)

    @OpCodeIdentifier(OpCode.F64_CONVERT_I64_U)
    @JvmStatic
    fun F64_CONVERT_I64_U(tos: Long): Double = OpcodeOps.F64_CONVERT_I64_U(tos)

    @OpCodeIdentifier(OpCode.F64_COPYSIGN)
    @JvmStatic
    fun F64_COPYSIGN(a: Double, b: Double): Double = OpcodeOps.F64_COPYSIGN(a, b)

    @OpCodeIdentifier(OpCode.F64_EQ)
    @JvmStatic
    fun F64_EQ(a: Double, b: Double): Int = OpcodeOps.F64_EQ(a, b)

    @OpCodeIdentifier(OpCode.F64_FLOOR)
    @JvmStatic
    fun F64_FLOOR(x: Double): Double = OpcodeOps.F64_FLOOR(x)

    @OpCodeIdentifier(OpCode.F64_GE)
    @JvmStatic
    fun F64_GE(a: Double, b: Double): Int = OpcodeOps.F64_GE(a, b)

    @OpCodeIdentifier(OpCode.F64_GT)
    @JvmStatic
    fun F64_GT(a: Double, b: Double): Int = OpcodeOps.F64_GT(a, b)

    @OpCodeIdentifier(OpCode.F64_LE)
    @JvmStatic
    fun F64_LE(a: Double, b: Double): Int = OpcodeOps.F64_LE(a, b)

    @OpCodeIdentifier(OpCode.F64_LT)
    @JvmStatic
    fun F64_LT(a: Double, b: Double): Int = OpcodeOps.F64_LT(a, b)

    @OpCodeIdentifier(OpCode.F64_MAX)
    @JvmStatic
    fun F64_MAX(a: Double, b: Double): Double = OpcodeOps.F64_MAX(a, b)

    @OpCodeIdentifier(OpCode.F64_MIN)
    @JvmStatic
    fun F64_MIN(a: Double, b: Double): Double = OpcodeOps.F64_MIN(a, b)

    @OpCodeIdentifier(OpCode.F64_NE)
    @JvmStatic
    fun F64_NE(a: Double, b: Double): Int = OpcodeOps.F64_NE(a, b)

    @OpCodeIdentifier(OpCode.F64_NEAREST)
    @JvmStatic
    fun F64_NEAREST(x: Double): Double = OpcodeOps.F64_NEAREST(x)

    @OpCodeIdentifier(OpCode.F64_REINTERPRET_I64)
    @JvmStatic
    fun F64_REINTERPRET_I64(x: Long): Double = OpcodeOps.F64_REINTERPRET_I64(x)

    @OpCodeIdentifier(OpCode.F64_SQRT)
    @JvmStatic
    fun F64_SQRT(x: Double): Double = OpcodeOps.F64_SQRT(x)

    @OpCodeIdentifier(OpCode.F64_TRUNC)
    @JvmStatic
    fun F64_TRUNC(x: Double): Double = OpcodeOps.F64_TRUNC(x)

    @JvmStatic
    fun TABLE_GET(instance: Instance, tableIndex: Int, index: Int): Int = OpcodeOps.TABLE_GET(instance, tableIndex, index)

    @JvmStatic
    fun TABLE_FILL(instance: Instance, tableIndex: Int, size: Int, value: Int, offset: Int) = OpcodeOps.TABLE_FILL(instance, tableIndex, size, value, offset)

    @JvmStatic
    fun TABLE_COPY(
        instance: Instance,
        srcTableIndex: Int,
        dstTableIndex: Int,
        size: Int,
        s: Int,
        d: Int,
    ) = OpcodeOps.TABLE_COPY(instance, srcTableIndex, dstTableIndex, size, s, d)

    @JvmStatic
    fun TABLE_INIT(
        instance: Instance,
        tableidx: Int,
        elementidx: Int,
        size: Int,
        elemidx: Int,
        offset: Int,
    ) = OpcodeOps.TABLE_INIT(instance, tableidx, elementidx, size, elemidx, offset)

    @JvmStatic
    fun boxForTable(stackValue: Long, instance: Instance): Int = OpcodeOps.boxForTable(stackValue, instance)

    @JvmStatic
    fun unboxFromTable(tableValue: Int, instance: Instance, elementType: ValType): Long = OpcodeOps.unboxFromTable(tableValue, instance, elementType)

    @OpCodeIdentifier(OpCode.ATOMIC_FENCE)
    @JvmStatic
    fun ATOMIC_FENCE() = OpcodeOps.ATOMIC_FENCE()
}
