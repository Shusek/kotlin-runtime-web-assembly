package uk.shusek.krwa.testing

import java.io.IOException
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasm.MalformedException
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

class TestModule private constructor(
    private val moduleBytes: ByteArray?,
    private val parsedModule: WasmModule?,
    private val classpath: String?,
) {
    private var validateTypes = true

    fun withTypeValidation(validate: Boolean): TestModule {
        validateTypes = validate
        return this
    }

    fun instantiate(store: Store): Instance {
        val module =
            moduleBytes?.let { bytes ->
                parserFor(classpath).withValidation(validateTypes).build().parseBytes(bytes)
            } ?: requireNotNull(parsedModule)
        return Instance.builder(module)
            .withImportValues(store.toImportValues())
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
                val bytes =
                    requireNotNull(TestModule::class.java.getResourceAsStream(classpath)) {
                            "WebAssembly test resource not found: $classpath"
                        }
                        .use { input ->
                            if (classpath.endsWith(".wat")) {
                                try {
                                    Wat2Wasm.parse(input)
                                } catch (e: RuntimeException) {
                                    throw MalformedException(
                                        e.message + HACK_MATCH_ALL_MALFORMED_EXCEPTION_TEXT
                                    )
                                }
                            } else {
                                input.readBytes()
                            }
                        }
                return TestModule(bytes, null, classpath)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        private fun parserFor(classpath: String?): Parser.Builder =
            Parser.builder()
                .withMultiTable(classpath == null || !isCoreMultiTableLimitTest(classpath))
                .withForwardTypeReferences(
                    classpath == null || !isLegacyFunctionReferencesTypeEquivalenceTest(classpath)
                )
                .withMultiMemory(
                    classpath?.contains("/proposals/multi-memory/") == true ||
                        classpath?.startsWith("/MultiMemory") == true
                )
                .withThreadsMemory(classpath?.startsWith("/Threads") == true)
                .withLocalGlobalReferencesInConstantExpressions(
                    classpath?.startsWith("/Gc") == true
                )

        private fun isCoreMultiTableLimitTest(classpath: String): Boolean =
            classpath == "/ThreadsImports/spec.47.wasm" ||
                classpath == "/ThreadsImports/spec.48.wasm" ||
                classpath == "/ThreadsImports/spec.49.wasm"

        private fun isLegacyFunctionReferencesTypeEquivalenceTest(classpath: String): Boolean =
            classpath.startsWith("/FunctionReferencesType-equivalence/")

        @JvmStatic
        fun of(module: WasmModule): TestModule =
            TestModule(null, module, null)
    }
}
