package uk.shusek.krwa.wasi

import uk.shusek.krwa.runtime.HostFunction

expect class WasiPreview1 {
    fun toHostFunctions(): Array<HostFunction>

    companion object {
        fun builder(): Builder
    }

    class Builder {
        fun withOptions(opts: WasiOptions): Builder

        fun build(): WasiPreview1
    }
}
