# Changelog

This project follows semantic versioning. The active compatibility line is fixed at `0.3.x`;
patch and prerelease identifiers advance without changing the `0.3` major/minor pair. Until
`1.0.0`, API changes may still be intentional; release candidates remain immutable once
published.

## 0.3.0-rc.5 (2026-07-25)

### Added

- The Component Model Gradle plugin can now declare multiple named components with isolated WIT
  generation and packaging tasks.
- Named components can select a Kotlin/Wasm target directly; KRWA resolves and normalizes its
  optimized production executable without exposing compiler output paths to build authors.

### Changed

- Generated binding output directories are cleared before regeneration so package-name changes
  cannot leave stale Kotlin sources behind.
- KRWA builds now require JDK 25 explicitly.

### Compatibility

- Existing single-component Gradle configuration remains available. Named components are an
  additive API used by higher-level build plugins such as the Suvio Plugin SDK.

## 0.3.0-rc.4 (2026-07-24)

### Changed

- Dependabot now scans the root Gradle build and its version catalog instead of treating the
  repository root as a Maven build.
- The Android validation sample now uses Android Gradle Plugin `9.3.1`, JUnit Jupiter API `6.1.2`,
  and kotlinx-io `0.9.1`.
- GitHub Actions workflows now use `actions/checkout@v7`, `actions/setup-python@v7`, and
  `actions/download-artifact@v8`.

### Compatibility

- The published KRWA runtime, component model, WASI, WebAssembly, annotations, and Gradle plugin
  APIs are unchanged from `0.3.0-rc.3`; this candidate refreshes build and validation tooling.

## 0.3.0-rc.3 (2026-07-22)

### Changed

- Wasmtime was updated from `46.0.1` to `47.0.2`. The update fixes asynchronously delivered
  future write-closed events in the Preview 3 future/stream execution paths used by KRWA.
- Wasmtime 47 also corrects call-hook accounting across yields and concurrent component calls.
  KRWA does not currently enable Wasmtime's `call-hook` Cargo feature or install store call hooks,
  so that correction does not change current KRWA behavior.
- The bundled WASI Preview 1 adapters and Android Wasmtime libraries for `arm64-v8a` and
  `armeabi-v7a` are rebuilt or refreshed from the pinned `v47.0.2` sources and release assets.

### Compatibility

- Wasmtime 47 enables WebAssembly GC and exception handling by default. KRWA already enables both
  proposals explicitly and does not use the removed `wasi-common` or `wasi-threads` crates, so
  the upgrade does not silently expand the accepted feature set or require a migration.

## 0.3.0-rc.2 (2026-07-20)

### Added

- Android Wasmtime/Pulley execution now ships native `armeabi-v7a` libraries alongside
  `arm64-v8a`, including Preview 3 support and ABI-aware device fixtures.
- Release validation now rejects versions outside the configured `0.3.x` compatibility line.

### Changed

- Android selects `pulley32` automatically for 32-bit processes and retains `pulley64` for
  64-bit processes.
- CI verifies the complete Android JNI library set for both supported ABIs.
- Release dependency preparation caches the Maven BOM descriptor required by offline standalone
  consumer verification.
- Maven Central releases are built from the verified staging repository, signed in an isolated
  runner keyring, and automatically published through the Publisher Portal API.

### Compatibility

- Kendive remains on the `0.3.x` release line; subsequent compatible releases advance only the
  patch or prerelease portion.
- Consumers should pin the complete `0.3.0-rc.2` version and must not depend on mutable snapshots.

## 0.3.0-rc.1

### Security

- WASI Preview 3 networking is denied by default and uses exact protocol, host, and port grants.
- Raw sockets are authorized independently from host-provided HTTP clients.
- The built-in Ktor HTTP client never follows redirects. Custom host HTTP clients must either
  reject redirects or re-authorize every outbound hop against the same endpoint policy.
- Wasm and WIT parsing now enforce configurable size, count, nesting, and allocation limits.
- Instance memory policies cover every imported and defined memory and cap aggregate growth.
- Preview 2 and Preview 3 hosts and WIT resources have explicit ownership and deterministic close
  semantics.
- Preview 3 now implements `AutoCloseable`, owns a cancellable child job even when configured with
  a caller-owned coroutine scope, and closes transports produced concurrently with shutdown.
- Preview 3 TCP bind allocation is coordinated across hosts, uses platform-correct address reuse,
  and preserves an accepted connection when competing readiness waits are cancelled.
- Component packaging uses bounded process output, isolated temporary files, and atomic results.
- Strict WIT-world import reachability replaces implicit module-wide host import discovery.

### Added

- Atomic execution and memory policies for untrusted modules.
- Exact typed WIT and core host-import identifiers.
- Concurrent, bounded WIT resource tables.
- A local `releaseGate` that verifies tests, ABI, Gradle plugins, disabled-test policy, immutable
  versions, and a task-owned Maven staging repository without publishing externally.
- Recursive, exact inventory and execution of the pinned WebAssembly core specification suite,
  including proposal subdirectories and fail-closed tracking for every exclusion.

### Changed

- Wasmtime configuration now defaults to the platform-resolved `auto` target: native on JVM and
  Pulley on iOS and Android. Explicit unsupported targets fail closed.
- Unrestricted networking and legacy import discovery remain available only as deprecated,
  explicitly unsafe migration APIs.
- Preview 2 and Preview 3 host ownership must be selected explicitly by embedders.
- Preview 3 hosts should be wrapped in `use`; closing a host cancels only its own child work and
  never cancels a coroutine scope supplied by the caller.
- Pulley export lookup now uses exact UTF-8 bytes on JVM, iOS, and Android, including empty,
  multibyte, and embedded-NUL names.
- Traps raised while a Pulley module start function runs are reported consistently as
  `UninstantiableException` on JVM, iOS, and Android.
- Maven staging and the BOM contain only curated consumer artifacts; benchmark, corpus, and
  testsuite-generator projects remain internal verification inputs.

### Compatibility

- This candidate is intended for the Suvio V4 plugin ABI and its exact capability model.
- Consumers should pin the complete `0.3.0-rc.1` version and must not depend on mutable snapshots.
