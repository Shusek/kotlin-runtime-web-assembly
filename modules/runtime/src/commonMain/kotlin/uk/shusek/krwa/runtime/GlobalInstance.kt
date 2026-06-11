@file:Suppress("DEPRECATION")

package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value
import uk.shusek.krwa.wasm.types.ValueType

open class GlobalInstance(
    var valueLow: Long,
    var valueHigh: Long,
    val type: ValType,
    val mutabilityType: MutabilityType,
) {
    var instance: Instance? = null

    var value: Long
        get() = valueLow
        set(value) {
            valueLow = value
        }

    constructor(value: Value) : this(value, MutabilityType.Const)

    constructor(
        value: Value,
        mutabilityType: MutabilityType,
    ) : this(value.raw(), 0, value.type(), mutabilityType)

    /** @deprecated use [GlobalInstance] with [ValType]. */
    @Deprecated("Use GlobalInstance(long, long, ValType, MutabilityType).")
    constructor(
        valueLow: Long,
        valueHigh: Long,
        valueType: ValueType,
        mutabilityType: MutabilityType,
    ) : this(valueLow, valueHigh, valueType.toValType(), mutabilityType)

    fun setValue(value: Value) {
        // globals can not be type polymorphic
        assert(value.type() == type)
        valueLow = value.raw()
    }
}
