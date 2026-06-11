package uk.shusek.krwa.wasi

import java.io.Closeable
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.runtime.HostFunction

actual class WasiPreview1 private constructor(private val engine: WasiPreview1Engine) :
    Closeable, WasiPreview1Host by engine {
    actual class Builder private constructor() {
        private var opts: WasiOptions? = null

        @Suppress("UNUSED_PARAMETER") fun withLogger(logger: Logger): Builder = this

        actual fun withOptions(opts: WasiOptions): Builder {
            this.opts = opts
            return this
        }

        actual fun build(): WasiPreview1 =
            WasiPreview1(WasiPreview1Engine(opts ?: WasiOptions.builder().build()))

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    override fun close() {
        engine.close()
    }

    actual fun toHostFunctions(): Array<HostFunction> = engine.toHostFunctions()

    fun toHostFunctions(moduleName: String): Array<HostFunction> =
        engine.toHostFunctions(moduleName)

    actual companion object {
        @JvmStatic actual fun builder(): Builder = Builder.create()
    }
}
