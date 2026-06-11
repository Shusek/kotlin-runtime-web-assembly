package uk.shusek.krwa.component

data class WitTuple7<out A, out B, out C, out D, out E, out F, out G>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
    val seventh: G,
) {
    fun first(): A = first

    fun second(): B = second

    fun third(): C = third

    fun fourth(): D = fourth

    fun fifth(): E = fifth

    fun sixth(): F = sixth

    fun seventh(): G = seventh

    override fun toString(): String = "($first, $second, $third, $fourth, $fifth, $sixth, $seventh)"

    override fun hashCode(): Int =
        listOf(first, second, third, fourth, fifth, sixth, seventh).hashCode()
}
