package uk.shusek.krwa.experimental.aot

import uk.shusek.krwa.compiler.internal.MachineFactory
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmEngineException

/**
 * Machine implementation that compiles WASM function bodies to JVM byte code. All compilation is
 * done in a single compile phase during instantiation.
 *
 * This class is deprecated and will be removed in a future version. Please use the
 * [uk.shusek.krwa.compiler.MachineFactoryCompiler] instead.
 */
@Deprecated("use uk.shusek.krwa.compiler.MachineFactoryCompiler instead")
class AotMachine
@Deprecated("use uk.shusek.krwa.compiler.MachineFactoryCompiler.compile(instance) instead")
constructor(instance: Instance) : Machine {
    private val machine: Machine = MachineFactory(instance.module())(instance)

    @Throws(WasmEngineException::class)
    override fun call(funcId: Int, args: LongArray): LongArray = machine.call(funcId, args)
}
