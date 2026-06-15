package uk.shusek.krwa.wasi

import uk.shusek.krwa.runtime.HostFunction

actual class WasiPreview1 private constructor(
    private val engine: WasiPreview1Engine,
) : WasiPreview1Host by engine {
    actual class Builder internal constructor() {
        private var opts: WasiOptions? = null

        actual fun withOptions(opts: WasiOptions): Builder {
            this.opts = opts
            return this
        }

        actual fun build(): WasiPreview1 =
            WasiPreview1(WasiPreview1Engine(opts ?: WasiOptions.builder().build()))
    }

    fun close() {
        engine.close()
    }

    actual fun toHostFunctions(): Array<HostFunction> = engine.toHostFunctions()

    fun toHostFunctions(moduleName: String): Array<HostFunction> =
        engine.toHostFunctions(moduleName)

    actual companion object {
        actual fun builder(): Builder = Builder()
    }
}
