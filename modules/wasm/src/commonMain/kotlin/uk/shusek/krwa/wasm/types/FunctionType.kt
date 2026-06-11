package uk.shusek.krwa.wasm.types

class FunctionType
private constructor(private val params: List<ValType>, private val returns: List<ValType>) {
    private val paramSlotCount = ValType.sizeOf(params)
    private val returnSlotCount = ValType.sizeOf(returns)

    fun params(): List<ValType> = params

    fun returns(): List<ValType> = returns

    fun paramSlotCount(): Int = paramSlotCount

    fun returnSlotCount(): Int = returnSlotCount

    fun paramsMatch(other: FunctionType): Boolean = params == other.params

    fun returnsMatch(other: FunctionType): Boolean = returns == other.returns

    override fun equals(other: Any?): Boolean = other is FunctionType && equals(other)

    fun equals(other: FunctionType): Boolean = paramsMatch(other) && returnsMatch(other)

    override fun hashCode(): Int = params.hashCode() * 31 + returns.hashCode()

    fun typesMatch(other: FunctionType): Boolean = paramsMatch(other) && returnsMatch(other)

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append('(')
        val nParams = params.size
        for (i in 0 until nParams) {
            builder.append(params[i].toString())
            if (i < nParams - 1) {
                builder.append(',')
            }
        }
        builder.append(") -> ")
        val nReturns = returns.size
        if (nReturns == 0) {
            builder.append("nil")
        } else {
            builder.append('(')
            for (i in 0 until nReturns) {
                builder.append(returns[i].toString())
                if (i < nReturns - 1) {
                    builder.append(',')
                }
            }
            builder.append(')')
        }
        return builder.toString()
    }

    companion object {
        private val EMPTY = FunctionType(emptyList(), emptyList())

        @WasmJvmStatic
        fun returning(valType: ValType): FunctionType =
            FunctionType(emptyList(), listOf(valType))

        @WasmJvmStatic
        fun accepting(valType: ValType): FunctionType =
            FunctionType(listOf(valType), emptyList())

        @WasmJvmStatic
        fun of(params: List<ValType>, returns: List<ValType>): FunctionType {
            if (params.isEmpty()) {
                if (returns.isEmpty()) {
                    return EMPTY
                }
                if (returns.size == 1) {
                    return returning(returns[0])
                }
            } else if (returns.isEmpty()) {
                if (params.size == 1) {
                    return accepting(params[0])
                }
            }
            return FunctionType(params.toList(), returns.toList())
        }

        @WasmJvmStatic
        fun of(params: Array<ValType>, returns: Array<ValType>): FunctionType =
            of(params.toList(), returns.toList())

        @WasmJvmStatic
        fun empty(): FunctionType = EMPTY
    }
}
