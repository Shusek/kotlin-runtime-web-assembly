package uk.shusek.krwa.wasm.types

object ValueJvm {
    @JvmStatic fun floatToLong(data: Float): Long = Value.floatToLong(data)

    @JvmStatic fun longToFloat(data: Long): Float = Value.longToFloat(data)

    @JvmStatic fun doubleToLong(data: Double): Long = Value.doubleToLong(data)

    @JvmStatic fun longToDouble(data: Long): Double = Value.longToDouble(data)
}
