@file:Suppress("DEPRECATION")

package uk.shusek.krwa.runtime

import java.lang.reflect.InvocationTargetException
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import uk.shusek.krwa.runtime.ConstantEvaluators.computeConstantValue
import uk.shusek.krwa.runtime.alloc.MemAllocStrategy
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.ActiveDataSegment
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.PassiveDataSegment

/**
 * Represents the linear memory in the Wasm program. Can be shared reference b/w the host and the
 * guest.
 *
 * This is the preferred memory implementation on Android systems.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ByteBufferMemory(private val limits: MemoryLimits) : Memory {
    // Page-based storage: fixed-size array of pages, slots filled lazily during grow.
    // Individual pages are never reallocated once created, enabling lock-free reads.
    private val pages: Array<ByteBuffer?> =
        arrayOfNulls(min(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES))
    private val pageArrays: Array<ByteArray?> =
        arrayOfNulls(min(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES))

    private var dataSegments: Array<DataSegment>? = null

    @Volatile private var nPages = limits.initialPages()

    // Lock for grow operation (only used when memory is shared).
    private val growLock = Any()

    private val waitStates: MutableMap<Int, WaitState>? =
        if (limits.shared()) ConcurrentHashMap() else null

    init {
        for (i in 0 until limits.initialPages()) {
            pages[i] = ByteBuffer.allocate(Memory.PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            pageArrays[i] = pages[i]!!.array()
        }
    }

    /**
     * @deprecated The MemAllocStrategy is no longer used since memory is allocated by page. Use
     *   [ByteBufferMemory] instead.
     */
    @Deprecated("The MemAllocStrategy is no longer used since memory is allocated by page.")
    @Suppress("UNUSED_PARAMETER")
    constructor(limits: MemoryLimits, allocStrategy: MemAllocStrategy) : this(limits)

    private class WaitState {
        var waiterCount = 0
        var pendingWakeups = 0

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun monitor(): java.lang.Object = this as java.lang.Object
    }

    override fun lock(address: Int): Any {
        if (!shared()) {
            // disable locking
            return Any()
        }
        return waitStates!!.computeIfAbsent(address) { WaitState() }
    }

    private fun waitOn(address: Int, condition: () -> Boolean, timeout: Long): Int {
        if (!shared()) {
            throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
        }

        val deadline = if (timeout < 0) Long.MAX_VALUE else System.nanoTime() + timeout
        val state = waitStates!!.computeIfAbsent(address) { WaitState() }

        synchronized(state) {
            if (!condition()) {
                return 1
            }

            state.waiterCount++
            try {
                while (state.pendingWakeups == 0) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        return 2
                    }
                    val millis = max(remaining / 1_000_000L, 0)
                    val nanos = max((remaining % 1_000_000L).toInt(), 0)
                    try {
                        state.monitor().wait(millis, nanos)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw WasmInterruptedException("Thread interrupted")
                    }
                }
                return 0
            } finally {
                if (state.pendingWakeups > 0) {
                    state.pendingWakeups--
                }
                state.waiterCount--
                assert(0 <= state.pendingWakeups)
                assert(state.pendingWakeups <= state.waiterCount)
            }
        }
    }

    override fun waitOn(address: Int, expected: Int, timeout: Long): Int =
        waitOn(address, { readInt(address) == expected }, timeout)

    override fun waitOn(address: Int, expected: Long, timeout: Long): Int =
        waitOn(address, { readLong(address) == expected }, timeout)

    override fun notify(address: Int, maxThreads: Int): Int {
        if (!shared()) {
            return 0
        }

        val state = waitStates!![address] ?: return 0
        synchronized(state) {
            val actualWaiters = state.waiterCount - state.pendingWakeups
            if (actualWaiters == 0) {
                return 0
            }

            val toWake = if (maxThreads < 0) actualWaiters else min(actualWaiters, maxThreads)
            state.pendingWakeups += toWake
            assert(state.pendingWakeups <= state.waiterCount)
            state.monitor().notifyAll()
            return toWake
        }
    }

    private inline fun <T> atomic(addr: Int, block: () -> T): T =
        if (shared()) {
            synchronized(lock(addr)) { block() }
        } else {
            block()
        }

    override fun pages(): Int = nPages

    override fun grow(size: Int): Int {
        if (!shared()) {
            return growImpl(size)
        }
        synchronized(growLock) {
            return growImpl(size)
        }
    }

    private fun growImpl(size: Int): Int {
        val prevPages = nPages
        val numPages = prevPages + size

        if (numPages > maximumPages() || numPages < prevPages) {
            return -1
        }

        for (i in prevPages until numPages) {
            pages[i] = ByteBuffer.allocate(Memory.PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            pageArrays[i] = pages[i]!!.array()
        }

        nPages = numPages
        return prevPages
    }

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = min(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES)

    override fun shared(): Boolean = limits.shared()

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

        for (s in dataSegments) {
            when (s) {
                is ActiveDataSegment -> {
                    if (s.index() != memoryIndex.toLong()) {
                        continue
                    }
                    val offsetExpr = s.offsetInstructions()
                    val data = s.data()
                    val offset = computeConstantValue(instance, offsetExpr)[0].toInt()
                    checkBounds(offset, data.size, sizeInBytes(), ::UninstantiableException)
                    write(offset, data, 0, data.size)
                }
                is PassiveDataSegment -> {
                    // Passive segment should be skipped.
                }
                else -> throw WasmEngineException("Data segment should be active or passive: $s")
            }
        }
    }

    private fun outOfBoundsException(e: RuntimeException, addr: Int, size: Int): RuntimeException {
        return if (
            e is IndexOutOfBoundsException ||
                e is BufferOverflowException ||
                e is BufferUnderflowException ||
                e is IllegalArgumentException ||
                e is NullPointerException ||
                e is NegativeArraySizeException
        ) {
            val limit = sizeInBytes()
            WasmRuntimeException(
                "out of bounds memory access: attempted to access address: " +
                    "$addr but limit is: $limit and size: $size"
            )
        } else {
            e
        }
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        val segment = dataSegments!![segmentId]
        write(dest, segment.data(), offset, size)
    }

    private fun sizeInBytes(): Int = Memory.PAGE_SIZE * nPages

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        var currentAddr = addr
        var currentOffset = offset
        var remaining = size
        checkBounds(currentOffset, remaining, data.size, ::WasmRuntimeException)
        checkBounds(currentAddr, remaining, sizeInBytes(), ::WasmRuntimeException)
        while (remaining > 0) {
            val pageIdx = currentAddr ushr PAGE_SHIFT
            val pageOffset = currentAddr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            page(pageIdx).position(pageOffset)
            page(pageIdx).put(data, currentOffset, chunk)
            currentAddr += chunk
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun read(addr: Int): Byte {
        try {
            return pageArrays[addr ushr PAGE_SHIFT]!![addr and PAGE_MASK]
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
    }

    override fun readU8Int(addr: Int): Int {
        try {
            return pageArrays[addr ushr PAGE_SHIFT]!![addr and PAGE_MASK].toInt() and 0xFF
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
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
        var targetOffset = offset
        var remaining = size
        var a = addr
        while (remaining > 0) {
            val pageIdx = a ushr PAGE_SHIFT
            val pageOffset = a and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            page(pageIdx).position(pageOffset)
            page(pageIdx).get(target, targetOffset, chunk)
            a += chunk
            targetOffset += chunk
            remaining -= chunk
        }
    }

    override fun writeI32(addr: Int, data: Int) {
        val off = addr and PAGE_MASK
        if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).putInt(off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            writeI32Slow(addr, data)
        }
    }

    private fun writeI32Slow(addr: Int, data: Int) {
        checkBounds(addr, 4, sizeInBytes(), ::WasmRuntimeException)
        writeByte(addr, data.toByte())
        writeByte(addr + 1, (data ushr 8).toByte())
        writeByte(addr + 2, (data ushr 16).toByte())
        writeByte(addr + 3, (data ushr 24).toByte())
    }

    override fun readInt(addr: Int): Int {
        val off = addr and PAGE_MASK
        return if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).getInt(off)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            readIntSlow(addr)
        }
    }

    private fun readIntSlow(addr: Int): Int =
        (read(addr).toInt() and 0xFF) or
            ((read(addr + 1).toInt() and 0xFF) shl 8) or
            ((read(addr + 2).toInt() and 0xFF) shl 16) or
            ((read(addr + 3).toInt() and 0xFF) shl 24)

    override fun writeLong(addr: Int, data: Long) {
        val off = addr and PAGE_MASK
        if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).putLong(off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            writeLongSlow(addr, data)
        }
    }

    private fun writeLongSlow(addr: Int, data: Long) {
        checkBounds(addr, 8, sizeInBytes(), ::WasmRuntimeException)
        writeByte(addr, data.toByte())
        writeByte(addr + 1, (data ushr 8).toByte())
        writeByte(addr + 2, (data ushr 16).toByte())
        writeByte(addr + 3, (data ushr 24).toByte())
        writeByte(addr + 4, (data ushr 32).toByte())
        writeByte(addr + 5, (data ushr 40).toByte())
        writeByte(addr + 6, (data ushr 48).toByte())
        writeByte(addr + 7, (data ushr 56).toByte())
    }

    override fun readLong(addr: Int): Long {
        val off = addr and PAGE_MASK
        return if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).getLong(off)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            readLongSlow(addr)
        }
    }

    private fun readLongSlow(addr: Int): Long =
        (read(addr).toLong() and 0xFFL) or
            ((read(addr + 1).toLong() and 0xFFL) shl 8) or
            ((read(addr + 2).toLong() and 0xFFL) shl 16) or
            ((read(addr + 3).toLong() and 0xFFL) shl 24) or
            ((read(addr + 4).toLong() and 0xFFL) shl 32) or
            ((read(addr + 5).toLong() and 0xFFL) shl 40) or
            ((read(addr + 6).toLong() and 0xFFL) shl 48) or
            ((read(addr + 7).toLong() and 0xFFL) shl 56)

    override fun writeShort(addr: Int, data: Short) {
        val off = addr and PAGE_MASK
        if (off + 2 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).putShort(off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 2)
            }
        } else {
            writeShortSlow(addr, data)
        }
    }

    private fun writeShortSlow(addr: Int, data: Short) {
        checkBounds(addr, 2, sizeInBytes(), ::WasmRuntimeException)
        writeByte(addr, data.toByte())
        writeByte(addr + 1, (data.toInt() ushr 8).toByte())
    }

    override fun readShort(addr: Int): Short {
        val off = addr and PAGE_MASK
        return if (off + 2 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).getShort(off)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 2)
            }
        } else {
            readShortSlow(addr)
        }
    }

    private fun readShortSlow(addr: Int): Short =
        ((read(addr).toInt() and 0xFF) or ((read(addr + 1).toInt() and 0xFF) shl 8)).toShort()

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xFFFFL

    override fun writeByte(addr: Int, data: Byte) {
        try {
            pageArrays[addr ushr PAGE_SHIFT]!![addr and PAGE_MASK] = data
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
    }

    override fun writeF32(addr: Int, data: Float) {
        val off = addr and PAGE_MASK
        if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).putFloat(off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            writeI32Slow(addr, java.lang.Float.floatToRawIntBits(data))
        }
    }

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float {
        val off = addr and PAGE_MASK
        return if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).getFloat(off)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            java.lang.Float.intBitsToFloat(readIntSlow(addr))
        }
    }

    override fun writeF64(addr: Int, data: Double) {
        val off = addr and PAGE_MASK
        if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).putDouble(off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            writeLongSlow(addr, java.lang.Double.doubleToRawLongBits(data))
        }
    }

    override fun readDouble(addr: Int): Double {
        val off = addr and PAGE_MASK
        return if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                page(addr ushr PAGE_SHIFT).getDouble(off)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            java.lang.Double.longBitsToDouble(readLongSlow(addr))
        }
    }

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun zero() {
        fill(0.toByte(), 0, sizeInBytes())
    }

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        var addr = fromIndex
        var remaining = toIndex - fromIndex
        checkBounds(addr, remaining, sizeInBytes(), ::WasmRuntimeException)
        while (remaining > 0) {
            val pageIdx = addr ushr PAGE_SHIFT
            val pageOffset = addr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            Arrays.fill(page(pageIdx).array(), pageOffset, pageOffset + chunk, value)
            addr += chunk
            remaining -= chunk
        }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        val limit = sizeInBytes()
        checkBounds(dest, size, limit, ::WasmRuntimeException)
        checkBounds(src, size, limit, ::WasmRuntimeException)
        if (dest > src && dest < src + size) {
            copyBackward(dest, src, size)
        } else {
            copyForward(dest, src, size)
        }
    }

    private fun copyForward(dest: Int, src: Int, size: Int) {
        var currentDest = dest
        var currentSrc = src
        var remaining = size
        while (remaining > 0) {
            val destOffset = currentDest and PAGE_MASK
            val srcOffset = currentSrc and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - max(destOffset, srcOffset))
            System.arraycopy(
                page(currentSrc ushr PAGE_SHIFT).array(),
                srcOffset,
                page(currentDest ushr PAGE_SHIFT).array(),
                destOffset,
                chunk,
            )
            currentDest += chunk
            currentSrc += chunk
            remaining -= chunk
        }
    }

    private fun copyBackward(dest: Int, src: Int, size: Int) {
        var currentDest = dest + size
        var currentSrc = src + size
        var remaining = size
        while (remaining > 0) {
            val destInPage = currentDest and PAGE_MASK
            val srcInPage = currentSrc and PAGE_MASK
            val destAvail = if (destInPage == 0) Memory.PAGE_SIZE else destInPage
            val srcAvail = if (srcInPage == 0) Memory.PAGE_SIZE else srcInPage
            val chunk = min(remaining, min(destAvail, srcAvail))
            currentDest -= chunk
            currentSrc -= chunk
            System.arraycopy(
                page(currentSrc ushr PAGE_SHIFT).array(),
                currentSrc and PAGE_MASK,
                page(currentDest ushr PAGE_SHIFT).array(),
                currentDest and PAGE_MASK,
                chunk,
            )
            remaining -= chunk
        }
    }

    override fun drop(segment: Int) {
        dataSegments!![segment] = PassiveDataSegment.EMPTY
    }

    override fun atomicFence() {
        ATOMIC_FENCE_IMPL.run()
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte =
        atomic(addr) {
            val value = read(addr)
            writeByte(addr, (value + delta).toByte())
            value
        }

    override fun atomicAddInt(addr: Int, delta: Int): Int =
        atomic(addr) {
            val value = readInt(addr)
            writeI32(addr, value + delta)
            value
        }

    override fun atomicAddLong(addr: Int, delta: Long): Long =
        atomic(addr) {
            val value = readLong(addr)
            writeLong(addr, value + delta)
            value
        }

    override fun atomicAddShort(addr: Int, delta: Short): Short =
        atomic(addr) {
            val value = readShort(addr)
            writeShort(addr, (value + delta).toShort())
            value
        }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte =
        atomic(addr) {
            val value = read(addr)
            writeByte(addr, (value.toInt() and mask.toInt()).toByte())
            value
        }

    override fun atomicAndInt(addr: Int, mask: Int): Int =
        atomic(addr) {
            val value = readInt(addr)
            writeI32(addr, value and mask)
            value
        }

    override fun atomicAndLong(addr: Int, mask: Long): Long =
        atomic(addr) {
            val value = readLong(addr)
            writeLong(addr, value and mask)
            value
        }

    override fun atomicAndShort(addr: Int, mask: Short): Short =
        atomic(addr) {
            val value = readShort(addr)
            writeShort(addr, (value.toInt() and mask.toInt()).toShort())
            value
        }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte =
        atomic(addr) {
            val value = read(addr)
            if (value == expected) {
                writeByte(addr, replacement)
            }
            value
        }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int =
        atomic(addr) {
            val value = readInt(addr)
            if (value == expected) {
                writeI32(addr, replacement)
            }
            value
        }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long =
        atomic(addr) {
            val value = readLong(addr)
            if (value == expected) {
                writeLong(addr, replacement)
            }
            value
        }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short =
        atomic(addr) {
            val value = readShort(addr)
            if (value == expected) {
                writeShort(addr, replacement)
            }
            value
        }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte =
        atomic(addr) {
            val value = read(addr)
            writeByte(addr, (value.toInt() or mask.toInt()).toByte())
            value
        }

    override fun atomicOrInt(addr: Int, mask: Int): Int =
        atomic(addr) {
            val value = readInt(addr)
            writeI32(addr, value or mask)
            value
        }

    override fun atomicOrLong(addr: Int, mask: Long): Long =
        atomic(addr) {
            val value = readLong(addr)
            writeLong(addr, value or mask)
            value
        }

    override fun atomicOrShort(addr: Int, mask: Short): Short =
        atomic(addr) {
            val value = readShort(addr)
            writeShort(addr, (value.toInt() or mask.toInt()).toShort())
            value
        }

    override fun atomicReadByte(addr: Int): Byte = atomic(addr) { read(addr) }

    override fun atomicReadInt(addr: Int): Int = atomic(addr) { readInt(addr) }

    override fun atomicReadLong(addr: Int): Long = atomic(addr) { readLong(addr) }

    override fun atomicReadShort(addr: Int): Short = atomic(addr) { readShort(addr) }

    override fun atomicWriteByte(addr: Int, value: Byte) {
        atomic(addr) { writeByte(addr, value) }
    }

    override fun atomicWriteInt(addr: Int, value: Int) {
        atomic(addr) { writeI32(addr, value) }
    }

    override fun atomicWriteLong(addr: Int, value: Long) {
        atomic(addr) { writeLong(addr, value) }
    }

    override fun atomicWriteShort(addr: Int, value: Short) {
        atomic(addr) { writeShort(addr, value) }
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte =
        atomic(addr) {
            val oldValue = read(addr)
            writeByte(addr, value)
            oldValue
        }

    override fun atomicXchgInt(addr: Int, value: Int): Int =
        atomic(addr) {
            val oldValue = readInt(addr)
            writeI32(addr, value)
            oldValue
        }

    override fun atomicXchgLong(addr: Int, value: Long): Long =
        atomic(addr) {
            val oldValue = readLong(addr)
            writeLong(addr, value)
            oldValue
        }

    override fun atomicXchgShort(addr: Int, value: Short): Short =
        atomic(addr) {
            val oldValue = readShort(addr)
            writeShort(addr, value)
            oldValue
        }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte =
        atomic(addr) {
            val value = read(addr)
            writeByte(addr, (value.toInt() xor mask.toInt()).toByte())
            value
        }

    override fun atomicXorInt(addr: Int, mask: Int): Int =
        atomic(addr) {
            val value = readInt(addr)
            writeI32(addr, value xor mask)
            value
        }

    override fun atomicXorLong(addr: Int, mask: Long): Long =
        atomic(addr) {
            val value = readLong(addr)
            writeLong(addr, value xor mask)
            value
        }

    override fun atomicXorShort(addr: Int, mask: Short): Short =
        atomic(addr) {
            val value = readShort(addr)
            writeShort(addr, (value.toInt() xor mask.toInt()).toShort())
            value
        }

    private fun page(index: Int): ByteBuffer = pages[index]!!

    companion object {
        // Package-private for usage as default impl. in Memory. Can become private in next major
        // release.
        @JvmField val ATOMIC_FENCE_IMPL: Runnable = getAtomicFenceImpl()

        private const val PAGE_SHIFT = 16 // PAGE_SIZE = 65536 = 2^16
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

        private fun getAtomicFenceImpl(): Runnable =
            try {
                java.lang.invoke.VarHandle.fullFence()
                Runnable { java.lang.invoke.VarHandle.fullFence() }
            } catch (_: NoSuchMethodError) {
                try {
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                    theUnsafeField.isAccessible = true
                    val theUnsafe = theUnsafeField.get(null)
                    val fullFence = unsafeClass.getMethod("fullFence")

                    Runnable {
                        try {
                            fullFence.invoke(theUnsafe)
                        } catch (ex: IllegalAccessException) {
                            throw RuntimeException(
                                "ATOMIC_FENCE implementation: Failed to invoke sun.misc.Unsafe",
                                ex,
                            )
                        } catch (ex: InvocationTargetException) {
                            throw RuntimeException(
                                "ATOMIC_FENCE implementation: Failed to invoke sun.misc.Unsafe",
                                ex,
                            )
                        }
                    }
                } catch (ex: Throwable) {
                    throw RuntimeException(
                        "ATOMIC_FENCE implementation: Failed to lookup sun.misc.Unsafe",
                        ex,
                    )
                }
            }
    }
}
