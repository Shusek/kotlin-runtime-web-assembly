package uk.shusek.krwa.wasm

internal class WasmByteReader private constructor(
    private val bytes: ByteArray,
    private val end: Int,
    private var currentPosition: Int,
) {
    constructor(bytes: ByteArray) : this(bytes, bytes.size, 0)

    fun hasRemaining(): Boolean = currentPosition < end

    fun remaining(): Int = end - currentPosition

    fun position(): Int = currentPosition

    fun readByte(): Byte {
        requireRemaining(1)
        return bytes[currentPosition++]
    }

    fun readBytes(dest: ByteArray) {
        requireRemaining(dest.size)
        bytes.copyInto(dest, startIndex = currentPosition, endIndex = currentPosition + dest.size)
        currentPosition += dest.size
    }

    fun readIntLittleEndian(): Int {
        requireRemaining(Int.SIZE_BYTES)
        val result =
            (bytes[currentPosition].toInt() and 0xFF) or
                ((bytes[currentPosition + 1].toInt() and 0xFF) shl 8) or
                ((bytes[currentPosition + 2].toInt() and 0xFF) shl 16) or
                ((bytes[currentPosition + 3].toInt() and 0xFF) shl 24)
        currentPosition += Int.SIZE_BYTES
        return result
    }

    fun readLongLittleEndian(): Long {
        requireRemaining(Long.SIZE_BYTES)
        var result = 0L
        for (i in 0 until Long.SIZE_BYTES) {
            result = result or ((bytes[currentPosition + i].toLong() and 0xFFL) shl (i * 8))
        }
        currentPosition += Long.SIZE_BYTES
        return result
    }

    fun slice(size: Int): WasmByteReader {
        if (size < 0) {
            throw MalformedException("length out of bounds")
        }
        requireRemaining(size)
        val slice = WasmByteReader(bytes, currentPosition + size, currentPosition)
        currentPosition += size
        return slice
    }

    private fun requireRemaining(size: Int) {
        if (remaining() < size) {
            throw MalformedException("length out of bounds")
        }
    }
}
