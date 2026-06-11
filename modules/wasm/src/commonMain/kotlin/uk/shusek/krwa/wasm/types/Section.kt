package uk.shusek.krwa.wasm.types

abstract class Section(id: Long) {
    private val id = id.toInt()

    fun sectionId(): Int = id
}
