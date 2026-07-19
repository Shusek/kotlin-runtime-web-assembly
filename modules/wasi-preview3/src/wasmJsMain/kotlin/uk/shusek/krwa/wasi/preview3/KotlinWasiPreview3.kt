package uk.shusek.krwa.wasi.preview3

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.kotlincrypto.random.CryptoRand

private const val STREAM_MAX_LENGTH: Int = (1 shl 28) - 1
private const val DEFAULT_STREAM_BUFFER_CAPACITY: Int = 64 * 1024

public actual class KotlinWasiPreview3 private constructor(
    public actual val version: String,
    public actual val fileSystems: Map<String, WasiFileSystem>,
    public val arguments: List<String>,
    public val environment: Map<String, String>,
    public val initialCwd: String?,
    public val wallClock: () -> WasiInstant,
    public val wallClockZoneId: String,
    public val wallClockResolution: Duration,
    public val monotonicClock: () -> Duration,
    public val monotonicResolution: Duration,
    public val secureRandom: CryptoRand,
    public val insecureRandom: Random,
    public val insecureSeed: Pair<ULong, ULong>?,
    public val terminalStdin: Boolean,
    public val terminalStdout: Boolean,
    public val terminalStderr: Boolean,
    public val networkPolicy: WasiNetworkPolicy,
    public val networkingEnabled: Boolean,
    public val streamBufferCapacity: Int,
    public val maxCanonicalThreads: Int,
    public val maxPendingFutures: Int,
    public val maxPendingStreams: Int,
    public val maxWaitables: Int,
    public val maxInFlightHostTasks: Int,
    public val coroutineScope: CoroutineScope?,
    private val ownsCoroutineScope: Boolean,
) : AutoCloseable {
    public actual fun fileSystem(guestRoot: String): WasiFileSystem =
        fileSystemOrNull(guestRoot)
            ?: throw IllegalArgumentException("unknown WASI Preview 3 preopen ${WasiFileSystem.normalizeGuestRoot(guestRoot)}")

    public actual fun fileSystemOrNull(guestRoot: String): WasiFileSystem? =
        fileSystems[WasiFileSystem.normalizeGuestRoot(guestRoot)]

    public actual override fun close() {
        if (ownsCoroutineScope) {
            coroutineScope?.cancel()
        }
    }

    public actual fun cancel() {
    }

    public actual companion object {
        public actual fun builder(): Builder = Builder()
    }

    public actual class Builder {
        private var version: String = "0.3.0"
        private var arguments: List<String> = emptyList()
        private val environment: MutableMap<String, String> = LinkedHashMap()
        private var initialCwd: String? = null
        private var wallClock: () -> WasiInstant = { WasiInstant.now() }
        private var wallClockZoneId: String = "UTC"
        private var wallClockResolution: Duration = 1.nanoseconds
        private var monotonicClock: () -> Duration = { Duration.ZERO }
        private var monotonicResolution: Duration = 1.nanoseconds
        private var secureRandom: CryptoRand = CryptoRand.Default
        private var insecureRandom: Random = Random.Default
        private var insecureSeed: Pair<ULong, ULong>? = null
        private val fileSystems: MutableMap<String, WasiFileSystem> = LinkedHashMap()
        private var terminalStdin = true
        private var terminalStdout = true
        private var terminalStderr = true
        private var networkPolicy = WasiNetworkPolicy.DENY_ALL
        private var networkingEnabled = false
        private var streamBufferCapacity = DEFAULT_STREAM_BUFFER_CAPACITY
        private var maxCanonicalThreads = Int.MAX_VALUE
        private var maxPendingFutures = Int.MAX_VALUE
        private var maxPendingStreams = Int.MAX_VALUE
        private var maxWaitables = Int.MAX_VALUE
        private var maxInFlightHostTasks = Int.MAX_VALUE
        private var coroutineScope: CoroutineScope? = null
        private var ownsCoroutineScope: Boolean = false

        public actual fun withVersion(version: String): Builder {
            this.version = version
            return this
        }

        public actual fun withArguments(arguments: List<String>): Builder {
            this.arguments = arguments.toList()
            return this
        }

        public actual fun withArguments(vararg arguments: String): Builder =
            withArguments(arguments.toList())

        public actual fun withEnvironment(environment: Map<String, String>): Builder {
            this.environment.clear()
            this.environment.putAll(environment)
            return this
        }

        public actual fun withEnvironment(name: String, value: String): Builder {
            environment[name] = value
            return this
        }

        public actual fun withInitialCwd(initialCwd: String?): Builder {
            this.initialCwd = initialCwd
            return this
        }

        public actual fun withWallClock(
            now: () -> WasiInstant,
            zoneId: String,
            resolution: Duration,
        ): Builder {
            wallClock = now
            wallClockZoneId = zoneId
            wallClockResolution = requirePositive("wallClockResolution", resolution)
            return this
        }

        @OptIn(ExperimentalTime::class)
        public actual fun withWallClock(
            clock: Clock,
            zoneId: String,
            resolution: Duration,
        ): Builder =
            withWallClock({ WasiInstant.fromKotlinInstant(clock.now()) }, zoneId, resolution)

        public actual fun withFixedWallClock(
            instant: WasiInstant,
            zoneId: String,
            resolution: Duration,
        ): Builder = withWallClock({ instant }, zoneId, resolution)

        public actual fun withWallClockResolution(resolution: Duration): Builder {
            wallClockResolution = requirePositive("wallClockResolution", resolution)
            return this
        }

        public actual fun withMonotonicClock(monotonicClock: () -> Duration): Builder {
            this.monotonicClock = monotonicClock
            return this
        }

        public actual fun withMonotonicResolution(resolution: Duration): Builder {
            monotonicResolution = requirePositive("monotonicResolution", resolution)
            return this
        }

        public actual fun withSecureRandom(secureRandom: CryptoRand): Builder {
            this.secureRandom = secureRandom
            return this
        }

        public actual fun withSecureRandom(secureRandom: Random): Builder {
            return withSecureRandom(KotlinRandomCryptoRand(secureRandom))
        }

        public actual fun withInsecureRandom(insecureRandom: Random): Builder {
            this.insecureRandom = insecureRandom
            return this
        }

        public actual fun withInsecureSeed(lower: ULong, upper: ULong): Builder {
            insecureSeed = lower to upper
            return this
        }

        public actual fun withPreopenedDirectory(guestPath: String, hostPath: String): Builder =
            withPreopenedDirectory(guestPath, hostPath, true)

        public actual fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: String): Builder =
            withPreopenedDirectory(guestPath, hostPath, false)

        public actual fun withPreopenedDirectory(
            guestPath: String,
            hostPath: String,
            writable: Boolean,
        ): Builder {
            val fileSystem = WasiFileSystem.create(guestPath, hostPath, writable)
            fileSystems[fileSystem.guestRoot] = fileSystem
            return this
        }

        public actual fun withTerminalStdin(terminalStdin: Boolean): Builder {
            this.terminalStdin = terminalStdin
            return this
        }

        public actual fun withTerminalStdout(terminalStdout: Boolean): Builder {
            this.terminalStdout = terminalStdout
            return this
        }

        public actual fun withTerminalStderr(terminalStderr: Boolean): Builder {
            this.terminalStderr = terminalStderr
            return this
        }

        public actual fun withNetworkPolicy(networkPolicy: WasiNetworkPolicy): Builder {
            this.networkPolicy = networkPolicy
            networkingEnabled = false
            return this
        }

        @Deprecated(
            "Unrestricted networking bypasses endpoint isolation. Use withNetworkPolicy.",
        )
        public actual fun withNetworking(): Builder =
            withNetworking(true)

        @Deprecated(
            "Unrestricted networking bypasses endpoint isolation. Use withNetworkPolicy.",
        )
        public actual fun withNetworking(networkingEnabled: Boolean): Builder {
            this.networkingEnabled = networkingEnabled
            networkPolicy = WasiNetworkPolicy.DENY_ALL
            return this
        }

        public actual fun withoutNetworking(): Builder {
            networkingEnabled = false
            networkPolicy = WasiNetworkPolicy.DENY_ALL
            return this
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        public actual fun withResourceBudget(
            parallelism: Int,
            streamBufferCapacity: Int,
            maxPendingFutures: Int,
            maxPendingStreams: Int,
            maxWaitables: Int,
            dispatcher: CoroutineDispatcher,
        ): Builder {
            val checkedParallelism = requirePositiveLimit("parallelism", parallelism)
            require(streamBufferCapacity in 1..STREAM_MAX_LENGTH) {
                "streamBufferCapacity must be between 1 and $STREAM_MAX_LENGTH"
            }
            this.streamBufferCapacity = streamBufferCapacity
            this.maxCanonicalThreads = checkedParallelism
            this.maxInFlightHostTasks = checkedParallelism
            this.maxPendingFutures = requirePositiveLimit("maxPendingFutures", maxPendingFutures)
            this.maxPendingStreams = requirePositiveLimit("maxPendingStreams", maxPendingStreams)
            this.maxWaitables = requirePositiveLimit("maxWaitables", maxWaitables)
            return withCoroutineDispatcher(dispatcher.limitedParallelism(checkedParallelism))
        }

        public actual fun withCoroutineScope(scope: CoroutineScope): Builder {
            coroutineScope = scope
            ownsCoroutineScope = false
            return this
        }

        public actual fun withCoroutineDispatcher(dispatcher: CoroutineDispatcher): Builder =
            apply {
                coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
                ownsCoroutineScope = true
            }

        public actual fun build(): KotlinWasiPreview3 {
            val configuredScope = coroutineScope
            val hostScope =
                if (configuredScope == null || ownsCoroutineScope) {
                    configuredScope
                } else {
                    CoroutineScope(
                        configuredScope.coroutineContext +
                            SupervisorJob(configuredScope.coroutineContext[Job])
                    )
                }
            return KotlinWasiPreview3(
                version,
                fileSystems.toMap(),
                arguments,
                environment.toMap(),
                initialCwd,
                wallClock,
                wallClockZoneId,
                wallClockResolution,
                monotonicClock,
                monotonicResolution,
                secureRandom,
                insecureRandom,
                insecureSeed,
                terminalStdin,
                terminalStdout,
                terminalStderr,
                networkPolicy,
                networkingEnabled,
                streamBufferCapacity,
                maxCanonicalThreads,
                maxPendingFutures,
                maxPendingStreams,
                maxWaitables,
                maxInFlightHostTasks,
                hostScope,
                hostScope != null,
            )
        }

        private fun requirePositive(name: String, duration: Duration): Duration {
            require(duration.inWholeNanoseconds > 0L) {
                "$name must be positive and at least 1ns"
            }
            return duration
        }

        private fun requirePositiveLimit(name: String, value: Int): Int {
            require(value > 0) { "$name must be positive" }
            return value
        }
    }
}
