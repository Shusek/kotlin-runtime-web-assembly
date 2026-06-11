package uk.shusek.krwa.compiler.internal

import java.lang.invoke.MethodType
import java.lang.invoke.MethodType.methodType
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.function.IntFunction
import java.util.function.Supplier
import kotlin.math.max
import kotlin.math.min
import org.objectweb.asm.ClassTooLargeException
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type.BOOLEAN_TYPE
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.Type.LONG_TYPE
import org.objectweb.asm.Type.VOID_TYPE
import org.objectweb.asm.Type.getDescriptor
import org.objectweb.asm.Type.getInternalName
import org.objectweb.asm.Type.getMethodDescriptor
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.InstructionAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import uk.shusek.krwa.compiler.InterpreterFallback
import uk.shusek.krwa.compiler.internal.CompilerUtil.asmType
import uk.shusek.krwa.compiler.internal.CompilerUtil.callDispatchMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.callIndirectMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.callIndirectMethodType
import uk.shusek.krwa.compiler.internal.CompilerUtil.callMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.classNameForCallIndirect
import uk.shusek.krwa.compiler.internal.CompilerUtil.classNameForDispatch
import uk.shusek.krwa.compiler.internal.CompilerUtil.defaultValue
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeFunction
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeStatic
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitInvokeVirtual
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitJvmToLong
import uk.shusek.krwa.compiler.internal.CompilerUtil.emitLongToJvm
import uk.shusek.krwa.compiler.internal.CompilerUtil.hasTooManyParameters
import uk.shusek.krwa.compiler.internal.CompilerUtil.internalClassName
import uk.shusek.krwa.compiler.internal.CompilerUtil.jvmReturnType
import uk.shusek.krwa.compiler.internal.CompilerUtil.localType
import uk.shusek.krwa.compiler.internal.CompilerUtil.methodNameForFunc
import uk.shusek.krwa.compiler.internal.CompilerUtil.methodTypeFor
import uk.shusek.krwa.compiler.internal.CompilerUtil.rawMethodTypeFor
import uk.shusek.krwa.compiler.internal.CompilerUtil.slotCount
import uk.shusek.krwa.compiler.internal.CompilerUtil.valueMethodName
import uk.shusek.krwa.compiler.internal.CompilerUtil.valueMethodType
import uk.shusek.krwa.compiler.internal.ShadedRefs.AOT_INTERPRETER_MACHINE_CALL
import uk.shusek.krwa.compiler.internal.ShadedRefs.CALL_HOST_FUNCTION
import uk.shusek.krwa.compiler.internal.ShadedRefs.CALL_INDIRECT
import uk.shusek.krwa.compiler.internal.ShadedRefs.CALL_INDIRECT_ON_INTERPRETER
import uk.shusek.krwa.compiler.internal.ShadedRefs.CHECK_INTERRUPTION
import uk.shusek.krwa.compiler.internal.ShadedRefs.INSTANCE_MEMORY
import uk.shusek.krwa.compiler.internal.ShadedRefs.INSTANCE_TABLE
import uk.shusek.krwa.compiler.internal.ShadedRefs.TABLE_INSTANCE
import uk.shusek.krwa.compiler.internal.ShadedRefs.TABLE_REQUIRED_REF
import uk.shusek.krwa.compiler.internal.ShadedRefs.THROW_CALL_STACK_EXHAUSTED
import uk.shusek.krwa.compiler.internal.ShadedRefs.THROW_INDIRECT_CALL_TYPE_MISMATCH
import uk.shusek.krwa.compiler.internal.ShadedRefs.THROW_UNKNOWN_FUNCTION
import uk.shusek.krwa.compiler.internal.Shader.createShadedClass
import uk.shusek.krwa.compiler.internal.Shader.shadedClassRemapper
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.runtime.WasmException
import uk.shusek.krwa.runtime.internal.CompilerInterpreterMachine
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.ValType

