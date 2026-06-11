package uk.shusek.krwa.wasm.types

class Global(
    private val valType: ValType,
    private val mutabilityType: MutabilityType,
    init: List<Instruction>,
) {
    private val init = init.toList()

    @Deprecated("use Global(ValType, MutabilityType, List<Instruction>)")
    constructor(
        valueType: ValueType,
        mutabilityType: MutabilityType,
        init: List<Instruction>,
    ) : this(valueType.toValType(), mutabilityType, init)

    fun mutabilityType(): MutabilityType = mutabilityType

    fun valueType(): ValType = valType

    fun initInstructions(): List<Instruction> = init

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Global) {
            return false
        }
        return valType == other.valType &&
            mutabilityType == other.mutabilityType &&
            init == other.init
    }

    override fun hashCode(): Int =
        (valType.hashCode() * 31 + mutabilityType.hashCode()) * 31 + init.hashCode()
}
