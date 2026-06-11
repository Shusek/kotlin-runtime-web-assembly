package uk.shusek.krwa.wasi.preview3

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

public data class WasiInstant(
    public val epochSeconds: Long,
    public val nanoseconds: Int = 0,
) {
    init {
        require(nanoseconds in 0..999_999_999) {
            "nanoseconds must be in 0..999999999"
        }
    }

    @OptIn(ExperimentalTime::class)
    public fun toKotlinInstant(): Instant =
        Instant.fromEpochSeconds(epochSeconds, nanoseconds)

    public companion object {
        public fun fromEpochSeconds(
            seconds: Long,
            nanoseconds: Int = 0,
        ): WasiInstant = WasiInstant(seconds, nanoseconds)

        @OptIn(ExperimentalTime::class)
        public fun fromEpochMilliseconds(milliseconds: Long): WasiInstant =
            fromKotlinInstant(Instant.fromEpochMilliseconds(milliseconds))

        @OptIn(ExperimentalTime::class)
        public fun fromKotlinInstant(instant: Instant): WasiInstant =
            WasiInstant(instant.epochSeconds, instant.nanosecondsOfSecond)

        @OptIn(ExperimentalTime::class)
        public fun now(clock: Clock = Clock.System): WasiInstant =
            fromKotlinInstant(clock.now())
    }
}
