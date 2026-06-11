package uk.shusek.krwa.component

data class WitTuple4<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
) {
    fun first(): A = first

    fun second(): B = second

    fun third(): C = third

    fun fourth(): D = fourth

    override fun toString(): String = "($first, $second, $third, $fourth)"

    override fun hashCode(): Int = listOf(first, second, third, fourth).hashCode()
}
