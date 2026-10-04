package uk.shusek.krwa.component

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val WASI_PREVIEW3_UNLIMITED_RESOURCES: Int = Int.MAX_VALUE

/**
 * Default caps for canonical futures, streams, waitables and in-flight host tasks when the host does
 * not call `withResourceBudget`. They match the `WitResourceTable` default and keep a guest that
 * loops on `future.new` / `stream.new` from exhausting host memory.
 */
internal const val WASI_PREVIEW3_DEFAULT_MAX_PENDING: Int = 65_536

internal const val WASI_PREVIEW3_DEFAULT_MAX_WAITABLES: Int = 131_072

/**
 * Timeout applied to an outgoing `wasi:http` request when the guest supplies no request options.
 * Guests never have to set a timeout, so without a host default a plugin or a slow peer could park a
 * host thread indefinitely.
 */
internal val WASI_PREVIEW_DEFAULT_HTTP_TIMEOUT: Duration = 60.seconds

/** Upper bound for a guest-supplied `wasi:http` connect / first-byte timeout. */
internal val WASI_PREVIEW_MAX_HTTP_TIMEOUT: Duration = 10.minutes

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
