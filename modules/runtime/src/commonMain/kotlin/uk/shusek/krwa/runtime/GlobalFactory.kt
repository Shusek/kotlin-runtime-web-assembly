package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.ValType

/** Factory for creating [GlobalInstance] objects during module instantiation. */
fun interface GlobalFactory {
    fun create(
        value: Long,
        highValue: Long,
        type: ValType,
        mutability: MutabilityType,
    ): GlobalInstance
}
