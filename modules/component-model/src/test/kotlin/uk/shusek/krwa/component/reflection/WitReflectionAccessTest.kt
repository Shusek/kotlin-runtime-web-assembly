package uk.shusek.krwa.component.reflection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import uk.shusek.krwa.component.WitReflection
import uk.shusek.krwa.component.canonicalAbiFieldValue
import uk.shusek.krwa.component.canonicalAbiResourceHandle

/**
 * WIT names may come from the plugin, so reflective binding must only ever reach the public surface
 * of a host object. This test lives outside `uk.shusek.krwa.component` so that access checks on
 * non-public host classes are real.
 */
class WitReflectionAccessTest {
    @Test
    fun hostHandlerBindsPublicMethodsOnly() {
        val host = LeakyHost()

        assertNull(WitReflection.hostHandler(listOf(host), "host", "secret"))
        assertNull(WitReflection.hostHandler(listOf(host), "host", "hidden-field"))
        assertNull(WitReflection.hostHandler(listOf(host), "host", "wait"))
        assertNull(WitReflection.hostHandler(listOf(host), "host", "hash-code"))

        val handler = WitReflection.hostHandler(listOf(host), "host", "log")
        assertNotNull(handler)
        handler!!.apply(listOf("hello"))
        assertEquals("hello", host.observed)
        assertEquals(0, host.secretCalls)
    }

    @Test
    fun nestedInterfaceObjectsRequirePublicAccessors() {
        assertNull(WitReflection.hostHandler(listOf(PrivateNestedHost()), "db", "read"))

        val handler = WitReflection.hostHandler(listOf(PublicNestedHost()), "db", "read")
        assertNotNull(handler)
        assertEquals(5, handler!!.apply(listOf(4)))
    }

    @Test
    fun anonymousHostObjectsStayBindable() {
        var observed: String? = null
        val host =
            object : Any() {
                @Suppress("unused")
                fun log(message: String) {
                    observed = message
                }
            }

        val handler = WitReflection.hostHandler(listOf(host), "host", "log")
        assertNotNull(handler)
        handler!!.apply(listOf("from guest"))
        assertEquals("from guest", observed)
    }

    @Test
    fun recordLoweringReadsPublicAccessorsOnly() {
        val value = LeakyRecord()

        assertEquals("ok", canonicalAbiFieldValue(value, "getVisible")?.value)
        assertEquals("open", canonicalAbiFieldValue(value, "open")?.value)
        assertNull(canonicalAbiFieldValue(value, "secret"))
        assertNull(canonicalAbiFieldValue(value, "getSecret"))
        assertNull(canonicalAbiFieldValue(value, "hiddenField"))
        assertNull(canonicalAbiFieldValue(value, "getClass"))
        assertNull(canonicalAbiFieldValue(value, "hashCode"))
        assertNull(canonicalAbiFieldValue(value, "toString"))
        assertEquals(0, value.secretCalls)
    }

    @Test
    fun resourceHandlesComeFromPublicMembersOnly() {
        assertEquals(7L, canonicalAbiResourceHandle(PublicHandle(7)))
        assertNull(canonicalAbiResourceHandle(PrivateHandle(7)))
    }

    private class LeakyHost {
        var observed: String? = null
        var secretCalls: Int = 0

        @Suppress("unused")
        private val hiddenField: String = "hidden"

        @Suppress("unused")
        fun log(message: String) {
            observed = message
        }

        @Suppress("unused")
        private fun secret(): String {
            secretCalls += 1
            return "s3cret"
        }

        @Suppress("unused")
        private fun hiddenField(): String = hiddenField
    }

    class Inner {
        @Suppress("unused")
        fun read(delta: Int): Int = delta + 1
    }

    private class PrivateNestedHost {
        @Suppress("unused")
        private val db: Inner = Inner()
    }

    private class PublicNestedHost {
        @Suppress("unused")
        val db: Inner = Inner()
    }

    private class LeakyRecord {
        var secretCalls: Int = 0

        @Suppress("unused")
        val visible: String = "ok"

        @Suppress("unused")
        private val hiddenField: String = "hidden"

        @Suppress("unused")
        fun open(): String = "open"

        @Suppress("unused")
        private fun secret(): String {
            secretCalls += 1
            return "s3cret"
        }

        @Suppress("unused")
        private fun getSecret(): String = secret()
    }

    private class PublicHandle(private val id: Long) {
        @Suppress("unused")
        fun handle(): Long = id
    }

    private class PrivateHandle(@Suppress("unused") private val handle: Long)
}
