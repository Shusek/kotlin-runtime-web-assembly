package uk.shusek.krwa.wasm.types

class GlobalSection private constructor(globals: List<Global>) :
    Section(SectionId.GLOBAL.toLong()) {
    private val globals = globals.toList()

    fun globals(): Array<Global> = globals.toTypedArray()

    fun globalCount(): Int = globals.size

    fun getGlobal(idx: Int): Global = globals[idx]

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is GlobalSection) {
            return false
        }
        return globals == other.globals
    }

    override fun hashCode(): Int = globals.hashCode()

    class Builder {
        private val globals = ArrayList<Global>()

        fun addGlobal(global: Global): Builder {
            globals.add(global)
            return this
        }

        fun build(): GlobalSection = GlobalSection(globals)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
