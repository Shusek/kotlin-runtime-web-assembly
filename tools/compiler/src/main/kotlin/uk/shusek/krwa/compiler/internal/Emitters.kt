package uk.shusek.krwa.compiler.internal

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.EnumMap
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.Type.LONG_TYPE
import org.objectweb.asm.Type.getInternalName
import org.objectweb.asm.Type.getMethodDescriptor
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.InstructionAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import uk.shusek.krwa.compiler.internal.CompilerUtil.asmType
import uk.shusek.krwa.compiler.internal.CompilerUtil.callIndirectMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.callIndirectMethodType
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeFunction
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeStatic
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeVirtual
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitJvmToLong
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitLongToJvm
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitPop
import uk.shusek.krwa.compiler.internal.CompilerUtil.hasTooManyParameters
import uk.shusek.krwa.compiler.internal.CompilerUtil.jvmReturnType
import uk.shusek.krwa.compiler.internal.CompilerUtil.localType
import uk.shusek.krwa.compiler.internal.CompilerUtil.slotCount
import uk.shusek.krwa.compiler.internal.CompilerUtil.valueMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.valueMethodType
import uk.shusek.krwa.compiler.internal.ShadedRefs.EXCEPTION_MATCHES
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.OpCodeIdentifier
import uk.shusek.krwa.runtime.WasmException
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

internal object Emitters {
    fun interface BytecodeEmitter {
        fun emit(context: Context, ins: CompilerInstruction, asm: InstructionAdapter)
    }

    class Builder {
        private val emitters = EnumMap<CompilerOpCode, BytecodeEmitter>(CompilerOpCode::class.java)

        fun intrinsic(opCode: CompilerOpCode, emitter: BytecodeEmitter): Builder {
            emitters[opCode] = emitter
            return this
        }

        fun shared(opCode: CompilerOpCode, staticHelpers: Class<*>): Builder {
            emitters[opCode] = intrinsify(opCode, staticHelpers)
            return this
        }

        fun build(): Map<CompilerOpCode, BytecodeEmitter> = java.util.Map.copyOf(emitters)
    }

    fun builder(): Builder = Builder()

    fun TRAP(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitInvokeStatic(asm, ShadedRefs.THROW_TRAP_EXCEPTION)
        asm.athrow()
    }

    fun valType(id: Long, ctx: Context): ValType {
        return ValType.builder().fromId(id).build().resolve(ctx.typeSection())
    }

    fun DROP_KEEP(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val keepStart = ins.operand(0).toInt() + 1

        // save result values
        var slot = ctx.tempSlot()
        for (i in ins.operandCount() - 1 downTo keepStart) {
            val type = valType(ins.operand(i), ctx)
            asm.store(slot, asmType(type))
            slot += slotCount(type)
        }

        // drop intervening values
        for (i in keepStart - 1 downTo 1) {
            emitPop(asm, valType(ins.operand(i), ctx))
        }

        // restore result values
        for (i in keepStart until ins.operandCount()) {
            val type = valType(ins.operand(i), ctx)
            slot -= slotCount(type)
            asm.load(slot, asmType(type))
        }
    }

    fun RETURN(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        if (ctx.getType().returns().size > 1) {
            asm.invokestatic(
                ctx.internalClassName(),
                valueMethodName(ctx.getType().returns()),
                valueMethodType(ctx.getType().returns()).toMethodDescriptorString(),
                false,
            )
        }
        asm.areturn(getType(jvmReturnType(ctx.getType())))
    }

    fun DROP(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitPop(asm, valType(ins.operand(0), ctx))
    }

    fun ELEM_DROP(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val index = ins.operand(0).toInt()
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        asm.iconst(index)
        asm.aconst(null)
        emitInvokeVirtual(asm, ShadedRefs.INSTANCE_SET_ELEMENT)
    }

