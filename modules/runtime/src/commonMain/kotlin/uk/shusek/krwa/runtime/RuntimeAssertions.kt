package uk.shusek.krwa.runtime

internal fun assert(condition: Boolean) {
    check(condition) { "Assertion failed" }
}
