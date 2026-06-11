package uk.shusek.krwa.wasm.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.wasm.Parser

class ValTypeJvmTest {
    @Test
    fun checkExternRef() {
        val module =
            Parser.parse(CorpusResources.getResource("compiled/externref-example.wat.wasm"))

        assertEquals(3, module.typeSection().types().size)

        val type0 = module.typeSection().types()[0].returns()[0]
        assertEquals(ValType.TypeIdxCode.EXTERN.code(), type0.typeIdx())
    }
}
