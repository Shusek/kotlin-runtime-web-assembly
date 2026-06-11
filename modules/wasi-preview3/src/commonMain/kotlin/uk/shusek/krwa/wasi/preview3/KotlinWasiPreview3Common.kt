package uk.shusek.krwa.wasi.preview3

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.kotlincrypto.random.CryptoRand

public expect class KotlinWasiPreview3 {
    public val version: String

    public val fileSystems: Map<String, WasiFileSystem>

    public fun fileSystem(guestRoot: String = "/"): WasiFileSystem

    public fun fileSystemOrNull(guestRoot: String = "/"): WasiFileSystem?

    public fun close()

    public fun cancel()

    public companion object {
        public fun builder(): Builder
    }

    public class Builder {
        public fun withVersion(version: String): Builder

        public fun withArguments(arguments: List<String>): Builder

        public fun withArguments(vararg arguments: String): Builder

        public fun withEnvironment(environment: Map<String, String>): Builder

        public fun withEnvironment(name: String, value: String): Builder

        public fun withInitialCwd(initialCwd: String?): Builder

        public fun withWallClock(
            now: () -> WasiInstant,
            zoneId: String = "UTC",
            resolution: Duration = 1.nanoseconds,
        ): Builder

        @OptIn(ExperimentalTime::class)
        public fun withWallClock(
            clock: Clock,
            zoneId: String = "UTC",
            resolution: Duration = 1.nanoseconds,
        ): Builder

        public fun withFixedWallClock(
            instant: WasiInstant,
            zoneId: String = "UTC",
            resolution: Duration = 1.nanoseconds,
        ): Builder

        public fun withWallClockResolution(resolution: Duration): Builder

        public fun withMonotonicClock(monotonicClock: () -> Duration): Builder

        public fun withMonotonicResolution(resolution: Duration): Builder

        public fun withSecureRandom(secureRandom: CryptoRand): Builder

        public fun withSecureRandom(secureRandom: Random): Builder

        public fun withInsecureRandom(insecureRandom: Random): Builder

        public fun withInsecureSeed(lower: ULong, upper: ULong): Builder

        public fun withPreopenedDirectory(guestPath: String, hostPath: String): Builder

        public fun withReadOnlyPreopenedDirectory(guestPath: String, hostPath: String): Builder

        public fun withPreopenedDirectory(
            guestPath: String,
            hostPath: String,
            writable: Boolean,
        ): Builder

        public fun withTerminalStdin(terminalStdin: Boolean): Builder

        public fun withTerminalStdout(terminalStdout: Boolean): Builder

        public fun withTerminalStderr(terminalStderr: Boolean): Builder

        public fun withNetworking(): Builder

        public fun withNetworking(networkingEnabled: Boolean): Builder

        public fun withoutNetworking(): Builder

        public fun withResourceBudget(
            parallelism: Int,
            streamBufferCapacity: Int = 64 * 1024,
            maxPendingFutures: Int = 1_024,
            maxPendingStreams: Int = 1_024,
            maxWaitables: Int = maxPendingFutures + maxPendingStreams,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): Builder

        public fun withCoroutineScope(scope: CoroutineScope): Builder

        public fun withCoroutineDispatcher(dispatcher: CoroutineDispatcher): Builder

        public fun build(): KotlinWasiPreview3
    }
}
