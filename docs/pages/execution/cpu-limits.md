# CPU Limits

The runtime does not own a global CPU quota. Hosts should enforce time budgets
around execution and treat cancellation as part of the embedding policy.

Wasmtime execution is a native call boundary. `Future.get(timeout, unit)` limits
how long the host waits, but it does not prove that the Wasm engine stopped at
that exact instant. Keep untrusted execution on a dedicated worker or process so
timeouts, cancellation, and process isolation are controlled by the embedder.

```kotlin
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
val executor = Executors.newSingleThreadExecutor()
val future = executor.submit<LongArray> {
    instance.export("run").apply()
}

try {
    val result = future.get(500, TimeUnit.MILLISECONDS)
    println(result.contentToString())
} catch (_: TimeoutException) {
    future.cancel(true) // sets Thread.interrupt() on the worker thread
    throw IllegalStateException("Wasm execution timed out")
} finally {
    executor.shutdownNow()
}
```

For untrusted code, keep all blocking host functions under the same timeout
policy. A timeout cannot preempt arbitrary host code that ignores cancellation.

Use `WasmtimeExecutionConfig` for limits the Wasmtime store can enforce during
instantiation and execution. `maxFuel` enables Wasmtime fuel metering for guest
Wasm instructions:

```kotlin
val instance =
    Instance.builder(module)
        .withExecutionBackend(ExecutionBackend.PULLEY)
        .withWasmtimeExecutionConfig(
            WasmtimeExecutionConfig(
                maxMemoryBytes = 64L * 1024L * 1024L,
                maxWasmStackBytes = 256L * 1024L,
                maxInstances = 1,
                maxTables = 32,
                maxMemories = 4,
                maxFuel = 5_000_000,
            ),
        )
        .build()
```

Fuel is a store budget, not a wall-clock timer. A module can be instantiated and
wait for the host to call an export without consuming fuel. Fuel is consumed only
while guest Wasm instructions run; if the budget is exhausted, Wasmtime traps the
call. `WasmtimeUnlimitedResourceLimit` (`-1`) disables fuel, `0` is allowed and
traps as soon as guest execution needs fuel, and positive values are the
available budget.

For a regular `Instance`, the budget belongs to that Wasmtime store and is shared
across exported calls on the same instance. For `WasmtimePreview3ComponentConfig`,
the Preview 3 bridge creates a fresh store per component call or command run, so
`maxFuel` is effectively a per-call budget. Precompiled Preview 3 components
must be compiled with fuel enabled, for example `wasmtime compile -W fuel=1 ...`,
otherwise Wasmtime rejects deserialization under a fuel-enabled engine.

Fuel does not measure host work. It does not tick while the guest is idle, while a
host import is blocked in I/O, or while the application waits outside Wasmtime.
Keep blocking imports under the same timeout and cancellation policy as the Wasm
entry call. On `wasmJs`, KRWA uses the browser or Node `WebAssembly` engine, which
does not expose Wasmtime fuel; use workers/timeouts or guest instrumentation for
that target.

## Coroutines and WASI Preview 3

Coroutine cancellation is not a CPU quota. `withTimeout`, `Job.cancel()`, or
closing a WASI Preview 3 host scope can cancel suspending host work, but they do
not preempt CPU-bound Wasm execution unless the Wasm call or resume is running
under the runtime interruption policy above.

WASI Preview 3 host tasks and coroutine-scheduled P3 resumes run in a host-owned
child job of the configured `CoroutineScope` or `CoroutineDispatcher`. Closing
the host cancels that child work without cancelling a caller-supplied scope. A
dispatcher with parallelism greater than one can use multiple CPU cores, so
billing based only on elapsed wall time may under-count work. If one-core
accounting is required, use `withResourceBudget(parallelism = 1)` so dispatcher
parallelism and the P3 async resource limits are set together.

`withResourceBudget(...)` is not a CPU meter. Its `parallelism` value is the
maximum amount of P3 work KRWA may run at the same time, not a measurement of
how much CPU the guest actually consumed. For example, a host may allow
`parallelism = 6` while a particular guest only keeps two cores busy. The
resource budget still caps exposure at six concurrent lanes, but it does not
prove that six CPU cores were used.

Use one of these accounting models:

- For simple one-core accounting, set `parallelism = 1` and charge elapsed time.
- For conservative multi-core accounting without CPU metering, charge at most
  `elapsedWallTime * parallelism`.
- For actual CPU accounting on the JVM, run each tenant or request on a
  dedicated worker pool and sum the pool thread CPU time with platform APIs
  such as `ThreadMXBean.getThreadCpuTime(...)`.
- For stricter production accounting, isolate execution in a process or
  container and use OS or cgroup CPU counters.

Coroutines and dispatchers do not provide portable per-coroutine CPU usage.
They are scheduling and cancellation primitives; they cannot tell the host
that one coroutine used two CPU-seconds while another used six. If exact CPU
metering matters, keep the metered work isolated from unrelated host work.

On non-JVM targets there is no host `Thread.interrupt()` equivalent. Use
target-specific cancellation around the host call boundary, and prefer small,
bounded host functions for untrusted workloads.
