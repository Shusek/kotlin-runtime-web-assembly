package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmEngineException

/** The possible WASM value types. */
class ValType
private constructor(
    opcodeInput: Int,
    typeIdxInput: Int = NULL_TYPEIDX,
    private val resolvedFunctionTypeId: Int = -1,
    private var resolvedFunctionTypeHash: Int = -1,
) {
    private val id: Long

    init {
        var opcode = opcodeInput
        var typeIdx = typeIdxInput
        when (opcode) {
            ID.FuncRef -> {
                typeIdx = TypeIdxCode.FUNC.code()
                opcode = ID.RefNull
            }
            ID.ExternRef -> {
                typeIdx = TypeIdxCode.EXTERN.code()
                opcode = ID.RefNull
            }
            ID.ExnRef -> {
                typeIdx = TypeIdxCode.EXN.code()
                opcode = ID.RefNull
            }
            ID.AnyRef -> {
                typeIdx = TypeIdxCode.ANY.code()
                opcode = ID.RefNull
            }
            ID.EqRef -> {
                typeIdx = TypeIdxCode.EQ.code()
                opcode = ID.RefNull
            }
            ID.i31 -> {
                typeIdx = TypeIdxCode.I31.code()
                opcode = ID.RefNull
            }
            ID.StructRef -> {
                typeIdx = TypeIdxCode.STRUCT.code()
                opcode = ID.RefNull
            }
            ID.ArrayRef -> {
                typeIdx = TypeIdxCode.ARRAY.code()
                opcode = ID.RefNull
            }
            ID.NoneRef -> {
                typeIdx = TypeIdxCode.NONE.code()
                opcode = ID.RefNull
            }
            ID.NoExternRef -> {
                typeIdx = TypeIdxCode.NOEXTERN.code()
                opcode = ID.RefNull
            }
            ID.NoFuncRef -> {
                typeIdx = TypeIdxCode.NOFUNC.code()
                opcode = ID.RefNull
            }
            ID.RefNull,
            ID.Ref -> {
                if (typeIdx >= 0) {
                    assert(resolvedFunctionTypeId >= 0)
                }
            }
        }

        id = createId(opcode, typeIdx)
    }

    fun resolve(typeSection: TypeSection): ValType {
        if (resolvedFunctionTypeId >= 0) {
            try {
                resolvedFunctionTypeHash = typeSection.getSubType(resolvedFunctionTypeId).hashCode()
            } catch (e: IndexOutOfBoundsException) {
                throw InvalidException("unknown type: $resolvedFunctionTypeId")
            }
        }
        return this
    }

    /** @return id of this ValType */
    fun id(): Long = id

    fun opcode(): Int = opcode(id)

    fun typeIdx(): Int = typeIdx(id)

    fun resolvedFunctionTypeId(): Int = resolvedFunctionTypeId

    /**
     * @return the size of this type in memory
     * @throws IllegalStateException if the type cannot be stored in memory
     */
    fun size(): Int =
        when (opcode()) {
            ID.F64,
            ID.I64 -> 8
            ID.F32,
            ID.I32 -> 4
            ID.V128 -> 16
            else -> throw IllegalStateException("Type does not have size")
        }

    /** @return {@code true} if the type is a numeric type, or {@code false} otherwise */
    fun isNumeric(): Boolean =
        when (opcode()) {
            ID.F64,
            ID.F32,
            ID.I64,
            ID.I32 -> true
            else -> false
        }

    /** @return {@code true} if the type is an integer type, or {@code false} otherwise */
    fun isInteger(): Boolean =
        when (opcode()) {
            ID.I64,
            ID.I32 -> true
            else -> false
        }

    /** @return {@code true} if the type is a floating-point type, or {@code false} otherwise */
    fun isFloatingPoint(): Boolean =
        when (opcode()) {
            ID.F64,
            ID.F32 -> true
            else -> false
        }

    fun isReference(): Boolean = isReference(opcode())

    fun isNullable(): Boolean =
        when (opcode()) {
            ID.Ref -> false
            ID.RefNull -> true
            else -> throw IllegalArgumentException("got non-reference type to isNullable(): $this")
        }

    fun withNullability(nullable: Boolean): ValType {
        val targetOpcode = if (nullable) ID.RefNull else ID.Ref
        if (opcode() == targetOpcode) {
            return this
        }
        return ValType(targetOpcode, typeIdx())
    }

    override fun hashCode(): Int {
        if (resolvedFunctionTypeHash != -1) {
            return resolvedFunctionTypeHash
        }
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ValType) {
            return false
        }

        if (this === other) {
            return true
        }

        if (resolvedFunctionTypeHash == -1 && other.resolvedFunctionTypeHash == -1) {
            return id == other.id
        }

        if (resolvedFunctionTypeHash == -1 || other.resolvedFunctionTypeHash == -1) {
            return false
        }

        if (opcode(id) != opcode(other.id)) {
            return false
        }

        if (typeIdx() >= 0 && typeIdx() == other.typeIdx()) {
            return true
        }

        return resolvedFunctionTypeHash == other.resolvedFunctionTypeHash
    }

    override fun toString(): String =
        when (opcode()) {
            ID.Ref,
            ID.RefNull -> ID.toName(opcode()) + "[" + typeIdx() + "]"
            else -> ID.toName(opcode())
        }

    /** a string representation of [ValType] that follows JVM's naming conventions */
    fun name(): String = ID.toName(opcode())

    enum class TypeIdxCode(private val code: Int) {
        // heap type
        NOFUNC(-13), // 0x73
        NOEXTERN(-14), // 0x72
        NONE(-15), // 0x71
        FUNC(-16), // 0x70
        EXTERN(-17), // 0x6F
        ANY(-18), // 0x6E
        EQ(-19), // 0x6D
        I31(-20), // 0x6C
        STRUCT(-21), // 0x6B
        ARRAY(-22), // 0x6A
        EXN(-23), // 0x69
        BOT(-1);

        fun code(): Int = code
    }

    /** A separate holder class for ID constants. */
    object ID {
        const val BOT: Int = -1
        const val RefNull: Int = 0x63
        const val Ref: Int = 0x64
        const val ExnRef: Int = 0x69 // -0x17
        const val ArrayRef: Int = 0x6A
        const val StructRef: Int = 0x6B
        const val i31: Int = 0x6C
        const val EqRef: Int = 0x6D
        const val AnyRef: Int = 0x6E
        const val ExternRef: Int = 0x6F
        const val FuncRef: Int = 0x70
        const val NoneRef: Int = 0x71
        const val NoExternRef: Int = 0x72
        const val NoFuncRef: Int = 0x73
        const val V128: Int = 0x7B
        const val F64: Int = 0x7C
        const val F32: Int = 0x7D
        const val I64: Int = 0x7E
        const val I32: Int = 0x7F

        fun toName(opcode: Int): String =
            when (opcode) {
                BOT -> "Bot"
                RefNull -> "RefNull"
                Ref -> "Ref"
                ExnRef -> "ExnRef"
                V128 -> "V128"
                F64 -> "F64"
                F32 -> "F32"
                I64 -> "I64"
                I32 -> "I32"
                else ->
                    throw IllegalArgumentException("got invalid opcode in ValType.toName: $opcode")
            }

        fun isValidOpcode(opcode: Int): Boolean =
            opcode == RefNull ||
                opcode == Ref ||
                opcode == ExternRef ||
                opcode == FuncRef ||
                opcode == ExnRef ||
                opcode == AnyRef ||
                opcode == EqRef ||
                opcode == i31 ||
                opcode == StructRef ||
                opcode == ArrayRef ||
                opcode == NoneRef ||
                opcode == NoExternRef ||
                opcode == NoFuncRef ||
                opcode == V128 ||
                opcode == F64 ||
                opcode == F32 ||
                opcode == I64 ||
                opcode == I32
    }

    class Builder private constructor() {
        private var opcode = 0
        private var typeIdx = NULL_TYPEIDX

        fun withOpcode(opcode: Int): Builder {
            this.opcode = opcode
            return this
        }

        fun withTypeIdx(typeIdx: Int): Builder {
            this.typeIdx = typeIdx
            return this
        }

        fun fromId(id: Long): Builder {
            opcode = opcode(id)
            typeIdx = ValType.typeIdx(id)
            return this
        }

        fun id(): Long = createId(opcode, typeIdx)

        fun typeIdx(): Int = typeIdx

        fun isReference(): Boolean = ValType.isReference(opcode)

        @Deprecated("use .build.resolve(typeSection) instead")
        fun build(context: (Int) -> FunctionType): ValType {
            if (!isValidOpcode(opcode)) {
                throw WasmEngineException("Invalid type opcode: $opcode")
            }

            val resolvedFunctionType = substitute(opcode, typeIdx, context)
            return ValType(
                opcode,
                typeIdx,
                if (ValType.isReference(opcode) && !ValType.isAbsHeapType(opcode) && typeIdx >= 0) {
                    typeIdx
                } else {
                    -1
                },
                SubType.builder()
                    .withCompType(
                        CompType.builder()
                            .withFuncType(
                                resolvedFunctionType
                                    ?: throw IllegalArgumentException(
                                        "Exactly one field must be filled"
                                    )
                            )
                            .build()
                    )
                    .build()
                    .hashCode(),
            )
        }

        fun build(): ValType {
            if (!isValidOpcode(opcode)) {
                throw WasmEngineException("Invalid type opcode: $opcode")
            }

            return ValType(
                opcode,
                typeIdx,
                if (ValType.isReference(opcode) && !ValType.isAbsHeapType(opcode) && typeIdx >= 0) {
                    typeIdx
                } else {
                    -1
                },
            )
        }

        @Deprecated("use .build.resolve(typeSection) instead")
        fun substitute(
            opcode: Int,
            typeIdx: Int,
            context: (Int) -> FunctionType,
        ): FunctionType? {
            if (ValType.isReference(opcode) && typeIdx >= 0) {
                try {
                    return context(typeIdx)
                } catch (e: IndexOutOfBoundsException) {
                    throw InvalidException("unknown type: $typeIdx")
                }
            }

            return null
        }

        companion object {
            fun create(): Builder = Builder()
        }
    }

    companion object {
        private const val NULL_TYPEIDX = 0
        private const val OPCODE_MASK = 0xFFFFFFFFL
        private const val TYPEIDX_SHIFT = 32

        @WasmJvmStatic
        val BOT: ValType = ValType(ID.BOT)

        @WasmJvmStatic
        val F64: ValType = ValType(ID.F64)

        @WasmJvmStatic
        val F32: ValType = ValType(ID.F32)

        @WasmJvmStatic
        val I64: ValType = ValType(ID.I64)

        @WasmJvmStatic
        val I32: ValType = ValType(ID.I32)

        @WasmJvmStatic
        val V128: ValType = ValType(ID.V128)

        @WasmJvmStatic
        val FuncRef: ValType = ValType(ID.FuncRef)

        @WasmJvmStatic
        val ExnRef: ValType = ValType(ID.ExnRef)

        @WasmJvmStatic
        val ExternRef: ValType = ValType(ID.ExternRef)

        @WasmJvmStatic
        val AnyRef: ValType = ValType(ID.AnyRef)

        @WasmJvmStatic
        val EqRef: ValType = ValType(ID.EqRef)

        @WasmJvmStatic
        val I31Ref: ValType = ValType(ID.i31)

        @WasmJvmStatic
        val StructRef: ValType = ValType(ID.StructRef)

        @WasmJvmStatic
        val ArrayRef: ValType = ValType(ID.ArrayRef)

        @WasmJvmStatic
        val NoneRef: ValType = ValType(ID.NoneRef)

        @WasmJvmStatic
        val NoFuncRef: ValType = ValType(ID.NoFuncRef)

        @WasmJvmStatic
        val NoExternRef: ValType = ValType(ID.NoExternRef)

        @WasmJvmStatic
        val RefBot: ValType = ValType(ID.Ref, TypeIdxCode.BOT.code())

        private fun createId(opcode: Int, typeIdx: Int): Long =
            (typeIdx.toLong() shl TYPEIDX_SHIFT) or (opcode.toLong() and OPCODE_MASK)

        private fun opcode(id: Long): Int = (id and OPCODE_MASK).toInt()

        private fun typeIdx(id: Long): Int = (id ushr TYPEIDX_SHIFT).toInt()

        private fun isReference(opcode: Int): Boolean =
            when (opcode) {
                ID.Ref,
                ID.RefNull,
                ID.ExnRef,
                ID.AnyRef,
                ID.EqRef,
                ID.i31,
                ID.StructRef,
                ID.ArrayRef,
                ID.FuncRef,
                ID.ExternRef,
                ID.NoneRef,
                ID.NoExternRef,
                ID.NoFuncRef -> true
                else -> false
            }

        // https://webassembly.github.io/gc/core/binary/types.html#heap-types
        fun isAbsHeapType(opcode: Int): Boolean =
            opcode == ID.NoFuncRef ||
                opcode == ID.NoExternRef ||
                opcode == ID.NoneRef ||
                opcode == ID.FuncRef ||
                opcode == ID.ExternRef ||
                // TODO: verify?
                opcode == ID.ExnRef ||
                opcode == ID.AnyRef ||
                opcode == ID.EqRef ||
                opcode == ID.i31 ||
                opcode == ID.StructRef ||
                opcode == ID.ArrayRef

        /**
         * @return {@code true} if the given type ID is a valid value type ID, or {@code false} if
         *   it is not
         */
        private fun isValidOpcode(opcode: Int): Boolean =
            when (opcode) {
                ID.RefNull,
                ID.Ref,
                ID.ExnRef,
                ID.AnyRef,
                ID.EqRef,
                ID.i31,
                ID.StructRef,
                ID.ArrayRef,
                ID.NoneRef,
                ID.NoExternRef,
                ID.NoFuncRef,
                ID.V128,
                ID.I32,
                ID.I64,
                ID.F32,
                ID.F64,
                ID.FuncRef,
                ID.ExternRef -> true
                else -> false
            }

        fun isValid(id: Long): Boolean = isValidOpcode(opcode(id))

        fun sizeOf(args: List<ValType>): Int {
            var total = 0
            for (arg in args) {
                total +=
                    if (arg.opcode() == ID.V128) {
                        2
                    } else {
                        1
                    }
            }
            return total
        }

        private fun matchesNull(null1: Boolean, null2: Boolean): Boolean = null1 == null2 || null2

        /**
         * Check if heap type ht1 is a subtype of heap type ht2. Heap types are represented as
         * typeIdx values (negative for abstract, non-negative for concrete).
         */
        fun heapTypeSubtype(ht1: Int, ht2: Int, ts: TypeSection?): Boolean {
            if (ht1 == ht2) {
                return true
            }
            if (ht1 == TypeIdxCode.BOT.code()) {
                return true
            }

            if (ht1 == TypeIdxCode.NONE.code()) {
                return ht2 == TypeIdxCode.ANY.code() ||
                    ht2 == TypeIdxCode.EQ.code() ||
                    ht2 == TypeIdxCode.I31.code() ||
                    ht2 == TypeIdxCode.STRUCT.code() ||
                    ht2 == TypeIdxCode.ARRAY.code() ||
                    (ht2 >= 0 && ts != null && isConcreteInAnyHierarchy(ht2, ts))
            }
            if (ht1 == TypeIdxCode.NOFUNC.code()) {
                return ht2 == TypeIdxCode.FUNC.code() ||
                    (ht2 >= 0 && ts != null && isConcreteFunc(ht2, ts))
            }
            if (ht1 == TypeIdxCode.NOEXTERN.code()) {
                return ht2 == TypeIdxCode.EXTERN.code()
            }

            if (ht1 == TypeIdxCode.I31.code()) {
                return ht2 == TypeIdxCode.EQ.code() || ht2 == TypeIdxCode.ANY.code()
            }
            if (ht1 == TypeIdxCode.STRUCT.code()) {
                return ht2 == TypeIdxCode.EQ.code() || ht2 == TypeIdxCode.ANY.code()
            }
            if (ht1 == TypeIdxCode.ARRAY.code()) {
                return ht2 == TypeIdxCode.EQ.code() || ht2 == TypeIdxCode.ANY.code()
            }
            if (ht1 == TypeIdxCode.EQ.code()) {
                return ht2 == TypeIdxCode.ANY.code()
            }

            if (ht1 >= 0) {
                if (ts != null) {
                    val st1 = ts.getSubType(ht1)
                    val comp = st1.compType()

                    if (comp.structType() != null) {
                        if (
                            ht2 == TypeIdxCode.STRUCT.code() ||
                                ht2 == TypeIdxCode.EQ.code() ||
                                ht2 == TypeIdxCode.ANY.code()
                        ) {
                            return true
                        }
                    }
                    if (comp.arrayType() != null) {
                        if (
                            ht2 == TypeIdxCode.ARRAY.code() ||
                                ht2 == TypeIdxCode.EQ.code() ||
                                ht2 == TypeIdxCode.ANY.code()
                        ) {
                            return true
                        }
                    }
                    if (comp.funcType() != null) {
                        if (ht2 == TypeIdxCode.FUNC.code()) {
                            return true
                        }
                    }

                    for (sup in st1.typeIdx()) {
                        if (heapTypeSubtype(sup, ht2, ts)) {
                            return true
                        }
                    }

                    if (ht2 >= 0 && ts.canonicallyEquivalent(ht1, ht2)) {
                        return true
                    }
                } else if (ht2 == TypeIdxCode.FUNC.code()) {
                    return true
                }
            }

            return false
        }

        private fun isConcreteInAnyHierarchy(typeIdx: Int, ts: TypeSection): Boolean {
            val st = ts.getSubType(typeIdx)
            val comp = st.compType()
            return comp.structType() != null || comp.arrayType() != null
        }

        private fun isConcreteFunc(typeIdx: Int, ts: TypeSection): Boolean {
            val st = ts.getSubType(typeIdx)
            return st.compType().funcType() != null
        }

        fun matchesRef(t1: ValType, t2: ValType): Boolean = matchesRef(t1, t2, null)

        fun matchesRef(t1: ValType, t2: ValType, ts: TypeSection?): Boolean {
            if (!matchesNull(t1.isNullable(), t2.isNullable())) {
                return false
            }

            val ht1 = t1.typeIdx()
            val ht2 = t2.typeIdx()

            if (ht1 == ht2) {
                return true
            }

            return heapTypeSubtype(ht1, ht2, ts)
        }

        fun matches(t1: ValType, t2: ValType): Boolean = matches(t1, t2, null)

        fun matches(t1: ValType, t2: ValType, ts: TypeSection?): Boolean =
            if (t1.isReference() && t2.isReference()) {
                matchesRef(t1, t2, ts)
            } else if (t1.opcode() == ID.BOT) {
                true
            } else {
                t1.id() == t2.id()
            }

        fun isAbsHeapTypeIdx(typeIdx: Int): Boolean =
            typeIdx < 0 && typeIdx != TypeIdxCode.BOT.code()

        fun builder(): Builder = Builder.create()
    }
}
