package uk.shusek.krwa.bench

import java.lang.reflect.Modifier
import java.util.Locale
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 8).coerceAtLeast(1)
    val functionCount = intProperty("krwa.coremark.report.functions", 8).coerceAtLeast(1)
    val printSequence = booleanProperty("krwa.coremark.report.sequence", false)
    val selectedFunctions = selectedFunctionsProperty()
    val report = CoremarkLoweredFunctionReport.analyze(module)
    val rankedFunctions =
        report.functions.sortedWith(
            compareByDescending<LoweredFunctionSummary> { it.loweredDispatchCount }
                .thenBy { it.bodyIndex }
        )

    println("CoreMark lowered function report")
    println(
        String.format(
            Locale.US,
            "functions=%d lowered_functions=%d raw_instructions=%d lowered_dispatches=%d dispatch_ratio=%.3f",
            report.functions.size,
            report.functions.count { it.loweredDispatchCount > 0 },
            report.functions.sumOf { it.rawInstructionCount },
            report.functions.sumOf { it.loweredDispatchCount },
            report.functions.sumOf { it.loweredDispatchCount }.toDouble() /
                report.functions.sumOf { it.rawInstructionCount }.coerceAtLeast(1),
        )
    )
    println("profile_path=static_stackframe_layout lowered_fast_path=true")
    println()
    println("functions_by_lowered_dispatch:")
    for ((rank, function) in rankedFunctions.take(functionCount).withIndex()) {
        println(
            String.format(
                Locale.US,
                "%02d body=%d func=%d raw=%d lowered=%d ratio=%.3f top_opcode=%s top_pair=%s",
                rank + 1,
                function.bodyIndex,
                function.functionIndex,
                function.rawInstructionCount,
                function.loweredDispatchCount,
                function.loweredDispatchCount.toDouble() / function.rawInstructionCount.coerceAtLeast(1),
                function.opcodes.firstOrNull()?.formatInline() ?: "-",
                function.pairs.firstOrNull()?.formatInline() ?: "-",
            )
        )
    }

    val detailed =
        if (selectedFunctions.isEmpty()) {
            rankedFunctions.take(functionCount)
        } else {
            report.functions.filter { it.functionIndex in selectedFunctions || it.bodyIndex in selectedFunctions }
        }

    for (function in detailed) {
        println()
        println(
            String.format(
                Locale.US,
                "function body=%d func=%d raw=%d lowered=%d ratio=%.3f",
                function.bodyIndex,
                function.functionIndex,
                function.rawInstructionCount,
                function.loweredDispatchCount,
                function.loweredDispatchCount.toDouble() / function.rawInstructionCount.coerceAtLeast(1),
            )
        )
        printRanked("opcodes", function.opcodes, top)
        printRanked("pairs", function.pairs, top)
        printRanked("triples", function.triples, top)

        if (printSequence) {
            println()
            println("lowered_sequence:")
            for (entry in function.entries) {
                println(
                    String.format(
                        Locale.US,
                        "index=%04d width=%d opcode=%s operand=%d operand2=%d operand3=%d label_true=%d label_false=%d",
                        entry.index,
                        entry.width,
                        entry.name,
                        entry.operand,
                        entry.operand2,
                        entry.operand3,
                        entry.labelTrue,
                        entry.labelFalse,
                    )
                )
            }
        }
    }
}

private object CoremarkLoweredFunctionReport {
    fun analyze(module: WasmModule): LoweredFunctionReport {
        val instance = diagnosticInstance(module)
        val codeSection = module.codeSection()
        val importedFunctions = module.importSection().count(ExternalType.FUNCTION)
        val functions = ArrayList<LoweredFunctionSummary>(codeSection.functionBodyCount())

        for (bodyIndex in 0 until codeSection.functionBodyCount()) {
            val functionIndex = importedFunctions + bodyIndex
            val type = module.functionSection().getFunctionType(bodyIndex, module.typeSection())
            val body = codeSection.getFunctionBody(bodyIndex)
            val rawInstructions = body.instructions()
            val layout = StackFrame.Layout(
                instance,
                type.params(),
                body.localTypes(),
                rawInstructions,
            )
            val loweredFunction = LoweredFunctionInspector.loweredFunction(layout)
            val entries =
                if (loweredFunction == null) {
                    emptyList()
                } else {
                    LoweredFunctionInspector.dispatchEntries(loweredFunction)
                }
            val names = entries.map { it.name }

            functions += LoweredFunctionSummary(
                bodyIndex = bodyIndex,
                functionIndex = functionIndex,
                rawInstructionCount = rawInstructions.size,
                loweredDispatchCount = entries.size,
                entries = entries,
                opcodes = names.countSinglePatterns(),
                pairs = names.countWindows(2),
                triples = names.countWindows(3),
            )
        }

        return LoweredFunctionReport(functions)
    }

    private fun diagnosticInstance(module: WasmModule): Instance {
        val clock =
            HostFunction(
                "env",
                "clock_ms",
                FunctionType.returning(ValType.I64),
            ) { _, _ ->
                longArrayOf(0L)
            }

        return Instance.builder(module)
            .withInitialize(false)
            .withStart(false)
            .withImportValues(ImportValues.builder().addFunction(clock).build())
            .withMachineFactory { instance ->
                object : InterpreterMachine(instance) {
                    override fun isInterrupted(): Boolean = false
                }
            }
            .build()
    }
}

