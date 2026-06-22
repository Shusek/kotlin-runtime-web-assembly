package uk.shusek.krwa.bench

import io.github.charlietap.chasm.embedding.dsl.imports as directChasmImports
import io.github.charlietap.chasm.embedding.invoke as directChasmInvoke
import io.github.charlietap.chasm.embedding.instance as directChasmInstance
import io.github.charlietap.chasm.embedding.module as directChasmModule
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.store as directChasmStore
import io.github.charlietap.chasm.runtime.value.NumberValue
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.ExecutionListener
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.withExperimentalFastInterpreter
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

enum class CoremarkBackend {
    INTERPRETER,
    CHASM_INTERPRETER,
    CHASM_DIRECT,
    SLOT_PLAN_PROBE,
    EXPERIMENTAL_FAST,
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
        if (backend == CoremarkBackend.CHASM_DIRECT) {
            return runDirectChasm(module.originalBytes() ?: loadModuleBytes())
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
        require(backend != CoremarkBackend.CHASM_INTERPRETER && backend != CoremarkBackend.CHASM_DIRECT) {
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
        val clockStart = System.nanoTime()
        val clockResult = LongArray(1)
        val clock =
            HostFunction(
                "env",
                "clock_ms",
                FunctionType.returning(ValType.I64),
            ) { _, _ ->
                clockResult[0] = (System.nanoTime() - clockStart) / 1_000_000L
                clockResult
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
            CoremarkBackend.CHASM_INTERPRETER -> builder.withExecutionBackend(ExecutionBackend.CHASM)
            CoremarkBackend.CHASM_DIRECT -> error("direct Chasm backend does not use KRWA Instance")
            CoremarkBackend.SLOT_PLAN_PROBE -> builder.withMachineFactory(::SlotPlanProbeMachine)
            CoremarkBackend.EXPERIMENTAL_FAST -> builder.withExperimentalFastInterpreter()
            CoremarkBackend.COMPILED_COLD -> builder.withMachineFactory { MachineFactoryCompiler.compile(it) }
            CoremarkBackend.COMPILED -> builder.withMachineFactory(compiledFactoryFor(module))
        }

        return builder.build()
    }

    private fun runDirectChasm(bytes: ByteArray): CoremarkResult {
        val start = System.nanoTime()
        val clockStart = System.nanoTime()
        val store = directChasmStore()
        val imports =
            directChasmImports(store) {
                function {
                    moduleName = "env"
                    entityName = "clock_ms"
                    type {
                        results { i64() }
                    }
                    reference {
                        listOf(NumberValue.I64((System.nanoTime() - clockStart) / 1_000_000L))
                    }
                }
            }

        val decodedModule = directChasmModule(bytes).orThrow("decode direct Chasm module")
        val instance = directChasmInstance(store, decodedModule, imports).orThrow("instantiate direct Chasm module")
        val result = directChasmInvoke(store, instance, "run").orThrow("invoke direct Chasm run")
        val elapsedNanos = System.nanoTime() - start
        val score =
            (result.firstOrNull() as? NumberValue.F32)?.value
                ?: error("direct Chasm run returned unexpected result: $result")
        return CoremarkResult(score, elapsedNanos)
    }

    private fun <S> ChasmResult<S, *>.orThrow(action: String): S =
        when (this) {
            is ChasmResult.Success -> result
            is ChasmResult.Error -> throw IllegalStateException("Chasm $action failed: ${this.error}")
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
