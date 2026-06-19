@file:Suppress("DEPRECATION")

package uk.shusek.krwa.runtime

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.lang.invoke.VarHandle.AccessMode
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
 * try-catch is faster than explicit checks and can be optimized by the JVM. Catching generic
 * RuntimeException to keep the method bodies short and easily inlinable.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ByteArrayMemory(private val limits: MemoryLimits) : Memory {
    private var dataSegments: Array<DataSegment>? = null

    // Page-based storage: fixed-size array of pages, slots filled lazily during grow.
    // Individual pages are never reallocated once created, enabling lock-free reads.
    private val pages: Array<ByteArray?> =
        arrayOfNulls(min(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES))

    @Volatile private var nPages = limits.initialPages()

    // Lock for grow operation (only used when memory is shared).
    private val growLock = Any()
    private val nonSharedMonitor = Any()

    private val waitStates: MutableMap<Int, WaitState>? =
        if (limits.shared()) ConcurrentHashMap() else null

    init {
        for (i in 0 until limits.initialPages()) {
            pages[i] = ByteArray(Memory.PAGE_SIZE)
        }
    }

    /**
     * @deprecated The MemAllocStrategy is no longer used since memory is allocated by page. Use
     *   [ByteArrayMemory] instead.
     */
    @Deprecated("The MemAllocStrategy is no longer used since memory is allocated by page.")
    @Suppress("UNUSED_PARAMETER")
    constructor(limits: MemoryLimits, allocStrategy: MemAllocStrategy) : this(limits)

    // Tracks wait state per address: waiter count and pending wakeups.
    // all field access should be guarded by synchronizing on the instance.
    // Invariants: 0 <= pendingWakeups <= waiterCount
    private class WaitState {
        var waiterCount = 0
        var pendingWakeups = 0

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun monitor(): java.lang.Object = this as java.lang.Object
    }

    override fun lock(address: Int): Any {
        throw UnsupportedOperationException()
    }

    private fun monitor(address: Int): Any =
        if (shared()) waitStates!!.computeIfAbsent(address) { WaitState() } else nonSharedMonitor

    // Wait IF condition is true.
    private fun waitOn(address: Int, condition: () -> Boolean, timeout: Long): Int {
        if (!shared()) {
            throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")
        }

        val deadline = if (timeout < 0) Long.MAX_VALUE else System.nanoTime() + timeout
        val state = waitStates!!.computeIfAbsent(address) { WaitState() }

        synchronized(state) {
            // Check the condition while holding the lock. This must be atomic with the decision to
            // wait.
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
        waitOn(address, { atomicReadInt(address) == expected }, timeout)

    override fun waitOn(address: Int, expected: Long, timeout: Long): Int =
        waitOn(address, { atomicReadLong(address) == expected }, timeout)

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
            pages[i] = ByteArray(Memory.PAGE_SIZE)
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

    private fun outOfBoundsException(e: RuntimeException, addr: Int, size: Int): RuntimeException =
        if (
            e is IndexOutOfBoundsException ||
                e is IllegalArgumentException ||
                e is NullPointerException ||
                e is NegativeArraySizeException
        ) {
            WasmRuntimeException(
                "out of bounds memory access: attempted to access address: " +
                    "$addr but limit is: ${sizeInBytes()} and size: $size"
            )
        } else {
            e
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
            System.arraycopy(data, currentOffset, page(pageIdx), pageOffset, chunk)
            currentAddr += chunk
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun read(addr: Int): Byte =
        try {
            page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK]
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }

    override fun readU8Int(addr: Int): Int =
        try {
            page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK].toInt() and 0xFF
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
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
        var currentAddr = addr
        while (remaining > 0) {
            val pageIdx = currentAddr ushr PAGE_SHIFT
            val pageOffset = currentAddr and PAGE_MASK
            val chunk = min(remaining, Memory.PAGE_SIZE - pageOffset)
            System.arraycopy(page(pageIdx), pageOffset, target, targetOffset, chunk)
            currentAddr += chunk
            targetOffset += chunk
            remaining -= chunk
        }
    }

    override fun writeI32(addr: Int, data: Int) {
        val off = addr and PAGE_MASK
        if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                INT_ARR_HANDLE.set(page(addr ushr PAGE_SHIFT), off, data)
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
                INT_ARR_HANDLE.get(page(addr ushr PAGE_SHIFT), off) as Int
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
                LONG_ARR_HANDLE.set(page(addr ushr PAGE_SHIFT), off, data)
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
                LONG_ARR_HANDLE.get(page(addr ushr PAGE_SHIFT), off) as Long
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
                SHORT_ARR_HANDLE.set(page(addr ushr PAGE_SHIFT), off, data)
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
                SHORT_ARR_HANDLE.get(page(addr ushr PAGE_SHIFT), off) as Short
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
            page(addr ushr PAGE_SHIFT)[addr and PAGE_MASK] = data
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, 1)
        }
    }

    override fun writeF32(addr: Int, data: Float) {
        val off = addr and PAGE_MASK
        if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                FLOAT_ARR_HANDLE.set(page(addr ushr PAGE_SHIFT), off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            writeI32(addr, java.lang.Float.floatToRawIntBits(data))
        }
    }

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float {
        val off = addr and PAGE_MASK
        return if (off + 4 <= Memory.PAGE_SIZE) {
            try {
                FLOAT_ARR_HANDLE.get(page(addr ushr PAGE_SHIFT), off) as Float
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 4)
            }
        } else {
            java.lang.Float.intBitsToFloat(readInt(addr))
        }
    }

    override fun writeF64(addr: Int, data: Double) {
        val off = addr and PAGE_MASK
        if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                DOUBLE_ARR_HANDLE.set(page(addr ushr PAGE_SHIFT), off, data)
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            writeLong(addr, java.lang.Double.doubleToRawLongBits(data))
        }
    }

    override fun readDouble(addr: Int): Double {
        val off = addr and PAGE_MASK
        return if (off + 8 <= Memory.PAGE_SIZE) {
            try {
                DOUBLE_ARR_HANDLE.get(page(addr ushr PAGE_SHIFT), off) as Double
            } catch (e: RuntimeException) {
                throw outOfBoundsException(e, addr, 8)
            }
        } else {
            java.lang.Double.longBitsToDouble(readLong(addr))
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
            Arrays.fill(page(pageIdx), pageOffset, pageOffset + chunk, value)
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
                page(currentSrc ushr PAGE_SHIFT),
                srcOffset,
                page(currentDest ushr PAGE_SHIFT),
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
                page(currentSrc ushr PAGE_SHIFT),
                currentSrc and PAGE_MASK,
                page(currentDest ushr PAGE_SHIFT),
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
        VarHandle.fullFence()
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.getAndAdd(page, off, delta) as Byte
            }
            synchronized(monitor(addr)) {
                val value = BYTE_ARR_HANDLE.get(page, off) as Byte
                BYTE_ARR_HANDLE.set(page, off, (value + delta).toByte())
                value
            }
        }

    override fun atomicAddInt(addr: Int, delta: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.getAndAdd(page, off, delta) as Int
            }
            synchronized(monitor(addr)) {
                val value = INT_ARR_HANDLE.get(page, off) as Int
                INT_ARR_HANDLE.set(page, off, value + delta)
                value
            }
        }

    override fun atomicAddLong(addr: Int, delta: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.getAndAdd(page, off, delta) as Long
            }
            synchronized(monitor(addr)) {
                val value = LONG_ARR_HANDLE.get(page, off) as Long
                LONG_ARR_HANDLE.set(page, off, value + delta)
                value
            }
        }

    override fun atomicAddShort(addr: Int, delta: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.getAndAdd(page, off, delta) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val mask = 0xFFFF shl shift
                while (true) {
                    val oldInt = INT_ARR_HANDLE.getVolatile(page, alignedOff) as Int
                    val oldShort = ((oldInt ushr shift) and 0xFFFF).toShort()
                    val newShort = (oldShort + delta).toShort()
                    val newInt =
                        (oldInt and mask.inv()) or ((newShort.toInt() and 0xFFFF) shl shift)
                    if (INT_ARR_HANDLE.compareAndSet(page, alignedOff, oldInt, newInt)) {
                        return@atomicOp oldShort
                    }
                }
            }
            synchronized(monitor(addr)) {
                val value = SHORT_ARR_HANDLE.get(page, off) as Short
                SHORT_ARR_HANDLE.set(page, off, (value + delta).toShort())
                value
            }
        }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.getAndBitwiseAnd(page, off, mask) as Byte
            }
            synchronized(monitor(addr)) {
                val value = BYTE_ARR_HANDLE.get(page, off) as Byte
                BYTE_ARR_HANDLE.set(page, off, (value.toInt() and mask.toInt()).toByte())
                value
            }
        }

    override fun atomicAndInt(addr: Int, mask: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.getAndBitwiseAnd(page, off, mask) as Int
            }
            synchronized(monitor(addr)) {
                val value = INT_ARR_HANDLE.get(page, off) as Int
                INT_ARR_HANDLE.set(page, off, value and mask)
                value
            }
        }

    override fun atomicAndLong(addr: Int, mask: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.getAndBitwiseAnd(page, off, mask) as Long
            }
            synchronized(monitor(addr)) {
                val value = LONG_ARR_HANDLE.get(page, off) as Long
                LONG_ARR_HANDLE.set(page, off, value and mask)
                value
            }
        }

    override fun atomicAndShort(addr: Int, mask: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.getAndBitwiseAnd(page, off, mask) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val intMask = ((mask.toInt() and 0xFFFF) shl shift) or (0xFFFF shl shift).inv()
                val intValue = INT_ARR_HANDLE.getAndBitwiseAnd(page, alignedOff, intMask) as Int
                return@atomicOp ((intValue ushr shift) and 0xFFFF).toShort()
            }
            synchronized(monitor(addr)) {
                val value = SHORT_ARR_HANDLE.get(page, off) as Short
                SHORT_ARR_HANDLE.set(page, off, (value.toInt() and mask.toInt()).toShort())
                value
            }
        }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.compareAndExchange(page, off, expected, replacement)
                    as Byte
            }
            synchronized(monitor(addr)) {
                val value = BYTE_ARR_HANDLE.get(page, off) as Byte
                if (value == expected) {
                    BYTE_ARR_HANDLE.set(page, off, replacement)
                }
                value
            }
        }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.compareAndExchange(page, off, expected, replacement)
                    as Int
            }
            synchronized(monitor(addr)) {
                val value = INT_ARR_HANDLE.get(page, off) as Int
                if (value == expected) {
                    INT_ARR_HANDLE.set(page, off, replacement)
                }
                value
            }
        }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.compareAndExchange(page, off, expected, replacement)
                    as Long
            }
            synchronized(monitor(addr)) {
                val value = LONG_ARR_HANDLE.get(page, off) as Long
                if (value == expected) {
                    LONG_ARR_HANDLE.set(page, off, replacement)
                }
                value
            }
        }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.compareAndExchange(
                    page,
                    off,
                    expected,
                    replacement,
                ) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val mask = 0xFFFF shl shift
                while (true) {
                    val oldInt = INT_ARR_HANDLE.getVolatile(page, alignedOff) as Int
                    val oldShort = ((oldInt ushr shift) and 0xFFFF).toShort()
                    if (oldShort != expected) {
                        return@atomicOp oldShort
                    }
                    val newInt =
                        (oldInt and mask.inv()) or ((replacement.toInt() and 0xFFFF) shl shift)
                    if (INT_ARR_HANDLE.compareAndSet(page, alignedOff, oldInt, newInt)) {
                        return@atomicOp oldShort
                    }
                }
            }
            synchronized(monitor(addr)) {
                val value = SHORT_ARR_HANDLE.get(page, off) as Short
                if (value == expected) {
                    SHORT_ARR_HANDLE.set(page, off, replacement)
                }
                value
            }
        }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.getAndBitwiseOr(page, off, mask) as Byte
            }
            synchronized(monitor(addr)) {
                val value = BYTE_ARR_HANDLE.get(page, off) as Byte
                BYTE_ARR_HANDLE.set(page, off, (value.toInt() or mask.toInt()).toByte())
                value
            }
        }

    override fun atomicOrInt(addr: Int, mask: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.getAndBitwiseOr(page, off, mask) as Int
            }
            synchronized(monitor(addr)) {
                val value = INT_ARR_HANDLE.get(page, off) as Int
                INT_ARR_HANDLE.set(page, off, value or mask)
                value
            }
        }

    override fun atomicOrLong(addr: Int, mask: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.getAndBitwiseOr(page, off, mask) as Long
            }
            synchronized(monitor(addr)) {
                val value = LONG_ARR_HANDLE.get(page, off) as Long
                LONG_ARR_HANDLE.set(page, off, value or mask)
                value
            }
        }

    override fun atomicOrShort(addr: Int, mask: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.getAndBitwiseOr(page, off, mask) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val intMask = (mask.toInt() and 0xFFFF) shl shift
                val intValue = INT_ARR_HANDLE.getAndBitwiseOr(page, alignedOff, intMask) as Int
                return@atomicOp ((intValue ushr shift) and 0xFFFF).toShort()
            }
            synchronized(monitor(addr)) {
                val value = SHORT_ARR_HANDLE.get(page, off) as Short
                SHORT_ARR_HANDLE.set(page, off, (value.toInt() or mask.toInt()).toShort())
                value
            }
        }

    override fun atomicReadByte(addr: Int): Byte =
        atomicOp(addr, 1) { page, off ->
            if (BYTE_ARR_HANDLE.isAccessModeSupported(AccessMode.GET_VOLATILE)) {
                return@atomicOp BYTE_ARR_HANDLE.getVolatile(page, off) as Byte
            }
            synchronized(monitor(addr)) { BYTE_ARR_HANDLE.get(page, off) as Byte }
        }

    override fun atomicReadInt(addr: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (INT_ARR_HANDLE.isAccessModeSupported(AccessMode.GET_VOLATILE)) {
                return@atomicOp INT_ARR_HANDLE.getVolatile(page, off) as Int
            }
            synchronized(monitor(addr)) { INT_ARR_HANDLE.get(page, off) as Int }
        }

    override fun atomicReadLong(addr: Int): Long =
        atomicOp(addr, 8) { page, off ->
            if (LONG_ARR_HANDLE.isAccessModeSupported(AccessMode.GET_VOLATILE)) {
                return@atomicOp LONG_ARR_HANDLE.getVolatile(page, off) as Long
            }
            synchronized(monitor(addr)) { LONG_ARR_HANDLE.get(page, off) as Long }
        }

    override fun atomicReadShort(addr: Int): Short =
        atomicOp(addr, 2) { page, off ->
            if (SHORT_ARR_HANDLE.isAccessModeSupported(AccessMode.GET_VOLATILE)) {
                return@atomicOp SHORT_ARR_HANDLE.getVolatile(page, off) as Short
            }
            synchronized(monitor(addr)) { SHORT_ARR_HANDLE.get(page, off) as Short }
        }

    override fun atomicWriteByte(addr: Int, value: Byte) {
        atomicUnitOp(addr, 1) { page, off ->
            if (BYTE_ARR_HANDLE.isAccessModeSupported(AccessMode.SET_VOLATILE)) {
                BYTE_ARR_HANDLE.setVolatile(page, off, value)
                return@atomicUnitOp
            }
            synchronized(monitor(addr)) { BYTE_ARR_HANDLE.set(page, off, value) }
        }
    }

    override fun atomicWriteInt(addr: Int, value: Int) {
        atomicUnitOp(addr, 4) { page, off ->
            if (INT_ARR_HANDLE.isAccessModeSupported(AccessMode.SET_VOLATILE)) {
                INT_ARR_HANDLE.setVolatile(page, off, value)
                return@atomicUnitOp
            }
            synchronized(monitor(addr)) { INT_ARR_HANDLE.set(page, off, value) }
        }
    }

    override fun atomicWriteLong(addr: Int, value: Long) {
        atomicUnitOp(addr, 8) { page, off ->
            if (LONG_ARR_HANDLE.isAccessModeSupported(AccessMode.SET_VOLATILE)) {
                LONG_ARR_HANDLE.setVolatile(page, off, value)
                return@atomicUnitOp
            }
            synchronized(monitor(addr)) { LONG_ARR_HANDLE.set(page, off, value) }
        }
    }

    override fun atomicWriteShort(addr: Int, value: Short) {
        atomicUnitOp(addr, 2) { page, off ->
            if (SHORT_ARR_HANDLE.isAccessModeSupported(AccessMode.SET_VOLATILE)) {
                SHORT_ARR_HANDLE.setVolatile(page, off, value)
                return@atomicUnitOp
            }
            synchronized(monitor(addr)) { SHORT_ARR_HANDLE.set(page, off, value) }
        }
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.getAndSet(page, off, value) as Byte
            }
            synchronized(monitor(addr)) {
                val oldValue = BYTE_ARR_HANDLE.get(page, off) as Byte
                BYTE_ARR_HANDLE.set(page, off, value)
                oldValue
            }
        }

    override fun atomicXchgInt(addr: Int, value: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.getAndSet(page, off, value) as Int
            }
            synchronized(monitor(addr)) {
                val oldValue = INT_ARR_HANDLE.get(page, off) as Int
                INT_ARR_HANDLE.set(page, off, value)
                oldValue
            }
        }

    override fun atomicXchgLong(addr: Int, value: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.getAndSet(page, off, value) as Long
            }
            synchronized(monitor(addr)) {
                val oldValue = LONG_ARR_HANDLE.get(page, off) as Long
                LONG_ARR_HANDLE.set(page, off, value)
                oldValue
            }
        }

    override fun atomicXchgShort(addr: Int, value: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.getAndSet(page, off, value) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val mask = 0xFFFF shl shift
                while (true) {
                    val oldInt = INT_ARR_HANDLE.getVolatile(page, alignedOff) as Int
                    val newInt = (oldInt and mask.inv()) or ((value.toInt() and 0xFFFF) shl shift)
                    if (INT_ARR_HANDLE.compareAndSet(page, alignedOff, oldInt, newInt)) {
                        return@atomicOp ((oldInt ushr shift) and 0xFFFF).toShort()
                    }
                }
            }
            synchronized(monitor(addr)) {
                val oldValue = SHORT_ARR_HANDLE.get(page, off) as Short
                SHORT_ARR_HANDLE.set(page, off, value)
                oldValue
            }
        }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte =
        atomicOp(addr, 1) { page, off ->
            if (HAS_BYTE_ATOMICS) {
                return@atomicOp BYTE_ARR_HANDLE.getAndBitwiseXor(page, off, mask) as Byte
            }
            synchronized(monitor(addr)) {
                val value = BYTE_ARR_HANDLE.get(page, off) as Byte
                BYTE_ARR_HANDLE.set(page, off, (value.toInt() xor mask.toInt()).toByte())
                value
            }
        }

    override fun atomicXorInt(addr: Int, mask: Int): Int =
        atomicOp(addr, 4) { page, off ->
            if (HAS_INT_ATOMICS) {
                return@atomicOp INT_ARR_HANDLE.getAndBitwiseXor(page, off, mask) as Int
            }
            synchronized(monitor(addr)) {
                val value = INT_ARR_HANDLE.get(page, off) as Int
                INT_ARR_HANDLE.set(page, off, value xor mask)
                value
            }
        }

    override fun atomicXorLong(addr: Int, mask: Long): Long =
        atomicOp(addr, 8) { page, off ->
            if (HAS_LONG_ATOMICS) {
                return@atomicOp LONG_ARR_HANDLE.getAndBitwiseXor(page, off, mask) as Long
            }
            synchronized(monitor(addr)) {
                val value = LONG_ARR_HANDLE.get(page, off) as Long
                LONG_ARR_HANDLE.set(page, off, value xor mask)
                value
            }
        }

    override fun atomicXorShort(addr: Int, mask: Short): Short =
        atomicOp(addr, 2) { page, off ->
            if (HAS_SHORT_ATOMICS) {
                return@atomicOp SHORT_ARR_HANDLE.getAndBitwiseXor(page, off, mask) as Short
            }
            if (HAS_INT_ATOMICS) {
                val alignedOff = off and 3.inv()
                val shift = (off and 2) * 8
                val intMask = (mask.toInt() and 0xFFFF) shl shift
                val intValue = INT_ARR_HANDLE.getAndBitwiseXor(page, alignedOff, intMask) as Int
                return@atomicOp ((intValue ushr shift) and 0xFFFF).toShort()
            }
            synchronized(monitor(addr)) {
                val value = SHORT_ARR_HANDLE.get(page, off) as Short
                SHORT_ARR_HANDLE.set(page, off, (value.toInt() xor mask.toInt()).toShort())
                value
            }
        }

    private fun <T> atomicOp(addr: Int, size: Int, block: (ByteArray, Int) -> T): T =
        try {
            block(page(addr ushr PAGE_SHIFT), addr and PAGE_MASK)
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, size)
        }

    private fun atomicUnitOp(addr: Int, size: Int, block: (ByteArray, Int) -> Unit) {
        try {
            block(page(addr ushr PAGE_SHIFT), addr and PAGE_MASK)
        } catch (e: RuntimeException) {
            throw outOfBoundsException(e, addr, size)
        }
    }

    private fun page(index: Int): ByteArray = pages[index]!!

    companion object {
        // get access to the byte array elements viewed as if it were a different primitive array
        // type, such as int[], long[], etc. This is actually the fastest way to access and
        // reinterpret the underlying bytes. see: https://stackoverflow.com/a/65276765/7898052
        private val BYTE_ARR_HANDLE: VarHandle =
            MethodHandles.arrayElementVarHandle(ByteArray::class.java)
        private val SHORT_ARR_HANDLE: VarHandle =
            MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val INT_ARR_HANDLE: VarHandle =
            MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val FLOAT_ARR_HANDLE: VarHandle =
            MethodHandles.byteArrayViewVarHandle(FloatArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val LONG_ARR_HANDLE: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val DOUBLE_ARR_HANDLE: VarHandle =
            MethodHandles.byteArrayViewVarHandle(DoubleArray::class.java, ByteOrder.LITTLE_ENDIAN)

        private val HAS_BYTE_ATOMICS = hasFullAtomicSupport(BYTE_ARR_HANDLE)
        private val HAS_SHORT_ATOMICS = hasFullAtomicSupport(SHORT_ARR_HANDLE)
        private val HAS_INT_ATOMICS = hasFullAtomicSupport(INT_ARR_HANDLE)
        private val HAS_LONG_ATOMICS = hasFullAtomicSupport(LONG_ARR_HANDLE)

        private const val PAGE_SHIFT = 16 // PAGE_SIZE = 65536 = 2^16
        private const val PAGE_MASK = Memory.PAGE_SIZE - 1

        private fun hasFullAtomicSupport(varHandle: VarHandle): Boolean =
            varHandle.isAccessModeSupported(AccessMode.GET_VOLATILE) &&
                varHandle.isAccessModeSupported(AccessMode.SET_VOLATILE) &&
                varHandle.isAccessModeSupported(AccessMode.COMPARE_AND_EXCHANGE) &&
                varHandle.isAccessModeSupported(AccessMode.GET_AND_SET) &&
                varHandle.isAccessModeSupported(AccessMode.GET_AND_ADD) &&
                varHandle.isAccessModeSupported(AccessMode.GET_AND_BITWISE_AND) &&
                varHandle.isAccessModeSupported(AccessMode.GET_AND_BITWISE_OR) &&
                varHandle.isAccessModeSupported(AccessMode.GET_AND_BITWISE_XOR)

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
