package uk.shusek.krwa.component

internal actual fun canonicalAbiReflectedCase(
    value: Any,
    caseLabels: List<String>,
    caseHasPayload: List<Boolean>,
): CanonicalAbiReflectedCase? = null

internal actual fun canonicalAbiFieldValue(value: Any, name: String): CanonicalAbiReflectedValue? =
    null

internal actual fun canonicalAbiTupleComponents(value: Any, size: Int): List<Any?>? = null

internal actual fun canonicalAbiArrayElements(value: Any): List<Any?>? = null

internal actual fun canonicalAbiResourceHandle(value: Any): Long? = null

internal actual fun canonicalAbiTypeName(value: Any): String = value.toString()
