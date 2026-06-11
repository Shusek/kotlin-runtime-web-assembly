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
}
