package uk.shusek.krwa.bench

import java.io.File
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
open class BenchmarkFactorialExecution {
    @Param("5", "1000") @JvmField var input: Int = 0

    private lateinit var iterFactInt: ExportFunction
    private lateinit var iterFactCompiled: ExportFunction

    @Setup
    open fun setup() {
        val factorialInt = Instance.builder(Parser.parse(ITERFACT)).build()
        iterFactInt = factorialInt.export("iterFact")

        val factorialCompiled =
            Instance.builder(Parser.parse(ITERFACT))
                .withMachineFactory { MachineFactoryCompiler.compile(it) }
                .build()
        iterFactCompiled = factorialCompiled.export("iterFact")
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    open fun benchmarkInt(bh: Blackhole) {
        bh.consume(iterFactInt.apply(input.toLong()))
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    open fun benchmarkCompiled(bh: Blackhole) {
        bh.consume(iterFactCompiled.apply(input.toLong()))
    }

    private companion object {
        val ITERFACT: File =
            File("testing/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm")
    }
}
