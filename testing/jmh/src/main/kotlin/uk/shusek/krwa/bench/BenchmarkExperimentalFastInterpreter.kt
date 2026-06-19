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
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.withExperimentalFastInterpreter
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.WasmParser

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
open class BenchmarkExperimentalFastInterpreter {
    @Param("STANDARD", "EXPERIMENTAL_FAST", "COMPILED") @JvmField var backendName: String = ""

    private lateinit var module: WasmModule
    private lateinit var run: ExportFunction

    @Setup
    open fun setup() {
        module = WasmParser.parse(COUNTDOWN_LOOP_WASM)
        val builder = Instance.builder(module)
        when (backendName) {
            "STANDARD" -> Unit
            "EXPERIMENTAL_FAST" -> builder.withExperimentalFastInterpreter()
            "COMPILED" -> builder.withMachineFactory { MachineFactoryCompiler.compile(it) }
            else -> error("Unknown backend: $backendName")
        }
        run = builder.build().export("run")
    }

    @Benchmark
    open fun countdownLoop(): Int =
        run.apply(LOOP_COUNT)[0].toInt()

    private companion object {
        private const val LOOP_COUNT = 10_000L

        private val COUNTDOWN_LOOP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06,
                0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x03, 0x02,
                0x01, 0x00,
                0x05, 0x03,
                0x01, 0x00, 0x01,
                0x07, 0x07,
                0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
                0x0A, 0x1F,
                0x01,
                0x1D, 0x01, 0x01, 0x7F,
                0x41, 0x00,
                0x21, 0x01,
                0x03, 0x40,
                0x20, 0x01,
                0x20, 0x00,
                0x6A,
                0x21, 0x01,
                0x20, 0x00,
                0x41, 0x01,
                0x6B,
                0x22, 0x00,
                0x0D, 0x00,
                0x0B,
                0x20, 0x01,
                0x0B,
            )
    }
}
