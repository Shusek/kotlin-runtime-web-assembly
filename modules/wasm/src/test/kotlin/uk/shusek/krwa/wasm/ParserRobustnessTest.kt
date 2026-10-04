package uk.shusek.krwa.wasm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.types.OpCode

/**
 * Crafted inputs must be reported through the documented parser and validator exception types,
 * never as raw runtime exceptions such as [ArrayIndexOutOfBoundsException], [NullPointerException]
 * or [StackOverflowError], because hosts that run untrusted plugins catch only the documented types.
 */
class ParserRobustnessTest {
    @Test
    fun unknownMultiByteOpcodeIsReportedAsMalformed() {
        // 0xFB prefix with LEB128 sub-opcode 0x400 decodes to 0xFF00, one past the opcode table.
        val body = bytes(0xFB, 0x80, 0x08, 0x0B)

        assertThrows(MalformedException::class.java) { WasmParser.parse(functionModule(body)) }
    }

    @Test
    fun opcodeLookupTreatsOutOfRangeValuesAsUnknown() {
        assertNull(OpCode.byOpCode(-1))
        assertNull(OpCode.byOpCode(0xFF00))
        assertNull(OpCode.byOpCode(Int.MAX_VALUE))
        assertEquals(OpCode.END, OpCode.byOpCode(0x0B))
    }

    @Test
    fun unbalancedEndIsReportedAsMalformed() {
        // end; end; block (empty) ; end
        assertThrows(MalformedException::class.java) {
            WasmParser.parse(functionModule(bytes(0x0B, 0x0B, 0x02, 0x40, 0x0B)))
        }
        // end; end; br 0
        assertThrows(MalformedException::class.java) {
            WasmParser.parse(functionModule(bytes(0x0B, 0x0B, 0x0C, 0x00)))
        }
    }

    @Test
    fun forwardSupertypeReferenceInRecursionGroupIsInvalid() {
        // rec { (sub 1 (struct)) (sub 0 (struct)) }: each type names the other as its supertype.
        val recGroup =
            bytes(0x4E, 0x02) +
                bytes(0x50, 0x01, 0x01, 0x5F, 0x00) +
                bytes(0x50, 0x01, 0x00, 0x5F, 0x00)
        val module = wasmModule(section(1, bytes(0x01) + recGroup))

        assertThrows(InvalidException::class.java) { WasmParser.parse(module) }
    }

    @Test
    fun subtypeHierarchyDeeperThanTheLimitIsInvalid() {
        val limit = WasmLimits.MAX_SUBTYPE_DEPTH

        // A chain of limit + 1 types has depth `limit` and is accepted.
        WasmParser.parse(wasmModule(section(1, subtypeChain(limit + 1))))

        // One more link exceeds the limit and must be rejected before any subtype check recurses.
        assertThrows(InvalidException::class.java) {
            WasmParser.parse(wasmModule(section(1, subtypeChain(limit + 2))))
        }
    }

    private fun subtypeChain(count: Int): ByteArray {
        var out = unsignedLeb128(count)
        for (index in 0 until count) {
            out +=
                if (index == 0) {
                    bytes(0x50, 0x00, 0x5F, 0x00)
                } else {
                    bytes(0x50, 0x01) + unsignedLeb128(index - 1) + bytes(0x5F, 0x00)
                }
        }
        return out
    }

    private fun functionModule(body: ByteArray): ByteArray =
        wasmModule(
            section(1, bytes(0x01, 0x60, 0x00, 0x00)),
            section(3, bytes(0x01, 0x00)),
            section(10, bytes(0x01) + unsignedLeb128(body.size + 1) + bytes(0x00) + body),
        )

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
    }
}
