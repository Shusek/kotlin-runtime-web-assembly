package uk.shusek.krwa.runtime

import java.util.concurrent.atomic.AtomicReference

actual object PulleyExecutionProviders {
    private val installedProvider = AtomicReference<PulleyExecutionProvider?>()

    actual fun install(provider: PulleyExecutionProvider?): PulleyExecutionProvider? =
        installedProvider.getAndSet(provider)

    internal actual fun installed(): PulleyExecutionProvider? = installedProvider.get()
}
