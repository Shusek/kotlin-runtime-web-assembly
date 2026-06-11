package uk.shusek.krwa.wasm.types

class MemoryImport(moduleName: String, name: String, private val limits: MemoryLimits) :
    Import(moduleName, name) {

    fun limits(): MemoryLimits = limits

    override fun importType(): ExternalType = ExternalType.MEMORY

    override fun equals(other: Import?): Boolean = other is MemoryImport && equals(other)

    fun equals(other: MemoryImport?): Boolean =
        this === other || super.equals(other) && limits == other?.limits

    override fun hashCode(): Int = super.hashCode() * 19 + limits.hashCode()

    override fun toString(b: StringBuilder): StringBuilder {
        b.append("memory (limits=")
        limits.toString(b)
        b.append(')')
        return super.toString(b)
    }
}
