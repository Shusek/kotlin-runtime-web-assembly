package uk.shusek.krwa.wasm.types

abstract class Import(private val module: String, private val name: String) {
    fun module(): String = module

    fun name(): String = name

    abstract fun importType(): ExternalType

    override fun equals(other: Any?): Boolean = other is Import && equals(other)

    open fun equals(other: Import?): Boolean =
        other != null && module == other.module && name == other.name

    override fun hashCode(): Int = module.hashCode() * 31 + name.hashCode()

    open fun toString(b: StringBuilder): StringBuilder =
        b.append('<').append(module).append('.').append(name).append('>')

    override fun toString(): String = toString(StringBuilder()).toString()
}
