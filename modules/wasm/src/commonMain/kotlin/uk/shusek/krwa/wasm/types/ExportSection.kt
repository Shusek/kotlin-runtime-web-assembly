package uk.shusek.krwa.wasm.types

class ExportSection private constructor(exports: List<Export>) :
    Section(SectionId.EXPORT.toLong()) {
    private val exports = exports.toList()

    fun exportCount(): Int = exports.size

    fun getExport(idx: Int): Export = exports[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ExportSection) {
            return false
        }
        return exports == other.exports
    }

    override fun hashCode(): Int = exports.hashCode()

    class Builder {
        private val exports = ArrayList<Export>()

        fun addExport(export: Export): Builder {
            exports.add(export)
            return this
        }

        fun build(): ExportSection = ExportSection(exports)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
