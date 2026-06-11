package uk.shusek.krwa.sample

fun main() {
    printShowcaseReport(
        "Kotlin Runtime Web Assembly wasmJs showcase",
        runKmpShowcase(),
    )
}

internal actual object ShowcasePlatform {
    actual val displayName: String = "wasmJs host with native WebAssembly engine"

    actual val storageRoot: String = "/memory"

    actual val storageCapability: ShowcaseCapability =
        ShowcaseCapability(
            "WASI Preview 3 Host Capabilities",
            "Memory-backed preopened filesystem on wasmJs",
            "The same KMP storage facade writes, appends, lists, reads metadata, deletes, and enforces read-only preopens on the wasmJs memory filesystem.",
        )

    actual val supportsSynchronousWasiPreview3HttpClientWait: Boolean = false

    actual fun close() = Unit
}
