package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType

/** The runtime storage for all function, global, memory, table instances. */
class Store {
    val functions = LinkedHashMap<QualifiedName, ImportFunction>()
    val globals = LinkedHashMap<QualifiedName, ImportGlobal>()
    val memories = LinkedHashMap<QualifiedName, ImportMemory>()
    val tables = LinkedHashMap<QualifiedName, ImportTable>()
    val tags = LinkedHashMap<QualifiedName, ImportTag>()

    /** Add a function to the store. */
    fun addFunction(vararg function: ImportFunction): Store {
        for (f in function) {
            functions[QualifiedName(f.module(), f.name())] = f
        }
        return this
    }

    /** Add a global to the store. */
    fun addGlobal(vararg global: ImportGlobal): Store {
        for (g in global) {
            globals[QualifiedName(g.module(), g.name())] = g
        }
        return this
    }

    /** Add a memory to the store. */
    fun addMemory(vararg memory: ImportMemory): Store {
        for (m in memory) {
            memories[QualifiedName(m.module(), m.name())] = m
        }
        return this
    }

    /** Add a table to the store. */
    fun addTable(vararg table: ImportTable): Store {
        for (t in table) {
            tables[QualifiedName(t.module(), t.name())] = t
        }
        return this
    }

    /** Add a tag to the store. */
    fun addTag(vararg tag: ImportTag): Store {
        for (t in tag) {
            tags[QualifiedName(t.module(), t.name())] = t
        }
        return this
    }

    /** Add the contents of an [ImportValues] instance to the store. */
    fun addImportValues(importValues: ImportValues): Store =
        addGlobal(*importValues.globals())
            .addFunction(*importValues.functions())
            .addMemory(*importValues.memories())
            .addTable(*importValues.tables())
            .addTag(*importValues.tags())

    /** Convert the contents of a store to an [ImportValues] instance. */
    fun toImportValues(): ImportValues =
        ImportValues.builder()
            .withFunctions(functions.values)
            .withGlobals(globals.values)
            .withMemories(memories.values)
            .withTables(tables.values)
            .withTags(tags.values)
            .build()

    /**
     * Register an instance in the store with the given name. All the exported functions, globals,
     * memories, and tables are added to the store with the given name.
     *
     * For instance, if a module named "myModule" exports a function named "myFunction", the
     * function will be added to the store with the name "myFunction.myModule".
     */
    fun register(name: String, instance: Instance): Store {
        val exportSection = instance.module().exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            val exportName = export.name()
            when (export.exportType()) {
                ExternalType.FUNCTION ->
                    addFunction(
                        ImportFunction.exportAsImport(name, exportName, instance, exportName)
                    )
                ExternalType.TABLE ->
                    addTable(ImportTable(name, exportName, instance.table(export.index())))
                ExternalType.MEMORY ->
                    addMemory(ImportMemory(name, exportName, instance.memory(export.index())))
                ExternalType.GLOBAL -> {
                    val global = instance.global(export.index())
                    addGlobal(ImportGlobal(name, exportName, global))
                }
                ExternalType.TAG ->
                    addTag(ImportTag(name, exportName, instance.tag(export.index())))
            }
        }
        return this
    }

    /** A shorthand for instantiating a module and registering it in the store. */
    fun instantiate(name: String, module: WasmModule): Instance =
        instantiate(name) { imports -> Instance.builder(module).withImportValues(imports).build() }

    /** Creates an instance with the given factory and registers the result in the store. */
    fun instantiate(name: String, instanceFactory: (ImportValues) -> Instance): Instance {
        val importValues = toImportValues()
        val instance = instanceFactory(importValues)
        register(name, instance)
        return instance
    }

    /** QualifiedName is internally used to use pairs (moduleName, name) as keys in the store. */
    data class QualifiedName(private val module: String, private val name: String)
}
