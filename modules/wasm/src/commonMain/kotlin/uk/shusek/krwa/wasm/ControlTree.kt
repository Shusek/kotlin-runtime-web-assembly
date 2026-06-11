package uk.shusek.krwa.wasm

import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.OpCode

/** Provides a control structure to label branches in a list of instructions. */
class ControlTree {
    private val instruction: AnnotatedInstruction.Builder?
    private val initialInstructionNumber: Int
    private val parent: ControlTree?
    private val nested: MutableList<ControlTree>
    private val callbacks: MutableList<(Int) -> Unit>

    constructor() {
        instruction = null
        initialInstructionNumber = 0
        parent = null
        nested = ArrayList()
        callbacks = ArrayList()
    }

    private constructor(
        initialInstructionNumber: Int,
        instruction: AnnotatedInstruction.Builder,
        parent: ControlTree,
    ) {
        this.instruction = instruction
        this.initialInstructionNumber = initialInstructionNumber
        this.parent = parent
        nested = ArrayList()
        callbacks = ArrayList()
    }

    fun spawn(
        initialInstructionNumber: Int,
        instruction: AnnotatedInstruction.Builder,
    ): ControlTree {
        val node = ControlTree(initialInstructionNumber, instruction, this)
        addNested(node)
        return node
    }

    fun instruction(): AnnotatedInstruction.Builder = instruction!!

    fun instructionNumber(): Int = initialInstructionNumber

    fun addNested(nested: ControlTree) {
        this.nested.add(nested)
    }

    fun parent(): ControlTree? = parent

    fun addCallback(callback: (Int) -> Unit) {
        callbacks.add(callback)
    }

    fun setFinalInstructionNumber(
        finalInstructionNumberInput: Int,
        end: AnnotatedInstruction.Builder,
    ) {
        var finalInstructionNumber = finalInstructionNumberInput

        // To be set when END is reached.
        if (end.scope()?.opcode() == OpCode.LOOP) {
            var lastLoopInstruction = 0
            if (parent != null) {
                for (ct in parent.nested) {
                    if (ct.instruction().opcode() == OpCode.LOOP) {
                        lastLoopInstruction = ct.instructionNumber()
                    }
                }
            }
            finalInstructionNumber = lastLoopInstruction + 1
        }

        for (callback in callbacks) {
            callback(finalInstructionNumber)
        }
    }
}
