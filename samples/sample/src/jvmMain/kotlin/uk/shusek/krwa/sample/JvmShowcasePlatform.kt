package uk.shusek.krwa.sample

import java.nio.file.Files
import java.nio.file.Path

internal actual object ShowcasePlatform {
    private val root: Path = Files.createTempDirectory("krwa-kmp-showcase-storage")

    actual val displayName: String = "JVM host"

    actual val storageRoot: String = root.toString()

    actual val storageCapability: ShowcaseCapability =
        ShowcaseCapability(
            "WASI Preview 3 Host Capabilities",
            "Preopened filesystem on JVM",
            "The same KMP storage facade writes, appends, lists, reads metadata, deletes, and enforces read-only preopens on the JVM filesystem.",
        )

    actual val supportsSynchronousWasiPreview3HttpClientWait: Boolean = true

    actual fun close() {
        Files.deleteIfExists(root)
    }
}
