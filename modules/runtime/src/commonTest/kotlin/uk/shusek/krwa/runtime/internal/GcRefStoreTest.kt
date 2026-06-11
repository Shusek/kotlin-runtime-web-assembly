package uk.shusek.krwa.runtime.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmGcRef
import uk.shusek.krwa.wasm.WasmParser

class GcRefStoreTest {
    @Test
    fun autoAssignKeysFromOffset() {
        val store = newStore()
        val k0 = store.put(ref(0))
        val k1 = store.put(ref(1))
        val k2 = store.put(ref(2))
        assertEquals(GcRefStore.ID_OFFSET, k0)
        assertEquals(GcRefStore.ID_OFFSET + 1, k1)
        assertEquals(GcRefStore.ID_OFFSET + 2, k2)
        assertEquals(0, store[k0]!!.typeIdx())
        assertEquals(1, store[k1]!!.typeIdx())
        assertEquals(2, store[k2]!!.typeIdx())
    }

    @Test
    fun getMissingKeyReturnsNull() {
        val store = newStore()
        assertNull(store[0])
        assertNull(store[999])
    }

    @Test
    fun isGcRefIdClassifiesCorrectly() {
        assertTrue(GcRefStore.isGcRefId(GcRefStore.ID_OFFSET.toLong()))
        assertTrue(GcRefStore.isGcRefId((GcRefStore.ID_OFFSET + 100).toLong()))
        assertFalse(GcRefStore.isGcRefId(0))
        assertFalse(GcRefStore.isGcRefId((GcRefStore.ID_OFFSET - 1).toLong()))
    }

    @Test
    fun safePointKeepsRefsUntilAsyncRootsAreTracked() {
        val store = newStore()
        val key = store.put(ref(7))
        repeat(4096) { store.put(ref(it)) }

        store.safePoint()

        assertEquals(7, store[key]!!.typeIdx())
    }

    private fun ref(typeIdx: Int): WasmGcRef = WasmGcRef { typeIdx }

    private fun newStore(): GcRefStore {
        val module = WasmParser.parse(EMPTY_WASM)
        val instance = Instance.builder(module).build()
        return GcRefStore(instance)
    }

    private companion object {
        val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
