package uk.shusek.krwa.wasm.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import uk.shusek.krwa.wasm.InvalidException

class MemoryLimitsTest {
    @Test
    fun shouldCreateDefaultMemoryLimits() {
        val defaults = MemoryLimits.defaultLimits()
        assertNotNull(defaults)
        assertEquals(0, defaults.initialPages())
        assertEquals(MemoryLimits.MAX_PAGES, defaults.maximumPages())
    }

    @Test
    fun shouldThrowOnInvalidMemoryLimits() {
        assertFailsWith<InvalidException> { MemoryLimits(-1, -1) }
        assertFailsWith<InvalidException> { MemoryLimits(0, -1) }
        assertFailsWith<InvalidException> { MemoryLimits(2, 1) }
        assertFailsWith<InvalidException> { MemoryLimits(2, Int.MAX_VALUE) }
    }
}
