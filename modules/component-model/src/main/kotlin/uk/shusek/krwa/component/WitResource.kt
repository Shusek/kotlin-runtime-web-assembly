package uk.shusek.krwa.component

@Suppress("UnusedTypeParameter")
data class WitResource<out T>(val handle: Long) {
    fun handle(): Long = handle

    override fun toString(): String = "WitResource(${handle.toULong()})"
}
