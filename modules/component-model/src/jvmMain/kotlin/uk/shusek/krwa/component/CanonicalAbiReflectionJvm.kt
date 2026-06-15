package uk.shusek.krwa.component

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field as ReflectField
import java.lang.reflect.Method

internal actual fun canonicalAbiReflectedCase(
    value: Any,
    caseLabels: List<String>,
    caseHasPayload: List<Boolean>,
): CanonicalAbiReflectedCase? {
    val simpleName = value.javaClass.simpleName
    for (i in caseLabels.indices) {
        if (simpleName != WitNames.typeName(caseLabels[i])) {
            continue
        }
        return CanonicalAbiReflectedCase(
            i,
            if (caseHasPayload[i]) variantPayload(value) else null,
        )
    }
    return null
}

internal actual fun canonicalAbiFieldValue(value: Any, name: String): CanonicalAbiReflectedValue? {
    val method = fieldAccessorOrNull(value.javaClass, name)
    if (method != null) {
        try {
            method.isAccessible = true
            return CanonicalAbiReflectedValue(method.invoke(value))
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read field $name on ${value.javaClass.name}",
                e,
            )
        }
    }
    val field = fieldOrNull(value.javaClass, name)
    if (field != null) {
        try {
            field.isAccessible = true
            return CanonicalAbiReflectedValue(field.get(value))
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read field $name on ${value.javaClass.name}",
                e,
            )
        }
    }
    return null
}

internal actual fun canonicalAbiTupleComponents(value: Any, size: Int): List<Any?>? {
    val result = ArrayList<Any?>(size)
    for (i in 1..size) {
        val method = componentMethod(value.javaClass, i) ?: return null
        try {
            method.isAccessible = true
            result.add(method.invoke(value))
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read tuple component$i from ${value.javaClass.name}",
                e,
            )
        }
    }
    return result
}

internal actual fun canonicalAbiArrayElements(value: Any): List<Any?>? {
    if (!value.javaClass.isArray) {
        return null
    }
    val result = ArrayList<Any?>()
    for (i in 0 until ReflectArray.getLength(value)) {
        result.add(ReflectArray.get(value, i))
    }
    return result
}

internal actual fun canonicalAbiResourceHandle(value: Any): Long? {
    for (method in value.javaClass.methods) {
        if (
            method.parameterCount == 0 &&
                (method.name == "handle" ||
                    method.name == "getHandle" ||
                    method.name.startsWith("getHandle-"))
        ) {
            val handle = invokeHandle(value, method)
            if (handle != null) {
                return handle
            }
        }
    }
    try {
        val field = value.javaClass.getField("handle")
        val handle = field.get(value)
        if (handle is Number) {
            return handle.toLong()
        }
    } catch (_: ReflectiveOperationException) {
        // Try only common Kotlin/Java resource wrapper shapes.
    }
    return null
}

internal actual fun canonicalAbiTypeName(value: Any): String = value.javaClass.name

private fun variantPayload(value: Any): Any? {
    for (methodName in listOf("value", "getValue")) {
        try {
            val method = value.javaClass.getMethod(methodName)
            return method.invoke(value)
        } catch (_: ReflectiveOperationException) {
            // Try the next common Kotlin/Java variant payload shape.
        }
    }
    try {
        val field = value.javaClass.getDeclaredField("value")
        field.isAccessible = true
        return field.get(value)
    } catch (_: ReflectiveOperationException) {
        throw ComponentModelException("missing variant payload on ${value.javaClass.name}")
    }
}

private fun fieldAccessorOrNull(type: Class<*>, name: String): Method? {
    try {
        return type.getMethod(name)
    } catch (_: NoSuchMethodException) {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
    }
    return null
}

private fun fieldOrNull(type: Class<*>, name: String): ReflectField? {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    return null
}

private fun componentMethod(type: Class<*>, index: Int): Method? {
    val name = "component$index"
    try {
        return type.getMethod(name)
    } catch (_: NoSuchMethodException) {
        try {
            return type.getDeclaredMethod(name)
        } catch (_: NoSuchMethodException) {
            return null
        }
    }
}

private fun invokeHandle(value: Any, method: Method): Long? {
    try {
        val handle = method.invoke(value)
        if (handle is Number) {
            return handle.toLong()
        }
    } catch (_: ReflectiveOperationException) {
        // Try the next common Kotlin/Java accessor shape.
    }
    return null
}
