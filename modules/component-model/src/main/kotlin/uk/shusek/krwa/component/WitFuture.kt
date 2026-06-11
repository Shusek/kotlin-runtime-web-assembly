package uk.shusek.krwa.component

interface WitFuture<out T> {
    val handle: Long

    fun handle(): Long = handle

    companion object {
        @ComponentModelJvmStatic fun <T> of(handle: Long): WitFuture<T> = Handle(handle)
    }

    data class Handle<out T>(override val handle: Long) : WitFuture<T> {
        override fun toString(): String = "WitFuture(${handle.toULong()})"
    }
}
