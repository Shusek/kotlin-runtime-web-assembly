package uk.shusek.krwa.wasi

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections.unmodifiableMap
import java.util.LinkedHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import okio.FileSystem
import okio.Path as OkioPath
import okio.Path.Companion.toPath
import org.kotlincrypto.random.CryptoRand

actual class WasiOptions
private constructor(
    private val random: CryptoRand,
    private val clock: Clock,
    private val stdout: RawSink,
    private val stderr: RawSink,
    private val stdin: RawSource,
    private val stdinAvailable: (() -> Int)?,
    private val stdinIsTty: Boolean,
    private val stdoutIsTty: Boolean,
    private val stderrIsTty: Boolean,
    arguments: List<String>,
    environment: Map<String, String>,
    directories: Map<String, Path>,
    preopenedDirectories: Map<String, WasiDirectory>,
    private val throwOnExit0: Boolean,
) {
    private val arguments: List<String> = java.util.List.copyOf(arguments)
    private val environment: Map<String, String> = unmodifiableMap(LinkedHashMap(environment))
    private val directories: Map<String, Path> = unmodifiableMap(LinkedHashMap(directories))
    private val preopenedDirectories: Map<String, WasiDirectory> =
        unmodifiableMap(LinkedHashMap(preopenedDirectories))

    actual fun random(): CryptoRand = random

    actual fun clock(): Clock = clock

    fun stdout(): OutputStream = stdout.buffered().asOutputStream()

    fun stderr(): OutputStream = stderr.buffered().asOutputStream()

    fun stdin(): InputStream = stdin.buffered().asInputStream()

    actual fun stdoutSink(): RawSink = stdout

    actual fun stderrSink(): RawSink = stderr

    actual fun stdinSource(): RawSource = stdin

    actual fun stdinAvailable(): Int = stdinAvailable?.invoke() ?: 0

    actual fun stdinIsTty(): Boolean = stdinIsTty

    actual fun stdoutIsTty(): Boolean = stdoutIsTty

    actual fun stderrIsTty(): Boolean = stderrIsTty

    actual fun arguments(): List<String> = arguments

    actual fun environment(): Map<String, String> = environment

    fun directories(): Map<String, Path> = directories

    actual fun preopenedDirectories(): Map<String, WasiDirectory> = preopenedDirectories

    actual fun throwOnExit0(): Boolean = throwOnExit0

    actual companion object {
        @JvmStatic actual fun builder(): Builder = Builder()
    }

    actual class Builder {
        private var random: CryptoRand = CryptoRand.Default
        @OptIn(ExperimentalTime::class)
        private var clock: Clock = Clock.System
        private var stdout: RawSink = IO.nullSink()
        private var stderr: RawSink = IO.nullSink()
        private var stdin: RawSource = IO.nullSource()
        private var stdinAvailable: (() -> Int)? = null
        private var stdinIsTty = true
        private var stdoutIsTty = true
        private var stderrIsTty = true
        private var arguments: List<String> = emptyList()
        private val environment: MutableMap<String, String> = LinkedHashMap()
        private val directories: MutableMap<String, Path> = LinkedHashMap()
        private val preopenedDirectories: MutableMap<String, WasiDirectory> = LinkedHashMap()
        private var throwOnExit0 = true

        actual fun withRandom(random: CryptoRand): Builder {
            this.random = random
            return this
        }

        actual fun withRandom(random: Random): Builder {
            return withRandom(KotlinRandomCryptoRand(random))
        }

        fun withRandom(random: java.util.Random): Builder {
            return withRandom(random.asKotlinRandom())
        }

        actual fun withClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        fun withStdout(stdout: OutputStream): Builder {
            return withStdout(stdout.asSink())
        }

        fun withStdout(stdout: OutputStream, isTty: Boolean): Builder {
            return withStdout(stdout.asSink(), isTty)
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

        fun withStderr(stderr: OutputStream): Builder {
            return withStderr(stderr.asSink())
        }

        fun withStderr(stderr: OutputStream, isTty: Boolean): Builder {
            return withStderr(stderr.asSink(), isTty)
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

        fun withStdin(stdin: InputStream): Builder {
            return withStdin(stdin.asSource(), { stdin.available() })
        }

        fun withStdin(stdin: InputStream, isTty: Boolean): Builder {
            return withStdin(stdin.asSource(), { stdin.available() }, isTty)
        }

        actual fun withStdin(stdin: RawSource): Builder {
            return withStdin(stdin, null)
        }

        actual fun withStdin(stdin: RawSource, isTty: Boolean): Builder {
            return withStdin(stdin, null, isTty)
        }

        private fun withStdin(stdin: RawSource, available: (() -> Int)?): Builder {
            this.stdin = stdin
            this.stdinAvailable = available
            return this
        }

        private fun withStdin(stdin: RawSource, available: (() -> Int)?, isTty: Boolean): Builder {
            this.stdin = stdin
            this.stdinAvailable = available
            this.stdinIsTty = isTty
            return this
        }

        fun inheritSystem(): Builder {
            stdout = System.out.asSink()
            stdin = System.`in`.asSource()
            stdinAvailable = { System.`in`.available() }
            stderr = System.err.asSink()
            return this
        }

        actual fun withArguments(arguments: List<String>): Builder {
            this.arguments = java.util.List.copyOf(arguments)
            return this
        }

        actual fun withEnvironment(name: String, value: String): Builder {
            environment[name] = value
            return this
        }

        fun withDirectory(guest: String, host: Path): Builder {
            directories[guest] = host
            preopenedDirectories[guest] =
                WasiDirectory(NioWasiFileSystem(host.fileSystem), host.normalize().toString().toPath(normalize = true))
            return this
        }

        actual fun withDirectory(guest: String, host: OkioPath): Builder =
            withDirectory(guest, host, defaultWasiFileSystem())

        actual fun withDirectory(guest: String, host: OkioPath, fileSystem: FileSystem): Builder {
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
                stdinAvailable,
                stdinIsTty,
                stdoutIsTty,
                stderrIsTty,
                arguments,
                environment,
                directories,
                preopenedDirectories,
                throwOnExit0,
            )
    }

}
