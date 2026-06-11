package uk.shusek.krwa.wabt

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.WatGenerator
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasm.Parser

class Wat2WasmTest {
    @Test
    fun shouldRunWat2Wasm() {
        val result =
            Wat2Wasm.parse(File("../../testing/wasm-corpus/src/main/resources/wat/iterfact.wat"))

        assertTrue(result.isNotEmpty())
        assertTrue(String(result, UTF_8).contains("iterFact"))
    }

    @Test
    fun shouldRunWat2WasmOnString() {
        val moduleInstance =
            Instance.builder(
                    Parser.parse(
                        Wat2Wasm.parse(
                            "(module (func (export \"add\") (param \$x" +
                                " i32) (param \$y i32) (result i32)" +
                                " (i32.add (local.get \$x) (local.get" +
                                " \$y))))"
                        )
                    )
                )
                .withInitialize(true)
                .build()

        val addFunction = moduleInstance.export("add")
        val results = addFunction.apply(1, 41)
        assertEquals(42L, results[0])
    }

    @Test
    fun shouldThrowMalformedException() {
        val exitException =
            assertThrows(WatParseException::class.java) {
                Wat2Wasm.parse(File("src/test/resources/utf8-invalid-encoding-spec.0.wat"))
            }

        assertEquals(1, (exitException.cause as WasiExitException).exitCode())
        assertTrue(
            exitException.message?.contains("invalid utf-8 encoding") == true,
            "found: ${exitException.message} doesn't contains the expected result",
        )
    }

    @Test
    fun canCompile50kFunctions() {
        val wat = WatGenerator.bigWat(50_000, 0)
        Wat2Wasm.parse(wat)
    }

    @Test
    fun canCompileBigFunctions() {
        val wat = WatGenerator.bigWat(10, 15_000)
        Wat2Wasm.parse(wat)
    }
}
