package uk.shusek.krwa.wasm

/** Parsing stopped because an input-controlled resource limit was exceeded. */
class WasmParseLimitException(
    val limitName: String,
    val configuredLimit: Long,
    val actual: Long,
    val specificationReason: String?,
) : MalformedException(
        buildString {
            if (specificationReason != null) {
                append(specificationReason).append(": ")
            }
            append("WebAssembly parser limit '")
                .append(limitName)
                .append("' exceeded: ")
                .append(actual)
                .append(" > ")
                .append(configuredLimit)
        }
) {
    constructor(
        limitName: String,
        configuredLimit: Long,
        actual: Long,
    ) : this(limitName, configuredLimit, actual, null)
}
