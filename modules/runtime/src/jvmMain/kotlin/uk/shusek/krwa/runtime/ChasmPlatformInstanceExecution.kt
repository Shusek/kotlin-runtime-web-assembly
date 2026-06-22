package uk.shusek.krwa.runtime

import io.github.charlietap.chasm.embedding.dsl.ValueTypeListBuilder
import io.github.charlietap.chasm.embedding.dsl.imports
import io.github.charlietap.chasm.embedding.error.ChasmError
import io.github.charlietap.chasm.embedding.invoke as chasmInvoke
import io.github.charlietap.chasm.embedding.instance as chasmInstance
import io.github.charlietap.chasm.embedding.memory.growMemory as chasmGrowMemory
import io.github.charlietap.chasm.embedding.memory.readByte as chasmReadByte
import io.github.charlietap.chasm.embedding.memory.readBytes as chasmReadBytes
import io.github.charlietap.chasm.embedding.memory.readDouble as chasmReadDouble
import io.github.charlietap.chasm.embedding.memory.readFloat as chasmReadFloat
import io.github.charlietap.chasm.embedding.memory.readInt as chasmReadInt
import io.github.charlietap.chasm.embedding.memory.readLong as chasmReadLong
import io.github.charlietap.chasm.embedding.memory.sizeMemory as chasmSizeMemory
import io.github.charlietap.chasm.embedding.memory.writeByte as chasmWriteByte
import io.github.charlietap.chasm.embedding.memory.writeBytes as chasmWriteBytes
import io.github.charlietap.chasm.embedding.memory.writeDouble as chasmWriteDouble
import io.github.charlietap.chasm.embedding.memory.writeFloat as chasmWriteFloat
import io.github.charlietap.chasm.embedding.memory.writeInt as chasmWriteInt
import io.github.charlietap.chasm.embedding.memory.writeLong as chasmWriteLong
import io.github.charlietap.chasm.embedding.module as chasmModule
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Instance as ChasmInstance
import io.github.charlietap.chasm.embedding.shapes.Memory as ChasmMemory
import io.github.charlietap.chasm.embedding.shapes.Store as ChasmStore
import io.github.charlietap.chasm.embedding.store as chasmStore
import io.github.charlietap.chasm.host.HostFunctionException
import io.github.charlietap.chasm.runtime.value.ExecutionValue
import io.github.charlietap.chasm.runtime.value.NumberValue
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.DataSegment
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.ValType

