package uk.shusek.krwa.bench

import java.nio.file.Files
import java.nio.file.Paths
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
import uk.shusek.krwa.wasm.Parser

@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
open class BenchmarkParsing {
    @Param(
        "testing/wasm-corpus/src/main/resources/compiled/basic.c.wasm",
        "testing/wasm-corpus/src/main/resources/compiled/javy-demo.js.javy.wasm",
    )
    @JvmField
    var fileName: String = ""

    private lateinit var memoryMappedFile: ByteArray

    @Setup
    open fun setup() {
        memoryMappedFile = Files.readAllBytes(Paths.get(fileName))
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    open fun benchmark(bh: Blackhole) {
        bh.consume(Parser.parse(memoryMappedFile))
    }
}
