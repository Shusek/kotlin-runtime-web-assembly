package uk.shusek.krwa.component

enum class WasiPreview(
    private val versionValue: String,
    private val binaryKindValue: BinaryKind,
    private val stabilityValue: Stability,
) {
    PREVIEW1("0.1", BinaryKind.CORE_MODULE, Stability.STABLE),
    PREVIEW2("0.2", BinaryKind.COMPONENT, Stability.STABLE),
    PREVIEW3("0.3.0", BinaryKind.COMPONENT, Stability.STABLE);

    fun version(): String = versionValue

    fun binaryKind(): BinaryKind = binaryKindValue

    fun stability(): Stability = stabilityValue

    fun isComponentModel(): Boolean = binaryKindValue == BinaryKind.COMPONENT

    fun isStable(): Boolean = stabilityValue == Stability.STABLE

    fun isReleaseCandidate(): Boolean = stabilityValue == Stability.RELEASE_CANDIDATE

    fun requireStable() {
        if (!isStable()) {
            throw ComponentModelException("$name is not stable yet")
        }
    }

    enum class BinaryKind {
        CORE_MODULE,
        COMPONENT,
    }

    enum class Stability {
        STABLE,
        RELEASE_CANDIDATE,
    }
}
