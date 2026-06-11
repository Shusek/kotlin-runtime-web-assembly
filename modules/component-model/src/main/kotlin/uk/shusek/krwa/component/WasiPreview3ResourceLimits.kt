package uk.shusek.krwa.component

internal const val WASI_PREVIEW3_UNLIMITED_RESOURCES: Int = Int.MAX_VALUE

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
