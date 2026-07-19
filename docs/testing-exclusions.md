# Test exclusions

Generated WebAssembly specification tests may only be disabled through a reviewed entry in the
test-generation configuration. Every generated `@Disabled` annotation points to the durable
tracking item below; hand-written tests must use their own issue reference and explanation.

## KRWA-1 — WebAssembly specification generator exclusions

- Owner: Kendive maintainers.
- Scope: cases listed in `src/test-gen/wasm-spec/**` exclusion configuration and emitted by
  `generateWasmSpecTests`. `excluded-wasts.txt` and `excluded-tests.txt` track parser/specification
  gaps; `excluded-runtime-wasts.txt` and `excluded-runtime-tests.txt` separately track the active
  execution backend.
- Reason: the old `java21` profile belonged to the removed JVM `SimdInterpreterMachine`. Reusing
  that allowlist after the runtime switched to Wasmtime/Pulley incorrectly scheduled suites whose
  host boundary is not implemented. The replacement `wasmtime-jvm` profile is explicit: the
  current bridge accepts function imports and scalar `i32`, `i64`, `f32`, and `f64` boundary
  values. Non-function imports, reference/SIMD boundary values, and exception-tag bridging remain
  tracked runtime exclusions. Exact active-suite cases retain otherwise supported WAST files while
  excluding only non-function import chains, reference values at the host boundary, and Wasmtime's
  less-specific table-element trap text. Scalar floating-point suites remain active.
- Inventory: the pinned corpus must be classified exactly once as included, parser/spec excluded,
  or runtime excluded. The release inventory has separate, fixed budgets for all three WAST
  categories and both method-exclusion categories: 445 WAST files comprise 80 active, 155
  parser/spec excluded, and 210 runtime excluded files. The active files generate 27,382 tests,
  with 18 exact parser/spec and 19 exact runtime exclusions (37 generated `@Disabled` tests).
  Increasing any exclusion budget requires an explicit reviewed build-script change.
- Exit criteria: implement the missing behavior, remove the case from the exclusion configuration,
  regenerate the suite, and require it to pass in `releaseGate`.
- Review rule: every release candidate reviews the exclusion diff. New exclusions require a
  changelog entry or a dedicated external issue linked from this tracker.

## KRWA-2 — WASI Preview 3 interactive runner coverage

- Owner: Kendive maintainers.
- Scope: `cli-stdout-flush.wasm` and `sockets-echo.wasm` from the pinned WASI Preview 3
  testsuite.
- Reason: both protocols require bidirectional host interaction while the guest command is still
  running. The current in-process runner preloads input and observes output only after the command
  returns, so it cannot verify the required stdout-before-stdin handshake or connect to the
  guest-hosted TCP echo server.
- Exit criteria: add a concurrent runner that can observe guest output, provide input or a loopback
  TCP peer while the guest is running, and require both official components to pass in
  `releaseGate`.
- Review rule: every release candidate reviews this exclusion and removes it as soon as the
  concurrent runner is available.
