package uk.shusek.krwa.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

class GcReferenceTypeTest {
    @Test
    fun compilerAcceptsNoneRefOperandsForRefEq() {
        val module = Parser.parse(noneRefEqModule())
        val instance =
            Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build()

        assertEquals(1L, instance.export("none-ref-eq").apply()[0])
    }

    private fun noneRefEqModule(): ByteArray =
        bytes(
            0x00,
            0x61,
            0x73,
            0x6d,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x05,
            0x01,
            0x60,
            0x00,
            0x01,
            0x7f,
            0x03,
            0x02,
            0x01,
            0x00,
            0x07,
            0x0f,
            0x01,
            0x0b,
            0x6e,
            0x6f,
            0x6e,
            0x65,
            0x2d,
            0x72,
            0x65,
            0x66,
            0x2d,
            0x65,
            0x71,
            0x00,
            0x00,
            0x0a,
            0x09,
            0x01,
            0x07,
            0x00,
            0xd0,
            0x71,
            0xd0,
            0x71,
            0xd3,
            0x0b,
        )

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
