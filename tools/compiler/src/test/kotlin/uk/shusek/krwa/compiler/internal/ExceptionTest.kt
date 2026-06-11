package uk.shusek.krwa.compiler.internal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

class ExceptionTest {
    @Test
    fun throwFromInterpretedCatchCompiled() {
        val instance =
            Instance.builder(MODULE)
                .withMachineFactory(
                    MachineFactoryCompiler.builder(MODULE)
                        .withInterpretedFunctions(setOf(throwIfFn))
                        .compile()
                )
                .build()

        val function = instance.export("catchless-try")
        assertArrayEquals(longArrayOf(0), function.apply(0))
        assertArrayEquals(longArrayOf(1), function.apply(1))
    }

    @Test
    fun throwFromCompiledCatchInterpreted() {
        val instance =
            Instance.builder(MODULE)
                .withMachineFactory(
                    MachineFactoryCompiler.builder(MODULE)
                        .withInterpretedFunctions(setOf(catchlessTryFn))
                        .compile()
                )
                .build()

        val function = instance.export("catchless-try")
        assertArrayEquals(longArrayOf(0), function.apply(0))
        assertArrayEquals(longArrayOf(1), function.apply(1))
    }

    companion object {
        private val MODULE: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/exceptions.wat.wasm"))
        private val throwIfFn: Int
        private val catchlessTryFn: Int

        init {
            val indexes = getFunctionNameIndex(MODULE, "throw-if", "catchless-try")
            throwIfFn = indexes[0]
            catchlessTryFn = indexes[1]
        }

        @JvmStatic
        fun getFunctionNameIndex(module: WasmModule, vararg functionNames: String): IntArray {
            val functionNameIndex = HashMap<String?, Int>()
            for (i in 0 until module.functionSection().functionCount()) {
                functionNameIndex[module.nameSection()!!.nameOfFunction(i)] = i
            }

            val result = IntArray(functionNames.size)
            for (i in functionNames.indices) {
                val index =
                    functionNameIndex[functionNames[i]]
                        ?: throw IllegalArgumentException(
                            "Function ${functionNames[i]} not found in module"
                        )
                result[i] = index
            }
            return result
        }
    }
}
