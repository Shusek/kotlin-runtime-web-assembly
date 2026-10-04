package uk.shusek.krwa.component

import java.lang.reflect.Array as ReflectArray
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
    val method = WitReflectionAccess.publicZeroArgMethod(value.javaClass, name)
    if (method != null) {
        try {
            return CanonicalAbiReflectedValue(WitReflectionAccess.open(method).invoke(value))
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read field $name on ${value.javaClass.name}",
                e,
            )
        }
    }
    val field = WitReflectionAccess.publicField(value.javaClass, name)
    if (field != null) {
        try {
            return CanonicalAbiReflectedValue(WitReflectionAccess.open(field).get(value))
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
        val method =
            WitReflectionAccess.publicZeroArgMethod(value.javaClass, "component$i") ?: return null
        try {
            result.add(WitReflectionAccess.open(method).invoke(value))
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
    for (method in WitReflectionAccess.publicInstanceMethods(value.javaClass)) {
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
    val field = WitReflectionAccess.publicField(value.javaClass, "handle")
    if (field != null) {
        try {
            val handle = WitReflectionAccess.open(field).get(value)
            if (handle is Number) {
                return handle.toLong()
            }
        } catch (_: ReflectiveOperationException) {
            // Try only common Kotlin/Java resource wrapper shapes.
        }
    }
    return null
}

internal actual fun canonicalAbiTypeName(value: Any): String = value.javaClass.name

private fun variantPayload(value: Any): Any? {
    for (methodName in listOf("value", "getValue")) {
        val method = WitReflectionAccess.publicZeroArgMethod(value.javaClass, methodName) ?: continue
        try {
            return WitReflectionAccess.open(method).invoke(value)
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read variant payload on ${value.javaClass.name}",
                e,
            )
        }
    }
    val field = WitReflectionAccess.publicField(value.javaClass, "value")
    if (field != null) {
        try {
            return WitReflectionAccess.open(field).get(value)
        } catch (e: ReflectiveOperationException) {
            throw ComponentModelException(
                "failed to read variant payload on ${value.javaClass.name}",
                e,
            )
        }
    }
    throw ComponentModelException(
        "missing public variant payload accessor (value() or getValue()) on ${value.javaClass.name}"
    )
}

private fun invokeHandle(value: Any, method: Method): Long? {
    try {
        val handle = WitReflectionAccess.open(method).invoke(value)
        if (handle is Number) {
            return handle.toLong()
        }
    } catch (_: ReflectiveOperationException) {
        // Try the next common Kotlin/Java accessor shape.
    }
    return null
}
