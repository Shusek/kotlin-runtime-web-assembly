package uk.shusek.krwa.wasi

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem
import okio.Path
import org.kotlincrypto.random.CryptoRand

expect class WasiOptions {
    fun random(): CryptoRand

    fun clock(): Clock

    fun stdoutSink(): RawSink

    fun stderrSink(): RawSink

    fun stdinSource(): RawSource

    fun stdinAvailable(): Int

    fun stdinIsTty(): Boolean

    fun stdoutIsTty(): Boolean

    fun stderrIsTty(): Boolean

    fun arguments(): List<String>

    fun environment(): Map<String, String>

    fun preopenedDirectories(): Map<String, WasiDirectory>

    fun throwOnExit0(): Boolean

    companion object {
        fun builder(): Builder
    }

    class Builder {
        fun withRandom(random: CryptoRand): Builder

        fun withRandom(random: Random): Builder

        fun withClock(clock: Clock): Builder

        fun withStdout(stdout: RawSink): Builder

        fun withStdout(stdout: RawSink, isTty: Boolean): Builder

        fun withStderr(stderr: RawSink): Builder

        fun withStderr(stderr: RawSink, isTty: Boolean): Builder

        fun withStdin(stdin: RawSource): Builder

        fun withStdin(stdin: RawSource, isTty: Boolean): Builder

        fun withArguments(arguments: List<String>): Builder

        fun withEnvironment(name: String, value: String): Builder

        fun withDirectory(guest: String, host: Path): Builder

        fun withDirectory(guest: String, host: Path, fileSystem: FileSystem): Builder

        fun withThrowOnExit0(throwOnExit0: Boolean): Builder

        fun build(): WasiOptions
    }
}
