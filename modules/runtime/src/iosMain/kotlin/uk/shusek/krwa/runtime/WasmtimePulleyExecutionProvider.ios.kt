@file:Suppress("MagicNumber", "ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")
@file:OptIn(ExperimentalForeignApi::class)

package uk.shusek.krwa.runtime

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.ExecutionBackendAvailability
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.ImportFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.runtime.PlatformInstanceExecution
import uk.shusek.krwa.runtime.PulleyExecutionProvider
import uk.shusek.krwa.runtime.PulleyExecutionProviders
import uk.shusek.krwa.runtime.TrapException
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.runtime.WasmRuntimeException
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_bind_function
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_bind_memory
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_call
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_create
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_destroy
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_last_error
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_byte_size
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_fill
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_grow
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_f32
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_f64
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_i16
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_i32
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_i64
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_read_u8
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_f32
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_f64
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_i16
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_i32
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_i64
import uk.shusek.krwa.runtime.wasmtimepulley.krwa_pulley_memory_write_u8

actual fun installWasmtimePulleyExecutionProviderIfAvailable() {
    PulleyExecutionProviders.install(IosPulleyExecutionProvider)
}

actual fun wasmtimeTargetUnavailableReason(target: String): String? = when (target) {
    WasmtimePulleyTarget -> iosWasmtimePulleyUnavailableReason()
    WasmtimeNativeTarget -> "Wasmtime native AOT target $target is not supported on iOS"
    else -> "Wasmtime target $target is not supported on iOS"
}

private object IosPulleyExecutionProvider : PulleyExecutionProvider {
    override fun availability(): ExecutionBackendAvailability =
        wasmtimeTargetUnavailableReason(WasmtimePulleyTarget)?.let { reason ->
            ExecutionBackendAvailability(available = false, reason = reason)
        } ?: ExecutionBackendAvailability(available = true)

    override fun create(module: WasmModule, imports: ImportValues, hostInstance: Instance): PlatformInstanceExecution {
        val config = hostInstance.wasmtimeExecutionConfig() ?: WasmtimeExecutionConfig()
        wasmtimeTargetUnavailableReason(config.target)?.let { reason ->
            throw WasmEngineException(reason)
        }
        val originalBytes =
            module.originalBytes()
                ?: throw WasmEngineException("Wasmtime Pulley execution needs original module bytes")
        val pulleyBytes = moduleBytesWithSyntheticMemoryExports(originalBytes, module)
        val precompiledPulleyBytes = if (pulleyBytes.syntheticMemoryExports.isEmpty()) {
            readPrecompiledModuleBytes(config)
        } else {
            null
        }
        val importSpecs =
            buildPulleyImportSpecs(
                module = module,
                imports = imports,
                hostInstance = hostInstance,
                register = IosHostCallbacks::register,
            )
        val callbackIds = importSpecs.map(PulleyImportSpec::callbackId).toLongArray()
        val params = flattenOpcodes(importSpecs.map(PulleyImportSpec::paramOpcodes))
        val returns = flattenOpcodes(importSpecs.map(PulleyImportSpec::returnOpcodes))

        var nativeHandle = 0L
        try {
            nativeHandle =
                createNativePulleyExecution(
                    moduleBytes = precompiledPulleyBytes ?: pulleyBytes.bytes,
                    precompiledModule = precompiledPulleyBytes != null,
                    maxMemoryBytes = config.maxMemoryBytes,
                    maxWasmStackBytes = config.maxWasmStackBytes,
                    maxTableElements = config.maxTableElements,
                    maxInstances = config.maxInstances,
                    maxTables = config.maxTables,
                    maxMemories = config.maxMemories,
                    maxFuel = config.maxFuel,
                    callbackIds = callbackIds,
                    params = params,
                    returns = returns,
                )
            return IosWasmtimePulleyExecution(
                nativeHandle = nativeHandle,
                functionExports = exportedPulleyFunctions(module),
                module = module,
                syntheticMemoryExports = pulleyBytes.syntheticMemoryExports,
                callbackIds = callbackIds,
            ).also(IosWasmtimePulleyExecution::bindExports)
        } catch (failure: Throwable) {
            if (nativeHandle != 0L) {
                krwa_pulley_destroy(nativeHandle)
            }
            IosHostCallbacks.unregister(callbackIds)
            throw failure
        }
    }
}

