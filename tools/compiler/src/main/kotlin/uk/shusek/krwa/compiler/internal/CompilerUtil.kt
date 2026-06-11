package uk.shusek.krwa.compiler.internal

import java.lang.invoke.MethodType
import java.lang.invoke.MethodType.methodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value
import uk.shusek.krwa.wasm.types.ValueJvm

object CompilerUtil {
    // The maximum number of wasm parameters that can be passed to a function before we box them
    // since Java methods have a limit of 255 parameters, but we need to reserve a few for the
    // Instance and Memory args.
    private const val MAX_PARAMETER_COUNT = 253

    private val LONG_TO_F32 = valueMethod("longToFloat", java.lang.Long.TYPE)
    private val LONG_TO_F64 = valueMethod("longToDouble", java.lang.Long.TYPE)
    private val F32_TO_LONG = valueMethod("floatToLong", java.lang.Float.TYPE)
    private val F64_TO_LONG = valueMethod("doubleToLong", java.lang.Double.TYPE)

    @JvmStatic
    fun jvmType(type: ValType): Class<*> =
        when (type.opcode()) {
            ValType.ID.I32,
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> Integer.TYPE
            ValType.ID.I64 -> java.lang.Long.TYPE
            ValType.ID.F32 -> java.lang.Float.TYPE
            ValType.ID.F64 -> java.lang.Double.TYPE
            else -> throw IllegalArgumentException("Unsupported ValType: $type")
        }

    @JvmStatic
    fun asmType(type: ValType): Type =
        when (type.opcode()) {
            ValType.ID.I32,
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> Type.INT_TYPE
            ValType.ID.I64 -> Type.LONG_TYPE
            ValType.ID.F32 -> Type.FLOAT_TYPE
            ValType.ID.F64 -> Type.DOUBLE_TYPE
            else -> throw IllegalArgumentException("Unsupported type: $type")
        }

    @JvmStatic
    fun localType(type: FunctionType, body: FunctionBody, localIndex: Int): ValType =
        if (localIndex < type.params().size) {
            type.params()[localIndex]
        } else {
            body.localTypes()[localIndex - type.params().size]
        }

    @JvmStatic
    fun emitLongToJvm(asm: MethodVisitor, type: ValType) {
        when (type.opcode()) {
            ValType.ID.I32,
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> asm.visitInsn(Opcodes.L2I)
            ValType.ID.I64 -> Unit
            ValType.ID.F32 -> emitInvokeStatic(asm, LONG_TO_F32)
            ValType.ID.F64 -> emitInvokeStatic(asm, LONG_TO_F64)
            else -> throw IllegalArgumentException("Unsupported ValType: $type")
        }
    }

    @JvmStatic
    fun emitJvmToLong(asm: MethodVisitor, type: ValType) {
        when (type.opcode()) {
            ValType.ID.I32,
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> asm.visitInsn(Opcodes.I2L)
            ValType.ID.I64 -> Unit
            ValType.ID.F32 -> emitInvokeStatic(asm, F32_TO_LONG)
            ValType.ID.F64 -> emitInvokeStatic(asm, F64_TO_LONG)
            else -> throw IllegalArgumentException("Unsupported ValType: $type")
        }
    }

    @JvmStatic
    fun valueMethodType(types: List<ValType>): MethodType =
        methodType(LongArray::class.java, jvmTypes(types))

    @JvmStatic
    fun callIndirectMethodType(functionType: FunctionType): MethodType =
        rawMethodTypeFor(functionType)
            .appendParameterTypes(
                Integer.TYPE,
                Integer.TYPE,
                Memory::class.java,
                Instance::class.java,
            )

    @JvmStatic
    fun methodTypeFor(type: FunctionType): MethodType =
        rawMethodTypeFor(type).appendParameterTypes(Memory::class.java, Instance::class.java)

    @JvmStatic
    fun hasTooManyParameters(type: FunctionType): Boolean =
        type.params().stream().mapToInt(CompilerUtil::slotCount).sum() > MAX_PARAMETER_COUNT

