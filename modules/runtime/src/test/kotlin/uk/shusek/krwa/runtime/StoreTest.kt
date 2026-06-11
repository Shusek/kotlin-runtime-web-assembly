package uk.shusek.krwa.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType

class StoreTest {
    @Test
    fun nameClashesShouldOverwriteTheStore() {
        val store = Store()

        val f1 = HostFunction("m", "f", FunctionType.empty(), null)
        store.addFunction(f1)
        assertEquals(f1, store.functions[Store.QualifiedName("m", "f")])

        val f2 = HostFunction("m", "f", FunctionType.empty(), null)
        store.addFunction(f2)
        assertEquals(f2, store.functions[Store.QualifiedName("m", "f")])
    }

    @Test
    fun exportsShouldBeRegistered() {
        val instance = Instance.builder(loadModule("compiled/exports.wat.wasm")).build()
        val store = Store()
        val moduleName = "exports-module"
        store.register(moduleName, instance)

        assertEquals(1, store.memories.size)
        assertTrue(store.memories.containsKey(Store.QualifiedName(moduleName, "mem")))

        assertEquals(1, store.tables.size)
        assertTrue(store.tables.containsKey(Store.QualifiedName(moduleName, "tab")))

        assertEquals(4, store.globals.size)
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob1")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob2")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob3")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob4")))

        assertEquals(4, store.functions.size)
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-1")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-2")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-3")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-4")))
    }

    @Test
    fun instantiateShouldRegisterInstance() {
        val store = Store()
        val moduleName = "exports-module"
        val inst = store.instantiate(moduleName, loadModule("compiled/exports.wat.wasm"))
        assertNotNull(inst)

        assertEquals(1, store.memories.size)
        assertTrue(store.memories.containsKey(Store.QualifiedName(moduleName, "mem")))

        assertEquals(1, store.tables.size)
        assertTrue(store.tables.containsKey(Store.QualifiedName(moduleName, "tab")))

        assertEquals(4, store.globals.size)
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob1")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob2")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob3")))
        assertTrue(store.globals.containsKey(Store.QualifiedName(moduleName, "glob4")))

        assertEquals(4, store.functions.size)
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-1")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-2")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-3")))
        assertTrue(store.functions.containsKey(Store.QualifiedName(moduleName, "get-4")))
    }

    @Test
    fun registerMultipleInstancesDifferentNamesCauseNoClash() {
        val store = Store()

        val name1 = "exports-module-1"
        store.instantiate(name1, loadModule("compiled/exports.wat.wasm"))

        val name2 = "exports-module-2"
        store.instantiate(name2, loadModule("compiled/exports.wat.wasm"))

        val names = listOf(name1, name2)

        assertEquals(2, store.memories.size)
        for (name in names) {
            assertTrue(store.memories.containsKey(Store.QualifiedName(name, "mem")))
        }

        assertEquals(2, store.tables.size)
        for (name in names) {
            assertTrue(store.tables.containsKey(Store.QualifiedName(name, "tab")))
        }

        assertEquals(8, store.globals.size)
        for (name in names) {
            assertTrue(store.globals.containsKey(Store.QualifiedName(name, "glob1")))
            assertTrue(store.globals.containsKey(Store.QualifiedName(name, "glob2")))
            assertTrue(store.globals.containsKey(Store.QualifiedName(name, "glob3")))
            assertTrue(store.globals.containsKey(Store.QualifiedName(name, "glob4")))
        }

        assertEquals(8, store.functions.size)
        for (name in names) {
            assertTrue(store.functions.containsKey(Store.QualifiedName(name, "get-1")))
            assertTrue(store.functions.containsKey(Store.QualifiedName(name, "get-2")))
            assertTrue(store.functions.containsKey(Store.QualifiedName(name, "get-3")))
            assertTrue(store.functions.containsKey(Store.QualifiedName(name, "get-4")))
        }
    }

    @Test
    fun moduleInstantiationShouldBeConfigurable() {
        val store = Store()

        val name1 = "exports-module-1"
        store.instantiate(name1) { imports ->
            Instance.builder(loadModule("compiled/exports.wat.wasm"))
                .withImportValues(imports)
                .withStart(false)
                .build()
        }

        val name2 = "exports-module-2"
        store.instantiate(name2) { imports ->
            Instance.builder(loadModule("compiled/exports.wat.wasm"))
                .withImportValues(imports)
                .build()
        }

        assertEquals(2, store.memories.size)
    }

    private fun loadModule(fileName: String): WasmModule =
        Parser.parse(CorpusResources.getResource(fileName))
}