private class IosWasmtimePulleyExecution(
    private val nativeHandle: Long,
    functionExports: Map<String, PulleyFunctionExport>,
    private val module: WasmModule,
    private val syntheticMemoryExports: Map<Int, String>,
    private val callbackIds: LongArray,
) : PlatformInstanceExecution {
    private val handleGuard = IosNativeHandleGuard(nativeHandle, callbackIds)
    private val functionsByName = functionExports.toMutableMap()
    private val nativeFunctionsByName = HashMap<String, Long>()
    private val memoriesByName = HashMap<String, Memory>()
    private val memoriesByIndex = ArrayList<Memory?>()

    override val backend: ExecutionBackend = ExecutionBackend.PULLEY

    fun bindExports() {
        for (export in functionsByName.values) {
            nativeFunctionsByName[export.name] = bindFunction(nativeHandle, export.name)
        }
        bindExportedMemories()
    }

    override fun export(name: String): ExportFunction {
        val export = functionsByName[name] ?: throw InvalidException("Unknown export with name $name")
        val nativeFunction = nativeFunctionsByName[name] ?: throw InvalidException("Unknown export with name $name")
        return ExportFunction { args ->
            callNativePulleyFunction(
                nativeHandle = nativeHandle,
                nativeFunction = nativeFunction,
                paramOpcodes = export.type.params().toPulleyOpcodes(),
                returnOpcodes = export.type.returns().toPulleyOpcodes(),
                args = args,
            )
        }
    }

    override fun exportType(name: String): FunctionType {
        val export = functionsByName[name] ?: throw InvalidException("Unknown export with name $name")
        return export.type
    }

    override fun memory(name: String): Memory =
        memoriesByName[name] ?: throw InvalidException("Unknown export with name $name")

    override fun memory(index: Int): Memory? =
        if (index in 0 until memoriesByIndex.size) memoriesByIndex[index] else null

    override fun close() {
        handleGuard.close()
    }

    private fun bindExportedMemories() {
        for (i in 0 until module.exportSection().exportCount()) {
            val export = module.exportSection().getExport(i)
            if (export.exportType() == ExternalType.MEMORY) {
                bindMemoryExport(export.name(), export.index(), exposeName = true)
            }
        }
        for ((index, name) in syntheticMemoryExports) {
            if (index in 0 until memoriesByIndex.size && memoriesByIndex[index] != null) {
                continue
            }
            bindMemoryExport(name, index, exposeName = false)
        }
    }

    private fun bindMemoryExport(name: String, index: Int, exposeName: Boolean) {
        val nativeMemory = bindMemory(nativeHandle, name)
        if (nativeMemory == 0L) {
            return
        }
        val memory =
            IosWasmtimeMemory(
                nativeHandle = nativeHandle,
                nativeMemory = nativeMemory,
                initialPages = exportedPulleyMemoryInitialPages(module, index),
                handleGuard = handleGuard,
            )
        if (exposeName) {
            memoriesByName[name] = memory
        }
        while (memoriesByIndex.size <= index) {
            memoriesByIndex.add(null)
        }
        memoriesByIndex[index] = memory
    }
}

private data class IosHostCallback(
    val function: ImportFunction,
    val handle: WasmFunctionHandle,
    val type: FunctionType,
    val hostInstance: Instance,
)

private object IosHostCallbacks {
    private var nextId = 0L
    private val callbacks = HashMap<Long, IosHostCallback>()

