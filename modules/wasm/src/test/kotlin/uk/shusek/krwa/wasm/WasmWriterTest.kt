package uk.shusek.krwa.wasm

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.ParserTest.Companion.wasmCorpusFiles
import uk.shusek.krwa.wasm.types.RawSection

class WasmWriterTest {
    @Test
    fun shouldRoundTrip() {
        for (file in wasmCorpusFiles()) {
            // uses non-canonical size encodings
            if (file.name.endsWith("main.go.wasm")) {
                continue
            }

            val wasm = Files.readAllBytes(file.toPath())
            val writer = WasmWriter()
            Parser.parseWithoutDecoding(wasm) { section ->
                writer.writeSection(section as RawSection)
            }
            Parser.parse(writer.bytes())
            assertArrayEquals(wasm, writer.bytes())
        }
    }
}
