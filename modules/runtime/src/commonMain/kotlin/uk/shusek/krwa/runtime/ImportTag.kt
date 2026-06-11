package uk.shusek.krwa.runtime

open class ImportTag(
    private val module: String,
    private val name: String,
    private val tag: TagInstance,
) : ImportValue {
    override fun module(): String = module

    override fun name(): String = name

    override fun type(): ImportValue.Type = ImportValue.Type.TAG

    fun tag(): TagInstance = tag
}
