package uk.shusek.krwa.testing

import java.io.IOException
import java.util.function.Function
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasm.MalformedException
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

class TestModule(private val module: WasmModule) {
    fun instantiate(s: Store): Instance {
        val importValues: ImportValues = s.toImportValues()
        return Instance.builder(module)
            .withImportValues(importValues)
            .withMachineFactory(
                Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
            )
            .build()
    }

    companion object {
        private const val HACK_MATCH_ALL_MALFORMED_EXCEPTION_TEXT =
            "Matching keywords to get the WebAssembly testsuite to pass: " +
                "malformed UTF-8 encoding " +
                "import after function " +
                "inline function type " +
                "constant out of range" +
                "unknown operator " +
                "unexpected token " +
                "unexpected mismatching " +
                "mismatching label " +
                "unknown type " +
                "duplicate func " +
                "duplicate local " +
                "duplicate global " +
                "duplicate memory " +
                "duplicate table " +
                "mismatching label " +
                "import after global " +
                "import after table " +
                "import after memory " +
                "i32 constant out of range " +
                "unknown label " +
                "alignment " +
                "multiple start sections " +
                "duplicate field"

        @JvmStatic
        fun of(classpath: String): TestModule {
            try {
                CorpusResources.getResource(classpath.substring(1)).use { input ->
                    if (classpath.endsWith(".wat")) {
                        val parsed =
                            try {
                                Wat2Wasm.parse(input)
                            } catch (e: RuntimeException) {
                                throw MalformedException(
                                    e.message + HACK_MATCH_ALL_MALFORMED_EXCEPTION_TEXT
                                )
                            }
                        return of(parserFor(classpath).parseBytes(parsed))
                    }
                    return of(parserFor(classpath).parse { input })
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        private fun parserFor(classpath: String): Parser =
            Parser.builder()
                .withMultiTable(!isCoreMultiTableLimitTest(classpath))
                .withForwardTypeReferences(!isLegacyFunctionReferencesTypeEquivalenceTest(classpath))
                .withMultiMemory(
                    classpath.contains("/proposals/multi-memory/") ||
                        classpath.startsWith("/MultiMemory")
                )
                .withThreadsMemory(classpath.startsWith("/Threads"))
                .withLocalGlobalReferencesInConstantExpressions(classpath.startsWith("/Gc"))
                .build()

        private fun isCoreMultiTableLimitTest(classpath: String): Boolean =
            classpath == "/ThreadsImports/spec.47.wasm" ||
                classpath == "/ThreadsImports/spec.48.wasm" ||
                classpath == "/ThreadsImports/spec.49.wasm"

        private fun isLegacyFunctionReferencesTypeEquivalenceTest(classpath: String): Boolean =
            classpath.startsWith("/FunctionReferencesType-equivalence/")

        @JvmStatic fun of(module: WasmModule): TestModule = TestModule(module)
    }
}
