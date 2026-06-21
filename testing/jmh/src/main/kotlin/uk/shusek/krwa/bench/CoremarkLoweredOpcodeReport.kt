package uk.shusek.krwa.bench

import java.lang.reflect.Modifier
import java.util.Locale
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 20).coerceAtLeast(1)
    val report = CoremarkLoweredOpcodeReport.analyze(module)

    println("CoreMark static lowered opcode report")
    println(
        String.format(
            Locale.US,
            "functions=%d lowered_functions=%d raw_instructions=%d lowered_dispatches=%d dispatch_ratio=%.3f",
            report.functionCount,
            report.loweredFunctionCount,
            report.rawInstructionCount,
            report.loweredDispatchCount,
            report.loweredDispatchCount.toDouble() / report.rawInstructionCount.coerceAtLeast(1),
        )
    )
    println("profile_path=static_stackframe_layout lowered_fast_path=true")
    printRanked("lowered opcodes", report.opcodes, top)
    printRanked("lowered pairs", report.pairs, top)
    printRanked("lowered triples", report.triples, top)
}

private object CoremarkLoweredOpcodeReport {
    fun analyze(module: WasmModule): LoweredReport {
        val instance = diagnosticInstance(module)
        val opcodes = HashMap<String, Int>()
        val pairs = HashMap<String, Int>()
        val triples = HashMap<String, Int>()
        val codeSection = module.codeSection()
        var rawInstructionCount = 0
        var loweredFunctionCount = 0
        var loweredDispatchCount = 0

        for (bodyIndex in 0 until codeSection.functionBodyCount()) {
            val type = module.functionSection().getFunctionType(bodyIndex, module.typeSection())
            val body = codeSection.getFunctionBody(bodyIndex)
            val rawInstructions = body.instructions()
            rawInstructionCount += rawInstructions.size

            val layout = StackFrame.Layout(
                instance,
                type.params(),
                body.localTypes(),
                rawInstructions,
            )
            val loweredFunction = LoweredReflection.loweredFunction(layout) ?: continue
            val loweredNames = LoweredReflection.dispatchNames(loweredFunction)
            loweredFunctionCount++
            loweredDispatchCount += loweredNames.size

            for (name in loweredNames) {
                opcodes.increment(name)
            }
            for (index in 0 until loweredNames.size - 1) {
                pairs.increment(loweredNames[index] + " " + loweredNames[index + 1])
            }
            for (index in 0 until loweredNames.size - 2) {
                triples.increment(
                    loweredNames[index] +
                        " " +
                        loweredNames[index + 1] +
                        " " +
                    loweredNames[index + 2],
                )
            }
        }

        return LoweredReport(
            functionCount = codeSection.functionBodyCount(),
            loweredFunctionCount = loweredFunctionCount,
            rawInstructionCount = rawInstructionCount,
            loweredDispatchCount = loweredDispatchCount,
            opcodes = opcodes.toRankedEntries(),
            pairs = pairs.toRankedEntries(),
            triples = triples.toRankedEntries(),
        )
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

private object LoweredReflection {
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

private data class LoweredReport(
    val functionCount: Int,
    val loweredFunctionCount: Int,
    val rawInstructionCount: Int,
    val loweredDispatchCount: Int,
    val opcodes: List<LoweredRankedEntry>,
    val pairs: List<LoweredRankedEntry>,
    val triples: List<LoweredRankedEntry>,
)

private data class LoweredRankedEntry(
    val name: String,
    val count: Int,
)

private fun HashMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun Map<String, Int>.toRankedEntries(): List<LoweredRankedEntry> =
    entries
        .map { LoweredRankedEntry(it.key, it.value) }
        .sortedWith(compareByDescending<LoweredRankedEntry> { it.count }.thenBy { it.name })

private fun printRanked(title: String, entries: List<LoweredRankedEntry>, top: Int) {
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
