package uk.shusek.krwa.wasm.types

abstract class DataSegment(data: ByteArray) {
    private val data = data.copyOf()

    fun data(): ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DataSegment) {
            return false
        }
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}
