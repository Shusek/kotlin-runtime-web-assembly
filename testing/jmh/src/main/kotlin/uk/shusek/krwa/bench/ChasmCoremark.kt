package uk.shusek.krwa.bench

import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.withExperimentalFastInterpreter
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

enum class CoremarkBackend {
    INTERPRETER,
    EXPERIMENTAL_FAST,
    COMPILED,
}

data class CoremarkResult(
    val score: Float,
    val elapsedNanos: Long,
)

object ChasmCoremark {
    fun loadModule(): WasmModule {
        val bytes =
            requireNotNull(javaClass.getResourceAsStream(RESOURCE)) {
                    "Missing benchmark resource: $RESOURCE"
                }
                .use { it.readBytes() }
        return Parser.parse(bytes)
    }

    fun run(module: WasmModule, backend: CoremarkBackend): CoremarkResult {
        val start = System.nanoTime()
        val instance = newInstance(module, backend)
        val scoreBits = instance.export("run").apply()[0]
        val elapsedNanos = System.nanoTime() - start
        return CoremarkResult(Float.fromBits(scoreBits.toInt()), elapsedNanos)
    }

    private fun newInstance(module: WasmModule, backend: CoremarkBackend): Instance {
        val clock =
            HostFunction(
                "env",
                "clock_ms",
                FunctionType.returning(ValType.I64),
            ) { _, _ ->
                longArrayOf(System.currentTimeMillis())
            }

        val builder =
            Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(clock).build())

        when (System.getProperty("krwa.coremark.memory", "default").lowercase()) {
            "default" -> {}
            "bytearray" -> builder.withMemoryFactory(::ByteArrayMemory)
            "bytebuffer" -> builder.withMemoryFactory(::ByteBufferMemory)
            else -> error("Unsupported krwa.coremark.memory")
        }

        when (backend) {
            CoremarkBackend.INTERPRETER -> {}
            CoremarkBackend.EXPERIMENTAL_FAST -> builder.withExperimentalFastInterpreter()
            CoremarkBackend.COMPILED -> builder.withMachineFactory { MachineFactoryCompiler.compile(it) }
        }

        return builder.build()
    }

    private const val RESOURCE = "/benchmark/chasm-coremark.wasm"
}
