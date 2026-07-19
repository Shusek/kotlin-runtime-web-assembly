# Execution Modes

KRWA requires a platform execution engine. JVM, Android, and iOS use the
Wasmtime backend when it is linked; wasmJs uses the host browser or Node
WebAssembly engine.

JVM, Android, and iOS execution requires Wasmtime; wasmJs execution requires
the host WebAssembly engine.

On `wasmJs`, `Instance.builder(module)` uses `ExecutionBackend.AUTO` by default
and instantiates parsed modules with the native browser or Node WebAssembly
engine. The wasmJs target already runs in an environment with a WebAssembly
engine, so `AUTO` does not fall back to another backend. If native
instantiation fails, including when imports require host objects the native
engine cannot accept, the error is reported directly. A
`WebAssembly.RuntimeError` becomes `NativeWasmRuntimeException`.

```kotlin
val module = WasmParser.parse(bytes)
val instance =
    Instance.builder(module)
        .build()
val result = instance.export("add").apply(1, 2)
val memory = instance.exports().memory("memory")
```

The common builder path exposes function exports plus exported memories through
the regular `Instance` API. `WasmJsExecution.instantiate` remains available as a
wasmJs-specific native facade for exported globals, tables, and exception tags
where callers need the native wrapper objects.

`ExecutionBackend.PULLEY` selects the historical Wasmtime provider boundary
where it is linked. It does **not** by itself mean that Wasmtime must use the
Pulley compiler target. `WasmtimeExecutionConfig.target` defaults to
`WasmtimeAutomaticTarget` (`auto`), which selects native Wasmtime on desktop JVM
and Pulley on iOS and Android. Callers can instead require
`WasmtimePulleyTarget` (`pulley64`) or `WasmtimeNativeTarget` (`native`, normally
Cranelift); an explicitly unsupported target fails closed. A platform execution backend owns native
exports and native linear memories while still receiving KRWA `ImportValues` for
host callbacks. The JVM implementation loads the Wasmtime C API from
`krwa.wasmtime.library`, `KRWA_WASMTIME_LIBRARY`, or common system library
locations, and requires JVM native access. Selecting `PULLEY` before the
platform links a Wasmtime binding fails fast instead of falling through to
another engine, so runtime measurements cannot accidentally report the wrong
engine.

Use `ExecutionBackend.PULLEY.availability()` or `.isAvailable()` before exposing
Wasmtime as a user-selectable mode. The availability check reports the same
platform/linking requirements that explicit `PULLEY` execution would enforce.

Wasmtime execution can be configured per instance during instantiation. Prefer
`withExecutionPolicy`, because it applies the engine, target, memory, and fuel
settings as one value instead of allowing a partially updated builder.
`WasmtimeExecutionConfig` exposes Wasmtime store and engine limits for maximum
linear memory bytes, maximum Wasm stack bytes, table elements, instances,
tables, memories, and guest execution fuel. Optional count limits and `maxFuel`
use `WasmtimeUnlimitedResourceLimit` (`-1`) for unlimited:

```kotlin
val instance =
    Instance.builder(module)
        .withExecutionPolicy(
            WasmExecutionPolicy.Wasmtime(
                WasmtimeExecutionConfig(
                    maxMemoryBytes = 64L * 1024L * 1024L,
                    maxWasmStackBytes = 256L * 1024L,
                    maxTableElements = WasmtimeUnlimitedResourceLimit,
                    maxInstances = 1,
                    maxTables = 32,
                    maxMemories = 4,
                    maxFuel = 5_000_000,
                ),
            ),
        )
        .build()
```

Fuel is consumed only while Wasmtime executes guest Wasm instructions. Keeping an
instance alive while it waits for the host to call an export does not consume
fuel, and host work blocked outside Wasmtime is not metered by fuel.

Embedders that package a platform-specific Wasmtime binding can install a provider
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

On wasmJs, host imports are supplied with `NativeWasmImports`. Exported native
memories, globals, tables, and exception tags are available as
`NativeWasmMemory`, `NativeWasmGlobal`, `NativeWasmTable`, and `NativeWasmTag`;
`NativeWasmMemory` implements the runtime `Memory` API for read/write access
from Kotlin/Wasm code, including native shared-memory atomics when the browser or
Node environment enables them. `NativeWasmTag` uses the host `WebAssembly.Tag`
API for exception handling modules; host callbacks can throw an imported tag
with `NativeWasmTag.throwException(instance, ...)` and let native Wasm `catch`
blocks handle it. Reference values use the raw `Long` call surface: store
JavaScript values with `NativeWasmInstance.storeReference` before passing them as
`externref`, `anyref`, or `funcref`, and call `referenceValue` to recover a
returned reference handle. Function reference tables and globals are mapped to
the JS API's `anyfunc` descriptor. Existing `ImportFunction` values can be reused
with `NativeWasmImports.fromImportValues`, including imports backed by exports
from another `Instance`; shared memories, globals, tables, and tags should be
provided with the native wrapper types because they are owned by the host
WebAssembly engine.

Use `NativeWasmFeatures` before relying on host-dependent browser or Node
features:

```kotlin
if (
    NativeWasmFeatures.available() &&
        NativeWasmFeatures.supportsValueType(ValType.I64) &&
        (!needsThreads || NativeWasmFeatures.supportsSharedMemory())
) {
    val instance = Instance.builder(module).build()
}
```

`supportsExceptionTags`, `supportsValueType`, `supportsTableElement`, and
`supportsTag` expose host-dependent features such as exception handling and GC
reference descriptors. `v128` values are not bridged through the JavaScript call
surface; modules may still use SIMD internally when the host engine supports the
module, but JS-exported or imported functions should not expose `v128`.

## Choosing A Mode

The effective platform matrix is intentionally asymmetric:

| Platform | Raw Wasm | Pulley CWasm | Cranelift CWasm |
| --- | --- | --- | --- |
| JVM / Android with the Suvio provider | Pulley | Pulley | Cranelift |
| iOS | Pulley | Pulley | unavailable |
| wasmJs | host `WebAssembly` | unavailable | unavailable |

CWasm is a Wasmtime-specific serialized artifact and is therefore never used
on `wasmJs`. iOS and Android resolve `auto` to Pulley. iOS builds only the
Pulley target; an explicit `native`/Cranelift target must fail availability
checks there instead of silently selecting another mode.

- Use the default `ExecutionBackend.AUTO` for normal hosts. It requires a linked
  Wasmtime backend on JVM, Android, and iOS, and uses the host WebAssembly engine
  on `wasmJs`.
- Use explicit `ExecutionBackend.PULLEY` only when an embedder wants to require
  the Wasmtime provider boundary and fail if it is not linked.
- Do not expose a backend selector on `wasmJs`; `AUTO` already means the browser
  or Node WebAssembly engine. Use `WasmJsExecution` only for wasmJs-specific
  native wrappers. Wasmtime-only controls such as `maxFuel` are unavailable on
  this path.

For untrusted modules, pair platform execution with explicit
[CPU limits](cpu-limits.md), memory limits, and narrow host capabilities.

iOS artifacts include their statically linked Pulley provider and select it automatically; an
embedder-installed provider still takes precedence. The standalone sample's
`runIosShowcase` task runs the iOS simulator showcase for the portable parser,
runtime, host import, exported function, structured-control-flow,
cross-module `Store`, trap, linear-memory, WIT parsing, WASIp3 metadata, and
WASIp3 preopened-storage APIs through the same `src/kmpShowcaseMain`
`runKmpShowcase` runner used by JVM and wasmJs.
