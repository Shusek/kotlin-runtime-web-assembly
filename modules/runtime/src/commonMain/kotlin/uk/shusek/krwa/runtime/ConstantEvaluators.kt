package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.MalformedException
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.OpCode.GLOBAL_GET
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

object ConstantEvaluators {
    fun computeConstantValue(instance: Instance, expr: Array<Instruction>): LongArray =
        computeConstantValue(instance, expr.asList())

    fun computeConstantValue(instance: Instance, expr: List<Instruction>): LongArray {
        val stack = ArrayDeque<LongArray>()
        for (instruction in expr) {
            when (instruction.opcode()) {
                OpCode.I32_ADD -> {
                    val x = stack.removeLast()[0].toInt()
                    val y = stack.removeLast()[0].toInt()
                    stack.addLast(longArrayOf((x + y).toLong()))
                }
                OpCode.I32_SUB -> {
                    val x = stack.removeLast()[0].toInt()
                    val y = stack.removeLast()[0].toInt()
                    stack.addLast(longArrayOf((y - x).toLong()))
                }
                OpCode.I32_MUL -> {
                    val x = stack.removeLast()[0].toInt()
                    val y = stack.removeLast()[0].toInt()
                    val result = x * y
                    stack.addLast(longArrayOf(result.toLong()))
                }
                OpCode.I64_ADD -> {
                    val x = stack.removeLast()[0]
                    val y = stack.removeLast()[0]
                    stack.addLast(longArrayOf(x + y))
                }
                OpCode.I64_SUB -> {
                    val x = stack.removeLast()[0]
                    val y = stack.removeLast()[0]
                    stack.addLast(longArrayOf(y - x))
                }
                OpCode.I64_MUL -> {
                    val x = stack.removeLast()[0]
                    val y = stack.removeLast()[0]
                    stack.addLast(longArrayOf(x * y))
                }
                OpCode.V128_CONST ->
                    stack.addLast(longArrayOf(instruction.operand(0), instruction.operand(1)))
                OpCode.F32_CONST,
                OpCode.F64_CONST,
                OpCode.I32_CONST,
                OpCode.I64_CONST,
                OpCode.REF_FUNC -> stack.addLast(longArrayOf(instruction.operand(0)))
                OpCode.REF_NULL -> stack.addLast(longArrayOf(Value.REF_NULL_VALUE.toLong()))
                GLOBAL_GET -> {
                    val idx = instruction.operand(0).toInt()
                    val global = instance.global(idx) ?: throw InvalidException("unknown global")
                    if (global.type == ValType.V128) {
                        stack.addLast(longArrayOf(global.valueLow, global.valueHigh))
                    } else {
                        stack.addLast(longArrayOf(global.valueLow))
                    }
                }
                OpCode.REF_I31 -> {
                    val value = stack.removeLast()[0].toInt()
                    stack.addLast(longArrayOf(Value.encodeI31(value)))
                }
                OpCode.STRUCT_NEW -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val structType =
                        instance
                            .module()
                            .typeSection()
                            .getSubType(typeIdx)
                            .compType()
                            .structType()!!
                    val fieldCount = structType.fieldTypes().size
                    val fields = LongArray(fieldCount)
                    for (i in fieldCount - 1 downTo 0) {
                        fields[i] = stack.removeLast()[0]
                    }
                    val struct = WasmStruct(typeIdx, fields)
                    val refId = instance.registerGcRef(struct)
                    stack.addLast(longArrayOf(refId.toLong()))
                }
                OpCode.STRUCT_NEW_DEFAULT -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val structType =
                        instance
                            .module()
                            .typeSection()
                            .getSubType(typeIdx)
                            .compType()
                            .structType()!!
                    val fieldCount = structType.fieldTypes().size
                    val fields = LongArray(fieldCount)
                    for (i in 0 until fieldCount) {
                        val fieldType = structType.fieldTypes()[i]
                        val valType = fieldType.storageType().valType()
                        if (valType != null && valType.isReference()) {
                            fields[i] = Value.REF_NULL_VALUE.toLong()
                        }
                    }
                    val struct = WasmStruct(typeIdx, fields)
                    val refId = instance.registerGcRef(struct)
                    stack.addLast(longArrayOf(refId.toLong()))
                }
                OpCode.ARRAY_NEW -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val len = stack.removeLast()[0].toInt()
                    val fillValue = stack.removeLast()[0]
                    val elements = LongArray(len)
                    elements.fill(fillValue)
                    val array = WasmArray(typeIdx, elements)
                    val refId = instance.registerGcRef(array)
                    stack.addLast(longArrayOf(refId.toLong()))
                }
                OpCode.ARRAY_NEW_DEFAULT -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val len = stack.removeLast()[0].toInt()
                    val arrayType =
                        instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
                    val elements = LongArray(len)
                    val valType = arrayType.fieldType().storageType().valType()
                    if (valType != null && valType.isReference()) {
                        elements.fill(Value.REF_NULL_VALUE.toLong())
                    }
                    val array = WasmArray(typeIdx, elements)
                    val refId = instance.registerGcRef(array)
                    stack.addLast(longArrayOf(refId.toLong()))
                }
                OpCode.ARRAY_NEW_FIXED -> {
                    val typeIdx = instruction.operand(0).toInt()
                    val len = instruction.operand(1).toInt()
                    val elements = LongArray(len)
                    for (i in len - 1 downTo 0) {
                        elements[i] = stack.removeLast()[0]
                    }
                    val array = WasmArray(typeIdx, elements)
                    val refId = instance.registerGcRef(array)
                    stack.addLast(longArrayOf(refId.toLong()))
                }
                OpCode.ANY_CONVERT_EXTERN,
                OpCode.EXTERN_CONVERT_ANY,
                OpCode.END -> {
                    // Identity operations at runtime.
                }
                else ->
                    throw MalformedException("Invalid instruction in constant value$instruction")
            }
        }

        return stack.removeLast()
    }

    fun computeConstantInstance(instance: Instance, expr: List<Instruction>): Instance {
        for (instruction in expr) {
            if (instruction.opcode() == GLOBAL_GET) {
                return instance.global(instruction.operand(0).toInt()).instance!!
            }
        }
        return instance
    }
}
