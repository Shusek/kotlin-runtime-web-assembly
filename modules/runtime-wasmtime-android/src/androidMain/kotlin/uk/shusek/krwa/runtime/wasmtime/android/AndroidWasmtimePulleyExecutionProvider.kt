@file:Suppress("DEPRECATION", "MagicNumber", "ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")

package uk.shusek.krwa.runtime.wasmtime.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
import uk.shusek.krwa.runtime.WasmtimeExecutionConfig
import uk.shusek.krwa.runtime.WasmtimeNativeTarget
import uk.shusek.krwa.runtime.WasmtimePulleyTarget
import uk.shusek.krwa.runtime.wasmtimeExecutionConfigFor
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType

fun installAndroidWasmtimePulleyExecutionProviderIfAvailable() {
    PulleyExecutionProviders.install(AndroidPulleyExecutionProvider)
}

private object AndroidPulleyExecutionProvider : PulleyExecutionProvider {
    override fun availability(): ExecutionBackendAvailability =
        androidWasmtimeTargetUnavailableReason(WasmtimeNativeTarget)?.let { reason ->
            ExecutionBackendAvailability(available = false, reason = reason)
        } ?: ExecutionBackendAvailability(available = true)

    override fun create(module: WasmModule, imports: ImportValues, hostInstance: Instance): PlatformInstanceExecution {
        val config = wasmtimeExecutionConfigFor(module) ?: androidWasmtimePropertyConfig()
        val unavailableReason = androidWasmtimeTargetUnavailableReason(config.target)
        if (unavailableReason != null) {
            throw WasmEngineException(unavailableReason)
        }

        val originalBytes =
            module.originalBytes()
                ?: throw WasmEngineException("Wasmtime Pulley execution needs original module bytes")
        val pulleyBytes = moduleBytesWithSyntheticMemoryExports(originalBytes, module)
        val precompiledModuleBytes =
            if (pulleyBytes.syntheticMemoryExports.isEmpty()) {
                config.precompiledModuleBytes
            } else {
                null
            }
        val importSpecs = buildPulleyImportSpecs(module, imports, hostInstance, AndroidHostCallbacks::register)
        val callbackIds = importSpecs.map(PulleyImportSpec::callbackId).toLongArray()

        var nativeHandle = 0L
        try {
            nativeHandle =
                AndroidWasmtimePulleyNative.create(
                    precompiledModuleBytes ?: pulleyBytes.bytes,
                    precompiledModuleBytes != null,
                    config.target,
                    config.maxMemoryBytes,
                    config.maxWasmStackBytes,
                    config.maxTableElements,
                    config.maxInstances,
                    config.maxTables,
                    config.maxMemories,
                    config.maxFuel,
                    callbackIds,
                    importSpecs.map(PulleyImportSpec::paramOpcodes).toTypedArray(),
                    importSpecs.map(PulleyImportSpec::returnOpcodes).toTypedArray(),
                )
            return AndroidWasmtimePulleyExecution(
                nativeHandle = nativeHandle,
                functionExports = exportedFunctions(module),
                module = module,
                syntheticMemoryExports = pulleyBytes.syntheticMemoryExports,
                callbackIds = callbackIds,
            ).also(AndroidWasmtimePulleyExecution::bindExports)
        } catch (failure: Throwable) {
            if (nativeHandle != 0L) {
                AndroidWasmtimePulleyNative.destroy(nativeHandle)
            }
            AndroidHostCallbacks.unregister(callbackIds)
            throw failure
        }
    }
}

private fun androidWasmtimePropertyConfig(): WasmtimeExecutionConfig = WasmtimeExecutionConfig(
    target = System.getProperty(AndroidWasmtimeTargetProperty)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: WasmtimeNativeTarget,
)

