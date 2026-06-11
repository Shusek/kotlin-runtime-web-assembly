package uk.shusek.krwa.tools.wasm

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasm.Parser

class WasmToolsTest {
    @Test
    fun shouldRunWast2Json(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("fac").toFile()
        val wast2Json =
            Wast2Json.builder()
                .withFile(File("src/test/resources/fac.wast"))
                .withOutput(outputFile)
                .build()

        wast2Json.process()

        assertTrue(outputFile.exists())
        assertTrue(outputFile.toPath().resolve("spec.0.wasm").toFile().exists())
    }

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
            exitException.message?.contains("malformed UTF-8 encoding") == true,
            "found: ${exitException.message} doesn't contains the expected result",
        )
    }

    @Test
    fun shouldValidateWatBeforeParsing() {
        val exitException =
            assertThrows(WatParseException::class.java) {
                Wat2Wasm.parse(File("src/test/resources/address.1.wat"))
            }

        assertEquals(1, (exitException.cause as WasiExitException).exitCode())
        assertTrue(
            exitException.message?.contains("failed to validate") == true,
            "found: ${exitException.message} doesn't contains the expected result",
        )
    }

    @Test
    fun shouldValidateWat() {
        val exitException =
            assertThrows(WatParseException::class.java) {
                Validate.validate(File("src/test/resources/address.1.wat"))
            }

        assertEquals(1, (exitException.cause as WasiExitException).exitCode())
        assertTrue(
            exitException.message?.contains("failed to validate") == true,
            "found: ${exitException.message} doesn't contains the expected result",
        )
    }

    @Test
    fun shouldValidateSimpleModuleWithWasm1() {
        Validate.builder()
            .withFeatures(WasmFeature.WASM1)
            .build()
            .validateModule(
                "(module (func (export \"add\")" +
                    " (param i32) (param i32) (result i32)" +
                    " (i32.add (local.get 0) (local.get 1))))"
            )
    }

    @Test
    fun shouldRejectSimdModuleWithWasm1() {
        val validator = Validate.builder().withFeatures(WasmFeature.WASM1).build()
        assertThrows(WatParseException::class.java) {
            validator.validateModule("(module (func (result v128) (v128.const i32x4 0 0 0 0)))")
        }
    }

    @Test
    fun shouldAcceptSimdModuleWithWasm2() {
        Validate.builder()
            .withFeatures(WasmFeature.WASM2)
            .build()
            .validateModule("(module (func (result v128) (v128.const i32x4 0 0 0 0)))")
    }

    @Test
    fun shouldRejectSimdModuleWhenDisabled() {
        val validator =
            Validate.builder()
                .withFeatures(WasmFeature.WASM2)
                .withoutFeature(WasmFeature.SIMD)
                .build()
        assertThrows(WatParseException::class.java) {
            validator.validateModule("(module (func (result v128) (v128.const i32x4 0 0 0 0)))")
        }
    }
}
