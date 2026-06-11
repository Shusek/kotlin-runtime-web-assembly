package uk.shusek.krwa.wasi.preview3

import kotlin.random.Random as KotlinRandom
import kotlin.time.Clock as KotlinClock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath
import org.kotlincrypto.random.CryptoRand
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WasmPlugin

public actual class KotlinWasiPreview3 private constructor(
    public val wasi: WasiPreview3,
    public actual val fileSystems: Map<String, WasiFileSystem> = emptyMap(),
) {
    public actual val version: String = wasi.version()

    public fun install(builder: WasmPlugin.Builder): WasmPlugin.Builder = wasi.install(builder)

    public suspend fun <T> await(future: WitFuture<T>): T = wasi.await(future)

    public fun <T> completed(value: T): WitFuture<T> = wasi.completed(value)

    @OptIn(ExperimentalUnsignedTypes::class)
    public fun byteStream(bytes: ByteArray): WitStream<UByte> = wasi.byteStream(bytes)

    public actual fun fileSystem(guestRoot: String): WasiFileSystem =
        fileSystemOrNull(guestRoot)
            ?: throw IllegalArgumentException("unknown WASI Preview 3 preopen ${WasiFileSystem.normalizeGuestRoot(guestRoot)}")

    public actual fun fileSystemOrNull(guestRoot: String): WasiFileSystem? =
        fileSystems[WasiFileSystem.normalizeGuestRoot(guestRoot)]

    public actual fun close() {
        wasi.close()
    }

    public actual fun cancel() {
        wasi.cancel()
    }

    public actual companion object {
        public actual fun builder(): Builder = Builder()

        public fun of(wasi: WasiPreview3): KotlinWasiPreview3 =
            KotlinWasiPreview3(wasi)
    }

    public actual class Builder {
        private val delegate: WasiPreview3.Builder = WasiPreview3.builder()
        private val fileSystems: MutableMap<String, WasiFileSystem> = LinkedHashMap()

        public actual fun withVersion(version: String): Builder {
            delegate.withVersion(version)
            return this
        }

        public actual fun withArguments(arguments: List<String>): Builder {
            delegate.withArguments(arguments)
            return this
        }

        public actual fun withArguments(vararg arguments: String): Builder {
            delegate.withArguments(*arguments)
            return this
        }

        public actual fun withEnvironment(environment: Map<String, String>): Builder {
            delegate.withEnvironment(environment)
            return this
        }

        public actual fun withEnvironment(name: String, value: String): Builder {
            delegate.withEnvironment(name, value)
            return this
        }

        public actual fun withInitialCwd(initialCwd: String?): Builder {
            delegate.withInitialCwd(initialCwd)
            return this
        }

        public actual fun withWallClock(
            now: () -> WasiInstant,
            zoneId: String,
            resolution: Duration,
        ): Builder {
            delegate.withWallClock({ now().toKotlinInstant() }, zoneId)
            delegate.withWallClockResolutionNanos(requirePositiveNanos("wallClockResolution", resolution))
            return this
        }

        @OptIn(ExperimentalTime::class)
        public actual fun withWallClock(
            clock: KotlinClock,
            zoneId: String,
            resolution: Duration,
        ): Builder {
            delegate.withWallClock(clock, zoneId)
            delegate.withWallClockResolutionNanos(requirePositiveNanos("wallClockResolution", resolution))
            return this
        }

        public actual fun withFixedWallClock(
            instant: WasiInstant,
            zoneId: String,
            resolution: Duration,
        ): Builder = withWallClock({ instant }, zoneId, resolution)

        public actual fun withWallClockResolution(resolution: Duration): Builder {
            delegate.withWallClockResolutionNanos(requirePositiveNanos("wallClockResolution", resolution))
            return this
        }

        public actual fun withMonotonicClock(monotonicClock: () -> Duration): Builder {
            delegate.withMonotonicClock { monotonicClock().inWholeNanoseconds }
            return this
        }

        public actual fun withMonotonicResolution(resolution: Duration): Builder {
            delegate.withMonotonicResolutionNanos(requirePositiveNanos("monotonicResolution", resolution))
            return this
        }

        public actual fun withSecureRandom(secureRandom: CryptoRand): Builder {
            delegate.withSecureRandom(secureRandom)
            return this
        }

        public actual fun withSecureRandom(secureRandom: KotlinRandom): Builder {
            delegate.withSecureRandom(secureRandom)
            return this
        }

        public actual fun withInsecureRandom(insecureRandom: KotlinRandom): Builder {
            delegate.withInsecureRandom(insecureRandom)
            return this
        }

        public actual fun withInsecureSeed(lower: ULong, upper: ULong): Builder {
            delegate.withInsecureSeed(lower.toLong(), upper.toLong())
            return this
        }

        public actual fun withPreopenedDirectory(guestPath: String, hostPath: String): Builder {
            return withPreopenedDirectory(guestPath, hostPath, true)
        }

        public actual fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: String): Builder {
            return withPreopenedDirectory(guestPath, hostPath, false)
        }

        public actual fun withPreopenedDirectory(
            guestPath: String,
            hostPath: String,
            writable: Boolean,
        ): Builder {
            val hostRoot = FileSystem.SYSTEM.canonicalize(hostPath.toPath(normalize = true))
            delegate.withPreopenedDirectory(guestPath, hostRoot, writable)
            val fileSystem = WasiFileSystem.create(guestPath, hostRoot, writable)
            fileSystems[fileSystem.guestRoot] = fileSystem
            return this
        }

        public actual fun withTerminalStdin(terminalStdin: Boolean): Builder {
            delegate.withTerminalStdin(terminalStdin)
            return this
        }

        public actual fun withTerminalStdout(terminalStdout: Boolean): Builder {
            delegate.withTerminalStdout(terminalStdout)
            return this
        }

        public actual fun withTerminalStderr(terminalStderr: Boolean): Builder {
            delegate.withTerminalStderr(terminalStderr)
            return this
        }

        public actual fun withNetworking(): Builder {
            delegate.withNetworking()
            return this
        }

        public actual fun withNetworking(networkingEnabled: Boolean): Builder {
            delegate.withNetworking(networkingEnabled)
            return this
        }

        public actual fun withoutNetworking(): Builder {
            delegate.withoutNetworking()
            return this
        }

        public actual fun withResourceBudget(
            parallelism: Int,
            streamBufferCapacity: Int,
            maxPendingFutures: Int,
            maxPendingStreams: Int,
            maxWaitables: Int,
            dispatcher: CoroutineDispatcher,
        ): Builder {
            delegate.withResourceBudget(
                parallelism,
                streamBufferCapacity,
                maxPendingFutures,
                maxPendingStreams,
                maxWaitables,
                dispatcher,
            )
            return this
        }

        public actual fun withCoroutineScope(scope: CoroutineScope): Builder {
            delegate.withCoroutineScope(scope)
            return this
        }

        public actual fun withCoroutineDispatcher(dispatcher: CoroutineDispatcher): Builder {
            delegate.withCoroutineDispatcher(dispatcher)
            return this
        }

        public actual fun build(): KotlinWasiPreview3 =
            KotlinWasiPreview3(delegate.build(), fileSystems.toMap())

        private fun requirePositiveNanos(name: String, duration: Duration): Long {
            val nanos = duration.inWholeNanoseconds
            require(nanos > 0L) { "$name must be positive and at least 1ns" }
            return nanos
        }
    }

}
