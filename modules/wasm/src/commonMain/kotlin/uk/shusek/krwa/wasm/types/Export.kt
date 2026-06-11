package uk.shusek.krwa.wasm.types

open class Export(
    private val name: String,
    private val index: Int,
    private val exportType: ExternalType,
) {
    fun name(): String = name

    fun index(): Int = index

    fun exportType(): ExternalType = exportType

    override fun hashCode(): Int = (name.hashCode() * 31 + index) * 31 + exportType.hashCode()

    override fun equals(other: Any?): Boolean = other is Export && equals(other)

    fun equals(other: Export?): Boolean =
        this === other ||
            other != null &&
                index == other.index &&
                exportType == other.exportType &&
                name == other.name
}
