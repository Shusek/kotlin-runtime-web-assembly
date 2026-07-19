# KRWA WASI Preview 3 Kotlin Facade

`uk.shusek.krwa:wasi-preview3` is the first-party Kotlin entrypoint for the
stable WASI Preview 3 support in KRWA.

It depends on `component-model`, re-exports the WIT runtime handle types, and
adds Kotlin coroutine conveniences on top:

- `KotlinWasiPreview3`, a Kotlin-facing builder/facade around `WasiPreview3`,
- `WasiPreview3.await(future)` for typed `WitFuture<T>` values,
- `WitFuture<T>.asDeferred(...)`,
- `Deferred<T>.toCompletedWitFuture(...)`,
- `Deferred<T>.toWitFuture(...)` for non-blocking pending future handles that
  complete from a coroutine scope,
- `WitStream<*>.awaitReadable(...)` and `awaitWritable(...)` for
  coroutine-friendly readiness waiting on canonical stream handles, including
  buffered host streams and Ktor-backed TCP receive/listen streams,
- `withCoroutineScope(...)` / `withCoroutineDispatcher(...)` for host coroutine
  scheduling,
- `withResourceBudget(...)` for P3 parallelism, stream buffering, and async
  resource policy,
- `close()` / `cancel()` for cancelling the default host coroutine scope,
- byte stream helpers for `stream<u8>`,
- typed list stream helpers for host-side stream values,
- Kotlin-first clock and random builder APIs using `kotlin.time.Duration`,
  Kotlin lambdas, `kotlin.random.Random`, and unsigned seeds,
- `WasiFileSystem`, an Okio-style first-party facade over WASI preopened
  directories.

Example:

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import uk.shusek.krwa.wasi.preview3.KotlinWasiPreview3
import uk.shusek.krwa.wasi.preview3.WasiHttpNetworkEndpoint
import uk.shusek.krwa.wasi.preview3.WasiHttpNetworkProtocol
import uk.shusek.krwa.wasi.preview3.WasiNetworkPolicy
import uk.shusek.krwa.wasi.preview3.asDeferred

fun main() = runBlocking {
    val runtime = KotlinWasiPreview3.builder()
        .withNetworkPolicy(
            WasiNetworkPolicy(
                httpEndpoints = setOf(
                    WasiHttpNetworkEndpoint(
                        WasiHttpNetworkProtocol.Https,
                        "api.example.com",
                        443,
                    ),
                ),
            ),
        )
        .withResourceBudget(
            parallelism = 2,
            streamBufferCapacity = 64 * 1024,
            dispatcher = Dispatchers.Default,
        )
        .build()

    val future = runtime.completed("ready")
    val value = runtime.await(future)
    val deferred = future.asDeferred(runtime.wasi, this)

    check(value == deferred.await())
    runtime.close()
}
```

The default network policy denies all access. HTTP scheme, canonical host, and
port are matched exactly; raw-socket grants are independent.

## CPU Budgets and Coroutine Scheduling

The coroutine APIs in this facade are for cooperative WASI Preview 3 work:
waiting for `future<T>`, stream readiness, host tasks, and coroutine-scheduled
P3 resumes. They are not a CPU quota for guest execution.

When a precompiled Preview 3 component runs through the Wasmtime bridge,
`WasmtimePreview3ComponentConfig(maxFuel = ...)` can enforce a deterministic
Wasmtime fuel budget for guest Wasm instructions. Fuel is consumed only while
guest Wasm is running; a component call waiting in host code or an already
instantiated module waiting to be called does not burn fuel. Because the bridge
uses precompiled component bytes, compile fuel-enabled artifacts with matching
Wasmtime settings, for example `wasmtime compile -W fuel=1 ...`, before setting a
finite `maxFuel`.

`withCoroutineScope(...)` and `withCoroutineDispatcher(...)` decide where P3
host tasks run. If that scope or dispatcher has parallelism greater than one,
the guest-visible async surface can make progress on multiple CPU cores at the
same time. A wall-clock timeout around one Wasm entry call does not account for
that extra parallel CPU use.

For workloads where billing or safety policy assumes one core, use a one-lane
resource budget:

```kotlin
import kotlinx.coroutines.Dispatchers
import uk.shusek.krwa.wasi.preview3.KotlinWasiPreview3

