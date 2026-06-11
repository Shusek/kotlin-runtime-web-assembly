package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.MalformedException

enum class PackedType(private val id: Int) {
    I8(0x78),
    I16(0x77);

    fun ID(): Int = id

    fun signExtend(value: Long): Long =
        when (this) {
            I8 -> value.toByte().toLong()
            I16 -> value.toShort().toLong()
        }

    fun mask(): Long =
        when (this) {
            I8 -> 0xFFL
            I16 -> 0xFFFFL
        }

    companion object {
        fun fromId(id: Int): PackedType =
            when (id) {
                0x78 -> I8
                0x77 -> I16
                else -> throw MalformedException("invalid packed type id: $id")
            }
    }
}
