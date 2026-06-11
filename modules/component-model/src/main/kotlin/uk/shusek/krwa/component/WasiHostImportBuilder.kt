package uk.shusek.krwa.component

public interface WasiHostImportBuilder {
    public fun withHostImport(
        interfaceName: String?,
        functionName: String?,
        handler: HostHandler,
    ): WasiHostImportBuilder

    public fun withHostImport(qualifiedName: String, handler: HostHandler): WasiHostImportBuilder

    public fun withWasiPreview3CanonicalIntrinsics(
        intrinsics: WasiPreview3CanonicalIntrinsics
    ): WasiHostImportBuilder = this
}
