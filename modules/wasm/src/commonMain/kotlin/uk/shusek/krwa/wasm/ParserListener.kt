package uk.shusek.krwa.wasm

import uk.shusek.krwa.wasm.types.Section

fun interface ParserListener {
    fun onSection(section: Section)
}
