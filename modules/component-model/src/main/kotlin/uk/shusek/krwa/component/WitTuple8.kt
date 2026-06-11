package uk.shusek.krwa.component

data class WitTuple8<out A, out B, out C, out D, out E, out F, out G, out H>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
    val seventh: G,
    val eighth: H,
) {
    fun first(): A = first

    fun second(): B = second

    fun third(): C = third

    fun fourth(): D = fourth

    fun fifth(): E = fifth

    fun sixth(): F = sixth

    fun seventh(): G = seventh

    fun eighth(): H = eighth

    override fun toString(): String =
        "($first, $second, $third, $fourth, $fifth, $sixth, $seventh, $eighth)"

    override fun hashCode(): Int =
        listOf(first, second, third, fourth, fifth, sixth, seventh, eighth).hashCode()
}