    @JvmStatic
    fun rawMethodTypeFor(type: FunctionType): MethodType {
        val paramsTypes =
            if (hasTooManyParameters(type)) {
                arrayOf<Class<*>>(LongArray::class.java)
            } else {
                jvmParameterTypes(type)
            }
        return methodType(jvmReturnType(type), paramsTypes)
    }

    @JvmStatic
    fun jvmTypes(types: List<ValType>): Array<Class<*>> =
        types.map(CompilerUtil::jvmType).toTypedArray()

    @JvmStatic fun jvmParameterTypes(type: FunctionType): Array<Class<*>> = jvmTypes(type.params())

    @JvmStatic
    fun jvmReturnType(type: FunctionType): Class<*> =
        when (type.returns().size) {
            0 -> Void.TYPE
            1 -> jvmType(type.returns()[0])
            else -> LongArray::class.java
        }

    @JvmStatic
    fun defaultValue(type: ValType): Any =
        when (type.opcode()) {
            ValType.ID.I32 -> 0
            ValType.ID.I64 -> 0L
            ValType.ID.F32 -> 0.0f
            ValType.ID.F64 -> 0.0
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> Value.REF_NULL_VALUE
            else -> throw IllegalArgumentException("Unsupported ValType: $type")
        }

    @JvmStatic fun slotCount(type: ValType): Int = slotCount(type.id())

    @JvmStatic
    fun slotCount(valTypeId: Long): Int {
        return when ((valTypeId and 0xFFFFFFFFL).toInt()) {
            ValType.ID.I32,
            ValType.ID.F32,
            ValType.ID.Ref,
            ValType.ID.RefNull,
            ValType.ID.ExnRef -> 1
            ValType.ID.I64,
            ValType.ID.F64 -> 2
            else -> throw IllegalArgumentException("Unsupported type id: $valTypeId")
        }
    }

    @JvmStatic
    fun emitPop(asm: MethodVisitor, type: ValType) {
        asm.visitInsn(if (slotCount(type) == 1) Opcodes.POP else Opcodes.POP2)
    }

    @JvmStatic
    fun emitInvokeStatic(asm: MethodVisitor, method: Method) {
        assert(Modifier.isStatic(method.modifiers))
        asm.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(method.declaringClass),
            method.name,
            Type.getMethodDescriptor(method),
            false,
        )
    }

    @JvmStatic
    fun emitInvokeVirtual(asm: MethodVisitor, method: Method) {
        assert(!Modifier.isStatic(method.modifiers))
        assert(!method.declaringClass.isInterface)
        asm.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            Type.getInternalName(method.declaringClass),
            method.name,
            Type.getMethodDescriptor(method),
            false,
        )
    }

    @JvmStatic
    fun emitInvokeFunction(
        asm: MethodVisitor,
        internalClassName: String,
        funcId: Int,
        functionType: FunctionType,
    ) {
        asm.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            internalClassName,
            methodNameForFunc(funcId),
            methodTypeFor(functionType).toMethodDescriptorString(),
            false,
        )
    }

    @JvmStatic
    fun valueMethodName(types: List<ValType>): String =
        "value_" + types.joinToString("_") { it.name().lowercase(Locale.ROOT) }

    @JvmStatic fun methodNameForFunc(funcId: Int): String = "func_$funcId"

    @JvmStatic fun callMethodName(funcId: Int): String = "call_$funcId"

    @JvmStatic fun callIndirectMethodName(typeId: Int): String = "call_indirect_$typeId"

    @JvmStatic fun internalClassName(name: String): String = name.replace('.', '/')

    @JvmStatic fun classNameForDispatch(prefix: String, id: Int): String = prefix + "Dispatch_" + id

    @JvmStatic fun callDispatchMethodName(start: Int): String = "call_dispatch_$start"

    @JvmStatic
    fun classNameForCallIndirect(prefix: String, typeId: Int, start: Int): String =
        prefix + "Indirect_" + typeId + "_" + start

    private fun valueMethod(name: String, type: Class<*>): Method =
        try {
            ValueJvm::class.java.getMethod(name, type)
        } catch (e: NoSuchMethodException) {
            throw AssertionError(e)
        }
}
