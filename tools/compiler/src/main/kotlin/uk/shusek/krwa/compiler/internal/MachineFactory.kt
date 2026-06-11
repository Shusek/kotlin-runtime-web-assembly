package uk.shusek.krwa.compiler.internal

import uk.shusek.krwa.runtime.CompiledModule
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmModule

/**
 * Machine factory implementation that AOT compiles function bodies. All compilation is done in a
 * single compile phase during instantiation and is reused for all created machine instances.
 */
class MachineFactory : (Instance) -> Machine, CompiledModule {
    private val module: WasmModule
    private val factory: (Instance) -> Machine

    constructor(module: WasmModule) {
        this.module = module
        val compiler =
            Compiler.builder(module).withClassCollectorFactory { ClassLoadingCollector() }.build()
        val result = compiler.compile()
        val collector = result.collector() as ClassLoadingCollector
        factory = collector.machineFactory()
    }

    constructor(module: WasmModule, factory: (Instance) -> Machine) {
        this.module = module
        this.factory = factory
    }

    override fun invoke(instance: Instance): Machine {
        if (instance.module() !== module) {
            throw IllegalArgumentException("Instance module does not match factory module")
        }
        return factory(instance)
    }

    override fun wasmModule(): WasmModule = module

    override fun machineFactory(): (Instance) -> Machine = factory
}
