package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.Table

/** Factory for creating [TableInstance] objects during module instantiation. */
fun interface TableFactory {
    fun create(table: Table, initValue: Int): TableInstance
}
