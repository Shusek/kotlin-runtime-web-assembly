package uk.shusek.krwa.bench

import java.util.Locale
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val module = ChasmCoremark.loadModule()
    val top = intProperty("krwa.coremark.report.top", 12).coerceAtLeast(1)
    val functionCount = intProperty("krwa.coremark.report.functions", 8).coerceAtLeast(1)
    val report = CoremarkFrameSlotPlanReport.analyze(module)
    val ranked =
        report.functions.sortedWith(
            compareByDescending<FrameSlotFunctionPlan> { it.rawInstructions - it.plannedDispatches }
                .thenBy { it.bodyIndex }
        )

    println("CoreMark frame-slot plan report")
    println(
        String.format(
            Locale.US,
            "functions=%d raw_instructions=%d planned_dispatches=%d ratio=%.3f elided_sources=%d materializations=%d unsupported=%d",
            report.functions.size,
            report.rawInstructions,
            report.plannedDispatches,
            report.plannedDispatches.toDouble() / report.rawInstructions.coerceAtLeast(1),
            report.elidedSources,
            report.materializations,
            report.unsupported,
        )
    )
    printFrameSlotRanked("planned op shapes", report.patterns.toFrameSlotRankedEntries(), top)
    println()
    println("functions_by_dispatch_delta:")
    for ((rank, function) in ranked.take(functionCount).withIndex()) {
        println(
            String.format(
                Locale.US,
                "%02d body=%d func=%d raw=%d planned=%d ratio=%.3f elided=%d materialize=%d unsupported=%d top=%s",
                rank + 1,
                function.bodyIndex,
                function.functionIndex,
                function.rawInstructions,
                function.plannedDispatches,
                function.plannedDispatches.toDouble() / function.rawInstructions.coerceAtLeast(1),
                function.elidedSources,
                function.materializations,
                function.unsupported,
                function.patterns.toFrameSlotRankedEntries().firstOrNull()?.formatInline() ?: "-",
            )
        )
    }
}

private object CoremarkFrameSlotPlanReport {
    fun analyze(module: WasmModule): FrameSlotPlanReport {
        val importedFunctions = module.importSection().count(uk.shusek.krwa.wasm.types.ExternalType.FUNCTION)
        val functions = ArrayList<FrameSlotFunctionPlan>()
        for (bodyIndex in 0 until module.codeSection().functionBodyCount()) {
            val functionIndex = importedFunctions + bodyIndex
            val type = module.functionSection().getFunctionType(bodyIndex, module.typeSection())
            val body = module.codeSection().getFunctionBody(bodyIndex)
            functions += FrameSlotPlanner(module, bodyIndex, functionIndex, type, body).plan()
        }
        return FrameSlotPlanReport(functions)
    }
}

