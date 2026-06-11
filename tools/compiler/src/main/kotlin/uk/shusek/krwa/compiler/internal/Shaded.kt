package uk.shusek.krwa.compiler.internal

import uk.shusek.krwa.runtime.ConstantEvaluators
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.MemCopyWorkaround
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.runtime.OpcodeImpl
import uk.shusek.krwa.runtime.TrapException
import uk.shusek.krwa.runtime.WasmArray
import uk.shusek.krwa.runtime.WasmException
import uk.shusek.krwa.runtime.WasmI31Ref
import uk.shusek.krwa.runtime.WasmInterruptedException
import uk.shusek.krwa.runtime.WasmRuntimeException
import uk.shusek.krwa.runtime.WasmStruct
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/** This class will get shaded into the compiled code. */
object Shaded {

    @JvmStatic
    fun callIndirect(args: LongArray, typeId: Int, funcId: Int, instance: Instance): LongArray {
        var actualTypeIdx = instance.functionType(funcId)
        if (
            actualTypeIdx != typeId &&
                !ValType.heapTypeSubtype(actualTypeIdx, typeId, instance.module().typeSection())
        ) {
            throw throwIndirectCallTypeMismatch()
        }
        return instance.getMachine().call(funcId, args)
    }

    @JvmStatic
    fun callIndirect(args: LongArray, funcId: Int, instance: Instance): LongArray {
        return instance.getMachine().call(funcId, args)
    }

    @JvmStatic
    fun callHostFunction(instance: Instance, funcId: Int, args: LongArray): LongArray? {
        val imprt = instance.imports().function(funcId)
        return imprt.handle()!!.apply(instance, args)
    }

    @JvmStatic
    fun setTailCall(funcId: Int, args: LongArray, instance: Instance) {
        instance.setTailCall(funcId, args)
    }

    @JvmStatic
    fun setTailCallIndirect(
        args: LongArray,
        funcTableIdx: Int,
        typeId: Int,
        tableIdx: Int,
        instance: Instance,
    ) {
        val table = instance.table(tableIdx)
        var funcId = table.requiredRef(funcTableIdx)
        val refInstance = table.instance(funcTableIdx)
        if (refInstance != null && refInstance != instance) {
            throw WasmEngineException(
                "Indirect tail-call to a different Machine implementation is not supported"
            )
        }
        var actualTypeIdx = instance.functionType(funcId)
        if (
            actualTypeIdx != typeId &&
                !ValType.heapTypeSubtype(actualTypeIdx, typeId, instance.module().typeSection())
        ) {
            throw throwIndirectCallTypeMismatch()
        }
        instance.setTailCall(funcId, args)
    }

    @JvmStatic
    fun isTailCallPending(instance: Instance): Boolean {
        return instance.isTailCallPending()
    }

    @JvmStatic
    fun resolveTailCall(instance: Instance): LongArray {
        var funcId = instance.tailCallFuncId()
        var args: LongArray = instance.tailCallArgs()
        instance.clearTailCall()
        return instance.getMachine().call(funcId, args)
    }

    @JvmStatic
    fun isRefNull(ref: Int): Boolean {
        return ref == Value.REF_NULL_VALUE
    }

