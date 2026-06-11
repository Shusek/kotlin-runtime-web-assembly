package uk.shusek.krwa.component

internal enum class HostCallMode {
    SYNC,
    ASYNC_FUNCTION,
    ASYNC_LOWER,
}

internal data class HostCallContext(val mode: HostCallMode) {
    val isAsync: Boolean
        get() = mode != HostCallMode.SYNC

    companion object {
        val SYNC = HostCallContext(HostCallMode.SYNC)
        val ASYNC_FUNCTION = HostCallContext(HostCallMode.ASYNC_FUNCTION)
        val ASYNC_LOWER = HostCallContext(HostCallMode.ASYNC_LOWER)
    }
}

internal interface ContextualHostHandler : HostHandler {
    fun apply(
        arguments: List<@ComponentModelJvmSuppressWildcards Any?>,
        context: HostCallContext,
    ): Any?

    override fun apply(arguments: List<@ComponentModelJvmSuppressWildcards Any?>): Any? =
        apply(arguments, HostCallContext.SYNC)
}

internal data class DirectHostCallResult(
    val value: Any?,
    val resultsStored: Boolean = false,
)

internal interface DirectHostHandler : HostHandler {
    fun applyDirect(
        context: CanonicalAbi.Context,
        function: WitPackage.Function,
        flatArguments: LongArray,
        resultPointer: Int?,
        callContext: HostCallContext,
    ): DirectHostCallResult?
}

internal fun applyHostHandler(
    handler: HostHandler,
    arguments: List<Any?>,
    context: HostCallContext,
): Any? =
    if (handler is ContextualHostHandler) {
        handler.apply(arguments, context)
    } else {
        handler.apply(arguments)
    }
