package uk.shusek.krwa.component

fun interface HostHandler {
    fun apply(arguments: List<@ComponentModelJvmSuppressWildcards Any?>): Any?
}
