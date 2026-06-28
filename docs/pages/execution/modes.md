# Execution Modes

The default interpreter is the most portable execution path. It works across
the supported Kotlin targets and is the right baseline for untrusted modules.

On the JVM, runtime compilation can compile Wasm to bytecode when a module is
instantiated. It can improve hot execution performance, but it needs bytecode
generation and dynamic class loading.

The build-time compiler generates JVM classes ahead of time. Use it when the
Wasm modules are known during the build and startup cost matters.

The optional `simd` artifact provides a JVM-only interpreter machine for
WebAssembly SIMD `v128` instructions. Use it when guest modules contain SIMD
opcodes and you want the interpreter to handle them through the JDK incubating
Vector API. It requires Java 25 and the `jdk.incubator.vector` module at run
time, so it is not available on iOS or Kotlin/Wasm browser targets.

```kotlin
import uk.shusek.krwa.simd.SimdInterpreterMachine

val instance = Instance.builder(module)
    .withMachineFactory(::SimdInterpreterMachine)
    .build()
```

Keep parser validation enabled when using this machine. The default
`Parser.parse(...)` and `WasmParser.parse(...)` entrypoints validate modules;
combining `SimdInterpreterMachine` with `.withValidation(false)` is intended
only for modules that were validated by another trusted tool first.

On `wasmJs`, `Instance.builder(module).withExecutionBackend(ExecutionBackend.AUTO)`
can instantiate parsed modules with the native browser or Node WebAssembly
engine and fall back to the interpreter when native execution is unavailable or
the provided imports require interpreter-owned objects. This keeps the common
KMP entry point intact while still using the fast path for browser workloads
where the module uses features supported by the host engine. `AUTO` does not
hide native runtime traps during instantiation; a `WebAssembly.RuntimeError`
becomes `NativeWasmRuntimeException` instead of retrying through the
interpreter.

```kotlin
val module = WasmParser.parse(bytes)
val instance =
    Instance.builder(module)
        .withExecutionBackend(ExecutionBackend.AUTO)
        .build()
val result = instance.export("add").apply(1, 2)
val memory = instance.exports().memory("memory")
```

The common builder path exposes function exports and exported memories through
the regular `Instance` API. `WasmJsExecution.instantiate` remains available as a
wasmJs-specific selector facade for exported globals, tables, and exception tags
where callers need backend-specific wrappers such as `nativeOrNull()` or
`interpreterOrNull()`.

`ExecutionBackend.PULLEY` selects the Wasmtime Pulley integration where it is
linked. Pulley is Wasmtime's portable Rust interpreter, so it belongs in the
platform execution path rather than in `withMachineFactory`: a platform execution
backend can own native exports and native linear memories while still receiving
KRWA `ImportValues` for host callbacks. The JVM implementation loads the
Wasmtime C API from `krwa.wasmtime.library`, `KRWA_WASMTIME_LIBRARY`, or common
system library locations, and requires JVM native access. Android, iOS, and
wasmJs currently fail fast until those platforms provide their own native
bindings. Selecting `PULLEY` before the platform links a Wasmtime binding fails
fast instead of falling back to the Kotlin interpreter, so runtime measurements
cannot accidentally report the wrong engine.

Use `ExecutionBackend.PULLEY.availability()` or `.isAvailable()` before exposing
Pulley as a user-selectable mode. The availability check reports the same
platform/linking requirements that explicit `PULLEY` execution would enforce.

Wasmtime-backed Pulley execution can be configured per module before
instantiation. `WasmtimeExecutionConfig` exposes Wasmtime store and engine
limits for maximum linear memory bytes, maximum Wasm stack bytes, table
elements, instances, tables, and memories. Optional count limits use
`WasmtimeUnlimitedResourceLimit` (`-1`) for unlimited:

```kotlin
configureWasmtimeExecution(
    module,
    WasmtimeExecutionConfig(
        maxMemoryBytes = 64L * 1024L * 1024L,
        maxWasmStackBytes = 256L * 1024L,
        maxTableElements = WasmtimeUnlimitedResourceLimit,
        maxInstances = 1,
        maxTables = 32,
        maxMemories = 4,
    ),
)
```

