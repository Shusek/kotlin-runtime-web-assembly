package uk.shusek.krwa.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.types.MemoryLimits

/**
 * The runtime supports at most [Memory.RUNTIME_MAX_PAGES] pages per memory. A module declaring more
 * initial pages must fail instantiation cleanly instead of indexing past the page table.
 */
class RuntimeMemoryLimitTest {
    @Test
    fun byteBufferMemoryRejectsInitialSizesAboveTheRuntimeLimit() {
        assertThrows(UninstantiableException::class.java) {
            ByteBufferMemory(MemoryLimits(Memory.RUNTIME_MAX_PAGES + 1))
        }

        val memory = ByteBufferMemory(MemoryLimits(1, Memory.RUNTIME_MAX_PAGES + 1))
        assertEquals(1, memory.pages())
    }

    @Test
    fun byteArrayMemoryRejectsInitialSizesAboveTheRuntimeLimit() {
        assertThrows(UninstantiableException::class.java) {
            ByteArrayMemory(MemoryLimits(Memory.RUNTIME_MAX_PAGES + 1))
        }

        val memory = ByteArrayMemory(MemoryLimits(1, Memory.RUNTIME_MAX_PAGES + 1))
        assertEquals(1, memory.pages())
    }
}
