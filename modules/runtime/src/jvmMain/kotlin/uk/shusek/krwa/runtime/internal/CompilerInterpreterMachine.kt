package uk.shusek.krwa.runtime.internal

import java.util.HashSet
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.MStack
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.runtime.WasmException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.FunctionType

/**
 * This class is used by compiler generated classes. It MUST remain backwards compatible so that
 * older generated code can run on newer versions of the library.
 */
class CompilerInterpreterMachine(instance: Instance, interpretedFuncIds: IntArray) :
    InterpreterMachine(instance) {
    @JvmField var interpretedFuncIds: Set<Int> = interpretedFuncIds.toSet()

    override fun isInterrupted(): Boolean = Thread.currentThread().isInterrupted

    @Throws(WasmEngineException::class)
    override fun call(
        stack: MStack,
        instance: Instance,
        callStack: ArrayDeque<StackFrame>,
        funcId: Int,
        args: LongArray,
        callType: FunctionType?,
        popResults: Boolean,
    ): LongArray {
        if (usedInterpretedFunctions != null && !usedInterpretedFunctions.contains(funcId)) {
            usedInterpretedFunctions.add(funcId)
            System.err.println("Kotlin Runtime Web Assembly: calling interpreted function $funcId")
        }
        return super.call(stack, instance, callStack, funcId, args, callType, popResults)
    }

    override fun CALL(operands: Operands): StackFrame? {
        val instance = instance()
        val funcId = operands.get(0).toInt()
        if (interpretedFuncIds.contains(funcId) || instance.function(funcId) == null) {
            // continue interpreting for interpreted functions or imported functions
            return super.CALL(operands)
        } else {
            // We end up here after a function switched to interpreted mode, going back to Java
            // bytecode.
            //
            // Must be an AOT function: switch back the to the AOT machine that's assigned to the
            // instance.
            val stack = stack()
            val typeId = instance.functionType(funcId)
            val type = instance.type(typeId)
            val args = extractArgsForParams(stack, type)

            try {
                val results: LongArray? = instance.getMachine().call(funcId, args)
                // a host function can return null or an array of ints which we will push onto the
                // stack
                if (results != null) {
                    for (result in results) {
                        stack.push(result)
                    }
                }
            } catch (e: WasmException) {
                // we need at least an empty frame
                val stackFrame = StackFrame(instance, funcId, args)
                THROW_REF(instance, instance.registerException(e), stack, stackFrame, callStack)
            }
            return null
        }
    }

    override fun CALL(currentStackFrame: StackFrame): StackFrame? {
        val instance = instance()
        val funcId = currentStackFrame.currentCallFunctionId()
        if (interpretedFuncIds.contains(funcId) || currentStackFrame.currentCallBody() == null) {
            // continue interpreting for interpreted functions or imported functions
            return super.CALL(currentStackFrame)
        } else {
            // We end up here after a function switched to interpreted mode, going back to Java
            // bytecode.
            //
            // Must be an AOT function: switch back the to the AOT machine that's assigned to the
            // instance.
            val stack = stack()
            val type = currentStackFrame.currentCallType()
            val args = extractArgsForParams(stack, type)

            try {
                val results: LongArray? = instance.getMachine().call(funcId, args)
                // a host function can return null or an array of ints which we will push onto the
                // stack
                if (results != null) {
                    for (result in results) {
                        stack.push(result)
                    }
                }
            } catch (e: WasmException) {
                // we need at least an empty frame
                val stackFrame = StackFrame(instance, funcId, args)
                THROW_REF(instance, instance.registerException(e), stack, stackFrame, callStack)
            }
            return null
        }
    }

    override fun useCurrentInstanceInterpreter(
        instance: Instance,
        refInstance: Instance,
        funcId: Int,
    ): Boolean =
        // this function influence the behavior of CALL_INDIRECT without rewriting it if we are on
        // the same instance and the next invoked function needs to stay in interpreted mode,
        // alternatively go through `Machine::call`
        refInstance == instance && interpretedFuncIds.contains(funcId)

    companion object {
        private val usedInterpretedFunctions: HashSet<Int>? =
            if (
                java.lang.Boolean.parseBoolean(
                    System.getProperty("krwa.compiler.printUseOfInterpretedFunctions")
                )
            ) {
                HashSet()
            } else {
                null
            }
    }
}