val runtime = KotlinWasiPreview3.builder()
    .withResourceBudget(parallelism = 1, dispatcher = Dispatchers.Default)
    .build()
```

If a host intentionally allows higher parallelism, account for CPU usage as
parallel work, not only as elapsed wall time. Still enforce the regular runtime
CPU timeout at each Wasm call or resume boundary; coroutine cancellation can
cancel suspending host work, but it does not preempt CPU-bound Wasm without the
runtime interruption policy described in
[`CPU limits`](../../docs/pages/execution/cpu-limits.md).

`withResourceBudget(...)` sets dispatcher parallelism, stream buffer capacity,
and the usual guest-visible P3 limits together.

`withResourceBudget(...)` does not measure CPU. `parallelism` is an upper bound
on how many P3 lanes may run, not proof that all lanes were busy. If a host sets
`parallelism = 6` and a guest only keeps two cores busy, the budget allowed six
lanes but actual CPU use was closer to two core-seconds per wall-clock second.
Measure that actual CPU use outside coroutine scheduling: on the JVM, use a
dedicated dispatcher backed by a fixed worker pool and platform thread CPU
counters; for stronger isolation, run the workload in a process or container
and read OS or cgroup CPU accounting.

This module does not replace the canonical ABI model. `future<T>` and
`stream<T>` still cross the Wasm boundary as `WitFuture<T>` and `WitStream<T>`
handles; the facade is the Kotlin-friendly layer for first-party users. When a
canonical future or stream read/write blocks, KRWA tracks that operation as a
P3 waitable so `waitable-set.poll` and `waitable-set.wait` can observe progress
through the corresponding future/stream event. Canonical thread imports are
minimal on the required platform execution path: `thread.index` and
`thread.yield` are available for stackless hosts, while stackful thread creation,
suspend, resume, and continuation-capture intrinsics are rejected.
JVM and iOS provide Ktor-backed default socket runtimes. wasmJs provides
Ktor-backed HTTP and suspend TCP connect/listen paths for Node-backed
environments; browser raw sockets, wasmJs UDP, and the default browser
filesystem remain platform-unavailable and must be supplied by
application-specific host imports when needed.

Clock and random configuration also stay Kotlin-facing:

```kotlin
import kotlin.random.Random
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import uk.shusek.krwa.wasi.preview3.WasiInstant

val monotonicBase = TimeSource.Monotonic.markNow()
val runtime = KotlinWasiPreview3.builder()
    .withFixedWallClock(WasiInstant.fromEpochSeconds(1_780_963_200L), resolution = 1.nanoseconds)
    .withMonotonicClock { monotonicBase.elapsedNow() }
    .withMonotonicResolution(1.nanoseconds)
    .withSecureRandom(Random.Default)
    .withInsecureSeed(11uL, 12uL)
    .build()
```

Generated WIT contracts can target this facade package directly:

```kotlin
val kotlin = KotlinWitBindings.builder(witPackage)
    .withPackageName("example.generated")
    .withRuntimePackageName("uk.shusek.krwa.wasi.preview3")
    .build()
    .generate()
```

Filesystem usage stays capability-based: first preopen a host directory, then use
the facade rooted at the guest path:

```kotlin
val runtime = KotlinWasiPreview3.builder()
    .withPreopenedDirectory("/", "data")
    .build()

val fs = runtime.fileSystem("/")
fs.writeText("out/result.txt", "done")
val bytes = fs.readBytes("out/result.txt")
val stream = fs.readWitByteStream("out/result.txt", runtime.wasi)
```

The facade rejects paths that escape the preopen root, so `../outside.txt` is not
accepted.
