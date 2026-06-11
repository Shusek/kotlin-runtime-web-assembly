package uk.shusek.krwa.runtime.smoke

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.WasmParser

object IosRuntimeSmoke {
    fun builderFor(bytes: ByteArray): Instance.Builder =
        Instance.builder(WasmParser.parse(bytes))
            .withInitialize(false)
            .withStart(false)
}
