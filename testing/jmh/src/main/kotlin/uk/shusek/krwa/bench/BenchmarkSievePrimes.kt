package uk.shusek.krwa.bench

import java.io.File
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.ImportMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
open class BenchmarkSievePrimes {
    @Param("4") @JvmField var numWorkers: Int = 0

    @Param("1000000") @JvmField var limit: Int = 0

    @Param("ByteArrayMemory", "ByteBufferMemory") @JvmField var memoryType: String = ""

    @Param("compiled") @JvmField var machineType: String = ""

    private lateinit var instance: Instance
    private lateinit var sieve: ExportFunction
    private lateinit var workers: MutableList<Thread>

    private fun newInstance(memory: Memory): Instance {
        val builder =
            Instance.builder(module)
                .withImportValues(
                    ImportValues.builder().addMemory(ImportMemory("env", "memory", memory)).build()
                )
        if (machineType == "compiled") {
            builder.withMachineFactory { MachineFactoryCompiler.compile(it) }
        }
        return builder.build()
    }

    private fun newThread(parent: Instance): Thread {
        val memory = parent.imports().memory(0).memory()!!
        val stackSize = parent.exports().global("__stack_pointer").value
        val tlsSize = parent.exports().global("__tls_size").value
        val tlsAlign = parent.exports().global("__tls_align").value

        val stackPtr = parent.export("__malloc").apply(stackSize, 16L)[0] + stackSize
        val child = newInstance(memory)
        child.exports().global("__stack_pointer").value = stackPtr
        val tlsPtr = child.export("__malloc").apply(tlsSize, tlsAlign)
        child.export("__wasm_init_tls").apply(*tlsPtr)
        return Thread { child.export("register_thread").apply() }
    }

    private fun createMemory(): Memory {
        val limits = MemoryLimits(20, 100, true)
        return if (memoryType == "ByteBufferMemory") ByteBufferMemory(limits)
        else ByteArrayMemory(limits)
    }

    @Setup(Level.Trial)
    open fun setup() {
        val memory = createMemory()
        instance = newInstance(memory)
        sieve = instance.export("parallel_sieve_bitset")
        workers = ArrayList()
        for (i in 0 until numWorkers) {
            val thread = newThread(instance)
            workers.add(thread)
            thread.start()
        }
    }

    @TearDown(Level.Trial)
    @Throws(InterruptedException::class)
    open fun tearDown() {
        instance.export("shutdown").apply()
        for (thread in workers) {
            thread.join()
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    open fun benchmark(bh: Blackhole) {
        bh.consume(sieve.apply(limit.toLong()))
    }

    private companion object {
        val SIEVE_PRIMES: File =
            File("testing/wasm-corpus/src/main/resources/compiled/sieve-primes.rs.wasm")
        val module: WasmModule = Parser.parse(SIEVE_PRIMES)
    }
}
