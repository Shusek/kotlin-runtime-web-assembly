package uk.shusek.krwa.wabt

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmModule

internal class WabtRuntime(moduleClassName: String) {
    private val moduleClass: Class<*> by lazy { Class.forName(moduleClassName) }

    val module: WasmModule by lazy { moduleClass.getMethod("load").invoke(null) as WasmModule }

    fun create(instance: Instance): Machine =
        moduleClass.getMethod("create", Instance::class.java).invoke(null, instance) as Machine
}
