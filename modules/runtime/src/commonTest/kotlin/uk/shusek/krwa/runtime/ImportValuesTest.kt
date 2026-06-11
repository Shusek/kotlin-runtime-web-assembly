package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Value

class ImportValuesTest {
    @Test
    fun emptyBuilderHasNoImports() {
        val result = ImportValues.builder().build()

        assertEquals(0, result.functionCount())
        assertEquals(0, result.globalCount())
        assertEquals(0, result.memoryCount())
        assertEquals(0, result.tableCount())
    }

    @Test
    fun builderAcceptsFunctionList() {
        val result =
            ImportValues.builder()
                .withFunctions(
                    listOf(
                        HostFunction("module_1", "", FunctionType.empty(), null),
                        HostFunction("module_2", "", FunctionType.empty(), null),
                    )
                )
                .build()

        assertEquals(2, result.functionCount())
    }

    @Test
    fun builderAddsFunctions() {
        val result =
            ImportValues.builder()
                .addFunction(HostFunction("module_1", "", FunctionType.empty(), null))
                .addFunction(HostFunction("module_2", "", FunctionType.empty(), null))
                .build()

        assertEquals(2, result.functionCount())
    }

    @Test
    fun builderAcceptsGlobalList() {
        val result =
            ImportValues.builder()
                .withGlobals(
                    listOf(
                        ImportGlobal("spectest", "global_i32", GlobalInstance(Value.i32(666))),
                        ImportGlobal("spectest", "global_i64", GlobalInstance(Value.i64(666))),
                    )
                )
                .build()

        assertEquals(2, result.globalCount())
    }

    @Test
    fun builderAddsGlobals() {
        val result =
            ImportValues.builder()
                .addGlobal(ImportGlobal("spectest", "global_i32", GlobalInstance(Value.i32(666))))
                .addGlobal(ImportGlobal("spectest", "global_i64", GlobalInstance(Value.i64(666))))
                .build()

        assertEquals(2, result.globalCount())
    }

    @Test
    fun builderAcceptsMemoryList() {
        val result =
            ImportValues.builder()
                .withMemories(
                    listOf(
                        ImportMemory("spectest", "memory", null),
                        ImportMemory("spectest", "memory_2", null),
                    )
                )
                .build()

        assertEquals(2, result.memoryCount())
    }

    @Test
    fun builderAddsMemories() {
        val result =
            ImportValues.builder()
                .addMemory(ImportMemory("spectest", "memory", null))
                .addMemory(ImportMemory("spectest", "memory_2", null))
                .build()

        assertEquals(2, result.memoryCount())
    }

    @Test
    fun builderAcceptsTableList() {
        val result =
            ImportValues.builder()
                .withTables(
                    listOf(
                        ImportTable("spectest", "table", emptyMap<Int, Int>()),
                        ImportTable("spectest", "table_2", emptyMap<Int, Int>()),
                    )
                )
                .build()

        assertEquals(2, result.tableCount())
    }

    @Test
    fun builderAddsTables() {
        val result =
            ImportValues.builder()
                .addTable(ImportTable("spectest", "table", emptyMap<Int, Int>()))
                .addTable(ImportTable("spectest", "table_2", emptyMap<Int, Int>()))
                .build()

        assertEquals(2, result.tableCount())
    }
}
