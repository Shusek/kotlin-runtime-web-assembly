package uk.shusek.krwa.wasm.types

open class Table(
    private val elementType: ValType,
    private val limits: TableLimits,
    private val init: List<Instruction>,
) {
    constructor(
        elementType: ValType,
        limits: TableLimits,
    ) : this(
        elementType,
        limits,
        listOf(Instruction(-1, OpCode.REF_NULL, longArrayOf(elementType.typeIdx().toLong()))),
    )

    @Deprecated("use Table(ValType, TableLimits)")
    constructor(
        elementType: ValueType,
        limits: TableLimits,
    ) : this(
        elementType.toValType(),
        limits,
        listOf(
            Instruction(
                -1,
                OpCode.REF_NULL,
                longArrayOf(elementType.toValType().typeIdx().toLong()),
            )
        ),
    )

    init {
        if (!elementType.isReference()) {
            throw IllegalArgumentException("Table element type must be a reference type")
        }
    }

    fun elementType(): ValType = elementType

    fun limits(): TableLimits = limits

    fun initialize(): List<Instruction> = init

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Table) {
            return false
        }
        return elementType == other.elementType && limits == other.limits
    }

    override fun hashCode(): Int = elementType.hashCode() * 31 + limits.hashCode()
}
