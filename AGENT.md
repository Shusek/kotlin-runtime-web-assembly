# Working with this repository

Kotlin Runtime Web Assembly (KRWA) is a Kotlin-first WebAssembly runtime and
toolchain. The core `wasm` and `runtime` artifacts are Kotlin Multiplatform
modules for JVM, iOS ARM, and web/wasm browser builds; compiler, tooling, test
generation, and some integration layers remain JVM-specific.

## Prerequisites

- Java 25 for the normal Gradle build and CI configuration
- Gradle via `./gradlew`
- Node.js and npm for Kotlin/Wasm browser artifacts and wasm node-based tests
- Node.js 22 and npm for the Docusaurus documentation site

## Key build commands

```bash
# Full build with all tests
./gradlew --no-daemon test --continue

# Publish local artifacts for docs snippets or downstream composite checks
./gradlew --no-daemon publishToMavenLocal -x test

# Run one Gradle module's tests
./gradlew --no-daemon :runtime:test

# Build and test the docs after publishing local artifacts
cd docs
npm ci
npm run build
npm test -- --runInBand
```

## Module dependency graph

The Gradle build wires module dependencies in the root build script. Use
qualified Gradle task paths for focused work and let Gradle bring in required
dependencies:

```
wasm-corpus (compiled Wasm fixtures and source corpus)
wasm (parser, writer, validation-facing types)
  -> runtime (interpreter, Instance, Store, Memory)
       -> wasi (WASI Preview 1)
       -> wasi-preview3 (Kotlin-first WASI 0.3 RC facade)
       -> component-model (WIT and canonical ABI support)
       -> compiler (JVM bytecode compiler)
       -> simd (SIMD-capable machine)
       -> log
```

Other modules include `annotations`, `annotations:processor`,
`build-time-compiler`, `codegen`, `dircache`, `wasm-tools`, `wabt`,
`test-gen-lib`, and `wasi-test-gen`.

## Building and testing a single module

Run the focused Gradle task for the module you changed. Publish to Maven local
only when an external consumer or docs snippet test needs artifact coordinates:

```bash
# Runtime unit tests
./gradlew --no-daemon :runtime:test

# Parser/writer tests
./gradlew --no-daemon :wasm:test

# Browser wasm compile and node-backed wasm tests
./gradlew --no-daemon :wasm:compileKotlinWasmJs :runtime:wasmJsNodeTest

# Compiler tests
./gradlew --no-daemon :compiler-tests:test
```

The supported native/mobile/web target matrix is intentionally narrow:
`iosArm64` and `iosSimulatorArm64` for iOS, and Kotlin/Wasm browser via
`wasmJs { browser() }` for web. Do not add `iosX64`, classic `js()`, or
`wasmWasi` targets to the core artifacts unless the platform decision changes.
The root build also registers `wasmJs { nodejs() }` for test execution only.

## Spec tests (runtime-tests)

The WebAssembly spec testsuite lives in `build/external-testsuites/wasm/`. WASI
tests live in `build/external-testsuites/wasi/`. CI checks out fixed upstream
refs before running the Gradle tests. Local runs need those directories present
when exercising generated spec tests.

### Adding a new spec test

1. Update the relevant generated-test configuration under
   `testing/*/src/test-gen`.
2. Run the affected Gradle test task.
3. Check generated approval output when the change updates expected behavior.

Individual spec cases can be excluded in the same generated-test configuration
when an upstream test targets unsupported behavior.

### Running spec tests

```bash
# Interpreter spec tests
./gradlew --no-daemon :runtime-tests:test

# Compiler spec tests
./gradlew --no-daemon :compiler-tests:test

# WASI tests
./gradlew --no-daemon :wasi-tests:test
```

### Running a single test class

```bash
./gradlew --no-daemon :runtime-tests:test --tests '*SpecV1GcStructTest'
```

## Test modules

| Module | What it tests |
|---|---|
| `runtime-tests` | Interpreter against the WebAssembly spec testsuite |
| `compiler-tests` | JVM bytecode compiler against the spec testsuite |
| `machine-tests` | Shared tests for both interpreter and compiler |
| `wasi-tests` | WASI preview1 against the WASI testsuite |

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
- `InterpreterMachine` - opcode interpreter and execution loop
- `Store` - cross-module linking
- `ImportFunction` - imported function representation with type validation
- `ConstantEvaluators` - constant expression evaluation
- `WasmStruct`, `WasmArray`, `WasmI31Ref` - GC object types
- `internal/GcRefStore` - auto-keyed store for Wasm GC references

### `compiler` module
- `MachineFactoryCompiler` - entry point for the JVM bytecode compiler
- `internal/Compiler` - translates Wasm opcodes to JVM bytecode

### `wasi` module
- `WasiPreview1` - WASI Preview 1 host function implementations
- `WasiOptions` - configuration for stdio, directories, env vars, and host
  services

## Performance considerations

- Types should NOT add computation at runtime. Subtyping checks and type lookups should be pre-computed or cached where feasible.
- The hot path in the interpreter (`InterpreterMachine`) must remain fast — avoid per-opcode type section lookups when they can be resolved at validation time.
- The validator enriches instruction operands with type hints (e.g., source heap type for `ref.test`/`ref.cast`/`br_on_cast`) so the interpreter can dispatch without guessing.

## Specification references

- Official WebAssembly spec: https://webassembly.github.io/spec/core/
- Validation algorithm appendix: https://webassembly.github.io/spec/core/appendix/algorithm.html
- GC proposal: https://github.com/WebAssembly/gc/blob/main/proposals/gc/MVP.md
