package uk.shusek.krwa.wasm

internal fun assert(condition: Boolean) {
    check(condition) { "Assertion failed" }
}
