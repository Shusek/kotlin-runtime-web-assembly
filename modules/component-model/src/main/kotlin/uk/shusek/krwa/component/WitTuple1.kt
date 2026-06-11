package uk.shusek.krwa.component

data class WitTuple1<out A>(val first: A) {
    fun first(): A = first

    override fun toString(): String = "($first)"

    override fun hashCode(): Int = first?.hashCode() ?: 0
}
