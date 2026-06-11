package uk.shusek.krwa.wasi.preview3

import java.time.Instant as JavaInstant

internal fun WasiInstant.toJavaInstant(): JavaInstant =
    JavaInstant.ofEpochSecond(epochSeconds, nanoseconds.toLong())

internal fun WasiInstant.Companion.fromJavaInstant(instant: JavaInstant): WasiInstant =
    WasiInstant(instant.epochSecond, instant.nano)
