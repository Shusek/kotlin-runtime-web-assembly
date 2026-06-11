package uk.shusek.krwa.compiler.internal

import java.util.ArrayDeque
import java.util.Deque
import java.util.HashMap
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.TypeSection
import uk.shusek.krwa.wasm.types.ValType

class TypeStack(private val typeSection: TypeSection) {
    private val stackTypes: Deque<Deque<ValType>> = ArrayDeque()
    private val restore: Deque<Deque<ValType>> = ArrayDeque()
    private val scopes: MutableMap<Instruction, Int> = HashMap()

    init {
        stackTypes.push(ArrayDeque())
    }

    fun peek(): ValType = types().first

    fun push(type: ValType) {
        types().push(type)
    }

    fun pop(expected: ValType) {
        val actual = types().pop()
        if (!ValType.matches(actual, expected, typeSection)) {
            throw IllegalArgumentException("Expected type $expected <> $actual")
        }
    }

    fun popRef() {
        val actual = types().pop()
        if (!actual.isReference()) {
            throw IllegalArgumentException("Expected reference type <> $actual")
        }
    }

    fun pushTypes() {
        stackTypes.push(ArrayDeque(types()))
    }

    fun popTypes() {
        stackTypes.pop()
    }

    fun enterScope(scope: Instruction, scopeType: FunctionType) {
        scopes[scope] = types().size

        // Restored stack when exiting polymorphic blocks after unconditional control transfer.
        val stack: Deque<ValType> = ArrayDeque(types())
        for (i in 0 until scopeType.params().size) {
            stack.pop()
        }
        for (type in scopeType.returns()) {
            stack.push(type)
        }
        restore.push(stack)
    }

    fun exitScope(scope: Instruction) {
        scopes.remove(scope)
        restore.pop()
    }

    fun scopeRestore() {
        stackTypes.pop()
        stackTypes.push(restore.first)
    }

    fun scopeStackSize(scope: Instruction): Int? = scopes[scope]

    fun types(): Deque<ValType> = stackTypes.first

    fun verifyEmpty() {
        if (stackTypes.size != 1) {
            throw RuntimeException("Bad types stack: $stackTypes")
        }
        if (!types().isEmpty()) {
            throw RuntimeException("Types not empty: ${types()}")
        }
    }

    companion object {
        @JvmField
        val FUNCTION_SCOPE: Instruction = Instruction(-1, OpCode.NOP, Instruction.EMPTY_OPERANDS)
    }
}
