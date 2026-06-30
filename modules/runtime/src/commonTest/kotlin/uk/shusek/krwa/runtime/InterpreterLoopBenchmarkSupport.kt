package uk.shusek.krwa.runtime

import kotlin.test.assertEquals
import kotlin.time.TimeSource
import uk.shusek.krwa.wasm.WasmParser

internal object InterpreterLoopBenchmarkSupport {
    fun runAll(iterations: Int, repetitions: Int, warmupRepetitions: Int): List<InterpreterLoopBenchmarkResult> =
        InterpreterLoopBenchmarkBackend.entries.map { backend ->
            run(iterations, repetitions, warmupRepetitions, backend)
        }

    fun run(iterations: Int, repetitions: Int, warmupRepetitions: Int): InterpreterLoopBenchmarkResult {
        return run(iterations, repetitions, warmupRepetitions, InterpreterLoopBenchmarkBackend.STANDARD)
    }

    fun run(
        iterations: Int,
        repetitions: Int,
        warmupRepetitions: Int,
        backend: InterpreterLoopBenchmarkBackend,
    ): InterpreterLoopBenchmarkResult {
        val module = WasmParser.parse(LOOP_WASM)
        val instance = Instance.builder(module).build()
        val run = instance.export("run")
        val expected = triangularI32(iterations)

        repeat(warmupRepetitions) {
            assertEquals(expected, run.apply(iterations.toLong())[0].toInt())
        }

        var checksum = 0L
        val mark = TimeSource.Monotonic.markNow()
        repeat(repetitions) {
            val result = run.apply(iterations.toLong())[0].toInt()
            assertEquals(expected, result)
            checksum += result.toLong()
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        return InterpreterLoopBenchmarkResult(
            backend = backend,
            iterations = iterations,
            repetitions = repetitions,
            elapsedNs = elapsedNs,
            checksum = checksum,
        )
    }

    private fun triangularI32(n: Int): Int =
        (n.toLong() * (n.toLong() + 1L) / 2L).toInt()

    private val LOOP_WASM =
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

internal enum class InterpreterLoopBenchmarkBackend(val label: String) {
    STANDARD("standard"),
}

internal data class InterpreterLoopBenchmarkResult(
    val backend: InterpreterLoopBenchmarkBackend,
    val iterations: Int,
    val repetitions: Int,
    val elapsedNs: Long,
    val checksum: Long,
) {
    fun formatted(platform: String): String {
        val totalIterations = iterations.toLong() * repetitions.toLong()
        val elapsedMs = elapsedNs / 1_000_000
        val nsPerIteration = if (totalIterations == 0L) 0L else elapsedNs / totalIterations
        return "KRWA interpreter loop benchmark: platform=$platform, " +
            "backend=${backend.label}, iterations=$iterations, repetitions=$repetitions, " +
            "elapsedMs=$elapsedMs, nsPerIteration=$nsPerIteration, checksum=$checksum"
    }
}
