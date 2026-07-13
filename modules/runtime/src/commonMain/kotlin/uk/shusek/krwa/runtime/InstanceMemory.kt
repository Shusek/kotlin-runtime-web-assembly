@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.DataSegment

/** Keeps a memory obtained from an [Instance] from accessing a released native handle. */
internal class InstanceMemory(
    private val instance: Instance,
    private val delegate: Memory,
) : Memory {
    private inline fun <T> open(block: Memory.() -> T): T {
        instance.ensureOpen()
        return delegate.block()
    }

    override fun pages(): Int = open { pages() }
    override fun grow(size: Int): Int = open { grow(size) }
    override fun initialPages(): Int = open { initialPages() }
    override fun maximumPages(): Int = open { maximumPages() }
    override fun shared(): Boolean = open { shared() }
    override fun lock(address: Int): Any = open { lock(address) }
    override fun waitOn(address: Int, expected: Int, timeout: Long): Int = open { waitOn(address, expected, timeout) }
    override fun waitOn(address: Int, expected: Long, timeout: Long): Int = open { waitOn(address, expected, timeout) }
    override fun notify(address: Int, maxThreads: Int): Int = open { notify(address, maxThreads) }
    override fun atomicFence(): Unit = open { atomicFence() }
    override fun atomicWait(addr: Int, expected: Int, timeout: Long): Int = open { atomicWait(addr, expected, timeout) }
    override fun atomicWait(addr: Int, expected: Long, timeout: Long): Int = open { atomicWait(addr, expected, timeout) }
    override fun atomicNotify(addr: Int, maxThreads: Int): Int = open { atomicNotify(addr, maxThreads) }
    override fun atomicReadInt(addr: Int): Int = open { atomicReadInt(addr) }
    override fun atomicReadLong(addr: Int): Long = open { atomicReadLong(addr) }
    override fun atomicReadShort(addr: Int): Short = open { atomicReadShort(addr) }
    override fun atomicReadByte(addr: Int): Byte = open { atomicReadByte(addr) }
    override fun atomicWriteInt(addr: Int, value: Int): Unit = open { atomicWriteInt(addr, value) }
    override fun atomicWriteLong(addr: Int, value: Long): Unit = open { atomicWriteLong(addr, value) }
    override fun atomicWriteShort(addr: Int, value: Short): Unit = open { atomicWriteShort(addr, value) }
    override fun atomicWriteByte(addr: Int, value: Byte): Unit = open { atomicWriteByte(addr, value) }
    override fun atomicAddInt(addr: Int, delta: Int): Int = open { atomicAddInt(addr, delta) }
    override fun atomicAndInt(addr: Int, mask: Int): Int = open { atomicAndInt(addr, mask) }
    override fun atomicOrInt(addr: Int, mask: Int): Int = open { atomicOrInt(addr, mask) }
    override fun atomicXorInt(addr: Int, mask: Int): Int = open { atomicXorInt(addr, mask) }
    override fun atomicXchgInt(addr: Int, value: Int): Int = open { atomicXchgInt(addr, value) }
    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int =
        open { atomicCmpxchgInt(addr, expected, replacement) }
    override fun atomicAddLong(addr: Int, delta: Long): Long = open { atomicAddLong(addr, delta) }
    override fun atomicAndLong(addr: Int, mask: Long): Long = open { atomicAndLong(addr, mask) }
    override fun atomicOrLong(addr: Int, mask: Long): Long = open { atomicOrLong(addr, mask) }
    override fun atomicXorLong(addr: Int, mask: Long): Long = open { atomicXorLong(addr, mask) }
    override fun atomicXchgLong(addr: Int, value: Long): Long = open { atomicXchgLong(addr, value) }
    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long =
        open { atomicCmpxchgLong(addr, expected, replacement) }
    override fun atomicAddShort(addr: Int, delta: Short): Short = open { atomicAddShort(addr, delta) }
    override fun atomicAndShort(addr: Int, mask: Short): Short = open { atomicAndShort(addr, mask) }
    override fun atomicOrShort(addr: Int, mask: Short): Short = open { atomicOrShort(addr, mask) }
    override fun atomicXorShort(addr: Int, mask: Short): Short = open { atomicXorShort(addr, mask) }
    override fun atomicXchgShort(addr: Int, value: Short): Short = open { atomicXchgShort(addr, value) }
    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short =
        open { atomicCmpxchgShort(addr, expected, replacement) }
    override fun atomicAddByte(addr: Int, delta: Byte): Byte = open { atomicAddByte(addr, delta) }
    override fun atomicAndByte(addr: Int, mask: Byte): Byte = open { atomicAndByte(addr, mask) }
    override fun atomicOrByte(addr: Int, mask: Byte): Byte = open { atomicOrByte(addr, mask) }
    override fun atomicXorByte(addr: Int, mask: Byte): Byte = open { atomicXorByte(addr, mask) }
    override fun atomicXchgByte(addr: Int, value: Byte): Byte = open { atomicXchgByte(addr, value) }
    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte =
        open { atomicCmpxchgByte(addr, expected, replacement) }
    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?): Unit =
        open { initialize(instance, dataSegments) }
    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?, memoryIndex: Int): Unit =
        open { initialize(instance, dataSegments, memoryIndex) }
    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int): Unit =
        open { initPassiveSegment(segmentId, dest, offset, size) }
    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int): Unit =
        open { write(addr, data, offset, size) }
    override fun read(addr: Int): Byte = open { read(addr) }
    override fun readBytes(addr: Int, len: Int): ByteArray = open { readBytes(addr, len) }
    override fun read(addr: Int, target: ByteArray, offset: Int, size: Int): Unit =
        open { read(addr, target, offset, size) }
    override fun writeI32(addr: Int, data: Int): Unit = open { writeI32(addr, data) }
    override fun readInt(addr: Int): Int = open { readInt(addr) }
    override fun writeLong(addr: Int, data: Long): Unit = open { writeLong(addr, data) }
    override fun readLong(addr: Int): Long = open { readLong(addr) }
    override fun writeShort(addr: Int, data: Short): Unit = open { writeShort(addr, data) }
    override fun readShort(addr: Int): Short = open { readShort(addr) }
    override fun readU16(addr: Int): Long = open { readU16(addr) }
    override fun writeByte(addr: Int, data: Byte): Unit = open { writeByte(addr, data) }
    override fun writeF32(addr: Int, data: Float): Unit = open { writeF32(addr, data) }
    override fun readF32(addr: Int): Long = open { readF32(addr) }
    override fun readFloat(addr: Int): Float = open { readFloat(addr) }
    override fun writeF64(addr: Int, data: Double): Unit = open { writeF64(addr, data) }
    override fun readDouble(addr: Int): Double = open { readDouble(addr) }
    override fun readF64(addr: Int): Long = open { readF64(addr) }
    override fun zero(): Unit = open { zero() }
    override fun fill(value: Byte, fromIndex: Int, toIndex: Int): Unit = open { fill(value, fromIndex, toIndex) }
    override fun drop(segment: Int): Unit = open { drop(segment) }
}
