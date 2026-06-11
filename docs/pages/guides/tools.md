# Tools

The repository includes tools for local experiments and build integration:

- `tools/cli`: command-line entrypoint for running local workloads.
- `tools/compiler`: JVM runtime compiler support.
- `tools/build-time-compiler`: ahead-of-time JVM class generation.
- `tools/wabt`: WABT-backed helpers.
- `tools/wasm-tools`: `wasm-tools` integration running through the runtime.

The standalone sample is the fastest way to see the pieces together:

```shell
cd samples/sample
./gradlew runShowcase
```

The showcase covers core Wasm execution, WASI Preview 1, a Kotlin `wasmWasi`
guest, host-backed HTTP body fetch, streaming JSON, Ktor `HttpClient` wiring
through WASIp3, Component Model packaging, WASI Preview 2 host wiring, and
WASI Preview 3 host support.

## WAT And Validation

Use `wasm-tools` for validation and WAT-to-Wasm conversion through the runtime:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:wasm-tools")
}
```

```kotlin
import uk.shusek.krwa.tools.wasm.Validate
import uk.shusek.krwa.tools.wasm.WasmFeature
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasm.WasmParser

val wat = """
    (module
      (func (export "answer") (result i32)
        i32.const 42))
""".trimIndent()

Validate.builder()
    .withFeatures(WasmFeature.WASM2)
    .build()
    .validateModule(wat)

val module = WasmParser.parse(Wat2Wasm.parse(wat))
```

For SIMD modules, validate with `WasmFeature.SIMD` before running them with the
JVM-only SIMD interpreter machine. The normal parser entrypoints validate core
module structure by default, but external validation is useful when you want a
specific feature profile.

The `wabt` artifact also exposes WABT-backed WAT parsing helpers:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:wabt")
}
```

Prefer `wasm-tools` for feature-profile validation and Component Model-adjacent
tooling, and use `wabt` when you specifically need WABT behavior.