    fun register(function: ImportFunction, type: FunctionType, hostInstance: Instance): Long {
        val handle =
            function.handle()
                ?: throw uk.shusek.krwa.wasm.UnlinkableException(
                    "native WebAssembly import ${function.module()}.${function.name()} has no function handle",
                )
        val id = ++nextId
        callbacks[id] = IosHostCallback(function, handle, type, hostInstance)
        return id
    }

    fun unregister(ids: LongArray) {
        for (id in ids) {
            callbacks.remove(id)
        }
    }

    fun invoke(id: Long, args: LongArray, results: LongArray) {
        val callback = callbacks[id] ?: throw TrapException("unknown host callback $id")
        val actualResults = callback.handle.apply(callback.hostInstance, args) ?: LongArray(0)
        if (actualResults.size != callback.type.returns().size) {
            throw TrapException(
                "host function ${callback.function.module()}.${callback.function.name()} " +
                    "returned ${actualResults.size} values, expected ${callback.type.returns().size}",
            )
        }
        actualResults.copyInto(results)
    }
}

private class IosNativeHandleGuard(
    private val nativeHandle: Long,
    private val callbackIds: LongArray,
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        krwa_pulley_destroy(nativeHandle)
        IosHostCallbacks.unregister(callbackIds)
    }

    @Suppress("deprecation")
    protected fun finalize() {
        close()
    }
}