internal class ChasmPlatformInstanceExecution
private constructor(
    private val module: WasmModule,
    private val hostInstance: Instance,
    private val store: ChasmStore,
    private val instance: ChasmInstance,
) : PlatformInstanceExecution {
    override val backend: ExecutionBackend = ExecutionBackend.CHASM
    private val memoryViews = HashMap<String, Memory>()

    override fun export(name: String): ExportFunction {
        val type = exportType(name)
        return ExportFunction { args ->
            val result =
                chasmInvoke(store, instance, name, toChasmValues(args, type.params()))
                    .orThrow("invoke export '$name'")
            toKrwaValues(result, type.returns())
        }
    }

    override fun exportType(name: String): FunctionType {
        val export = functionExport(name)
        return hostInstance.type(hostInstance.functionType(export.index()))
    }

    override fun memory(name: String): Memory {
        return memoryViews.getOrPut(name) {
            val export = memoryExport(name)
            ChasmMemoryView(
                store = store,
                memory = chasmMemoryExport(name),
                limits = memoryLimits(export.index()),
            )
        }
    }

    override fun memory(index: Int): Memory? {
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            if (export.exportType() == ExternalType.MEMORY && export.index() == index) {
                return memory(export.name())
            }
        }
        return null
    }

    private fun functionExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val export = findExport(name)
        if (export.exportType() != ExternalType.FUNCTION) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to ${ExternalType.FUNCTION}"
            )
        }
        return export
    }

    private fun memoryExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val export = findExport(name)
        if (export.exportType() != ExternalType.MEMORY) {
            throw InvalidException(
                "The export ${export.name()} is of type ${export.exportType()} and cannot be converted to ${ExternalType.MEMORY}"
            )
        }
        return export
    }

    private fun findExport(name: String): uk.shusek.krwa.wasm.types.Export {
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            val export = exportSection.getExport(i)
            if (export.name() == name) {
                return export
            }
        }
        throw InvalidException("Unknown export with name $name")
    }

    private fun chasmMemoryExport(name: String): ChasmMemory =
        (instance.exports.firstOrNull { it.name == name }?.value as? ChasmMemory)
            ?: throw WasmEngineException("Chasm memory export '$name' is not available")

    private fun memoryLimits(index: Int): MemoryLimits {
        val importedMemories = module.importSection().count(ExternalType.MEMORY)
        if (index < importedMemories) {
            throw WasmEngineException("Chasm backend does not expose imported KRWA Memory views yet")
        }
        val memoryIndex = index - importedMemories
        return module.memorySection()?.getMemory(memoryIndex)?.limits()
            ?: throw InvalidException("unknown memory $index")
    }

    companion object {
        fun create(
            module: WasmModule,
            imports: ImportValues,
            hostInstance: Instance,
        ): PlatformInstanceExecution {
            requireSupportedImports(module, imports)

            val bytes =
                module.originalBytes()
                    ?: throw WasmEngineException("Chasm backend requires original Wasm module bytes")
            val store = chasmStore()
            val chasmImports =
                imports(store) {
                    for (i in 0 until imports.functionCount()) {
                        val function = imports.function(i)
                        val paramTypes = function.paramTypes()
                        val returnTypes = function.returnTypes()
                        val handle =
                            function.handle()
                                ?: throw WasmEngineException(
                                    "Chasm backend requires host function handles for ${function.module()}.${function.name()}"
                                )
                        function {
                            moduleName = function.module()
                            entityName = function.name()
                            type {
                                params { paramTypes.forEach { addKrwaType(it) } }
                                results { returnTypes.forEach { addKrwaType(it) } }
                            }
                            reference { values ->
                                try {
                                    val args = toKrwaValues(values, paramTypes)
                                    val result = handle.apply(hostInstance, args) ?: EMPTY_LONG_ARRAY
                                    toChasmValues(result, returnTypes)
                                } catch (failure: Exception) {
                                    throw HostFunctionException(
                                        failure.message ?: failure.javaClass.simpleName
                                    )
                                }
                            }
                        }
                    }
                }

            val decodedModule = chasmModule(bytes).orThrow("decode module")
            val chasmInstance =
                chasmInstance(store, decodedModule, chasmImports)
                    .orThrow("instantiate module")
            return ChasmPlatformInstanceExecution(module, hostInstance, store, chasmInstance)
        }

        private fun requireSupportedImports(
            module: WasmModule,
            imports: ImportValues,
        ) {
            val importSection = module.importSection()
            if (
                importSection.count(ExternalType.GLOBAL) != 0 ||
                    importSection.count(ExternalType.MEMORY) != 0 ||
                    importSection.count(ExternalType.TABLE) != 0 ||
                    importSection.count(ExternalType.TAG) != 0 ||
                    imports.globalCount() != 0 ||
                    imports.memoryCount() != 0 ||
                    imports.tableCount() != 0 ||
                    imports.tagCount() != 0
            ) {
                throw WasmEngineException(
                    "Chasm backend currently supports function imports only"
                )
            }
        }

        private fun ValueTypeListBuilder.addKrwaType(type: ValType) {
            when (type.opcode()) {
                ValType.ID.I32 -> i32()
                ValType.ID.I64 -> i64()
                ValType.ID.F32 -> f32()
                ValType.ID.F64 -> f64()
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }
        }

        private fun toChasmValues(
            values: LongArray,
            types: List<ValType>,
        ): List<ExecutionValue> {
            if (values.size != types.size) {
                throw WasmEngineException("Expected ${types.size} values, got ${values.size}")
            }
            return when (values.size) {
                0 -> emptyList()
                1 -> listOf(toChasmValue(values[0], types[0]))
                else -> values.indices.map { idx -> toChasmValue(values[idx], types[idx]) }
            }
        }

        private fun toChasmValue(
            value: Long,
            type: ValType,
        ): ExecutionValue =
            when (type.opcode()) {
                ValType.ID.I32 -> NumberValue.I32(value.toInt())
                ValType.ID.I64 -> NumberValue.I64(value)
                ValType.ID.F32 -> NumberValue.F32(Float.fromBits(value.toInt()))
                ValType.ID.F64 -> NumberValue.F64(Double.fromBits(value))
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }

        private fun toKrwaValues(
            values: List<ExecutionValue>,
            types: List<ValType>,
        ): LongArray {
            if (values.size != types.size) {
                throw WasmEngineException("Expected ${types.size} values, got ${values.size}")
            }
            return when (values.size) {
                0 -> EMPTY_LONG_ARRAY
                1 -> longArrayOf(toKrwaValue(values[0], types[0]))
                else -> LongArray(values.size) { idx -> toKrwaValue(values[idx], types[idx]) }
            }
        }

        private fun toKrwaValue(
            value: ExecutionValue,
            type: ValType,
        ): Long =
            when (type.opcode()) {
                ValType.ID.I32 ->
                    (value as? NumberValue.I32)?.value?.toLong()
                        ?: unexpectedValue(value, type)
                ValType.ID.I64 ->
                    (value as? NumberValue.I64)?.value
                        ?: unexpectedValue(value, type)
                ValType.ID.F32 ->
                    (value as? NumberValue.F32)?.value?.toRawBits()?.toLong()
                        ?: unexpectedValue(value, type)
                ValType.ID.F64 ->
                    (value as? NumberValue.F64)?.value?.toRawBits()
                        ?: unexpectedValue(value, type)
                else ->
                    throw WasmEngineException(
                        "Chasm backend currently supports numeric value types only: $type"
                    )
            }

        private fun unexpectedValue(
            value: ExecutionValue,
            type: ValType,
        ): Nothing =
            throw WasmEngineException("Expected Chasm value of type $type, got $value")

        private fun <S> ChasmResult<S, out ChasmError>.orThrow(action: String): S =
            when (this) {
                is ChasmResult.Success -> result
                is ChasmResult.Error ->
                    throw WasmEngineException("Chasm $action failed: ${error.error}")
            }

        private val EMPTY_LONG_ARRAY = LongArray(0)
    }
}