private class AndroidWasmtimePulleyExecution(
    private val nativeHandle: Long,
    functionExports: Map<String, AndroidFunctionExport>,
    private val module: WasmModule,
    private val syntheticMemoryExports: Map<Int, String>,
    private val callbackIds: LongArray,
) : PlatformInstanceExecution {
    private val handleGuard = AndroidNativeHandleGuard(nativeHandle, callbackIds)
    private val functionsByName = functionExports.toMutableMap()
    private val memoriesByName = HashMap<String, Memory>()
    private val memoriesByIndex = ArrayList<Memory?>()

    override val backend: ExecutionBackend = ExecutionBackend.PULLEY

    fun bindExports() {
        for (export in functionsByName.values) {
            export.nativeFunction =
                AndroidWasmtimePulleyNative.bindFunction(nativeHandle, export.name)
        }
        bindExportedMemories()
    }

    override fun export(name: String): ExportFunction {
        val export = functionsByName[name] ?: throw InvalidException("Unknown export with name $name")
        return ExportFunction { args ->
            AndroidWasmtimePulleyNative.call(
                nativeHandle,
                export.nativeFunction,
                export.type.params().toPulleyOpcodes(),
                export.type.returns().toPulleyOpcodes(),
                args,
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
        val nativeMemory = AndroidWasmtimePulleyNative.bindMemory(nativeHandle, name)
        if (nativeMemory == 0L) {
            return
        }
        val memory =
            AndroidWasmtimeMemory(
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

private data class AndroidFunctionExport(
    val name: String,
    val index: Int,
    val type: FunctionType,
    var nativeFunction: Long = 0L,
)

private data class AndroidHostCallback(
    val function: ImportFunction,
    val handle: WasmFunctionHandle,
    val type: FunctionType,
    val hostInstance: Instance,
)

private object AndroidHostCallbacks {
    private val nextId = AtomicLong()
    private val callbacks = ConcurrentHashMap<Long, AndroidHostCallback>()

    fun register(function: ImportFunction, type: FunctionType, hostInstance: Instance): Long {
        val handle =
            function.handle()
                ?: throw UnlinkableException(
                    "native WebAssembly import ${function.module()}.${function.name()} has no function handle",
                )
        val id = nextId.incrementAndGet()
        callbacks[id] = AndroidHostCallback(function, handle, type, hostInstance)
        return id
    }

    fun unregister(ids: LongArray) {
        for (id in ids) {
            callbacks.remove(id)
        }
    }

    fun invoke(id: Long, args: LongArray): LongArray {
        val callback = callbacks[id] ?: throw TrapException("unknown host callback $id")
        val results = callback.handle.apply(callback.hostInstance, args) ?: LongArray(0)
        if (results.size != callback.type.returns().size) {
            throw TrapException(
                "host function ${callback.function.module()}.${callback.function.name()} " +
                    "returned ${results.size} values, expected ${callback.type.returns().size}",
            )
        }
        return results
    }
}

private class AndroidNativeHandleGuard(private val nativeHandle: Long, private val callbackIds: LongArray) {
    @Suppress("deprecation")
    protected fun finalize() {
        AndroidWasmtimePulleyNative.destroy(nativeHandle)
        AndroidHostCallbacks.unregister(callbackIds)
    }
}

private class AndroidWasmtimeMemory(
    private val nativeHandle: Long,
    private val nativeMemory: Long,
    private val initialPages: Int,
    @Suppress("unused") private val handleGuard: AndroidNativeHandleGuard,
) : Memory {
    override fun pages(): Int =
        (AndroidWasmtimePulleyNative.memoryByteSize(nativeHandle, nativeMemory) / Memory.PAGE_SIZE).toInt()

    override fun grow(size: Int): Int = AndroidWasmtimePulleyNative.memoryGrow(nativeHandle, nativeMemory, size)

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

    override fun atomicAddInt(addr: Int, delta: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        writeI32(addr, previous + delta)
        previous
    }

    override fun atomicAndInt(addr: Int, mask: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        writeI32(addr, previous and mask)
        previous
    }

    override fun atomicOrInt(addr: Int, mask: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        writeI32(addr, previous or mask)
        previous
    }

    override fun atomicXorInt(addr: Int, mask: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        writeI32(addr, previous xor mask)
        previous
    }

    override fun atomicXchgInt(addr: Int, value: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        writeI32(addr, value)
        previous
    }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int = synchronized(this) {
        val previous = readInt(addr)
        if (previous == expected) {
            writeI32(addr, replacement)
        }
        previous
    }

    override fun atomicAddLong(addr: Int, delta: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        writeLong(addr, previous + delta)
        previous
    }

    override fun atomicAndLong(addr: Int, mask: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        writeLong(addr, previous and mask)
        previous
    }

    override fun atomicOrLong(addr: Int, mask: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        writeLong(addr, previous or mask)
        previous
    }

    override fun atomicXorLong(addr: Int, mask: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        writeLong(addr, previous xor mask)
        previous
    }

    override fun atomicXchgLong(addr: Int, value: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        writeLong(addr, value)
        previous
    }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long = synchronized(this) {
        val previous = readLong(addr)
        if (previous == expected) {
            writeLong(addr, replacement)
        }
        previous
    }

    override fun atomicAddShort(addr: Int, delta: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        writeShort(addr, (previous + delta).toShort())
        previous
    }

    override fun atomicAndShort(addr: Int, mask: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() and mask.toInt()).toShort())
        previous
    }

    override fun atomicOrShort(addr: Int, mask: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() or mask.toInt()).toShort())
        previous
    }

    override fun atomicXorShort(addr: Int, mask: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        writeShort(addr, (previous.toInt() xor mask.toInt()).toShort())
        previous
    }

    override fun atomicXchgShort(addr: Int, value: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        writeShort(addr, value)
        previous
    }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short = synchronized(this) {
        val previous = readShort(addr)
        if (previous == expected) {
            writeShort(addr, replacement)
        }
        previous
    }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        writeByte(addr, (previous + delta).toByte())
        previous
    }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() and mask.toInt()).toByte())
        previous
    }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() or mask.toInt()).toByte())
        previous
    }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        writeByte(addr, (previous.toInt() xor mask.toInt()).toByte())
        previous
    }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        writeByte(addr, value)
        previous
    }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte = synchronized(this) {
        val previous = read(addr)
        if (previous == expected) {
            writeByte(addr, replacement)
        }
        previous
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
    ) = AndroidWasmtimePulleyNative.memoryWrite(nativeHandle, nativeMemory, addr, data, offset, size)

    override fun read(addr: Int): Byte = AndroidWasmtimePulleyNative.memoryReadByte(nativeHandle, nativeMemory, addr)

    override fun readBytes(addr: Int, len: Int): ByteArray =
        AndroidWasmtimePulleyNative.memoryRead(nativeHandle, nativeMemory, addr, len)

    override fun read(
        addr: Int,
        target: ByteArray,
        offset: Int,
        size: Int,
    ) = AndroidWasmtimePulleyNative.memoryReadInto(nativeHandle, nativeMemory, addr, target, offset, size)

    override fun writeI32(addr: Int, data: Int) =
        AndroidWasmtimePulleyNative.memoryWriteI32(nativeHandle, nativeMemory, addr, data)

    override fun readInt(addr: Int): Int = AndroidWasmtimePulleyNative.memoryReadI32(nativeHandle, nativeMemory, addr)

    override fun writeLong(addr: Int, data: Long) =
        AndroidWasmtimePulleyNative.memoryWriteI64(nativeHandle, nativeMemory, addr, data)

    override fun readLong(addr: Int): Long = AndroidWasmtimePulleyNative.memoryReadI64(nativeHandle, nativeMemory, addr)

    override fun writeShort(addr: Int, data: Short) =
        AndroidWasmtimePulleyNative.memoryWriteI16(nativeHandle, nativeMemory, addr, data)

    override fun readShort(addr: Int): Short =
        AndroidWasmtimePulleyNative.memoryReadI16(nativeHandle, nativeMemory, addr)

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xffffL

    override fun writeByte(addr: Int, data: Byte) =
        AndroidWasmtimePulleyNative.memoryWriteByte(nativeHandle, nativeMemory, addr, data)

    override fun writeF32(addr: Int, data: Float) =
        AndroidWasmtimePulleyNative.memoryWriteF32(nativeHandle, nativeMemory, addr, data)

    override fun readF32(addr: Int): Long = readFloat(addr).toRawBits().toLong()

    override fun readFloat(addr: Int): Float =
        AndroidWasmtimePulleyNative.memoryReadF32(nativeHandle, nativeMemory, addr)

    override fun writeF64(addr: Int, data: Double) =
        AndroidWasmtimePulleyNative.memoryWriteF64(nativeHandle, nativeMemory, addr, data)

    override fun readDouble(addr: Int): Double =
        AndroidWasmtimePulleyNative.memoryReadF64(nativeHandle, nativeMemory, addr)

    override fun readF64(addr: Int): Long = readDouble(addr).toRawBits()

    override fun zero() = fill(0, 0, AndroidWasmtimePulleyNative.memoryByteSize(nativeHandle, nativeMemory).toInt())

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) =
        AndroidWasmtimePulleyNative.memoryFill(nativeHandle, nativeMemory, value, fromIndex, toIndex)

    override fun drop(segment: Int) = throw WasmEngineException("Wasmtime owns passive data segments")
}

