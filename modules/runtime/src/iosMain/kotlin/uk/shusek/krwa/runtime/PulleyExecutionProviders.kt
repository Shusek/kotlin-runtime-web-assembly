package uk.shusek.krwa.runtime

actual object PulleyExecutionProviders {
    private var installedProvider: PulleyExecutionProvider? = null

    actual fun install(provider: PulleyExecutionProvider?): PulleyExecutionProvider? {
        val previous = installedProvider
        installedProvider = provider
        return previous
    }

    internal actual fun installed(): PulleyExecutionProvider? = installedProvider
}
