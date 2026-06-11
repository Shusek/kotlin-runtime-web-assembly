package uk.shusek.krwa.sample

import platform.Foundation.NSTemporaryDirectory

internal actual object ShowcasePlatform {
    actual val displayName: String = "iOS simulator host"

    actual val storageRoot: String =
        "${NSTemporaryDirectory().trimEnd('/')}/krwa-ios-showcase-storage"

    actual val storageCapability: ShowcaseCapability =
        ShowcaseCapability(
            "WASI Preview 3 Host Capabilities",
            "Preopened filesystem on iOS",
            "The same KMP storage facade writes, appends, lists, reads metadata, deletes, and enforces read-only preopens inside the iOS simulator sandbox.",
        )

    actual val supportsSynchronousWasiPreview3HttpClientWait: Boolean = true

    actual fun close() = Unit
}
