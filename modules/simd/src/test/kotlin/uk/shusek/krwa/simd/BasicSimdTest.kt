package uk.shusek.krwa.simd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

class BasicSimdTest {
    @Test
    fun shouldRunBasicExample() {
        // from: https://blog.dkwr.de/development/wasm-simd-operations/
        val instance =
            Instance.builder(
                    Parser.parse(CorpusResources.getResource("compiled/simd-example.wat.wasm"))
                )
                .withMachineFactory(::SimdInterpreterMachine)
                .build()
        val main = instance.export("main")
        val result = main.apply()[0]
        assertEquals(6L, result)
    }
}
