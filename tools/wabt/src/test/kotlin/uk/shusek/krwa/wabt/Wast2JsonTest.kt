package uk.shusek.krwa.wabt

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Wast2JsonTest {
    @Test
    fun shouldRunWast2Json(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("fac").resolve("spec.json").toFile()
        val wast2Json =
            Wast2Json.builder()
                .withFile(File("src/test/resources/fac.wast"))
                .withOutput(outputFile)
                .build()

        wast2Json.process()

        assertTrue(outputFile.exists())
        assertTrue(outputFile.toPath().parent.resolve("spec.0.wasm").toFile().exists())
    }
}
