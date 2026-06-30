package uk.shusek.krwa.runtime

import kotlin.math.min
import uk.shusek.krwa.runtime.ConstantEvaluators.computeConstantValue
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.ActiveDataSegment
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.PassiveDataSegment

/**
 * Portable, non-shared linear memory implementation for Kotlin targets without a specialized
 * memory backend.
 */
@Suppress("OVERRIDE_DEPRECATION")
class PortableMemory(private val limits: MemoryLimits) : Memory {
    private var dataSegments: Array<DataSegment>? = null
    private val maximumPages = min(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES)
    private val pages: Array<ByteArray?> = arrayOfNulls(maximumPages)
    private var nPages = limits.initialPages()

    init {
        if (limits.shared()) {
            throw WasmEngineException("PortableMemory does not support shared memory.")
        }
        if (nPages > maximumPages) {
            throw UninstantiableException(
                "memory size must be at most ${Memory.RUNTIME_MAX_PAGES} runtime pages"
            )
        }
        for (i in 0 until nPages) {
            pages[i] = ByteArray(Memory.PAGE_SIZE)
        }
    }

    override fun pages(): Int = nPages

    override fun grow(size: Int): Int {
        val previousPages = nPages
        val requestedPages = previousPages + size
        if (size < 0 || requestedPages < previousPages || requestedPages > maximumPages) {
            return -1
        }
        for (i in previousPages until requestedPages) {
            pages[i] = ByteArray(Memory.PAGE_SIZE)
        }
        nPages = requestedPages
        return previousPages
    }

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = maximumPages

    override fun shared(): Boolean = false

    override fun lock(address: Int): Any {
        throw UnsupportedOperationException("PortableMemory does not support address locks.")
    }

    override fun waitOn(address: Int, expected: Int, timeout: Long): Int {
        throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
    }

    override fun waitOn(address: Int, expected: Long, timeout: Long): Int {
        throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
    }

