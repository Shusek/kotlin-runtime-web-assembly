package uk.shusek.krwa.runtime

/**
 * Runtime representation of a WasmGC struct instance. Fields are stored as raw long values (same
 * encoding as stack values).
 */
class WasmStruct(private val typeIdxValue: Int, internal val fields: LongArray) : WasmGcRef {
    override fun typeIdx(): Int = typeIdxValue

    fun field(idx: Int): Long = fields[idx]

    fun setField(idx: Int, value: Long) {
        fields[idx] = value
    }

    fun fieldCount(): Int = fields.size
}
