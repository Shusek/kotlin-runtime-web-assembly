package uk.shusek.krwa.wasm

import java.nio.ByteBuffer

class Encoding private constructor() {
    companion object {
        // https://webassembly.github.io/spec/core/binary/values.html#integers
        const val MAX_VARINT_LEN_32: Int = 5 // ceil(32/7)
        const val MAX_VARINT_LEN_64: Int = 10 // ceil(64/7)

        // https://webassembly.github.io/spec/core/syntax/values.html#integers
        const val MIN_SIGNED_INT: Long = -2147483648L // -2^(32-1)
        const val MAX_SIGNED_INT: Long = 2147483647L // 2^(32-1)-1
        const val MAX_UNSIGNED_INT: Long = 0xFFFFFFFFL // 2^(32)-1

        @JvmStatic
        fun readInt(buffer: ByteBuffer): Int {
            if (buffer.remaining() < 4) {
                throw MalformedException("length out of bounds")
            }
            return buffer.getInt()
        }

        @JvmStatic
        fun readByte(buffer: ByteBuffer): Byte {
            if (!buffer.hasRemaining()) {
                throw MalformedException("length out of bounds")
            }
            return buffer.get()
        }

        @JvmStatic
        fun readBytes(buffer: ByteBuffer, dest: ByteArray) {
            if (buffer.remaining() < dest.size) {
                throw MalformedException("length out of bounds")
            }
            buffer.get(dest)
        }

        /** Read an unsigned I32 from the buffer. */
        @JvmStatic
        fun readVarUInt32(buffer: ByteBuffer): Long {
            val value = readUnsignedLeb128(buffer, MAX_VARINT_LEN_32)
            if (value < 0 || value > MAX_UNSIGNED_INT) {
                throw MalformedException("integer too large")
            }
            return value
        }

        /** Read a signed I32 from the buffer. */
        @JvmStatic
        fun readVarSInt32(buffer: ByteBuffer): Long {
            val value = readSigned32Leb128(buffer)
            if (value < MIN_SIGNED_INT || value > MAX_SIGNED_INT) {
                throw MalformedException("integer too large")
            }
            return value
        }

        /** Read a signed I64 from the buffer which fits neatly into a long. */
        @JvmStatic fun readVarSInt64(buffer: ByteBuffer): Long = readSigned64Leb128(buffer)

        /** Read a F64 from the buffer which fits neatly into a long. */
        @JvmStatic fun readFloat64(buffer: ByteBuffer): Long = buffer.getLong()

        /** Read a F32 from the buffer which fits neatly into a long. */
        @JvmStatic fun readFloat32(buffer: ByteBuffer): Long = readInt(buffer).toLong()

        /** Read a symbol name from the buffer as UTF-8 String. */
        @JvmStatic fun readName(buffer: ByteBuffer): String = readName(buffer, true)

        /** Read a symbol name from the buffer as UTF-8 String. */
        @JvmStatic
        fun readName(buffer: ByteBuffer, checkMalformed: Boolean): String {
            val length = readVarUInt32(buffer).toInt()
            val bytes = ByteArray(length)
            readBytes(buffer, bytes)
            val name = bytes.decodeToString()
            if (checkMalformed && !isValidIdentifier(name)) {
                throw MalformedException("malformed UTF-8 encoding")
            }
            return name
        }

        /** Reads an unsigned integer from {@code byteBuffer}. */
        @JvmStatic
        fun readUnsignedLeb128(byteBuffer: ByteBuffer, maxVarInt: Int): Long {
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
                val b = byteBuffer.get()
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

        @JvmStatic
        fun readSigned32Leb128(byteBuffer: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            var i = 0
            var currentByte: Byte

            do {
                i++
                if (byteBuffer.remaining() == 0) {
                    throw MalformedException(
                        "integer representation too long, length out of bounds"
                    )
                }

                currentByte = byteBuffer.get()
                if ((currentByte.toInt() and 0x80) != 0 && i >= MAX_VARINT_LEN_32) {
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

        @JvmStatic
        fun readSigned64Leb128(byteBuffer: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            var i = 0
            var currentByte: Byte
            val size = 64

            do {
                i++
                if (byteBuffer.remaining() == 0) {
                    throw MalformedException(
                        "integer representation too long, length out of bounds"
                    )
                }

                currentByte = byteBuffer.get()
                if ((currentByte.toInt() and 0x80) != 0 && i >= MAX_VARINT_LEN_64) {
                    throw MalformedException("integer representation too long")
                }
                result = result or ((currentByte.toInt() and 0x7F).toLong() shl shift)
                shift += 7
            } while ((currentByte.toInt() and 0x80) != 0)

            // If the final byte read has its sign bit set (0x40), sign-extend the result.
            if (shift < size && (currentByte.toInt() and 0x40) != 0) {
                result = result or (-1L shl shift)
            }

            if (i >= MAX_VARINT_LEN_64 && currentByte.toInt() != 0 && currentByte.toInt() < 0x7F) {
                throw MalformedException("integer too large")
            }

            return result
        }
    }
}
