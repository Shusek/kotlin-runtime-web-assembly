package uk.shusek.krwa.wasm.types

class PassiveDataSegment(data: ByteArray) : DataSegment(data) {
    companion object {
        val EMPTY = PassiveDataSegment(byteArrayOf())
    }
}
