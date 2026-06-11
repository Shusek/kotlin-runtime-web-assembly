package uk.shusek.krwa.runtime.alloc

/**
 * Default memory allocation strategy that doubles the buffer size on growth.
 *
 * @deprecated Memory is now allocated by page (64KB each), so custom allocation strategies are no
 *   longer used.
 */
@Deprecated(
    "Memory is now allocated by page (64KB each), so custom allocation strategies are no longer used."
)
@Suppress("DEPRECATION")
class DefaultMemAllocStrategy(private val max: Int) : MemAllocStrategy {
    override fun initial(min: Int): Int = min

    override fun next(current: Int, target: Int): Int {
        var next = if (current <= 0) target else current
        while (next < target && next < max) {
            next = next shl 1
            if (next < 0) {
                return max
            }
        }
        return max.coerceAtMost(next)
    }
}