class Compiler
private constructor(
    module: WasmModule?,
    className: String?,
    maxFunctionsPerClass: Int,
    interpreterFallback: InterpreterFallback?,
    interpretedFunctions: Set<Int>?,
    private val classCollectorFactory: Supplier<ClassCollector>,
) {
    private val className: String = java.util.Objects.requireNonNull(className, "className")!!
    private val module: WasmModule = java.util.Objects.requireNonNull(module, "module")!!
    private val analyzer = WasmAnalyzer(this.module)
    private val functionImports = this.module.importSection().count(ExternalType.FUNCTION)
    private val functionTypes: List<FunctionType>
    private var collector: ClassCollector = classCollectorFactory.get()
    private var maxFunctionsPerClass: Int = maxFunctionsPerClass
    private val interpretedFunctions: HashSet<Int>
    private val interpreterFallback: InterpreterFallback
    private val callRefTypeIds: Set<Int>
    private val tailCallFunctions: BooleanArray
    private val tailCallTypes: BooleanArray
    private val moduleHasTailCalls: Boolean
    private var useBridgeClasses = false
    private var callIndirectClassResolver: IntFunction<String>? = null

    init {
        if (interpretedFunctions == null || interpretedFunctions.isEmpty()) {
            this.interpretedFunctions = HashSet()
            this.interpreterFallback = interpreterFallback ?: InterpreterFallback.WARN
        } else if (interpreterFallback != null && interpreterFallback != InterpreterFallback.FAIL) {
            // If a fixed interpreted-function set is provided, every unlisted function must fail.
            throw IllegalArgumentException(
                "InterpreterFallback must be set to FAIL if a fixed set of interpreted" +
                    " functions is provided"
            )
        } else {
            this.interpretedFunctions = HashSet(interpretedFunctions)
            this.interpreterFallback = InterpreterFallback.FAIL
        }

        functionTypes = analyzer.functionTypes()
        callRefTypeIds = collectCallRefTypeIds()
        tailCallFunctions = analyzer.tailCallFunctions()
        tailCallTypes = analyzer.tailCallTypes()
        moduleHasTailCalls = analyzer.hasTailCalls()
    }

    private fun collectCallRefTypeIds(): Set<Int> {
        val result = HashSet<Int>()
        val funcCount = module.functionSection().functionCount()
        for (i in 0 until funcCount) {
            val body = module.codeSection().getFunctionBody(i)
            for (ins in body.instructions()) {
                if (ins.opcode() == OpCode.CALL_REF || ins.opcode() == OpCode.RETURN_CALL_REF) {
                    result.add(ins.operand(0).toInt())
                }
            }
        }
        return result
    }

    fun compile(): CompilerResult {
        while (true) {
            try {
                compileExtraClasses()
                val bytes = compileClass()
                collector.putMainClass(className, bytes)
                break
            } catch (e: ClassTooLargeException) {
                if (useBridgeClasses) {
                    throw e
                }
                // Retry with call_indirect methods split into bridge classes.
                useBridgeClasses = true
                collector = classCollectorFactory.get()
            }
        }
        return CompilerResult(collector, java.util.Set.copyOf(interpretedFunctions))
    }

    private fun compileExtraClasses() {
        createShadedClass(className, collector)

        // When call_indirect methods are moved to bridge classes, compile them first so the
        // resolver is built before FuncGroup compilation.
        if (useBridgeClasses) {
            compileCallIndirectBridgeClasses()
        }

        val totalFunctions = functionImports + module.functionSection().functionCount()
        val originalMaxFunctionsPerClass = maxFunctionsPerClass
        while (true) {
            try {
                maxFunctionsPerClass =
                    loadChunkedClass(totalFunctions, maxFunctionsPerClass) {
                        collector,
                        start,
                        end,
                        chunkSize ->
                        maxFunctionsPerClass = chunkSize
                        val className = classNameForFuncGroup(this.className, start)
                        compileExtraClass(
                            collector,
                            className,
                            emitFunctionGroup(start, end, internalClassName(this.className)),
                        )
                    }
                break
            } catch (e: MethodTooLargeException) {
                val methodName = e.methodName
                if (methodName.startsWith("func_")) {
                    val funcId = methodName.substring("func_".length).toInt()

                    var functionDescription = "WASM function index: $funcId"
                    module.nameSection()?.let { nameSection ->
                        val name = nameSection.nameOfFunction(funcId)
                        if (name != null) {
                            functionDescription += String.format(" (name: %s)", name)
                        }
                    }

                    when (interpreterFallback) {
                        InterpreterFallback.SILENT -> Unit
                        InterpreterFallback.WARN ->
                            System.err.println(
                                "Warning: using interpreted mode for $functionDescription"
                            )
                        InterpreterFallback.FAIL ->
                            throw WasmEngineException(
                                "WASM function size exceeds the Java method size limits and" +
                                    " cannot be compiled to Java bytecode. It can only be run" +
                                    " in the interpreter. Either reduce the size of the" +
                                    " function or enable the interpreter fallback mode: " +
                                    functionDescription,
                                e,
                            )
                    }

                    interpretedFunctions.add(funcId)
                    maxFunctionsPerClass = originalMaxFunctionsPerClass
                } else {
                    throw e
                }
            }
        }

        if (functionTypes.isNotEmpty()) {
            compileMachineCallClass()
        }
    }

    private fun compileCallIndirectBridgeClasses() {
        @Suppress("UNCHECKED_CAST")
        val allTypes = module.typeSection().types() as Array<FunctionType?>
        val mainInternalClassName = internalClassName(className)

        val chunkSize =
            loadChunkedClass(allTypes.size, DEFAULT_MAX_FUNCTIONS_PER_CLASS) {
                collector,
                start,
                end,
                _ ->
                val bridgeClassName = className + "CallIndirectBridge_" + start
                compileExtraClass(collector, bridgeClassName) { cw ->
                    for (i in start until end) {
                        val typeId = i
                        val type = allTypes[i] ?: continue
                        emitFunction(
                            cw,
                            callIndirectMethodName(typeId),
                            callIndirectMethodType(type),
                            true,
                        ) { asm ->
                            compileCallIndirect(mainInternalClassName, typeId, type, asm)
                        }
                    }
                }
            }

        val bridgePrefix = internalClassName(className) + "CallIndirectBridge_"
        callIndirectClassResolver = IntFunction { typeId ->
            val start = (typeId / chunkSize) * chunkSize
            bridgePrefix + start
        }
    }

    private fun interface ChunkedClassEmitter {
        fun emit(collector: ClassCollector, start: Int, end: Int, chunkSize: Int)
    }

    /**
     * Loads a chunked class based on the given size and chunk size.
     *
     * This method attempts to load a class in chunks to avoid MethodTooLargeException or
     * ClassTooLargeException. It dynamically adjusts the chunk size based on the size of the class
     * to be loaded and the maximum size that can be loaded without causing an exception. The method
     * returns the final chunk size used for loading.
     */
    private fun loadChunkedClass(size: Int, chunkSize: Int, emitter: ChunkedClassEmitter): Int {
        var currentChunkSize = chunkSize
        var collector = classCollectorFactory.get()
        while (true) {
            try {
                val chunks = size / currentChunkSize + if (size % currentChunkSize == 0) 0 else 1
                for (i in 0 until chunks) {
                    val start = i * currentChunkSize
                    val end = min(start + currentChunkSize, size)
                    emitter.emit(collector, start, end, currentChunkSize)
                }
                break
            } catch (e: ClassTooLargeException) {
                currentChunkSize = currentChunkSize shr 1
                if (currentChunkSize == 0) {
                    throw e
                }
                collector = classCollectorFactory.get()
            }
        }

        this.collector.putAll(collector)
        return currentChunkSize
    }

    private fun classNameForFuncGroup(prefix: String, funcId: Int): String =
        prefix + "FuncGroup_" + (funcId / maxFunctionsPerClass)

    private fun emitFunctionGroup(
        start: Int,
        end: Int,
        internalClassName: String,
    ): (ClassVisitor) -> Unit = { classWriter ->
        for (i in start until end) {
            var body: FunctionBody? = null
            try {
                val funcId = i
                val type = functionTypes[funcId]

                if (i < functionImports) {
                    emitFunction(
                        classWriter,
                        methodNameForFunc(funcId),
                        methodTypeFor(type),
                        true,
                    ) { asm ->
                        compileHostFunction(funcId, type, asm)
                    }
                } else {
                    body = module.codeSection().getFunctionBody(i - functionImports)
                    val bodyCopy = body

                    emitFunction(
                        classWriter,
                        methodNameForFunc(funcId),
                        methodTypeFor(type),
                        true,
                    ) { asm ->
                        compileFunction(internalClassName, funcId, type, bodyCopy, asm)
                    }

                    emitFunction(classWriter, callMethodName(funcId), CALL_METHOD_TYPE, true) { asm
                        ->
                        compileCallFunction(funcId, type, asm)
                    }
                }
            } catch (e: MethodTooLargeException) {
                throw handleMethodTooLarge(e, module)
            }
        }
    }

    private fun compileClass(): ByteArray {
        val internalClass = internalClassName(className)

        val binaryWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val classWriter: ClassVisitor = shadedClassRemapper(binaryWriter, className)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            internalClass,
            null,
            getInternalName(Any::class.java),
            arrayOf(getInternalName(Machine::class.java)),
        )

        classWriter.visitSource("wasm", null)

        classWriter.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
            "instance",
            getDescriptor(Instance::class.java),
            null,
            null,
        )

        if (interpretedFunctions.isNotEmpty()) {
            classWriter.visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
                "compilerInterpreterMachine",
                getDescriptor(CompilerInterpreterMachine::class.java),
                null,
                null,
            )
        }

        emitFunction(classWriter, "<init>", methodType(Void.TYPE, Instance::class.java), false) {
            asm ->
            compileConstructor(asm, internalClass)
        }

        emitFunction(
            classWriter,
            "call",
            methodType(LongArray::class.java, Integer.TYPE, LongArray::class.java),
            false,
        ) { asm ->
            compileMachineCall(internalClass, asm)
        }

        if (!useBridgeClasses) {
            @Suppress("UNCHECKED_CAST")
            val allTypes = module.typeSection().types() as Array<FunctionType?>
            for (i in allTypes.indices) {
                val typeId = i
                val type = allTypes[i] ?: continue
                emitFunction(
                    classWriter,
                    callIndirectMethodName(typeId),
                    callIndirectMethodType(type),
                    true,
                ) { asm ->
                    compileCallIndirect(internalClass, typeId, type, asm)
                }
            }
        }

        val seenValueMethods = HashSet<String>()
        val returnTypes = functionTypes.map { it.returns() }.filter { it.size > 1 }.toSet()
        for (types in returnTypes) {
            val methodName = valueMethodName(types)
            if (!seenValueMethods.add(methodName)) {
                continue
            }
            emitFunction(classWriter, methodName, valueMethodType(types), true) { asm ->
                emitBoxArguments(asm, types)
                asm.areturn(OBJECT_TYPE)
            }
        }

        classWriter.visitEnd()

        try {
            return binaryWriter.toByteArray()
        } catch (e: MethodTooLargeException) {
            throw handleMethodTooLarge(e, module)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isFuncTypeMatch(
        expectedTypeId: Int,
        funcIdx: Int,
        expectedType: FunctionType,
    ): Boolean {
        val funcTypeIdx = analyzer.functionTypeIndex(funcIdx)
        if (expectedTypeId == funcTypeIdx) {
            return true
        }
        return ValType.heapTypeSubtype(funcTypeIdx, expectedTypeId, module.typeSection())
    }

    private fun compileConstructor(asm: InstructionAdapter, internalClassName: String) {
        emitCallSuper(asm)

        asm.load(0, OBJECT_TYPE)
        asm.load(1, OBJECT_TYPE)
        asm.putfield(internalClassName, "instance", getDescriptor(Instance::class.java))

        if (interpretedFunctions.isNotEmpty()) {
            asm.load(0, OBJECT_TYPE)
            asm.anew(AOT_INTERPRETER_MACHINE_TYPE)
            asm.dup()
            asm.load(1, OBJECT_TYPE)

            val funcIds = ArrayList(interpretedFunctions)
            asm.iconst(funcIds.size)
            asm.newarray(INT_TYPE)
            for (i in funcIds.indices) {
                asm.dup()
                asm.iconst(i)
                asm.iconst(funcIds[i])
                asm.astore(INT_TYPE)
            }

            asm.invokespecial(
                AOT_INTERPRETER_MACHINE_TYPE.internalName,
                "<init>",
                getMethodDescriptor(VOID_TYPE, INSTANCE_TYPE, INT_ARRAY_TYPE),
                false,
            )
            asm.putfield(
                internalClassName,
                "compilerInterpreterMachine",
                getDescriptor(CompilerInterpreterMachine::class.java),
            )
        }

        asm.areturn(VOID_TYPE)
    }

    private fun compileMachineCall(internalClassName: String, asm: InstructionAdapter) {
        if (functionTypes.isEmpty()) {
            asm.load(1, INT_TYPE)
            emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION)
            asm.athrow()
            return
        }

        if (interpretedFunctions.isNotEmpty()) {
            val invalid = Label()
            val keys = interpretedFunctions.sorted().toIntArray()
            val labels = Array(interpretedFunctions.size) { Label() }
            asm.load(1, INT_TYPE)
            asm.lookupswitch(invalid, keys, labels)
            for (i in interpretedFunctions.indices) {
                asm.mark(labels[i])

                asm.load(0, OBJECT_TYPE)
                asm.getfield(
                    internalClassName,
                    "compilerInterpreterMachine",
                    getDescriptor(CompilerInterpreterMachine::class.java),
                )
                asm.load(1, INT_TYPE)
                asm.load(2, OBJECT_TYPE)

                asm.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    AOT_INTERPRETER_MACHINE_TYPE.internalName,
                    "call",
                    getMethodDescriptor(AOT_INTERPRETER_MACHINE_CALL),
                    false,
                )
                asm.areturn(OBJECT_TYPE)
            }
            asm.mark(invalid)
        }

        if (moduleHasTailCalls) {
            compileMachineCallWithTailCalls(internalClassName, asm)
        } else {
            compileMachineCallSimple(internalClassName, asm)
        }
    }

    private fun compileMachineCallSimple(internalClassName: String, asm: InstructionAdapter) {
        val start = Label()
        val end = Label()
        asm.visitTryCatchBlock(start, end, end, getInternalName(StackOverflowError::class.java))
        asm.mark(start)

        asm.load(0, OBJECT_TYPE)
        asm.getfield(internalClassName, "instance", getDescriptor(Instance::class.java))
        asm.dup()
        emitInvokeVirtual(asm, INSTANCE_MEMORY)
        asm.load(1, INT_TYPE)
        asm.load(2, OBJECT_TYPE)

        asm.invokestatic(
            internalClassName + "MachineCall",
            "call",
            MACHINE_CALL_METHOD_TYPE.toMethodDescriptorString(),
            false,
        )
        asm.areturn(OBJECT_TYPE)

        asm.mark(end)
        emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED)
        asm.athrow()
    }

    private fun compileMachineCallWithTailCalls(
        internalClassName: String,
        asm: InstructionAdapter,
    ) {
        val start = Label()
        val end = Label()
        val soeCatch = Label()
        asm.visitTryCatchBlock(
            start,
            end,
            soeCatch,
            getInternalName(StackOverflowError::class.java),
        )
        asm.mark(start)

        asm.load(0, OBJECT_TYPE)
        asm.getfield(internalClassName, "instance", getDescriptor(Instance::class.java))
        asm.store(3, OBJECT_TYPE)

        val loopStart = Label()
        asm.mark(loopStart)

        asm.load(3, OBJECT_TYPE)
        asm.dup()
        emitInvokeVirtual(asm, INSTANCE_MEMORY)
        asm.load(1, INT_TYPE)
        asm.load(2, OBJECT_TYPE)

        asm.invokestatic(
            internalClassName + "MachineCall",
            "call",
            MACHINE_CALL_METHOD_TYPE.toMethodDescriptorString(),
            false,
        )

        asm.load(3, OBJECT_TYPE)
        asm.invokevirtual(
            getInternalName(Instance::class.java),
            "isTailCallPending",
            getMethodDescriptor(BOOLEAN_TYPE),
            false,
        )
        val returnResult = Label()
        asm.ifeq(returnResult)

        asm.pop()
        asm.load(3, OBJECT_TYPE)
        asm.invokevirtual(
            getInternalName(Instance::class.java),
            "tailCallFuncId",
            getMethodDescriptor(INT_TYPE),
            false,
        )
        asm.store(1, INT_TYPE)
        asm.load(3, OBJECT_TYPE)
        asm.invokevirtual(
            getInternalName(Instance::class.java),
            "tailCallArgs",
            getMethodDescriptor(getType(LongArray::class.java)),
            false,
        )
        asm.store(2, OBJECT_TYPE)
        asm.load(3, OBJECT_TYPE)
        asm.invokevirtual(
            getInternalName(Instance::class.java),
            "clearTailCall",
            getMethodDescriptor(VOID_TYPE),
            false,
        )
        asm.goTo(loopStart)

        asm.mark(returnResult)
        asm.mark(end)
        asm.areturn(OBJECT_TYPE)

        asm.mark(soeCatch)
        emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED)
        asm.athrow()
    }

    private fun compileMachineCallClass() {
        val binaryWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val classWriter: ClassVisitor = shadedClassRemapper(binaryWriter, className)
        val machineCallClassName = internalClassName(className + "MachineCall")

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            machineCallClassName,
            null,
            getInternalName(Any::class.java),
            null,
        )

        emitFunction(classWriter, "<init>", methodType(Void.TYPE), false) { asm ->
            emitCallSuper(asm)
            asm.areturn(VOID_TYPE)
        }

        val callMethod: (InstructionAdapter) -> Unit =
            if (functionTypes.size < MAX_DISPATCH_METHODS) {
                { asm -> compileMachineCallInvoke(asm, 0, functionTypes.size) }
            } else {
                var maxMachineCallMethods = MAX_DISPATCH_METHODS shl 2
                maxMachineCallMethods =
                    loadChunkedClass(functionTypes.size, maxMachineCallMethods) {
                        collector,
                        start,
                        end,
                        _ ->
                        compileExtraClass(collector, classNameForDispatch(className, start)) { cw ->
                            emitFunction(
                                cw,
                                callDispatchMethodName(start),
                                MACHINE_CALL_METHOD_TYPE,
                                true,
                            ) { asm ->
                                compileMachineCallInvoke(asm, start, end)
                            }
                        }
                    }
                compileMachineCallDispatch(maxMachineCallMethods)
            }
        emitFunction(classWriter, "call", MACHINE_CALL_METHOD_TYPE, true, callMethod)

        collector.put(machineCallClassName, binaryWriter.toByteArray())
    }

    private fun compileExtraClass(
        collector: ClassCollector,
        name: String,
        consumer: (ClassVisitor) -> Unit,
    ) {
        val binaryWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val classWriter: ClassVisitor = shadedClassRemapper(binaryWriter, className)
        val internalClassName = internalClassName(name)
        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            internalClassName,
            null,
            getInternalName(Any::class.java),
            null,
        )
        consumer(classWriter)
        collector.put(name, binaryWriter.toByteArray())
    }

    private fun compileMachineCallDispatch(
        maxMachineCallMethods: Int
    ): (InstructionAdapter) -> Unit = { asm ->
        asm.load(0, OBJECT_TYPE)
        asm.load(1, OBJECT_TYPE)
        asm.load(2, INT_TYPE)
        asm.load(3, OBJECT_TYPE)

        assert(Integer.bitCount(maxMachineCallMethods) == 1)
        val shift = Integer.numberOfTrailingZeros(maxMachineCallMethods)

        val labels = Array(((functionTypes.size - 1) shr shift) + 1) { Label() }

        asm.load(2, INT_TYPE)
        asm.iconst(shift)
        asm.shr(INT_TYPE)
        asm.tableswitch(0, labels.size - 1, labels[0], *labels)

        for (i in labels.indices) {
            asm.mark(labels[i])
            asm.invokestatic(
                internalClassName(classNameForDispatch(className, i shl shift)),
                callDispatchMethodName(i shl shift),
                MACHINE_CALL_METHOD_TYPE.toMethodDescriptorString(),
                false,
            )
            asm.areturn(OBJECT_TYPE)
        }
    }

    private fun compileMachineCallInvoke(asm: InstructionAdapter, start: Int, end: Int) {
        asm.load(0, OBJECT_TYPE)
        asm.load(1, OBJECT_TYPE)
        asm.load(3, OBJECT_TYPE)

        val defaultLabel = Label()
        val hostLabel = Label()
        val labels =
            Array(end - start) { id -> if (id + start < functionImports) hostLabel else Label() }

        asm.load(2, INT_TYPE)
        asm.tableswitch(start, end - 1, defaultLabel, *labels)

        for (id in max(start, functionImports) until end) {
            asm.mark(labels[id - start])
            asm.invokestatic(
                internalClassName(classNameForFuncGroup(className, id)),
                callMethodName(id),
                CALL_METHOD_TYPE.toMethodDescriptorString(),
                false,
            )
            asm.areturn(OBJECT_TYPE)
        }

        if (functionImports > start) {
            asm.mark(hostLabel)
            asm.pop()
            asm.pop()
            asm.load(2, INT_TYPE)
            asm.load(3, OBJECT_TYPE)
            emitInvokeStatic(asm, CALL_HOST_FUNCTION)
            asm.areturn(OBJECT_TYPE)
        }

        asm.mark(defaultLabel)
        asm.load(2, INT_TYPE)
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION)
        asm.athrow()
    }

    private fun compileCallFunction(funcId: Int, type: FunctionType, asm: InstructionAdapter) {
        if (hasTooManyParameters(type)) {
            asm.load(2, LONG_ARRAY_TYPE)
        } else {
            for (i in type.params().indices) {
                val param = type.params()[i]
                asm.load(2, OBJECT_TYPE)
                asm.iconst(i)
                asm.aload(LONG_TYPE)
                emitLongToJvm(asm, param)
            }
        }

        asm.load(1, OBJECT_TYPE)
        asm.load(0, OBJECT_TYPE)

        emitInvokeFunction(
            asm,
            internalClassName(classNameForFuncGroup(className, funcId)),
            funcId,
            type,
        )

        val returnType = jvmReturnType(type)
        if (returnType == Void.TYPE) {
            asm.aconst(null)
        } else if (returnType != LongArray::class.java) {
            emitJvmToLong(asm, type.returns()[0])
            asm.store(3, LONG_TYPE)
            asm.iconst(1)
            asm.newarray(LONG_TYPE)
            asm.dup()
            asm.iconst(0)
            asm.load(3, LONG_TYPE)
            asm.astore(LONG_TYPE)
        }
        asm.areturn(OBJECT_TYPE)
    }

    private fun compileCallIndirect(
        internalClassName: String,
        typeId: Int,
        type: FunctionType,
        asm: InstructionAdapter,
    ) {
        var slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum()
        if (hasTooManyParameters(type)) {
            slots = 1
        }

        val validIds = ArrayList<Int>()
        for (i in functionTypes.indices) {
            if (isFuncTypeMatch(typeId, i, type)) {
                validIds.add(i)
            }
        }
        val invalid = Label()

        val funcTableIdx = slots
        val tableIdx = slots + 1
        val memory = slots + 2
        val instance = slots + 3

        val table = slots + 4
        val funcId = slots + 5
        val refInstance = slots + 6

        emitInvokeStatic(asm, CHECK_INTERRUPTION)

        val local = Label()
        val other = Label()

        val hasCallRef = callRefTypeIds.contains(typeId)

        if (hasCallRef) {
            asm.load(tableIdx, INT_TYPE)
            asm.iconst(-1)
            val notCallRef = Label()
            asm.ificmpne(notCallRef)

            asm.load(funcTableIdx, INT_TYPE)
            asm.store(funcId, INT_TYPE)
            asm.goTo(local)

            asm.mark(notCallRef)
        }

        asm.load(instance, OBJECT_TYPE)
        asm.load(tableIdx, INT_TYPE)
        emitInvokeVirtual(asm, INSTANCE_TABLE)
        asm.store(table, OBJECT_TYPE)

        asm.load(table, OBJECT_TYPE)
        asm.load(funcTableIdx, INT_TYPE)
        emitInvokeVirtual(asm, TABLE_REQUIRED_REF)
        asm.store(funcId, INT_TYPE)

        asm.load(table, OBJECT_TYPE)
        asm.load(funcTableIdx, INT_TYPE)
        emitInvokeVirtual(asm, TABLE_INSTANCE)
        asm.store(refInstance, OBJECT_TYPE)

        asm.load(refInstance, OBJECT_TYPE)
        asm.ifnull(local)
        asm.load(refInstance, OBJECT_TYPE)
        asm.load(instance, OBJECT_TYPE)
        asm.ifacmpne(other)

        asm.mark(local)
        if (hasTooManyParameters(type)) {
            asm.load(0, LONG_ARRAY_TYPE)
        } else {
            var slot = 0
            for (param in type.params()) {
                asm.load(slot, asmType(param))
                slot += slotCount(param)
            }
        }
        asm.load(memory, OBJECT_TYPE)
        asm.load(instance, OBJECT_TYPE)

        if (validIds.size <= MAX_CALL_INDIRECT_METHODS) {
            val keys = validIds.toIntArray()
            val labels = Array(validIds.size) { Label() }

            asm.load(funcId, INT_TYPE)
            asm.lookupswitch(invalid, keys, labels)

            for (i in validIds.indices) {
                asm.mark(labels[i])
                emitInvokeFunction(
                    asm,
                    classNameForFuncGroup(internalClassName, keys[i]),
                    keys[i],
                    type,
                )
                asm.areturn(getType(jvmReturnType(type)))
            }

            asm.mark(invalid)
            emitInvokeStatic(asm, THROW_INDIRECT_CALL_TYPE_MISMATCH)
            asm.athrow()
        } else {
            val applyParams =
                rawMethodTypeFor(type)
                    .appendParameterTypes(Memory::class.java, Instance::class.java, Integer.TYPE)

            val maxCallIndirectMethods = MAX_CALL_INDIRECT_METHODS shl 2
            loadChunkedClass(functionTypes.size, maxCallIndirectMethods) { collector, start, end, _
                ->
                compileExtraClass(
                    collector,
                    classNameForCallIndirect(this.className, typeId, start),
                ) { cw ->
                    emitFunction(cw, "apply", applyParams, true) { a ->
                        compileCallIndirectApply(internalClassName, typeId, type, a, start, end)
                    }
                }
            }

            assert(Integer.bitCount(maxCallIndirectMethods) == 1)
            val shift = Integer.numberOfTrailingZeros(maxCallIndirectMethods)

            val labels = Array(((functionTypes.size - 1) shr shift) + 1) { Label() }

            asm.load(funcId, INT_TYPE)
            asm.iconst(shift)
            asm.shr(INT_TYPE)
            asm.tableswitch(0, labels.size - 1, labels[0], *labels)

            for (i in labels.indices) {
                asm.mark(labels[i])
                asm.load(funcId, INT_TYPE)
                asm.invokestatic(
                    classNameForCallIndirect(internalClassName, typeId, i shl shift),
                    "apply",
                    applyParams.toMethodDescriptorString(),
                    false,
                )
                asm.areturn(getType(jvmReturnType(type)))
            }
        }

        asm.mark(other)

        if (hasTooManyParameters(type)) {
            asm.load(0, LONG_ARRAY_TYPE)
        } else {
            emitBoxArguments(asm, type.params())
        }
        asm.iconst(typeId)
        asm.load(funcId, INT_TYPE)
        asm.load(refInstance, OBJECT_TYPE)

        emitInvokeStatic(asm, CALL_INDIRECT)

        emitUnboxResult(type, asm)
    }

    private fun compileCallIndirectApply(
        internalClassName: String,
        typeId: Int,
        type: FunctionType,
        asm: InstructionAdapter,
        startFunc: Int,
        endFunc: Int,
    ) {
        var slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum()
        if (hasTooManyParameters(type)) {
            slots = 1
        }

        val memory = slots
        val instance = slots + 1
        val funcId = slots + 2

        val validIds = ArrayList<Int>()
        for (i in functionTypes.indices) {
            if (isFuncTypeMatch(typeId, i, type) && startFunc <= i && i < endFunc) {
                validIds.add(i)
            }
        }
        val invalid = Label()

        val keys = validIds.toIntArray()
        val labels = Array(validIds.size) { Label() }

        for (i in type.params().indices) {
            asm.load(i, asmType(type.params()[i]))
        }
        asm.load(memory, OBJECT_TYPE)
        asm.load(instance, OBJECT_TYPE)

        asm.load(funcId, INT_TYPE)
        asm.lookupswitch(invalid, keys, labels)

        for (i in validIds.indices) {
            asm.mark(labels[i])
            emitInvokeFunction(
                asm,
                classNameForFuncGroup(internalClassName, keys[i]),
                keys[i],
                type,
            )
            asm.areturn(getType(jvmReturnType(type)))
            asm.areturn(OBJECT_TYPE)
        }

        asm.mark(invalid)

        asm.load(funcId, INT_TYPE)
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION)
        asm.athrow()
    }

    private fun compileFunction(
        internalClassName: String,
        funcId: Int,
        type: FunctionType,
        body: FunctionBody,
        asm: InstructionAdapter,
    ) {
        if (interpretedFunctions.contains(funcId)) {
            val slots =
                if (hasTooManyParameters(type)) {
                    asm.load(0, LONG_ARRAY_TYPE)
                    1
                } else {
                    emitBoxArguments(asm, type.params())
                    type.params().stream().mapToInt(CompilerUtil::slotCount).sum()
                }
            val refInstance = slots + 1

            asm.iconst(funcId)
            asm.load(refInstance, OBJECT_TYPE)
            emitInvokeStatic(asm, CALL_INDIRECT_ON_INTERPRETER)
            emitUnboxResult(type, asm)
            return
        }

        val analysis = analyzer.analyze(funcId)
        val instructions = analysis.instructions()

        val ctx =
            Context(
                module,
                internalClassName,
                maxFunctionsPerClass,
                analyzer.globalTypes(),
                functionTypes,
                funcId,
                type,
                body,
                tailCallFunctions,
                tailCallTypes,
                if (useBridgeClasses) callIndirectClassResolver!!
                else IntFunction { internalClassName },
                analysis.maxTempSlots(),
            )

        var localsCount = type.params().size
        if (hasTooManyParameters(type)) {
            for (i in type.params().indices) {
                val param = type.params()[i]
                asm.load(0, OBJECT_TYPE)
                asm.iconst(i)
                asm.aload(LONG_TYPE)
                emitLongToJvm(asm, param)
                asm.store(ctx.localSlotIndex(i), asmType(param))
            }
            localsCount = 1
        }

        localsCount += body.localTypes().size
        for (i in type.params().size until localsCount) {
            val localType = localType(type, body, i)
            asm.visitLdcInsn(defaultValue(localType))
            asm.store(ctx.localSlotIndex(i), asmType(localType))
        }
        var trySaveSlot = ctx.trySaveBaseSlot()
        for (trySaveType in analysis.trySaveSlotTypes()) {
            asm.visitLdcInsn(defaultValue(trySaveType))
            asm.store(trySaveSlot, asmType(trySaveType))
            trySaveSlot += slotCount(trySaveType)
        }

        val labels = HashMap<Long, Label>()
        for (ins in instructions) {
            for (target in ins.labelTargets()) {
                labels[target] = Label()
            }
        }

        val visitedTargets = HashSet<Long>()

        for (ins in instructions) {
            when (ins.opcode()) {
                CompilerOpCode.LABEL -> {
                    val label = labels[ins.operand(0)]
                    if (label != null) {
                        asm.mark(label)
                        visitedTargets.add(ins.operand(0))
                    }
                }
                CompilerOpCode.GOTO -> {
                    if (visitedTargets.contains(ins.operand(0))) {
                        emitInvokeStatic(asm, CHECK_INTERRUPTION)
                    }
                    asm.goTo(labels[ins.operand(0)]!!)
                }
                CompilerOpCode.IFEQ -> {
                    if (visitedTargets.contains(ins.operand(0))) {
                        throw WasmEngineException("Unexpected backward jump")
                    }
                    asm.ifeq(labels[ins.operand(0)]!!)
                }
                CompilerOpCode.IFNE -> {
                    if (visitedTargets.contains(ins.operand(0))) {
                        val skip = Label()
                        asm.ifeq(skip)
                        emitInvokeStatic(asm, CHECK_INTERRUPTION)
                        asm.goTo(labels[ins.operand(0)]!!)
                        asm.mark(skip)
                    } else {
                        asm.ifne(labels[ins.operand(0)]!!)
                    }
                }
                CompilerOpCode.SWITCH -> {
                    if (ins.operands().anyMatch { visitedTargets.contains(it) }) {
                        emitInvokeStatic(asm, CHECK_INTERRUPTION)
                    }
                    val table = Array(ins.operandCount() - 1) { labels[ins.operand(it)]!! }
                    val defaultLabel = labels[ins.operand(table.size)]!!
                    asm.tableswitch(0, table.size - 1, defaultLabel, *table)
                }
                CompilerOpCode.TRY_CATCH_BLOCK ->
                    asm.visitTryCatchBlock(
                        labels[ins.operand(0)]!!,
                        labels[ins.operand(1)]!!,
                        labels[ins.operand(2)]!!,
                        getInternalName(WasmException::class.java),
                    )
                else -> {
                    val emitter =
                        EmitterMap.EMITTERS[ins.opcode()]
                            ?: throw WasmEngineException("Unhandled opcode: " + ins.opcode())
                    emitter.emit(ctx, ins, asm)
                }
            }
        }
    }

    class Builder internal constructor(private val module: WasmModule?) {
        private var className: String? = null
        private var maxFunctionsPerClass = 0
        private var interpreterFallback: InterpreterFallback? = null
        private var interpretedFunctions: Set<Int>? = null
        private var classCollectorFactory: Supplier<ClassCollector>? = null

        fun withClassName(className: String?): Builder {
            this.className = className
            return this
        }

        fun withMaxFunctionsPerClass(maxFunctionsPerClass: Int): Builder {
            this.maxFunctionsPerClass = maxFunctionsPerClass
            return this
        }

        fun withInterpreterFallback(interpreterFallback: InterpreterFallback?): Builder {
            this.interpreterFallback = interpreterFallback
            return this
        }

        fun withInterpretedFunctions(interpretedFunctions: Set<Int>?): Builder {
            this.interpretedFunctions = interpretedFunctions
            return this
        }

        fun withClassCollectorFactory(classCollectorFactory: Supplier<ClassCollector>?): Builder {
            this.classCollectorFactory = classCollectorFactory
            return this
        }

        fun build(): Compiler {
            val className = className ?: DEFAULT_CLASS_NAME
            val maxFunctionsPerClass =
                if (maxFunctionsPerClass <= 0) {
                    DEFAULT_MAX_FUNCTIONS_PER_CLASS
                } else {
                    maxFunctionsPerClass
                }
            val classCollectorFactory =
                classCollectorFactory ?: Supplier<ClassCollector> { ClassLoadingCollector() }

            return Compiler(
                module,
                className,
                maxFunctionsPerClass,
                interpreterFallback,
                interpretedFunctions,
                classCollectorFactory,
            )
        }
    }

    companion object {
        const val DEFAULT_CLASS_NAME: String = "uk.shusek.krwa.\$gen.CompiledMachine"
        private val LONG_ARRAY_TYPE = getType(LongArray::class.java)
        private val INT_ARRAY_TYPE = getType(IntArray::class.java)
        private val AOT_INTERPRETER_MACHINE_TYPE = getType(CompilerInterpreterMachine::class.java)
        private val INSTANCE_TYPE = getType(Instance::class.java)

        private val CALL_METHOD_TYPE: MethodType =
            methodType(
                LongArray::class.java,
                Instance::class.java,
                Memory::class.java,
                LongArray::class.java,
            )

        private val MACHINE_CALL_METHOD_TYPE: MethodType =
            methodType(
                LongArray::class.java,
                Instance::class.java,
                Memory::class.java,
                Integer.TYPE,
                LongArray::class.java,
            )

        // C2 JIT's HugeMethodLimit (default 8KB): methods exceeding this get degraded optimization.
        private val HUGE_METHOD_LIMIT = Integer.getInteger("krwa.hugeMethodLimit", 8000)
        private const val ESTIMATED_BYTES_PER_DISPATCH_ENTRY = 40
        private val MAX_DISPATCH_METHODS =
            Integer.highestOneBit(HUGE_METHOD_LIMIT / ESTIMATED_BYTES_PER_DISPATCH_ENTRY)
        private const val MAX_CALL_INDIRECT_METHODS = 1024
        private const val DEFAULT_MAX_FUNCTIONS_PER_CLASS = 1024 * 12

        @JvmStatic fun builder(module: WasmModule?): Builder = Builder(module)

        private fun handleMethodTooLarge(
            e: MethodTooLargeException,
            module: WasmModule,
        ): RuntimeException {
            var name = e.methodName
            val nameSection = module.nameSection()
            if (name.startsWith("func_") && nameSection != null) {
                val funcId = name.split("_", limit = -1)[1].toInt()
                val function = nameSection.nameOfFunction(funcId)
                if (function != null) {
                    name += " ($function)"
                }
            }
            return WasmEngineException(
                String.format(
                    "JVM bytecode too large for WASM method: %s size=%d",
                    name,
                    e.codeSize,
                ),
                e,
            )
        }

        private fun emitFunction(
            classWriter: ClassVisitor,
            methodName: String,
            methodType: MethodType,
            isStatic: Boolean,
            consumer: (InstructionAdapter) -> Unit,
        ) {
            val methodWriter =
                classWriter.visitMethod(
                    Opcodes.ACC_PUBLIC or if (isStatic) Opcodes.ACC_STATIC else 0,
                    methodName,
                    methodType.toMethodDescriptorString(),
                    null,
                    null,
                )

            methodWriter.visitCode()
            consumer(InstructionAdapter(methodWriter))
            methodWriter.visitMaxs(0, 0)
            methodWriter.visitEnd()
        }

        private fun emitCallSuper(asm: InstructionAdapter) {
            asm.load(0, OBJECT_TYPE)
            asm.invokespecial(
                OBJECT_TYPE.internalName,
                "<init>",
                getMethodDescriptor(VOID_TYPE),
                false,
            )
        }

        private fun compileHostFunction(funcId: Int, type: FunctionType, asm: InstructionAdapter) {
            val slot = type.params().stream().mapToInt(CompilerUtil::slotCount).sum()

            asm.load(slot + 1, OBJECT_TYPE)
            asm.iconst(funcId)
            emitBoxArguments(asm, type.params())

            emitInvokeStatic(asm, CALL_HOST_FUNCTION)

            emitUnboxResult(type, asm)
        }

        private fun emitBoxArguments(asm: InstructionAdapter, types: List<ValType>) {
            var slot = 0
            asm.iconst(types.size)
            asm.newarray(LONG_TYPE)
            for (i in types.indices) {
                asm.dup()
                asm.iconst(i)
                val valType = types[i]
                asm.load(slot, asmType(valType))
                emitJvmToLong(asm, valType)
                asm.astore(LONG_TYPE)
                slot += slotCount(valType)
            }
        }

        private fun emitUnboxResult(type: FunctionType, asm: InstructionAdapter) {
            val returnType = jvmReturnType(type)
            if (returnType == Void.TYPE) {
                asm.areturn(VOID_TYPE)
            } else if (returnType == LongArray::class.java) {
                asm.areturn(OBJECT_TYPE)
            } else {
                asm.iconst(0)
                asm.aload(LONG_TYPE)
                emitLongToJvm(asm, type.returns()[0])
                asm.areturn(getType(returnType))
            }
        }
    }
}
