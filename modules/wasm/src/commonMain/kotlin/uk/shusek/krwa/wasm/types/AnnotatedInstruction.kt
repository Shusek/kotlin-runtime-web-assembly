package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.InvalidException

class AnnotatedInstruction
private constructor(
    address: Int,
    opcode: OpCode,
    operands: LongArray,
    private val depth: Int,
    private val labelTrue: Int,
    private val labelFalse: Int,
    private val labelTable: List<Int>,
    private val catches: List<CatchOpCode.Catch>?,
    private val scope: Instruction?,
) : Instruction(address, opcode, operands) {

    fun labelTrue(): Int = labelTrue

    fun labelFalse(): Int = labelFalse

    fun labelTable(): List<Int> = labelTable

    fun catches(): List<CatchOpCode.Catch>? = catches

    fun depth(): Int = depth

    fun scope(): Instruction? = scope

    override fun toString(): String =
        "AnnotatedInstruction{" +
            "instruction=" +
            super.toString() +
            ", depth=" +
            depth +
            ", labelTrue=" +
            labelTrue +
            ", labelFalse=" +
            labelFalse +
            ", labelTable=" +
            labelTable +
            ", catches=" +
            catches +
            ", scope=" +
            scope +
            '}'

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AnnotatedInstruction) {
            return false
        }
        return depth == other.depth &&
            labelTrue == other.labelTrue &&
            labelFalse == other.labelFalse &&
            labelTable == other.labelTable &&
            catches == other.catches &&
            scope == other.scope
    }

    override fun hashCode(): Int =
        (((((depth * 31 + labelTrue) * 31 + labelFalse) * 31 + labelTable.hashCode()) * 31 +
            (catches?.hashCode() ?: 0)) * 31 + (scope?.hashCode() ?: 0))

    class Builder {
        private var base: Instruction? = null
        private var depth = 0
        private var labelTrue: Int? = null
        private var labelFalse: Int? = null
        private var labelTable: List<Int>? = null
        private var catches: List<CatchOpCode.Catch>? = null
        private var scope: Instruction? = null

        fun opcode(): OpCode = base!!.opcode()

        fun scope(): Instruction? = scope

        fun from(ins: Instruction): Builder {
            base = ins
            return this
        }

        fun withDepth(depth: Int): Builder {
            this.depth = depth
            return this
        }

        fun withLabelTrue(label: Int): Builder {
            labelTrue = label
            return this
        }

        fun withLabelFalse(label: Int): Builder {
            labelFalse = label
            return this
        }

        fun updateLabelFalse(label: Int): Builder {
            if (labelFalse == labelTrue) {
                labelFalse = label
            }
            return this
        }

        fun withLabelTable(labelTable: List<Int>): Builder {
            this.labelTable = labelTable
            return this
        }

        fun withCatches(catches: List<CatchOpCode.Catch>): Builder {
            this.catches = catches
            return this
        }

        fun withScope(scope: Instruction): Builder {
            this.scope = scope
            return this
        }

        fun build(): AnnotatedInstruction {
            val base = base!!
            when (base.opcode()) {
                OpCode.BLOCK,
                OpCode.LOOP,
                OpCode.END,
                OpCode.IF,
                OpCode.TRY_TABLE -> assert(scope != null)

                else -> assert(scope == null)
            }
            when (base.opcode()) {
                OpCode.BR_IF,
                OpCode.BR_ON_NULL,
                OpCode.BR_ON_NON_NULL,
                OpCode.BR_ON_CAST,
                OpCode.BR_ON_CAST_FAIL,
                OpCode.IF -> {
                    if (labelFalse == null) {
                        throw InvalidException("unknown label $base")
                    }
                    if (labelTrue == null) {
                        throw InvalidException("unknown label $base")
                    }
                }

                OpCode.ELSE,
                OpCode.BR -> {
                    if (labelTrue == null) {
                        throw InvalidException("unknown label $base")
                    }
                }

                else -> {
                    assert(labelTrue == null)
                    assert(labelFalse == null)
                }
            }
            when (base.opcode()) {
                OpCode.BR_TABLE -> {
                    if (labelTable == null) {
                        throw InvalidException("unknown label table $base")
                    }
                }

                else -> assert(labelTable == null)
            }
            when (base.opcode()) {
                OpCode.TRY_TABLE -> {
                    if (catches == null) {
                        throw InvalidException("unknown catches $base")
                    }
                }

                else -> assert(catches == null)
            }

            return AnnotatedInstruction(
                base.address(),
                base.opcode(),
                base.operands(),
                depth,
                labelTrue ?: UNDEFINED_LABEL,
                labelFalse ?: UNDEFINED_LABEL,
                labelTable ?: emptyList(),
                catches,
                scope,
            )
        }
    }

    companion object {
        const val UNDEFINED_LABEL: Int = -1

        fun builder(): Builder = Builder()
    }
}
