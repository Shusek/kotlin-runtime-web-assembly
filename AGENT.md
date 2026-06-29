# Working with this repository

Kotlin Runtime Web Assembly (KRWA) is a Kotlin-first WebAssembly runtime and
toolchain. The core `wasm` and `runtime` artifacts are Kotlin Multiplatform
modules for JVM, iOS ARM, and web/wasm browser builds; tooling, test generation,
and some integration layers remain JVM-specific.

## Prerequisites

- Java 25 for the normal Gradle build and CI configuration
- Gradle via `./gradlew`
- Node.js and npm for Kotlin/Wasm browser artifacts and wasm node-based tests
- Wasmtime linked for JVM/Android/iOS execution tests; wasmJs uses the host
  browser or Node WebAssembly engine

## Key build commands

```bash
# Full build with all tests
./gradlew --no-daemon test --continue

# Publish local artifacts for downstream composite checks
./gradlew --no-daemon publishToMavenLocal -x test

# Run one Gradle module's tests
./gradlew --no-daemon :runtime:test

# Build generated API documentation
./gradlew --no-daemon :dokkaGenerate
```

## Module dependency graph

The Gradle build wires module dependencies in the root build script. Use
qualified Gradle task paths for focused work and let Gradle bring in required
dependencies:

```
wasm-corpus (compiled Wasm fixtures and source corpus)
wasm (parser, writer, validation-facing types)
  <- runtime (platform execution, Instance, Store, Memory)
       <- wasi (WASI Preview 1)
       <- component-model (WIT and canonical ABI support)
            <- wasi-preview3 (Kotlin-first WASI 0.3 facade)
```

Other modules include `annotations`, `annotations:processor`,
`codegen`, `wasm-tools`, `wabt`, `test-gen-lib`, and `wasi-test-gen`.

## Building and testing a single module

Run the focused Gradle task for the module you changed. Publish to Maven local
only when an external consumer needs artifact coordinates:

```bash
# Runtime unit tests
./gradlew --no-daemon :runtime:test

# Parser/writer tests
./gradlew --no-daemon :wasm:test

# Browser wasm compile and node-backed wasm tests
./gradlew --no-daemon :wasm:compileKotlinWasmJs :runtime:wasmJsNodeTest

# WASI tests
./gradlew --no-daemon :wasi:jvmTest
```

The supported native/mobile/web target matrix is intentionally narrow:
`iosArm64` and `iosSimulatorArm64` for iOS, and Kotlin/Wasm browser via
`wasmJs { browser() }` for web. Do not add `iosX64`, classic `js()`, or
`wasmWasi` targets to the core artifacts unless the platform decision changes.
The root build also registers `wasmJs { nodejs() }` for test execution only.

## Focused integration tests

The old generated spec-test modules have been removed. Use the focused platform
suites for the surface you changed.

### Running a single test class

```bash
./gradlew --no-daemon :wasi:jvmTest --tests '*WasiPreview1Test'
```

## Test modules

| Module | What it tests |
|---|---|
| `runtime:jvmTest` | Core platform execution, imports, exports, memory, traps |
| `wasi:jvmTest` | WASI Preview 1 host behavior |
| `component-model:jvmTest` | WIT, canonical ABI, WASI Preview 2/3, plugin loading |

## Code style

- No wildcard imports (configure your IDE accordingly)
- Keep Kotlin formatting consistent with the surrounding file
- Approval tests: set `APPROVAL_TESTS_USE_REPORTER=AutoApproveReporter` to auto-approve golden samples

## Module architecture overview

### `wasm` module
- `WasmParser` / `Parser` - portable parser API and JVM facade
- `WasmWriter` - binary writer
- `types/` - Wasm types such as `ValType`, `FunctionType`, `SubType`,
  `RecType`, `CompType`, `StructType`, `ArrayType`, `FieldType`,
  `StorageType`, `PackedType`, `TypeSection`, and `OpCode`

### `runtime` module
- `Instance` - module instantiation, imports, exports, and runtime state
- `Store` - cross-module linking
- `ImportFunction` - imported function representation with type validation
- `ConstantEvaluators` - constant expression evaluation
- `WasmStruct`, `WasmArray`, `WasmI31Ref` - GC object types
- `internal/GcRefStore` - auto-keyed store for Wasm GC references

### `wasi` module
- `WasiPreview1` - WASI Preview 1 host function implementations
- `WasiOptions` - configuration for stdio, directories, env vars, and host
  services

## Performance considerations

- Types should NOT add computation at runtime. Subtyping checks and type lookups should be pre-computed or cached where feasible.
- The validator enriches instruction operands with type hints (e.g., source heap type for `ref.test`/`ref.cast`/`br_on_cast`) so runtime helper paths do not need to guess.

## Specification references

- Official WebAssembly spec: https://webassembly.github.io/spec/core/
- Validation algorithm appendix: https://webassembly.github.io/spec/core/appendix/algorithm.html
- GC proposal: https://github.com/WebAssembly/gc/blob/main/proposals/gc/MVP.md
