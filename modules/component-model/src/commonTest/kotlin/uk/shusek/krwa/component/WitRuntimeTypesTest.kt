package uk.shusek.krwa.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WitRuntimeTypesTest {
    @Test
    fun witValueKeepsHelpersAndVariantShape() {
        val record = WitValue.record("body", byteArrayOf(1, 2, 3), "content-type", "text/plain")
        val flags = WitValue.flags("read", "write")
        val none = WitValue.none()
        val some = WitValue.some("payload")

        assertEquals(listOf("body", "content-type"), record.keys.toList())
        assertEquals(byteArrayOf(1, 2, 3).toList(), (record["body"] as ByteArray).toList())
        assertEquals(mapOf("read" to true, "write" to true), flags)
        assertEquals("none", none.label())
        assertFalse(none.hasValue())
        assertEquals("none", none.toString())
        assertEquals("some", some.label())
        assertEquals("payload", some.value())
        assertTrue(some.hasValue())
        assertEquals("some(payload)", some.toString())
        assertFailsWith<IllegalArgumentException> { WitValue.record("orphan") }
    }

    @Test
    fun resourceTableKeepsHandleLifecycle() {
        val table = WitResourceTable<String>()
        val resource = table.insert("value")

        assertEquals(1L, resource.handle())
        assertTrue(table.contains(resource))
        assertEquals(1, table.size())
        assertEquals("value", table.get(resource))
        assertEquals("value", table.remove(resource))
        assertFalse(table.contains(resource))
        assertEquals(0, table.size())
        assertFailsWith<ComponentModelException> { table.get(resource) }
        assertFailsWith<ComponentModelException> { table.remove(resource) }
    }

    @Test
    fun resourceTableRejectsNullValues() {
        val table = WitResourceTable<String?>()

        assertFailsWith<NullPointerException> { table.insert(null) }
    }

    @Test
    fun resourceTableEnforcesItsEntryLimitWithoutPartialBatchInsertion() {
        assertFailsWith<IllegalArgumentException> { WitResourceTable<String>(0) }

        val table = WitResourceTable<String>(2)
        val first = table.insert("first")

        assertFailsWith<ComponentModelException> {
            table.insertResourceHandles(listOf("second", "third"))
        }
        assertEquals(listOf("first"), table.snapshot())

        val second = table.insert("second")
        assertFailsWith<ComponentModelException> { table.insert("third") }

        assertEquals("first", table.remove(first))
        assertEquals(3L, table.insert("third").handle())
        assertEquals(listOf("second", "third"), table.snapshot())
        assertTrue(table.contains(second))
    }

    @Test
    fun resourceTableDrainKeepsTableOpenWhileCloseRejectsNewEntries() {
        val table = WitResourceTable<String>(3)
        table.insert("first")
        table.insert("second")

        assertEquals(listOf("first", "second"), table.drain())
        assertEquals(0, table.size())

        table.insert("third")
        assertEquals(listOf("third"), table.close())
        assertEquals(emptyList(), table.close())
        assertEquals(0, table.size())
        assertFailsWith<ComponentModelException> { table.insert("after-close") }
    }

    @Test
    fun wasiPreviewLockSupportsRecursiveEntry() {
        val lock = WasiPreviewLock()

        assertEquals(
            "locked",
            withWasiPreviewLock(lock) {
                withWasiPreviewLock(lock) {
                    "locked"
                }
            },
        )
    }
}
