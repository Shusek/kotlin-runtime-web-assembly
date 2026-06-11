package uk.shusek.krwa.runtime

/** Marker interface for WasmGC heap objects (structs and arrays). */
fun interface WasmGcRef {
    fun typeIdx(): Int
}