private object AndroidWasmtimePulleyNative {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("krwa_pulley_android")
    }.exceptionOrNull()

    fun unavailableReason(target: String): String? {
        loadError?.let { error ->
            return error.message ?: error::class.simpleName ?: "failed to load krwa_pulley_android"
        }
        return nativeUnavailableReason(target)
    }

    fun componentWasiUnavailableReason(): String? {
        loadError?.let { error ->
            return error.message ?: error::class.simpleName ?: "failed to load krwa_pulley_android"
        }
        return nativeComponentWasiUnavailableReason()
    }

    @JvmStatic
    fun invokeHostFunction(callbackId: Long, args: LongArray): LongArray = AndroidHostCallbacks.invoke(callbackId, args)

    @JvmStatic
    external fun nativeUnavailableReason(target: String): String?

    @JvmStatic
    external fun nativeComponentWasiUnavailableReason(): String?

    @JvmStatic
    external fun create(
        moduleBytes: ByteArray,
        precompiledModule: Boolean,
        target: String,
        maxMemoryBytes: Long,
        maxWasmStackBytes: Long,
        maxTableElements: Long,
        maxInstances: Long,
        maxTables: Long,
        maxMemories: Long,
        maxFuel: Long,
        importCallbackIds: LongArray,
        importParamOpcodes: Array<IntArray>,
        importReturnOpcodes: Array<IntArray>,
    ): Long

    @JvmStatic
    external fun destroy(nativeHandle: Long)

    @JvmStatic
    external fun bindFunction(nativeHandle: Long, name: String): Long

    @JvmStatic
    external fun bindMemory(nativeHandle: Long, name: String): Long

    @JvmStatic
    external fun call(
        nativeHandle: Long,
        nativeFunction: Long,
        paramOpcodes: IntArray,
        returnOpcodes: IntArray,
        args: LongArray,
    ): LongArray

    @JvmStatic
    external fun memoryByteSize(nativeHandle: Long, nativeMemory: Long): Long

    @JvmStatic
    external fun memoryGrow(nativeHandle: Long, nativeMemory: Long, deltaPages: Int): Int

    @JvmStatic
    external fun memoryRead(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        size: Int,
    ): ByteArray

    @JvmStatic
    external fun memoryReadInto(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        target: ByteArray,
        offset: Int,
        size: Int,
    )

    @JvmStatic
    external fun memoryWrite(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        data: ByteArray,
        offset: Int,
        size: Int,
    )

    @JvmStatic
    external fun memoryReadByte(nativeHandle: Long, nativeMemory: Long, addr: Int): Byte

    @JvmStatic
    external fun memoryWriteByte(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Byte,
    )

    @JvmStatic
    external fun memoryReadI16(nativeHandle: Long, nativeMemory: Long, addr: Int): Short

    @JvmStatic
    external fun memoryWriteI16(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Short,
    )

    @JvmStatic
    external fun memoryReadI32(nativeHandle: Long, nativeMemory: Long, addr: Int): Int

    @JvmStatic
    external fun memoryWriteI32(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Int,
    )

    @JvmStatic
    external fun memoryReadI64(nativeHandle: Long, nativeMemory: Long, addr: Int): Long

    @JvmStatic
    external fun memoryWriteI64(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Long,
    )

    @JvmStatic
    external fun memoryReadF32(nativeHandle: Long, nativeMemory: Long, addr: Int): Float

    @JvmStatic
    external fun memoryWriteF32(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Float,
    )

    @JvmStatic
    external fun memoryReadF64(nativeHandle: Long, nativeMemory: Long, addr: Int): Double

    @JvmStatic
    external fun memoryWriteF64(
        nativeHandle: Long,
        nativeMemory: Long,
        addr: Int,
        value: Double,
    )

    @JvmStatic
    external fun memoryFill(
        nativeHandle: Long,
        nativeMemory: Long,
        value: Byte,
        fromIndex: Int,
        toIndex: Int,
    )
}

fun androidWasmtimeTargetUnavailableReason(target: String): String? =
    AndroidWasmtimePulleyNative.unavailableReason(target)

fun androidWasmtimeComponentWasiUnavailableReason(): String? =
    AndroidWasmtimePulleyNative.componentWasiUnavailableReason()

private const val AndroidWasmtimeTargetProperty = "krwa.android.wasmtime.target"

private fun exportedFunctions(module: WasmModule): Map<String, AndroidFunctionExport> {
    val result = HashMap<String, AndroidFunctionExport>()
    for (export in exportedPulleyFunctions(module).values) {
        result[export.name] = AndroidFunctionExport(export.name, export.index, export.type)
    }
    return result
}
