package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmLimits

open class TableLimits
constructor(min: Long, max: Long = LIMIT_MAX, private val shared: Boolean = false) {
    private var min: Long
    private val max: Long

    init {
        if (min > max) {
            throw InvalidException("size minimum must not be greater than maximum")
        }
        this.min = kotlin.math.min(kotlin.math.max(0, min), LIMIT_MAX)
        this.max = kotlin.math.min(max, LIMIT_MAX)
    }

    fun grow(size: Int) {
        min += size.toLong()
    }

    fun min(): Long = min

    fun max(): Long = max

    fun shared(): Boolean = shared

    override fun equals(other: Any?): Boolean = other is TableLimits && equals(other)

    fun equals(other: TableLimits?): Boolean =
        this === other || other != null && min == other.min && max == other.max

    override fun hashCode(): Int = min.hashCode() * 19 + max.hashCode()

    override fun toString(): String = toString(StringBuilder()).toString()

    fun toString(b: StringBuilder): StringBuilder {
        b.append("[").append(min).append(',')
        if (max == LIMIT_MAX) {
            b.append("max")
        } else {
            b.append(max)
        }
        return b.append(']')
    }

    companion object {
        val LIMIT_MAX: Long = WasmLimits.MAX_TABLE_ENTRIES.toLong()

        private val UNBOUNDED = TableLimits(0)

        fun unbounded(): TableLimits = UNBOUNDED
    }
}
