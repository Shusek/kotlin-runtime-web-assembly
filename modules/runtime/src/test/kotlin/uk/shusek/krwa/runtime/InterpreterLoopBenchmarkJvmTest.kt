package uk.shusek.krwa.runtime

import kotlin.test.Test

class InterpreterLoopBenchmarkJvmTest {
    @Test
    fun benchmarksInterpreterLoop() {
        if (!benchmarkEnabled()) return

        val result =
            InterpreterLoopBenchmarkSupport.run(
                iterations = intProperty(IterationsProperty, DefaultIterations),
                repetitions = intProperty(RepetitionsProperty, DefaultRepetitions),
                warmupRepetitions = intProperty(WarmupRepetitionsProperty, DefaultWarmupRepetitions),
            )
        println(result.formatted("jvm"))
    }

    private fun benchmarkEnabled(): Boolean =
        java.lang.Boolean.getBoolean(EnabledProperty) ||
            System.getenv(EnabledEnv) == "true"

    private fun intProperty(name: String, defaultValue: Int): Int =
        System.getProperty(name)?.toIntOrNull()
            ?: System.getenv(envName(name))?.toIntOrNull()
            ?: defaultValue

    private fun envName(name: String): String =
        when (name) {
            IterationsProperty -> IterationsEnv
            RepetitionsProperty -> RepetitionsEnv
            WarmupRepetitionsProperty -> WarmupRepetitionsEnv
            else -> name.uppercase().replace('.', '_')
        }

    private companion object {
        const val EnabledProperty = "krwa.runtimeBenchmark"
        const val EnabledEnv = "KRWA_RUNTIME_BENCHMARK"
        const val IterationsProperty = "krwa.runtimeBenchmarkIterations"
        const val IterationsEnv = "KRWA_RUNTIME_BENCHMARK_ITERATIONS"
        const val RepetitionsProperty = "krwa.runtimeBenchmarkRepetitions"
        const val RepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_REPETITIONS"
        const val WarmupRepetitionsProperty = "krwa.runtimeBenchmarkWarmupRepetitions"
        const val WarmupRepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_WARMUP_REPETITIONS"
        const val DefaultIterations = 500_000
        const val DefaultRepetitions = 5
        const val DefaultWarmupRepetitions = 2
    }
}
