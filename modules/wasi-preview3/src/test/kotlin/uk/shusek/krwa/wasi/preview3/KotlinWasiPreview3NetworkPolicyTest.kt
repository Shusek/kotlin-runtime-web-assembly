package uk.shusek.krwa.wasi.preview3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KotlinWasiPreview3NetworkPolicyTest {
    @Test
    fun exactPolicyBuildsThroughPublicFacade() {
        val runtime = KotlinWasiPreview3.builder()
            .withNetworkPolicy(
                WasiNetworkPolicy(
                    httpEndpoints = setOf(
                        WasiHttpNetworkEndpoint(
                            protocol = WasiHttpNetworkProtocol.Https,
                            host = "API.Example.COM.",
                            port = 443,
                        ),
                    ),
                ),
            )
            .build()

        runtime.close()
    }

    @Test
    fun policyRejectsHostsOutsideCanonicalAsciiGrammar() {
        listOf(
            "żółw.example",
            "127.000.0.1",
            "256.0.0.1",
            "api_example.com",
            "[fe80::1%en0]",
        ).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                WasiHttpNetworkEndpoint(WasiHttpNetworkProtocol.Https, host, 443)
            }
        }
    }

    @Test
    fun policyRejectsCanonicalEndpointDuplicates() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            WasiNetworkPolicy(
                httpEndpoints = setOf(
                    WasiHttpNetworkEndpoint(WasiHttpNetworkProtocol.Https, "api.example.com", 443),
                    WasiHttpNetworkEndpoint(WasiHttpNetworkProtocol.Https, "API.EXAMPLE.COM.", 443),
                ),
            )
        }

        assertEquals(
            "WASI Preview 3 HTTP endpoints must be unique after host canonicalization",
            failure.message,
        )
    }
}
