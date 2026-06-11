package uk.shusek.krwa.wasm.types

/** A custom section which is unknown to the parser. */
class UnknownCustomSection private constructor(name: String, bytes: ByteArray) : CustomSection() {
    private val name = name
    private val bytes = bytes.copyOf()

    override fun name(): String = name

    fun bytes(): ByteArray = bytes.copyOf()

    class Builder {
        private var name: String? = null
        private var bytes: ByteArray? = null

        fun withName(name: String): Builder {
            this.name = name
            return this
        }

        fun withBytes(bytes: ByteArray): Builder {
            this.bytes = bytes
            return this
        }

        fun build(): UnknownCustomSection = UnknownCustomSection(name!!, bytes!!)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
