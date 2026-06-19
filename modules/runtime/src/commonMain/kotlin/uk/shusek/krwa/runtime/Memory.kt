@file:Suppress("DEPRECATION")

package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.DataSegment

interface Memory {
    fun pages(): Int

    fun grow(size: Int): Int

    fun initialPages(): Int

    fun maximumPages(): Int

    fun shared(): Boolean

    @Deprecated("for removal") fun lock(address: Int): Any

    @Deprecated("for removal") fun waitOn(address: Int, expected: Int, timeout: Long): Int

    @Deprecated("for removal") fun waitOn(address: Int, expected: Long, timeout: Long): Int

    @Deprecated("for removal") fun notify(address: Int, maxThreads: Int): Int

    // Atomic operations. Platform memory implementations provide their own synchronization and
    // atomic primitive strategy.
    fun atomicFence() = Unit

    fun atomicWait(addr: Int, expected: Int, timeout: Long): Int = waitOn(addr, expected, timeout)

    fun atomicWait(addr: Int, expected: Long, timeout: Long): Int = waitOn(addr, expected, timeout)

    fun atomicNotify(addr: Int, maxThreads: Int): Int = notify(addr, maxThreads)

    fun atomicReadInt(addr: Int): Int

    fun atomicReadLong(addr: Int): Long

    fun atomicReadShort(addr: Int): Short

    fun atomicReadByte(addr: Int): Byte

    fun atomicWriteInt(addr: Int, value: Int)

    fun atomicWriteLong(addr: Int, value: Long)

    fun atomicWriteShort(addr: Int, value: Short)

    fun atomicWriteByte(addr: Int, value: Byte)

    fun atomicAddInt(addr: Int, delta: Int): Int

    fun atomicAndInt(addr: Int, mask: Int): Int

    fun atomicOrInt(addr: Int, mask: Int): Int

    fun atomicXorInt(addr: Int, mask: Int): Int

    fun atomicXchgInt(addr: Int, value: Int): Int

    fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int

    fun atomicAddLong(addr: Int, delta: Long): Long

    fun atomicAndLong(addr: Int, mask: Long): Long

    fun atomicOrLong(addr: Int, mask: Long): Long

    fun atomicXorLong(addr: Int, mask: Long): Long

    fun atomicXchgLong(addr: Int, value: Long): Long

    fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long

    fun atomicAddShort(addr: Int, delta: Short): Short

    fun atomicAndShort(addr: Int, mask: Short): Short

    fun atomicOrShort(addr: Int, mask: Short): Short

    fun atomicXorShort(addr: Int, mask: Short): Short

    fun atomicXchgShort(addr: Int, value: Short): Short

    fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short

    fun atomicAddByte(addr: Int, delta: Byte): Byte

    fun atomicAndByte(addr: Int, mask: Byte): Byte

    fun atomicOrByte(addr: Int, mask: Byte): Byte

    fun atomicXorByte(addr: Int, mask: Byte): Byte

    fun atomicXchgByte(addr: Int, value: Byte): Byte

    fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte

    fun initialize(instance: Instance, dataSegments: Array<DataSegment>?)

    fun initialize(instance: Instance, dataSegments: Array<DataSegment>?, memoryIndex: Int) {
        initialize(instance, dataSegments)
    }

    fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int)

    fun writeUtf8String(offset: Int, data: String) {
        write(offset, data.encodeToByteArray())
    }

    fun readUtf8String(addr: Int, len: Int): String = readBytes(addr, len).decodeToString()

    fun writeUtf8CString(offset: Int, str: String) {
        writeUtf8String(offset, "$str\u0000")
    }

    fun readUtf8CString(addr: Int): String {
        var current = addr
        while (read(current).toInt() != 0) {
            current++
        }
        return readBytes(addr, current - addr).decodeToString()
    }

    fun writeString(offset: Int, data: String) {
        writeUtf8String(offset, data)
    }

    fun readString(addr: Int, len: Int): String = readUtf8String(addr, len)

    fun writeCString(offset: Int, str: String) {
        writeUtf8CString(offset, str)
    }

    fun readCString(addr: Int): String = readUtf8CString(addr)

    fun write(addr: Int, data: ByteArray) {
        write(addr, data, 0, data.size)
    }

    fun write(addr: Int, data: ByteArray, offset: Int, size: Int)

    fun read(addr: Int): Byte

    fun readBytes(addr: Int, len: Int): ByteArray

    fun read(addr: Int, target: ByteArray, offset: Int, size: Int) {
        val bytes = readBytes(addr, size)
        bytes.copyInto(target, destinationOffset = offset)
    }

    fun writeI32(addr: Int, data: Int)

    fun readInt(addr: Int): Int

    fun readI32(addr: Int): Long = readInt(addr).toLong()

    fun readU32(addr: Int): Long = readInt(addr).toLong() and 0xFFFF_FFFFL

    fun writeLong(addr: Int, data: Long)

    fun readLong(addr: Int): Long

    fun readI64(addr: Int): Long = readLong(addr)

    fun writeShort(addr: Int, data: Short)

    fun readShort(addr: Int): Short

    fun readI16(addr: Int): Long = readShort(addr).toLong()

    fun readU16(addr: Int): Long

    fun writeByte(addr: Int, data: Byte)

    fun readU8(addr: Int): Long = readU8Int(addr).toLong()

    fun readU8Int(addr: Int): Int = read(addr).toInt() and 0xFF

    fun readI8(addr: Int): Long = read(addr).toLong()

    fun writeF32(addr: Int, data: Float)

    fun readF32(addr: Int): Long

    fun readFloat(addr: Int): Float

    fun writeF64(addr: Int, data: Double)

    fun readDouble(addr: Int): Double

    fun readF64(addr: Int): Long

    fun zero()

    fun fill(value: Byte, fromIndex: Int, toIndex: Int)

    fun copy(dest: Int, src: Int, size: Int) {
        write(dest, readBytes(src, size))
    }

    fun drop(segment: Int)

    companion object {
        /** A WebAssembly page size is 64KiB = 65,536 bytes. */
        const val PAGE_SIZE: Int = 65536

        /**
         * Maximum number of pages allowed by the runtime. WASM supports 2^16 pages, but this
         * runtime stores memory in signed-indexed arrays, so the practical limit is lower.
         */
        const val RUNTIME_MAX_PAGES: Int = 32767

        fun bytes(pages: Int): Int = PAGE_SIZE * minOf(pages, RUNTIME_MAX_PAGES)
    }
}
