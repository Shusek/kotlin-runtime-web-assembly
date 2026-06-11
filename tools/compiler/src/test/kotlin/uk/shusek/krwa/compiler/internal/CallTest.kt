package uk.shusek.krwa.compiler.internal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

class CallTest {
    @Test
    fun callLotsOfArgs() {
        val module = Parser.parse(CorpusResources.getResource("compiled/lots-of-args.wat.wasm"))
        val instance =
            Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build()

        val function = instance.export("test")
        val result = function.apply(2, 3)
        assertArrayEquals(longArrayOf(5), result)
    }

    @Test
    @Suppress("DEPRECATION")
    fun callLotsOfArgsOnDeprecatedAotMachine() {
        val module = Parser.parse(CorpusResources.getResource("compiled/lots-of-args.wat.wasm"))
        val instance =
            Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build()

        val function = instance.export("test")
        val result = function.apply(2, 3)
        assertArrayEquals(longArrayOf(5), result)
    }
}
