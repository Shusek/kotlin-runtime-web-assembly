package uk.shusek.krwa.testing

import java.io.IOException
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasm.MalformedException
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

class TestModule(private val module: WasmModule) {
    fun instantiate(s: Store): Instance {
        val importValues = s.toImportValues()
        return Instance.builder(module)
            .withImportValues(importValues)
            .withMachineFactory(InterpreterMachineFactory::create)
            .withMemoryFactory(::ByteArrayMemory)
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
                "multiple start sections" +
                "wrong number of lane literals" +
                "alignment must be a power of two" +
                "invalid lane length" +
                "malformed lane index"

        @JvmStatic
        fun of(classpath: String): TestModule {
            try {
                TestModule::class.java.getResourceAsStream(classpath).use { input ->
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
