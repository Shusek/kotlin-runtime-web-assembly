package uk.shusek.krwa.bench

import java.util.Locale
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.OpCode

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 20).coerceAtLeast(1)
    val report = CoremarkOpcodeReport.analyze(module)

    println("CoreMark static opcode report")
    println("functions=${report.functionCount} instructions=${report.instructionCount}")
    printRanked("opcodes", report.opcodes, top)
    printRanked("pairs", report.pairs, top)
    printRanked("triples", report.triples, top)
}

private object CoremarkOpcodeReport {
    fun analyze(module: WasmModule): Report {
        val opcodes = HashMap<String, Int>()
        val pairs = HashMap<String, Int>()
        val triples = HashMap<String, Int>()
        var instructionCount = 0

        val codeSection = module.codeSection()
        for (functionIndex in 0 until codeSection.functionBodyCount()) {
            val instructions = codeSection.getFunctionBody(functionIndex).instructions()
            val names = instructions.map { it.opcode().displayName() }
            instructionCount += names.size

            for (name in names) {
                opcodes.increment(name)
            }
            for (index in 0 until names.size - 1) {
                pairs.increment(names[index] + " " + names[index + 1])
            }
            for (index in 0 until names.size - 2) {
                triples.increment(names[index] + " " + names[index + 1] + " " + names[index + 2])
            }
        }

        return Report(
            functionCount = codeSection.functionBodyCount(),
            instructionCount = instructionCount,
            opcodes = opcodes.toRankedEntries(),
            pairs = pairs.toRankedEntries(),
            triples = triples.toRankedEntries(),
        )
    }
}

private data class Report(
    val functionCount: Int,
    val instructionCount: Int,
    val opcodes: List<RankedEntry>,
    val pairs: List<RankedEntry>,
    val triples: List<RankedEntry>,
)

private data class RankedEntry(
    val name: String,
    val count: Int,
)

private fun HashMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun Map<String, Int>.toRankedEntries(): List<RankedEntry> =
    entries
        .map { RankedEntry(it.key, it.value) }
        .sortedWith(compareByDescending<RankedEntry> { it.count }.thenBy { it.name })

private fun printRanked(title: String, entries: List<RankedEntry>, top: Int) {
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

private fun OpCode.displayName(): String = name.lowercase(Locale.ROOT)
