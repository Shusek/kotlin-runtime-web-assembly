package uk.shusek.krwa.component

sealed interface WitResult<out O, out E> {
    companion object {
        @ComponentModelJvmStatic fun <O> ok(value: O): Ok<O, Nothing> = Ok(value)

        @ComponentModelJvmStatic fun <E> err(value: E): Err<Nothing, E> = Err(value)
    }

    data class Ok<out O, out E>(val value: O) : WitResult<O, E> {
        fun value(): O = value

        override fun toString(): String = "Ok($value)"
    }

    data class Err<out O, out E>(val value: E) : WitResult<O, E> {
        fun value(): E = value

        override fun toString(): String = "Err($value)"
    }
}
