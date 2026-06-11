package uk.shusek.krwa.runtime

class ImportValues
private constructor(
    functions: Array<ImportFunction>,
    globals: Array<ImportGlobal>,
    memories: Array<ImportMemory>,
    tables: Array<ImportTable>,
    tags: Array<ImportTag>,
) {
    private val functions: Array<ImportFunction> = functions.copyOf()
    private val globals: Array<ImportGlobal> = globals.copyOf()
    private val memories: Array<ImportMemory> = memories.copyOf()
    private val tables: Array<ImportTable> = tables.copyOf()
    private val tags: Array<ImportTag> = tags.copyOf()

    fun functions(): Array<ImportFunction> = functions.copyOf()

    fun functionCount(): Int = functions.size

    fun function(idx: Int): ImportFunction = functions[idx]

    fun globals(): Array<ImportGlobal> = globals

    fun globalCount(): Int = globals.size

    fun global(idx: Int): ImportGlobal = globals[idx]

    fun memories(): Array<ImportMemory> = memories

    fun memoryCount(): Int = memories.size

    fun memory(idx: Int): ImportMemory = memories[idx]

    fun tables(): Array<ImportTable> = tables

    fun tableCount(): Int = tables.size

    fun table(idx: Int): ImportTable = tables[idx]

    fun tags(): Array<ImportTag> = tags

    fun tagCount(): Int = tags.size

    fun tag(idx: Int): ImportTag = tags[idx]

    class Builder internal constructor() {
        private var functions: Collection<ImportFunction>? = null
        private var globals: Collection<ImportGlobal>? = null
        private var memories: Collection<ImportMemory>? = null
        private var tables: Collection<ImportTable>? = null
        private var tags: Collection<ImportTag>? = null

        fun withFunctions(functions: Collection<ImportFunction>): Builder {
            this.functions = functions
            return this
        }

        fun addFunction(vararg function: ImportFunction): Builder {
            functions = append(functions, function)
            return this
        }

        fun withGlobals(globals: Collection<ImportGlobal>): Builder {
            this.globals = globals
            return this
        }

        fun addGlobal(vararg global: ImportGlobal): Builder {
            globals = append(globals, global)
            return this
        }

        fun withMemories(memories: Collection<ImportMemory>): Builder {
            this.memories = memories
            return this
        }

        fun addMemory(vararg memory: ImportMemory): Builder {
            memories = append(memories, memory)
            return this
        }

        fun withTables(tables: Collection<ImportTable>): Builder {
            this.tables = tables
            return this
        }

        fun addTable(vararg table: ImportTable): Builder {
            tables = append(tables, table)
            return this
        }

        fun withTags(tags: Collection<ImportTag>): Builder {
            this.tags = tags
            return this
        }

        fun addTag(vararg tag: ImportTag): Builder {
            tags = append(tags, tag)
            return this
        }

        fun build(): ImportValues =
            ImportValues(
                functions?.toTypedArray() ?: NO_EXTERNAL_FUNCTIONS,
                globals?.toTypedArray() ?: NO_EXTERNAL_GLOBALS,
                memories?.toTypedArray() ?: NO_EXTERNAL_MEMORIES,
                tables?.toTypedArray() ?: NO_EXTERNAL_TABLES,
                tags?.toTypedArray() ?: NO_EXTERNAL_TAGS,
            )

        private fun <T> append(collection: Collection<T>?, values: Array<out T>): Collection<T> {
            val target =
                when (collection) {
                    null -> ArrayList<T>()
                    is MutableCollection<T> -> collection
                    else -> ArrayList(collection)
                }
            target.addAll(values)
            return target
        }
    }

    companion object {
        private val NO_EXTERNAL_FUNCTIONS = emptyArray<ImportFunction>()
        private val NO_EXTERNAL_GLOBALS = emptyArray<ImportGlobal>()
        private val NO_EXTERNAL_MEMORIES = emptyArray<ImportMemory>()
        private val NO_EXTERNAL_TABLES = emptyArray<ImportTable>()
        private val NO_EXTERNAL_TAGS = emptyArray<ImportTag>()

        fun builder(): Builder = Builder()

        fun empty(): ImportValues = Builder().build()
    }
}
