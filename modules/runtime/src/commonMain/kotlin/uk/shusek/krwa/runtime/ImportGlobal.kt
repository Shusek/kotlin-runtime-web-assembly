package uk.shusek.krwa.runtime

open class ImportGlobal(
    private val module: String,
    private val name: String,
    private val instance: GlobalInstance,
) : ImportValue {
    fun instance(): GlobalInstance = instance

    override fun module(): String = module

    override fun name(): String = name

    override fun type(): ImportValue.Type = ImportValue.Type.GLOBAL
}