private class IosWasmtimeMemory(
    private val nativeHandle: Long,
    private val nativeMemory: Long,
    private val initialPages: Int,
    @Suppress("unused") private val handleGuard: IosNativeHandleGuard,
) : Memory {
    override fun pages(): Int =
        (krwa_pulley_memory_byte_size(nativeHandle, nativeMemory) / Memory.PAGE_SIZE.convert<ULong>()).toInt()

    override fun grow(size: Int): Int = memScoped {
        val previous = allocArray<IntVar>(1)
        val status = krwa_pulley_memory_grow(nativeHandle, nativeMemory, size, previous)
        if (status != 0) {
            -1
        } else {
            previous[0]
        }
    }

    override fun initialPages(): Int = initialPages

    override fun maximumPages(): Int = Memory.RUNTIME_MAX_PAGES

    override fun shared(): Boolean = false

    @Deprecated("for removal")
    override fun lock(address: Int): Any = this

    @Deprecated("for removal")
    override fun waitOn(address: Int, expected: Int, timeout: Long): Int =
        throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")

    @Deprecated("for removal")
    override fun waitOn(address: Int, expected: Long, timeout: Long): Int =
        throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")

    @Deprecated("for removal")
    override fun notify(address: Int, maxThreads: Int): Int = 0

    override fun atomicReadInt(addr: Int): Int = readInt(addr)

    override fun atomicReadLong(addr: Int): Long = readLong(addr)

    override fun atomicReadShort(addr: Int): Short = readShort(addr)

    override fun atomicReadByte(addr: Int): Byte = read(addr)

    override fun atomicWriteInt(addr: Int, value: Int) = writeI32(addr, value)

    override fun atomicWriteLong(addr: Int, value: Long) = writeLong(addr, value)

    override fun atomicWriteShort(addr: Int, value: Short) = writeShort(addr, value)

    override fun atomicWriteByte(addr: Int, value: Byte) = writeByte(addr, value)

    override fun atomicAddInt(addr: Int, delta: Int): Int {
        val previous = readInt(addr)
        writeI32(addr, previous + delta)
        return previous
    }

    override fun atomicAndInt(addr: Int, mask: Int): Int {
        val previous = readInt(addr)
        writeI32(addr, previous and mask)
        return previous
    }

    override fun atomicOrInt(addr: Int, mask: Int): Int {
        val previous = readInt(addr)
        writeI32(addr, previous or mask)
        return previous
    }

    override fun atomicXorInt(addr: Int, mask: Int): Int {
        val previous = readInt(addr)
        writeI32(addr, previous xor mask)
        return previous
    }

    override fun atomicXchgInt(addr: Int, value: Int): Int {
        val previous = readInt(addr)
        writeI32(addr, value)
        return previous
    }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int {
        val previous = readInt(addr)
        if (previous == expected) {
            writeI32(addr, replacement)
        }
        return previous
    }

    override fun atomicAddLong(addr: Int, delta: Long): Long {
        val previous = readLong(addr)
        writeLong(addr, previous + delta)
        return previous
    }

    override fun atomicAndLong(addr: Int, mask: Long): Long {
        val previous = readLong(addr)
        writeLong(addr, previous and mask)
        return previous
    }

    override fun atomicOrLong(addr: Int, mask: Long): Long {
        val previous = readLong(addr)
        writeLong(addr, previous or mask)
        return previous
    }

    override fun atomicXorLong(addr: Int, mask: Long): Long {
        val previous = readLong(addr)
        writeLong(addr, previous xor mask)
        return previous
    }

    override fun atomicXchgLong(addr: Int, value: Long): Long {
        val previous = readLong(addr)
        writeLong(addr, value)
        return previous
    }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long {
        val previous = readLong(addr)
        if (previous == expected) {
            writeLong(addr, replacement)
        }
        return previous
    }

    override fun atomicAddShort(addr: Int, delta: Short): Short {
        val previous = readShort(addr)
        writeShort(addr, (previous + delta).toShort())
        return previous
    }

    override fun atomicAndShort(addr: Int, mask: Short): Short {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() and mask.toInt()).toShort())
        return previous
    }

    override fun atomicOrShort(addr: Int, mask: Short): Short {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() or mask.toInt()).toShort())
        return previous
    }

    override fun atomicXorShort(addr: Int, mask: Short): Short {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() xor mask.toInt()).toShort())
        return previous
    }

    override fun atomicXchgShort(addr: Int, value: Short): Short {
        val previous = readShort(addr)
        writeShort(addr, value)
        return previous
    }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short {
        val previous = readShort(addr)
        if (previous == expected) {
            writeShort(addr, replacement)
        }
        return previous
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte {
        val previous = read(addr)
        writeByte(addr, (previous + delta).toByte())
        return previous
    }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() and mask.toInt()).toByte())
        return previous
    }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() or mask.toInt()).toByte())
        return previous
    }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() xor mask.toInt()).toByte())
        return previous
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte {
        val previous = read(addr)
        writeByte(addr, value)
        return previous
    }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte {
        val previous = read(addr)
        if (previous == expected) {
            writeByte(addr, replacement)
        }
        return previous
    }

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?) =
        throw WasmEngineException("Wasmtime owns native memory initialization")

    override fun initPassiveSegment(
        segmentId: Int,
        dest: Int,
        offset: Int,
        size: Int,
    ) = throw WasmEngineException("Wasmtime owns passive data segments")

    override fun write(
        addr: Int,
        data: ByteArray,
        offset: Int,
        size: Int,
    ) {
        if (size == 0) return
        data.usePinned { pinned ->
            checkMemoryStatus(
                krwa_pulley_memory_write(
                    nativeHandle,
                    nativeMemory,
                    addr,
                    pinned.addressOf(offset).reinterpret(),
                    size.convert(),
                ),
            )
        }
    }

    override fun read(addr: Int): Byte = memScoped {
        val result = allocArray<UByteVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_u8(nativeHandle, nativeMemory, addr, result))
        result[0].toByte()
    }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        val result = ByteArray(len)
        read(addr, result, 0, len)
        return result
    }

    override fun read(
        addr: Int,
        target: ByteArray,
        offset: Int,
        size: Int,
    ) {
        if (size == 0) return
        target.usePinned { pinned ->
            checkMemoryStatus(
                krwa_pulley_memory_read(
                    nativeHandle,
                    nativeMemory,
                    addr,
                    pinned.addressOf(offset).reinterpret(),
                    size.convert(),
                ),
            )
        }
    }

    override fun writeI32(addr: Int, data: Int) =
        checkMemoryStatus(krwa_pulley_memory_write_i32(nativeHandle, nativeMemory, addr, data))

    override fun readInt(addr: Int): Int = memScoped {
        val result = allocArray<IntVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_i32(nativeHandle, nativeMemory, addr, result))
        result[0]
    }

    override fun writeLong(addr: Int, data: Long) =
        checkMemoryStatus(krwa_pulley_memory_write_i64(nativeHandle, nativeMemory, addr, data))

    override fun readLong(addr: Int): Long = memScoped {
        val result = allocArray<LongVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_i64(nativeHandle, nativeMemory, addr, result))
        result[0]
    }

    override fun writeShort(addr: Int, data: Short) =
        checkMemoryStatus(krwa_pulley_memory_write_i16(nativeHandle, nativeMemory, addr, data))

    override fun readShort(addr: Int): Short = memScoped {
        val result = allocArray<ShortVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_i16(nativeHandle, nativeMemory, addr, result))
        result[0]
    }

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xffffL

    override fun writeByte(addr: Int, data: Byte) =
        checkMemoryStatus(krwa_pulley_memory_write_u8(nativeHandle, nativeMemory, addr, data.toUByte()))

    override fun writeF32(addr: Int, data: Float) =
        checkMemoryStatus(krwa_pulley_memory_write_f32(nativeHandle, nativeMemory, addr, data))

    override fun readF32(addr: Int): Long = readFloat(addr).toRawBits().toLong()

    override fun readFloat(addr: Int): Float = memScoped {
        val result = allocArray<FloatVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_f32(nativeHandle, nativeMemory, addr, result))
        result[0]
    }

    override fun writeF64(addr: Int, data: Double) =
        checkMemoryStatus(krwa_pulley_memory_write_f64(nativeHandle, nativeMemory, addr, data))

    override fun readDouble(addr: Int): Double = memScoped {
        val result = allocArray<DoubleVar>(1)
        checkMemoryStatus(krwa_pulley_memory_read_f64(nativeHandle, nativeMemory, addr, result))
        result[0]
    }

    override fun readF64(addr: Int): Long = readDouble(addr).toRawBits()

    override fun zero() = fill(0, 0, krwa_pulley_memory_byte_size(nativeHandle, nativeMemory).toInt())

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) =
        checkMemoryStatus(krwa_pulley_memory_fill(nativeHandle, nativeMemory, value.toUByte(), fromIndex, toIndex))

    override fun drop(segment: Int) = throw WasmEngineException("Wasmtime owns passive data segments")
}

