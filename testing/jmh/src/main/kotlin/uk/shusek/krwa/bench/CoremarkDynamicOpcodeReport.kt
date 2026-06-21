package uk.shusek.krwa.bench

import java.util.Locale
import uk.shusek.krwa.runtime.MStack
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 20).coerceAtLeast(1)
    val warmups = intProperty("krwa.coremark.profile.warmups", 0).coerceAtLeast(0)
    val backend = profileBackend()

    require(backend == CoremarkBackend.INTERPRETER || backend == CoremarkBackend.EXPERIMENTAL_FAST) {
        "Dynamic opcode profile requires an interpreter backend"
    }

    repeat(warmups) {
        ChasmCoremark.run(module, backend)
    }

    val profiler = DynamicOpcodeProfiler()
    val result = ChasmCoremark.runProfiled(module, backend, profiler::onExecution)
    val score = result.score.toDouble()

    println("CoreMark dynamic opcode profile")
    println(
        String.format(
            Locale.US,
            "backend=%s lowered_fast_path=false profile_path=raw_listener score=%.6f score_valid=%s ms=%.3f instructions=%d",
            backend.name.lowercase(Locale.ROOT),
            score,
            (score.isFinite() && score > 0.0).toString(),
            result.elapsedNanos / 1_000_000.0,
            profiler.totalInstructions(),
        )
    )
    println("note=ExecutionListener disables lowered fast paths; use this for raw interpreter control-flow shape, not lowered dispatch ranking.")
    printRanked("opcodes", profiler.rankedOpcodes(), top)
    printRanked("pairs", profiler.rankedPairs(), top)
    printRanked("triples", profiler.rankedTriples(), top)
}

private class DynamicOpcodeProfiler {
    private val opcodes = OpCode.values()
    private val opcodeCounts = LongArray(opcodes.size)
    private val pairCounts = LongArray(opcodes.size * opcodes.size)
    private val tripleCounts = HashMap<Int, Long>()
    private var total = 0L
    private var previous = -1
    private var previousPrevious = -1

    fun onExecution(instruction: Instruction, @Suppress("UNUSED_PARAMETER") stack: MStack) {
        val current = instruction.opcode().ordinal
        opcodeCounts[current]++
        total++

        if (previous >= 0) {
            pairCounts[previous * opcodes.size + current]++
        }
        if (previousPrevious >= 0) {
            val key = (previousPrevious * opcodes.size + previous) * opcodes.size + current
            tripleCounts[key] = (tripleCounts[key] ?: 0L) + 1L
        }

        previousPrevious = previous
        previous = current
    }

    fun totalInstructions(): Long = total

    fun rankedOpcodes(): List<DynamicRankedEntry> {
        val result = ArrayList<DynamicRankedEntry>()
        for (index in opcodeCounts.indices) {
            val count = opcodeCounts[index]
            if (count != 0L) {
                result.add(DynamicRankedEntry(opcodes[index].displayName(), count))
            }
        }
        return result.ranked()
    }

    fun rankedPairs(): List<DynamicRankedEntry> {
        val result = ArrayList<DynamicRankedEntry>()
        for (first in opcodes.indices) {
            for (second in opcodes.indices) {
                val count = pairCounts[first * opcodes.size + second]
                if (count != 0L) {
                    result.add(
                        DynamicRankedEntry(
                            opcodes[first].displayName() + " " + opcodes[second].displayName(),
                            count,
                        )
                    )
                }
            }
        }
        return result.ranked()
    }

    fun rankedTriples(): List<DynamicRankedEntry> =
        tripleCounts
            .map { (key, count) ->
                val first = key / (opcodes.size * opcodes.size)
                val rest = key % (opcodes.size * opcodes.size)
                val second = rest / opcodes.size
                val third = rest % opcodes.size
                DynamicRankedEntry(
                    opcodes[first].displayName() +
                        " " +
                        opcodes[second].displayName() +
                        " " +
                        opcodes[third].displayName(),
                    count,
                )
            }
            .ranked()
}

private data class DynamicRankedEntry(
    val name: String,
    val count: Long,
)

private fun List<DynamicRankedEntry>.ranked(): List<DynamicRankedEntry> =
    sortedWith(compareByDescending<DynamicRankedEntry> { it.count }.thenBy { it.name })

private fun printRanked(title: String, entries: List<DynamicRankedEntry>, top: Int) {
    println()
    println("$title:")
    for ((index, entry) in entries.take(top).withIndex()) {
        println(
            String.format(
                Locale.US,
                "%02d count=%d pattern=%s",
                index + 1,
                entry.count,
                entry.name,
            )
        )
    }
}

private fun intProperty(name: String, defaultValue: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: defaultValue

private fun profileBackend(): CoremarkBackend =
    when (System.getProperty("krwa.coremark.profile.backend", "interpreter").trim().lowercase(Locale.ROOT)) {
        "interpreter", "interpreted", "int" -> CoremarkBackend.INTERPRETER
        "experimental", "experimental_fast", "experimental-fast", "fast" ->
            CoremarkBackend.EXPERIMENTAL_FAST
        else -> error("Unsupported krwa.coremark.profile.backend")
    }

private fun OpCode.displayName(): String = name.lowercase(Locale.ROOT)
