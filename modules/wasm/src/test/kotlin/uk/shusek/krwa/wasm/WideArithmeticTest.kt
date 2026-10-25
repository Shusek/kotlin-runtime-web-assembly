package uk.shusek.krwa.wasm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.types.OpCode

/**
 * The wide-arithmetic proposal adds four `0xFC`-prefixed instructions. Wasmtime enables the
 * proposal by default since 49.0.0, so KRWA's parser and validator must accept the same modules
 * that the execution engine accepts.
 */
class WideArithmeticTest {
    @Test
    fun opcodeTableDecodesWideArithmeticInstructions() {
        assertEquals(OpCode.I64_ADD128, OpCode.byOpCode(0xFC13))
        assertEquals(OpCode.I64_SUB128, OpCode.byOpCode(0xFC14))
        assertEquals(OpCode.I64_MUL_WIDE_S, OpCode.byOpCode(0xFC15))
        assertEquals(OpCode.I64_MUL_WIDE_U, OpCode.byOpCode(0xFC16))
    }

    @Test
    fun parsesAndValidatesWideArithmeticFunctions() {
        val module =
            WasmParser.parse(
                wasmModule(
                    section(1, bytes(0x02) + QUAD_I64_TO_PAIR + PAIR_I64_TO_PAIR),
                    section(3, bytes(0x04, 0x00, 0x00, 0x01, 0x01)),
                    section(
                        10,
                        bytes(0x04) +
                            functionBody(fourLocals() + bytes(0xFC, 0x13, 0x0B)) +
                            functionBody(fourLocals() + bytes(0xFC, 0x14, 0x0B)) +
                            functionBody(twoLocals() + bytes(0xFC, 0x15, 0x0B)) +
                            functionBody(twoLocals() + bytes(0xFC, 0x16, 0x0B)),
                    ),
                )
            )

        val bodies = module.codeSection().functionBodies()
        assertEquals(4, bodies.size)
        assertEquals(OpCode.I64_ADD128, bodies[0].instructions()[4].opcode())
        assertEquals(OpCode.I64_SUB128, bodies[1].instructions()[4].opcode())
        assertEquals(OpCode.I64_MUL_WIDE_S, bodies[2].instructions()[2].opcode())
        assertEquals(OpCode.I64_MUL_WIDE_U, bodies[3].instructions()[2].opcode())
    }

    @Test
    fun rejectsWideArithmeticWithTooFewOperands() {
        // (func (param i64 i64) (result i64 i64) local.get 0 local.get 1 i64.add128)
        val module =
            wasmModule(
                section(1, bytes(0x01) + PAIR_I64_TO_PAIR),
                section(3, bytes(0x01, 0x00)),
                section(10, bytes(0x01) + functionBody(twoLocals() + bytes(0xFC, 0x13, 0x0B))),
            )

        assertThrows(InvalidException::class.java) { WasmParser.parse(module) }
    }

    @Test
    fun rejectsWideMultiplyOfMismatchedOperandTypes() {
        // (func (param i64 i32) (result i64 i64) local.get 0 local.get 1 i64.mul_wide_u)
        val type = bytes(0x60, 0x02, 0x7E, 0x7F, 0x02, 0x7E, 0x7E)
        val module =
            wasmModule(
                section(1, bytes(0x01) + type),
                section(3, bytes(0x01, 0x00)),
                section(10, bytes(0x01) + functionBody(twoLocals() + bytes(0xFC, 0x16, 0x0B))),
            )

        assertThrows(InvalidException::class.java) { WasmParser.parse(module) }
    }

    private fun fourLocals(): ByteArray = bytes(0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x20, 0x03)

    private fun twoLocals(): ByteArray = bytes(0x20, 0x00, 0x20, 0x01)

    private fun functionBody(instructions: ByteArray): ByteArray =
        unsignedLeb128(instructions.size + 1) + bytes(0x00) + instructions

    private fun wasmModule(vararg sections: ByteArray): ByteArray =
        sections.fold(MAGIC_AND_VERSION) { acc, section -> acc + section }

    private fun section(id: Int, body: ByteArray): ByteArray =
        bytes(id) + unsignedLeb128(body.size) + body

    private fun unsignedLeb128(value: Int): ByteArray {
        var remaining = value
        val out = ArrayList<Byte>()
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            out.add(byte.toByte())
        } while (remaining != 0)
        return out.toByteArray()
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private companion object {
        val MAGIC_AND_VERSION = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

        /** `(func (param i64 i64 i64 i64) (result i64 i64))` */
        val QUAD_I64_TO_PAIR = byteArrayOf(0x60, 0x04, 0x7E, 0x7E, 0x7E, 0x7E, 0x02, 0x7E, 0x7E)

        /** `(func (param i64 i64) (result i64 i64))` */
        val PAIR_I64_TO_PAIR = byteArrayOf(0x60, 0x02, 0x7E, 0x7E, 0x02, 0x7E, 0x7E)
    }
}
