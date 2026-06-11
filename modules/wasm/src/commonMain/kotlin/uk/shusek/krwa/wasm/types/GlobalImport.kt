package uk.shusek.krwa.wasm.types

class GlobalImport(
    moduleName: String,
    name: String,
    private val mutabilityType: MutabilityType,
    private val type: ValType,
) : Import(moduleName, name) {
    @Deprecated("use GlobalImport(String, String, MutabilityType, ValType)")
    constructor(
        moduleName: String,
        name: String,
        mutabilityType: MutabilityType,
        type: ValueType,
    ) : this(moduleName, name, mutabilityType, type.toValType())

    fun mutabilityType(): MutabilityType = mutabilityType

    fun type(): ValType = type

    override fun importType(): ExternalType = ExternalType.GLOBAL

    override fun equals(other: Import?): Boolean = other is GlobalImport && equals(other)

    fun equals(other: GlobalImport?): Boolean =
        this === other ||
            super.equals(other) && mutabilityType == other?.mutabilityType && type == other.type

    override fun hashCode(): Int =
        (super.hashCode() * 19 + mutabilityType.hashCode()) * 19 + type.hashCode()

    override fun toString(b: StringBuilder): StringBuilder {
        b.append("global (type=").append(type).append(",mut=").append(mutabilityType).append(')')
        return super.toString(b)
    }
}
