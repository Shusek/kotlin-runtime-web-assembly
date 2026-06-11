package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.InvalidException

class TypeSection private constructor(types: List<RecType>) : Section(SectionId.TYPE.toLong()) {
    private val types = types.toList()
    private val flattenedSubTypes: Array<SubType>
    private val recGroupBase: IntArray
    private val recGroupSize: IntArray

    init {
        val flatList = ArrayList<SubType>()
        val baseList = ArrayList<Int>()
        val sizeList = ArrayList<Int>()
        var base = 0
        for (type in this.types) {
            val groupSize = type.subTypes().size
            for (subType in type.subTypes()) {
                flatList.add(subType)
                baseList.add(base)
                sizeList.add(groupSize)
            }
            base += groupSize
        }
        flattenedSubTypes = flatList.toTypedArray()
        recGroupBase = baseList.toIntArray()
        recGroupSize = sizeList.toIntArray()
    }

    fun canonicallyEquivalent(idx1: Int, idx2: Int): Boolean {
        if (idx1 == idx2) {
            return true
        }
        return crossModuleCanonicallyEquivalent(this, idx1, this, idx2)
    }

    @Suppress("UNCHECKED_CAST")
    fun types(): Array<FunctionType> {
        val result = arrayOfNulls<FunctionType>(flattenedSubTypes.size)
        for (i in flattenedSubTypes.indices) {
            result[i] = flattenedSubTypes[i].compType().funcType()
        }
        return result as Array<FunctionType>
    }

    fun typeCount(): Int = types.size

    fun subTypeCount(): Int = flattenedSubTypes.size

    fun getType(idx: Int): FunctionType = getSubType(idx).compType().funcType()!!

    fun getRecType(idx: Int): RecType = types[idx]

