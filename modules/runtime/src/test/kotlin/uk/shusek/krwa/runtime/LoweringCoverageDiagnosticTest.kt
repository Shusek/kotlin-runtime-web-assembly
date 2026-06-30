package uk.shusek.krwa.runtime

import java.io.File
import kotlin.test.Test
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

class LoweringCoverageDiagnosticTest {
    @Test
    fun printLoweringCoverageForConfiguredWasm() {
        val path = System.getProperty("krwa.loweringCoverageWasm")
            ?: System.getenv("KRWA_LOWERING_COVERAGE_WASM")
            ?: return
        val file = File(path)
        require(file.isFile) { "krwa.loweringCoverageWasm is not a file: $path" }

        val module = Parser.parse(file)
        val functionImports = module.importSection().count(ExternalType.FUNCTION)
        val bodies = module.codeSection().functionBodies()
        val inspectedFunctions = inspectFunctionIds()
        if (inspectedFunctions.isNotEmpty()) {
            printInspectedFunctions(inspectedFunctions, functionImports, module, bodies)
        }
        var loweredFunctions = 0
        var loweredInstructions = 0
        var totalInstructions = 0
        val opcodeCounts = HashMap<OpCode, Int>()
        val firstUnsupportedCounts = HashMap<String, Int>()
        val largestUnsupported = ArrayList<UnsupportedFunction>()

        for (bodyIndex in bodies.indices) {
            val functionIndex = functionImports + bodyIndex
            val typeIndex = module.functionSection().getFunctionType(bodyIndex)
            val type = module.typeSection().getType(typeIndex)
            val body = bodies[bodyIndex]
            val instructions = body.instructions()
            totalInstructions += instructions.size
            for (instruction in instructions) {
                opcodeCounts[instruction.opcode()] = opcodeCounts.getOrDefault(instruction.opcode(), 0) + 1
            }

            val localTypes = (type.params() + body.localTypes()).toTypedArray()
            val localIdx = localSlotsFor(localTypes)
            val lowered = LoweredFunction.tryBuild(instructions.toTypedArray(), localIdx, localTypes)
            if (lowered != null) {
                loweredFunctions++
                loweredInstructions += instructions.size
            } else {
                val reason = firstUnsupportedReason(type, body)
                firstUnsupportedCounts[reason] = firstUnsupportedCounts.getOrDefault(reason, 0) + 1
                largestUnsupported += UnsupportedFunction(
                    functionIndex = functionIndex,
                    name = module.nameSection()?.nameOfFunction(functionIndex),
                    instructionCount = instructions.size,
                    reason = reason,
                )
            }
        }

        println(
            "KRWA lowering coverage file=${file.name} " +
                "functions=${bodies.size} loweredFunctions=$loweredFunctions " +
                "loweredFunctionPercent=${percent(loweredFunctions, bodies.size)} " +
                "instructions=$totalInstructions loweredInstructions=$loweredInstructions " +
                "loweredInstructionPercent=${percent(loweredInstructions, totalInstructions)}",
        )
        println("KRWA lowering first unsupported reasons:")
        for ((reason, count) in firstUnsupportedCounts.entries.sortedByDescending { it.value }.take(30)) {
            println("  $count $reason")
        }
        println("KRWA lowering largest unsupported functions:")
        for (function in largestUnsupported.sortedByDescending { it.instructionCount }.take(30)) {
            val name = function.name ?: "<unnamed>"
            println("  #${function.functionIndex} instructions=${function.instructionCount} reason=${function.reason} name=$name")
        }
        println("KRWA lowering static opcode counts:")
        for ((opcode, count) in opcodeCounts.entries.sortedByDescending { it.value }.take(40)) {
            println("  $count $opcode")
        }
    }

    private fun inspectFunctionIds(): List<Int> {
        val value = System.getProperty("krwa.inspectFunctions")
            ?: System.getenv("KRWA_INSPECT_FUNCTIONS")
            ?: return emptyList()
        return value.split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
    }

    private fun printInspectedFunctions(
        functionIds: List<Int>,
        functionImports: Int,
        module: uk.shusek.krwa.wasm.WasmModule,
        bodies: Array<FunctionBody>,
    ) {
        println("KRWA inspected functions:")
        for (functionId in functionIds) {
            val bodyIndex = functionId - functionImports
            if (bodyIndex !in bodies.indices) {
                println("  #$functionId imported-or-missing")
                continue
            }
            val body = bodies[bodyIndex]
            val typeIndex = module.functionSection().getFunctionType(bodyIndex)
            val type = module.typeSection().getType(typeIndex)
            val name = module.nameSection()?.nameOfFunction(functionId) ?: "<unnamed>"
            println(
                "  #$functionId bodyIndex=$bodyIndex name=$name " +
                    "type=$typeIndex params=${type.params()} returns=${type.returns()} " +
                    "locals=${body.localTypes()} instructions=${body.instructions().size}",
            )
            body.instructions().forEachIndexed { index, instruction ->
                println(
                    "    $index ${instruction.opcode()} operands=${instruction.operands().contentToString()} " +
                        "labels=(${instruction.labelTrue()},${instruction.labelFalse()}) table=${instruction.labelTable()}",
                )
            }
        }
    }

