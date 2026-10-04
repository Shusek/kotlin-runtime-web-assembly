package uk.shusek.krwa.component

internal const val WASI_PREVIEW3_UNLIMITED_RESOURCES: Int = Int.MAX_VALUE

/**
 * Largest byte count a guest may request from a single host call that allocates the result on the
 * host before any I/O happens (`random.get-random-bytes`, `descriptor.read`, ...). WASI encodes
 * these lengths as `u64`; without a cap a guest can force a 2 GiB allocation per call.
 */
internal const val WASI_PREVIEW_MAX_GUEST_BYTE_REQUEST: Int = 64 * 1024 * 1024

/** Upper bound for an outgoing HTTP request body buffered on the host before it is sent. */
internal const val WASI_PREVIEW_MAX_HTTP_REQUEST_BODY_BYTES: Long = 64L * 1024L * 1024L

internal fun requireWasiPreview3Limit(name: String, value: Int): Int {
    if (value <= 0) {
        throw IllegalArgumentException("$name must be positive")
    }
    return value
}

internal fun requireWasiPreview3Capacity(name: String, current: Int, requested: Int, limit: Int) {
    if (requested <= 0) {
        return
    }
    if (current > limit - requested) {
        throw ComponentModelException(
            "WASI Preview 3 $name limit exceeded: requested $requested, current $current, limit $limit"
        )
    }
}
