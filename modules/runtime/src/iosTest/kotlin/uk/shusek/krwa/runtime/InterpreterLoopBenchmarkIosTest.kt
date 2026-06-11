package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.posix.getenv

class InterpreterLoopBenchmarkIosTest {
    @Test
    fun benchmarksInterpreterLoop() {
        if (argument(EnabledArgument) != "true" && env(EnabledEnv) != "true") return

        val result =
            InterpreterLoopBenchmarkSupport.run(
                iterations = intOption(IterationsArgument, IterationsEnv, DefaultIterations),
                repetitions = intOption(RepetitionsArgument, RepetitionsEnv, DefaultRepetitions),
                warmupRepetitions =
                    intOption(
                        WarmupRepetitionsArgument,
                        WarmupRepetitionsEnv,
                        DefaultWarmupRepetitions,
                    ),
            )
        println(result.formatted("iosSimulatorArm64"))
    }

    private fun intOption(argumentName: String, envName: String, defaultValue: Int): Int =
        argument(argumentName)?.toIntOrNull()
            ?: env(envName)?.toIntOrNull()
            ?: defaultValue

    private fun argument(name: String): String? {
        val prefix = "$name="
        for (value in NSProcessInfo.processInfo.arguments) {
            val argument = value as? String ?: continue
            if (argument.startsWith(prefix)) {
                return argument.substring(prefix.length)
            }
        }
        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun env(name: String): String? =
        getenv(name)?.toKString()

    private companion object {
        const val EnabledArgument = "--krwa-runtime-benchmark"
        const val EnabledEnv = "KRWA_RUNTIME_BENCHMARK"
        const val IterationsArgument = "--krwa-runtime-benchmark-iterations"
        const val IterationsEnv = "KRWA_RUNTIME_BENCHMARK_ITERATIONS"
        const val RepetitionsArgument = "--krwa-runtime-benchmark-repetitions"
        const val RepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_REPETITIONS"
        const val WarmupRepetitionsArgument = "--krwa-runtime-benchmark-warmup-repetitions"
        const val WarmupRepetitionsEnv = "KRWA_RUNTIME_BENCHMARK_WARMUP_REPETITIONS"
        const val DefaultIterations = 500_000
        const val DefaultRepetitions = 5
        const val DefaultWarmupRepetitions = 2
    }
}
