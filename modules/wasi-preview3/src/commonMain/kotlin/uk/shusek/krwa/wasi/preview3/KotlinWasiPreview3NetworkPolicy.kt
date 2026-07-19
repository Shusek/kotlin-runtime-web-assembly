package uk.shusek.krwa.wasi.preview3

import uk.shusek.krwa.runtime.canonicalizeExactNetworkHost

public data class WasiNetworkEndpoint(
    val host: String,
    val port: Int,
) {
    internal val normalizedHost: String = canonicalizeExactNetworkHost(host)

    init {
        require(port in 0..65_535) {
            "WASI Preview 3 raw-socket endpoint port must be between 0 and 65535"
        }
    }
}

public enum class WasiHttpNetworkProtocol {
    Http,
    Https,
}

public data class WasiHttpNetworkEndpoint(
    val protocol: WasiHttpNetworkProtocol,
    val host: String,
    val port: Int,
) {
    internal val normalizedHost: String = canonicalizeExactNetworkHost(host)

    init {
        require(port in 1..65_535) {
            "WASI Preview 3 HTTP endpoint port must be between 1 and 65535"
        }
    }
}

/**
 * Exact network capabilities for [KotlinWasiPreview3].
 *
 * Empty sets deny all networking. HTTP scheme, canonical host, and port are matched exactly; raw
 * socket grants are independent.
 */
public class WasiNetworkPolicy(
    httpEndpoints: Set<WasiHttpNetworkEndpoint> = emptySet(),
    rawSocketEndpoints: Set<WasiNetworkEndpoint> = emptySet(),
) {
    private val configuredHttpEndpoints = LinkedHashSet(httpEndpoints)
    private val configuredRawSocketEndpoints = LinkedHashSet(rawSocketEndpoints)

    public val httpEndpoints: Set<WasiHttpNetworkEndpoint>
        get() = configuredHttpEndpoints.toSet()

    public val rawSocketEndpoints: Set<WasiNetworkEndpoint>
        get() = configuredRawSocketEndpoints.toSet()

    init {
        require(
            configuredHttpEndpoints
                .map { endpoint -> Triple(endpoint.protocol, endpoint.normalizedHost, endpoint.port) }
                .distinct()
                .size == configuredHttpEndpoints.size,
        ) {
            "WASI Preview 3 HTTP endpoints must be unique after host canonicalization"
        }
        require(
            configuredRawSocketEndpoints
                .map { endpoint -> endpoint.normalizedHost to endpoint.port }
                .distinct()
                .size == configuredRawSocketEndpoints.size,
        ) {
            "WASI Preview 3 raw-socket endpoints must be unique after host canonicalization"
        }
    }

    public companion object {
        public val DENY_ALL: WasiNetworkPolicy = WasiNetworkPolicy()
    }
}