    private data class UnsupportedFunction(
        val functionIndex: Int,
        val name: String?,
        val instructionCount: Int,
        val reason: String,
    )

    private fun firstUnsupportedReason(type: FunctionType, body: FunctionBody): String {
        val localTypes = type.params() + body.localTypes()
        for (instruction in body.instructions()) {
            when (instruction.opcode()) {
                OpCode.LOOP -> {
                    if (instruction.operand(0).toInt() != EMPTY_BLOCK_TYPE) {
                        return "loop-block-type:${instruction.operand(0)}"
                    }
                }
                OpCode.LOCAL_GET,
                OpCode.LOCAL_SET,
                OpCode.LOCAL_TEE,
                -> {
                    val localIndex = instruction.operand(0).toInt()
                    if (localIndex !in localTypes.indices) {
                        return "${instruction.opcode()}:invalid-local"
                    }
                    if (localTypes[localIndex] == ValType.V128) {
                        return "${instruction.opcode()}:v128-local"
                    }
                }
                OpCode.SELECT -> {
                    if (instruction.operand(0) == ValType.ID.V128.toLong()) {
                        return "SELECT:v128"
                    }
                }
                else -> {
                    if (instruction.opcode() !in loweredSupportedOpcodes) {
                        return instruction.opcode().name
                    }
                }
            }
        }
        return "unknown"
    }

    private fun localSlotsFor(types: Array<ValType>): IntArray {
        val slots = IntArray(types.size)
        var slot = 0
        for (index in types.indices) {
            slots[index] = slot
            slot += if (types[index] == ValType.V128) 2 else 1
        }
        return slots
    }

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "0.00%" else "%.2f%%".format(value * 100.0 / total)

    private companion object {
        private const val EMPTY_BLOCK_TYPE = 0x40

        private val loweredSupportedOpcodes = setOf(
            OpCode.NOP,
            OpCode.UNREACHABLE,
            OpCode.BLOCK,
            OpCode.LOOP,
            OpCode.IF,
            OpCode.ELSE,
            OpCode.END,
            OpCode.BR,
            OpCode.BR_IF,
            OpCode.BR_TABLE,
            OpCode.RETURN,
            OpCode.CALL,
            OpCode.LOCAL_GET,
            OpCode.LOCAL_SET,
            OpCode.LOCAL_TEE,
            OpCode.I32_CONST,
            OpCode.I64_CONST,
            OpCode.F64_CONST,
            OpCode.I32_ADD,
            OpCode.I32_SUB,
            OpCode.I32_MUL,
            OpCode.I32_AND,
            OpCode.I32_OR,
            OpCode.I32_XOR,
            OpCode.I32_SHL,
            OpCode.I32_SHR_S,
            OpCode.I32_SHR_U,
            OpCode.I32_EQZ,
            OpCode.I32_EQ,
            OpCode.I32_NE,
            OpCode.I32_LT_S,
            OpCode.I32_LT_U,
            OpCode.I32_GT_S,
            OpCode.I32_GT_U,
            OpCode.I32_LE_S,
            OpCode.I32_LE_U,
            OpCode.I32_GE_S,
            OpCode.I32_GE_U,
            OpCode.I32_DIV_U,
            OpCode.I32_REM_S,
            OpCode.I64_SUB,
            OpCode.I32_LOAD,
            OpCode.I32_LOAD8_U,
            OpCode.I32_LOAD16_S,
            OpCode.I32_LOAD16_U,
            OpCode.I64_LOAD,
            OpCode.I32_STORE,
            OpCode.I32_STORE8,
            OpCode.I32_STORE16,
            OpCode.I64_STORE,
            OpCode.SELECT,
            OpCode.GLOBAL_GET,
            OpCode.GLOBAL_SET,
            OpCode.F64_DIV,
            OpCode.F64_LT,
            OpCode.F64_GE,
            OpCode.F64_CONVERT_I64_U,
            OpCode.F64_CONVERT_I32_U,
            OpCode.I32_TRUNC_F64_U,
            OpCode.F32_DEMOTE_F64,
        )
    }
}
