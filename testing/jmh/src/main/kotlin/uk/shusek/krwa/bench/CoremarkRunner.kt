package uk.shusek.krwa.bench

import java.util.Locale
import kotlin.math.max

fun main(args: Array<String>) {
    val backends = selectedBackends(args)
    val warmups = intProperty("krwa.coremark.warmups", 1).coerceAtLeast(0)
    val repetitions = intProperty("krwa.coremark.repetitions", 3).coerceAtLeast(1)
    val printRuns = booleanProperty("krwa.coremark.printRuns", false)
    val interleave = booleanProperty("krwa.coremark.interleave", false)
    val rotateInterleave = booleanProperty("krwa.coremark.rotateInterleave", false)
    val reference = referenceComparison()
    val module = ChasmCoremark.loadModule()

    println("Benchmark: Chasm coremark.wasm")
    println(
        "Warmups: $warmups, repetitions: $repetitions, interleave: $interleave, " +
            "rotateInterleave: $rotateInterleave"
    )

    val summaries =
        if (interleave) {
            runInterleaved(module, backends, warmups, repetitions, printRuns, rotateInterleave)
        } else {
            runSequential(module, backends, warmups, repetitions, printRuns)
        }

    if (reference != null) {
        printReferenceComparison(reference, summaries)
    }
}

private fun runSequential(
    module: uk.shusek.krwa.wasm.WasmModule,
    backends: List<CoremarkBackend>,
    warmups: Int,
    repetitions: Int,
    printRuns: Boolean,
): List<CoremarkSummary> {
    val summaries = ArrayList<CoremarkSummary>(backends.size)
    for (backend in backends) {
        repeat(warmups) { ChasmCoremark.run(module, backend) }

        val results = ArrayList<CoremarkResult>(repetitions)
        repeat(repetitions) { index ->
            results.add(runMeasured(module, backend, index, printRuns))
        }

        summaries.add(printSummary(backend, results))
    }
    return summaries
}

private fun runInterleaved(
    module: uk.shusek.krwa.wasm.WasmModule,
    backends: List<CoremarkBackend>,
    warmups: Int,
    repetitions: Int,
    printRuns: Boolean,
    rotateInterleave: Boolean,
): List<CoremarkSummary> {
    repeat(warmups) { index ->
        forInterleavedBackend(backends, index, rotateInterleave) { backend ->
            ChasmCoremark.run(module, backend)
        }
    }

    val results = linkedMapOf<CoremarkBackend, ArrayList<CoremarkResult>>()
    for (backend in backends) {
        results[backend] = ArrayList(repetitions)
    }

    repeat(repetitions) { index ->
        forInterleavedBackend(backends, index, rotateInterleave) { backend ->
            results.getValue(backend).add(runMeasured(module, backend, index, printRuns))
        }
    }

    val summaries = ArrayList<CoremarkSummary>(backends.size)
    for (backend in backends) {
        summaries.add(printSummary(backend, results.getValue(backend)))
    }
    return summaries
}

private inline fun forInterleavedBackend(
    backends: List<CoremarkBackend>,
    index: Int,
    rotate: Boolean,
    block: (CoremarkBackend) -> Unit,
) {
    val offset = if (rotate && backends.isNotEmpty()) index % backends.size else 0
    for (position in backends.indices) {
        block(backends[(offset + position) % backends.size])
    }
}

private fun runMeasured(
    module: uk.shusek.krwa.wasm.WasmModule,
    backend: CoremarkBackend,
    index: Int,
    printRuns: Boolean,
): CoremarkResult {
    val result = ChasmCoremark.run(module, backend)
    if (printRuns) {
        printRun(backend, index + 1, result)
    }
    return result
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
            parseBackend(value) ?: if (value.isBlank()) null else error("Unknown backend: $value")
        }
        .ifEmpty { listOf(CoremarkBackend.INTERPRETER, CoremarkBackend.COMPILED) }
}