    override fun notify(address: Int, maxThreads: Int): Int = 0

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?) {
        initialize(instance, dataSegments, 0)
    }

    override fun initialize(
        instance: Instance,
        dataSegments: Array<DataSegment>?,
        memoryIndex: Int,
    ) {
        this.dataSegments = dataSegments
        if (dataSegments == null) {
            return
        }

        for (segment in dataSegments) {
            when (segment) {
                is ActiveDataSegment -> {
                    if (segment.index() != memoryIndex.toLong()) {
                        continue
                    }
                    val offset = computeConstantValue(instance, segment.offsetInstructions())[0].toInt()
                    val data = segment.data()
                    checkBounds(offset, data.size, sizeInBytes(), ::UninstantiableException)
                    write(offset, data)
                }
                is PassiveDataSegment -> {
                    // Passive segment should be skipped.
                }
                else -> throw WasmEngineException("Data segment should be active or passive: $segment")
            }
        }
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        val segment = dataSegments!![segmentId]
        write(dest, segment.data(), offset, size)
    }

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        checkBounds(offset, size, data.size, ::WasmRuntimeException)
        checkBounds(addr, size, sizeInBytes(), ::WasmRuntimeException)

        var currentAddr = addr
        var currentOffset = offset
        var remaining = size
        while (remaining > 0) {
            val pageOffset = currentAddr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            data.copyInto(
                destination = page(currentAddr ushr PAGE_SHIFT),
                destinationOffset = pageOffset,
                startIndex = currentOffset,
                endIndex = currentOffset + chunk,
            )
            currentAddr += chunk
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun read(addr: Int): Byte {
        try {
            return page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK]
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
    }

    override fun readU8(addr: Int): Long = read(addr).toLong() and 0xFFL

    override fun compareUnsignedBytes(leftAddr: Int, rightAddr: Int, length: Int): Int {
        checkBounds(leftAddr, length, sizeInBytes(), ::WasmRuntimeException)
        checkBounds(rightAddr, length, sizeInBytes(), ::WasmRuntimeException)

        var currentLeft = leftAddr
        var currentRight = rightAddr
        var remaining = length
        while (remaining > 0) {
            val leftOffset = currentLeft and PAGE_MASK
            val rightOffset = currentRight and PAGE_MASK
            val chunk = min(remaining, min(Memory.PAGE_SIZE - leftOffset, Memory.PAGE_SIZE - rightOffset))
            val diff = RuntimePlatform.compareByteArraysUnsigned(
                page(currentLeft ushr PAGE_SHIFT),
                leftOffset,
                page(currentRight ushr PAGE_SHIFT),
                rightOffset,
                chunk,
            )
            if (diff != 0) return diff
            currentLeft += chunk
            currentRight += chunk
            remaining -= chunk
        }
        return 0
    }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkBounds(addr, len, sizeInBytes(), ::WasmRuntimeException)
        val result = ByteArray(len)
        read(addr, result, 0, len)
        return result
    }

    override fun read(addr: Int, target: ByteArray, offset: Int, size: Int) {
        checkBounds(offset, size, target.size, ::WasmRuntimeException)
        checkBounds(addr, size, sizeInBytes(), ::WasmRuntimeException)
        var currentAddr = addr
        var currentOffset = offset
        var remaining = size
        while (remaining > 0) {
            val pageOffset = currentAddr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            page(currentAddr ushr PAGE_SHIFT).copyInto(
                destination = target,
                destinationOffset = currentOffset,
                startIndex = pageOffset,
                endIndex = pageOffset + chunk,
            )
            currentAddr += chunk
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun writeI32(addr: Int, data: Int) {
        checkBounds(addr, 4, sizeInBytes(), ::WasmRuntimeException)
        val pageOffset = addr and PAGE_MASK
        if (pageOffset <= PAGE_MASK - 3) {
            val page = page(addr ushr PAGE_SHIFT)
            page[pageOffset] = data.toByte()
            page[pageOffset + 1] = (data ushr 8).toByte()
            page[pageOffset + 2] = (data ushr 16).toByte()
            page[pageOffset + 3] = (data ushr 24).toByte()
        } else {
            writeByteUnchecked(addr, data.toByte())
            writeByteUnchecked(addr + 1, (data ushr 8).toByte())
            writeByteUnchecked(addr + 2, (data ushr 16).toByte())
            writeByteUnchecked(addr + 3, (data ushr 24).toByte())
        }
    }

    override fun readInt(addr: Int): Int {
        checkBounds(addr, 4, sizeInBytes(), ::WasmRuntimeException)
        val pageOffset = addr and PAGE_MASK
        return if (pageOffset <= PAGE_MASK - 3) {
            val page = page(addr ushr PAGE_SHIFT)
            (page[pageOffset].toInt() and 0xFF) or
                ((page[pageOffset + 1].toInt() and 0xFF) shl 8) or
                ((page[pageOffset + 2].toInt() and 0xFF) shl 16) or
                ((page[pageOffset + 3].toInt() and 0xFF) shl 24)
        } else {
            readByteUnchecked(addr) or
                (readByteUnchecked(addr + 1) shl 8) or
                (readByteUnchecked(addr + 2) shl 16) or
                (readByteUnchecked(addr + 3) shl 24)
        }
    }

    override fun readI32(addr: Int): Long = readInt(addr).toLong()

    override fun writeLong(addr: Int, data: Long) {
        checkBounds(addr, 8, sizeInBytes(), ::WasmRuntimeException)
        val pageOffset = addr and PAGE_MASK
        if (pageOffset <= PAGE_MASK - 7) {
            val page = page(addr ushr PAGE_SHIFT)
            for (i in 0 until 8) {
                page[pageOffset + i] = (data ushr (i * 8)).toByte()
            }
        } else {
            for (i in 0 until 8) {
                writeByteUnchecked(addr + i, (data ushr (i * 8)).toByte())
            }
        }
    }

    override fun readLong(addr: Int): Long {
        checkBounds(addr, 8, sizeInBytes(), ::WasmRuntimeException)
        var result = 0L
        val pageOffset = addr and PAGE_MASK
        if (pageOffset <= PAGE_MASK - 7) {
            val page = page(addr ushr PAGE_SHIFT)
            for (i in 0 until 8) {
                result = result or ((page[pageOffset + i].toLong() and 0xFFL) shl (i * 8))
            }
        } else {
            for (i in 0 until 8) {
                result = result or (readByteUnchecked(addr + i).toLong() shl (i * 8))
            }
        }
        return result
    }

    override fun readI64(addr: Int): Long = readLong(addr)

    override fun writeShort(addr: Int, data: Short) {
        checkBounds(addr, 2, sizeInBytes(), ::WasmRuntimeException)
        val pageOffset = addr and PAGE_MASK
        if (pageOffset <= PAGE_MASK - 1) {
            val page = page(addr ushr PAGE_SHIFT)
            page[pageOffset] = data.toByte()
            page[pageOffset + 1] = (data.toInt() ushr 8).toByte()
        } else {
            writeByteUnchecked(addr, data.toByte())
            writeByteUnchecked(addr + 1, (data.toInt() ushr 8).toByte())
        }
    }

    override fun readShort(addr: Int): Short {
        checkBounds(addr, 2, sizeInBytes(), ::WasmRuntimeException)
        val pageOffset = addr and PAGE_MASK
        return if (pageOffset <= PAGE_MASK - 1) {
            val page = page(addr ushr PAGE_SHIFT)
            ((page[pageOffset].toInt() and 0xFF) or
                ((page[pageOffset + 1].toInt() and 0xFF) shl 8))
                .toShort()
        } else {
            (readByteUnchecked(addr) or (readByteUnchecked(addr + 1) shl 8)).toShort()
        }
    }

    override fun readI16(addr: Int): Long = readShort(addr).toLong()

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xFFFFL

    override fun writeByte(addr: Int, data: Byte) {
        try {
            page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK] = data
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
    }

    override fun writeF32(addr: Int, data: Float) {
        writeI32(addr, data.toRawBits())
    }

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float = Float.fromBits(readInt(addr))

    override fun writeF64(addr: Int, data: Double) {
        writeLong(addr, data.toRawBits())
    }

    override fun readDouble(addr: Int): Double = Double.fromBits(readLong(addr))

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun zero() {
        fill(0.toByte(), 0, sizeInBytes())
    }

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        checkBounds(fromIndex, toIndex - fromIndex, sizeInBytes(), ::WasmRuntimeException)
        var currentAddr = fromIndex
        var remaining = toIndex - fromIndex
        while (remaining > 0) {
            val pageOffset = currentAddr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            page(currentAddr ushr PAGE_SHIFT).fill(value, pageOffset, pageOffset + chunk)
            currentAddr += chunk
            remaining -= chunk
        }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        checkBounds(dest, size, sizeInBytes(), ::WasmRuntimeException)
        checkBounds(src, size, sizeInBytes(), ::WasmRuntimeException)
        if (size == 0 || dest == src) {
            return
        }

        val srcEnd = src.toLong() + size.toLong()
        if (dest.toLong() < src.toLong() || dest.toLong() >= srcEnd) {
            copyForward(dest, src, size)
        } else {
            copyBackward(dest, src, size)
        }
    }

    override fun drop(segment: Int) {
        dataSegments!![segment] = PassiveDataSegment.EMPTY
    }

    override fun atomicReadInt(addr: Int): Int = readInt(addr)

    override fun atomicReadLong(addr: Int): Long = readLong(addr)

    override fun atomicReadShort(addr: Int): Short = readShort(addr)

    override fun atomicReadByte(addr: Int): Byte = read(addr)

    override fun atomicWriteInt(addr: Int, value: Int) {
        writeI32(addr, value)
    }

    override fun atomicWriteLong(addr: Int, value: Long) {
        writeLong(addr, value)
    }

    override fun atomicWriteShort(addr: Int, value: Short) {
        writeShort(addr, value)
    }

    override fun atomicWriteByte(addr: Int, value: Byte) {
        writeByte(addr, value)
    }

    override fun atomicAddInt(addr: Int, delta: Int): Int {
        val value = readInt(addr)
        writeI32(addr, value + delta)
        return value
    }

    override fun atomicAndInt(addr: Int, mask: Int): Int {
        val value = readInt(addr)
        writeI32(addr, value and mask)
        return value
    }

    override fun atomicOrInt(addr: Int, mask: Int): Int {
        val value = readInt(addr)
        writeI32(addr, value or mask)
        return value
    }

    override fun atomicXorInt(addr: Int, mask: Int): Int {
        val value = readInt(addr)
        writeI32(addr, value xor mask)
        return value
    }

    override fun atomicXchgInt(addr: Int, value: Int): Int {
        val oldValue = readInt(addr)
        writeI32(addr, value)
        return oldValue
    }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int {
        val value = readInt(addr)
        if (value == expected) {
            writeI32(addr, replacement)
        }
        return value
    }

    override fun atomicAddLong(addr: Int, delta: Long): Long {
        val value = readLong(addr)
        writeLong(addr, value + delta)
        return value
    }

    override fun atomicAndLong(addr: Int, mask: Long): Long {
        val value = readLong(addr)
        writeLong(addr, value and mask)
        return value
    }

    override fun atomicOrLong(addr: Int, mask: Long): Long {
        val value = readLong(addr)
        writeLong(addr, value or mask)
        return value
    }

    override fun atomicXorLong(addr: Int, mask: Long): Long {
        val value = readLong(addr)
        writeLong(addr, value xor mask)
        return value
    }

    override fun atomicXchgLong(addr: Int, value: Long): Long {
        val oldValue = readLong(addr)
        writeLong(addr, value)
        return oldValue
    }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long {
        val value = readLong(addr)
        if (value == expected) {
            writeLong(addr, replacement)
        }
        return value
    }

    override fun atomicAddShort(addr: Int, delta: Short): Short {
        val value = readShort(addr)
        writeShort(addr, (value + delta).toShort())
        return value
    }

    override fun atomicAndShort(addr: Int, mask: Short): Short {
        val value = readShort(addr)
        writeShort(addr, (value.toInt() and mask.toInt()).toShort())
        return value
    }

    override fun atomicOrShort(addr: Int, mask: Short): Short {
        val value = readShort(addr)
        writeShort(addr, (value.toInt() or mask.toInt()).toShort())
        return value
    }

    override fun atomicXorShort(addr: Int, mask: Short): Short {
        val value = readShort(addr)
        writeShort(addr, (value.toInt() xor mask.toInt()).toShort())
        return value
    }

    override fun atomicXchgShort(addr: Int, value: Short): Short {
        val oldValue = readShort(addr)
        writeShort(addr, value)
        return oldValue
    }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short {
        val value = readShort(addr)
        if (value == expected) {
            writeShort(addr, replacement)
        }
        return value
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte {
        val value = read(addr)
        writeByte(addr, (value + delta).toByte())
        return value
    }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte {
        val value = read(addr)
        writeByte(addr, (value.toInt() and mask.toInt()).toByte())
        return value
    }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte {
        val value = read(addr)
        writeByte(addr, (value.toInt() or mask.toInt()).toByte())
        return value
    }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte {
        val value = read(addr)
        writeByte(addr, (value.toInt() xor mask.toInt()).toByte())
        return value
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte {
        val oldValue = read(addr)
        writeByte(addr, value)
        return oldValue
    }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte {
        val value = read(addr)
        if (value == expected) {
            writeByte(addr, replacement)
        }
        return value
    }

    private fun sizeInBytes(): Int = Memory.PAGE_SIZE * nPages

    private fun page(index: Int): ByteArray = pages[index]!!

    private fun outOfBoundsException(e: RuntimeException, addr: Int, size: Int): RuntimeException =
        if (
            e is IndexOutOfBoundsException ||
                e is IllegalArgumentException ||
                e is NullPointerException
        ) {
            WasmRuntimeException(
                "out of bounds memory access: attempted to access address: " +
                    "$addr but limit is: ${sizeInBytes()} and size: $size"
            )
        } else {
            e
        }

    private fun readByteUnchecked(addr: Int): Int =
        page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK].toInt() and 0xFF

    private fun writeByteUnchecked(addr: Int, data: Byte) {
        page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK] = data
    }

    private fun copyForward(dest: Int, src: Int, size: Int) {
        var currentDest = dest
        var currentSrc = src
        var remaining = size
        while (remaining > 0) {
            val destOffset = currentDest and PAGE_MASK
            val srcOffset = currentSrc and PAGE_MASK
            val chunk = min(remaining, min(Memory.PAGE_SIZE - destOffset, Memory.PAGE_SIZE - srcOffset))
            page(currentSrc ushr PAGE_SHIFT).copyInto(
                destination = page(currentDest ushr PAGE_SHIFT),
                destinationOffset = destOffset,
                startIndex = srcOffset,
                endIndex = srcOffset + chunk,
            )
            currentDest += chunk
            currentSrc += chunk
            remaining -= chunk
        }
    }

    private fun copyBackward(dest: Int, src: Int, size: Int) {
        var remaining = size
        while (remaining > 0) {
            val currentDestEnd = dest + remaining
            val currentSrcEnd = src + remaining
            val destEndOffset = currentDestEnd and PAGE_MASK
            val srcEndOffset = currentSrcEnd and PAGE_MASK
            val destChunk = if (destEndOffset == 0) Memory.PAGE_SIZE else destEndOffset
            val srcChunk = if (srcEndOffset == 0) Memory.PAGE_SIZE else srcEndOffset
            val chunk = min(remaining, min(destChunk, srcChunk))
            val currentDest = currentDestEnd - chunk
            val currentSrc = currentSrcEnd - chunk
            val destOffset = currentDest and PAGE_MASK
            val srcOffset = currentSrc and PAGE_MASK
            page(currentSrc ushr PAGE_SHIFT).copyInto(
                destination = page(currentDest ushr PAGE_SHIFT),
                destinationOffset = destOffset,
                startIndex = srcOffset,
                endIndex = srcOffset + chunk,
            )
            remaining -= chunk
        }
    }

    private companion object {
        private const val PAGE_SHIFT = 16
        private const val PAGE_MASK = Memory.PAGE_SIZE - 1

        private fun checkBounds(
            addr: Int,
            size: Int,
            limit: Int,
            exceptionFactory: (String) -> WasmEngineException,
        ) {
            if (
                addr < 0 ||
                    size < 0 ||
                    addr > limit ||
                    (size > 0 && addr.toLong() + size.toLong() > limit.toLong())
            ) {
                throw exceptionFactory(
                    "out of bounds memory access: attempted to access address: " +
                        "$addr but limit is: $limit and size: $size"
                )
            }
        }
    }
}
