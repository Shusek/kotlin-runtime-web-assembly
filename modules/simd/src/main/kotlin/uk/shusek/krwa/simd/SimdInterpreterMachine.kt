package uk.shusek.krwa.simd

import java.util.Arrays
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.Vector
import uk.shusek.krwa.runtime.BitOps
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.MStack
import uk.shusek.krwa.runtime.OpcodeImpl
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.Value

class SimdInterpreterMachine(instance: Instance) : InterpreterMachine(instance) {
    override fun isInterrupted(): Boolean = Thread.currentThread().isInterrupted

    @Throws(WasmEngineException::class)
    override fun evalDefault(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        instruction: Instruction,
        operands: Operands,
    ) {
        when (instruction.opcode()) {
            OpCode.V128_CONST -> {
                V128_CONST(stack, operands)
            }
            OpCode.V128_LOAD -> {
                V128_LOAD(stack, instance, operands)
            }
            OpCode.V128_LOAD32_ZERO -> {
                V128_LOAD32_ZERO(stack, instance, operands)
            }
            OpCode.V128_LOAD64_ZERO -> {
                V128_LOAD64_ZERO(stack, instance, operands)
            }
            OpCode.V128_LOAD8_LANE -> {
                LOAD_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        v.reinterpretAsBytes()
                            .withLane(
                                operands.get(3).toInt(),
                                instance.memory(operands.get(2).toInt()).read(ptr),
                            )
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.V128_LOAD16_LANE -> {
                LOAD_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        v.reinterpretAsShorts()
                            .withLane(
                                operands.get(3).toInt(),
                                instance.memory(operands.get(2).toInt()).readShort(ptr),
                            )
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.V128_LOAD32_LANE -> {
                LOAD_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        v.reinterpretAsInts()
                            .withLane(
                                operands.get(3).toInt(),
                                instance.memory(operands.get(2).toInt()).readInt(ptr),
                            )
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.V128_LOAD64_LANE -> {
                LOAD_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        v.withLane(
                            operands.get(3).toInt(),
                            instance.memory(operands.get(2).toInt()).readLong(ptr),
                        )
                    },
                )
            }
            OpCode.V128_LOAD8x8_S -> {
                V128_LOAD8x8_S(stack, instance, operands)
            }
            OpCode.V128_LOAD8x8_U -> {
                V128_LOAD8x8_U(stack, instance, operands)
            }
            OpCode.V128_LOAD16x4_S -> {
                V128_LOAD16x4_S(stack, instance, operands)
            }
            OpCode.V128_LOAD16x4_U -> {
                V128_LOAD16x4_U(stack, instance, operands)
            }
            OpCode.V128_LOAD32x2_S -> {
                V128_LOAD32x2_S(stack, instance, operands)
            }
            OpCode.V128_LOAD32x2_U -> {
                V128_LOAD32x2_U(stack, instance, operands)
            }
            OpCode.V128_STORE -> {
                V128_STORE(stack, instance, operands)
            }
            OpCode.V128_LOAD8_SPLAT -> {
                V128_LOAD8_SPLAT(stack, instance, operands)
            }
            OpCode.V128_LOAD16_SPLAT -> {
                V128_LOAD16_SPLAT(stack, instance, operands)
            }
            OpCode.V128_LOAD32_SPLAT -> {
                V128_LOAD32_SPLAT(stack, instance, operands)
            }
            OpCode.V128_LOAD64_SPLAT -> {
                V128_LOAD64_SPLAT(stack, instance, operands)
            }
            OpCode.I8x16_SHUFFLE -> {
                I8x16_SHUFFLE(stack, operands)
            }
            OpCode.I8x16_SPLAT -> {
                I8x16_SPLAT(stack)
            }
            OpCode.I16x8_SPLAT -> {
                I16x8_SPLAT(stack)
            }
            OpCode.I32x4_SPLAT -> {
                I32x4_SPLAT(stack)
            }
            OpCode.F32x4_SPLAT -> {
                F32x4_SPLAT(stack)
            }
            OpCode.I64x2_SPLAT -> {
                I64x2_SPLAT(stack)
            }
            OpCode.F64x2_SPLAT -> {
                F64x2_SPLAT(stack)
            }
            OpCode.V128_STORE8_LANE -> {
                STORE_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        instance
                            .memory(operands.get(2).toInt())
                            .writeByte(ptr, v.reinterpretAsBytes().lane(operands.get(3).toInt()))
                    },
                )
            }
            OpCode.V128_STORE16_LANE -> {
                STORE_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        instance
                            .memory(operands.get(2).toInt())
                            .writeShort(ptr, v.reinterpretAsShorts().lane(operands.get(3).toInt()))
                    },
                )
            }
            OpCode.V128_STORE32_LANE -> {
                STORE_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        instance
                            .memory(operands.get(2).toInt())
                            .writeI32(ptr, v.reinterpretAsInts().lane(operands.get(3).toInt()))
                    },
                )
            }
            OpCode.V128_STORE64_LANE -> {
                STORE_LANE(
                    stack,
                    operands,
                    { v, ptr ->
                        instance
                            .memory(operands.get(2).toInt())
                            .writeLong(ptr, v.reinterpretAsLongs().lane(operands.get(3).toInt()))
                    },
                )
            }
            OpCode.I8x16_REPLACE_LANE -> {
                REPLACE_LANE(
                    stack,
                    { v, value ->
                        v.reinterpretAsBytes()
                            .withLane(operands.get(0).toInt(), value.toByte())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I16x8_REPLACE_LANE -> {
                REPLACE_LANE(
                    stack,
                    { v, value ->
                        v.reinterpretAsShorts()
                            .withLane(operands.get(0).toInt(), value.toShort())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I32x4_REPLACE_LANE -> {
                REPLACE_LANE(
                    stack,
                    { v, value ->
                        v.reinterpretAsInts()
                            .withLane(operands.get(0).toInt(), value.toInt())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.F32x4_REPLACE_LANE -> {
                REPLACE_LANE(
                    stack,
                    { v, value ->
                        v.reinterpretAsFloats()
                            .withLane(operands.get(0).toInt(), Value.longToFloat(value))
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I64x2_REPLACE_LANE -> {
                REPLACE_LANE(stack, { v, value -> v.withLane(operands.get(0).toInt(), value) })
            }
            OpCode.F64x2_REPLACE_LANE -> {
                REPLACE_LANE(
                    stack,
                    { v, value ->
                        v.reinterpretAsDoubles()
                            .withLane(operands.get(0).toInt(), Value.longToDouble(value))
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I8x16_EXTRACT_LANE_U -> {
                I8x16_EXTRACT_LANE_U(stack, operands)
            }
            OpCode.I16x8_EXTRACT_LANE_U -> {
                I16x8_EXTRACT_LANE_U(stack, operands)
            }
            OpCode.I8x16_EXTRACT_LANE_S -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v -> v.reinterpretAsBytes().lane(operands.get(0).toInt()).toLong() },
                )
            }
            OpCode.I16x8_EXTRACT_LANE_S -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v -> v.reinterpretAsShorts().lane(operands.get(0).toInt()).toLong() },
                )
            }
            OpCode.I32x4_EXTRACT_LANE -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v -> v.reinterpretAsInts().lane(operands.get(0).toInt()).toLong() },
                )
            }
            OpCode.F32x4_EXTRACT_LANE -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v ->
                        Value.floatToLong(v.reinterpretAsFloats().lane(operands.get(0).toInt()))
                    },
                )
            }
            OpCode.I64x2_EXTRACT_LANE -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v -> v.reinterpretAsLongs().lane(operands.get(0).toInt()) },
                )
            }
            OpCode.F64x2_EXTRACT_LANE -> {
                EXTRACT_LANE(
                    stack,
                    operands,
                    { v ->
                        Value.doubleToLong(v.reinterpretAsDoubles().lane(operands.get(0).toInt()))
                    },
                )
            }
            OpCode.V128_NOT -> {
                V128_NOT(stack)
            }
            OpCode.V128_AND -> {
                V128_BINOP(stack, { v1, v2 -> v1.and(v2) })
            }
            OpCode.V128_ANDNOT -> {
                V128_BINOP(stack, { v1, v2 -> v1.not().and(v2) })
            }
            OpCode.V128_OR -> {
                V128_BINOP(stack, { v1, v2 -> v1.or(v2) })
            }
            OpCode.V128_XOR -> {
                V128_BINOP(stack, { v1, v2 -> v1.and(v2.not()).or(v1.not().and(v2)) })
            }
            OpCode.V128_BITSELECT -> {
                V128_BITSELECT(stack)
            }
            OpCode.V128_ANY_TRUE -> {
                V128_ANY_TRUE(stack)
            }
            OpCode.I8x16_EQ -> {
                BINOP(stack, LongVector::reinterpretAsBytes, { v1, v2 -> v1.eq(v2).toVector() })
            }
            OpCode.I16x8_EQ -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v1.eq(v2).toVector() })
            }
            OpCode.I32x4_EQ -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v1.eq(v2).toVector() })
            }
            OpCode.F64x2_EQ -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v1.eq(v2).toVector() })
            }
            OpCode.I8x16_SUB -> {
                I8x16_SUB(stack)
            }
            OpCode.I8x16_SWIZZLE -> {
                I8x16_SWIZZLE(stack)
            }
            OpCode.I8x16_ALL_TRUE -> {
                BOOL_OP(
                    stack,
                    { v ->
                        v.reinterpretAsBytes().compare(VectorOperators.NE, 0.toByte()).allTrue()
                    },
                )
            }
            OpCode.I16x8_ALL_TRUE -> {
                BOOL_OP(
                    stack,
                    { v ->
                        v.reinterpretAsShorts().compare(VectorOperators.NE, 0.toShort()).allTrue()
                    },
                )
            }
            OpCode.I32x4_ALL_TRUE -> {
                BOOL_OP(
                    stack,
                    { v -> v.reinterpretAsInts().compare(VectorOperators.NE, 0).allTrue() },
                )
            }
            OpCode.I64x2_ALL_TRUE -> {
                BOOL_OP(stack, { v -> v.compare(VectorOperators.NE, 0).allTrue() })
            }
            OpCode.I8x16_BITMASK -> {
                BITMASK(stack, { v -> v.reinterpretAsBytes().toLongArray() })
            }
            OpCode.I16x8_BITMASK -> {
                BITMASK(stack, { v -> v.reinterpretAsShorts().toLongArray() })
            }
            OpCode.I32x4_BITMASK -> {
                BITMASK(stack, { v -> v.reinterpretAsInts().toLongArray() })
            }
            OpCode.I64x2_BITMASK -> {
                BITMASK(stack, { v -> v.toLongArray() })
            }
            OpCode.I8x16_SHL -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsBytes()
                            .lanewise(VectorOperators.LSHL, s.toByte())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I8x16_SHR_U -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsBytes()
                            .lanewise(VectorOperators.LSHR, s.toByte())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I8x16_SHR_S -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsBytes()
                            .lanewise(VectorOperators.ASHR, s.toByte())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I8x16_ADD -> {
                BINOP(stack, LongVector::reinterpretAsBytes, { v1, v2 -> v1.add(v2) })
            }
            OpCode.I8x16_ADD_SAT_S -> {
                I8x16_ADD_SAT_S(stack)
            }
            OpCode.I8x16_ADD_SAT_U -> {
                I8x16_ADD_SAT_U(stack)
            }
            OpCode.I8x16_SUB_SAT_U -> {
                I8x16_SUB_SAT_U(stack)
            }
            OpCode.I8x16_SUB_SAT_S -> {
                I8x16_SUB_SAT_S(stack)
            }
            OpCode.I8x16_MIN_S -> {
                BINOP(stack, LongVector::reinterpretAsBytes, { v1, v2 -> v1.min(v2) })
            }
            OpCode.I8x16_MAX_S -> {
                BINOP(stack, LongVector::reinterpretAsBytes, { v1, v2 -> v1.max(v2) })
            }
            OpCode.I8x16_MAX_U -> {
                I8x16(
                    stack,
                    { a, b ->
                        Math.max(java.lang.Byte.toUnsignedInt(a), java.lang.Byte.toUnsignedInt(b))
                            .toLong()
                    },
                )
            }
            OpCode.I8x16_MIN_U -> {
                I8x16(
                    stack,
                    { a, b ->
                        Math.min(java.lang.Byte.toUnsignedInt(a), java.lang.Byte.toUnsignedInt(b))
                            .toLong()
                    },
                )
            }
            OpCode.I8x16_AVGR_U -> {
                I8x16(
                    stack,
                    { a, b ->
                        ((java.lang.Byte.toUnsignedInt(a) + java.lang.Byte.toUnsignedInt(b) + 1) /
                                2)
                            .toLong()
                    },
                )
            }
            OpCode.I8x16_ABS -> {
                UNARY(stack, LongVector::reinterpretAsBytes, { v -> v.abs() })
            }
            OpCode.I8x16_NEG -> {
                UNARY(stack, LongVector::reinterpretAsBytes, { v -> v.neg() })
            }
            OpCode.I8x16_NE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v1.eq(v2).not().toVector() },
                )
            }
            OpCode.I8x16_LT_S -> {
                BINOP(stack, LongVector::reinterpretAsBytes, { v1, v2 -> v2.lt(v1).toVector() })
            }
            OpCode.I8x16_LT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LT, v1).toVector() },
                )
            }
            OpCode.I8x16_LE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.LE, v1).toVector() },
                )
            }
            OpCode.I8x16_LE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LE, v1).toVector() },
                )
            }
            OpCode.I8x16_GT_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.GT, v1).toVector() },
                )
            }
            OpCode.I8x16_GT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GT, v1).toVector() },
                )
            }
            OpCode.I8x16_GE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.GE, v1).toVector() },
                )
            }
            OpCode.I8x16_GE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GE, v1).toVector() },
                )
            }
            OpCode.I8x16_POPCNT -> {
                UNARY(
                    stack,
                    LongVector::reinterpretAsBytes,
                    { v -> v.lanewise(VectorOperators.BIT_COUNT) },
                )
            }
            OpCode.I16x8_NEG -> {
                UNARY(stack, LongVector::reinterpretAsShorts, { v -> v.neg() })
            }
            OpCode.I16x8_NE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v1.eq(v2).not().toVector() },
                )
            }
            OpCode.I16x8_LT_S -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v2.lt(v1).toVector() })
            }
            OpCode.I16x8_LT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LT, v1).toVector() },
                )
            }
            OpCode.I16x8_LE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.LE, v1).toVector() },
                )
            }
            OpCode.I16x8_LE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LE, v1).toVector() },
                )
            }
            OpCode.I16x8_GT_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.GT, v1).toVector() },
                )
            }
            OpCode.I16x8_GT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GT, v1).toVector() },
                )
            }
            OpCode.I16x8_GE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.GE, v1).toVector() },
                )
            }
            OpCode.I16x8_GE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsShorts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GE, v1).toVector() },
                )
            }
            OpCode.I16x8_MIN_S -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v1.min(v2) })
            }
            OpCode.I16x8_MAX_S -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v1.max(v2) })
            }
            OpCode.I16x8_MAX_U -> {
                I16x8(
                    stack,
                    { a, b ->
                        Math.max(java.lang.Short.toUnsignedInt(a), java.lang.Short.toUnsignedInt(b))
                            .toLong()
                    },
                )
            }
            OpCode.I16x8_MIN_U -> {
                I16x8(
                    stack,
                    { a, b ->
                        Math.min(java.lang.Short.toUnsignedInt(a), java.lang.Short.toUnsignedInt(b))
                            .toLong()
                    },
                )
            }
            OpCode.I16x8_AVGR_U -> {
                I16x8(
                    stack,
                    { a, b ->
                        ((java.lang.Short.toUnsignedInt(a) + java.lang.Short.toUnsignedInt(b) + 1) /
                                2)
                            .toLong()
                    },
                )
            }
            OpCode.I16x8_ADD -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v1.add(v2) })
            }
            OpCode.I16x8_ADD_SAT_S -> {
                I16x8_ADD_SAT_S(stack)
            }
            OpCode.I16x8_ADD_SAT_U -> {
                I16x8_ADD_SAT_U(stack)
            }
            OpCode.I16x8_SUB_SAT_U -> {
                I16x8_SUB_SAT_U(stack)
            }
            OpCode.I16x8_SUB_SAT_S -> {
                I16x8_SUB_SAT_S(stack)
            }
            OpCode.I16x8_SUB -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v2.sub(v1) })
            }
            OpCode.I16x8_ABS -> {
                UNARY(stack, LongVector::reinterpretAsShorts, { v -> v.abs() })
            }
            OpCode.I32x4_ABS -> {
                UNARY(stack, LongVector::reinterpretAsInts, { v -> v.abs() })
            }
            OpCode.I32x4_NEG -> {
                UNARY(stack, LongVector::reinterpretAsInts, { v -> v.neg() })
            }
            OpCode.I32x4_NE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v1.eq(v2).not().toVector() },
                )
            }
            OpCode.I32x4_LT_S -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v2.lt(v1).toVector() })
            }
            OpCode.I32x4_LT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LT, v1).toVector() },
                )
            }
            OpCode.I32x4_LE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.LE, v1).toVector() },
                )
            }
            OpCode.I32x4_LE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_LE, v1).toVector() },
                )
            }
            OpCode.I32x4_GT_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.GT, v1).toVector() },
                )
            }
            OpCode.I32x4_GT_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GT, v1).toVector() },
                )
            }
            OpCode.I32x4_GE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.GE, v1).toVector() },
                )
            }
            OpCode.I32x4_GE_U -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsInts,
                    { v1, v2 -> v2.compare(VectorOperators.UNSIGNED_GE, v1).toVector() },
                )
            }
            OpCode.I32x4_MAX_S -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v1.max(v2) })
            }
            OpCode.I32x4_MAX_U -> {
                I32x4(
                    stack,
                    { a, b ->
                        Math.max(
                            java.lang.Integer.toUnsignedLong(a),
                            java.lang.Integer.toUnsignedLong(b),
                        )
                    },
                )
            }
            OpCode.I32x4_MIN_S -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v1.min(v2) })
            }
            OpCode.I32x4_MIN_U -> {
                I32x4(
                    stack,
                    { a, b ->
                        Math.min(
                            java.lang.Integer.toUnsignedLong(a),
                            java.lang.Integer.toUnsignedLong(b),
                        )
                    },
                )
            }
            OpCode.I32x4_DOT_I16x8_S -> {
                I32x4_DOT_I16x8_S(stack)
            }
            OpCode.I32x4_EXTMUL_LOW_I16x8_S -> {
                I32x4_EXTMUL_LOW_I16x8_S(stack)
            }
            OpCode.I32x4_EXTMUL_HIGH_I16x8_S -> {
                I32x4_EXTMUL_HIGH_I16x8_S(stack)
            }
            OpCode.I32x4_EXTMUL_LOW_I16x8_U -> {
                I32x4_EXTMUL_LOW_I16x8_U(stack)
            }
            OpCode.I32x4_EXTMUL_HIGH_I16x8_U -> {
                I32x4_EXTMUL_HIGH_I16x8_U(stack)
            }
            OpCode.I64x2_ABS -> {
                UNARY(stack, LongVector::reinterpretAsLongs, { v -> v.abs() })
            }
            OpCode.I64x2_NEG -> {
                UNARY(stack, LongVector::reinterpretAsLongs, { v -> v.neg() })
            }
            OpCode.I64x2_MUL -> {
                BINOP(stack, LongVector::reinterpretAsLongs, { v1, v2 -> v1.mul(v2) })
            }
            OpCode.I64x2_EQ -> {
                BINOP(stack, LongVector::reinterpretAsLongs, { v1, v2 -> v1.eq(v2).toVector() })
            }
            OpCode.I64x2_NE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsLongs,
                    { v1, v2 -> v1.compare(VectorOperators.NE, v2).toVector() },
                )
            }
            OpCode.I64x2_LT_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsLongs,
                    { v1, v2 -> v1.compare(VectorOperators.LT, v2).toVector() },
                )
            }
            OpCode.I64x2_LE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsLongs,
                    { v1, v2 -> v1.compare(VectorOperators.LE, v2).toVector() },
                )
            }
            OpCode.I64x2_GT_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsLongs,
                    { v1, v2 -> v1.compare(VectorOperators.GT, v2).toVector() },
                )
            }
            OpCode.I64x2_GE_S -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsLongs,
                    { v1, v2 -> v1.compare(VectorOperators.GE, v2).toVector() },
                )
            }
            OpCode.I64x2_SUB -> {
                BINOP(stack, LongVector::reinterpretAsLongs, { v1, v2 -> v2.sub(v1) })
            }
            OpCode.I64x2_EXTMUL_LOW_I32x4_S -> {
                I64x2_EXTMUL_LOW_I32x4_S(stack)
            }
            OpCode.I64x2_EXTMUL_HIGH_I32x4_S -> {
                I64x2_EXTMUL_HIGH_I32x4_S(stack)
            }
            OpCode.I64x2_EXTMUL_LOW_I32x4_U -> {
                I64x2_EXTMUL_LOW_I32x4_U(stack)
            }
            OpCode.I64x2_EXTMUL_HIGH_I32x4_U -> {
                I64x2_EXTMUL_HIGH_I32x4_U(stack)
            }
            OpCode.F32x4_EQ -> {
                F32x4(stack, { a, b -> if (OpcodeImpl.F32_EQ(a, b) > 0) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_ABS -> {
                UNARY(stack, LongVector::reinterpretAsFloats, { v -> v.abs() })
            }
            OpCode.F32x4_MIN -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v1.min(v2) })
            }
            OpCode.F32x4_MAX -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v1.max(v2) })
            }
            OpCode.F32x4_ADD -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v1.add(v2) })
            }
            OpCode.F32x4_SUB -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v2.sub(v1) })
            }
            OpCode.F32x4_MUL -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v1.mul(v2) })
            }
            OpCode.F32x4_DIV -> {
                BINOP(stack, LongVector::reinterpretAsFloats, { v1, v2 -> v2.div(v1) })
            }
            OpCode.F32x4_NEG -> {
                UNARY(stack, LongVector::reinterpretAsFloats, { v -> v.neg() })
            }
            OpCode.F32x4_SQRT -> {
                UNARY(
                    stack,
                    LongVector::reinterpretAsFloats,
                    { v -> v.lanewise(VectorOperators.SQRT) },
                )
            }
            OpCode.F32x4_LE -> {
                F32x4(stack, { a, b -> if (le(b, a)) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_LT -> {
                F32x4(stack, { a, b -> if (lt(b, a)) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_GE -> {
                F32x4(stack, { a, b -> if (ge(b, a)) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_GT -> {
                F32x4(stack, { a, b -> if (gt(b, a)) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_NE -> {
                F32x4(stack, { a, b -> if (!equals(a, b)) 0xFFFFFFFFL else 0x0L })
            }
            OpCode.F32x4_PMIN -> {
                F32x4(stack, { a, b -> Value.floatToLong(if (a < b) a else b) })
            }
            OpCode.F32x4_PMAX -> {
                F32x4(stack, { a, b -> Value.floatToLong(if (a > b) a else b) })
            }
            OpCode.F32x4_CEIL -> {
                F32x4(stack, { v -> Value.floatToLong(OpcodeImpl.F32_CEIL(v)) })
            }
            OpCode.F32x4_TRUNC -> {
                F32x4(stack, { v -> Value.floatToLong(OpcodeImpl.F32_TRUNC(v)) })
            }
            OpCode.F32x4_FLOOR -> {
                F32x4(stack, { v -> Value.floatToLong(OpcodeImpl.F32_FLOOR(v)) })
            }
            OpCode.F32x4_NEAREST -> {
                F32x4(stack, { v -> Value.floatToLong(OpcodeImpl.F32_NEAREST(v)) })
            }
            OpCode.F64x2_ADD -> {
                BINOP(stack, LongVector::reinterpretAsLongs, { v1, v2 -> v1.add(v2) })
            }
            OpCode.F64x2_MUL -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v1.mul(v2) })
            }
            OpCode.F64x2_DIV -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v1.div(v2) })
            }
            OpCode.F64x2_SUB -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v2.sub(v1) })
            }
            OpCode.F64x2_MIN -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v2.min(v1) })
            }
            OpCode.F64x2_MAX -> {
                BINOP(stack, LongVector::reinterpretAsDoubles, { v1, v2 -> v2.max(v1) })
            }
            OpCode.F64x2_ABS -> {
                UNARY(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v -> v.lanewise(VectorOperators.ABS) },
                )
            }
            OpCode.F64x2_SQRT -> {
                UNARY(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v -> v.lanewise(VectorOperators.SQRT) },
                )
            }
            OpCode.F64x2_NEG -> {
                UNARY(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v -> v.lanewise(VectorOperators.NEG) },
                )
            }
            OpCode.F64x2_NE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v1, v2 -> v2.compare(VectorOperators.NE, v1).toVector() },
                )
            }
            OpCode.F64x2_GE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v1, v2 -> v2.compare(VectorOperators.GE, v1).toVector() },
                )
            }
            OpCode.F64x2_LT -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v1, v2 -> v2.compare(VectorOperators.LT, v1).toVector() },
                )
            }
            OpCode.F64x2_LE -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v1, v2 -> v2.compare(VectorOperators.LE, v1).toVector() },
                )
            }
            OpCode.F64x2_GT -> {
                BINOP(
                    stack,
                    LongVector::reinterpretAsDoubles,
                    { v1, v2 -> v2.compare(VectorOperators.GT, v1).toVector() },
                )
            }
            OpCode.F64x2_PMIN -> {
                F64x2(stack, { a, b -> Value.doubleToLong(if (a < b) a else b) })
            }
            OpCode.F64x2_PMAX -> {
                F64x2(stack, { a, b -> Value.doubleToLong(if (a > b) a else b) })
            }
            OpCode.F64x2_CEIL -> {
                F64x2(stack, { v -> Value.doubleToLong(OpcodeImpl.F64_CEIL(v)) })
            }
            OpCode.F64x2_TRUNC -> {
                F64x2(stack, { v -> Value.doubleToLong(OpcodeImpl.F64_TRUNC(v)) })
            }
            OpCode.F64x2_FLOOR -> {
                F64x2(stack, { v -> Value.doubleToLong(OpcodeImpl.F64_FLOOR(v)) })
            }
            OpCode.F64x2_NEAREST -> {
                F64x2(stack, { v -> Value.doubleToLong(OpcodeImpl.F64_NEAREST(v)) })
            }
            OpCode.I16x8_SHL -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsShorts()
                            .lanewise(VectorOperators.LSHL, s.toShort())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I16x8_SHR_U -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsShorts()
                            .lanewise(VectorOperators.LSHR, s.toShort())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I16x8_SHR_S -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsShorts()
                            .lanewise(VectorOperators.ASHR, s.toShort())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I32x4_SHL -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsInts()
                            .lanewise(VectorOperators.LSHL, s.toInt())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I32x4_SHR_U -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsInts()
                            .lanewise(VectorOperators.LSHR, s.toInt())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I32x4_SHR_S -> {
                SH(
                    stack,
                    { v, s ->
                        v.reinterpretAsInts()
                            .lanewise(VectorOperators.ASHR, s.toInt())
                            .reinterpretAsLongs()
                    },
                )
            }
            OpCode.I16x8_MUL -> {
                BINOP(stack, LongVector::reinterpretAsShorts, { v1, v2 -> v2.mul(v1) })
            }
            OpCode.I32x4_ADD -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v2.add(v1) })
            }
            OpCode.I32x4_SUB -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v2.sub(v1) })
            }
            OpCode.I32x4_MUL -> {
                BINOP(stack, LongVector::reinterpretAsInts, { v1, v2 -> v2.mul(v1) })
            }
            OpCode.I64x2_SHL -> {
                SH(stack, { v, s -> v.lanewise(VectorOperators.LSHL, s) })
            }
            OpCode.I64x2_SHR_U -> {
                SH(stack, { v, s -> v.lanewise(VectorOperators.LSHR, s) })
            }
            OpCode.I64x2_SHR_S -> {
                SH(stack, { v, s -> v.lanewise(VectorOperators.ASHR, s) })
            }
            OpCode.I64x2_ADD -> {
                BINOP(stack, LongVector::reinterpretAsLongs, { v1, v2 -> v2.add(v1) })
            }
            OpCode.I32x4_TRUNC_SAT_F32X4_S -> {
                I32x4_TRUNC_SAT_F32x4_S(stack)
            }
            OpCode.I32x4_TRUNC_SAT_F32X4_U -> {
                I32x4_TRUNC_SAT_F32x4_U(stack)
            }
            OpCode.I32x4_TRUNC_SAT_F64x2_S_ZERO -> {
                I32x4_TRUNC_SAT_F64x2_S_ZERO(stack)
            }
            OpCode.I32x4_TRUNC_SAT_F64x2_U_ZERO -> {
                I32x4_TRUNC_SAT_F64x2_U_ZERO(stack)
            }
            OpCode.F32x4_CONVERT_I32x4_S -> {
                F32x4_CONVERT_I32x4_S(stack)
            }
            OpCode.F32x4_CONVERT_I32x4_U -> {
                F32x4_CONVERT_I32x4_U(stack)
            }
            OpCode.F64x2_PROMOTE_LOW_F32x4,
            OpCode.F64x2_CONVERT_LOW_I32x4_S,
            OpCode.F64x2_CONVERT_LOW_I32x4_U -> {
                F64x2_PROMOTE_LOW_F32x4(stack)
            }
            OpCode.F32x4_DEMOTE_LOW_F64x2_ZERO -> {
                F32x4_DEMOTE_LOW_F64x2_ZERO(stack)
            }
            OpCode.I8x16_NARROW_I16x8_S -> {
                I8x16_NARROW_I16x8(stack, this::narrowS)
            }
            OpCode.I8x16_NARROW_I16x8_U -> {
                I8x16_NARROW_I16x8(stack, this::narrowU)
            }
            OpCode.I16x8_EXTADD_PAIRWISE_I8x16_S -> {
                I16x8_EXTADD_PAIRWISE_I8x16_S(stack)
            }
            OpCode.I16x8_EXTADD_PAIRWISE_I8x16_U -> {
                I16x8_EXTADD_PAIRWISE_I8x16_U(stack)
            }
            OpCode.I16x8_EXTMUL_LOW_I8x16_S -> {
                I16x8_EXTMUL_LOW_I8x16_S(stack)
            }
            OpCode.I16x8_EXTMUL_HIGH_I8x16_S -> {
                I16x8_EXTMUL_HIGH_I8x16_S(stack)
            }
            OpCode.I16x8_EXTMUL_LOW_I8x16_U -> {
                I16x8_EXTMUL_LOW_I8x16_U(stack)
            }
            OpCode.I16x8_EXTMUL_HIGH_I8x16_U -> {
                I16x8_EXTMUL_HIGH_I8x16_U(stack)
            }
            OpCode.I16x8_Q15MULR_SAT_S -> {
                I16x8_Q15MULR_SAT_S(stack)
            }
            OpCode.I16x8_NARROW_I32x4_S -> {
                I16x8_NARROW_I32x4(stack, this::narrowS)
            }
            OpCode.I16x8_NARROW_I32x4_U -> {
                I16x8_NARROW_I32x4(stack, this::narrowU)
            }
            OpCode.I16x8_EXTEND_LOW_I8x16_S -> {
                I16x8_EXTEND_LOW_I8x16_S(stack)
            }
            OpCode.I16x8_EXTEND_HIGH_I8x16_S -> {
                I16x8_EXTEND_HIGH_I8x16_S(stack)
            }
            OpCode.I16x8_EXTEND_LOW_I8x16_U -> {
                I16x8_EXTEND_LOW_I8x16_U(stack)
            }
            OpCode.I16x8_EXTEND_HIGH_I8x16_U -> {
                I16x8_EXTEND_HIGH_I8x16_U(stack)
            }
            OpCode.I32x4_EXTEND_LOW_I16x8_S -> {
                I32x4_EXTEND_LOW_I16x8_S(stack)
            }
            OpCode.I32x4_EXTEND_HIGH_I16x8_S -> {
                I32x4_EXTEND_HIGH_I16x8_S(stack)
            }
            OpCode.I32x4_EXTEND_LOW_I16x8_U -> {
                I32x4_EXTEND_LOW_I16x8_U(stack)
            }
            OpCode.I32x4_EXTEND_HIGH_I16x8_U -> {
                I32x4_EXTEND_HIGH_I16x8_U(stack)
            }
            OpCode.I32x4_EXTADD_PAIRWISE_I16x8_S -> {
                I32x4_EXTADD_PAIRWISE_I16x8_S(stack)
            }
            OpCode.I32x4_EXTADD_PAIRWISE_I16x8_U -> {
                I32x4_EXTADD_PAIRWISE_I16x8_U(stack)
            }
            OpCode.I64x2_EXTEND_LOW_I32x4_S -> {
                I64x2_EXTEND_LOW_I32x4_S(stack)
            }
            OpCode.I64x2_EXTEND_HIGH_I32x4_S -> {
                I64x2_EXTEND_HIGH_I32x4_S(stack)
            }
            OpCode.I64x2_EXTEND_LOW_I32x4_U -> {
                I64x2_EXTEND_LOW_I32x4_U(stack)
            }
            OpCode.I64x2_EXTEND_HIGH_I32x4_U -> {
                I64x2_EXTEND_HIGH_I32x4_U(stack)
            }
            else -> {
                super.evalDefault(stack, instance, callStack, instruction, operands)
            }
        }
    }

    private fun longs(vararg values: Number): LongArray =
        LongArray(values.size) { values[it].toLong() }

    private fun lane32(value: Long, shift: Int): Int = ((value shr shift) and 0xFFFFFFFFL).toInt()

    private fun insertLane32(accumulator: Long, value: Long, shift: Int): Long =
        accumulator or ((value and 0xFFFFFFFFL) shl shift)

    private fun V128_CONST(stack: MStack, operands: Operands) {
        stack.push(operands.get(0))
        stack.push(operands.get(1))
    }

    private fun V128_LOAD(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var valHigh = instance.memory(operands.get(2).toInt()).readLong(ptr)
        var valLow = instance.memory(operands.get(2).toInt()).readLong(ptr + 8)
        stack.push(valHigh)
        stack.push(valLow)
    }

    private fun V128_LOAD8_SPLAT(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).read(ptr)
        var bytes = LongArray(16)
        Arrays.fill(bytes, value.toLong())
        var vals = Value.i8ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD16_SPLAT(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readShort(ptr)
        var shorts = LongArray(8)
        Arrays.fill(shorts, value.toLong())
        var vals = Value.i16ToVec(shorts)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD32_SPLAT(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readInt(ptr)
        var ints = LongArray(4)
        Arrays.fill(ints, value.toLong())
        var vals = Value.i32ToVec(ints)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD64_SPLAT(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readLong(ptr)
        stack.push(value)
        stack.push(value)
    }

    private fun V128_LOAD8x8_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(8)
        for (i in 0 until 8) {
            bytes[i] = instance.memory(operands.get(2).toInt()).read(ptr + i).toLong()
        }
        var vals = Value.i16ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD8x8_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(8)
        for (i in 0 until 8) {
            bytes[i] =
                java.lang.Byte.toUnsignedLong(
                    instance.memory(operands.get(2).toInt()).read(ptr + i)
                )
        }
        var vals = Value.i16ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD16x4_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(4)
        for (i in 0 until 4) {
            bytes[i] = instance.memory(operands.get(2).toInt()).readShort(ptr + (i * 2)).toLong()
        }
        var vals = Value.i32ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD16x4_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(4)
        for (i in 0 until 4) {
            bytes[i] =
                java.lang.Short.toUnsignedLong(
                    instance.memory(operands.get(2).toInt()).readShort(ptr + (i * 2))
                )
        }
        var vals = Value.i32ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD32x2_S(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(8)
        for (i in 0 until 2) {
            bytes[i] = instance.memory(operands.get(2).toInt()).readInt(ptr + (i * 4)).toLong()
        }
        var vals = Value.i64ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD32x2_U(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var bytes = LongArray(8)
        for (i in 0 until 2) {
            bytes[i] =
                java.lang.Integer.toUnsignedLong(
                    instance.memory(operands.get(2).toInt()).readInt(ptr + (i * 4))
                )
        }
        var vals = Value.i64ToVec(bytes)
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD32_ZERO(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readInt(ptr)
        var vals = Value.i32ToVec(longs(value, 0, 0, 0))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun V128_LOAD64_ZERO(stack: MStack, instance: Instance, operands: Operands) {
        var ptr = readMemPtr(stack, operands)
        var value = instance.memory(operands.get(2).toInt()).readLong(ptr)
        var vals = Value.i64ToVec(longs(value, 0))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun LOAD_LANE(
        stack: MStack,
        operands: Operands,
        loadLane: (LongVector, Int) -> LongVector,
    ) {
        var valHigh = stack.pop()
        var valLow = stack.pop()
        var ptr = readMemPtr(stack, operands)

        var result =
            loadLane(LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0), ptr)
                .toArray()

        for (v in result) {
            stack.push(v)
        }
    }

    private fun V128_STORE(stack: MStack, instance: Instance, operands: Operands) {
        var valHigh = stack.pop()
        var valLow = stack.pop()
        var offset = operands.get(1)
        var i = stack.pop()
        // to let the bounds check kick in appropriately
        var ptr = if (i >= 0) (offset + i).toInt() else i.toInt()

        instance.memory(operands.get(2).toInt()).writeLong(ptr, valLow)
        instance.memory(operands.get(2).toInt()).writeLong(ptr + 8, valHigh)
    }

    private fun I8x16_SHUFFLE(stack: MStack, operands: Operands) {
        var v2High = stack.pop()
        var v2Low = stack.pop()
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var select =
            LongVector.fromArray(LongVector.SPECIES_128, longs(operands.get(0), operands.get(1)), 0)
                .reinterpretAsBytes()
                .toArray()

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v2Low, v2High), 0)
                .reinterpretAsBytes()
                .toArray()

        var result = ByteArray(16)
        for (i in 0 until 16) {
            var s = select[i].toInt()
            if (s >= 16) {
                result[i] = v2[s - 16]
            } else {
                result[i] = v1[s]
            }
        }

        var res = Value.bytesToVec(result)
        for (v in res) {
            stack.push(v)
        }
    }

    private fun I8x16_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals =
            Value.i8ToVec(
                longs(
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                    value,
                )
            )
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun I16x8_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals = Value.i16ToVec(longs(value, value, value, value, value, value, value, value))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun I32x4_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals = Value.i32ToVec(longs(value, value, value, value))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun F32x4_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals = Value.f32ToVec(longs(value, value, value, value))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun I64x2_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals = Value.i64ToVec(longs(value, value))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun F64x2_SPLAT(stack: MStack) {
        var value = stack.pop()
        var vals = Value.f64ToVec(longs(value, value))
        for (v in vals) {
            stack.push(v)
        }
    }

    private fun STORE_LANE(stack: MStack, operands: Operands, store: (LongVector, Int) -> Unit) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var offset = operands.get(1)
        var i = stack.pop()
        // to let the bounds check kick in appropriately
        var ptr = if (i >= 0) (offset + i).toInt() else i.toInt()

        var result = LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)

        store(result, ptr)
    }

    private fun EXTRACT_LANE(stack: MStack, operands: Operands, extract: (LongVector) -> Long) {
        var offset = stack.size() - 2
        var result = extract(LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset))

        // consume one element
        stack.pop()
        stack.array()[stack.size() - 1] = result
    }

    private fun REPLACE_LANE(stack: MStack, replace: (LongVector, Long) -> LongVector) {
        var value = stack.pop()
        var offset = stack.size() - 2

        var result =
            replace(LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset), value)
                .toArray()

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_EXTRACT_LANE_U(stack: MStack, operands: Operands) {
        var offset = stack.size() - 2
        var result =
            java.lang.Byte.toUnsignedLong(
                LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                    .reinterpretAsBytes()
                    .lane(operands.get(0).toInt())
            )

        // consume one element
        stack.pop()
        stack.array()[stack.size() - 1] = result
    }

    private fun I16x8_EXTRACT_LANE_U(stack: MStack, operands: Operands) {
        var offset = stack.size() - 2
        var result =
            java.lang.Short.toUnsignedLong(
                LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                    .reinterpretAsShorts()
                    .lane(operands.get(0).toInt())
            )

        // consume one element
        stack.pop()
        stack.array()[stack.size() - 1] = result
    }

    private fun addSatU(a: Byte, b: Byte): Byte {
        var result = java.lang.Byte.toUnsignedInt(a) + java.lang.Byte.toUnsignedInt(b)
        if (result >= 0xFF) {
            return 0xFF.toByte()
        } else if (result < 0) {
            return 0.toByte()
        } else {
            return result.toByte()
        }
    }

    private fun addSatU(a: Short, b: Short): Long {
        var result = java.lang.Short.toUnsignedInt(a) + java.lang.Short.toUnsignedInt(b)
        if (result >= 0xFFFF) {
            return 0xFFFF
        } else {
            return result.toLong()
        }
    }

    private fun subSatS(a: Byte, b: Byte): Byte {
        var result = a - b
        if (result > java.lang.Byte.MAX_VALUE) {
            return java.lang.Byte.MAX_VALUE
        } else if (result < java.lang.Byte.MIN_VALUE) {
            return java.lang.Byte.MIN_VALUE
        } else {
            return result.toByte()
        }
    }

    private fun subSatS(a: Short, b: Short): Long {
        var result = a - b
        if (result > java.lang.Short.MAX_VALUE) {
            return java.lang.Short.MAX_VALUE.toLong()
        } else if (result < java.lang.Short.MIN_VALUE) {
            return java.lang.Short.MIN_VALUE.toLong()
        } else {
            return result.toLong()
        }
    }

    private fun subSatU(a: Byte, b: Byte): Byte {
        var result = java.lang.Byte.toUnsignedInt(a) - java.lang.Byte.toUnsignedInt(b)
        if (result < 0) {
            return 0.toByte()
        } else {
            return result.toByte()
        }
    }

    private fun subSatU(a: Short, b: Short): Short {
        var result = java.lang.Short.toUnsignedInt(a) - java.lang.Short.toUnsignedInt(b)
        if (result < 0) {
            return 0.toShort()
        } else {
            return result.toShort()
        }
    }

    private fun I16x8_SUB_SAT_U(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i16ToVec(
                longs(
                    subSatU(v2[0], v1[0]),
                    subSatU(v2[1], v1[1]),
                    subSatU(v2[2], v1[2]),
                    subSatU(v2[3], v1[3]),
                    subSatU(v2[4], v1[4]),
                    subSatU(v2[5], v1[5]),
                    subSatU(v2[6], v1[6]),
                    subSatU(v2[7], v1[7]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_SUB_SAT_S(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i16ToVec(
                longs(
                    subSatS(v2[0], v1[0]),
                    subSatS(v2[1], v1[1]),
                    subSatS(v2[2], v1[2]),
                    subSatS(v2[3], v1[3]),
                    subSatS(v2[4], v1[4]),
                    subSatS(v2[5], v1[5]),
                    subSatS(v2[6], v1[6]),
                    subSatS(v2[7], v1[7]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_SUB_SAT_U(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var result =
            Value.i8ToVec(
                longs(
                    subSatU(v2[0], v1[0]),
                    subSatU(v2[1], v1[1]),
                    subSatU(v2[2], v1[2]),
                    subSatU(v2[3], v1[3]),
                    subSatU(v2[4], v1[4]),
                    subSatU(v2[5], v1[5]),
                    subSatU(v2[6], v1[6]),
                    subSatU(v2[7], v1[7]),
                    subSatU(v2[8], v1[8]),
                    subSatU(v2[9], v1[9]),
                    subSatU(v2[10], v1[10]),
                    subSatU(v2[11], v1[11]),
                    subSatU(v2[12], v1[12]),
                    subSatU(v2[13], v1[13]),
                    subSatU(v2[14], v1[14]),
                    subSatU(v2[15], v1[15]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_SUB_SAT_S(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var result =
            Value.i8ToVec(
                longs(
                    subSatS(v2[0], v1[0]),
                    subSatS(v2[1], v1[1]),
                    subSatS(v2[2], v1[2]),
                    subSatS(v2[3], v1[3]),
                    subSatS(v2[4], v1[4]),
                    subSatS(v2[5], v1[5]),
                    subSatS(v2[6], v1[6]),
                    subSatS(v2[7], v1[7]),
                    subSatS(v2[8], v1[8]),
                    subSatS(v2[9], v1[9]),
                    subSatS(v2[10], v1[10]),
                    subSatS(v2[11], v1[11]),
                    subSatS(v2[12], v1[12]),
                    subSatS(v2[13], v1[13]),
                    subSatS(v2[14], v1[14]),
                    subSatS(v2[15], v1[15]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_ADD_SAT_S(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var result =
            Value.i8ToVec(
                longs(
                    narrowS((v1[0] + v2[0]).toShort()),
                    narrowS((v1[1] + v2[1]).toShort()),
                    narrowS((v1[2] + v2[2]).toShort()),
                    narrowS((v1[3] + v2[3]).toShort()),
                    narrowS((v1[4] + v2[4]).toShort()),
                    narrowS((v1[5] + v2[5]).toShort()),
                    narrowS((v1[6] + v2[6]).toShort()),
                    narrowS((v1[7] + v2[7]).toShort()),
                    narrowS((v1[8] + v2[8]).toShort()),
                    narrowS((v1[9] + v2[9]).toShort()),
                    narrowS((v1[10] + v2[10]).toShort()),
                    narrowS((v1[11] + v2[11]).toShort()),
                    narrowS((v1[12] + v2[12]).toShort()),
                    narrowS((v1[13] + v2[13]).toShort()),
                    narrowS((v1[14] + v2[14]).toShort()),
                    narrowS((v1[15] + v2[15]).toShort()),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_ADD_SAT_U(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var result =
            Value.i8ToVec(
                longs(
                    addSatU(v1[0], v2[0]),
                    addSatU(v1[1], v2[1]),
                    addSatU(v1[2], v2[2]),
                    addSatU(v1[3], v2[3]),
                    addSatU(v1[4], v2[4]),
                    addSatU(v1[5], v2[5]),
                    addSatU(v1[6], v2[6]),
                    addSatU(v1[7], v2[7]),
                    addSatU(v1[8], v2[8]),
                    addSatU(v1[9], v2[9]),
                    addSatU(v1[10], v2[10]),
                    addSatU(v1[11], v2[11]),
                    addSatU(v1[12], v2[12]),
                    addSatU(v1[13], v2[13]),
                    addSatU(v1[14], v2[14]),
                    addSatU(v1[15], v2[15]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_ADD_SAT_S(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i16ToVec(
                longs(
                    narrowS(v1[0] + v2[0]),
                    narrowS(v1[1] + v2[1]),
                    narrowS(v1[2] + v2[2]),
                    narrowS(v1[3] + v2[3]),
                    narrowS(v1[4] + v2[4]),
                    narrowS(v1[5] + v2[5]),
                    narrowS(v1[6] + v2[6]),
                    narrowS(v1[7] + v2[7]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_ADD_SAT_U(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i16ToVec(
                longs(
                    addSatU(v1[0], v2[0]),
                    addSatU(v1[1], v2[1]),
                    addSatU(v1[2], v2[2]),
                    addSatU(v1[3], v2[3]),
                    addSatU(v1[4], v2[4]),
                    addSatU(v1[5], v2[5]),
                    addSatU(v1[6], v2[6]),
                    addSatU(v1[7], v2[7]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun <E> UNARY(
        stack: MStack,
        reinterpret: (LongVector) -> Vector<E>,
        operation: (Vector<E>) -> Vector<E>,
    ) {
        var offset = stack.size() - 2
        var v = reinterpret(LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset))

        var result = operation(v).reinterpretAsLongs().toArray()

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun <E> BINOP(
        stack: MStack,
        reinterpret: (LongVector) -> Vector<E>,
        operation: (Vector<E>, Vector<E>) -> Vector<E>,
    ) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 = reinterpret(LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0))
        var v2 = reinterpret(LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset))

        var result = operation(v1, v2).reinterpretAsLongs().toArray()

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun lt(a: Float, b: Float): Boolean {
        if (java.lang.Float.isNaN(b)) {
            return false
        } else if ((a == 0.0f && b == -0.0f) || (a == -0.0f && b == 0.0f)) {
            return false
        } else {
            return java.lang.Float.compare(a, b) < 0
        }
    }

    private fun le(a: Float, b: Float): Boolean {
        if (java.lang.Float.isNaN(b)) {
            return false
        } else if ((a == 0.0f && b == -0.0f) || (a == -0.0f && b == 0.0f)) {
            return true
        } else {
            return java.lang.Float.compare(a, b) <= 0
        }
    }

    private fun gt(a: Float, b: Float): Boolean {
        if (java.lang.Float.isNaN(a)) {
            return false
        } else if ((a == 0.0f && b == -0.0f) || (a == -0.0f && b == 0.0f)) {
            return false
        } else {
            return java.lang.Float.compare(a, b) > 0
        }
    }

    private fun ge(a: Float, b: Float): Boolean {
        if (java.lang.Float.isNaN(a)) {
            return false
        } else if ((a == 0.0f && b == -0.0f) || (a == -0.0f && b == 0.0f)) {
            return true
        } else {
            return java.lang.Float.compare(a, b) >= 0
        }
    }

    private fun equals(a: Float, b: Float): Boolean {
        if (java.lang.Float.isNaN(a) || java.lang.Float.isNaN(b)) {
            return false
        } else if ((a == 0.0f && b == -0.0f) || (a == -0.0f && b == 0.0f)) {
            return true
        } else {
            return java.lang.Float.compare(a, b) == 0
        }
    }

    private fun F32x4(stack: MStack, fn: (Float, Float) -> Long) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsFloats()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsFloats()
                .toArray()

        var result =
            Value.i32ToVec(
                longs(fn(v1[0], v2[0]), fn(v1[1], v2[1]), fn(v1[2], v2[2]), fn(v1[3], v2[3]))
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun F32x4(stack: MStack, fn: (Float) -> Long) {
        var offset = stack.size() - 2

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsFloats()
                .toArray()

        var result = Value.i32ToVec(longs(fn(v[0]), fn(v[1]), fn(v[2]), fn(v[3])))

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun F64x2(stack: MStack, fn: (Double, Double) -> Long) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsDoubles()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsDoubles()
                .toArray()

        var result = Value.i64ToVec(longs(fn(v1[0], v2[0]), fn(v1[1], v2[1])))

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun F64x2(stack: MStack, fn: (Double) -> Long) {
        var offset = stack.size() - 2

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsDoubles()
                .toArray()

        var result = Value.i64ToVec(longs(fn(v[0]), fn(v[1])))

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16_SUB(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset).reinterpretAsBytes()

        var result = v2.sub(v1).reinterpretAsLongs().toArray()

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun SH(stack: MStack, shl: (LongVector, Long) -> LongVector) {
        var s = stack.pop()
        var offset = stack.size() - 2

        var result =
            shl(LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset), s).toArray()

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun BOOL_OP(stack: MStack, condition: (LongVector) -> Boolean) {
        var vHigh = stack.pop()
        var vLow = stack.pop()

        var result = condition(LongVector.fromArray(LongVector.SPECIES_128, longs(vLow, vHigh), 0))

        if (result) {
            stack.push(BitOps.TRUE.toLong())
        } else {
            stack.push(BitOps.FALSE.toLong())
        }
    }

    private fun BITMASK(stack: MStack, reduce: (LongVector) -> LongArray) {
        var vHigh = stack.pop()
        var vLow = stack.pop()

        var vals = reduce(LongVector.fromArray(LongVector.SPECIES_128, longs(vLow, vHigh), 0))

        var result = 0L
        for (i in 0 until vals.size) {
            if (vals[i] < 0) {
                result = result or (1L shl i)
            }
        }

        stack.push(result)
    }

    private fun V128_NOT(stack: MStack) {
        var offset = stack.size() - 2
        var not = LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset).not()
        var res = not.toArray()

        System.arraycopy(res, 0, stack.array(), offset, 2)
    }

    private fun V128_BINOP(stack: MStack, binop: (LongVector, LongVector) -> LongVector) {
        var v1High = stack.pop()
        var v1Low = stack.pop()
        var offset = stack.size() - 2
        var v1 = LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
        var v2 = LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
        var res = binop(v1, v2).toArray()

        System.arraycopy(res, 0, stack.array(), offset, 2)
    }

    private fun V128_ANY_TRUE(stack: MStack) {
        var vHigh = stack.pop()
        var vLow = stack.pop()

        // any_true is true when any bit in the 128-bit vector is set.
        if (vLow != 0L || vHigh != 0L) {
            stack.push(BitOps.TRUE.toLong())
        } else {
            stack.push(BitOps.FALSE.toLong())
        }
    }

    private fun V128_BITSELECT(stack: MStack) {
        var cHi = stack.pop()
        var cLo = stack.pop()
        var x1Hi = stack.pop()
        var x1Lo = stack.pop()

        var m = LongVector.fromArray(LongVector.SPECIES_128, longs(cLo, cHi), 0)
        var v1 = LongVector.fromArray(LongVector.SPECIES_128, longs(x1Lo, x1Hi), 0)
        var v2 = LongVector.fromArray(LongVector.SPECIES_128, stack.array(), stack.size() - 2)

        var result = v1.bitwiseBlend(v2, m).toArray()

        System.arraycopy(result, 0, stack.array(), stack.size() - 2, 2)
    }

    private fun I32x4_DOT_I16x8_S(stack: MStack) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i32ToVec(
                longs(
                    (v1[0] * v2[0]) + (v1[4] * v2[4]),
                    (v1[1] * v2[1]) + (v1[5] * v2[5]),
                    (v1[2] * v2[2]) + (v1[6] * v2[6]),
                    (v1[3] * v2[3]) + (v1[7] * v2[7]),
                )
            )
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I8x16(stack: MStack, op: (Byte, Byte) -> Long) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var result = LongArray(16)
        for (i in 0 until 16) {
            result[i] = op(v1[i], v2[i])
        }
        System.arraycopy(Value.i8ToVec(result), 0, stack.array(), offset, 2)
    }

    private fun I16x8(stack: MStack, op: (Short, Short) -> Long) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result = LongArray(8)
        for (i in 0 until 8) {
            result[i] = op(v1[i], v2[i])
        }
        System.arraycopy(Value.i16ToVec(result), 0, stack.array(), offset, 2)
    }

    private fun I32x4(stack: MStack, op: (Int, Int) -> Long) {
        var v1High = stack.pop()
        var v1Low = stack.pop()

        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(v1Low, v1High), 0)
                .reinterpretAsInts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsInts()
                .toArray()

        var result = LongArray(4)
        for (i in 0 until 4) {
            result[i] = op(v1[i], v2[i])
        }
        System.arraycopy(Value.i32ToVec(result), 0, stack.array(), offset, 2)
    }

    private fun I32x4_TRUNC_SAT_F32x4_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var resultLow = 0L
        var resultHigh = 0L

        for (i in 0 until 2) {
            var shift = i * 32
            resultHigh =
                insertLane32(
                    resultHigh,
                    OpcodeImpl.I32_TRUNC_SAT_F32_S(
                            java.lang.Float.intBitsToFloat(lane32(valHigh, shift))
                        )
                        .toLong(),
                    shift,
                )
            resultLow =
                insertLane32(
                    resultLow,
                    OpcodeImpl.I32_TRUNC_SAT_F32_S(
                            java.lang.Float.intBitsToFloat(lane32(valLow, shift))
                        )
                        .toLong(),
                    shift,
                )
        }

        stack.push(resultLow)
        stack.push(resultHigh)
    }

    private fun I32x4_TRUNC_SAT_F32x4_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var resultLow = 0L
        var resultHigh = 0L

        for (i in 0 until 2) {
            var shift = i * 32
            resultHigh =
                insertLane32(
                    resultHigh,
                    OpcodeImpl.I32_TRUNC_SAT_F32_U(
                            java.lang.Float.intBitsToFloat(lane32(valHigh, shift))
                        )
                        .toLong(),
                    shift,
                )
            resultLow =
                insertLane32(
                    resultLow,
                    OpcodeImpl.I32_TRUNC_SAT_F32_U(
                            java.lang.Float.intBitsToFloat(lane32(valLow, shift))
                        )
                        .toLong(),
                    shift,
                )
        }

        stack.push(resultLow)
        stack.push(resultHigh)
    }

    private fun I32x4_TRUNC_SAT_F64x2_S_ZERO(stack: MStack) {
        var offset = stack.size() - 2

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsDoubles()
                .toArray()

        var result =
            Value.i32ToVec(
                longs(
                    OpcodeImpl.I32_TRUNC_SAT_F64_S(v[0]),
                    OpcodeImpl.I32_TRUNC_SAT_F64_S(v[1]),
                    0L,
                    0L,
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_TRUNC_SAT_F64x2_U_ZERO(stack: MStack) {
        var offset = stack.size() - 2

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsDoubles()
                .toArray()

        var result =
            Value.i32ToVec(
                longs(
                    OpcodeImpl.I32_TRUNC_SAT_F64_U(v[0]),
                    OpcodeImpl.I32_TRUNC_SAT_F64_U(v[1]),
                    0L,
                    0L,
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun F32x4_CONVERT_I32x4_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var resultLow = 0L
        var resultHigh = 0L

        for (i in 0 until 2) {
            var shift = i * 32
            resultHigh =
                insertLane32(
                    resultHigh,
                    java.lang.Float.floatToIntBits(
                            OpcodeImpl.F32_CONVERT_I32_U(lane32(valHigh, shift))
                        )
                        .toLong(),
                    shift,
                )
            resultLow =
                insertLane32(
                    resultLow,
                    java.lang.Float.floatToIntBits(
                            OpcodeImpl.F32_CONVERT_I32_U(lane32(valLow, shift))
                        )
                        .toLong(),
                    shift,
                )
        }

        stack.push(resultLow)
        stack.push(resultHigh)
    }

    private fun F32x4_CONVERT_I32x4_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var resultLow = 0L
        var resultHigh = 0L

        for (i in 0 until 2) {
            var shift = i * 32
            resultHigh =
                insertLane32(
                    resultHigh,
                    java.lang.Float.floatToIntBits(
                            OpcodeImpl.F32_CONVERT_I32_S(lane32(valHigh, shift))
                        )
                        .toLong(),
                    shift,
                )
            resultLow =
                insertLane32(
                    resultLow,
                    java.lang.Float.floatToIntBits(
                            OpcodeImpl.F32_CONVERT_I32_S(lane32(valLow, shift))
                        )
                        .toLong(),
                    shift,
                )
        }

        stack.push(resultLow)
        stack.push(resultHigh)
    }

    private fun F64x2_PROMOTE_LOW_F32x4(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsFloats()
                .toArray()

        stack.push(Value.floatToLong(v[0]))
        stack.push(Value.floatToLong(v[1]))
    }

    private fun narrowS(a: Short): Byte {
        if (a < java.lang.Byte.MIN_VALUE) {
            return java.lang.Byte.MIN_VALUE
        } else if (a > java.lang.Byte.MAX_VALUE) {
            return java.lang.Byte.MAX_VALUE
        } else {
            return a.toByte()
        }
    }

    private fun narrowS(a: Int): Short {
        if (a < java.lang.Short.MIN_VALUE) {
            return java.lang.Short.MIN_VALUE
        } else if (a > java.lang.Short.MAX_VALUE) {
            return java.lang.Short.MAX_VALUE
        } else {
            return a.toShort()
        }
    }

    private fun narrowU(a: Short): Byte {
        if (a < 0) {
            return 0.toByte()
        } else if (a > 255) {
            return (-1).toByte()
        } else {
            return a.toByte()
        }
    }

    private fun narrowU(a: Int): Short {
        if (a < 0) {
            return 0.toShort()
        } else if (a > 65535) {
            return (-1).toShort()
        } else {
            return a.toShort()
        }
    }

    private fun I8x16_NARROW_I16x8(stack: MStack, narrow: (Short) -> Byte) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var result =
            Value.i8ToVec(
                longs(
                    narrow(v2[0]),
                    narrow(v2[1]),
                    narrow(v2[2]),
                    narrow(v2[3]),
                    narrow(v2[4]),
                    narrow(v2[5]),
                    narrow(v2[6]),
                    narrow(v2[7]),
                    narrow(v1[0]),
                    narrow(v1[1]),
                    narrow(v1[2]),
                    narrow(v1[3]),
                    narrow(v1[4]),
                    narrow(v1[5]),
                    narrow(v1[6]),
                    narrow(v1[7]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_NARROW_I32x4(stack: MStack, narrow: (Int) -> Short) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsInts()
                .toArray()
        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsInts()
                .toArray()

        var result =
            Value.i16ToVec(
                longs(
                    narrow(v2[0]),
                    narrow(v2[1]),
                    narrow(v2[2]),
                    narrow(v2[3]),
                    narrow(v1[0]),
                    narrow(v1[1]),
                    narrow(v1[2]),
                    narrow(v1[3]),
                )
            )

        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun F32x4_DEMOTE_LOW_F64x2_ZERO(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsDoubles()
                .toArray()

        var vals =
            Value.f32ToVec(
                longs(Value.floatToLong(v[0].toFloat()), Value.floatToLong(v[1].toFloat()), 0, 0)
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTMUL_LOW_I8x16_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var res = LongArray(8)
        for (i in 0 until 8) {
            res[i] = (v1[i] * v2[i]).toLong()
        }
        var result = Value.i16ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_EXTMUL_HIGH_I8x16_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var res = LongArray(8)
        for (i in 0 until 8) {
            res[i] = (v1[8 + i] * v2[8 + i]).toLong()
        }
        var result = Value.i16ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_EXTMUL_LOW_I8x16_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var res = LongArray(8)
        for (i in 0 until 8) {
            res[i] = java.lang.Byte.toUnsignedLong(v1[i]) * java.lang.Byte.toUnsignedLong(v2[i])
        }
        var result = Value.i16ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I16x8_EXTMUL_HIGH_I8x16_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var res = LongArray(8)
        for (i in 0 until 8) {
            res[i] =
                java.lang.Byte.toUnsignedLong(v1[8 + i]) * java.lang.Byte.toUnsignedLong(v2[8 + i])
        }
        var result = Value.i16ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_EXTMUL_LOW_I16x8_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var res = LongArray(4)
        for (i in 0 until 4) {
            res[i] = (v1[i] * v2[i]).toLong()
        }
        var result = Value.i32ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_EXTMUL_HIGH_I16x8_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var res = LongArray(4)
        for (i in 0 until 4) {
            res[i] = (v1[4 + i] * v2[4 + i]).toLong()
        }
        var result = Value.i32ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_EXTMUL_LOW_I16x8_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var res = LongArray(4)
        for (i in 0 until 4) {
            res[i] = java.lang.Short.toUnsignedLong(v1[i]) * java.lang.Short.toUnsignedLong(v2[i])
        }
        var result = Value.i32ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_EXTMUL_HIGH_I16x8_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var res = LongArray(4)
        for (i in 0 until 4) {
            res[i] =
                java.lang.Short.toUnsignedLong(v1[4 + i]) *
                    java.lang.Short.toUnsignedLong(v2[4 + i])
        }
        var result = Value.i32ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I64x2_EXTMUL_LOW_I32x4_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsInts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsInts()
                .toArray()

        var res = longs(v1[0] * v2[0], v1[1] * v2[1])
        var result = Value.i64ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I64x2_EXTMUL_HIGH_I32x4_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsInts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsInts()
                .toArray()

        var res = longs(v1[2] * v2[2], v1[3] * v2[3])
        var result = Value.i64ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I64x2_EXTMUL_LOW_I32x4_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsInts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsInts()
                .toArray()

        var res =
            longs(
                java.lang.Integer.toUnsignedLong(v1[0]) * java.lang.Integer.toUnsignedLong(v2[0]),
                java.lang.Integer.toUnsignedLong(v1[1]) * java.lang.Integer.toUnsignedLong(v2[1]),
            )
        var result = Value.i64ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I64x2_EXTMUL_HIGH_I32x4_U(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsBytes()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsBytes()
                .toArray()

        var res =
            longs(
                java.lang.Integer.toUnsignedLong(v1[2].toInt()) *
                    java.lang.Integer.toUnsignedLong(v2[2].toInt()),
                java.lang.Integer.toUnsignedLong(v1[3].toInt()) *
                    java.lang.Integer.toUnsignedLong(v2[3].toInt()),
            )

        var result = Value.i64ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun roundQ15(a: Int): Short {
        if (a < java.lang.Short.MIN_VALUE) {
            return java.lang.Short.MIN_VALUE
        } else if (a > java.lang.Short.MAX_VALUE) {
            return java.lang.Short.MAX_VALUE
        } else {
            return a.toShort()
        }
    }

    private fun I16x8_Q15MULR_SAT_S(stack: MStack) {
        var val1High = stack.pop()
        var val1Low = stack.pop()
        var offset = stack.size() - 2

        var v1 =
            LongVector.fromArray(LongVector.SPECIES_128, longs(val1Low, val1High), 0)
                .reinterpretAsShorts()
                .toArray()

        var v2 =
            LongVector.fromArray(LongVector.SPECIES_128, stack.array(), offset)
                .reinterpretAsShorts()
                .toArray()

        var res = LongArray(8)
        for (i in 0 until 8) {
            // https://github.com/argon-lang/jawawasm/blob/0193bc0bbd1157d0de4d83c8139d317e638d44ed/engine/src/main/java/dev/argon/jawawasm/engine/StackFrame.java#L903-L910
            res[i] = roundQ15((v1[i].toInt() * v2[i].toInt() + (1 shl 14)) shr 15).toLong()
        }
        var result = Value.i16ToVec(res)
        System.arraycopy(result, 0, stack.array(), offset, 2)
    }

    private fun I32x4_EXTADD_PAIRWISE_I16x8_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals = Value.i32ToVec(longs(v[0] + v[1], v[2] + v[3], v[4] + v[5], v[6] + v[7]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I32x4_EXTADD_PAIRWISE_I16x8_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals =
            Value.i32ToVec(
                longs(
                    java.lang.Short.toUnsignedLong(v[0]) + java.lang.Short.toUnsignedLong(v[1]),
                    java.lang.Short.toUnsignedLong(v[2]) + java.lang.Short.toUnsignedLong(v[3]),
                    java.lang.Short.toUnsignedLong(v[4]) + java.lang.Short.toUnsignedLong(v[5]),
                    java.lang.Short.toUnsignedLong(v[6]) + java.lang.Short.toUnsignedLong(v[7]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTADD_PAIRWISE_I8x16_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals =
            Value.i16ToVec(
                longs(
                    v[0] + v[1],
                    v[2] + v[3],
                    v[4] + v[5],
                    v[6] + v[7],
                    v[8] + v[9],
                    v[10] + v[11],
                    v[12] + v[13],
                    v[14] + v[15],
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTADD_PAIRWISE_I8x16_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals =
            Value.i16ToVec(
                longs(
                    java.lang.Byte.toUnsignedLong(v[0]) + java.lang.Byte.toUnsignedLong(v[1]),
                    java.lang.Byte.toUnsignedLong(v[2]) + java.lang.Byte.toUnsignedLong(v[3]),
                    java.lang.Byte.toUnsignedLong(v[4]) + java.lang.Byte.toUnsignedLong(v[5]),
                    java.lang.Byte.toUnsignedLong(v[6]) + java.lang.Byte.toUnsignedLong(v[7]),
                    java.lang.Byte.toUnsignedLong(v[8]) + java.lang.Byte.toUnsignedLong(v[9]),
                    java.lang.Byte.toUnsignedLong(v[10]) + java.lang.Byte.toUnsignedLong(v[11]),
                    java.lang.Byte.toUnsignedLong(v[12]) + java.lang.Byte.toUnsignedLong(v[13]),
                    java.lang.Byte.toUnsignedLong(v[14]) + java.lang.Byte.toUnsignedLong(v[15]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTEND_LOW_I8x16_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals = Value.i16ToVec(longs(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTEND_HIGH_I8x16_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals = Value.i16ToVec(longs(v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I32x4_EXTEND_LOW_I16x8_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals = Value.i32ToVec(longs(v[0], v[1], v[2], v[3]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I32x4_EXTEND_HIGH_I16x8_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals = Value.i32ToVec(longs(v[4], v[5], v[6], v[7]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I32x4_EXTEND_LOW_I16x8_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals =
            Value.i32ToVec(
                longs(
                    java.lang.Short.toUnsignedLong(v[0]),
                    java.lang.Short.toUnsignedLong(v[1]),
                    java.lang.Short.toUnsignedLong(v[2]),
                    java.lang.Short.toUnsignedLong(v[3]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I32x4_EXTEND_HIGH_I16x8_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsShorts()
                .toArray()

        var vals =
            Value.i32ToVec(
                longs(
                    java.lang.Short.toUnsignedLong(v[4]),
                    java.lang.Short.toUnsignedLong(v[5]),
                    java.lang.Short.toUnsignedLong(v[6]),
                    java.lang.Short.toUnsignedLong(v[7]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I64x2_EXTEND_HIGH_I32x4_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsInts()
                .toArray()

        var vals = Value.i64ToVec(longs(v[2], v[3]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I64x2_EXTEND_LOW_I32x4_S(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsInts()
                .toArray()

        var vals = Value.i64ToVec(longs(v[0], v[1]))
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I64x2_EXTEND_HIGH_I32x4_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsInts()
                .toArray()

        var vals =
            Value.i64ToVec(
                longs(
                    java.lang.Integer.toUnsignedLong(v[2]),
                    java.lang.Integer.toUnsignedLong(v[3]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I64x2_EXTEND_LOW_I32x4_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsInts()
                .toArray()

        var vals =
            Value.i64ToVec(
                longs(
                    java.lang.Integer.toUnsignedLong(v[0]),
                    java.lang.Integer.toUnsignedLong(v[1]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTEND_LOW_I8x16_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals =
            Value.i16ToVec(
                longs(
                    java.lang.Byte.toUnsignedLong(v[0]),
                    java.lang.Byte.toUnsignedLong(v[1]),
                    java.lang.Byte.toUnsignedLong(v[2]),
                    java.lang.Byte.toUnsignedLong(v[3]),
                    java.lang.Byte.toUnsignedLong(v[4]),
                    java.lang.Byte.toUnsignedLong(v[5]),
                    java.lang.Byte.toUnsignedLong(v[6]),
                    java.lang.Byte.toUnsignedLong(v[7]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I16x8_EXTEND_HIGH_I8x16_U(stack: MStack) {
        var valHigh = stack.pop()
        var valLow = stack.pop()

        var v =
            LongVector.fromArray(LongVector.SPECIES_128, longs(valLow, valHigh), 0)
                .reinterpretAsBytes()
                .toArray()

        var vals =
            Value.i16ToVec(
                longs(
                    java.lang.Byte.toUnsignedLong(v[8]),
                    java.lang.Byte.toUnsignedLong(v[9]),
                    java.lang.Byte.toUnsignedLong(v[10]),
                    java.lang.Byte.toUnsignedLong(v[11]),
                    java.lang.Byte.toUnsignedLong(v[12]),
                    java.lang.Byte.toUnsignedLong(v[13]),
                    java.lang.Byte.toUnsignedLong(v[14]),
                    java.lang.Byte.toUnsignedLong(v[15]),
                )
            )
        for (value in vals) {
            stack.push(value)
        }
    }

    private fun I8x16_SWIZZLE(stack: MStack) {
        var idxHigh = stack.pop()
        var idxLow = stack.pop()
        var baseHigh = stack.pop()
        var baseLow = stack.pop()

        var resultLow = 0L
        var resultHigh = 0L

        for (i in 0 until 16) {
            var id = 0L
            if (i < 8) {
                id = (idxLow shr (i * 8)) and 0xFFL
            } else {
                id = (idxHigh shr ((i - 8) * 8)) and 0xFFL
            }

            var base: Long
            if (id < 8) {
                base = (baseLow shr (id.toInt() * 8)) and 0xFFL
            } else if (id < 16) {
                base = (baseHigh shr (id.toInt() * 8)) and 0xFFL
            } else {
                base = 0x00L
            }

            if (i < 8) {
                resultLow = resultLow or (base shl (i * 8))
            } else {
                resultHigh = resultHigh or (base shl ((i - 8) * 8))
            }
        }

        stack.push(resultLow)
        stack.push(resultHigh)
    }
}
