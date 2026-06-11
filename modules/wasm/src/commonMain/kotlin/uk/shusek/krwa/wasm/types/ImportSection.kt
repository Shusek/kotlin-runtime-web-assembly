package uk.shusek.krwa.wasm.types

class ImportSection private constructor(imports: List<Import>) :
    Section(SectionId.IMPORT.toLong()) {
    private val imports = imports.toList()

    fun importCount(): Int = imports.size

    fun getImport(idx: Int): Import = imports[idx]

    fun imports(): Array<Import> = imports.toTypedArray()

    fun count(type: ExternalType?): Int = imports.count { it.importType() == type }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ImportSection) {
            return false
        }
        return imports == other.imports
    }

    override fun hashCode(): Int = imports.hashCode()

    class Builder {
        private val imports = ArrayList<Import>()

        fun addImport(import_: Import): Builder {
            imports.add(import_)
            return this
        }

        fun build(): ImportSection = ImportSection(imports)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
