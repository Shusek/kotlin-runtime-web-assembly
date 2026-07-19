package uk.shusek.krwa.component

public interface WasiHostImportBuilder {
    @Suppress("DEPRECATION")
    public fun withWitHostImport(
        id: WitHostImportId,
        handler: HostHandler,
    ): WasiHostImportBuilder = withHostImport(id.interfaceName, id.functionName, handler)

    @Deprecated(
        message = "Use withWitHostImport(WitHostImportId, handler) for an exact WIT import.",
        replaceWith =
            ReplaceWith(
                "withWitHostImport(WitHostImportId(interfaceName!!, functionName!!), handler)"
            ),
    )
    public fun withHostImport(
        interfaceName: String?,
        functionName: String?,
        handler: HostHandler,
    ): WasiHostImportBuilder

    @Deprecated(
        message = "Unqualified host imports are ambiguous; use withWitHostImport instead.",
    )
    public fun withHostImport(qualifiedName: String, handler: HostHandler): WasiHostImportBuilder

    public fun withWasiPreview3CanonicalIntrinsics(
        intrinsics: WasiPreview3CanonicalIntrinsics
    ): WasiHostImportBuilder = this
}
