package uk.shusek.krwa.simd

import jdk.incubator.vector.VectorOperators as JdkVectorOperators

internal object VectorOperators {
    @JvmField val NE: JdkVectorOperators.Comparison = JdkVectorOperators.NE
    @JvmField val LSHL: JdkVectorOperators.Binary = JdkVectorOperators.LSHL
    @JvmField val LSHR: JdkVectorOperators.Binary = JdkVectorOperators.LSHR
    @JvmField val ASHR: JdkVectorOperators.Binary = JdkVectorOperators.ASHR
    @JvmField val UNSIGNED_LT: JdkVectorOperators.Comparison = comparison("UNSIGNED_LT", "ULT")
    @JvmField val LE: JdkVectorOperators.Comparison = JdkVectorOperators.LE
    @JvmField val UNSIGNED_LE: JdkVectorOperators.Comparison = comparison("UNSIGNED_LE", "ULE")
    @JvmField val GT: JdkVectorOperators.Comparison = JdkVectorOperators.GT
    @JvmField val UNSIGNED_GT: JdkVectorOperators.Comparison = comparison("UNSIGNED_GT", "UGT")
    @JvmField val GE: JdkVectorOperators.Comparison = JdkVectorOperators.GE
    @JvmField val UNSIGNED_GE: JdkVectorOperators.Comparison = comparison("UNSIGNED_GE", "UGE")
    @JvmField val BIT_COUNT: JdkVectorOperators.Unary = JdkVectorOperators.BIT_COUNT
    @JvmField val LT: JdkVectorOperators.Comparison = JdkVectorOperators.LT
    @JvmField val SQRT: JdkVectorOperators.Unary = JdkVectorOperators.SQRT
    @JvmField val ABS: JdkVectorOperators.Unary = JdkVectorOperators.ABS
    @JvmField val NEG: JdkVectorOperators.Unary = JdkVectorOperators.NEG

    private fun comparison(
        preferredName: String,
        fallbackName: String,
    ): JdkVectorOperators.Comparison = field(preferredName, fallbackName)

    private fun <T> field(preferredName: String, fallbackName: String): T {
        val operators = Class.forName("jdk.incubator.vector.VectorOperators")
        return try {
            @Suppress("UNCHECKED_CAST")
            operators.getField(preferredName).get(null) as T
        } catch (_: ReflectiveOperationException) {
            @Suppress("UNCHECKED_CAST")
            operators.getField(fallbackName).get(null) as T
        }
    }
}
