package uk.shusek.krwa.component

data class WitTuple5<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
) {
    fun first(): A = first

    fun second(): B = second

    fun third(): C = third

    fun fourth(): D = fourth

    fun fifth(): E = fifth

    override fun toString(): String = "($first, $second, $third, $fourth, $fifth)"

    override fun hashCode(): Int = listOf(first, second, third, fourth, fifth).hashCode()
}
