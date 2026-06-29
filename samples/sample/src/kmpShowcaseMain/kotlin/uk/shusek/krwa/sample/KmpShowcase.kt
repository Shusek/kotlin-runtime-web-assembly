package uk.shusek.krwa.sample

internal expect object ShowcasePlatform {
    val displayName: String
    val storageRoot: String
    val storageCapability: ShowcaseCapability
    val supportsSynchronousWasiPreview3HttpClientWait: Boolean

    fun close()
}

internal data class ShowcaseCapability(
    val area: String,
    val title: String,
    val detail: String,
)

internal typealias ShowcaseCapabilities = MutableList<ShowcaseCapability>

internal fun ShowcaseCapabilities.demonstrate(
    area: String,
    title: String,
    detail: String,
) {
    this += ShowcaseCapability(area, title, detail)
}

internal fun runKmpShowcase(): List<ShowcaseCapability> {
    val capabilities = ArrayList<ShowcaseCapability>()
    val portable = PortableRuntimeShowcase(capabilities)

    try {
        portable.platformRuntime()
        portable.builderSelectedRuntime()
        portable.componentModelContracts()
        portable.kotlinEcosystemIntegrations()
        portable.wasiPreview3Storage(ShowcasePlatform.storageRoot, ShowcasePlatform.storageCapability)
    } finally {
        ShowcasePlatform.close()
    }

    return capabilities
}

internal fun printShowcaseReport(title: String, capabilities: List<ShowcaseCapability>) {
    println(title)
    println("Host: ${ShowcasePlatform.displayName}")
    println("Capabilities demonstrated: ${capabilities.size}")
    for ((area, entries) in capabilities.groupBy { it.area }) {
        println()
        println(area)
        for (entry in entries) {
            println(" - ${entry.title}: ${entry.detail}")
        }
    }
}
