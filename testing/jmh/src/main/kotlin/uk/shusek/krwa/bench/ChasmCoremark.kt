package uk.shusek.krwa.bench

import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.ExecutionListener
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

enum class CoremarkBackend {
    INTERPRETER,
    CHASM_INTERPRETER,
    COMPILED_COLD,
    COMPILED,
}

data class CoremarkResult(
    val score: Float,
    val elapsedNanos: Long,
)

object ChasmCoremark {
    fun loadModuleBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(RESOURCE)) {
                "Missing benchmark resource: $RESOURCE"
            }
            .use { it.readBytes() }

    fun loadModule(): WasmModule {
        return Parser.parse(loadModuleBytes())
    }

    fun run(module: WasmModule, backend: CoremarkBackend): CoremarkResult {
        if (backend == CoremarkBackend.CHASM_INTERPRETER) {
            return ChasmInterpreterCoremark.run(loadModuleBytes())
        }
        val start = System.nanoTime()
        val instance = newInstance(module, backend)
        val scoreBits = instance.export("run").apply()[0]
        val elapsedNanos = System.nanoTime() - start
        return CoremarkResult(Float.fromBits(scoreBits.toInt()), elapsedNanos)
    }

    fun runProfiled(
        module: WasmModule,
        backend: CoremarkBackend,
        listener: ExecutionListener,
    ): CoremarkResult {
        require(backend != CoremarkBackend.CHASM_INTERPRETER) {
            "Chasm interpreter backend cannot be profiled by KRWA ExecutionListener"
        }
        val start = System.nanoTime()
        val instance = newInstance(module, backend, listener)
        val scoreBits = instance.export("run").apply()[0]
        val elapsedNanos = System.nanoTime() - start
        return CoremarkResult(Float.fromBits(scoreBits.toInt()), elapsedNanos)
    }

    private fun newInstance(
        module: WasmModule,
        backend: CoremarkBackend,
        listener: ExecutionListener? = null,
    ): Instance {
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

        if (listener != null) {
            builder.withUnsafeExecutionListener(listener)
        }

        when (System.getProperty("krwa.coremark.memory", "default").lowercase()) {
            "default" -> {}
            "bytearray" -> builder.withMemoryFactory(::ByteArrayMemory)
            "bytebuffer" -> builder.withMemoryFactory(::ByteBufferMemory)
            else -> error("Unsupported krwa.coremark.memory")
        }

        when (backend) {
            CoremarkBackend.INTERPRETER ->
                builder.withMachineFactory { instance ->
                    object : InterpreterMachine(instance) {
                        override fun isInterrupted(): Boolean = Thread.currentThread().isInterrupted
                    }
                }
            CoremarkBackend.CHASM_INTERPRETER ->
                error("Chasm interpreter backend does not use KRWA Instance")
            CoremarkBackend.COMPILED_COLD -> builder.withMachineFactory { MachineFactoryCompiler.compile(it) }
            CoremarkBackend.COMPILED -> builder.withMachineFactory(compiledFactoryFor(module))
        }

        return builder.build()
    }

    private fun compiledFactoryFor(module: WasmModule): (Instance) -> Machine {
        val factory = compiledFactory
        if (factory != null && compiledModule === module) {
            return factory
        }

        return MachineFactoryCompiler.compile(module).also {
            compiledModule = module
            compiledFactory = it
        }
    }

    private var compiledModule: WasmModule? = null
    private var compiledFactory: ((Instance) -> Machine)? = null

    private const val RESOURCE = "/benchmark/chasm-coremark.wasm"
}
