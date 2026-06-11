package uk.shusek.krwa.wasm

// Spec: https://webassembly.github.io/spec/core/appendix/implementation.html#syntactic-limits
// From: https://github.com/WebKit/webkit/blob/main/Source/JavaScriptCore/wasm/WasmLimits.h
object WasmLimits {
    const val MAX_TYPES = 1000000
    const val MAX_FUNCTIONS = 1000000
    const val MAX_IMPORTS = 100000
    const val MAX_EXPORTS = 100000
    const val MAX_EXCEPTIONS = 100000
    const val MAX_GLOBALS = 1000000
    const val MAX_DATA_SEGMENTS = 100000
    const val MAX_STRUCT_FIELD_COUNT = 10000
    const val MAX_ARRAY_NEW_FIXED_ARGS = 10000
    const val MAX_RECURSION_GROUP_COUNT = 1000000
    const val MAX_NUMBER_OF_RECURSION_GROUPS = 1000000
    const val MAX_SUBTYPE_SUPERTYPE_COUNT = 1
    const val MAX_SUBTYPE_DEPTH = 63

    const val MAX_STRING_SIZE = 100000
    const val MAX_MODULE_SIZE = 1024 * 1024 * 1024
    const val MAX_FUNCTION_SIZE = 7654321
    const val MAX_FUNCTION_LOCALS = 50000
    const val MAX_FUNCTION_PARAMS = 1000
    const val MAX_FUNCTION_RETURNS = 1000

    const val MAX_TABLE_ENTRIES = 10000000
    const val MAX_TABLES = 1000000
}
