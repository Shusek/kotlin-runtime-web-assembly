package uk.shusek.krwa.component

interface WitStream<out T> {
    val handle: Long

    fun handle(): Long = handle

    companion object {
        @ComponentModelJvmStatic fun <T> of(handle: Long): WitStream<T> = Handle(handle)
    }

    data class Handle<out T>(override val handle: Long) : WitStream<T> {
        override fun toString(): String = "WitStream(${handle.toULong()})"
    }
}
