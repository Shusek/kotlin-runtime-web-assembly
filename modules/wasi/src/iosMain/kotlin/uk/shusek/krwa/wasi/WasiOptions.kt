package uk.shusek.krwa.wasi

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.FileSystem
import okio.Path
import org.kotlincrypto.random.CryptoRand

actual class WasiOptions
private constructor(
    private val random: CryptoRand,
    private val clock: Clock,
    private val stdout: RawSink,
    private val stderr: RawSink,
    private val stdin: RawSource,
    private val stdinIsTty: Boolean,
    private val stdoutIsTty: Boolean,
    private val stderrIsTty: Boolean,
    arguments: List<String>,
    environment: Map<String, String>,
    preopenedDirectories: Map<String, WasiDirectory>,
    private val throwOnExit0: Boolean,
) {
    private val arguments: List<String> = arguments.toList()
    private val environment: Map<String, String> = environment.toMap()
    private val preopenedDirectories: Map<String, WasiDirectory> = preopenedDirectories.toMap()

    actual fun random(): CryptoRand = random

    actual fun clock(): Clock = clock

    actual fun stdoutSink(): RawSink = stdout

    actual fun stderrSink(): RawSink = stderr

    actual fun stdinSource(): RawSource = stdin

    actual fun stdinAvailable(): Int = 0

    actual fun stdinIsTty(): Boolean = stdinIsTty

    actual fun stdoutIsTty(): Boolean = stdoutIsTty

    actual fun stderrIsTty(): Boolean = stderrIsTty

    actual fun arguments(): List<String> = arguments

    actual fun environment(): Map<String, String> = environment

    actual fun preopenedDirectories(): Map<String, WasiDirectory> = preopenedDirectories

    actual fun throwOnExit0(): Boolean = throwOnExit0

    actual companion object {
        actual fun builder(): Builder = Builder()
    }

    actual class Builder {
        private var random: CryptoRand = CryptoRand.Default
        @OptIn(ExperimentalTime::class)
        private var clock: Clock = Clock.System
        private var stdout: RawSink = BlackholeSink()
        private var stderr: RawSink = BlackholeSink()
        private var stdin: RawSource = NullSource()
        private var stdinIsTty = true
        private var stdoutIsTty = true
        private var stderrIsTty = true
        private var arguments: List<String> = emptyList()
        private val environment: MutableMap<String, String> = LinkedHashMap()
        private val preopenedDirectories: MutableMap<String, WasiDirectory> = LinkedHashMap()
        private var throwOnExit0 = true

        actual fun withRandom(random: CryptoRand): Builder {
            this.random = random
            return this
        }

        actual fun withRandom(random: Random): Builder {
            return withRandom(KotlinRandomCryptoRand(random))
        }

        actual fun withClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        actual fun withStdout(stdout: RawSink): Builder {
            this.stdout = stdout
            return this
        }

        actual fun withStdout(stdout: RawSink, isTty: Boolean): Builder {
            this.stdout = stdout
            this.stdoutIsTty = isTty
            return this
        }

        actual fun withStderr(stderr: RawSink): Builder {
            this.stderr = stderr
            return this
        }

        actual fun withStderr(stderr: RawSink, isTty: Boolean): Builder {
            this.stderr = stderr
            this.stderrIsTty = isTty
            return this
        }

        actual fun withStdin(stdin: RawSource): Builder {
            this.stdin = stdin
            return this
        }

        actual fun withStdin(stdin: RawSource, isTty: Boolean): Builder {
            this.stdin = stdin
            this.stdinIsTty = isTty
            return this
        }

        actual fun withArguments(arguments: List<String>): Builder {
            this.arguments = arguments.toList()
            return this
        }

        actual fun withEnvironment(name: String, value: String): Builder {
            environment[name] = value
            return this
        }

        actual fun withDirectory(guest: String, host: Path): Builder =
            withDirectory(guest, host, defaultWasiFileSystem())

        actual fun withDirectory(guest: String, host: Path, fileSystem: FileSystem): Builder {
            preopenedDirectories[guest] = WasiDirectory(fileSystem, host)
            return this
        }

        actual fun withThrowOnExit0(throwOnExit0: Boolean): Builder {
            this.throwOnExit0 = throwOnExit0
            return this
        }

        actual fun build(): WasiOptions =
            WasiOptions(
                random,
                clock,
                stdout,
                stderr,
                stdin,
                stdinIsTty,
                stdoutIsTty,
                stderrIsTty,
                arguments,
                environment,
                preopenedDirectories,
                throwOnExit0,
            )
    }

    private class BlackholeSink : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            source.skip(byteCount)
        }

        override fun flush() {
        }

        override fun close() {
        }
    }

    private class NullSource : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L

        override fun close() {
        }
    }
}
