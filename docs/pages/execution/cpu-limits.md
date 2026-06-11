# CPU Limits

The runtime does not own a global CPU quota. Hosts should enforce time budgets
around execution and treat cancellation as part of the embedding policy.

On the JVM, the default interpreter, the SIMD interpreter machine, and compiled
machines poll `Thread.currentThread().isInterrupted`. Interrupting the worker
thread terminates guest execution with `WasmInterruptedException`.

```kotlin
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import uk.shusek.krwa.runtime.WasmInterruptedException

val executor = Executors.newSingleThreadExecutor()
val future = executor.submit<LongArray> {
    instance.export("run").apply()
}

try {
    val result = future.get(500, TimeUnit.MILLISECONDS)
    println(result.contentToString())
} catch (_: TimeoutException) {
    future.cancel(true) // sets Thread.interrupt() on the worker thread
    throw WasmInterruptedException("execution timed out")
} finally {
    executor.shutdownNow()
}
```

`Future.get(timeout, unit)` only limits how long the host waits. The call to
`cancel(true)` is what asks the JVM worker thread to stop.

For untrusted code, run guest execution on a worker that can be interrupted,
and keep all blocking host functions under the same timeout policy. A timeout
cannot preempt arbitrary host code that ignores interruption.

## Coroutines and WASI Preview 3

Coroutine cancellation is not a CPU quota. `withTimeout`, `Job.cancel()`, or
closing a WASI Preview 3 host scope can cancel suspending host work, but they do
not preempt CPU-bound Wasm execution unless the Wasm call or resume is running
under the runtime interruption policy above.

WASI Preview 3 host tasks and coroutine-scheduled P3 resumes run on the
configured `CoroutineScope` or `CoroutineDispatcher`. A dispatcher with
parallelism greater than one can use multiple CPU cores, so billing based only
on elapsed wall time may under-count work. If one-core accounting is required,
use `withResourceBudget(parallelism = 1)` so dispatcher parallelism and the P3
async resource limits are set together.

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

## Instruction Listener

`Instance.Builder.withUnsafeExecutionListener(...)` can observe interpreter
instructions and may be useful for profiling, debugging, or experimental
instruction counters:

```kotlin
var instructions = 0L
val maxInstructions = 1_000_000L

val limited = Instance.builder(module)
    .withUnsafeExecutionListener { _, _ ->
        instructions += 1
        if (instructions > maxInstructions) {
            throw WasmInterruptedException("instruction limit exceeded")
        }
    }
    .build()
```

This listener runs on the interpreter hot path for every instruction. Keep it
very small, expect a large performance cost, and do not treat it as a stable
public quota API. Runtime-compiled machines do not execute interpreter listener
callbacks.

On non-JVM targets there is no host `Thread.interrupt()` equivalent. Use
target-specific cancellation around the host call boundary, and prefer small,
bounded host functions for untrusted workloads.
