package uk.shusek.krwa.component

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

object WitReflection {
    private val NO_CONVERSION = Any()

    @JvmStatic
    fun hostHandler(
        hosts: List<@JvmSuppressWildcards Any>,
        interfaceName: String,
        functionName: String,
    ): HostHandler? {
        for (host in hosts) {
            val direct = findFunctionMethod(host, functionName)
            if (direct != null) {
                return HostHandler { arguments -> invokeHost(host, direct, arguments) }
            }

            val target = nestedInterfaceObject(host, interfaceName) ?: continue
            val method = findFunctionMethod(target, functionName)
            if (method != null) {
                return HostHandler { arguments -> invokeHost(target, method, arguments) }
            }
            val resourceMethod = nestedResourceMethod(target, functionName)
            if (resourceMethod != null) {
                return HostHandler { arguments ->
                    invokeHost(resourceMethod.target, resourceMethod.method, arguments)
                }
            }
        }
        return null
    }

    @JvmStatic
    fun <T : Any> exports(plugin: WasmPlugin, contractType: Class<T>?): T {
        val type = contractType ?: throw NullPointerException("contractType")
        if (!type.isInterface) {
            throw ComponentModelException("export contract must be an interface: ${type.name}")
        }
        return proxy(plugin, inferExportInterface(plugin, type), type)
    }

    private fun <T : Any> proxy(
        plugin: WasmPlugin,
        interfaceName: String?,
        contractType: Class<T>,
    ): T {
        val handler = InvocationHandler { proxyObject, method, args ->
            invokeExport(plugin, interfaceName, proxyObject, method, args)
        }
        return contractType.cast(
            Proxy.newProxyInstance(
                contractType.classLoader,
                arrayOf<Class<*>>(contractType),
                handler,
            )
        )
    }

    private fun invokeExport(
        plugin: WasmPlugin,
        interfaceName: String?,
        proxy: Any,
        method: Method,
        args: Array<Any?>?,
    ): Any? {
        if (method.declaringClass == Any::class.java) {
            return invokeObjectMethod(proxy, method, args)
        }

        val suspendReturnType = suspendReturnType(method)
        val arguments =
            if (suspendReturnType == null) {
                args ?: emptyArray()
            } else {
                val rawArguments = args ?: emptyArray()
                if (rawArguments.isEmpty()) {
                    throw ComponentModelException(
                        "suspend WIT export ${method.name} is missing continuation"
                    )
                }
                rawArguments.copyOf(rawArguments.size - 1)
            }
        if (arguments.isEmpty() && interfaceName == null) {
            val item = findWorldExport(plugin.world(), propertyName(method))
            if (item != null && !item.isFunction) {
                return proxy(plugin, interfaceName(item), method.returnType)
            }
        }

        val binding =
            findFunction(plugin, interfaceName, method)
                ?: throw ComponentModelException("unknown WIT export for method ${method.name}")

        val exportName =
            if (interfaceName == null) {
                binding.publicName
            } else {
                "$interfaceName.${binding.publicName}"
            }
        return if (suspendReturnType == null) {
            val result = plugin.call(exportName, *arguments)
            convertReturn(result, method)
        } else {
            invokeSuspendExport(
                plugin,
                exportName,
                arguments,
                suspendReturnType,
                continuation(args),
            )
        }
    }

    private fun continuation(args: Array<Any?>?): Continuation<Any?> {
        val value =
            args?.lastOrNull()
                ?: throw ComponentModelException("suspend WIT export is missing continuation")
        if (value !is Continuation<*>) {
            throw ComponentModelException("suspend WIT export continuation has unexpected type")
        }
        @Suppress("UNCHECKED_CAST")
        return value as Continuation<Any?>
    }

    private fun invokeSuspendExport(
        plugin: WasmPlugin,
        exportName: String,
        arguments: Array<Any?>,
        returnType: Type,
        continuation: Continuation<Any?>,
    ): Any? {
        val job = continuation.context[Job]
        if (job == null) {
            return convertReturn(plugin.call(exportName, *arguments), returnType)
        }
        return invokeCancellableSuspendExport(
            plugin,
            exportName,
            arguments,
            returnType,
            continuation,
            job,
        )
    }

