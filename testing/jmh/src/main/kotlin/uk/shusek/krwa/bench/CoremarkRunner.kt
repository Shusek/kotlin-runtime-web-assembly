package uk.shusek.krwa.bench

import java.util.Locale
import kotlin.math.max

fun main(args: Array<String>) {
    val backends = selectedBackends(args)
    val warmups = intProperty("krwa.coremark.warmups", 1).coerceAtLeast(0)
    val repetitions = intProperty("krwa.coremark.repetitions", 3).coerceAtLeast(1)
    val module = ChasmCoremark.loadModule()

    println("Benchmark: Chasm coremark.wasm on KRWA")
    println("Warmups: $warmups, repetitions: $repetitions")

    for (backend in backends) {
        repeat(warmups) {
            ChasmCoremark.run(module, backend)
        }

        val results = ArrayList<CoremarkResult>(repetitions)
        repeat(repetitions) {
            results.add(ChasmCoremark.run(module, backend))
        }

        printSummary(backend, results)
    }
}

private fun selectedBackends(args: Array<String>): List<CoremarkBackend> {
    val raw =
        when {
            args.isNotEmpty() -> args.joinToString(",")
            else -> System.getProperty("krwa.coremark.backends", "interpreter,compiled")
        }
    return raw
        .split(',')
        .mapNotNull { value ->
            when (value.trim().lowercase(Locale.ROOT)) {
                "interpreter", "interpreted", "int" -> CoremarkBackend.INTERPRETER
                "experimental", "experimental_fast", "experimental-fast", "fast" ->
                    CoremarkBackend.EXPERIMENTAL_FAST
                "compiled", "compiler", "jit" -> CoremarkBackend.COMPILED
                "" -> null
                else -> error("Unknown backend: $value")
            }
        }
        .ifEmpty { listOf(CoremarkBackend.INTERPRETER, CoremarkBackend.COMPILED) }
}

private fun intProperty(name: String, defaultValue: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: defaultValue

private fun printSummary(backend: CoremarkBackend, results: List<CoremarkResult>) {
    val scores = results.map { it.score.toDouble() }
    val millis = results.map { it.elapsedNanos / 1_000_000.0 }
    val sortedScores = scores.sorted()
    val sortedMillis = millis.sorted()
    val bestScore = sortedScores.last()
    val averageScore = scores.average()
    val averageMillis = millis.average()
    val p50Millis = sortedMillis[sortedMillis.size / 2]
    val minMillis = sortedMillis.first()
    val maxMillis = sortedMillis.last()

    println(
        String.format(
            Locale.US,
            "%s score_avg=%.6f score_best=%.6f ms_avg=%.3f ms_min=%.3f ms_p50=%.3f ms_max=%.3f",
            backend.name.lowercase(Locale.ROOT),
            averageScore,
            bestScore,
            averageMillis,
            minMillis,
            p50Millis,
            max(maxMillis, minMillis),
        )
    )
}
