package uk.shusek.krwa.wasm.types

internal fun assert(condition: Boolean) {
    check(condition) { "Assertion failed" }
}
