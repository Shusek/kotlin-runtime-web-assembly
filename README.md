# Kotlin Runtime Web Assembly

Kotlin Runtime Web Assembly is a Kotlin-first WebAssembly runtime for running
plugins without JNI, native runtimes, or JSON-only plugin boundaries. The main
Kotlin Multiplatform artifacts target JVM, iOS ARM, and Kotlin/Wasm browser
builds. JVM variants are compiled for Java 25.

## Project Status

This project is experimental and under active development. Every push to `main`
publishes `0.3.0-SNAPSHOT` artifacts for evaluation and integration work;
production use [should wait](https://youtube.com/shorts/xODPOxVDzFE) for
reviewed releases and pinned versions. Public APIs in experimental modules may
change while the Kotlin Multiplatform and Component Model surfaces settle. If
you still decide to run it in production, who am I to judge? Pin versions
carefully and expect breaking changes.

Special thanks to [dylibso/chicory](https://github.com/dylibso/chicory) for the
solid foundations this project builds on.

## Documentation

**Read the full documentation:**
[shusek.github.io/kotlin-runtime-web-assembly](https://shusek.github.io/kotlin-runtime-web-assembly/)

Start with:

- [Installation](docs/pages/getting-started/installation.md)
- [Runtime basics](docs/pages/getting-started/runtime-basics.md)
- [Execution modes](docs/pages/execution/modes.md)
- [CPU limits](docs/pages/execution/cpu-limits.md)
- [WASI Preview 1](docs/pages/wasi/preview1.md)
- [Component Model](docs/pages/components/index.md)

## Quick Start

Snapshots are published to the public GitHub Pages Maven repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://shusek.github.io/kotlin-runtime-web-assembly/maven")
    }
}
```

Use the BOM and add the runtime:

```kotlin
// build.gradle.kts
val runtimeVersion = "0.3.0-SNAPSHOT"

dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
}
```

Parse and instantiate a module:

```kotlin
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.WasmParser

fun instantiate(bytes: ByteArray): Instance =
    Instance.builder(WasmParser.parse(bytes)).build()
```

Add `wasi`, `component-model`, `wasi-preview3`, or JVM-only `simd` when the
host needs those surfaces. The [installation guide](docs/pages/getting-started/installation.md)
has the full module list and target-specific notes.

The JVM-only `Parser` facade still provides `InputStream`, `File`, and `Path`
entrypoints for JVM consumers. Multiplatform consumers should use `WasmParser`
with `ByteArray` or `okio.Source`.

## WASI And Components

Use `component-model` when plugin boundaries should be described with WIT and
lifted/lowered through the canonical ABI instead of ad-hoc JSON payloads. It
includes WIT parsing, Kotlin contract generation, `WasmPlugin`, WASI Preview 2,
and canonical Preview 3 runtime support. See
[modules/component-model/README.md](modules/component-model/README.md) for the
full surface.

Use `wasi-preview3` when you want the Kotlin-first WASI 0.3 facade:

```kotlin
val runtime = KotlinWasiPreview3.builder()
    .withNetworking()
    .withResourceBudget(
        parallelism = 2,
        streamBufferCapacity = 64 * 1024,
        maxPendingFutures = 4_096,
    )
    .build()

