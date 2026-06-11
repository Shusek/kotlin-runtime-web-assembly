package uk.shusek.krwa.runtime

class WasmContinuation internal constructor(
    internal val stackValues: LongArray,
    internal val callStackFrames: List<StackFrame>,
    internal val returnSlotCount: Int,
)

class WasmExecutionSuspended(
    val resumeResults: LongArray = LongArray(0),
) : RuntimeException("Wasm execution suspended") {
    var continuation: WasmContinuation? = null
        internal set
}
