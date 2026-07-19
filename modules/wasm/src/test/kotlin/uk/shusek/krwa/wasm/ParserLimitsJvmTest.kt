package uk.shusek.krwa.wasm

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ParserLimitsJvmTest {
    @Test
    fun enforcesModuleByteBoundaryBeforeBufferingAnInputStream() {
        Parser.builder()
            .withLimits(WasmParserLimits(maxModuleBytes = EMPTY_MODULE.size.toLong()))
            .build()
            .parse { ByteArrayInputStream(EMPTY_MODULE) }

        val failure =
            assertThrows(WasmParseLimitException::class.java) {
                Parser.builder()
                    .withLimits(WasmParserLimits(maxModuleBytes = EMPTY_MODULE.size - 1L))
                    .build()
                    .parse { ByteArrayInputStream(EMPTY_MODULE) }
            }

        assertEquals("maxModuleBytes", failure.limitName)
        assertEquals(EMPTY_MODULE.size - 1L, failure.configuredLimit)
        assertEquals(EMPTY_MODULE.size.toLong(), failure.actual)
    }

    private companion object {
        val EMPTY_MODULE =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
