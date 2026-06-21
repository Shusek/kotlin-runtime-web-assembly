package uk.shusek.krwa.bench

import java.lang.reflect.Modifier
import java.util.Locale
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.MStack
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 12).coerceAtLeast(1)
    val warmups = intProperty("krwa.coremark.profile.warmups", 0).coerceAtLeast(0)
    val backend = profileBackend()

    require(backend == CoremarkBackend.INTERPRETER || backend == CoremarkBackend.EXPERIMENTAL_FAST) {
        "Function call profile requires an interpreter backend"
    }

    repeat(warmups) {
        ChasmCoremark.run(module, backend)
    }

    val staticFunctions = StaticFunctionShape.analyze(module)
    val profiler = FunctionCallProfiler()
    val result = ChasmCoremark.runProfiled(module, backend, profiler::onExecution)
    val score = result.score.toDouble()

    println("CoreMark function call profile")
    println(
        String.format(
            Locale.US,
            "backend=%s profile_path=raw_listener score=%.6f score_valid=%s ms=%.3f instructions=%d calls=%d",
            backend.name.lowercase(Locale.ROOT),
            score,
            (score.isFinite() && score > 0.0).toString(),
            result.elapsedNanos / 1_000_000.0,
            profiler.totalInstructions(),
            profiler.totalCalls(),
        )
    )
    println("note=ExecutionListener disables lowered fast paths; use call targets as dynamic control-flow evidence, not lowered opcode timing.")
    println()
    println("call_targets:")
    val rankedCalls = profiler.rankedCalls(staticFunctions)
    for ((rank, entry) in rankedCalls.take(top).withIndex()) {
        val shape = staticFunctions[entry.functionIndex]
        println(
            String.format(
                Locale.US,
                "%02d func=%d calls=%d raw=%s lowered=%s weighted_lowered=%s top_opcode=%s top_pair=%s",
                rank + 1,
                entry.functionIndex,
                entry.calls,
                shape?.rawInstructionCount?.toString() ?: "-",
                shape?.loweredDispatchCount?.toString() ?: "-",
                shape?.let { (entry.calls * it.loweredDispatchCount).toString() } ?: "-",
                shape?.topOpcode ?: "-",
                shape?.topPair ?: "-",
            )
        )
    }

    printWeightedPatterns("weighted_static_opcodes", weightedPatterns(rankedCalls, staticFunctions) { it.opcodes }, top)
    printWeightedPatterns("weighted_static_pairs", weightedPatterns(rankedCalls, staticFunctions) { it.pairs }, top)
    printWeightedPatterns("weighted_static_triples", weightedPatterns(rankedCalls, staticFunctions) { it.triples }, top)
}

private class FunctionCallProfiler {
    private val callCounts = HashMap<Int, Long>()
    private var totalInstructions = 0L
    private var totalCalls = 0L

    fun onExecution(instruction: Instruction, @Suppress("UNUSED_PARAMETER") stack: MStack) {
        totalInstructions++
        if (instruction.opcode() != OpCode.CALL) {
            return
        }

        val target = instruction.operand(0).toInt()
        callCounts[target] = (callCounts[target] ?: 0L) + 1L
        totalCalls++
    }

    fun totalInstructions(): Long = totalInstructions

    fun totalCalls(): Long = totalCalls

    fun rankedCalls(staticFunctions: Map<Int, FunctionShape>): List<FunctionCallEntry> =
        callCounts
            .map { (functionIndex, calls) ->
                FunctionCallEntry(
                    functionIndex = functionIndex,
                    calls = calls,
                    weightedLowered = calls * (staticFunctions[functionIndex]?.loweredDispatchCount ?: 0),
                )
            }
            .sortedWith(
                compareByDescending<FunctionCallEntry> { it.weightedLowered }
                    .thenByDescending { it.calls }
                    .thenBy { it.functionIndex }
            )
}