Embedders that package a platform-specific Pulley binding can install a provider
without changing call sites that already select `ExecutionBackend.PULLEY`. This
is the intended integration point for Android JNI and iOS cinterop bindings:

```kotlin
PulleyExecutionProviders.install(androidPulleyProvider)

val instance =
    Instance.builder(module)
        .withExecutionBackend(ExecutionBackend.PULLEY)
        .build()
```

On the JVM, a `PulleyExecutionProvider` can also be discovered through
`ServiceLoader`. A manually installed provider takes precedence over
ServiceLoader and over the built-in desktop Wasmtime FFM binding. KRWA checks
the provider's availability before instantiation and rejects providers that do
not return a `PlatformInstanceExecution` with `ExecutionBackend.PULLEY`, so a
broken Android or iOS native binding fails at the backend boundary instead of
silently falling through to another engine.

Host imports are supplied with `NativeWasmImports`. Exported native memories,
globals, tables, and exception tags are available as `NativeWasmMemory`,
`NativeWasmGlobal`, `NativeWasmTable`, and `NativeWasmTag`; `NativeWasmMemory`
implements the runtime `Memory` API for read/write access from Kotlin/Wasm code,
including native shared-memory atomics when the browser or Node environment
enables them. `NativeWasmTag` uses the host `WebAssembly.Tag` API for exception
handling modules; host callbacks can throw an imported tag with
`NativeWasmTag.throwException(instance, ...)` and let native Wasm `catch` blocks
handle it. Reference values use the same raw `Long` call surface as the
interpreter: store JavaScript values with `NativeWasmInstance.storeReference`
before passing them as `externref`, `anyref`, or `funcref`, and call
`referenceValue` to recover a returned reference handle. Function reference
tables and globals are mapped to the JS API's `anyfunc` descriptor.
Existing `ImportFunction` values can be reused with
`NativeWasmImports.fromImportValues`, including imports backed by exports from
an interpreter `Instance`; shared memories, globals, tables, and tags should be
provided with the native wrapper types because they are owned by the host
WebAssembly engine.

Use `NativeWasmFeatures` before selecting this path in browser code:

```kotlin
if (
    NativeWasmFeatures.available() &&
        NativeWasmFeatures.supportsValueType(ValType.I64) &&
        (!needsThreads || NativeWasmFeatures.supportsSharedMemory())
) {
    Instance.builder(module)
        .withExecutionBackend(ExecutionBackend.NATIVE)
        .build()
}
```

`supportsExceptionTags`, `supportsValueType`, `supportsTableElement`, and
`supportsTag` expose host-dependent features such as exception handling and GC
reference descriptors. `v128` values are not bridged through the JavaScript call
surface; modules may still use SIMD internally when the host engine supports the
module, but JS-exported or imported functions should not expose `v128`.

## Choosing A Mode

- Use the interpreter for portability, simpler deployment, and untrusted code.
- Use runtime compilation for JVM workloads where the same module runs hot and
  startup overhead is acceptable.
- Use build-time compilation when modules are fixed inputs and generated JVM
  classes fit the deployment model.
- Use the SIMD interpreter machine only for JVM modules that actually require
  WebAssembly SIMD support.
- Use Wasmtime Pulley only after the embedding platform links the native binding
  and needs a Rust interpreter with Wasmtime's proposal coverage.
- Use native WebAssembly execution on `wasmJs` when browser or Node engine
  throughput matters and the module stays within the host engine's supported
  Wasm feature set.

For untrusted modules, pair the selected mode with explicit
[CPU limits](cpu-limits.md), memory limits, and narrow host capabilities.

iOS users should assume interpreter-first execution unless a module surface
explicitly documents another supported path. The standalone sample's
`runIosShowcase` task runs the iOS simulator showcase for the portable parser,
interpreter, host import, exported function, structured-control-flow,
cross-module `Store`, trap, linear-memory, WIT parsing, WASIp3 metadata, and
WASIp3 preopened-storage APIs through the same `src/kmpShowcaseMain`
`runKmpShowcase` runner used by JVM and wasmJs.
