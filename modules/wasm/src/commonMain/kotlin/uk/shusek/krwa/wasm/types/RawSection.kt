package uk.shusek.krwa.wasm.types

class RawSection(id: Long, contents: ByteArray) : Section(id) {
    private val contents = contents.copyOf()

    fun contents(): ByteArray = contents.copyOf()
}