private class FrameSlotPlanner(
    @Suppress("unused") private val module: WasmModule,
    private val bodyIndex: Int,
    private val functionIndex: Int,
    private val type: FunctionType,
    private val body: FunctionBody,
) {
    private val localSlots = localSlots(type.params() + body.localTypes())
    private val stack = ArrayList<OperandSource>()
    private val patterns = HashMap<String, Int>()
    private var tempSlot = localSlots.maxOrNull()?.plus(1) ?: 0
    private var plannedDispatches = 0
    private var elidedSources = 0
    private var materializations = 0
    private var unsupported = 0

    fun plan(): FrameSlotFunctionPlan {
        val instructions = body.instructions()
        var index = 0
        while (index < instructions.size) {
            val instruction = instructions[index]
            val next = instructions.getOrNull(index + 1)
            val nextLocalDestination =
                if (next != null && (next.opcode() == OpCode.LOCAL_SET || next.opcode() == OpCode.LOCAL_TEE)) {
                    next.localDestination()
                } else {
                    null
                }
            val opcode = instruction.opcode()

            when {
                opcode == OpCode.LOCAL_GET -> {
                    val slot = localSlot(instruction)
                    if (slot == null) {
                        materializeStack("local_get_v128")
                        planned("local_get(v128)")
                    } else {
                        stack += OperandSource.Local
                        elidedSources++
                    }
                    index++
                }

                opcode.isConst() -> {
                    stack += OperandSource.Const
                    elidedSources++
                    index++
                }

                opcode.isBinaryI32() -> {
                    val right = popSource()
                    val left = popSource()
                    if (nextLocalDestination != null) {
                        planned("${opcode.shortName()}(${left.id},${right.id})->${nextLocalDestination.id}")
                        if (next!!.opcode() == OpCode.LOCAL_TEE) {
                            stack += OperandSource.Local
                        }
                        index += 2
                    } else {
                        planned("${opcode.shortName()}(${left.id},${right.id})->temp")
                        stack += OperandSource.Temp(tempSlot++)
                        index++
                    }
                }

                opcode.isUnaryI32() || opcode.isUnaryNumericConversion() -> {
                    val operand = popSource()
                    if (nextLocalDestination != null) {
                        planned("${opcode.shortName()}(${operand.id})->${nextLocalDestination.id}")
                        if (next!!.opcode() == OpCode.LOCAL_TEE) {
                            stack += OperandSource.Local
                        }
                        index += 2
                    } else {
                        planned("${opcode.shortName()}(${operand.id})->temp")
                        stack += OperandSource.Temp(tempSlot++)
                        index++
                    }
                }

                opcode.isMemoryLoad() -> {
                    val address = popSource()
                    if (nextLocalDestination != null) {
                        planned("${opcode.shortName()}(${address.id})->${nextLocalDestination.id}")
                        if (next!!.opcode() == OpCode.LOCAL_TEE) {
                            stack += OperandSource.Local
                        }
                        index += 2
                    } else {
                        planned("${opcode.shortName()}(${address.id})->temp")
                        stack += OperandSource.Temp(tempSlot++)
                        index++
                    }
                }

                opcode.isMemoryStore() -> {
                    val value = popSource()
                    val address = popSource()
                    planned("${opcode.shortName()}(${address.id},${value.id})")
                    index++
                }

                opcode == OpCode.LOCAL_SET -> {
                    val value = popSource()
                    val destination = instruction.localDestination()
                    planned("local_set(${value.id})->${destination?.id ?: "v128"}")
                    index++
                }

                opcode == OpCode.LOCAL_TEE -> {
                    val value = popSource()
                    val destination = instruction.localDestination()
                    planned("local_tee(${value.id})->${destination?.id ?: "v128"}")
                    if (destination != null) {
                        stack += OperandSource.Local
                    } else {
                        stack += OperandSource.Unknown
                    }
                    index++
                }

                opcode == OpCode.SELECT -> {
                    val pred = popSource()
                    val b = popSource()
                    val a = popSource()
                    planned("select(${a.id},${b.id},${pred.id})->temp")
                    stack += OperandSource.Temp(tempSlot++)
                    index++
                }

                opcode == OpCode.BR_IF || opcode == OpCode.IF || opcode == OpCode.BR_TABLE -> {
                    val pred = popSource()
                    materializeStack(opcode.shortName())
                    planned("${opcode.shortName()}(${pred.id})")
                    index++
                }

                opcode == OpCode.CALL -> {
                    materializeStack("call")
                    planned("call")
                    index++
                }

                opcode == OpCode.GLOBAL_GET -> {
                    planned("global_get->temp")
                    stack += OperandSource.Temp(tempSlot++)
                    index++
                }

                opcode == OpCode.GLOBAL_SET -> {
                    val value = popSource()
                    planned("global_set(${value.id})")
                    index++
                }

                opcode.isControlBarrier() -> {
                    materializeStack(opcode.shortName())
                    planned(opcode.shortName())
                    index++
                }

                else -> {
                    materializeStack("unsupported_${opcode.shortName()}")
                    planned("unsupported_${opcode.shortName()}")
                    unsupported++
                    index++
                }
            }
        }
        materializeStack("function_end")

        return FrameSlotFunctionPlan(
            bodyIndex = bodyIndex,
            functionIndex = functionIndex,
            rawInstructions = body.instructions().size,
            plannedDispatches = plannedDispatches,
            elidedSources = elidedSources,
            materializations = materializations,
            unsupported = unsupported,
            patterns = patterns,
        )
    }

    private fun planned(pattern: String) {
        plannedDispatches++
        patterns.increment(pattern)
    }

    private fun materializeStack(reason: String) {
        if (stack.isEmpty()) return
        materializations += stack.size
        plannedDispatches += stack.size
        patterns.increment("materialize($reason)")
        stack.clear()
    }

    private fun popSource(): OperandSource =
        if (stack.isEmpty()) {
            materializations++
            OperandSource.Unknown
        } else {
            stack.removeAt(stack.lastIndex)
        }

    private fun localSlot(instruction: AnnotatedInstruction): Int? {
        val localIndex = instruction.operand(0).toInt()
        if (localIndex !in localSlots.indices) return null
        return localSlots[localIndex].takeIf { it >= 0 }
    }

    private fun AnnotatedInstruction.localDestination(): OperandSource? =
        localSlot(this)?.let { OperandSource.Local }
}