private class ChasmMemoryView(
    private val store: ChasmStore,
    private val memory: ChasmMemory,
    private val limits: MemoryLimits,
) : Memory {
    private val monitor = Any()

    override fun pages(): Int =
        chasmSizeMemory(store, memory).orThrow("size memory") / Memory.PAGE_SIZE

    override fun grow(size: Int): Int =
        chasmGrowMemory(store, memory, size).orThrow("grow memory")

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = limits.maximumPages()

    override fun shared(): Boolean = limits.shared()

    override fun lock(address: Int): Any = monitor

    override fun waitOn(address: Int, expected: Int, timeout: Long): Int =
        throw WasmEngineException("Chasm memory view does not support wait/notify")

    override fun waitOn(address: Int, expected: Long, timeout: Long): Int =
        throw WasmEngineException("Chasm memory view does not support wait/notify")

    override fun notify(address: Int, maxThreads: Int): Int =
        throw WasmEngineException("Chasm memory view does not support wait/notify")

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?) = Unit

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        throw WasmEngineException("Chasm memory view does not expose passive data segments")
    }

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        val bytes =
            if (offset == 0 && size == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + size)
            }
        chasmWriteBytes(store, memory, addr, bytes).orThrow("write memory bytes")
    }

    override fun read(addr: Int): Byte =
        chasmReadByte(store, memory, addr).orThrow("read memory byte")

    override fun readBytes(addr: Int, len: Int): ByteArray =
        ByteArray(len).also { read(addr, it, 0, len) }

    override fun read(addr: Int, target: ByteArray, offset: Int, size: Int) {
        chasmReadBytes(store, memory, target, addr, size, offset).orThrow("read memory bytes")
    }

    override fun writeI32(addr: Int, data: Int) {
        chasmWriteInt(store, memory, addr, data).orThrow("write memory i32")
    }

    override fun readInt(addr: Int): Int =
        chasmReadInt(store, memory, addr).orThrow("read memory i32")

    override fun writeLong(addr: Int, data: Long) {
        chasmWriteLong(store, memory, addr, data).orThrow("write memory i64")
    }

    override fun readLong(addr: Int): Long =
        chasmReadLong(store, memory, addr).orThrow("read memory i64")

    override fun writeShort(addr: Int, data: Short) {
        write(addr, byteArrayOf(data.toByte(), (data.toInt() ushr 8).toByte()))
    }

    override fun readShort(addr: Int): Short {
        val bytes = readBytes(addr, 2)
        return ((bytes[0].toInt() and 0xFF) or (bytes[1].toInt() shl 8)).toShort()
    }

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xFFFFL

    override fun writeByte(addr: Int, data: Byte) {
        chasmWriteByte(store, memory, addr, data).orThrow("write memory byte")
    }

    override fun writeF32(addr: Int, data: Float) {
        chasmWriteFloat(store, memory, addr, data).orThrow("write memory f32")
    }

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float =
        chasmReadFloat(store, memory, addr).orThrow("read memory f32")

    override fun writeF64(addr: Int, data: Double) {
        chasmWriteDouble(store, memory, addr, data).orThrow("write memory f64")
    }

    override fun readDouble(addr: Int): Double =
        chasmReadDouble(store, memory, addr).orThrow("read memory f64")

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun zero() {
        fill(0, 0, Memory.bytes(pages()))
    }

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        var current = fromIndex
        val chunk = ByteArray(minOf(8192, maxOf(0, toIndex - fromIndex))) { value }
        while (current < toIndex) {
            val size = minOf(chunk.size, toIndex - current)
            write(current, chunk, 0, size)
            current += size
        }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        write(dest, readBytes(src, size))
    }

    override fun drop(segment: Int) = Unit

    override fun atomicFence() = Unit

    override fun atomicReadInt(addr: Int): Int = synchronized(monitor) { readInt(addr) }

    override fun atomicReadLong(addr: Int): Long = synchronized(monitor) { readLong(addr) }

    override fun atomicReadShort(addr: Int): Short = synchronized(monitor) { readShort(addr) }

    override fun atomicReadByte(addr: Int): Byte = synchronized(monitor) { read(addr) }

    override fun atomicWriteInt(addr: Int, value: Int) = synchronized(monitor) { writeI32(addr, value) }

    override fun atomicWriteLong(addr: Int, value: Long) = synchronized(monitor) { writeLong(addr, value) }

    override fun atomicWriteShort(addr: Int, value: Short) = synchronized(monitor) { writeShort(addr, value) }

    override fun atomicWriteByte(addr: Int, value: Byte) = synchronized(monitor) { writeByte(addr, value) }

    override fun atomicAddInt(addr: Int, delta: Int): Int =
        atomicUpdate(addr, ::readInt, ::writeI32) { it + delta }

    override fun atomicAndInt(addr: Int, mask: Int): Int =
        atomicUpdate(addr, ::readInt, ::writeI32) { it and mask }

    override fun atomicOrInt(addr: Int, mask: Int): Int =
        atomicUpdate(addr, ::readInt, ::writeI32) { it or mask }

    override fun atomicXorInt(addr: Int, mask: Int): Int =
        atomicUpdate(addr, ::readInt, ::writeI32) { it xor mask }

    override fun atomicXchgInt(addr: Int, value: Int): Int =
        atomicUpdate(addr, ::readInt, ::writeI32) { value }

    override fun atomicCmpxchgInt(addr: Int, expected: Int, replacement: Int): Int =
        synchronized(monitor) {
            val old = readInt(addr)
            if (old == expected) writeI32(addr, replacement)
            old
        }

    override fun atomicAddLong(addr: Int, delta: Long): Long =
        atomicUpdate(addr, ::readLong, ::writeLong) { it + delta }

    override fun atomicAndLong(addr: Int, mask: Long): Long =
        atomicUpdate(addr, ::readLong, ::writeLong) { it and mask }

    override fun atomicOrLong(addr: Int, mask: Long): Long =
        atomicUpdate(addr, ::readLong, ::writeLong) { it or mask }

    override fun atomicXorLong(addr: Int, mask: Long): Long =
        atomicUpdate(addr, ::readLong, ::writeLong) { it xor mask }

    override fun atomicXchgLong(addr: Int, value: Long): Long =
        atomicUpdate(addr, ::readLong, ::writeLong) { value }

    override fun atomicCmpxchgLong(addr: Int, expected: Long, replacement: Long): Long =
        synchronized(monitor) {
            val old = readLong(addr)
            if (old == expected) writeLong(addr, replacement)
            old
        }

    override fun atomicAddShort(addr: Int, delta: Short): Short =
        atomicUpdate(addr, ::readShort, ::writeShort) { (it + delta).toShort() }

    override fun atomicAndShort(addr: Int, mask: Short): Short =
        atomicUpdate(addr, ::readShort, ::writeShort) { (it.toInt() and mask.toInt()).toShort() }

    override fun atomicOrShort(addr: Int, mask: Short): Short =
        atomicUpdate(addr, ::readShort, ::writeShort) { (it.toInt() or mask.toInt()).toShort() }

    override fun atomicXorShort(addr: Int, mask: Short): Short =
        atomicUpdate(addr, ::readShort, ::writeShort) { (it.toInt() xor mask.toInt()).toShort() }

    override fun atomicXchgShort(addr: Int, value: Short): Short =
        atomicUpdate(addr, ::readShort, ::writeShort) { value }

    override fun atomicCmpxchgShort(addr: Int, expected: Short, replacement: Short): Short =
        synchronized(monitor) {
            val old = readShort(addr)
            if (old == expected) writeShort(addr, replacement)
            old
        }

    override fun atomicAddByte(addr: Int, delta: Byte): Byte =
        atomicUpdate(addr, ::read, ::writeByte) { (it + delta).toByte() }

    override fun atomicAndByte(addr: Int, mask: Byte): Byte =
        atomicUpdate(addr, ::read, ::writeByte) { (it.toInt() and mask.toInt()).toByte() }

    override fun atomicOrByte(addr: Int, mask: Byte): Byte =
        atomicUpdate(addr, ::read, ::writeByte) { (it.toInt() or mask.toInt()).toByte() }

    override fun atomicXorByte(addr: Int, mask: Byte): Byte =
        atomicUpdate(addr, ::read, ::writeByte) { (it.toInt() xor mask.toInt()).toByte() }

    override fun atomicXchgByte(addr: Int, value: Byte): Byte =
        atomicUpdate(addr, ::read, ::writeByte) { value }

    override fun atomicCmpxchgByte(addr: Int, expected: Byte, replacement: Byte): Byte =
        synchronized(monitor) {
            val old = read(addr)
            if (old == expected) writeByte(addr, replacement)
            old
        }

    private fun <T> atomicUpdate(
        addr: Int,
        read: (Int) -> T,
        write: (Int, T) -> Unit,
        update: (T) -> T,
    ): T =
        synchronized(monitor) {
            val old = read(addr)
            write(addr, update(old))
            old
        }

    private fun <S> ChasmResult<S, out ChasmError>.orThrow(action: String): S =
        when (this) {
            is ChasmResult.Success -> result
            is ChasmResult.Error ->
                throw WasmEngineException("Chasm $action failed: ${error.error}")
        }
}