val future = runtime.completed("ready")
val value = runtime.await(future)
runtime.close()
```

The facade keeps canonical `WitFuture<T>` and `WitStream<T>` handles at the
boundary, while adding coroutine adapters, typed stream helpers, Kotlin clock
and random configuration, and a capability-based file API over preopened
directories. See [modules/wasi-preview3/README.md](modules/wasi-preview3/README.md).

Browser wasm builds can parse modules, instantiate the interpreter, or use
`WasmJsExecution.instantiate` to prefer the browser or Node WebAssembly engine
and fall back to the interpreter when native execution is unavailable or the
provided imports require interpreter-owned objects. Its common facade exposes
function exports, exported memories, globals, tables, and exception tags across
the selected backend. Tables expose shared metadata such as size, element type,
and limits; use the concrete backend object for raw table entries. Advanced
callers can use `NativeWasmInstance` directly. Native runtime traps during
instantiation are not masked by `AUTO`; they are reported as
`NativeWasmRuntimeException`. Native execution accepts modules parsed from
complete bytes, uses `NativeWasmImports` for host functions, memories, globals,
tables, and exception tags, and exposes exported linear memories through
`NativeWasmMemory` with native shared memory atomics when the host enables
`SharedArrayBuffer`.
`NativeWasmTag` bridges `WebAssembly.Tag` for exception-handling modules when
the host engine exposes that JS API, including host callbacks throwing imported
tags with `NativeWasmTag.throwException`. Reference values such as `externref`,
`anyref`, and `funcref` are represented as stable `Long` handles; use
`NativeWasmInstance.storeReference` and `referenceValue` to bridge those handles
to real JavaScript values. Function reference tables and globals use the
host-compatible `anyfunc` descriptor under the hood. `NativeWasmFeatures`
reports host support for the native engine, shared memories, exception tags,
value types, and table element types before an application selects that fast
path. `NativeWasmImports.fromImportValues` can reuse existing `ImportFunction`
handles, including functions exported by an interpreter `Instance`, while
memory/global/table/tag imports still need the native wrapper types when they
must be shared with the browser engine. Browser filesystem access,
raw TCP/UDP sockets, and blocking delay must be supplied by the application when
a WASI workload needs those capabilities. Node-backed wasmJs environments also
have suspend TCP connect and listen paths; wasmJs UDP remains unavailable.

## Resource Budgets

The runtime does not own a global CPU quota. Hosts that run untrusted code should
enforce execution time at the Wasm call or resume boundary, for example by
running JVM execution on an interruptible worker thread and cancelling it on
timeout. Coroutine cancellation alone does not preempt CPU-bound Wasm execution.

When using WASI Preview 3, also budget the async host side. A supplied
`CoroutineDispatcher` or `CoroutineScope` controls where P3 host tasks and
coroutine-scheduled P3 resumes run; a dispatcher with parallelism greater than
one can consume multiple CPU cores. If billing or limits assume one core, use a
bounded resource policy such as `withResourceBudget(parallelism = 1)`.
`withResourceBudget(...)` is a resource limit, not a CPU meter. Real CPU
accounting requires OS/process isolation, cgroup accounting, or a dedicated JVM
worker pool measured with platform thread CPU counters.
See [CPU limits](docs/pages/execution/cpu-limits.md) and
[wasi-preview3](modules/wasi-preview3/README.md).

## Sample

The standalone sample builds a Kotlin `wasmWasi` guest and runs it through the
JVM host:

```shell
cd samples/sample
./gradlew runShowcase
./gradlew runWasmJsShowcase
./gradlew runIosShowcase
```

The showcase covers core Wasm execution plus a Kotlin 2.4 `wasmWasi` app that
runs as raw WASI Preview 1, as a component with the bundled Preview 1 adapter,
and through a WASIp3-configured host bridge. The WASI path exercises host HTTP,
`kotlinx.serialization`, chunked `stdin` streaming, coroutine aggregation,
sandboxed filesystem reports, capability-safe file semantics, controlled malformed
JSON handling, WIT generation, Component Model packaging, WASIp3 runtime services,
Ktor `HttpClient` wiring through WASIp3, and WASIp2 host wiring. The wasmJs
showcase runs a web-targeted subset through
`Instance.builder(...).withExecutionBackend(ExecutionBackend.AUTO)`, proving the
same common API can select the host browser or Node WebAssembly engine on
wasmJs, the Chasm-backed interpreter on supported JVM modules, or the portable
interpreter fallback on JVM and iOS. The iOS simulator showcase demonstrates the
same portable parser/interpreter API surface for instance
construction, function exports, structured control flow, host imports,
`Store`-based cross-module imports, traps, linear memory, WIT parsing, WASIp3
metadata/contracts, and WASIp3 preopened storage from Kotlin/Native. See
[samples/sample/README.md](samples/sample/README.md) for the detailed coverage
list.

The shared KMP portion of the sample lives in `src/kmpShowcaseMain` and is used
by JVM, wasmJs, and iOS. Its common runner owns the shared execution flow, while
platform source sets only add entry points and storage-root configuration.

## Local Development

For local changes that are not committed yet, keep this repository checked out
next to your application and use a Gradle composite build:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

includeBuild("../kotlin-runtime-web-assembly")
```

Then declare the same module dependencies in your application. Adjust the
`includeBuild` path to where this repository is checked out.

If you prefer normal local Maven dependencies, publish the checkout once and
enable `mavenLocal()` in the consuming build:

```shell
git clone https://github.com/Shusek/kotlin-runtime-web-assembly.git
cd kotlin-runtime-web-assembly
./gradlew publishToMavenLocal
```

## Goals

- Make Kotlin/Wasm plugins practical across supported targets.
- Use WIT and the Component Model for plugin boundaries instead of ad-hoc JSON.
- Keep the host runtime portable and dependency-light.
- Support WASI Preview 2 as a first-class host surface and WASI Preview 3 as
  the stable async Component Model surface.

## License

MIT. See [LICENSE](LICENSE).