private data class FrameSlotPlanReport(
    val functions: List<FrameSlotFunctionPlan>,
) {
    val rawInstructions: Int = functions.sumOf { it.rawInstructions }
    val plannedDispatches: Int = functions.sumOf { it.plannedDispatches }
    val elidedSources: Int = functions.sumOf { it.elidedSources }
    val materializations: Int = functions.sumOf { it.materializations }
    val unsupported: Int = functions.sumOf { it.unsupported }
    val patterns: Map<String, Int> =
        HashMap<String, Int>().also { merged ->
            for (function in functions) {
                for ((pattern, count) in function.patterns) {
                    merged[pattern] = (merged[pattern] ?: 0) + count
                }
            }
        }
}

private data class FrameSlotFunctionPlan(
    val bodyIndex: Int,
    val functionIndex: Int,
    val rawInstructions: Int,
    val plannedDispatches: Int,
    val elidedSources: Int,
    val materializations: Int,
    val unsupported: Int,
    val patterns: Map<String, Int>,
)

private sealed class OperandSource(val id: String) {
    data object Local : OperandSource("local")
    data object Const : OperandSource("const")
    data class Temp(val slot: Int) : OperandSource("temp")
    data object Unknown : OperandSource("stack")
}

private data class FrameSlotRankedEntry(
    val name: String,
    val count: Int,
) {
    fun formatInline(): String = "$name:$count"
}

private fun OpCode.isConst(): Boolean =
    this == OpCode.I32_CONST ||
        this == OpCode.I64_CONST ||
        this == OpCode.F32_CONST ||
        this == OpCode.F64_CONST

private fun OpCode.isBinaryI32(): Boolean =
    this == OpCode.I32_ADD ||
        this == OpCode.I32_SUB ||
        this == OpCode.I32_MUL ||
        this == OpCode.I32_DIV_U ||
        this == OpCode.I32_REM_S ||
        this == OpCode.I32_AND ||
        this == OpCode.I32_OR ||
        this == OpCode.I32_XOR ||
        this == OpCode.I32_SHL ||
        this == OpCode.I32_SHR_S ||
        this == OpCode.I32_SHR_U ||
        this == OpCode.I32_EQ ||
        this == OpCode.I32_NE ||
        this == OpCode.I32_LT_S ||
        this == OpCode.I32_LT_U ||
        this == OpCode.I32_GT_S ||
        this == OpCode.I32_GT_U ||
        this == OpCode.I32_LE_S ||
        this == OpCode.I32_LE_U ||
        this == OpCode.I32_GE_S ||
        this == OpCode.I32_GE_U ||
        this == OpCode.F64_DIV ||
        this == OpCode.F64_LT ||
        this == OpCode.F64_GE

private fun OpCode.isUnaryI32(): Boolean =
    this == OpCode.I32_EQZ

private fun OpCode.isUnaryNumericConversion(): Boolean =
    this == OpCode.F64_CONVERT_I64_U ||
        this == OpCode.F64_CONVERT_I32_U ||
        this == OpCode.I32_TRUNC_F64_U ||
        this == OpCode.F32_DEMOTE_F64

private fun OpCode.isMemoryLoad(): Boolean =
    this == OpCode.I32_LOAD ||
        this == OpCode.I32_LOAD8_U ||
        this == OpCode.I32_LOAD16_S ||
        this == OpCode.I32_LOAD16_U ||
        this == OpCode.I64_LOAD

private fun OpCode.isMemoryStore(): Boolean =
    this == OpCode.I32_STORE ||
        this == OpCode.I32_STORE8 ||
        this == OpCode.I32_STORE16 ||
        this == OpCode.I64_STORE

private fun OpCode.isControlBarrier(): Boolean =
    this == OpCode.NOP ||
        this == OpCode.UNREACHABLE ||
        this == OpCode.BLOCK ||
        this == OpCode.LOOP ||
        this == OpCode.ELSE ||
        this == OpCode.END ||
        this == OpCode.BR ||
        this == OpCode.RETURN

private fun OpCode.shortName(): String = name.lowercase(Locale.ROOT)

private fun localSlots(localTypes: List<ValType>): IntArray {
    val slots = IntArray(localTypes.size)
    var slot = 0
    for (index in localTypes.indices) {
        slots[index] = slot
        slot += if (localTypes[index] == ValType.V128) 2 else 1
    }
    return slots
}

private fun HashMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun Map<String, Int>.toFrameSlotRankedEntries(): List<FrameSlotRankedEntry> =
    entries
        .map { FrameSlotRankedEntry(it.key, it.value) }
        .sortedWith(compareByDescending<FrameSlotRankedEntry> { it.count }.thenBy { it.name })

private fun printFrameSlotRanked(title: String, entries: List<FrameSlotRankedEntry>, top: Int) {
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
