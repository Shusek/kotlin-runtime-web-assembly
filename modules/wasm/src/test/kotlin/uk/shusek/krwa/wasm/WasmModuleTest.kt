package uk.shusek.krwa.wasm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources

class WasmModuleTest {
    @Test
    fun shouldHaveTheSameHashCode() {
        val mod1 = Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"))
        val mod2 = Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"))

        assertEquals(mod1.hashCode(), mod2.hashCode())
    }

    @Test
    fun shouldBeEquals() {
        val mod1 = Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"))
        val mod2 = Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"))

        assertTrue(mod1 == mod2)
    }
}
