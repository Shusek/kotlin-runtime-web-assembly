# Changelog

This project follows semantic versioning. The active compatibility line is fixed at `0.3.x`;
patch and prerelease identifiers advance without changing the `0.3` major/minor pair. Until
`1.0.0`, API changes may still be intentional; release candidates remain immutable once
published.

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
