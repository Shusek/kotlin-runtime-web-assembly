package uk.shusek.krwa.component

/**
 * Identifies a host function declared by an interface imported by the selected WIT world.
 */
data class WitHostImportId(
    val interfaceName: String,
    val functionName: String,
) {
    init {
        require(interfaceName.isNotBlank()) { "WIT host interface name must not be blank" }
        require(functionName.isNotBlank()) { "WIT host function name must not be blank" }
    }
}

/**
 * Identifies an explicitly granted raw core WebAssembly host import.
 *
 * Core imports bypass the WIT world boundary and therefore must be registered separately from
 * normal WIT host handlers.
 */
data class CoreHostImportId(
    val moduleName: String,
    val functionName: String,
) {
    init {
        require(moduleName.isNotBlank()) { "core host module name must not be blank" }
        require(functionName.isNotBlank()) { "core host function name must not be blank" }
    }
}

/**
 * Marks APIs which deliberately relax Component Model sandbox boundaries.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API relaxes Component Model sandbox boundaries and must not be used for untrusted plugins.",
)
annotation class UnsafeComponentModelApi