    private fun invokeCancellableSuspendExport(
        plugin: WasmPlugin,
        exportName: String,
        arguments: Array<Any?>,
        returnType: Type,
        continuation: Continuation<Any?>,
        job: Job,
    ): Any? {
        if (!job.isActive) {
            continuation.resumeWithException(cancellationException())
            return COROUTINE_SUSPENDED
        }
        CoroutineScope(continuation.context).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result =
                    runInterruptible(Dispatchers.IO) {
                        convertReturn(plugin.call(exportName, *arguments), returnType)
                    }
                continuation.resume(result)
            } catch (e: Throwable) {
                if (job.isActive) {
                    continuation.resumeWithException(e)
                } else {
                    continuation.resumeWithException(cancellationException(e))
                }
            }
        }
        return COROUTINE_SUSPENDED
    }

    private fun cancellationException(): CancellationException =
        CancellationException("WIT export coroutine was cancelled")

    private fun cancellationException(cause: Throwable): CancellationException =
        if (cause is CancellationException) {
            cause
        } else {
            CancellationException("WIT export coroutine was cancelled", cause)
        }

    private fun suspendReturnType(method: Method): Type? {
        val parameterTypes = method.parameterTypes
        if (parameterTypes.isEmpty()) {
            return null
        }
        if (!Continuation::class.java.isAssignableFrom(parameterTypes[parameterTypes.lastIndex])) {
            return null
        }
        val genericParameterTypes = method.genericParameterTypes
        if (genericParameterTypes.isEmpty()) {
            return Any::class.java
        }
        val continuationType = genericParameterTypes[genericParameterTypes.lastIndex]
        if (continuationType !is ParameterizedType) {
            return Any::class.java
        }
        val valueType = continuationType.actualTypeArguments.firstOrNull() ?: Any::class.java
        return boundType(valueType)
    }

    private fun invokeObjectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any =
        when (method.name) {
            "toString" -> "WIT export proxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args != null && args.size == 1 && args[0] === proxy
            else -> throw ComponentModelException("unsupported Object method ${method.name}")
        }

    private fun findFunction(
        plugin: WasmPlugin,
        interfaceName: String?,
        method: Method,
    ): FunctionBinding? {
        if (interfaceName == null) {
            for (item in plugin.world().exports()) {
                if (
                    item.isFunction &&
                        WitNames.matchesMemberName(method.name, item.function()!!.name())
                ) {
                    return FunctionBinding(item.function()!!.name())
                }
            }
            return null
        }

        for (declaration in plugin.witPackage().interfaces()) {
            if (
                WitNames.lastSegment(declaration.name()) != interfaceName &&
                    declaration.name() != interfaceName
            ) {
                continue
            }
            for (binding in interfaceFunctionBindings(declaration)) {
                if (WitNames.matchesMemberName(method.name, binding.publicName)) {
                    return binding
                }
            }
        }
        return null
    }

    private fun interfaceFunctionBindings(
        declaration: WitPackage.InterfaceDeclaration
    ): List<FunctionBinding> {
        val result = ArrayList<FunctionBinding>()
        for (function in declaration.functions()) {
            result.add(FunctionBinding(function.name()))
        }
        for (member in declaration.members()) {
            if (
                member is WitPackage.TypeDeclaration &&
                    member.kind() == WitPackage.TypeDeclaration.Kind.RESOURCE
            ) {
                for (function in member.functions()) {
                    result.add(resourceFunctionBinding(member.name(), function))
                }
            }
        }
        return result
    }

    private fun resourceFunctionBinding(
        resourceName: String,
        function: WitPackage.Function,
    ): FunctionBinding {
        var publicName = "$resourceName.${function.name()}"
        if (function.isConstructor) {
            publicName = "$resourceName.constructor"
        }
        return FunctionBinding(publicName)
    }

    private fun findWorldExport(
        world: WitPackage.WorldDeclaration,
        propertyName: String?,
    ): WitPackage.WorldItem? {
        if (propertyName == null) {
            return null
        }
        for (item in world.exports()) {
            if (
                WitNames.matchesMemberName(propertyName, item.name()) ||
                    WitNames.matchesMemberName(propertyName, WitNames.lastSegment(item.name()))
            ) {
                return item
            }
        }
        return null
    }

    private fun inferExportInterface(plugin: WasmPlugin, contractType: Class<*>): String? {
        val contractName = contractType.simpleName
        for (item in plugin.world().exports()) {
            if (item.isFunction) {
                continue
            }
            val localName = interfaceName(item)
            if (
                contractName == WitNames.typeName(localName) ||
                    contractName == WitNames.typeName(item.name())
            ) {
                return localName
            }
        }
        return null
    }

    private fun nestedInterfaceObject(host: Any, interfaceName: String): Any? {
        val propertyName = WitNames.memberName(WitNames.lastSegment(interfaceName))
        for (methodName in listOf(propertyName, "get${WitNames.typeName(propertyName)}")) {
            val method = findZeroArgMethod(host.javaClass, methodName)
            if (method != null) {
                try {
                    method.isAccessible = true
                    return method.invoke(host)
                } catch (e: ReflectiveOperationException) {
                    throw ComponentModelException("failed to read host interface $interfaceName", e)
                }
            }
        }

        val field = findField(host.javaClass, propertyName)
        if (field != null) {
            try {
                field.isAccessible = true
                return field.get(host)
            } catch (e: ReflectiveOperationException) {
                throw ComponentModelException("failed to read host interface $interfaceName", e)
            }
        }
        return null
    }

    private fun findFunctionMethod(target: Any, functionName: String): Method? {
        for (method in methods(target.javaClass)) {
            if (
                method.declaringClass == Any::class.java ||
                    Modifier.isStatic(method.modifiers) ||
                    method.isSynthetic ||
                    method.isBridge
            ) {
                continue
            }
            if (WitNames.matchesMemberName(method.name, functionName)) {
                return method
            }
        }
        return null
    }

    private fun nestedResourceMethod(target: Any, functionName: String): ResourceMethod? {
        val dot = functionName.indexOf('.')
        if (dot <= 0 || dot + 1 >= functionName.length) {
            return null
        }
        val resource = nestedInterfaceObject(target, functionName.substring(0, dot)) ?: return null
        val method = findFunctionMethod(resource, functionName.substring(dot + 1))
        return if (method == null) null else ResourceMethod(resource, method)
    }

    private fun invokeHost(target: Any, method: Method, arguments: List<Any?>): Any? {
        try {
            method.isAccessible = true
            return method.invoke(target, *adaptArguments(method, arguments))
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException("failed to invoke host import ${method.name}", e)
        }
    }

    private fun adaptArguments(method: Method, arguments: List<Any?>): Array<Any?> {
        val parameterTypes = method.parameterTypes
        if (parameterTypes.size == 1 && List::class.java.isAssignableFrom(parameterTypes[0])) {
            return arrayOf(arguments)
        }
        if (parameterTypes.size != arguments.size) {
            throw ComponentModelException(
                "host import ${method.name} expects ${parameterTypes.size} arguments, got ${arguments.size}"
            )
        }
        val result = arrayOfNulls<Any>(parameterTypes.size)
        val genericParameterTypes = method.genericParameterTypes
        for (index in parameterTypes.indices) {
            result[index] = convertValue(arguments[index], genericParameterTypes[index])
        }
        return result
    }

    private fun convertReturn(value: Any?, method: Method): Any? {
        val returnType = method.returnType
        if (returnType == java.lang.Void.TYPE) {
            return null
        }
        return convertReturn(value, returnType, method.genericReturnType)
    }

    private fun convertReturn(value: Any?, targetType: Type): Any? {
        val returnType = rawClass(targetType)
        if (returnType == java.lang.Void.TYPE) {
            return null
        }
        return convertReturn(value, returnType ?: Any::class.java, targetType)
    }

    private fun convertReturn(value: Any?, returnType: Class<*>, genericReturnType: Type): Any? {
        if (value is WitValue.Variant) {
            val result = constructResult(value, genericReturnType)
            if (result != null) {
                return result
            }
            val converted = convertVariant(value, returnType)
            if (converted != null) {
                return converted
            }
        }
        return convertValue(value, genericReturnType)
    }

    private fun convertVariant(value: WitValue.Variant, returnType: Class<*>): Any? {
        if (!returnType.isInterface) {
            return null
        }
        val caseTypeName = WitNames.typeName(value.label())
        for (nested in nestedTypes(returnType)) {
            if (nested.simpleName != caseTypeName) {
                continue
            }
            if (!value.hasValue()) {
                val singleton = objectSingleton(nested)
                if (singleton != null) {
                    return singleton
                }
            }
            val constructed = constructCase(nested, value)
            if (constructed != null) {
                return constructed
            }
        }
        return null
    }

    private fun constructCase(nested: Class<*>, value: WitValue.Variant): Any? {
        for (constructor in nested.declaredConstructors) {
            if (constructor.parameterCount == 0 && !value.hasValue()) {
                return construct(constructor)
            }
            if (constructor.parameterCount == 1 && value.hasValue()) {
                val parameterType = constructor.genericParameterTypes[0]
                return construct(constructor, convertValue(value.value(), parameterType))
            }
        }
        return null
    }

    private fun construct(constructor: Constructor<*>, vararg args: Any?): Any {
        try {
            constructor.isAccessible = true
            return constructor.newInstance(*args)
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to construct WIT variant ${constructor.declaringClass}",
                e,
            )
        }
    }

    private fun objectSingleton(type: Class<*>): Any? =
        try {
            val field = type.getField("INSTANCE")
            field.isAccessible = true
            field.get(null)
        } catch (ignored: ReflectiveOperationException) {
            null
        }

    private fun convertValue(value: Any?, targetType: Type): Any? {
        if (targetType is WildcardType) {
            return convertValue(value, boundType(targetType))
        }
        if (targetType is Class<*>) {
            return convertValue(value, targetType)
        }
        if (targetType !is ParameterizedType) {
            return value
        }
        val rawType = rawClass(targetType) ?: return value
        if (value is WitValue.Variant) {
            val enumValue = constructEnum(value, rawType)
            if (enumValue != null) {
                return enumValue
            }
            val option = convertOption(value, targetType)
            if (option !== NO_CONVERSION) {
                return option
            }
        }
        val result = constructResult(value, targetType)
        if (result != null) {
            return result
        }
        val tuple = constructTuple(value, targetType)
        if (tuple != null) {
            return tuple
        }
        if (List::class.java.isAssignableFrom(rawType) && value is List<*>) {
            return convertList(value, targetType.actualTypeArguments[0])
        }
        return convertValue(value, rawType)
    }

    private fun convertValue(value: Any?, targetType: Class<*>): Any? {
        if (value == null) {
            return if (targetType.isPrimitive) primitiveDefault(targetType) else null
        }
        if (targetType.isInstance(value)) {
            return value
        }
        if (value is WitValue.Variant) {
            val enumValue = constructEnum(value, targetType)
            if (enumValue != null) {
                return enumValue
            }
            val option = convertOption(value, targetType)
            if (option !== NO_CONVERSION) {
                return option
            }
            val variant = convertVariant(value, targetType)
            if (variant != null) {
                return variant
            }
        }
        val resource = constructResource(value, targetType)
        if (resource != null) {
            return resource
        }
        val tuple = constructTuple(value, targetType)
        if (tuple != null) {
            return tuple
        }
        val record = constructRecord(value, targetType)
        if (record != null) {
            return record
        }
        val unsigned = boxKotlinUnsigned(value, targetType)
        if (unsigned != null) {
            return unsigned
        }
        if (targetType == String::class.java) {
            return value.toString()
        }
        if (
            (targetType == Byte::class.javaPrimitiveType ||
                targetType == Byte::class.javaObjectType) && value is Number
        ) {
            return value.toByte()
        }
        if (
            (targetType == Short::class.javaPrimitiveType ||
                targetType == Short::class.javaObjectType) && value is Number
        ) {
            return value.toShort()
        }
        if (
            (targetType == Int::class.javaPrimitiveType ||
                targetType == Int::class.javaObjectType) && value is Number
        ) {
            return value.toInt()
        }
        if (
            (targetType == Long::class.javaPrimitiveType ||
                targetType == Long::class.javaObjectType) && value is Number
        ) {
            return value.toLong()
        }
        if (
            (targetType == Char::class.javaPrimitiveType ||
                targetType == Char::class.javaObjectType) && value is Number
        ) {
            val codePoint = value.toLong()
            if (
                codePoint < Character.MIN_VALUE.code ||
                    codePoint > Character.MAX_VALUE.code ||
                    Character.isSurrogate(codePoint.toInt().toChar())
            ) {
                throw ComponentModelException(
                    "cannot convert WIT char scalar ${java.lang.Long.toUnsignedString(codePoint)} to JVM Char"
                )
            }
            return codePoint.toInt().toChar()
        }
        if (
            (targetType == Float::class.javaPrimitiveType ||
                targetType == Float::class.javaObjectType) && value is Number
        ) {
            return value.toFloat()
        }
        if (
            (targetType == Double::class.javaPrimitiveType ||
                targetType == Double::class.javaObjectType) && value is Number
        ) {
            return value.toDouble()
        }
        if (
            (targetType == Boolean::class.javaPrimitiveType ||
                targetType == Boolean::class.javaObjectType) && value is Boolean
        ) {
            return value
        }
        if (targetType.isArray && value is List<*>) {
            return listToArray(value, targetType.componentType)
        }
        return value
    }

    private fun constructResult(value: Any?, targetType: Type): Any? {
        if (value !is WitValue.Variant || targetType !is ParameterizedType) {
            return null
        }
        return constructResult(value, targetType)
    }

    private fun constructResult(value: WitValue.Variant, targetType: Type): Any? {
        if (targetType !is ParameterizedType) {
            return null
        }
        return constructResult(value, targetType)
    }

    private fun constructResult(value: WitValue.Variant, targetType: ParameterizedType): Any? {
        val rawType = rawClass(targetType)
        if (rawType == null || !isWitResultType(rawType)) {
            return null
        }
        val payloadIndex: Int
        val nestedName: String
        when (value.label()) {
            "ok" -> {
                payloadIndex = 0
                nestedName = "Ok"
            }
            "err" -> {
                payloadIndex = 1
                nestedName = "Err"
            }
            else -> return null
        }
        val typeArguments = targetType.actualTypeArguments
        val payloadType =
            if (payloadIndex < typeArguments.size) typeArguments[payloadIndex] else Any::class.java
        val payload =
            if (value.hasValue()) {
                convertValue(value.value(), payloadType)
            } else {
                unitOrNull(payloadType)
            }
        for (nested in nestedTypes(rawType)) {
            if (nested.simpleName != nestedName) {
                continue
            }
            for (constructor in nested.declaredConstructors) {
                if (constructor.parameterCount == 1) {
                    return construct(constructor, payload)
                }
                if (constructor.parameterCount == 0 && !value.hasValue()) {
                    return construct(constructor)
                }
            }
        }
        return null
    }

    private fun convertOption(value: WitValue.Variant, targetType: Class<*>): Any? =
        convertOption(value, targetType as Type)

    private fun convertOption(value: WitValue.Variant, targetType: Type): Any? {
        if (value.label() == "none" && !value.hasValue()) {
            return null
        }
        if (value.label() == "some") {
            if (value.hasValue()) {
                return convertValue(value.value(), targetType)
            }
            val rawType = rawClass(targetType)
            if (rawType != null && rawType.name == "kotlin.Unit") {
                return objectSingleton(rawType)
            }
        }
        return NO_CONVERSION
    }

    private fun constructEnum(value: WitValue.Variant, targetType: Class<*>): Any? {
        if (!targetType.isEnum || value.hasValue()) {
            return null
        }
        for (constant in targetType.enumConstants ?: emptyArray<Any>()) {
            val name = (constant as Enum<*>).name
            if (
                name == value.label() ||
                    name == WitNames.enumName(value.label()) ||
                    name == WitNames.typeName(value.label())
            ) {
                return constant
            }
        }
        return null
    }

    private fun constructRecord(value: Any?, targetType: Class<*>): Any? {
        if (value !is Map<*, *> || !isRecordTarget(targetType)) {
            return null
        }
        val values = ArrayList(value.values)
        for (constructor in targetType.declaredConstructors) {
            if (constructor.isSynthetic || constructor.parameterCount != values.size) {
                continue
            }
            val parameterTypes = constructor.genericParameterTypes
            val arguments = arrayOfNulls<Any>(values.size)
            for (index in values.indices) {
                arguments[index] = convertValue(values[index], parameterTypes[index])
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments)
            } catch (e: IllegalArgumentException) {
                throw ComponentModelException(
                    "failed to construct WIT record ${targetType.name}",
                    e,
                )
            } catch (e: ReflectiveOperationException) {
                throw ComponentModelException(
                    "failed to construct WIT record ${targetType.name}",
                    e,
                )
            }
        }
        return null
    }

    private fun isRecordTarget(targetType: Class<*>): Boolean =
        targetType != Any::class.java &&
            !targetType.isPrimitive &&
            !targetType.isInterface &&
            !targetType.isEnum &&
            !targetType.isArray &&
            !targetType.isAnnotation &&
            !Map::class.java.isAssignableFrom(targetType) &&
            !List::class.java.isAssignableFrom(targetType) &&
            !Number::class.java.isAssignableFrom(targetType) &&
            !CharSequence::class.java.isAssignableFrom(targetType) &&
            targetType != Boolean::class.javaObjectType &&
            targetType != Char::class.javaObjectType

    private fun constructTuple(value: Any?, targetType: Class<*>): Any? =
        constructTuple(value, targetType, null)

    private fun constructTuple(value: Any?, targetType: ParameterizedType): Any? {
        val rawType = rawClass(targetType) ?: return null
        return constructTuple(value, rawType, targetType.actualTypeArguments)
    }

    private fun constructTuple(
        value: Any?,
        targetType: Class<*>,
        elementTypes: Array<Type>?,
    ): Any? {
        if (value !is List<*> || !isTupleType(targetType)) {
            return null
        }
        for (constructor in targetType.declaredConstructors) {
            if (constructor.parameterCount != value.size) {
                continue
            }
            val parameterTypes = constructor.genericParameterTypes
            val arguments = arrayOfNulls<Any>(value.size)
            for (index in value.indices) {
                val targetElementType =
                    if (elementTypes != null && index < elementTypes.size) {
                        elementTypes[index]
                    } else {
                        parameterTypes[index]
                    }
                arguments[index] = convertValue(value[index], targetElementType)
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments)
            } catch (e: IllegalArgumentException) {
                throw ComponentModelException("failed to construct WIT tuple ${targetType.name}", e)
            } catch (e: ReflectiveOperationException) {
                throw ComponentModelException("failed to construct WIT tuple ${targetType.name}", e)
            }
        }
        return null
    }

    private fun isTupleType(targetType: Class<*>): Boolean {
        val name = targetType.name
        val simpleName = targetType.simpleName
        return name == "kotlin.Pair" ||
            name == "kotlin.Triple" ||
            simpleName.matches(Regex("WitTuple[1-8]"))
    }

    private fun constructResource(value: Any?, targetType: Class<*>): Any? {
        if (value !is Number || targetType.name != "uk.shusek.krwa.component.WitResource") {
            return null
        }
        try {
            val constructor = targetType.getConstructor(Long::class.javaPrimitiveType!!)
            return constructor.newInstance(value.toLong())
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException("failed to construct WIT resource ${targetType.name}", e)
        }
    }

    private fun boxKotlinUnsigned(value: Any?, targetType: Class<*>): Any? {
        if (value !is Number) {
            return null
        }
        try {
            return when (targetType.name) {
                "kotlin.UByte" ->
                    targetType
                        .getDeclaredMethod("box-impl", Byte::class.javaPrimitiveType!!)
                        .invoke(null, value.toByte())
                "kotlin.UShort" ->
                    targetType
                        .getDeclaredMethod("box-impl", Short::class.javaPrimitiveType!!)
                        .invoke(null, value.toShort())
                "kotlin.UInt" ->
                    targetType
                        .getDeclaredMethod("box-impl", Int::class.javaPrimitiveType!!)
                        .invoke(null, value.toInt())
                "kotlin.ULong" ->
                    targetType
                        .getDeclaredMethod("box-impl", Long::class.javaPrimitiveType!!)
                        .invoke(null, value.toLong())
                else -> null
            }
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to box Kotlin unsigned value for ${targetType.name}",
                e,
            )
        }
    }

    private fun listToArray(value: List<*>, componentType: Class<*>): Any {
        val result = ReflectArray.newInstance(componentType, value.size)
        for (index in value.indices) {
            ReflectArray.set(result, index, convertValue(value[index], componentType))
        }
        return result
    }

    private fun convertList(value: List<*>, elementType: Type): List<*> {
        val result = ArrayList<Any?>(value.size)
        for (item in value) {
            result.add(convertValue(item, elementType))
        }
        return result
    }

    private fun unitOrNull(type: Type): Any? {
        val rawType = rawClass(type)
        return if (rawType != null && rawType.name == "kotlin.Unit") objectSingleton(rawType)
        else null
    }

    private fun rawClass(type: Type): Class<*>? =
        when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*>
            is WildcardType -> rawClass(boundType(type))
            else -> null
        }

    private fun boundType(type: Type): Type =
        if (type is WildcardType) {
            type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: Any::class.java
        } else {
            type
        }

    private fun isWitResultType(type: Class<*>): Boolean =
        type.name == "uk.shusek.krwa.component.WitResult" || type.simpleName == "WitResult"

    private fun primitiveDefault(type: Class<*>): Any =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Char::class.javaPrimitiveType -> '\u0000'
            Float::class.javaPrimitiveType -> 0.0f
            Double::class.javaPrimitiveType -> 0.0
            else -> 0
        }

    private fun nestedTypes(type: Class<*>): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        result.addAll(type.classes.toList())
        result.addAll(type.declaredClasses.toList())
        return result
    }

    private fun methods(type: Class<*>): List<Method> {
        val result = ArrayList<Method>()
        result.addAll(type.methods.toList())
        result.addAll(type.declaredMethods.toList())
        return result
    }

    private fun findZeroArgMethod(type: Class<*>, name: String): Method? {
        for (method in methods(type)) {
            if (method.parameterCount == 0 && method.name == name) {
                return method
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun propertyName(method: Method): String? {
        if (method.parameterCount != 0) {
            return null
        }
        val name = method.name
        if (name.startsWith("get") && name.length > 3) {
            return "${Character.toLowerCase(name[3])}${name.substring(4)}"
        }
        if (name.startsWith("is") && name.length > 2) {
            return "${Character.toLowerCase(name[2])}${name.substring(3)}"
        }
        return name
    }

    private fun interfaceName(item: WitPackage.WorldItem): String {
        val type = item.type()
        if (type != null && type.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            return WitNames.lastSegment(type.name()!!)
        }
        return WitNames.lastSegment(item.name())
    }

    private data class FunctionBinding(val publicName: String)

    private data class ResourceMethod(val target: Any, val method: Method)
}
