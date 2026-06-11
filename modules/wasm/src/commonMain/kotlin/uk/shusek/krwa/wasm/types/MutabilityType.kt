package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.MalformedException

/** The kind of mutability */
enum class MutabilityType(private val id: Int) {
    Const(ID.Const),
    Var(ID.Var);

    /** @return the numerical identifier for this type */
    fun id(): Int = id

    class ID private constructor() {
        companion object {
            const val Const = 0x00
            const val Var = 0x01
        }
    }

    companion object {
        /**
         * @return the [MutabilityType] for the given ID value
         * @throws IllegalArgumentException if the ID value does not correspond to a valid
         *   mutability type
         */
        fun forId(id: Int): MutabilityType =
            when (id) {
                ID.Const -> Const
                ID.Var -> Var
                else -> throw MalformedException("malformed mutability $id")
            }
    }
}
