package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.InvalidException

class MemoryLimits
constructor(
    private val initial: Int,
    private val maximum: Int = MAX_PAGES,
    private val shared: Boolean = false,
) {
    init {
        if (initial > MAX_PAGES || maximum > MAX_PAGES || initial < 0 || maximum < 0) {
            throw InvalidException("memory size must be at most 65536 pages (4GiB)")
        }
        if (initial > maximum) {
            throw InvalidException("size minimum must not be greater than maximum")
        }
    }

    fun initialPages(): Int = initial

    fun maximumPages(): Int = maximum

    fun shared(): Boolean = shared

    override fun equals(other: Any?): Boolean = other is MemoryLimits && equals(other)

    fun equals(other: MemoryLimits?): Boolean =
        this === other || other != null && initial == other.initial && maximum == other.maximum

    override fun hashCode(): Int = maximum * 19 + initial

    override fun toString(): String = toString(StringBuilder()).toString()

    fun toString(b: StringBuilder): StringBuilder {
        b.append("[").append(initial).append(',')
        if (maximum == MAX_PAGES) {
            b.append("max")
        } else {
            b.append(maximum)
        }
        b.append(']')
        return b
    }

    companion object {
        const val MAX_PAGES: Int = 1 shl 16

        private val DEFAULT_LIMITS = MemoryLimits(0, MAX_PAGES)

        fun defaultLimits(): MemoryLimits = DEFAULT_LIMITS
    }
}
