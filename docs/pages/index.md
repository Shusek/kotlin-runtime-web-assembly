# Kotlin Runtime Web Assembly

Kotlin Runtime Web Assembly is a Kotlin-first WebAssembly runtime and component
toolchain for running plugins without JSON-only plugin boundaries.

The main artifacts are Kotlin Multiplatform libraries. Portable modules target
JVM, Android, iOS ARM, and Kotlin/Wasm browser builds. JVM, Android, and iOS
execution require a linked Wasmtime backend; wasmJs uses the host browser or
Node WebAssembly engine. JVM artifacts are compiled for Java 25.

## Project Status

KRWA `0.3.1` is a stable release available from Maven Central. The supported
platforms and feature boundaries are defined in the
[support matrix](guides/support.md). Published versions are immutable;
compatible fixes use the `0.3.x` patch line. Public APIs may evolve before `1.0.0`.

Development builds remain available as immutable `0.3.0-dev.<12-character-commit>`
artifacts for evaluation. Use a reviewed release for deployed applications.

Special thanks to [dylibso/chicory](https://github.com/dylibso/chicory) for
the solid foundations this project builds on.

## What To Use

- `runtime`: portable WebAssembly runtime APIs backed by platform execution.
- `wasm`: common parser model and Okio-based byte input.
- `wasi`: WASI Preview 1 host support.
- `component-model`: WIT parsing, Kotlin WIT bindings, canonical ABI, WASI
  Preview 2/3 host wiring, and the lightweight component runtime.
- `component-model-tooling`: optional JVM WIT normalization and Component Model
  packaging/unbundling backed by the embedded `wasm-tools` executable.
- `wasi-preview3`: Kotlin-first WASI Preview 3 facade with coroutine-friendly
  futures, streams, clocks, random, networking, and preopened filesystem APIs.
- `tools/cli`: command-line entrypoint for local experiments.

## Start Here

Add the development Maven repository, then depend on the modules you need:

```kotlin
val runtimeVersion = "0.3.0-dev.<12-character-commit>"

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
- [CPU limits](execution/cpu-limits.md): timeouts, Wasmtime fuel/resource limits,
  and host-side accounting.
- [Tools](guides/tools.md): WAT parsing, validation, and local integration
  helpers.
- [Logging](guides/logging.md): the lightweight logger facade and JVM backend.

Generated API documentation is published separately under [API Reference](api/).
