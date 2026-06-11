package uk.shusek.krwa.wasm.types

class TableSection private constructor(tables: List<Table>) : Section(SectionId.TABLE.toLong()) {
    private val tables = tables.toList()

    fun tableCount(): Int = tables.size

    fun getTable(idx: Int): Table = tables[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is TableSection) {
            return false
        }
        return tables == other.tables
    }

    override fun hashCode(): Int = tables.hashCode()

    class Builder {
        private val tables = ArrayList<Table>()

        fun addTable(table: Table): Builder {
            tables.add(table)
            return this
        }

        fun build(): TableSection = TableSection(tables)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
