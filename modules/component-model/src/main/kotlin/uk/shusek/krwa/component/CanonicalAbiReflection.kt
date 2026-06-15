package uk.shusek.krwa.component

internal data class CanonicalAbiReflectedCase(val index: Int, val payload: Any?)

internal data class CanonicalAbiReflectedValue(val value: Any?)

internal expect fun canonicalAbiReflectedCase(
    value: Any,
    caseLabels: List<String>,
    caseHasPayload: List<Boolean>,
): CanonicalAbiReflectedCase?

internal expect fun canonicalAbiFieldValue(value: Any, name: String): CanonicalAbiReflectedValue?

internal expect fun canonicalAbiTupleComponents(value: Any, size: Int): List<Any?>?

internal expect fun canonicalAbiArrayElements(value: Any): List<Any?>?

internal expect fun canonicalAbiResourceHandle(value: Any): Long?

internal expect fun canonicalAbiTypeName(value: Any): String
