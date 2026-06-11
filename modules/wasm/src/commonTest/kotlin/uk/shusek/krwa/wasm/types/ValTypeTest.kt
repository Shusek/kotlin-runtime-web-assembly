package uk.shusek.krwa.wasm.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValTypeTest {
    @Test
    fun roundtrip() {
        val cases =
            arrayOf(
                ValType.F64,
                ValType.F32,
                ValType.I64,
                ValType.I32,
                ValType.V128,
                ValType.FuncRef,
                ValType.ExternRef,
                ValType.builder()
                    .withOpcode(ValType.ID.RefNull)
                    .withTypeIdx(ValType.TypeIdxCode.FUNC.code())
                    .build(),
                ValType.builder()
                    .withOpcode(ValType.ID.Ref)
                    .withTypeIdx(ValType.TypeIdxCode.EXTERN.code())
                    .build(),
                ValType.builder().withOpcode(ValType.ID.Ref).withTypeIdx(16).build(),
            )

        for (valueType in cases) {
            val id = valueType.id()
            val roundTrip = ValType.builder().fromId(id).build()
            assertEquals(valueType, roundTrip, "Failed to roundtrip: $valueType")
        }
    }

    @Test
    fun noneRefMatchesAnyHierarchyReferenceTypes() {
        assertTrue(ValType.matches(ValType.NoneRef, ValType.AnyRef))
        assertTrue(ValType.matches(ValType.NoneRef, ValType.EqRef))
        assertTrue(ValType.matches(ValType.NoneRef, ValType.I31Ref))
        assertTrue(ValType.matches(ValType.NoneRef, ValType.StructRef))
        assertTrue(ValType.matches(ValType.NoneRef, ValType.ArrayRef))
        assertFalse(ValType.matches(ValType.NoneRef, ValType.FuncRef))
        assertFalse(ValType.matches(ValType.NoneRef, ValType.ExternRef))
    }
}