private data class FlattenedOpcodes(val offsets: IntArray, val opcodes: IntArray)

private fun flattenOpcodes(items: List<IntArray>): FlattenedOpcodes {
    val offsets = IntArray(items.size + 1)
    var total = 0
    for (i in items.indices) {
        offsets[i] = total
        total += items[i].size
    }
    offsets[items.size] = total
    val opcodes = IntArray(total)
    var offset = 0
    for (item in items) {
        item.copyInto(opcodes, destinationOffset = offset)
        offset += item.size
    }
    return FlattenedOpcodes(offsets, opcodes)
}

private fun createNativePulleyExecution(
    moduleBytes: ByteArray,
    precompiledModule: Boolean,
    maxMemoryBytes: Long,
    maxWasmStackBytes: Long,
    maxTableElements: Long,
    maxInstances: Long,
    maxTables: Long,
    maxMemories: Long,
    maxFuel: Long,
    callbackIds: LongArray,
    params: FlattenedOpcodes,
    returns: FlattenedOpcodes,
): Long = memScoped {
    val callbackIdsPointer = allocLongArray(callbackIds)
    val paramOffsetsPointer = allocIntArray(params.offsets)
    val paramOpcodesPointer = allocIntArray(params.opcodes)
    val returnOffsetsPointer = allocIntArray(returns.offsets)
    val returnOpcodesPointer = allocIntArray(returns.opcodes)
    moduleBytes.usePinned { modulePinned ->
        val handle =
            krwa_pulley_create(
                modulePinned.addressOf(0).reinterpret(),
                moduleBytes.size.convert(),
                if (precompiledModule) 1 else 0,
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel,
                callbackIdsPointer,
                callbackIds.size.convert(),
                paramOffsetsPointer,
                paramOpcodesPointer,
                returnOffsetsPointer,
                returnOpcodesPointer,
                staticCFunction(::invokeIosHostFunction),
            )
        if (handle == 0L) {
            throw WasmEngineException(lastPulleyError())
        }
        handle
    }
}