    fun SELECT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val type = valType(ins.operand(0), ctx)
        val endLabel = Label()
        asm.ifne(endLabel)
        if (slotCount(type) == 1) {
            asm.swap()
        } else {
            asm.dup2X2()
            asm.pop2()
        }
        asm.mark(endLabel)
        emitPop(asm, type)
    }

    private fun emitBoxValuesOnStack(ctx: Context, asm: InstructionAdapter, types: List<ValType>) {

        // Store values from stack to locals in reverse order
        var slot = ctx.tempSlot() + types.stream().mapToInt(CompilerUtil::slotCount).sum()
        for (i in types.size - 1 downTo 0) {
            val valType = types.get(i)
            slot -= slotCount(valType)
            asm.store(slot, asmType(valType))
        }

        // Create the array
        asm.iconst(types.size)
        asm.newarray(LONG_TYPE)

        // Load from locals and store in array
        slot = ctx.tempSlot()
        for (i in 0 until types.size) {
            val valType = types.get(i)

            asm.dup() // Duplicate the array reference
            asm.iconst(i) // Array index
            asm.load(slot, asmType(valType)) // Load value from local
            slot += slotCount(valType)
            emitJvmToLong(asm, valType) // Convert to long
            asm.astore(LONG_TYPE) // Store in array
        }
    }

    fun CALL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val funcId = ins.operand(0).toInt()
        val functionType = ctx.functionTypes().get(funcId)

        emitInvokeStatic(asm, ShadedRefs.CHECK_INTERRUPTION)
        if (hasTooManyParameters(functionType)) {
            emitBoxValuesOnStack(ctx, asm, functionType.params())
        }

        asm.load(ctx.memorySlot(), OBJECT_TYPE)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeFunction(
            asm,
            ctx.classNameForFuncGroup(ctx.internalClassName(), funcId),
            funcId,
            functionType,
        )

        if (ctx.needsTailCallCheck(funcId)) {
            emitTailCallCheck(ctx, asm, functionType)
        }

        if (functionType.returns().size > 1) {
            emitUnboxResult(asm, ctx, functionType.returns())
        }
    }

    fun CALL_INDIRECT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeId = ins.operand(0).toInt()
        val tableIdx = ins.operand(1).toInt()
        val functionType = ctx.types()[typeId]

        if (hasTooManyParameters(functionType)) {
            emitBoxValuesOnStack(ctx, asm, functionType.params())
        }

        // stack: arguments, funcTableIdx, tableIdx, memory, instance
        asm.iconst(tableIdx)
        asm.load(ctx.memorySlot(), OBJECT_TYPE)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)

        asm.invokestatic(
            ctx.callIndirectClassName(typeId),
            callIndirectMethodName(typeId),
            callIndirectMethodType(functionType).toMethodDescriptorString(),
            false,
        )

        if (ctx.needsTailCallCheckForType(typeId)) {
            emitTailCallCheck(ctx, asm, functionType)
        }

        if (functionType.returns().size > 1) {
            emitUnboxResult(asm, ctx, functionType.returns())
        }
    }

    fun REF_FUNC(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
    }

    fun REF_NULL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(Value.REF_NULL_VALUE)
    }

    fun REF_IS_NULL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitInvokeStatic(asm, ShadedRefs.REF_IS_NULL)
    }

    fun REF_EQ(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.REF_EQ)
    }

    fun REF_AS_NON_NULL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitInvokeStatic(asm, ShadedRefs.REF_AS_NON_NULL)
    }

    fun LOCAL_GET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val loadIndex = ins.operand(0).toInt()
        val localType = localType(ctx.getType(), ctx.getBody(), loadIndex)
        asm.load(ctx.localSlotIndex(loadIndex), asmType(localType))
    }

    fun LOCAL_SET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val index = ins.operand(0).toInt()
        val localType = localType(ctx.getType(), ctx.getBody(), index)
        asm.store(ctx.localSlotIndex(index), asmType(localType))
    }

    fun LOCAL_TEE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        if (slotCount(valType(ins.operand(1), ctx)) == 1) {
            asm.dup()
        } else {
            asm.dup2()
        }

        LOCAL_SET(ctx, ins, asm)
    }

    fun GLOBAL_GET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val globalIndex = ins.operand(0).toInt()
        val globalType = ctx.globalTypes().get(globalIndex)

        asm.iconst(globalIndex)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)

        if (globalType.isReference()) {
            // Use readGlobalRef to handle i31 tagged-long values from constant initializers
            emitInvokeStatic(asm, ShadedRefs.READ_GLOBAL_REF)
        } else {
            emitInvokeStatic(asm, ShadedRefs.READ_GLOBAL)
            emitLongToJvm(asm, globalType)
        }
    }

    fun GLOBAL_SET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val globalIndex = ins.operand(0).toInt()

        emitJvmToLong(asm, ctx.globalTypes().get(globalIndex))
        asm.iconst(globalIndex)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.WRITE_GLOBAL)
    }

    fun TABLE_GET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_GET)
    }

    fun TABLE_SET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_SET)
    }

    fun TABLE_SIZE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_SIZE)
    }

    fun TABLE_GROW(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_GROW)
    }

    fun TABLE_FILL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_FILL)
    }

    fun TABLE_COPY(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.iconst(ins.operand(1).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_COPY)
    }

    fun TABLE_INIT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.iconst(ins.operand(1).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.TABLE_INIT)
    }

    fun MEMORY_INIT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        emitMemoryForIndex(ctx, ins, asm, 1)
        emitInvokeStatic(asm, ShadedRefs.MEMORY_INIT)
    }

    fun MEMORY_COPY(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val dstMemIdx = ins.operand(0).toInt()
        val srcMemIdx = ins.operand(1).toInt()
        if (dstMemIdx == srcMemIdx) {
            emitMemoryForIndex(ctx, ins, asm, 0)
            emitInvokeStatic(asm, ShadedRefs.MEMORY_COPY)
        } else {
            emitMemoryForIndex(ctx, ins, asm, 0)
            emitMemoryForIndex(ctx, ins, asm, 1)
            emitInvokeStatic(asm, ShadedRefs.MEMORY_COPY_2)
        }
    }

    fun MEMORY_FILL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitMemoryForIndex(ctx, ins, asm, 0)
        emitInvokeStatic(asm, ShadedRefs.MEMORY_FILL)
    }

    fun MEMORY_GROW(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitMemoryForIndex(ctx, ins, asm, 0)
        emitInvokeStatic(asm, ShadedRefs.MEMORY_GROW)
    }

    fun MEMORY_SIZE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitMemoryForIndex(ctx, ins, asm, 0)
        emitInvokeStatic(asm, ShadedRefs.MEMORY_PAGES)
    }

    fun DATA_DROP(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.DATA_DROP)
    }

    fun I32_GE_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitInvokeStatic(asm, ShadedRefs.I32_GE_U)
    }

    fun I32_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IADD)
    }

    fun I32_AND(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IAND)
    }

    fun I32_CONST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.iconst(ins.operand(0).toInt())
    }

    fun I32_MUL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IMUL)
    }

    fun I32_OR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IOR)
    }

    fun I32_SHL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.ISHL)
    }

    fun I32_SHR_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.ISHR)
    }

    fun I32_SHR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IUSHR)
    }

    fun I32_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.ISUB)
    }

    fun I32_WRAP_I64(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
    }

    fun I32_XOR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.IXOR)
    }

    fun I64_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LADD)
    }

    fun I64_AND(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LAND)
    }

    fun I64_CONST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.lconst(ins.operand(0))
    }

    fun I64_EXTEND_I32_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_MUL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LMUL)
    }

    fun I64_OR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LOR)
    }

    fun I64_SHL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        asm.visitInsn(Opcodes.LSHL)
    }

    fun I64_SHR_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        asm.visitInsn(Opcodes.LSHR)
    }

    fun I64_SHR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        asm.visitInsn(Opcodes.LUSHR)
    }

    fun I64_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LSUB)
    }

    fun I64_XOR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.LXOR)
    }

    fun F32_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.FADD)
    }

    fun F32_CONST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.fconst(java.lang.Float.intBitsToFloat(ins.operand(0).toInt()))
    }

    fun F32_DEMOTE_F64(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.D2F)
    }

    fun F32_DIV(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.FDIV)
    }

    fun F32_MUL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.FMUL)
    }

    fun F32_NEG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.FNEG)
    }

    fun F32_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.FSUB)
    }

    fun F64_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.DADD)
    }

    fun F64_CONST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.dconst(java.lang.Double.longBitsToDouble(ins.operand(0)))
    }

    fun F64_DIV(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.DDIV)
    }

    fun F64_MUL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.DMUL)
    }

    fun F64_NEG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.DNEG)
    }

    fun F64_PROMOTE_F32(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.F2D)
    }

    fun F64_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.DSUB)
    }

    fun I32_LOAD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_INT)
    }

    fun I32_LOAD8_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_BYTE)
    }

    fun I32_LOAD8_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitUnsignedByteLoad(ctx, ins, asm)
    }

    fun I32_LOAD16_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_SHORT)
    }

    fun I32_LOAD16_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD16_S(ctx, ins, asm)
        asm.iconst(0xFFFF)
        asm.visitInsn(Opcodes.IAND)
    }

    fun F32_LOAD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_FLOAT)
    }

    fun I64_LOAD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_LONG)
    }

    fun I64_LOAD8_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD8_S(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_LOAD8_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD8_U(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_LOAD16_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD16_S(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_LOAD16_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD16_U(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_LOAD32_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
    }

    fun I64_LOAD32_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        I32_LOAD(ctx, ins, asm)
        asm.visitInsn(Opcodes.I2L)
        asm.lconst(0xFFFF_FFFFL)
        asm.visitInsn(Opcodes.LAND)
    }

    fun F64_LOAD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_READ_DOUBLE)
    }

    fun I32_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_INT)
    }

    fun I32_STORE8(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.I2B)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_BYTE)
    }

    fun I32_STORE16(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.I2S)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_SHORT)
    }

    fun F32_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_FLOAT)
    }

    fun I64_STORE8(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        I32_STORE8(ctx, ins, asm)
    }

    fun I64_STORE16(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        I32_STORE16(ctx, ins, asm)
    }

    fun I64_STORE32(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_INT)
    }

    fun I64_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_LONG)
    }

    fun F64_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_WRITE_DOUBLE)
    }

    fun ATOMIC_INT_READ_BYTE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_BYTE_READ)
    }

    fun ATOMIC_INT_READ_SHORT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_SHORT_READ)
    }

    fun ATOMIC_INT_READ(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_READ)
    }

    fun ATOMIC_LONG_READ(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_READ)
    }

    fun ATOMIC_LONG_READ_BYTE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_BYTE_READ)
    }

    fun ATOMIC_LONG_READ_SHORT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_SHORT_READ)
    }

    fun ATOMIC_LONG_READ_INT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_INT_READ)
    }

    fun ATOMIC_INT_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_WRITE)
    }

    fun ATOMIC_INT_STORE_BYTE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_BYTE_WRITE)
    }

    fun ATOMIC_INT_STORE_SHORT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_SHORT_WRITE)
    }

    fun ATOMIC_LONG_STORE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_WRITE)
    }

    fun ATOMIC_LONG_STORE_BYTE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_BYTE_WRITE)
    }

    fun ATOMIC_LONG_STORE_SHORT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_SHORT_WRITE)
    }

    fun ATOMIC_LONG_STORE_INT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.visitInsn(Opcodes.L2I)
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_INT_WRITE)
    }

    fun ATOMIC_INT_RMW_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_ADD)
    }

    fun ATOMIC_INT_RMW_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_SUB)
    }

    fun ATOMIC_INT_RMW_AND(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_AND)
    }

    fun ATOMIC_INT_RMW_OR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_OR)
    }

    fun ATOMIC_INT_RMW_XOR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_XOR)
    }

    fun ATOMIC_INT_RMW_XCHG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_XCHG)
    }

    fun ATOMIC_INT_RMW_CMPXCHG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW_CMPXCHG)
    }

    fun ATOMIC_INT_RMW8_ADD_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_ADD_U)
    }

    fun ATOMIC_INT_RMW8_SUB_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_SUB_U)
    }

    fun ATOMIC_INT_RMW8_AND_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_AND_U)
    }

    fun ATOMIC_INT_RMW8_OR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_OR_U)
    }

    fun ATOMIC_INT_RMW8_XOR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_XOR_U)
    }

    fun ATOMIC_INT_RMW8_XCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_XCHG_U)
    }

    fun ATOMIC_INT_RMW8_CMPXCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW8_CMPXCHG_U)
    }

    fun ATOMIC_INT_RMW16_ADD_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_ADD_U)
    }

    fun ATOMIC_INT_RMW16_SUB_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_SUB_U)
    }

    fun ATOMIC_INT_RMW16_AND_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_AND_U)
    }

    fun ATOMIC_INT_RMW16_OR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_OR_U)
    }

    fun ATOMIC_INT_RMW16_XOR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_XOR_U)
    }

    fun ATOMIC_INT_RMW16_XCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_XCHG_U)
    }

    fun ATOMIC_INT_RMW16_CMPXCHG_U(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
    ) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_INT_RMW16_CMPXCHG_U)
    }

    // I64 variants
    fun ATOMIC_LONG_RMW_ADD(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_ADD)
    }

    fun ATOMIC_LONG_RMW_SUB(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_SUB)
    }

    fun ATOMIC_LONG_RMW_AND(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_AND)
    }

    fun ATOMIC_LONG_RMW_OR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_OR)
    }

    fun ATOMIC_LONG_RMW_XOR(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_XOR)
    }

    fun ATOMIC_LONG_RMW_XCHG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_XCHG)
    }

    fun ATOMIC_LONG_RMW_CMPXCHG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW_CMPXCHG)
    }

    fun ATOMIC_LONG_RMW8_ADD_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_ADD_U)
    }

    fun ATOMIC_LONG_RMW8_SUB_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_SUB_U)
    }

    fun ATOMIC_LONG_RMW8_AND_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_AND_U)
    }

    fun ATOMIC_LONG_RMW8_OR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_OR_U)
    }

    fun ATOMIC_LONG_RMW8_XOR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_XOR_U)
    }

    fun ATOMIC_LONG_RMW8_XCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_XCHG_U)
    }

    fun ATOMIC_LONG_RMW8_CMPXCHG_U(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
    ) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW8_CMPXCHG_U)
    }

    fun ATOMIC_LONG_RMW16_ADD_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_ADD_U)
    }

    fun ATOMIC_LONG_RMW16_SUB_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_SUB_U)
    }

    fun ATOMIC_LONG_RMW16_AND_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_AND_U)
    }

    fun ATOMIC_LONG_RMW16_OR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_OR_U)
    }

    fun ATOMIC_LONG_RMW16_XOR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_XOR_U)
    }

    fun ATOMIC_LONG_RMW16_XCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_XCHG_U)
    }

    fun ATOMIC_LONG_RMW16_CMPXCHG_U(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
    ) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW16_CMPXCHG_U)
    }

    fun ATOMIC_LONG_RMW32_ADD_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_ADD_U)
    }

    fun ATOMIC_LONG_RMW32_SUB_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_SUB_U)
    }

    fun ATOMIC_LONG_RMW32_AND_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_AND_U)
    }

    fun ATOMIC_LONG_RMW32_OR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_OR_U)
    }

    fun ATOMIC_LONG_RMW32_XOR_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_XOR_U)
    }

    fun ATOMIC_LONG_RMW32_XCHG_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_XCHG_U)
    }

    fun ATOMIC_LONG_RMW32_CMPXCHG_U(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
    ) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_LONG_RMW32_CMPXCHG_U)
    }

    // Wait/Notify
    fun MEM_ATOMIC_WAIT32(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_WAIT32)
    }

    fun MEM_ATOMIC_WAIT64(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_WAIT64)
    }

    fun MEM_ATOMIC_NOTIFY(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        emitLoadOrStore(ctx, ins, asm, ShadedRefs.MEMORY_ATOMIC_NOTIFY)
    }

    fun MEM_ATOMIC_FENCE(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // ATOMIC_FENCE always uses memory 0 per spec
        asm.load(ctx.memorySlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.MEMORY_ATOMIC_FENCE)
    }

    private fun emitMemoryForIndex(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
        operandIdx: Int,
    ) {
        val memIdx = ins.operand(operandIdx).toInt()
        if (memIdx == 0) {
            asm.load(ctx.memorySlot(), OBJECT_TYPE)
        } else {
            asm.load(ctx.instanceSlot(), OBJECT_TYPE)
            asm.iconst(memIdx)
            emitInvokeVirtual(asm, ShadedRefs.INSTANCE_MEMORY_IDX)
        }
    }

    private fun emitLoadOrStore(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
        method: Method,
    ) {
        val offset = ins.operand(1)

        if (offset < 0 || offset >= Integer.MAX_VALUE) {
            emitInvokeStatic(asm, ShadedRefs.THROW_OUT_OF_BOUNDS_MEMORY_ACCESS)
            asm.athrow()
        }

        asm.iconst(offset.toInt())
        emitMemoryForIndex(ctx, ins, asm, 2)
        emitInvokeStatic(asm, method)
    }

    private fun emitUnsignedByteLoad(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
    ) {
        emitDirectMemoryRead(ctx, ins, asm, ShadedRefs.MEMORY_READ_DIRECT)
        asm.iconst(0xFF)
        asm.visitInsn(Opcodes.IAND)
    }

    private fun emitDirectMemoryRead(
        ctx: Context,
        ins: CompilerInstruction,
        asm: InstructionAdapter,
        read: Method,
    ) {
        val offset = ins.operand(1)

        if (offset < 0 || offset >= Integer.MAX_VALUE) {
            emitInvokeStatic(asm, ShadedRefs.THROW_OUT_OF_BOUNDS_MEMORY_ACCESS)
            asm.athrow()
        }

        emitAddressWithOffset(asm, offset.toInt())
        emitMemoryForIndex(ctx, ins, asm, 2)
        asm.swap()
        asm.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            getInternalName(read.declaringClass),
            read.name,
            getMethodDescriptor(read),
            true,
        )
    }

    private fun emitAddressWithOffset(asm: InstructionAdapter, offset: Int) {
        if (offset == 0) return

        val negativeBase = Label()
        val done = Label()
        asm.dup()
        asm.visitJumpInsn(Opcodes.IFLT, negativeBase)
        asm.iconst(offset)
        asm.visitInsn(Opcodes.IADD)
        asm.goTo(done)
        asm.mark(negativeBase)
        asm.mark(done)
    }

    // Callee side: boxes params, signals tail call, returns a dummy value.
    // Equivalent to: Shaded.setTailCall(funcId, box(params...), instance) return default
    fun RETURN_CALL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val funcId = ins.operand(0).toInt()
        val calleeType = ctx.functionTypes().get(funcId)

        emitBoxValuesOnStack(ctx, asm, calleeType.params())
        asm.iconst(funcId)
        asm.swap()
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.SET_TAIL_CALL)
        emitDefaultReturn(ctx, asm)
    }

    fun RETURN_CALL_INDIRECT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeId = ins.operand(0).toInt()
        val tableIdx = ins.operand(1).toInt()
        val calleeType = ctx.types()[typeId]

        val paramSlots = calleeType.params().stream().mapToInt(CompilerUtil::slotCount).sum()
        val savedSlot = ctx.tempSlot() + paramSlots
        asm.store(savedSlot, INT_TYPE)

        emitBoxValuesOnStack(ctx, asm, calleeType.params())
        asm.load(savedSlot, INT_TYPE)
        asm.iconst(typeId)
        asm.iconst(tableIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.SET_TAIL_CALL_INDIRECT)
        emitDefaultReturn(ctx, asm)
    }

    fun RETURN_CALL_REF(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeId = ins.operand(0).toInt()
        val calleeType = ctx.types()[typeId]

        val paramSlots = calleeType.params().stream().mapToInt(CompilerUtil::slotCount).sum()
        val savedSlot = ctx.tempSlot() + paramSlots
        asm.store(savedSlot, INT_TYPE)

        // null check
        asm.load(savedSlot, INT_TYPE)
        asm.iconst(Value.REF_NULL_VALUE)
        val notNull = Label()
        asm.ificmpne(notNull)
        emitInvokeStatic(asm, ShadedRefs.THROW_NULL_FUNCTION_REFERENCE)
        asm.athrow()
        asm.mark(notNull)

        emitBoxValuesOnStack(ctx, asm, calleeType.params())
        asm.load(savedSlot, INT_TYPE)
        asm.swap()
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.SET_TAIL_CALL)
        emitDefaultReturn(ctx, asm)
    }

    private fun emitDefaultReturn(ctx: Context, asm: InstructionAdapter) {
        val type = ctx.getType()
        if (type.returns().isEmpty()) {
            asm.areturn(getType(Void.TYPE))
        } else if (type.returns().size == 1) {
            val defaultVal = CompilerUtil.defaultValue(type.returns().get(0))
            when (defaultVal) {
                is Int -> asm.iconst(defaultVal)
                is Long -> asm.lconst(defaultVal)
                is Float -> asm.fconst(defaultVal)
                is Double -> asm.dconst(defaultVal)
            }
            asm.areturn(getType(jvmReturnType(type)))
        } else {
            asm.aconst(null)
            asm.areturn(OBJECT_TYPE)
        }
    }

    private fun emitTailCallCheck(
        ctx: Context,
        asm: InstructionAdapter,
        functionType: FunctionType,
    ) {
        val noPending = Label()
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.IS_TAIL_CALL_PENDING)
        asm.ifeq(noPending)

        val returns: List<ValType> = functionType.returns()

        if (returns.size == 1) {
            emitPop(asm, returns.get(0))
        } else if (returns.size > 1) {
            asm.pop()
        }

        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.RESOLVE_TAIL_CALL)

        if (returns.isEmpty()) {
            asm.pop()
        } else if (returns.size == 1) {
            asm.iconst(0)
            asm.aload(LONG_TYPE)
            emitLongToJvm(asm, returns.get(0))
        }
        // For multi-value: leave long[] on stack — caller's emitUnboxResult will handle it

        asm.mark(noPending)
    }

    // ========= Unbox Helpers =========

    private fun emitUnboxResult(asm: InstructionAdapter, ctx: Context, types: List<ValType>) {
        emitUnboxResult(asm, types, ctx.tempSlot())
    }

    private fun emitUnboxResult(asm: InstructionAdapter, types: List<ValType>, tempSlot: Int) {
        asm.store(tempSlot, OBJECT_TYPE)
        for (i in 0 until types.size) {
            asm.load(tempSlot, OBJECT_TYPE)
            asm.iconst(i)
            asm.aload(LONG_TYPE)
            emitLongToJvm(asm, types.get(i))
        }
    }

    /**
     * The AOT compiler assumes two main ways of implementing opcodes: intrinsics, and shared
     * implementations. Intrinsics refer to WASM opcodes that are implemented in the AOT by
     * assembling JVM bytecode that implements the logic of the opcode. Shared implementations refer
     * to static methods in a public class that do the same, with the term "shared" referring to the
     * fact that these implementations are intended to be used by both the AOT and the interpreter.
     *
     * <p>
     * This method takes an opcode and a class (which must have a public static method annotated as
     * an implementation of the opcode) and creates a BytecodeEmitter that will implement the WASM
     * opcode as a static method call to the implementation provided by the class. That is, it
     * "intrinsifies" the shared implementation by generating a static call to it. The method
     * implementing the opcode must have a signature that exactly matches the stack operands and
     * result type of the opcode, and if its parameters are order-sensitive then they must be in the
     * order that produces the expected result when the JVM's stack and calling convention are used
     * instead of the interpreter's. That is, if order is significant they must be in the order
     * methodName(..., tos - 2, tos - 1, tos) where "tos" is the top-of-stack value.
     *
     * @param opcode the WASM opcode that is implemented by an annotated static method in this class
     * @param staticHelpers the class containing the implementation
     * @return a BytecodeEmitter that will implement the opcode via a call to the shared
     *   implementation
     */
    fun intrinsify(opcode: CompilerOpCode, staticHelpers: Class<*>): BytecodeEmitter {
        for (method in staticHelpers.declaredMethods) {
            if (
                Modifier.isStatic(method.modifiers) &&
                    method.isAnnotationPresent(OpCodeIdentifier::class.java) &&
                    method.getAnnotation(OpCodeIdentifier::class.java).value == opcode.opcode()
            ) {
                return BytecodeEmitter { _, _, asm -> emitInvokeStatic(asm, method) }
            }
        }
        throw IllegalArgumentException(
            "Static helper " +
                staticHelpers.name +
                " does not provide an implementation of opcode " +
                opcode.name
        )
    }

    fun THROW(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val tagNumber = ins.operand(0).toInt()
        val type = ctx.tagFunctionType(tagNumber)

        // emmit:
        // call createWasmException(long[] args, int tagNumber, Instance instance)
        emitBoxValuesOnStack(ctx, asm, type.params())
        asm.iconst(tagNumber)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.CREATE_WASM_EXCEPTION)
        asm.athrow()
    }

    fun THROW_REF(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // The exception reference is already on the stack as an integer
        // Get the instance and retrieve the exception
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        asm.swap() // Swap instance and exception reference
        emitInvokeVirtual(asm, ShadedRefs.INSTANCE_GET_EXCEPTION)
        asm.athrow()
    }

    fun CATCH_START(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.store(ctx.tempSlot(), OBJECT_TYPE)
    }

    fun CATCH_END(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        asm.load(ctx.tempSlot(), OBJECT_TYPE)
        asm.athrow()
    }

    // Save all values on the JVM operand stack to local variables before a try block.
    // This is needed because JVM exception handlers clear the operand stack,
    // but WASM try_table catch preserves values below the try scope.
    // Operands: [saveSlotBase, belowCount, type_ids from bottom to top...]
    fun TRY_SAVE_STACK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val saveSlotBase = ins.operand(0).toInt()
        val belowCount = ins.operand(1).toInt()
        val totalCount = ins.operandCount() - 2

        // Pop above-try values (block params) from stack top, save to transient temp
        var tempSlot = ctx.tempSlot()
        for (i in totalCount - 1 downTo belowCount) {
            val type = valType(ins.operand(i + 2), ctx)
            asm.store(tempSlot, asmType(type))
            tempSlot += slotCount(type)
        }

        // Pop below-try values and save to persistent try-save slots.
        // Compute slot assignments bottom-to-top so the layout matches
        // TRY_RESTORE_STACK's sequential loading order.
        val belowSlots = IntArray(belowCount)
        var saveSlot = ctx.trySaveBaseSlot() + saveSlotBase
        for (i in 0 until belowCount) {
            belowSlots[i] = saveSlot
            saveSlot += slotCount(valType(ins.operand(i + 2), ctx))
        }
        // Pop in reverse (top-of-below first) and store to pre-computed slots
        for (i in belowCount - 1 downTo 0) {
            val type = valType(ins.operand(i + 2), ctx)
            asm.store(belowSlots[i], asmType(type))
        }

        // Restore below-try values (bottom to top)
        for (i in 0 until belowCount) {
            val type = valType(ins.operand(i + 2), ctx)
            asm.load(belowSlots[i], asmType(type))
        }

        // Restore above-try values (bottom to top)
        tempSlot = ctx.tempSlot()
        for (i in belowCount until totalCount) {
            val type = valType(ins.operand(i + 2), ctx)
            asm.load(tempSlot, asmType(type))
            tempSlot += slotCount(type)
        }
    }

    // Restore below-try values in the catch handler from persistent try-save slots.
    // Operands: [saveSlotBase, type_ids of below-try values from bottom to top...]
    fun TRY_RESTORE_STACK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val saveSlotBase = ins.operand(0).toInt()
        var saveSlot = ctx.trySaveBaseSlot() + saveSlotBase

        // Load below-try values (bottom to top)
        for (i in 1 until ins.operandCount()) {
            val type = valType(ins.operand(i), ctx)
            asm.load(saveSlot, asmType(type))
            saveSlot += slotCount(type)
        }
    }

    fun CATCH_UNBOX_PARAMS(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val tag = ins.operand(0).toInt()
        // Get the tag type to know what
        // parameter types to unbox
        val tagFuncType = ctx.tagFunctionType(tag)
        if (!tagFuncType.params().isEmpty()) {
            // unbox the exception args
            asm.load(ctx.tempSlot(), OBJECT_TYPE)
            asm.invokevirtual(
                getInternalName(WasmException::class.java),
                "args",
                getMethodDescriptor(getType(LongArray::class.java)),
                false,
            )

            // Store the array in a local variable
            // Unbox each argument from the
            // long[] array and push onto stack
            emitUnboxResult(asm, tagFuncType.params(), ctx.tempSlot() + 1)
        }
    }

    fun CATCH_COMPARE_TAG(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val tag = ins.operand(0).toInt()
        // Compare tag
        asm.load(ctx.tempSlot(), OBJECT_TYPE)
        asm.iconst(tag)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, EXCEPTION_MATCHES)
    }

    fun CATCH_REGISTER_EXCEPTION(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // Register exception and push its
        // index
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        asm.load(ctx.tempSlot(), OBJECT_TYPE)
        asm.invokevirtual(
            getInternalName(Instance::class.java),
            "registerException",
            getMethodDescriptor(INT_TYPE, getType(WasmException::class.java)),
            false,
        )
    }

    // ========= GC Operations =========

    fun CALL_REF(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeId = ins.operand(0).toInt()
        val functionType = ctx.types()[typeId]

        // stack: [args..., funcref]
        // save funcref to temp
        asm.store(ctx.tempSlot(), INT_TYPE)

        // null check
        asm.load(ctx.tempSlot(), INT_TYPE)
        asm.iconst(Value.REF_NULL_VALUE)
        val notNull = Label()
        asm.ificmpne(notNull)
        emitInvokeStatic(asm, ShadedRefs.THROW_NULL_FUNCTION_REFERENCE)
        asm.athrow()
        asm.mark(notNull)

        if (hasTooManyParameters(functionType)) {
            emitBoxValuesOnStack(ctx, asm, functionType.params())
        }

        asm.load(ctx.tempSlot(), INT_TYPE) // funcref as tableIdx arg
        asm.iconst(-1) // no table index (use -1 as sentinel)
        asm.load(ctx.memorySlot(), OBJECT_TYPE)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)

        asm.invokestatic(
            ctx.callIndirectClassName(typeId),
            callIndirectMethodName(typeId),
            callIndirectMethodType(functionType).toMethodDescriptorString(),
            false,
        )

        if (ctx.needsTailCallCheckForType(typeId)) {
            emitTailCallCheck(ctx, asm, functionType)
        }

        if (functionType.returns().size > 1) {
            emitUnboxResult(asm, ctx, functionType.returns())
        }
    }

    fun STRUCT_NEW(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val st = ctx.typeSection().getSubType(typeIdx).compType().structType()!!
        val fieldCount = st.fieldTypes().size

        // Collect all field values into long[]
        val fieldTypes = ArrayList<ValType>(fieldCount)
        for (i in 0 until fieldCount) {
            val ft = st.fieldTypes()[i]
            if (ft.storageType().valType() != null) {
                fieldTypes.add(ft.storageType().valType()!!)
            } else {
                // packed types (i8, i16) are treated as I32
                fieldTypes.add(ValType.I32)
            }
        }
        emitBoxValuesOnStack(ctx, asm, fieldTypes)

        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_NEW)
    }

    fun STRUCT_NEW_DEFAULT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_NEW_DEFAULT)
    }

    fun STRUCT_GET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val fieldIdx = ins.operand(1).toInt()
        // stack: [ref]
        asm.iconst(typeIdx)
        asm.iconst(fieldIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_GET)
        // returns long, convert to proper JVM type
        emitStructGetResult(ctx, asm, typeIdx, fieldIdx)
    }

    fun STRUCT_GET_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val fieldIdx = ins.operand(1).toInt()
        asm.iconst(typeIdx)
        asm.iconst(fieldIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_GET_S)
        // packed types always return as I32
        asm.visitInsn(Opcodes.L2I)
    }

    fun STRUCT_GET_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val fieldIdx = ins.operand(1).toInt()
        asm.iconst(typeIdx)
        asm.iconst(fieldIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_GET_U)
        // packed types always return as I32
        asm.visitInsn(Opcodes.L2I)
    }

    private fun emitStructGetResult(
        ctx: Context,
        asm: InstructionAdapter,
        typeIdx: Int,
        fieldIdx: Int,
    ) {
        val st = ctx.typeSection().getSubType(typeIdx).compType().structType()!!
        val ft = st.fieldTypes()[fieldIdx]
        if (ft.storageType().valType() != null) {
            emitLongToJvm(asm, ft.storageType().valType()!!)
        } else {
            // packed types push as I32
            asm.visitInsn(Opcodes.L2I)
        }
    }

    fun STRUCT_SET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val fieldIdx = ins.operand(1).toInt()
        // stack: [ref, val]
        val st = ctx.typeSection().getSubType(typeIdx).compType().structType()!!
        val ft = st.fieldTypes()[fieldIdx]
        if (ft.storageType().valType() != null) {
            emitJvmToLong(asm, ft.storageType().valType()!!)
        } else {
            // packed types come as I32
            asm.visitInsn(Opcodes.I2L)
        }
        asm.iconst(typeIdx)
        asm.iconst(fieldIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.STRUCT_SET)
    }

    fun ARRAY_NEW(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        // stack: [initVal, len]
        // save len to temp
        asm.store(ctx.tempSlot(), INT_TYPE)
        // convert initVal to long
        val at = ctx.typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().valType() != null) {
            emitJvmToLong(asm, at.fieldType().storageType().valType()!!)
        } else {
            asm.visitInsn(Opcodes.I2L)
        }
        asm.load(ctx.tempSlot(), INT_TYPE)
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_NEW)
    }

    fun ARRAY_NEW_DEFAULT(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        // stack: [len]
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_NEW_DEFAULT)
    }

    fun ARRAY_NEW_FIXED(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val len = ins.operand(1).toInt()
        val at = ctx.typeSection().getSubType(typeIdx).compType().arrayType()!!
        val elemType =
            if (at.fieldType().storageType().valType() != null) {
                at.fieldType().storageType().valType()!!
            } else {
                ValType.I32
            }
        val types = ArrayList<ValType>(len)
        for (i in 0 until len) {
            types.add(elemType)
        }
        emitBoxValuesOnStack(ctx, asm, types)
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_NEW_FIXED)
    }

    fun ARRAY_NEW_DATA(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val dataIdx = ins.operand(1).toInt()
        // stack: [offset, len]
        // save len to temp, keep offset
        asm.store(ctx.tempSlot(), INT_TYPE)
        // stack: [offset]
        asm.load(ctx.tempSlot(), INT_TYPE)
        // stack: [offset, len]
        asm.iconst(typeIdx)
        asm.iconst(dataIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_NEW_DATA)
    }

    fun ARRAY_NEW_ELEM(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val elemIdx = ins.operand(1).toInt()
        // stack: [offset, len]
        asm.store(ctx.tempSlot(), INT_TYPE)
        asm.load(ctx.tempSlot(), INT_TYPE)
        asm.iconst(typeIdx)
        asm.iconst(elemIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_NEW_ELEM)
    }

    fun ARRAY_GET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        // stack: [ref, idx]
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_GET)
        emitArrayGetResult(ctx, asm, typeIdx)
    }

    fun ARRAY_GET_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_GET_S)
        asm.visitInsn(Opcodes.L2I)
    }

    fun ARRAY_GET_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_GET_U)
        asm.visitInsn(Opcodes.L2I)
    }

    private fun emitArrayGetResult(ctx: Context, asm: InstructionAdapter, typeIdx: Int) {
        val at = ctx.typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().valType() != null) {
            emitLongToJvm(asm, at.fieldType().storageType().valType()!!)
        } else {
            asm.visitInsn(Opcodes.L2I)
        }
    }

    fun ARRAY_SET(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        // stack: [ref, idx, val]
        val at = ctx.typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().valType() != null) {
            emitJvmToLong(asm, at.fieldType().storageType().valType()!!)
        } else {
            asm.visitInsn(Opcodes.I2L)
        }
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_SET)
    }

    fun ARRAY_LEN(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [ref]
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_LEN)
    }

    fun ARRAY_FILL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        // stack: [ref, offset, val, len]
        // save len to temp
        asm.store(ctx.tempSlot(), INT_TYPE)
        // stack: [ref, offset, val]
        val at = ctx.typeSection().getSubType(typeIdx).compType().arrayType()!!
        if (at.fieldType().storageType().valType() != null) {
            emitJvmToLong(asm, at.fieldType().storageType().valType()!!)
        } else {
            asm.visitInsn(Opcodes.I2L)
        }
        // stack: [ref, offset, val_as_long]
        asm.load(ctx.tempSlot(), INT_TYPE)
        asm.iconst(typeIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_FILL)
    }

    fun ARRAY_COPY(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [dstRef, dstOff, srcRef, srcOff, len]
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_COPY)
    }

    fun ARRAY_INIT_DATA(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val dataIdx = ins.operand(1).toInt()
        // stack: [ref, dstOff, srcOff, len]
        asm.iconst(typeIdx)
        asm.iconst(dataIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_INIT_DATA)
    }

    fun ARRAY_INIT_ELEM(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val typeIdx = ins.operand(0).toInt()
        val elemIdx = ins.operand(1).toInt()
        // stack: [ref, dstOff, srcOff, len]
        asm.iconst(typeIdx)
        asm.iconst(elemIdx)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.ARRAY_INIT_ELEM)
    }

    fun REF_TEST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val heapType = ins.operand(0).toInt()
        val srcHeapType = ins.operand(1).toInt()
        // stack: [ref]
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.REF_TEST)
    }

    fun REF_TEST_NULL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val heapType = ins.operand(0).toInt()
        val srcHeapType = ins.operand(1).toInt()
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.REF_TEST_NULL)
    }

    fun CAST_TEST(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val heapType = ins.operand(0).toInt()
        val srcHeapType = ins.operand(1).toInt()
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.CAST_TEST)
    }

    fun CAST_TEST_NULL(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        val heapType = ins.operand(0).toInt()
        val srcHeapType = ins.operand(1).toInt()
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.CAST_TEST_NULL)
    }

    fun REF_I31(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [val]
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.REF_I31)
    }

    fun I31_GET_S(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [ref]
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.I31_GET_S)
    }

    fun I31_GET_U(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [ref]
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.I31_GET_U)
    }

    fun ANY_CONVERT_EXTERN(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // identity - no-op at runtime
    }

    fun EXTERN_CONVERT_ANY(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // identity - no-op at runtime
    }

    fun BR_ON_NULL_CHECK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [ref]
        // DUP the ref, compare against Value.REF_NULL_VALUE
        asm.dup()
        asm.iconst(Value.REF_NULL_VALUE)
        // result is used by following IFEQ/IFNE
        // if ref == null -> 0 (equal), used with IFEQ to branch
        // we want: push 1 if null, 0 if not null
        val isNull = Label()
        val end = Label()
        asm.ificmpeq(isNull)
        asm.iconst(0) // not null
        asm.goTo(end)
        asm.mark(isNull)
        asm.iconst(1) // is null
        asm.mark(end)
    }

    fun BR_ON_NON_NULL_CHECK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // stack: [ref]
        asm.dup()
        asm.iconst(Value.REF_NULL_VALUE)
        val isNull = Label()
        val end = Label()
        asm.ificmpeq(isNull)
        asm.iconst(1) // non-null
        asm.goTo(end)
        asm.mark(isNull)
        asm.iconst(0) // is null
        asm.mark(end)
    }

    fun BR_ON_CAST_CHECK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // operands: [nullable(bool), heapType, srcHeapType]
        val nullable = ins.operand(0) != 0L
        val heapType = ins.operand(1).toInt()
        val srcHeapType = ins.operand(2).toInt()
        // stack: [ref]
        asm.dup()
        asm.iconst(if (nullable) 1 else 0)
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.HEAP_TYPE_MATCH)
        // result: boolean on stack (1 if matches, 0 if not)
    }

    fun BR_ON_CAST_FAIL_CHECK(ctx: Context, ins: CompilerInstruction, asm: InstructionAdapter) {
        // Same as BR_ON_CAST_CHECK but inverted: branch if NOT matching
        val nullable = ins.operand(0) != 0L
        val heapType = ins.operand(1).toInt()
        val srcHeapType = ins.operand(2).toInt()
        // stack: [ref]
        asm.dup()
        asm.iconst(if (nullable) 1 else 0)
        asm.iconst(heapType)
        asm.iconst(srcHeapType)
        asm.load(ctx.instanceSlot(), OBJECT_TYPE)
        emitInvokeStatic(asm, ShadedRefs.HEAP_TYPE_MATCH)
        // invert: 1 if NOT matching (branch), 0 if matching (don't branch)
        val wasTrue = Label()
        val end = Label()
        asm.ifne(wasTrue)
        asm.iconst(1) // didn't match -> branch
        asm.goTo(end)
        asm.mark(wasTrue)
        asm.iconst(0) // matched -> don't branch
        asm.mark(end)
    }
}
