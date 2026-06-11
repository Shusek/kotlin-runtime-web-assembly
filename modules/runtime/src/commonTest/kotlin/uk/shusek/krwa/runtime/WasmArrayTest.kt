package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WasmArrayTest {
    @Test
    fun byteBackedArrayStoresPackedValues() {
        val array = WasmArray(1, ByteArray(3))

        array.set(0, 255)
        array.set(1, 128)
        array.set(2, 7)

        assertEquals(3, array.length())
        assertContentEquals(longArrayOf(-1, -128, 7), array.elements())

        val copied = LongArray(5) { 99 }
        array.copyInto(copied, destinationOffset = 1, startIndex = 1, endIndex = 3)
        assertContentEquals(longArrayOf(99, -128, 7, 99, 99), copied)
    }

    @Test
    fun shortBackedArrayStoresPackedValues() {
        val array = WasmArray(1, ShortArray(3))

        array.set(0, 65535)
        array.set(1, 32768)
        array.set(2, 42)

        assertEquals(3, array.length())
        assertContentEquals(longArrayOf(-1, -32768, 42), array.elements())

        val copied = LongArray(4)
        array.copyInto(copied, destinationOffset = 1, startIndex = 0, endIndex = 2)
        assertContentEquals(longArrayOf(0, -1, -32768, 0), copied)
    }

    @Test
    fun longBackedArrayStillExposesBackingElements() {
        val elements = longArrayOf(1, 2, 3)
        val array = WasmArray(1, elements)

        array.set(1, 99)

        assertEquals(3, array.length())
        assertContentEquals(longArrayOf(1, 99, 3), array.elements())
        assertContentEquals(longArrayOf(1, 99, 3), elements)
    }
}
