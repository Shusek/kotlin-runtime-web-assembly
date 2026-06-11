package uk.shusek.krwa.runtime.alloc

/**
 * Strategy for allocating memory buffers.
 *
 * @deprecated Memory is now allocated by page (64KB each), so custom allocation strategies are no
 *   longer used. This interface will be removed in a future release.
 */
@Deprecated(
    "Memory is now allocated by page (64KB each), so custom allocation strategies are no longer used. This interface will be removed in a future release."
)
interface MemAllocStrategy {
    fun initial(min: Int): Int

    fun next(current: Int, target: Int): Int
}