private fun readPrecompiledModuleBytes(config: WasmtimeExecutionConfig): ByteArray? {
    return config.precompiledModuleBytes
}

private fun callNativePulleyFunction(
    nativeHandle: Long,
    nativeFunction: Long,
    paramOpcodes: IntArray,
    returnOpcodes: IntArray,
    args: LongArray,
): LongArray = memScoped {
    val paramOpcodesPointer = allocIntArray(paramOpcodes)
    val returnOpcodesPointer = allocIntArray(returnOpcodes)
    val argsPointer = allocLongArray(args)
    val results = LongArray(returnOpcodes.size)
    val resultsPointer = allocLongArray(results)
    val status =
        krwa_pulley_call(
            nativeHandle,
            nativeFunction,
            paramOpcodesPointer,
            paramOpcodes.size.convert(),
            returnOpcodesPointer,
            returnOpcodes.size.convert(),
            argsPointer,
            args.size.convert(),
            resultsPointer,
            results.size.convert(),
        )
    if (status != 0) {
        throw WasmEngineException(lastPulleyError())
    }
    if (resultsPointer != null) {
        for (i in results.indices) {
            results[i] = resultsPointer[i]
        }
    }
    results
}

private fun bindFunction(nativeHandle: Long, name: String): Long {
    val nativeFunction = krwa_pulley_bind_function(nativeHandle, name, name.encodeToByteArray().size.convert())
    if (nativeFunction == 0L) {
        throw WasmEngineException(lastPulleyError())
    }
    return nativeFunction
}

private fun bindMemory(nativeHandle: Long, name: String): Long =
    krwa_pulley_bind_memory(nativeHandle, name, name.encodeToByteArray().size.convert())

private fun invokeIosHostFunction(
    callbackId: Long,
    args: CPointer<LongVar>?,
    argCount: ULong,
    results: CPointer<LongVar>?,
    resultCount: ULong,
): Int = try {
    val arguments = LongArray(argCount.toInt()) { i -> args?.get(i) ?: 0L }
    val resultValues = LongArray(resultCount.toInt())
    IosHostCallbacks.invoke(callbackId, arguments, resultValues)
    for (i in resultValues.indices) {
        results?.set(i, resultValues[i])
    }
    0
} catch (_: Throwable) {
    -1
}

private fun MemScope.allocIntArray(values: IntArray): CPointer<IntVar>? {
    if (values.isEmpty()) return null
    val pointer = allocArray<IntVar>(values.size)
    for (i in values.indices) {
        pointer[i] = values[i]
    }
    return pointer
}

private fun MemScope.allocLongArray(values: LongArray): CPointer<LongVar>? {
    if (values.isEmpty()) return null
    val pointer = allocArray<LongVar>(values.size)
    for (i in values.indices) {
        pointer[i] = values[i]
    }
    return pointer
}

private inline fun <T> ByteArray.usePinned(block: (Pinned<ByteArray>) -> T): T {
    val pinned = pin()
    try {
        return block(pinned)
    } finally {
        pinned.unpin()
    }
}

private fun checkMemoryStatus(status: Int) {
    if (status != 0) {
        throw WasmRuntimeException(lastPulleyError())
    }
}

private fun lastPulleyError(): String = krwa_pulley_last_error()?.toKString() ?: "Wasmtime Pulley bridge failed"