private fun parseBackend(value: String): CoremarkBackend? =
    when (value.trim().lowercase(Locale.ROOT)) {
        "interpreter", "interpreted", "int" -> CoremarkBackend.INTERPRETER
        "chasm", "chasm_interpreter", "chasm-interpreter", "upstream_chasm", "upstream-chasm" ->
            CoremarkBackend.CHASM_INTERPRETER
        "chasm_direct", "chasm-direct", "direct_chasm", "direct-chasm", "upstream_chasm_direct",
        "upstream-chasm-direct" -> CoremarkBackend.CHASM_DIRECT
        "slot_plan_probe", "slot-plan-probe", "slot_plan", "slot-plan", "plan_probe", "plan-probe" ->
            CoremarkBackend.SLOT_PLAN_PROBE
        "experimental", "experimental_fast", "experimental-fast", "fast" ->
            CoremarkBackend.EXPERIMENTAL_FAST
        "compiled_cold",
        "compiled-cold",
        "compiler_cold",
        "compiler-cold",
        "jit_cold",
        "jit-cold" -> CoremarkBackend.COMPILED_COLD
        "compiled", "compiler", "jit" -> CoremarkBackend.COMPILED
        "" -> null
        else -> null
    }

private fun intProperty(name: String, defaultValue: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: defaultValue

private fun doubleProperty(name: String, defaultValue: Double): Double =
    System.getProperty(name)?.toDoubleOrNull() ?: defaultValue

private fun booleanProperty(name: String, defaultValue: Boolean): Boolean =
    System.getProperty(name)?.let { value ->
        when (value.trim().lowercase(Locale.ROOT)) {
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> defaultValue
        }
    } ?: defaultValue

private fun referenceComparison(): ReferenceComparison? {
    val score = System.getProperty("krwa.coremark.referenceScore")?.toDoubleOrNull()
    val backend =
        System.getProperty("krwa.coremark.referenceBackend")?.let { value ->
            parseBackend(value) ?: error("Unknown reference backend: $value")
        }
    if (score == null && backend == null) return null
    if (score != null) {
        require(score > 0.0 && score.isFinite()) {
            "krwa.coremark.referenceScore must be finite and positive"
        }
    }
    val minRatio = doubleProperty("krwa.coremark.referenceMinRatio", 1.0)
    require(minRatio > 0.0 && minRatio.isFinite()) {
        "krwa.coremark.referenceMinRatio must be finite and positive"
    }
    return ReferenceComparison(
        name = System.getProperty("krwa.coremark.referenceName")
            ?: backend?.name?.lowercase(Locale.ROOT)
            ?: "reference",
        score = score,
        backend = backend,
        metric = referenceMetric(),
        minRatio = minRatio,
        failBelowReference = booleanProperty("krwa.coremark.failBelowReference", false),
    )
}

private fun referenceMetric(): ReferenceMetric =
    when (System.getProperty("krwa.coremark.referenceMetric", "p50").trim().lowercase(Locale.ROOT)) {
        "avg", "average", "mean" -> ReferenceMetric.AVG
        "min", "minimum" -> ReferenceMetric.MIN
        "p50", "median" -> ReferenceMetric.P50
        "best", "max", "maximum" -> ReferenceMetric.BEST
        else -> error("Unsupported krwa.coremark.referenceMetric")
    }

private fun printRun(backend: CoremarkBackend, run: Int, result: CoremarkResult) {
    println(
        String.format(
            Locale.US,
            "%s run=%d score=%.6f ms=%.3f",
            backend.name.lowercase(Locale.ROOT),
            run,
            result.score.toDouble(),
            result.elapsedNanos / 1_000_000.0,
        )
    )
}

private fun printSummary(backend: CoremarkBackend, results: List<CoremarkResult>): CoremarkSummary {
    val scores = results.map { it.score.toDouble() }
    val validScores = scores.filter { it.isFinite() && it > 0.0 }
    val invalidRuns = scores.size - validScores.size
    val millis = results.map { it.elapsedNanos / 1_000_000.0 }
    val sortedScores = validScores.sorted()
    val sortedMillis = millis.sorted()
    val bestScore = sortedScores.lastOrNull() ?: Double.NaN
    val minScore = sortedScores.firstOrNull() ?: Double.NaN
    val p50Score = sortedScores.getOrNull(sortedScores.size / 2) ?: Double.NaN
    val averageScore = validScores.average()
    val averageMillis = millis.average()
    val p50Millis = sortedMillis[sortedMillis.size / 2]
    val minMillis = sortedMillis.first()
    val maxMillis = sortedMillis.last()

    println(
        String.format(
            Locale.US,
            "%s score_avg=%.6f score_min=%.6f score_p50=%.6f score_best=%.6f valid_runs=%d invalid_runs=%d ms_avg=%.3f ms_min=%.3f ms_p50=%.3f ms_max=%.3f",
            backend.name.lowercase(Locale.ROOT),
            averageScore,
            minScore,
            p50Score,
            bestScore,
            validScores.size,
            invalidRuns,
            averageMillis,
            minMillis,
            p50Millis,
            max(maxMillis, minMillis),
        )
    )

    return CoremarkSummary(
        backend = backend,
        scoreAvg = averageScore,
        scoreMin = minScore,
        scoreP50 = p50Score,
        scoreBest = bestScore,
        validRuns = validScores.size,
        invalidRuns = invalidRuns,
    )
}

private fun printReferenceComparison(
    reference: ReferenceComparison,
    summaries: List<CoremarkSummary>,
) {
    val referenceSummary =
        reference.backend?.let { backend ->
            summaries.firstOrNull { it.backend == backend }
                ?: error("Reference backend $backend was not measured")
        }
    val referenceScore = reference.score ?: reference.metric.select(referenceSummary!!)
    val requiredScore = referenceScore * reference.minRatio
    val referenceInvalidRuns = referenceSummary?.invalidRuns ?: 0
    val failures = ArrayList<String>()
    for (summary in summaries) {
        if (reference.score == null && summary.backend == reference.backend) {
            continue
        }
        val score = reference.metric.select(summary)
        val ratio = score / referenceScore
        val passed = referenceInvalidRuns == 0 && summary.invalidRuns == 0 && score >= requiredScore
        val status = if (passed) "pass" else "fail"
        val backend = summary.backend.name.lowercase(Locale.ROOT)
        println(
            String.format(
                Locale.US,
                "%s reference=%s metric=%s score=%.6f reference_score=%.6f ratio=%.3f status=%s",
                backend,
                reference.name,
                reference.metric.id,
                score,
                referenceScore,
                ratio,
                status,
            )
        )
        if (!passed) {
            failures.add(
                "$backend ${reference.metric.id} $score < ${reference.name} $referenceScore * ${reference.minRatio}"
            )
        }
    }

    if (reference.failBelowReference && failures.isNotEmpty()) {
        System.out.flush()
        error("CoreMark reference comparison failed: " + failures.joinToString("; "))
    }
}

private data class CoremarkSummary(
    val backend: CoremarkBackend,
    val scoreAvg: Double,
    val scoreMin: Double,
    val scoreP50: Double,
    val scoreBest: Double,
    val validRuns: Int,
    val invalidRuns: Int,
)

private data class ReferenceComparison(
    val name: String,
    val score: Double?,
    val backend: CoremarkBackend?,
    val metric: ReferenceMetric,
    val minRatio: Double,
    val failBelowReference: Boolean,
)

private enum class ReferenceMetric(val id: String) {
    AVG("score_avg"),
    MIN("score_min"),
    P50("score_p50"),
    BEST("score_best");

    fun select(summary: CoremarkSummary): Double =
        when (this) {
            AVG -> summary.scoreAvg
            MIN -> summary.scoreMin
            P50 -> summary.scoreP50
            BEST -> summary.scoreBest
        }
}