private object LoweredFunctionInspector {
    private val loweredFunctionField =
        StackFrame.Layout::class.java.getDeclaredField("loweredFunction").apply {
            isAccessible = true
        }
    private val loweredFunctionClass = Class.forName("uk.shusek.krwa.runtime.LoweredFunction")
    private val opcodesField =
        loweredFunctionClass.getDeclaredField("opcodes").apply {
            isAccessible = true
        }
    private val operandsField =
        loweredFunctionClass.getDeclaredField("operands").apply {
            isAccessible = true
        }
    private val operands2Field =
        loweredFunctionClass.getDeclaredField("operands2").apply {
            isAccessible = true
        }
    private val operands3Field =
        loweredFunctionClass.getDeclaredField("operands3").apply {
            isAccessible = true
        }
    private val labelTrueField =
        loweredFunctionClass.getDeclaredField("labelTrue").apply {
            isAccessible = true
        }
    private val labelFalseField =
        loweredFunctionClass.getDeclaredField("labelFalse").apply {
            isAccessible = true
        }
    private val opcodeNames = loadOpcodeNames()

    fun loweredFunction(layout: StackFrame.Layout): Any? =
        loweredFunctionField.get(layout)

    fun dispatchEntries(loweredFunction: Any): List<LoweredDispatchEntry> {
        val opcodes = opcodesField.get(loweredFunction) as IntArray
        val operands = operandsField.get(loweredFunction) as LongArray
        val operands2 = operands2Field.get(loweredFunction) as IntArray
        val operands3 = operands3Field.get(loweredFunction) as IntArray
        val labelTrue = labelTrueField.get(loweredFunction) as IntArray
        val labelFalse = labelFalseField.get(loweredFunction) as IntArray
        val entries = ArrayList<LoweredDispatchEntry>()
        var index = 0
        while (index < opcodes.size) {
            val name = opcodeNames[opcodes[index]] ?: "unknown_${opcodes[index]}"
            val width = dispatchWidth(name)
            entries += LoweredDispatchEntry(
                index = index,
                width = width,
                name = name,
                operand = operands[index],
                operand2 = operands2[index],
                operand3 = operands3[index],
                labelTrue = labelTrue[index],
                labelFalse = labelFalse[index],
            )
            index += width
        }
        return entries
    }

    private fun loadOpcodeNames(): Map<Int, String> {
        val names = HashMap<Int, String>()
        for (field in loweredFunctionClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) {
                continue
            }
            if (field.name == "CURRENT_PARAMETERLESS_LOOP_DEPTH") {
                continue
            }
            field.isAccessible = true
            val value = field.getInt(null)
            if (value >= 0) {
                names[value] = field.name.lowercase(Locale.ROOT)
            }
        }
        require(names.isNotEmpty()) {
            "Could not reflect lowered opcode constants from ${loweredFunctionClass.name}"
        }
        return names
    }

    private fun dispatchWidth(name: String): Int =
        when (name) {
            "i32_countdown_branch" -> 5
            "local_get_i32_const_i32_add_local_set",
            "local_get_i32_const_i32_add_local_tee",
            "local_get_i32_const_i32_and_local_tee",
            "local_get_i32_const_i32_shl_local_set",
            "local_get_local_get_i32_load8_u_i32_store8",
            -> 4
            "local_get_i32_const_i32_add",
            "local_get_i32_const_i32_and",
            "local_get_i32_const_i32_shl",
            "local_get_i32_const_i32_shr_u",
            -> 3
            "local_get_i32_load",
            "local_get_i32_load8_u",
            "local_get_i32_load16_s",
            "local_get_i32_load16_u",
            "local_get_i64_load",
            "local_set_local_get",
            "local_get_local_set",
            "i32_const_local_set",
            "i32_add_local_set",
            "i32_add_local_tee",
            -> 2
            else -> 1
        }
}

private data class LoweredFunctionReport(
    val functions: List<LoweredFunctionSummary>,
)

private data class LoweredFunctionSummary(
    val bodyIndex: Int,
    val functionIndex: Int,
    val rawInstructionCount: Int,
    val loweredDispatchCount: Int,
    val entries: List<LoweredDispatchEntry>,
    val opcodes: List<LoweredFunctionRankedEntry>,
    val pairs: List<LoweredFunctionRankedEntry>,
    val triples: List<LoweredFunctionRankedEntry>,
)

private data class LoweredDispatchEntry(
    val index: Int,
    val width: Int,
    val name: String,
    val operand: Long,
    val operand2: Int,
    val operand3: Int,
    val labelTrue: Int,
    val labelFalse: Int,
)

private data class LoweredFunctionRankedEntry(
    val name: String,
    val count: Int,
) {
    fun formatInline(): String = "$name:$count"
}

private fun List<String>.countSinglePatterns(): List<LoweredFunctionRankedEntry> =
    groupingBy { it }.eachCount().toRankedEntries()

private fun List<String>.countWindows(size: Int): List<LoweredFunctionRankedEntry> {
    if (this.size < size) return emptyList()
    val counts = HashMap<String, Int>()
    for (index in 0..this.size - size) {
        val key = subList(index, index + size).joinToString(" ")
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.toRankedEntries()
}

private fun Map<String, Int>.toRankedEntries(): List<LoweredFunctionRankedEntry> =
    entries
        .map { LoweredFunctionRankedEntry(it.key, it.value) }
        .sortedWith(compareByDescending<LoweredFunctionRankedEntry> { it.count }.thenBy { it.name })

private fun printRanked(title: String, entries: List<LoweredFunctionRankedEntry>, top: Int) {
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

private fun selectedFunctionsProperty(): Set<Int> =
    System.getProperty("krwa.coremark.report.functionIds")
        ?.split(',')
        ?.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() }
        ?.toSet()
        ?: emptySet()

private fun intProperty(name: String, defaultValue: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: defaultValue

private fun booleanProperty(name: String, defaultValue: Boolean): Boolean =
    System.getProperty(name)?.let { value ->
        when (value.trim().lowercase(Locale.ROOT)) {
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> defaultValue
        }
    } ?: defaultValue
