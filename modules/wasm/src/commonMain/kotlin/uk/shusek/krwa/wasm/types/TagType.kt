package uk.shusek.krwa.wasm.types

open class TagType(private val attribute: Byte, private val typeIdx: Int) {
    fun attribute(): Byte = attribute

    fun typeIdx(): Int = typeIdx
}
