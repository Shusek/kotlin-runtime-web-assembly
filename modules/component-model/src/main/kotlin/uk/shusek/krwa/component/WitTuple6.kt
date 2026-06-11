package uk.shusek.krwa.component

data class WitTuple6<out A, out B, out C, out D, out E, out F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
) {
    fun first(): A = first

    fun second(): B = second

    fun third(): C = third

    fun fourth(): D = fourth

    fun fifth(): E = fifth

    fun sixth(): F = sixth

    override fun toString(): String = "($first, $second, $third, $fourth, $fifth, $sixth)"

    override fun hashCode(): Int = listOf(first, second, third, fourth, fifth, sixth).hashCode()
}
