package uk.shusek.krwa.fuzz

import java.io.File
import java.util.function.Function
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.WasmInterruptedException
import uk.shusek.krwa.wasm.Parser

class DefaultRunner(private val machineFactory: Function<Instance, Machine>?) : WasmRunner {
    constructor() : this(null)

    @Throws(Exception::class)
    override fun run(wasmFile: File, functionName: String, params: List<String>): String {
        if (Thread.currentThread().isInterrupted) {
            throw WasmInterruptedException("Thread interrupted")
        }
        val module = Parser.parse(wasmFile)
        val builder = Instance.builder(module).withInitialize(true).withStart(false)
        if (machineFactory != null) {
            builder.withMachineFactory { instance -> machineFactory.apply(instance) }
        }
        val instance = builder.build()

        val type = instance.exportType(functionName)
        val export = instance.export(functionName)
        val longParams = LongArray(type.params().size)
        for (i in 0..<type.params().size) {
            longParams[i] = params[i].toLong()
        }

        val result = export.apply(*longParams)
        val sb = StringBuilder()
        if (result != null) {
            for (r in result) {
                sb.append(r).append("\n")
            }
        }
        return sb.toString()
    }
}
