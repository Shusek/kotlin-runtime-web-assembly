package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.MalformedException

@Deprecated("use uk.shusek.krwa.wasm.types.ValType")
enum class ValueType(private val id: Int) {
    UNKNOWN(-1),
    F64(ID.F64),
    F32(ID.F32),
    I64(ID.I64),
    I32(ID.I32),
    V128(ID.V128),
    FuncRef(ID.FuncRef),
    ExnRef(ID.ExnRef),
    ExternRef(ID.ExternRef);

    fun id(): Int = id

    fun size(): Int =
        when (this) {
            F64,
            I64 -> 8
            F32,
            I32 -> 4
            V128 -> 16
            else -> throw IllegalStateException("Type does not have size")
        }

    fun isNumeric(): Boolean =
        when (this) {
            F64,
            F32,
            I64,
            I32 -> true
            else -> false
        }

    fun isInteger(): Boolean =
        when (this) {
            I64,
            I32 -> true
            else -> false
        }

    fun isFloatingPoint(): Boolean =
        when (this) {
            F64,
            F32 -> true
            else -> false
        }

    fun isReference(): Boolean =
        when (this) {
            FuncRef,
            ExnRef,
            ExternRef -> true
            else -> false
        }

    fun toValType(): ValType =
        when (id) {
            ID.F64 -> ValType.F64
            ID.F32 -> ValType.F32
            ID.I64 -> ValType.I64
            ID.I32 -> ValType.I32
            ID.V128 -> ValType.V128
            ID.FuncRef -> ValType.FuncRef
            ID.ExnRef -> ValType.ExnRef
            ID.ExternRef -> ValType.ExternRef
            else -> throw IllegalArgumentException("Invalid value type $id")
        }

    object ID {
        const val ExternRef: Int = 0x6f
        const val ExnRef: Int = 0x69
        const val FuncRef: Int = 0x70
        const val V128: Int = 0x7b
        const val F64: Int = 0x7c
        const val F32: Int = 0x7d
        const val I64: Int = 0x7e
        const val I32: Int = 0x7f
    }

    companion object {
        fun isValid(typeId: Int): Boolean =
            when (typeId) {
                ID.ExternRef,
                ID.ExnRef,
                ID.FuncRef,
                ID.V128,
                ID.I32,
                ID.I64,
                ID.F32,
                ID.F64 -> true

                else -> false
            }

        fun forId(id: Int): ValueType =
            when (id) {
                ID.F64 -> F64
                ID.F32 -> F32
                ID.I64 -> I64
                ID.I32 -> I32
                ID.V128 -> V128
                ID.FuncRef -> FuncRef
                ID.ExnRef -> ExnRef
                ID.ExternRef -> ExternRef
                else -> throw IllegalArgumentException("Invalid value type $id")
            }

        fun refTypeForId(id: Int): ValueType =
            when (id) {
                ID.FuncRef -> FuncRef
                ID.ExternRef -> ExternRef
                ID.ExnRef -> ExnRef
                else -> throw MalformedException("malformed reference type $id")
            }

        fun sizeOf(args: List<ValueType>): Int {
            var total = 0
            for (arg in args) {
                total +=
                    if (arg == V128) {
                        2
                    } else {
                        1
                    }
            }
            return total
        }
    }
}
