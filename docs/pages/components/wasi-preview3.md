# WASI Preview 3

`wasi-preview3` is the Kotlin-first facade for stable WASI 0.3 support. It
depends on `component-model`, keeps canonical handle types at the Wasm boundary,
and adds coroutine-friendly Kotlin APIs.

Main surfaces:

- `KotlinWasiPreview3` builder and facade,
- `WasiPreview3.await(future)` for typed `WitFuture<T>` values,
- `WitFuture<T>.asDeferred(...)`,
- `Deferred<T>.toWitFuture(...)`,
- readable and writable readiness helpers for `WitStream<T>`,
- bounded stream buffers and waitable limits,
- Kotlin clock and random configuration,
- capability-based `WasiFileSystem` over preopened directories.

Example:

```kotlin
val runtime = KotlinWasiPreview3.builder()
    .withNetworking()
    .withResourceBudget(parallelism = 2, streamBufferCapacity = 64 * 1024)
    .build()

val future = runtime.completed("ready")
val value = runtime.await(future)
runtime.close()
```

When running precompiled Preview 3 components through the Wasmtime
bridge, `WasmtimePreview3ComponentConfig` carries the same resource limits as
the raw Wasmtime backend: `maxMemoryBytes`, `maxWasmStackBytes`,
`maxTableElements`, `maxInstances`, `maxTables`, and `maxMemories`. Count limits
accept `WasmtimeUnlimitedResourceLimit` (`-1`) for unlimited. The separate
`executionTimeoutMillis` value is a wall-clock bridge timeout and should not be
treated as deterministic fuel or CPU metering.

JVM and iOS provide Ktor-backed default socket runtimes. wasmJs provides
Ktor-backed HTTP and suspend TCP connect/listen paths for Node-backed
environments. Browser raw sockets, wasmJs UDP, and the default browser
filesystem remain platform-unavailable and must be supplied by the application
when needed.
