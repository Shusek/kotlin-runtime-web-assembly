package uk.shusek.krwa.runtime

import kotlin.test.Test

class RuntimeBenchmarkJvmTest {
    @Test
    fun benchmarksRuntimeExecution() {
        if (!benchmarkEnabled()) return

        val iterations = intProperty(IterationsProperty, DefaultIterations)
        val repetitions = intProperty(RepetitionsProperty, DefaultRepetitions)
        val hostRepetitions = intProperty(HostRepetitionsProperty, repetitions)
        val warmupRepetitions = intProperty(WarmupRepetitionsProperty, DefaultWarmupRepetitions)
        for (backend in benchmarkBackends()) {
            runCatching {
                RuntimeBenchmarkSupport.run(
                    backend = backend,
                    iterations = iterations,
                    repetitions = repetitions,
                    warmupRepetitions = warmupRepetitions,
                )
            }.onSuccess { result ->
                println(result.formatted("jvm"))
            }.onFailure { error ->
                println("KRWA runtime benchmark skipped: platform=jvm, workload=loop, backend=$backend, reason=${error.message}")
            }

            runCatching {
                RuntimeBenchmarkSupport.runMemoryScan(
                    backend = backend,
                    iterations = iterations,
                    repetitions = repetitions,
                    warmupRepetitions = warmupRepetitions,
                )
            }.onSuccess { result ->
                println(result.formatted("jvm"))
            }.onFailure { error ->
                println(
                    "KRWA runtime benchmark skipped: platform=jvm, workload=memory-scan, " +
                        "backend=$backend, reason=${error.message}",
                )
            }

            runCatching {
                RuntimeBenchmarkSupport.runHostCallback(
                    backend = backend,
                    repetitions = hostRepetitions,
                    warmupRepetitions = warmupRepetitions,
                )
            }.onSuccess { result ->
                println(result.formatted("jvm"))
            }.onFailure { error ->
                println(
                    "KRWA runtime benchmark skipped: platform=jvm, workload=host-callback, " +
                        "backend=$backend, reason=${error.message}",
                )
            }
        }
    }

    private fun benchmarkEnabled(): Boolean =
        java.lang.Boolean.getBoolean(EnabledProperty) ||
            System.getenv(EnabledEnv) == "true"

    private fun intProperty(name: String, defaultValue: Int): Int =
        System.getProperty(name)?.toIntOrNull()
            ?: System.getenv(envName(name))?.toIntOrNull()
            ?: defaultValue

    private fun benchmarkBackends(): List<ExecutionBackend> =
        (System.getProperty(BackendsProperty) ?: System.getenv(BackendsEnv))
            ?.split(',')
            ?.mapNotNull { value ->
                ExecutionBackend.entries.firstOrNull { backend ->
                    backend.name.equals(value.trim(), ignoreCase = true)
                }
            }
            ?.takeIf(List<ExecutionBackend>::isNotEmpty)
            ?: listOf(ExecutionBackend.PULLEY)

    private fun envName(name: String): String =
        when (name) {
            IterationsProperty -> IterationsEnv
            RepetitionsProperty -> RepetitionsEnv
            WarmupRepetitionsProperty -> WarmupRepetitionsEnv
            HostRepetitionsProperty -> HostRepetitionsEnv
            BackendsProperty -> BackendsEnv
            else -> name.uppercase().replace('.', '_')
        }

    private companion object {
        const val EnabledProperty = "krwa.runtimeBenchmark"
        const val EnabledEnv = "KRWA_RUNTIME_BENCHMARK"
        const val IterationsProperty = "krwa.runtimeBenchmarkIterations"
        const val IterationsEnv = "KRWA_RUNTIME_BENCHMARK_ITERATIONS"
        const val RepetitionsProperty = "krwa.runtimeBenchmarkRepetitions"
        const val RepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_REPETITIONS"
        const val HostRepetitionsProperty = "krwa.runtimeBenchmarkHostRepetitions"
        const val HostRepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_HOST_REPETITIONS"
        const val WarmupRepetitionsProperty = "krwa.runtimeBenchmarkWarmupRepetitions"
        const val WarmupRepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_WARMUP_REPETITIONS"
        const val BackendsProperty = "krwa.runtimeBenchmarkBackends"
        const val BackendsEnv = "KRWA_RUNTIME_BENCHMARK_BACKENDS"
        const val DefaultIterations = 500_000
        const val DefaultRepetitions = 5
        const val DefaultWarmupRepetitions = 2
    }
}
