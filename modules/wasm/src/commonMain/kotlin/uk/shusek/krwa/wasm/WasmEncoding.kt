package uk.shusek.krwa.wasm

// https://webassembly.github.io/spec/core/binary/values.html#integers
internal const val WASM_MAX_VARINT_LEN_32: Int = 5 // ceil(32/7)
internal const val WASM_MAX_VARINT_LEN_64: Int = 10 // ceil(64/7)

// https://webassembly.github.io/spec/core/syntax/values.html#integers
private val MIN_SIGNED_INT: Long = Int.MIN_VALUE.toLong() // -2^(32-1)
private val MAX_SIGNED_INT: Long = Int.MAX_VALUE.toLong() // 2^(32-1)-1
private const val MAX_UNSIGNED_INT: Long = 0xFFFFFFFFL // 2^(32)-1

internal fun readInt(buffer: WasmByteReader): Int = buffer.readIntLittleEndian()

internal fun readByte(buffer: WasmByteReader): Byte = buffer.readByte()

internal fun readBytes(buffer: WasmByteReader, dest: ByteArray) {
    buffer.readBytes(dest)
}

/** Read an unsigned I32 from the buffer. */
internal fun readVarUInt32(buffer: WasmByteReader): Long {
    val value = readUnsignedLeb128(buffer, WASM_MAX_VARINT_LEN_32)
    if (value < 0 || value > MAX_UNSIGNED_INT) {
        throw MalformedException("integer too large")
    }
    return value
}

/** Read a signed I32 from the buffer. */
internal fun readVarSInt32(buffer: WasmByteReader): Long {
    val value = readSigned32Leb128(buffer)
    if (value < MIN_SIGNED_INT || value > MAX_SIGNED_INT) {
        throw MalformedException("integer too large")
    }
    return value
}

/** Read a signed I64 from the buffer which fits neatly into a long. */
internal fun readVarSInt64(buffer: WasmByteReader): Long = readSigned64Leb128(buffer)

/** Read a F64 from the buffer which fits neatly into a long. */
internal fun readFloat64(buffer: WasmByteReader): Long = buffer.readLongLittleEndian()

/** Read a F32 from the buffer which fits neatly into a long. */
internal fun readFloat32(buffer: WasmByteReader): Long = readInt(buffer).toLong()

/** Read a symbol name from the buffer as UTF-8 String. */
internal fun readName(buffer: WasmByteReader): String = readName(buffer, true)

/** Read a symbol name from the buffer as UTF-8 String. */
internal fun readName(buffer: WasmByteReader, checkMalformed: Boolean): String {
    val length = readVarUInt32(buffer).toInt()
    val bytes = ByteArray(length)
    readBytes(buffer, bytes)
    val name = bytes.decodeToString()
    if (checkMalformed && !isValidIdentifier(name)) {
        throw MalformedException("malformed UTF-8 encoding")
    }
    return name
}

internal fun isValidIdentifier(string: String): Boolean =
    string.all { ch -> ch.code < 0x80 || ch.isLetterOrDigit() || ch == '_' }

/** Reads an unsigned integer from {@code byteBuffer}. */
internal fun readUnsignedLeb128(byteBuffer: WasmByteReader, maxVarInt: Int): Long {
    var result = 0L
    var shift = 0
    var i = 0
    while (true) {
        i++
        if (byteBuffer.remaining() == 0) {
            throw MalformedException(
                "integer too large, integer representation too long, length out of bounds"
            )
        }
        val b = byteBuffer.readByte()
        result = result or ((b.toInt() and 0x7F).toLong() shl shift)

        if ((b.toInt() and 0x80) == 0) {
            break
        }

        if (i >= maxVarInt || byteBuffer.remaining() == 0) {
            throw MalformedException("integer representation too long")
        }

        shift += 7
    }

    return result
}

internal fun readSigned32Leb128(byteBuffer: WasmByteReader): Long {
    var result = 0L
    var shift = 0
    var i = 0
    var currentByte: Byte

    do {
        i++
        if (byteBuffer.remaining() == 0) {
            throw MalformedException("integer representation too long, length out of bounds")
        }

        currentByte = byteBuffer.readByte()
        if ((currentByte.toInt() and 0x80) != 0 && i >= WASM_MAX_VARINT_LEN_32) {
            throw MalformedException("integer representation too long")
        }
        result = result or ((currentByte.toInt() and 0x7F).toLong() shl shift)
        shift += 7
    } while ((currentByte.toInt() and 0x80) != 0)

    // If the final byte read has its sign bit set (0x40), sign-extend the result.
    if ((currentByte.toInt() and 0x40) != 0) {
        result = result or -(1L shl shift)
    }

    return result
}

internal fun readSigned64Leb128(byteBuffer: WasmByteReader): Long {
    var result = 0L
    var shift = 0
    var i = 0
    var currentByte: Byte
    val size = 64

    do {
        i++
        if (byteBuffer.remaining() == 0) {
            throw MalformedException("integer representation too long, length out of bounds")
        }

        currentByte = byteBuffer.readByte()
        if ((currentByte.toInt() and 0x80) != 0 && i >= WASM_MAX_VARINT_LEN_64) {
            throw MalformedException("integer representation too long")
        }
        result = result or ((currentByte.toInt() and 0x7F).toLong() shl shift)
        shift += 7
    } while ((currentByte.toInt() and 0x80) != 0)

    // If the final byte read has its sign bit set (0x40), sign-extend the result.
    if (shift < size && (currentByte.toInt() and 0x40) != 0) {
        result = result or (-1L shl shift)
    }

    if (i >= WASM_MAX_VARINT_LEN_64 && currentByte.toInt() != 0 && currentByte.toInt() < 0x7F) {
        throw MalformedException("integer too large")
    }

    return result
}
