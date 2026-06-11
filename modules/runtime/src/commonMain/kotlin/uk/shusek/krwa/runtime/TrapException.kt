package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmEngineException

/** Wasm spec: runtime trap during execution (unreachable, OOB access, null ref, etc.). */
open class TrapException(msg: String) : WasmEngineException(msg)
