package uk.shusek.krwa.wasm.types

enum class CatchOpCode(private val opcode: Int) {
    CATCH(0x00),
    CATCH_REF(0x01),
    CATCH_ALL(0x02),
    CATCH_ALL_REF(0x03);

    fun opcode(): Int = opcode

    class Catch
    private constructor(
        private val opcode: CatchOpCode,
        private val tag: Int,
        private val label: Int,
    ) {
        private var resolvedLabel = 0

        private constructor(opcode: CatchOpCode, label: Int) : this(opcode, -1, label) {
            assert(opcode == CATCH_ALL || opcode == CATCH_ALL_REF)
        }

        fun opcode(): CatchOpCode = opcode

        fun tag(): Int = tag

        fun label(): Int = label

        fun resolvedLabel(label: Int) {
            resolvedLabel = label
        }

        fun resolvedLabel(): Int = resolvedLabel

        companion object {
            fun forTag(opcode: CatchOpCode, tag: Int, label: Int): Catch {
                assert(tag == -1 || opcode == CATCH || opcode == CATCH_REF)
                return Catch(opcode, tag, label)
            }

            fun forAll(opcode: CatchOpCode, label: Int): Catch = Catch(opcode, label)
        }
    }

    companion object {
        private const val OP_CODES_SIZE = 4
        private val byOpCode = arrayOfNulls<CatchOpCode>(OP_CODES_SIZE)

        init {
            for (opcode in values()) {
                byOpCode[opcode.opcode] = opcode
            }
        }

        fun byOpCode(opcode: Int): CatchOpCode = byOpCode[opcode]!!

        fun decode(operands: LongArray): List<Catch> {
            val length = operands[1]
            val result = ArrayList<Catch>()
            var i = 2
            while (i < operands.size) {
                val catchEnum = byOpCode(operands[i++].toInt())
                when (catchEnum) {
                    CATCH,
                    CATCH_REF -> {
                        val tag = operands[i++].toInt()
                        val label = operands[i].toInt()
                        result.add(Catch.forTag(catchEnum, tag, label))
                    }
                    CATCH_ALL,
                    CATCH_ALL_REF -> {
                        val label = operands[i].toInt()
                        result.add(Catch.forAll(catchEnum, label))
                    }
                }
                i++
            }
            assert(result.size.toLong() == length)
            return result
        }

        fun allLabels(operands: LongArray): List<Int> =
            decode(operands).map { it.label() }
    }
}