private object StaticFunctionShape {
    fun analyze(module: WasmModule): Map<Int, FunctionShape> {
        val instance = diagnosticInstance(module)
        val importedFunctions = module.importSection().count(ExternalType.FUNCTION)
        val codeSection = module.codeSection()
        val result = HashMap<Int, FunctionShape>()

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
            val loweredFunction = LoweredShapeInspector.loweredFunction(layout)
            val names =
                if (loweredFunction == null) {
                    emptyList()
                } else {
                    LoweredShapeInspector.dispatchNames(loweredFunction)
                }

            result[functionIndex] =
                FunctionShape(
                    rawInstructionCount = rawInstructions.size,
                    loweredDispatchCount = names.size,
                    opcodes = names.countPatterns(1),
                    pairs = names.countPatterns(2),
                    triples = names.countPatterns(3),
                )
        }

        return result
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

private object LoweredShapeInspector {
    private val loweredFunctionField =
        StackFrame.Layout::class.java.getDeclaredField("loweredFunction").apply {
            isAccessible = true
        }
    private val loweredFunctionClass = Class.forName("uk.shusek.krwa.runtime.LoweredFunction")
    private val opcodesField =
        loweredFunctionClass.getDeclaredField("opcodes").apply {
            isAccessible = true
        }
    private val opcodeNames = loadOpcodeNames()

    fun loweredFunction(layout: StackFrame.Layout): Any? =
        loweredFunctionField.get(layout)

    fun dispatchNames(loweredFunction: Any): List<String> {
        val opcodes = opcodesField.get(loweredFunction) as IntArray
        val names = ArrayList<String>()
        var index = 0
        while (index < opcodes.size) {
            val name = opcodeNames[opcodes[index]] ?: "unknown_${opcodes[index]}"
            names += name
            index += dispatchWidth(name)
        }
        return names
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

private data class FunctionCallEntry(
    val functionIndex: Int,
    val calls: Long,
    val weightedLowered: Long,
)

private data class FunctionShape(
    val rawInstructionCount: Int,
    val loweredDispatchCount: Int,
    val opcodes: List<FunctionPattern>,
    val pairs: List<FunctionPattern>,
    val triples: List<FunctionPattern>,
) {
    val topOpcode: String = opcodes.firstOrNull()?.formatInline() ?: "-"
    val topPair: String = pairs.firstOrNull()?.formatInline() ?: "-"
}

private data class FunctionPattern(
    val name: String,
    val count: Int,
) {
    fun formatInline(): String = "$name:$count"
}

private data class WeightedFunctionPattern(
    val functionIndex: Int,
    val calls: Long,
    val pattern: String,
    val staticCount: Int,
    val weightedCount: Long,
)

private fun List<String>.countPatterns(size: Int): List<FunctionPattern> {
    if (this.size < size) return emptyList()
    val counts = HashMap<String, Int>()
    for (index in 0..this.size - size) {
        val key = subList(index, index + size).joinToString(" ")
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.entries
        .map { FunctionPattern(it.key, it.value) }
        .sortedWith(compareByDescending<FunctionPattern> { it.count }.thenBy { it.name })
}

private fun weightedPatterns(
    calls: List<FunctionCallEntry>,
    staticFunctions: Map<Int, FunctionShape>,
    patterns: (FunctionShape) -> List<FunctionPattern>,
): List<WeightedFunctionPattern> {
    val result = ArrayList<WeightedFunctionPattern>()
    for (call in calls) {
        val shape = staticFunctions[call.functionIndex] ?: continue
        for (pattern in patterns(shape)) {
            result += WeightedFunctionPattern(
                functionIndex = call.functionIndex,
                calls = call.calls,
                pattern = pattern.name,
                staticCount = pattern.count,
                weightedCount = call.calls * pattern.count,
            )
        }
    }
    return result.sortedWith(
        compareByDescending<WeightedFunctionPattern> { it.weightedCount }
            .thenByDescending { it.calls }
            .thenBy { it.functionIndex }
            .thenBy { it.pattern }
    )
}

private fun printWeightedPatterns(
    title: String,
    entries: List<WeightedFunctionPattern>,
    top: Int,
) {
    println()
    println("$title:")
    for ((rank, entry) in entries.take(top).withIndex()) {
        println(
            String.format(
                Locale.US,
                "%02d func=%d weighted=%d calls=%d static_count=%d pattern=%s",
                rank + 1,
                entry.functionIndex,
                entry.weightedCount,
                entry.calls,
                entry.staticCount,
                entry.pattern,
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
