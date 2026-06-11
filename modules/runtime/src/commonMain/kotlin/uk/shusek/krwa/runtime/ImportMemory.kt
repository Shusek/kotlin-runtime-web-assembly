package uk.shusek.krwa.runtime

open class ImportMemory(
    private val module: String,
    private val name: String,
    private val memory: Memory?,
) : ImportValue {
    override fun module(): String = module

    override fun name(): String = name

    override fun type(): ImportValue.Type = ImportValue.Type.MEMORY

    fun memory(): Memory? = memory
}
