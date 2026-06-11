package uk.shusek.krwa.wasm.types

class TableImport(
    moduleName: String,
    name: String,
    private val entryType: ValType,
    private val limits: TableLimits,
) : Import(moduleName, name) {
    @Deprecated("use TableImport(String, String, ValType, TableLimits)")
    constructor(
        moduleName: String,
        name: String,
        entryType: ValueType,
        limits: TableLimits,
    ) : this(moduleName, name, entryType.toValType(), limits)

    fun entryType(): ValType = entryType

    fun limits(): TableLimits = limits

    override fun importType(): ExternalType = ExternalType.TABLE

    override fun equals(other: Import?): Boolean = other is TableImport && equals(other)

    fun equals(other: TableImport?): Boolean =
        this === other ||
            super.equals(other) && entryType == other?.entryType && limits == other.limits

    override fun hashCode(): Int =
        (super.hashCode() * 19 + entryType.hashCode()) * 19 + limits.hashCode()

    override fun toString(b: StringBuilder): StringBuilder {
        b.append("table (type=").append(entryType).append(",limits=")
        limits.toString(b)
        b.append(')')
        return super.toString(b)
    }
}
