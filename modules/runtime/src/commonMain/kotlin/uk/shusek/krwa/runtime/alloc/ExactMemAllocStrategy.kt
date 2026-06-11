package uk.shusek.krwa.runtime.alloc

/**
 * Memory allocation strategy that allocates exactly the requested size.
 *
 * @deprecated Memory is now allocated by page (64KB each), so custom allocation strategies are no
 *   longer used.
 */
@Deprecated(
    "Memory is now allocated by page (64KB each), so custom allocation strategies are no longer used."
)
@Suppress("DEPRECATION")
class ExactMemAllocStrategy : MemAllocStrategy {
    override fun initial(min: Int): Int = min

    override fun next(current: Int, target: Int): Int = target
}
