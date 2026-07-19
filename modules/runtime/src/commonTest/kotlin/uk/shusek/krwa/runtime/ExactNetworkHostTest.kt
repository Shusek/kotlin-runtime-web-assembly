package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactNetworkHostTest {
    @Test
    fun canonicalizesAsciiIpLiterals() {
        assertEquals("127.0.0.1", canonicalizeExactNetworkHost("127.0.0.1"))
        assertEquals(
            "2001:db8:0:0:0:0:0:1",
            canonicalizeExactNetworkHost("[2001:0DB8::1]"),
        )
    }

    @Test
    fun rejectsUnicodeDigitsInIpv4Literals() {
        listOf(
            "١٢٧.٠.٠.١",
            "１２７.０.０.１",
            "1٢7.0.0.1",
        ).forEach { host ->
            assertFailsWith<IllegalArgumentException>(host) {
                canonicalizeExactNetworkHost(host)
            }
        }
    }

    @Test
    fun rejectsUnicodeDigitsInIpv6Literals() {
        listOf(
            "200١:db8::1",
            "٢٠٠١:db8::1",
            "::ffff:١٢٧.0.0.1",
            "::ffff:１２７.０.０.１",
        ).forEach { host ->
            assertFailsWith<IllegalArgumentException>(host) {
                canonicalizeExactNetworkHost(host)
            }
        }
    }
}
