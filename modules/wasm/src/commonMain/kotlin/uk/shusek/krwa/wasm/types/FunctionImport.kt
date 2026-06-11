package uk.shusek.krwa.wasm.types

class FunctionImport(moduleName: String, name: String, private val typeIndex: Int) :
    Import(moduleName, name) {

    fun typeIndex(): Int = typeIndex

    override fun importType(): ExternalType = ExternalType.FUNCTION

    override fun equals(other: Import?): Boolean = other is FunctionImport && equals(other)

    fun equals(other: FunctionImport?): Boolean =
        this === other || super.equals(other) && typeIndex == other?.typeIndex

    override fun hashCode(): Int = super.hashCode() * 19 + typeIndex

    override fun toString(b: StringBuilder): StringBuilder {
        b.append("func (type=").append(typeIndex).append(')')
        return super.toString(b)
    }
}
