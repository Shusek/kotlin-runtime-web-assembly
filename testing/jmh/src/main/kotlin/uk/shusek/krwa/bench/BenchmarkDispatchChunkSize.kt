package uk.shusek.krwa.bench

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
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasm.Parser

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
open class BenchmarkDispatchChunkSize {
    @Param("0", "999", "1999") @JvmField var targetFunc: Int = 0

    private lateinit var exportFunc: ExportFunction

    @Setup
    open fun setup() {
        val wat = buildString {
            append("(module\n")
            for (i in 0 until NUM_FUNCTIONS) {
                append("  (func \$f").append(i)
                append(" (export \"f").append(i).append("\")")
                append(" (param i32) (result i32)\n")
                append("    local.get 0\n")
                append("    i32.const ").append(i + 1).append("\n")
                append("    i32.add)\n")
            }
            append(")\n")
        }

        val wasm = Wat2Wasm.parse(wat)
        val instance =
            Instance.builder(Parser.parse(wasm))
                .withMachineFactory { MachineFactoryCompiler.compile(it) }
                .build()
        exportFunc = instance.export("f$targetFunc")
    }

    @Benchmark
    open fun dispatch(bh: Blackhole) {
        bh.consume(exportFunc.apply(42L))
    }

    private companion object {
        const val NUM_FUNCTIONS: Int = 2000
    }
}