    fun getSubType(idx: Int): SubType {
        if (idx < 0 || idx >= flattenedSubTypes.size) {
            throw InvalidException("unknown type $idx")
        }
        return flattenedSubTypes[idx]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is TypeSection) {
            return false
        }
        return types == other.types
    }

    override fun hashCode(): Int = types.hashCode()

    class Builder {
        private val types = ArrayList<RecType>()

        @Deprecated("")
        fun getTypes(): List<FunctionType> = types.filter { it.isLegacy() }.map { it.legacy() }

        fun addFunctionType(functionType: FunctionType): Builder {
            val type =
                RecType.builder()
                    .withSubTypes(
                        arrayOf(
                            SubType.builder()
                                .withTypeIdx(intArrayOf())
                                .withFinal(true)
                                .withCompType(CompType.builder().withFuncType(functionType).build())
                                .build()
                        )
                    )
                    .build()
            types.add(type)
            return this
        }

        fun addRecType(recType: RecType): Builder {
            types.add(recType)
            return this
        }

        fun build(): TypeSection = TypeSection(types)
    }

    companion object {
        fun crossModuleCanonicallyEquivalent(
            ts1: TypeSection,
            idx1: Int,
            ts2: TypeSection,
            idx2: Int,
        ): Boolean {
            if (ts1 === ts2 && idx1 == idx2) {
                return true
            }
            if (
                idx1 < 0 ||
                    idx1 >= ts1.flattenedSubTypes.size ||
                    idx2 < 0 ||
                    idx2 >= ts2.flattenedSubTypes.size
            ) {
                return false
            }
            val base1 = ts1.recGroupBase[idx1]
            val size1 = ts1.recGroupSize[idx1]
            val base2 = ts2.recGroupBase[idx2]
            val size2 = ts2.recGroupSize[idx2]
            if (size1 != size2) {
                return false
            }
            if (idx1 - base1 != idx2 - base2) {
                return false
            }
            for (i in 0 until size1) {
                if (
                    !subtypeCanonicallyEquals(
                        ts1,
                        ts1.flattenedSubTypes[base1 + i],
                        base1,
                        size1,
                        ts2,
                        ts2.flattenedSubTypes[base2 + i],
                        base2,
                        size2,
                    )
                ) {
                    return false
                }
            }
            return true
        }

        private fun subtypeCanonicallyEquals(
            ts1: TypeSection,
            st1: SubType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            st2: SubType,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (st1.isFinal() != st2.isFinal()) {
                return false
            }
            val supers1 = st1.typeIdx()
            val supers2 = st2.typeIdx()
            if (supers1.size != supers2.size) {
                return false
            }
            for (i in supers1.indices) {
                if (
                    !refIdxCanonicallyEquals(
                        ts1,
                        supers1[i],
                        base1,
                        size1,
                        ts2,
                        supers2[i],
                        base2,
                        size2,
                    )
                ) {
                    return false
                }
            }
            return compTypeCanonicallyEquals(
                ts1,
                st1.compType(),
                base1,
                size1,
                ts2,
                st2.compType(),
                base2,
                size2,
            )
        }

        private fun compTypeCanonicallyEquals(
            ts1: TypeSection,
            ct1: CompType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            ct2: CompType,
            base2: Int,
            size2: Int,
        ): Boolean {
            val ft1 = ct1.funcType()
            val ft2 = ct2.funcType()
            if (ft1 != null && ft2 != null) {
                return funcTypeCanonicallyEquals(ts1, ft1, base1, size1, ts2, ft2, base2, size2)
            }
            val st1 = ct1.structType()
            val st2 = ct2.structType()
            if (st1 != null && st2 != null) {
                return structTypeCanonicallyEquals(ts1, st1, base1, size1, ts2, st2, base2, size2)
            }
            val at1 = ct1.arrayType()
            val at2 = ct2.arrayType()
            if (at1 != null && at2 != null) {
                return fieldTypeCanonicallyEquals(
                    ts1,
                    at1.fieldType(),
                    base1,
                    size1,
                    ts2,
                    at2.fieldType(),
                    base2,
                    size2,
                )
            }
            return false
        }

        private fun funcTypeCanonicallyEquals(
            ts1: TypeSection,
            ft1: FunctionType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            ft2: FunctionType,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (
                ft1.params().size != ft2.params().size || ft1.returns().size != ft2.returns().size
            ) {
                return false
            }
            for (i in ft1.params().indices) {
                if (
                    !valTypeCanonicallyEquals(
                        ts1,
                        ft1.params()[i],
                        base1,
                        size1,
                        ts2,
                        ft2.params()[i],
                        base2,
                        size2,
                    )
                ) {
                    return false
                }
            }
            for (i in ft1.returns().indices) {
                if (
                    !valTypeCanonicallyEquals(
                        ts1,
                        ft1.returns()[i],
                        base1,
                        size1,
                        ts2,
                        ft2.returns()[i],
                        base2,
                        size2,
                    )
                ) {
                    return false
                }
            }
            return true
        }

        private fun structTypeCanonicallyEquals(
            ts1: TypeSection,
            st1: StructType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            st2: StructType,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (st1.fieldTypes().size != st2.fieldTypes().size) {
                return false
            }
            for (i in st1.fieldTypes().indices) {
                if (
                    !fieldTypeCanonicallyEquals(
                        ts1,
                        st1.fieldTypes()[i],
                        base1,
                        size1,
                        ts2,
                        st2.fieldTypes()[i],
                        base2,
                        size2,
                    )
                ) {
                    return false
                }
            }
            return true
        }

        private fun fieldTypeCanonicallyEquals(
            ts1: TypeSection,
            f1: FieldType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            f2: FieldType,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (f1.mut() != f2.mut()) {
                return false
            }
            val s1 = f1.storageType()
            val s2 = f2.storageType()
            if (s1.packedType() != null || s2.packedType() != null) {
                return s1.packedType() == s2.packedType()
            }
            return valTypeCanonicallyEquals(
                ts1,
                s1.valType()!!,
                base1,
                size1,
                ts2,
                s2.valType()!!,
                base2,
                size2,
            )
        }

        private fun valTypeCanonicallyEquals(
            ts1: TypeSection,
            v1: ValType,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            v2: ValType,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (v1.opcode() != v2.opcode()) {
                return false
            }
            if (!v1.isReference()) {
                return true
            }
            return refIdxCanonicallyEquals(
                ts1,
                v1.typeIdx(),
                base1,
                size1,
                ts2,
                v2.typeIdx(),
                base2,
                size2,
            )
        }

        private fun refIdxCanonicallyEquals(
            ts1: TypeSection,
            idx1: Int,
            base1: Int,
            size1: Int,
            ts2: TypeSection,
            idx2: Int,
            base2: Int,
            size2: Int,
        ): Boolean {
            if (idx1 < 0 && idx2 < 0) {
                return idx1 == idx2
            }
            val inGroup1 = idx1 >= base1 && idx1 < base1 + size1
            val inGroup2 = idx2 >= base2 && idx2 < base2 + size2
            if (inGroup1 && inGroup2) {
                return idx1 - base1 == idx2 - base2
            }
            if (inGroup1 || inGroup2) {
                return false
            }
            return crossModuleCanonicallyEquivalent(ts1, idx1, ts2, idx2)
        }

        fun builder(): Builder = Builder()
    }
}
