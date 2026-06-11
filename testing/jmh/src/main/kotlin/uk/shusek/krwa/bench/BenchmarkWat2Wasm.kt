package uk.shusek.krwa.bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import uk.shusek.krwa.corpus.WatGenerator
import uk.shusek.krwa.wabt.Wat2Wasm

@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
open class BenchmarkWat2Wasm {
    private lateinit var wat: String

    @Setup
    open fun setup() {
        wat = WatGenerator.bigWat(1000, 10)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(8)
    open fun benchmarkAot(bh: Blackhole) {
        bh.consume(Wat2Wasm.parse(wat))
    }
}
