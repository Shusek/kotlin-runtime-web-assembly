package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

fun interface Machine {
    @Throws(WasmEngineException::class) fun call(funcId: Int, args: LongArray): LongArray
}

interface ResumableMachine : Machine {
    @Throws(WasmEngineException::class) fun resume(continuation: WasmContinuation): LongArray
}
