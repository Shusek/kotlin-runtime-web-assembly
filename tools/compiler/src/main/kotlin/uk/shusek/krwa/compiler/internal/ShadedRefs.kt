package uk.shusek.krwa.compiler.internal

import java.lang.reflect.Method
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.runtime.TableInstance
import uk.shusek.krwa.runtime.WasmException
import uk.shusek.krwa.runtime.internal.CompilerInterpreterMachine
import uk.shusek.krwa.wasm.types.Element

object ShadedRefs {
    @JvmField val CHECK_INTERRUPTION: Method = method(Shaded::class.java, "checkInterruption")

    @JvmField
    val CALL_INDIRECT: Method =
        method(
            Shaded::class.java,
            "callIndirect",
            LongArray::class.java,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val CALL_INDIRECT_ON_INTERPRETER: Method =
        method(
            Shaded::class.java,
            "callIndirect",
            LongArray::class.java,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField val INSTANCE_MEMORY: Method = method(Instance::class.java, "memory")

    @JvmField val INSTANCE_MEMORY_IDX: Method = method(Instance::class.java, "memory", Integer.TYPE)

    @JvmField
    val CALL_HOST_FUNCTION: Method =
        method(
            Shaded::class.java,
            "callHostFunction",
            Instance::class.java,
            Integer.TYPE,
            LongArray::class.java,
        )

    @JvmField
    val READ_GLOBAL: Method =
        method(Shaded::class.java, "readGlobal", Integer.TYPE, Instance::class.java)

    @JvmField
    val READ_GLOBAL_REF: Method =
        method(Shaded::class.java, "readGlobalRef", Integer.TYPE, Instance::class.java)

    @JvmField
    val WRITE_GLOBAL: Method =
        method(
            Shaded::class.java,
            "writeGlobal",
            java.lang.Long.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val INSTANCE_SET_ELEMENT: Method =
        method(Instance::class.java, "setElement", Integer.TYPE, Element::class.java)

    @JvmField val INSTANCE_TABLE: Method = method(Instance::class.java, "table", Integer.TYPE)

    @JvmField
    val MEMORY_COPY: Method =
        method(
            Shaded::class.java,
            "memoryCopy",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_COPY_2: Method =
        method(
            Shaded::class.java,
            "memoryCopy",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_FILL: Method =
        method(
            Shaded::class.java,
            "memoryFill",
            Integer.TYPE,
            java.lang.Byte.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_INIT: Method =
        method(
            Shaded::class.java,
            "memoryInit",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_GROW: Method =
        method(Shaded::class.java, "memoryGrow", Integer.TYPE, Memory::class.java)

    @JvmField
    val MEMORY_DROP: Method =
        method(Shaded::class.java, "memoryDrop", Integer.TYPE, Memory::class.java)

    @JvmField
    val MEMORY_PAGES: Method = method(Shaded::class.java, "memoryPages", Memory::class.java)

    @JvmField
    val MEMORY_READ_BYTE: Method =
        method(Shaded::class.java, "memoryReadByte", Integer.TYPE, Integer.TYPE, Memory::class.java)

    @JvmField val MEMORY_READ_DIRECT: Method = method(Memory::class.java, "read", Integer.TYPE)

    @JvmField val MEMORY_READ_U8_DIRECT: Method = method(Memory::class.java, "readU8Int", Integer.TYPE)

    @JvmField val MEMORY_READ_SHORT_DIRECT: Method = method(Memory::class.java, "readShort", Integer.TYPE)

    @JvmField val MEMORY_READ_INT_DIRECT: Method = method(Memory::class.java, "readInt", Integer.TYPE)

    @JvmField val MEMORY_READ_LONG_DIRECT: Method = method(Memory::class.java, "readLong", Integer.TYPE)

    @JvmField val MEMORY_READ_FLOAT_DIRECT: Method = method(Memory::class.java, "readFloat", Integer.TYPE)

    @JvmField val MEMORY_READ_DOUBLE_DIRECT: Method = method(Memory::class.java, "readDouble", Integer.TYPE)

    @JvmField
    val MEMORY_READ_SHORT: Method =
        method(
            Shaded::class.java,
            "memoryReadShort",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_READ_INT: Method =
        method(Shaded::class.java, "memoryReadInt", Integer.TYPE, Integer.TYPE, Memory::class.java)

    @JvmField
    val MEMORY_READ_LONG: Method =
        method(Shaded::class.java, "memoryReadLong", Integer.TYPE, Integer.TYPE, Memory::class.java)

    @JvmField
    val MEMORY_READ_FLOAT: Method =
        method(
            Shaded::class.java,
            "memoryReadFloat",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_READ_DOUBLE: Method =
        method(
            Shaded::class.java,
            "memoryReadDouble",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_BYTE: Method =
        method(
            Shaded::class.java,
            "memoryWriteByte",
            Integer.TYPE,
            java.lang.Byte.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_SHORT: Method =
        method(
            Shaded::class.java,
            "memoryWriteShort",
            Integer.TYPE,
            java.lang.Short.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_INT: Method =
        method(
            Shaded::class.java,
            "memoryWriteInt",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_LONG: Method =
        method(
            Shaded::class.java,
            "memoryWriteLong",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_FLOAT: Method =
        method(
            Shaded::class.java,
            "memoryWriteFloat",
            Integer.TYPE,
            java.lang.Float.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_WRITE_DOUBLE: Method =
        method(
            Shaded::class.java,
            "memoryWriteDouble",
            Integer.TYPE,
            java.lang.Double.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_BYTE_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntByteRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_SHORT_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntShortRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_BYTE_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongByteRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_SHORT_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongShortRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_INT_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongIntRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_READ: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRead",
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val I32_GE_U: Method = method(Shaded::class.java, "i32_ge_u", Integer.TYPE, Integer.TYPE)

    @JvmField val REF_IS_NULL: Method = method(Shaded::class.java, "isRefNull", Integer.TYPE)

    @JvmField val REF_AS_NON_NULL: Method = method(Shaded::class.java, "refAsNonNull", Integer.TYPE)

    @JvmField
    val TABLE_GET: Method =
        method(Shaded::class.java, "tableGet", Integer.TYPE, Integer.TYPE, Instance::class.java)

    @JvmField
    val TABLE_SET: Method =
        method(
            Shaded::class.java,
            "tableSet",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val TABLE_SIZE: Method =
        method(Shaded::class.java, "tableSize", Integer.TYPE, Instance::class.java)

    @JvmField
    val TABLE_GROW: Method =
        method(
            Shaded::class.java,
            "tableGrow",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val TABLE_FILL: Method =
        method(
            Shaded::class.java,
            "tableFill",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val TABLE_COPY: Method =
        method(
            Shaded::class.java,
            "tableCopy",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val TABLE_INIT: Method =
        method(
            Shaded::class.java,
            "tableInit",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val TABLE_REQUIRED_REF: Method = method(TableInstance::class.java, "requiredRef", Integer.TYPE)

    @JvmField
    val TABLE_INSTANCE: Method = method(TableInstance::class.java, "instance", Integer.TYPE)

    @JvmField
    val THROW_CALL_STACK_EXHAUSTED: Method =
        method(Shaded::class.java, "throwCallStackExhausted", StackOverflowError::class.java)

    @JvmField
    val THROW_INDIRECT_CALL_TYPE_MISMATCH: Method =
        method(Shaded::class.java, "throwIndirectCallTypeMismatch")

    @JvmField
    val THROW_OUT_OF_BOUNDS_MEMORY_ACCESS: Method =
        method(Shaded::class.java, "throwOutOfBoundsMemoryAccess")

    @JvmField val THROW_TRAP_EXCEPTION: Method = method(Shaded::class.java, "throwTrapException")

    @JvmField
    val THROW_NULL_FUNCTION_REFERENCE: Method =
        method(Shaded::class.java, "throwNullFunctionReference")

    @JvmField
    val THROW_UNKNOWN_FUNCTION: Method =
        method(Shaded::class.java, "throwUnknownFunction", Integer.TYPE)

    @JvmField
    val AOT_INTERPRETER_MACHINE_CALL: Method =
        method(CompilerInterpreterMachine::class.java, "call", Integer.TYPE, LongArray::class.java)

    @JvmField
    val CREATE_WASM_EXCEPTION: Method =
        method(
            Shaded::class.java,
            "createWasmException",
            LongArray::class.java,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField val INSTANCE_GET_EXCEPTION: Method = method(Instance::class.java, "exn", Integer.TYPE)

    @JvmField
    val EXCEPTION_MATCHES: Method =
        method(
            Shaded::class.java,
            "exceptionMatches",
            WasmException::class.java,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntWrite",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_BYTE_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntByteWrite",
            Integer.TYPE,
            java.lang.Byte.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_SHORT_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntShortWrite",
            Integer.TYPE,
            java.lang.Short.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongWrite",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_BYTE_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongByteWrite",
            Integer.TYPE,
            java.lang.Byte.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_SHORT_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongShortWrite",
            Integer.TYPE,
            java.lang.Short.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_INT_WRITE: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongIntWrite",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_ADD: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwAdd",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_SUB: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwSub",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_AND: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwAnd",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_OR: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwOr",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_XOR: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwXor",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_XCHG: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwXchg",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW_CMPXCHG: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmwCmpxchg",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_ADD_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8AddU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_SUB_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8SubU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_AND_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8AndU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_OR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8OrU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_XOR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8XorU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_XCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8XchgU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW8_CMPXCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw8CmpxchgU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_ADD_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16AddU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_SUB_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16SubU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_AND_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16AndU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_OR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16OrU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_XOR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16XorU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_XCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16XchgU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_INT_RMW16_CMPXCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicIntRmw16CmpxchgU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_ADD: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwAdd",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_SUB: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwSub",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_AND: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwAnd",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_OR: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwOr",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_XOR: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwXor",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_XCHG: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwXchg",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW_CMPXCHG: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmwCmpxchg",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_ADD_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8AddU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_SUB_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8SubU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_AND_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8AndU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_OR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8OrU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_XOR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8XorU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_XCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8XchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW8_CMPXCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw8CmpxchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_ADD_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16AddU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_SUB_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16SubU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_AND_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16AndU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_OR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16OrU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_XOR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16XorU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_XCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16XchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW16_CMPXCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw16CmpxchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_ADD_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32AddU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_SUB_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32SubU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_AND_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32AndU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_OR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32OrU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_XOR_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32XorU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_XCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32XchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_LONG_RMW32_CMPXCHG_U: Method =
        method(
            Shaded::class.java,
            "memoryAtomicLongRmw32CmpxchgU",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_WAIT32: Method =
        method(
            Shaded::class.java,
            "memoryAtomicWait32",
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_WAIT64: Method =
        method(
            Shaded::class.java,
            "memoryAtomicWait64",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_NOTIFY: Method =
        method(
            Shaded::class.java,
            "memoryAtomicNotify",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Memory::class.java,
        )

    @JvmField
    val MEMORY_ATOMIC_FENCE: Method =
        method(Shaded::class.java, "memoryAtomicFence", Memory::class.java)

    @JvmField
    val SET_TAIL_CALL: Method =
        method(
            Shaded::class.java,
            "setTailCall",
            Integer.TYPE,
            LongArray::class.java,
            Instance::class.java,
        )

    @JvmField
    val SET_TAIL_CALL_INDIRECT: Method =
        method(
            Shaded::class.java,
            "setTailCallIndirect",
            LongArray::class.java,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val IS_TAIL_CALL_PENDING: Method =
        method(Shaded::class.java, "isTailCallPending", Instance::class.java)

    @JvmField
    val RESOLVE_TAIL_CALL: Method =
        method(Shaded::class.java, "resolveTailCall", Instance::class.java)

    @JvmField
    val STRUCT_NEW: Method =
        method(
            Shaded::class.java,
            "structNew",
            LongArray::class.java,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val STRUCT_NEW_DEFAULT: Method =
        method(Shaded::class.java, "structNewDefault", Integer.TYPE, Instance::class.java)

    @JvmField
    val STRUCT_GET: Method =
        method(
            Shaded::class.java,
            "structGet",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val STRUCT_GET_S: Method =
        method(
            Shaded::class.java,
            "structGetS",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val STRUCT_GET_U: Method =
        method(
            Shaded::class.java,
            "structGetU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val STRUCT_SET: Method =
        method(
            Shaded::class.java,
            "structSet",
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_NEW: Method =
        method(
            Shaded::class.java,
            "arrayNew",
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_NEW_DEFAULT: Method =
        method(
            Shaded::class.java,
            "arrayNewDefault",
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_NEW_FIXED: Method =
        method(
            Shaded::class.java,
            "arrayNewFixed",
            LongArray::class.java,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_NEW_DATA: Method =
        method(
            Shaded::class.java,
            "arrayNewData",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_NEW_ELEM: Method =
        method(
            Shaded::class.java,
            "arrayNewElem",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_GET: Method =
        method(
            Shaded::class.java,
            "arrayGet",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_GET_S: Method =
        method(
            Shaded::class.java,
            "arrayGetS",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_GET_U: Method =
        method(
            Shaded::class.java,
            "arrayGetU",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_SET: Method =
        method(
            Shaded::class.java,
            "arraySet",
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_LEN: Method =
        method(Shaded::class.java, "arrayLen", Integer.TYPE, Instance::class.java)

    @JvmField
    val ARRAY_FILL: Method =
        method(
            Shaded::class.java,
            "arrayFill",
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_COPY: Method =
        method(
            Shaded::class.java,
            "arrayCopy",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_INIT_DATA: Method =
        method(
            Shaded::class.java,
            "arrayInitData",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val ARRAY_INIT_ELEM: Method =
        method(
            Shaded::class.java,
            "arrayInitElem",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val REF_TEST: Method =
        method(
            Shaded::class.java,
            "refTest",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val REF_TEST_NULL: Method =
        method(
            Shaded::class.java,
            "refTestNull",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val CAST_TEST: Method =
        method(
            Shaded::class.java,
            "castTest",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val CAST_TEST_NULL: Method =
        method(
            Shaded::class.java,
            "castTestNull",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val HEAP_TYPE_MATCH: Method =
        method(
            Shaded::class.java,
            "heapTypeMatch",
            Integer.TYPE,
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Instance::class.java,
        )

    @JvmField
    val REF_EQ: Method =
        method(Shaded::class.java, "refEq", Integer.TYPE, Integer.TYPE, Instance::class.java)

    @JvmField
    val REF_I31: Method = method(Shaded::class.java, "refI31", Integer.TYPE, Instance::class.java)

    @JvmField
    val I31_GET_S: Method =
        method(Shaded::class.java, "i31GetS", Integer.TYPE, Instance::class.java)

    @JvmField
    val I31_GET_U: Method =
        method(Shaded::class.java, "i31GetU", Integer.TYPE, Instance::class.java)

    @JvmField
    val DATA_DROP: Method =
        method(Shaded::class.java, "dataDrop", Integer.TYPE, Instance::class.java)

    private fun method(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method =
        try {
            owner.getMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw AssertionError(e)
        }
}
