package uk.shusek.krwa.wasm.types

/** A custom section of some kind. */
abstract class CustomSection : Section(SectionId.CUSTOM.toLong()) {
    abstract fun name(): String
}
