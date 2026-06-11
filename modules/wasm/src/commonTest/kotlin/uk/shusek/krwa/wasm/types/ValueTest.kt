package uk.shusek.krwa.wasm.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ValueTest {
    @Test
    fun shouldEncodeValuesFromLong() {
        val i32 = Value.i32(123L)
        assertEquals("123@i32", i32.toString())
        val i64 = Value.i64(123L)
        assertEquals("123@i64", i64.toString())
        val f32 = Value.f32(123L)
        assertEquals("1.72e-43@f32", f32.toString().lowercase())
        val f64 = Value.f64(123L)
        assertEquals("6.1e-322@f64", f64.toString().lowercase())
    }

    @Test
    fun shouldConvertFloats() {
        val f32Ref = 0.12345678f
        val f32 = Value.f32(1039980265L)
        assertEquals(f32Ref, f32.asFloat())
        assertEquals(f32.raw(), Value.fromFloat(f32Ref).raw())
        val f64Ref = 0.123456789012345
        val f64 = Value.f64(4593560419847042606L)
        assertEquals(f64Ref, f64.asDouble())
        assertEquals(f64.raw(), Value.fromDouble(f64Ref).raw())
    }

    @Test
    fun validConstruction() {
        Value(ValType.I32, 42L)
    }

    @Test
    fun equalsContract() {
        val i32FortyTwo = Value.i32(42)
        val i64FortyTwo = Value.i64(42L)
        val i32TwentyOne = Value.i32(21)
        val f32TwentyOne = Value.f32(21.0f.toRawBits().toLong())

        assertEquals(i32FortyTwo, i32FortyTwo)
        assertEquals(i32FortyTwo, Value.i32(42))
        assertNotEquals(i32FortyTwo, i32TwentyOne)
        assertNotNull(i32FortyTwo)
        assertNotEquals(i32TwentyOne, f32TwentyOne)
        assertNotEquals(i32FortyTwo, i64FortyTwo)
    }

    @Test
    fun hashCodeContract() {
        val i32FortyTwo = Value.i32(42)
        val i64FortyTwo = Value.i64(42L)

        assertEquals(i32FortyTwo.hashCode(), Value.i32(42).hashCode())
        assertNotEquals(i32FortyTwo.hashCode(), i64FortyTwo.hashCode())
    }

    @Test
    fun toStringContract() {
        val i32FortyTwo = Value.i32(42)
        assertNotNull(i32FortyTwo.toString())
    }

    @Test
    fun shouldConvertToArrays() {
        val x = 0x0706_0504_0302_0100L
        val result = Value.vecTo8(longArrayOf(x))

        assertEquals(8, result.size)
        assertEquals(0, result[0])
        assertEquals(1, result[1])
        assertEquals(2, result[2])
        assertEquals(3, result[3])
        assertEquals(4, result[4])
        assertEquals(5, result[5])
        assertEquals(6, result[6])
        assertEquals(7, result[7])
    }

    @Test
    fun shouldConvertToArraysHL() {
        val xLo = 0x0706_0504_0302_0100L
        val xHi = 0x0F0E_0D0C_0B0A_0908L
        val result = Value.vecTo8(longArrayOf(xLo, xHi))

        assertEquals(16, result.size)
        assertEquals(0, result[0])
        assertEquals(1, result[1])
        assertEquals(2, result[2])
        assertEquals(3, result[3])
        assertEquals(4, result[4])
        assertEquals(5, result[5])
        assertEquals(6, result[6])
        assertEquals(7, result[7])
        assertEquals(8, result[8])
        assertEquals(9, result[9])
        assertEquals(10, result[10])
        assertEquals(11, result[11])
        assertEquals(12, result[12])
        assertEquals(13, result[13])
        assertEquals(14, result[14])
        assertEquals(15, result[15])
    }

    @Test
    fun shouldConvertBackFromBytes() {
        val value = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        val result = Value.bytesToVec(value)
        val xLo = 0x0706_0504_0302_0100L
        val xHi = 0x0F0E_0D0C_0B0A_0908L

        assertEquals(2, result.size)
        assertEquals(xLo, result[0])
        assertEquals(xHi, result[1])
    }

    @Test
    fun i8ToVec() {
        val result =
            Value.i8ToVec(longArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
        val xLo = 0x0706_0504_0302_0100L
        val xHi = 0x0F0E_0D0C_0B0A_0908L

        assertEquals(2, result.size)
        assertEquals(xLo, result[0])
        assertEquals(xHi, result[1])
    }

    @Test
    fun i16ToVec() {
        val result = Value.i16ToVec(longArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        val xLo = 0x0003_0002_0001_0000L
        val xHi = 0x0007_0006_0005_0004L

        assertEquals(2, result.size)
        assertEquals(xLo, result[0])
        assertEquals(xHi, result[1])
    }

    @Test
    fun i32ToVec() {
        val values = longArrayOf(0xAAAA_AAAAL, 0xBBBB_BBBBL, 0xCCCC_CCCCL, 0xDDDD_DDDDL)
        val result = Value.i32ToVec(values)
        val xLo = 0xBBBB_BBBB_AAAA_AAAAuL.toLong()
        val xHi = 0xDDDD_DDDD_CCCC_CCCCuL.toLong()

        assertEquals(xLo, result[0])
        assertEquals(xHi, result[1])
    }
}
