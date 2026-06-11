package uk.shusek.krwa.wasm.types

enum class WasmEncoding {
    VARUINT,
    VARSINT32,
    VARSINT64,
    FLOAT32,
    FLOAT64,
    VEC_VARUINT,
    VEC_CATCH,
    BYTE,
    V128,
    BLOCK_TYPE,
    VEC_VALUE_TYPE,
    MEMARG,
}
