# Kotlin Runtime Web Assembly

Kotlin Runtime Web Assembly is a Kotlin-first WebAssembly runtime for running
plugins without JNI, native runtimes, or JSON-only plugin boundaries.

The main artifacts are Kotlin Multiplatform libraries. Portable modules target
JVM, iOS ARM, and Kotlin/Wasm browser builds. JVM artifacts are compiled for
Java 25.

## Project Status

This project is experimental and under active development. Every push to
`main` publishes `0.3.0-SNAPSHOT` artifacts for evaluation and integration
work. Production use should wait for reviewed releases and pinned versions.
Public APIs in experimental modules may change while the Kotlin Multiplatform
and Component Model surfaces settle.

Special thanks to [dylibso/chicory](https://github.com/dylibso/chicory) for
the solid foundations this project builds on.

## What To Use

- `runtime`: portable WebAssembly runtime APIs backed by platform execution.
- `wasm`: common parser model and Okio-based byte input.
- `wasi`: WASI Preview 1 host support.
- `component-model`: WIT parsing, Kotlin WIT bindings, canonical ABI, WASI
  Preview 2/3 host wiring, and Component Model packaging helpers.
- `wasi-preview3`: Kotlin-first WASI Preview 3 facade with coroutine-friendly
  futures, streams, clocks, random, networking, and preopened filesystem APIs.
- `tools/cli`: command-line entrypoint for local experiments.

## Start Here

Add the snapshot Maven repository, then depend on the modules you need:

```kotlin
val runtimeVersion = "0.3.0-SNAPSHOT"

dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
    implementation("uk.shusek.krwa:wasi")
    implementation("uk.shusek.krwa:component-model")
    implementation("uk.shusek.krwa:wasi-preview3")
}
```

The [installation guide](getting-started/installation.md) shows the repository
configuration and Kotlin Multiplatform setup. The [runtime basics](getting-started/runtime-basics.md)
page covers parsing, instantiation, exports, and memory.

## Operational Topics

- [Security](guides/security.md): trust boundaries, host imports, and resource
  limits.
- [CPU limits](execution/cpu-limits.md): timeouts, Wasmtime resource limits,
  and host-side accounting.
- [Tools](guides/tools.md): WAT parsing, validation, and local integration
  helpers.
- [Logging](guides/logging.md): the lightweight logger facade and JVM backend.

Generated API documentation is published separately under [API Reference](api/).