    @JvmStatic
    fun refAsNonNull(ref: Int): Int {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null reference")
        }
        return ref
    }

    @JvmStatic
    fun tableGet(index: Int, tableIndex: Int, instance: Instance): Int {
        return OpcodeImpl.TABLE_GET(instance, tableIndex, index)
    }

    @JvmStatic
    fun tableSet(index: Int, value: Int, tableIndex: Int, instance: Instance) {
        instance.table(tableIndex).setRef(index, value, instance)
    }

    @JvmStatic
    fun tableGrow(value: Int, size: Int, tableIndex: Int, instance: Instance): Int {
        return instance.table(tableIndex).grow(size, value, instance)
    }

    @JvmStatic
    fun tableSize(tableIndex: Int, instance: Instance): Int {
        return instance.table(tableIndex).size()
    }

    @JvmStatic
    fun tableFill(offset: Int, value: Int, size: Int, tableIndex: Int, instance: Instance) {
        OpcodeImpl.TABLE_FILL(instance, tableIndex, size, value, offset)
    }

    @JvmStatic
    fun tableCopy(
        d: Int,
        s: Int,
        size: Int,
        dstTableIndex: Int,
        srcTableIndex: Int,
        instance: Instance,
    ) {
        OpcodeImpl.TABLE_COPY(instance, srcTableIndex, dstTableIndex, size, s, d)
    }

    @JvmStatic
    fun tableInit(
        offset: Int,
        elemidx: Int,
        size: Int,
        elementidx: Int,
        tableidx: Int,
        instance: Instance,
    ) {
        OpcodeImpl.TABLE_INIT(instance, tableidx, elementidx, size, elemidx, offset)
    }

    @JvmStatic
    fun i32_ge_u(a: Int, b: Int): Int {
        if (memCopyWorkaround) {
            // Use this workaround to avoid a bug in some JVMs (Temurin 17)
            return MemCopyWorkaround.i32_ge_u(a, b)
        } else {
            // Go back to the original implementation, once that bug is no longer an issue:
            return OpcodeImpl.I32_GE_U(a, b)
        }
    }

    private val memCopyWorkaround: Boolean =
        System.getProperty("krwa.memCopyWorkaround")?.let { java.lang.Boolean.valueOf(it) }
            ?: MemCopyWorkaround.shouldUseMemWorkaround()

    @JvmStatic
    fun memoryCopy(destination: Int, offset: Int, size: Int, memory: Memory) {
        if (memCopyWorkaround) {
            // Use this workaround to avoid a bug in some JVMs (Temurin 17)
            MemCopyWorkaround.memoryCopy(destination, offset, size, memory)
        } else {
            // Go back to the original implementation, once that bug is no longer an issue:
            memory.copy(destination, offset, size)
        }
    }

    @JvmStatic
    fun memoryCopy(destination: Int, offset: Int, size: Int, dstMemory: Memory, srcMemory: Memory) {
        if (dstMemory == srcMemory) {
            memoryCopy(destination, offset, size, dstMemory)
        } else {
            dstMemory.write(destination, srcMemory.readBytes(offset, size))
        }
    }

    @JvmStatic
    fun memoryFill(offset: Int, value: Byte, size: Int, memory: Memory) {
        var end = size + offset
        memory.fill(value, offset, end)
    }

    @JvmStatic
    fun memoryInit(destination: Int, offset: Int, size: Int, segmentId: Int, memory: Memory) {
        memory.initPassiveSegment(segmentId, destination, offset, size)
    }

    @JvmStatic
    fun memoryGrow(size: Int, memory: Memory): Int {
        return memory.grow(size)
    }

    @JvmStatic
    fun memoryDrop(segment: Int, memory: Memory) {
        memory.drop(segment)
    }

    @JvmStatic
    fun memoryPages(memory: Memory): Int {
        return memory.pages()
    }

    @JvmStatic
    fun memoryReadByte(base: Int, offset: Int, memory: Memory): Byte {
        return memory.read(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryReadShort(base: Int, offset: Int, memory: Memory): Short {
        return memory.readShort(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryReadInt(base: Int, offset: Int, memory: Memory): Int {
        return memory.readInt(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryReadLong(base: Int, offset: Int, memory: Memory): Long {
        return memory.readLong(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryReadFloat(base: Int, offset: Int, memory: Memory): Float {
        return memory.readFloat(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryReadDouble(base: Int, offset: Int, memory: Memory): Double {
        return memory.readDouble(getAddr(base, offset))
    }

    @JvmStatic
    fun memoryWriteByte(base: Int, value: Byte, offset: Int, memory: Memory) {
        memory.writeByte(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryWriteShort(base: Int, value: Short, offset: Int, memory: Memory) {
        memory.writeShort(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryWriteInt(base: Int, value: Int, offset: Int, memory: Memory) {
        memory.writeI32(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryWriteLong(base: Int, value: Long, offset: Int, memory: Memory) {
        memory.writeLong(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryWriteFloat(base: Int, value: Float, offset: Int, memory: Memory) {
        memory.writeF32(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryWriteDouble(base: Int, value: Double, offset: Int, memory: Memory) {
        memory.writeF64(getAddr(base, offset), value)
    }

    @JvmStatic
    fun memoryAtomicIntByteRead(base: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicReadByte(ptr))
    }

    @JvmStatic
    fun memoryAtomicIntShortRead(base: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicReadShort(ptr))
    }

    @JvmStatic
    fun memoryAtomicIntRead(base: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicReadInt(ptr)
    }

    @JvmStatic
    fun memoryAtomicLongRead(base: Int, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicReadLong(ptr)
    }

    @JvmStatic
    fun memoryAtomicLongByteRead(base: Int, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicReadByte(ptr))
    }

    @JvmStatic
    fun memoryAtomicLongShortRead(base: Int, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicReadShort(ptr))
    }

    @JvmStatic
    fun memoryAtomicLongIntRead(base: Int, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicReadInt(ptr))
    }

    @JvmStatic
    fun memoryAtomicIntWrite(base: Int, value: Int, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        memory.atomicWriteInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntByteWrite(base: Int, value: Byte, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        memory.atomicWriteByte(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntShortWrite(base: Int, value: Short, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        memory.atomicWriteShort(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongWrite(base: Int, value: Long, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        memory.atomicWriteLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongByteWrite(base: Int, value: Byte, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        memory.atomicWriteByte(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongShortWrite(base: Int, value: Short, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        memory.atomicWriteShort(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongIntWrite(base: Int, value: Int, offset: Int, memory: Memory) {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        memory.atomicWriteInt(ptr, value)
    }

    // let the following memory access throw if the base is negative
    @JvmStatic
    fun getAddr(base: Int, offset: Int): Int {
        return if (base < 0) base else base + offset
    }

    @JvmStatic
    fun throwCallStackExhausted(e: StackOverflowError): RuntimeException {
        throw WasmEngineException("call stack exhausted", e)
    }

    @JvmStatic
    fun throwIndirectCallTypeMismatch(): RuntimeException {
        return WasmEngineException("indirect call type mismatch")
    }

    @JvmStatic
    fun throwOutOfBoundsMemoryAccess(): RuntimeException {
        throw WasmRuntimeException("out of bounds memory access")
    }

    @JvmStatic
    fun throwTrapException(): RuntimeException {
        throw TrapException("Trapped on unreachable instruction")
    }

    @JvmStatic
    fun throwNullFunctionReference(): RuntimeException {
        throw TrapException("null function reference")
    }

    @JvmStatic
    fun throwUnknownFunction(index: Int): RuntimeException {
        throw InvalidException(String.format("unknown function %d", index))
    }

    @JvmStatic
    fun checkInterruption() {
        if (Thread.currentThread().isInterrupted) {
            throw WasmInterruptedException("Thread interrupted")
        }
    }

    @JvmStatic
    fun readGlobal(index: Int, instance: Instance): Long {
        return instance.global(index).value
    }

    @JvmStatic
    fun writeGlobal(value: Long, index: Int, instance: Instance) {
        instance.global(index).value = value
    }

    @JvmStatic
    fun readGlobalRef(index: Int, instance: Instance): Int {
        val globalValue = instance.global(index).value
        if (Value.isI31(globalValue)) {
            val i31 = WasmI31Ref(Value.decodeI31U(globalValue))
            return instance.registerGcRef(i31)
        }
        return globalValue.toInt()
    }

    /** Creates a WasmException for the given tag and arguments */
    @JvmStatic
    fun createWasmException(args: LongArray?, tagNumber: Int, instance: Instance): WasmException {
        val actualArgs = args ?: LongArray(0)
        val e = WasmException(instance, tagNumber, actualArgs)
        instance.registerException(e)
        return e
    }

    @JvmStatic
    fun exceptionMatches(exception: WasmException, tag: Int, instance: Instance): Boolean {
        if (exception.instance() == instance && exception.tagIdx() == tag) {
            return true
        }

        val currentCatchTag = instance.tag(tag)
        val exceptionTag = exception.instance().tag(exception.tagIdx())
        return tag < instance.imports().tagCount() &&
            currentCatchTag.type()!!.typesMatch(exceptionTag.type()!!) &&
            currentCatchTag.type()!!.returnsMatch(exceptionTag.type()!!)
    }

    // I32 32-bit RMW ops
    @JvmStatic
    fun memoryAtomicIntRmwAdd(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAddInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwSub(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAddInt(ptr, -value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwAnd(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAndInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwOr(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicOrInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwXor(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicXorInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwXchg(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicXchgInt(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicIntRmwCmpxchg(
        base: Int,
        expected: Int,
        replacement: Int,
        offset: Int,
        memory: Memory,
    ): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicCmpxchgInt(ptr, expected, replacement)
    }

    // I32 8-bit RMW ops
    @JvmStatic
    fun memoryAtomicIntRmw8AddU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicAddByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8SubU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicAddByte(ptr, (-value).toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8AndU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicAndByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8OrU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicOrByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8XorU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicXorByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8XchgU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(memory.atomicXchgByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw8CmpxchgU(
        base: Int,
        expected: Int,
        replacement: Int,
        offset: Int,
        memory: Memory,
    ): Int {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedInt(
            memory.atomicCmpxchgByte(ptr, expected.toByte(), replacement.toByte())
        )
    }

    // I32 16-bit RMW ops
    @JvmStatic
    fun memoryAtomicIntRmw16AddU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAddShort(ptr, value.toShort()).toInt() and 0xFFFF
    }

    @JvmStatic
    fun memoryAtomicIntRmw16SubU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicAddShort(ptr, (-value).toShort()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw16AndU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicAndShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw16OrU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicOrShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw16XorU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicXorShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw16XchgU(base: Int, value: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(memory.atomicXchgShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicIntRmw16CmpxchgU(
        base: Int,
        expected: Int,
        replacement: Int,
        offset: Int,
        memory: Memory,
    ): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedInt(
            memory.atomicCmpxchgShort(ptr, expected.toShort(), replacement.toShort())
        )
    }

    // I64 8-bit RMW ops
    @JvmStatic
    fun memoryAtomicLongRmw8AddU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicAddByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8SubU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicAddByte(ptr, (-value).toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8AndU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicAndByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8OrU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicOrByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8XorU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicXorByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8XchgU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(memory.atomicXchgByte(ptr, value.toByte()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw8CmpxchgU(
        base: Int,
        expected: Long,
        replacement: Long,
        offset: Int,
        memory: Memory,
    ): Long {
        val ptr = getAddr(base, offset)
        return java.lang.Byte.toUnsignedLong(
            memory.atomicCmpxchgByte(ptr, expected.toByte(), replacement.toByte())
        )
    }

    // I64 16-bit RMW ops
    @JvmStatic
    fun memoryAtomicLongRmw16AddU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicAddShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16SubU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicAddShort(ptr, (-value).toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16AndU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicAndShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16OrU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicOrShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16XorU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicXorShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16XchgU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(memory.atomicXchgShort(ptr, value.toShort()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw16CmpxchgU(
        base: Int,
        expected: Long,
        replacement: Long,
        offset: Int,
        memory: Memory,
    ): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 2 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return java.lang.Short.toUnsignedLong(
            memory.atomicCmpxchgShort(ptr, expected.toShort(), replacement.toShort())
        )
    }

    // I64 32-bit RMW ops
    @JvmStatic
    fun memoryAtomicLongRmw32AddU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicAddInt(ptr, value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32SubU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicAddInt(ptr, -value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32AndU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicAndInt(ptr, value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32OrU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicOrInt(ptr, value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32XorU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicXorInt(ptr, value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32XchgU(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(memory.atomicXchgInt(ptr, value.toInt()))
    }

    @JvmStatic
    fun memoryAtomicLongRmw32CmpxchgU(
        base: Int,
        expected: Long,
        replacement: Long,
        offset: Int,
        memory: Memory,
    ): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return Integer.toUnsignedLong(
            memory.atomicCmpxchgInt(ptr, expected.toInt(), replacement.toInt())
        )
    }

    // I64 64-bit RMW ops
    @JvmStatic
    fun memoryAtomicLongRmwAdd(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAddLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwSub(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAddLong(ptr, -value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwAnd(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicAndLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwOr(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicOrLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwXor(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicXorLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwXchg(base: Int, value: Long, offset: Int, memory: Memory): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicXchgLong(ptr, value)
    }

    @JvmStatic
    fun memoryAtomicLongRmwCmpxchg(
        base: Int,
        expected: Long,
        replacement: Long,
        offset: Int,
        memory: Memory,
    ): Long {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicCmpxchgLong(ptr, expected, replacement)
    }

    // Wait/Notify
    @JvmStatic
    fun memoryAtomicWait32(
        base: Int,
        expected: Int,
        timeout: Long,
        offset: Int,
        memory: Memory,
    ): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 4 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicWait(ptr, expected, timeout)
    }

    @JvmStatic
    fun memoryAtomicWait64(
        base: Int,
        expected: Long,
        timeout: Long,
        offset: Int,
        memory: Memory,
    ): Int {
        val ptr = getAddr(base, offset)
        if (ptr % 8 != 0) {
            throw InvalidException("unaligned atomic")
        }
        return memory.atomicWait(ptr, expected, timeout)
    }

    @JvmStatic
    fun memoryAtomicNotify(base: Int, count: Int, offset: Int, memory: Memory): Int {
        val ptr = getAddr(base, offset)
        return memory.atomicNotify(ptr, count)
    }

    @JvmStatic
    fun memoryAtomicFence(memory: Memory) {
        memory.atomicFence()
    }

    // ========= GC Operations =========

    @JvmStatic
    fun structNew(fields: LongArray, typeIdx: Int, instance: Instance): Int {
        val struct = WasmStruct(typeIdx, fields)
        return instance.registerGcRef(struct)
    }

    @JvmStatic
    fun structNewDefault(typeIdx: Int, instance: Instance): Int {
        val st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        val fields = LongArray(st.fieldTypes().size)
        for (i in 0 until fields.size) {
            val ft = st.fieldTypes()[i]
            if (ft.storageType().valType() != null && ft.storageType().valType()!!.isReference()) {
                fields[i] = Value.REF_NULL_VALUE.toLong()
            }
        }
        val struct = WasmStruct(typeIdx, fields)
        return instance.registerGcRef(struct)
    }

    @JvmStatic
    fun structGet(ref: Int, typeIdx: Int, fieldIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        val struct = instance.gcRef(ref) as WasmStruct
        return struct.field(fieldIdx)
    }

    @JvmStatic
    fun structGetS(ref: Int, typeIdx: Int, fieldIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        val struct = instance.gcRef(ref) as WasmStruct
        var result = struct.field(fieldIdx)
        val st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        val ft = st.fieldTypes()[fieldIdx]
        if (ft.storageType().packedType() != null) {
            result = ft.storageType().packedType()!!.signExtend(result)
        }
        return result
    }

    @JvmStatic
    fun structGetU(ref: Int, typeIdx: Int, fieldIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        val struct = instance.gcRef(ref) as WasmStruct
        var result = struct.field(fieldIdx)
        val st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        val ft = st.fieldTypes()[fieldIdx]
        if (ft.storageType().packedType() != null) {
            result = result and ft.storageType().packedType()!!.mask()
        }
        return result
    }

    @JvmStatic
    fun structSet(ref: Int, value: Long, typeIdx: Int, fieldIdx: Int, instance: Instance) {
        var fieldValue = value
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null structure reference")
        }
        val struct = instance.gcRef(ref) as WasmStruct
        val st = instance.module().typeSection().getSubType(typeIdx).compType().structType()!!
        val ft = st.fieldTypes()[fieldIdx]
        if (ft.storageType().packedType() != null) {
            fieldValue = fieldValue and ft.storageType().packedType()!!.mask()
        }
        struct.setField(fieldIdx, fieldValue)
    }

    @JvmStatic
    fun arrayNew(initVal: Long, len: Int, typeIdx: Int, instance: Instance): Int {
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            val arr =
                when (packedType.ID()) {
                    0x78 -> WasmArray(typeIdx, ByteArray(len) { initVal.toByte() })
                    0x77 -> WasmArray(typeIdx, ShortArray(len) { initVal.toShort() })
                    else -> error("Unsupported packed array type")
                }
            return instance.registerGcRef(arr)
        }
        val elems = LongArray(len)
        java.util.Arrays.fill(elems, initVal)
        val arr = WasmArray(typeIdx, elems)
        return instance.registerGcRef(arr)
    }

    @JvmStatic
    fun arrayNewDefault(len: Int, typeIdx: Int, instance: Instance): Int {
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            val arr =
                when (packedType.ID()) {
                    0x78 -> WasmArray(typeIdx, ByteArray(len))
                    0x77 -> WasmArray(typeIdx, ShortArray(len))
                    else -> error("Unsupported packed array type")
                }
            return instance.registerGcRef(arr)
        }
        val elems = LongArray(len)
        if (
            at.fieldType().storageType().valType() != null &&
                at.fieldType().storageType().valType()!!.isReference()
        ) {
            java.util.Arrays.fill(elems, Value.REF_NULL_VALUE.toLong())
        }
        val arr = WasmArray(typeIdx, elems)
        return instance.registerGcRef(arr)
    }

    @JvmStatic
    fun arrayNewFixed(vals: LongArray, typeIdx: Int, instance: Instance): Int {
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            val arr =
                when (packedType.ID()) {
                    0x78 -> WasmArray(typeIdx, ByteArray(vals.size) { vals[it].toByte() })
                    0x77 -> WasmArray(typeIdx, ShortArray(vals.size) { vals[it].toShort() })
                    else -> error("Unsupported packed array type")
                }
            return instance.registerGcRef(arr)
        }
        val arr = WasmArray(typeIdx, vals)
        return instance.registerGcRef(arr)
    }

    @JvmStatic
    fun arrayNewData(offset: Int, len: Int, typeIdx: Int, dataIdx: Int, instance: Instance): Int {
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val elemSize = at.fieldType().storageType().byteSize()
        val data = instance.dataSegmentData(dataIdx)
        if (offset.toLong() + len.toLong() * elemSize > data.size) {
            throw TrapException("out of bounds memory access")
        }
        val elems = LongArray(len)
        val packedType = at.fieldType().storageType().packedType()
        if (packedType != null) {
            val arr =
                when (packedType.ID()) {
                    0x78 -> {
                        val bytes = ByteArray(len)
                        for (i in 0 until len) {
                            bytes[i] = data[offset + i].toByte()
                        }
                        WasmArray(typeIdx, bytes)
                    }
                    0x77 -> {
                        val shorts = ShortArray(len)
                        for (i in 0 until len) {
                            val byteOff = offset + i * elemSize
                            shorts[i] = readFromData(data, byteOff, elemSize).toShort()
                        }
                        WasmArray(typeIdx, shorts)
                    }
                    else -> error("Unsupported packed array type")
                }
            return instance.registerGcRef(arr)
        }
        for (i in 0 until len) {
            val byteOff = offset + i * elemSize
            elems[i] = readFromData(data, byteOff, elemSize)
        }
        val arr = WasmArray(typeIdx, elems)
        return instance.registerGcRef(arr)
    }

    @JvmStatic
    fun arrayNewElem(offset: Int, len: Int, typeIdx: Int, elemIdx: Int, instance: Instance): Int {
        val element = instance.elementOrNull(elemIdx)
        if (element == null || offset + len > element.elementCount()) {
            throw TrapException("out of bounds table access")
        }
        val elems = LongArray(len)
        for (i in 0 until len) {
            elems[i] =
                elementValueToRef(computeElementValue(instance, elemIdx, offset + i), instance)
        }
        val arr = WasmArray(typeIdx, elems)
        return instance.registerGcRef(arr)
    }

    @JvmStatic
    fun arrayGet(ref: Int, idx: Int, typeIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        return arr.get(idx)
    }

    @JvmStatic
    fun arrayGetS(ref: Int, idx: Int, typeIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        var result = arr.get(idx)
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().packedType() != null) {
            result = at.fieldType().storageType().packedType()!!.signExtend(result)
        }
        return result
    }

    @JvmStatic
    fun arrayGetU(ref: Int, idx: Int, typeIdx: Int, instance: Instance): Long {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        var result = arr.get(idx)
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().packedType() != null) {
            result = result and at.fieldType().storageType().packedType()!!.mask()
        }
        return result
    }

    @JvmStatic
    fun arraySet(ref: Int, idx: Int, value: Long, typeIdx: Int, instance: Instance) {
        var elementValue = value
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        if (idx < 0 || idx >= arr.length()) {
            throw TrapException("out of bounds array access")
        }
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().packedType() != null) {
            elementValue = elementValue and at.fieldType().storageType().packedType()!!.mask()
        }
        arr.set(idx, elementValue)
    }

    @JvmStatic
    fun arrayLen(ref: Int, instance: Instance): Int {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        return arr.length()
    }

    @JvmStatic
    fun arrayFill(ref: Int, offset: Int, value: Long, len: Int, typeIdx: Int, instance: Instance) {
        var elementValue = value
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        if (offset + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().packedType() != null) {
            elementValue = elementValue and at.fieldType().storageType().packedType()!!.mask()
        }
        for (i in 0 until len) {
            arr.set(offset + i, elementValue)
        }
    }

    @JvmStatic
    fun arrayCopy(
        dstRef: Int,
        dstOff: Int,
        srcRef: Int,
        srcOff: Int,
        len: Int,
        instance: Instance,
    ) {
        if (dstRef == Value.REF_NULL_VALUE || srcRef == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val dst = instance.gcRef(dstRef) as WasmArray
        val src = instance.gcRef(srcRef) as WasmArray
        if (dstOff + len > dst.length() || srcOff + len > src.length()) {
            throw TrapException("out of bounds array access")
        }
        if (dstOff <= srcOff) {
            for (i in 0 until len) {
                dst.set(dstOff + i, src.get(srcOff + i))
            }
        } else {
            for (i in len - 1 downTo 0) {
                dst.set(dstOff + i, src.get(srcOff + i))
            }
        }
    }

    @JvmStatic
    fun arrayInitData(
        ref: Int,
        dstOff: Int,
        srcOff: Int,
        len: Int,
        typeIdx: Int,
        dataIdx: Int,
        instance: Instance,
    ) {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        val at = instance.module().typeSection().getSubType(typeIdx).compType().arrayType()!!
        val elemSize = at.fieldType().storageType().byteSize()
        val data = instance.dataSegmentData(dataIdx)
        if (dstOff + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        if (srcOff.toLong() + len.toLong() * elemSize > data.size) {
            throw TrapException("out of bounds memory access")
        }
        for (i in 0 until len) {
            val byteOff = srcOff + i * elemSize
            arr.set(dstOff + i, readFromData(data, byteOff, elemSize))
        }
    }

    @JvmStatic
    fun arrayInitElem(
        ref: Int,
        dstOff: Int,
        srcOff: Int,
        len: Int,
        typeIdx: Int,
        elemIdx: Int,
        instance: Instance,
    ) {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null array reference")
        }
        val arr = instance.gcRef(ref) as WasmArray
        val element = instance.elementOrNull(elemIdx)
        if (dstOff + len > arr.length()) {
            throw TrapException("out of bounds array access")
        }
        val elementCount = element?.elementCount() ?: 0
        if (srcOff + len > elementCount) {
            throw TrapException("out of bounds table access")
        }
        if (len == 0) {
            return
        }
        for (i in 0 until len) {
            arr.set(
                dstOff + i,
                elementValueToRef(computeElementValue(instance, elemIdx, srcOff + i), instance),
            )
        }
    }

    @JvmStatic
    fun refTest(ref: Int, heapType: Int, srcHeapType: Int, instance: Instance): Int {
        return if (instance.heapTypeMatch(ref.toLong(), false, heapType, srcHeapType)) 1 else 0
    }

    @JvmStatic
    fun refTestNull(ref: Int, heapType: Int, srcHeapType: Int, instance: Instance): Int {
        return if (instance.heapTypeMatch(ref.toLong(), true, heapType, srcHeapType)) 1 else 0
    }

    @JvmStatic
    fun castTest(ref: Int, heapType: Int, srcHeapType: Int, instance: Instance): Int {
        if (!instance.heapTypeMatch(ref.toLong(), false, heapType, srcHeapType)) {
            throw TrapException("cast failure")
        }
        return ref
    }

    @JvmStatic
    fun castTestNull(ref: Int, heapType: Int, srcHeapType: Int, instance: Instance): Int {
        if (!instance.heapTypeMatch(ref.toLong(), true, heapType, srcHeapType)) {
            throw TrapException("cast failure")
        }
        return ref
    }

    @JvmStatic
    fun heapTypeMatch(
        ref: Int,
        nullable: Boolean,
        heapType: Int,
        srcHeapType: Int,
        instance: Instance,
    ): Boolean {
        return instance.heapTypeMatch(ref.toLong(), nullable, heapType, srcHeapType)
    }

    @JvmStatic
    fun refI31(value: Int, instance: Instance): Int {
        val i31 = WasmI31Ref(value and 0x7FFFFFFF)
        return instance.registerGcRef(i31)
    }

    @JvmStatic
    fun i31GetS(ref: Int, instance: Instance): Int {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null i31 reference")
        }
        val i31 = instance.gcRef(ref) as WasmI31Ref
        val value = i31.value()
        // sign extend from 31 bits
        return (value shl 1) shr 1
    }

    @JvmStatic
    fun i31GetU(ref: Int, instance: Instance): Int {
        if (ref == Value.REF_NULL_VALUE) {
            throw TrapException("null i31 reference")
        }
        val i31 = instance.gcRef(ref) as WasmI31Ref
        return i31.value() and 0x7FFFFFFF
    }

    @JvmStatic
    fun refEq(a: Int, b: Int, instance: Instance): Int {
        if (a == b) {
            return 1
        }
        if (a == Value.REF_NULL_VALUE || b == Value.REF_NULL_VALUE) {
            return 0
        }
        val gcA = instance.gcRef(a)
        val gcB = instance.gcRef(b)
        if (gcA is WasmI31Ref && gcB is WasmI31Ref) {
            return if (gcA.value() == gcB.value()) 1 else 0
        }
        return 0
    }

    private fun elementValueToRef(value: Long, instance: Instance): Long {
        if (Value.isI31(value)) {
            val i31 = WasmI31Ref(Value.decodeI31U(value))
            return instance.registerGcRef(i31).toLong()
        }
        return value
    }

    private fun computeElementValue(instance: Instance, elemIdx: Int, offset: Int): Long {
        val element = instance.element(elemIdx)
        val init = element.initializers().get(offset)
        return ConstantEvaluators.computeConstantValue(instance, init)[0]
    }

    private fun readFromData(data: ByteArray, offset: Int, size: Int): Long {
        var result = 0L
        for (i in 0 until size) {
            result = result or ((data[offset + i].toInt() and 0xFF).toLong() shl (i * 8))
        }
        return result
    }

    @JvmStatic
    fun dataDrop(segment: Int, instance: Instance) {
        instance.dropDataSegment(segment)
    }
}
