package uk.shusek.krwa.wasm.types

class SectionId private constructor() {
    companion object {
        const val CUSTOM = 0
        const val TYPE = 1
        const val IMPORT = 2
        const val FUNCTION = 3
        const val TABLE = 4
        const val MEMORY = 5
        const val GLOBAL = 6
        const val EXPORT = 7
        const val START = 8
        const val ELEMENT = 9
        const val CODE = 10
        const val DATA = 11
        const val DATA_COUNT = 12
        const val TAG = 13
    }
}
