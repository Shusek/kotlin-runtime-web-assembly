package uk.shusek.krwa.wasi.preview3

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import org.kotlincrypto.random.CryptoRand

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
    public val networkingEnabled: Boolean,
) {
    public actual fun fileSystem(guestRoot: String): WasiFileSystem =
        fileSystemOrNull(guestRoot)
            ?: throw IllegalArgumentException("unknown WASI Preview 3 preopen ${WasiFileSystem.normalizeGuestRoot(guestRoot)}")

    public actual fun fileSystemOrNull(guestRoot: String): WasiFileSystem? =
        fileSystems[WasiFileSystem.normalizeGuestRoot(guestRoot)]

    public actual companion object {
        public actual fun builder(): Builder = Builder()
    }

    public actual class Builder {
        private var version: String = "0.2.0"
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
        private var networkingEnabled = false

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

        public actual fun withNetworking(): Builder =
            withNetworking(true)

        public actual fun withNetworking(networkingEnabled: Boolean): Builder {
            this.networkingEnabled = networkingEnabled
            return this
        }

        public actual fun withoutNetworking(): Builder =
            withNetworking(false)

        public actual fun build(): KotlinWasiPreview3 =
            KotlinWasiPreview3(
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
                networkingEnabled,
            )

        private fun requirePositive(name: String, duration: Duration): Duration {
            require(duration.inWholeNanoseconds > 0L) {
                "$name must be positive and at least 1ns"
            }
            return duration
        }
    }
}
