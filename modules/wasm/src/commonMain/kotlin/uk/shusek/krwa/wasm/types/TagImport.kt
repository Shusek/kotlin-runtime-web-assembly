package uk.shusek.krwa.wasm.types

class TagImport(moduleName: String, name: String, attribute: Byte, tagTypeIdx: Int) :
    Import(moduleName, name) {
    private val tagType = TagType(attribute, tagTypeIdx)

    fun tagType(): TagType = tagType

    override fun importType(): ExternalType = ExternalType.TAG

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is TagImport) {
            return false
        }
        if (!super.equals(other)) {
            return false
        }
        return tagType == other.tagType
    }

    override fun hashCode(): Int = super.hashCode() * 19 + tagType.hashCode()

    override fun toString(): String = "TagImport{tagType=$tagType}"
}
