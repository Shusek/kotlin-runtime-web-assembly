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
import uk.shusek.krwa.wasm.WasmModule

@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
open class BenchmarkChasmCoremarkExecution {
    @Param("INTERPRETER", "CHASM_INTERPRETER", "COMPILED") @JvmField var backendName: String = ""

    private lateinit var module: WasmModule
    private lateinit var backend: CoremarkBackend

    @Setup
    open fun setup() {
        module = ChasmCoremark.loadModule()
        backend = CoremarkBackend.valueOf(backendName)
    }

    @Benchmark
    open fun coremark(bh: Blackhole) {
        bh.consume(ChasmCoremark.run(module, backend))
    }
}
