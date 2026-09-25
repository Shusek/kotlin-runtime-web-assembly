# Support and compatibility

KRWA `0.3.0` is a stable release for the platform and feature scope below. The
release uses Wasmtime **48.0.2**. Compatible fixes advance the `0.3.x` patch
version; public APIs may still evolve before `1.0.0`.

## Platforms

| Platform | Supported target | Execution backend |
| --- | --- | --- |
| JVM | Java 25 | Wasmtime with the matching native C API library |
| Android | `arm64-v8a`, `armeabi-v7a`; API 28+ | Packaged Wasmtime/Pulley provider |
| iOS | `iosArm64`, `iosSimulatorArm64` | Wasmtime/Pulley with precompiled modules and components |
| Browser | Kotlin `wasmJs` | The browser's WebAssembly engine |

Node is used to execute Kotlin/Wasm tests. Classic Kotlin/JS, `wasmWasi`, and
Intel iOS simulator targets are outside this release's target matrix.
Browser execution depends on the features and capabilities of the host engine;
it does not provide Wasmtime's native fuel metering. See
[execution limits](../execution/cpu-limits.md) and [security](security.md).

## Runtime and Component Model scope

The Wasmtime host bridge supports function imports and scalar `i32`, `i64`,
`f32`, and `f64` boundary values. Non-function imports, reference/SIMD values
crossing that boundary, and exception-tag bridging are not supported by the
current bridge. This restriction concerns the KRWA host boundary, not the full
instruction set implemented inside Wasmtime.

The release includes WASI Preview 1, Component Model tooling, and the WASI
Preview 3 integration described in their respective guides. Preview APIs and
upstream preview protocols retain their documented status.

Precompiled Wasmtime artifacts are tied to the engine version, target, and
configuration. Recompile modules and components when upgrading Wasmtime;
artifacts produced with an older release are not portable caches for 48.0.2.

## Verification and known gaps

The pinned core specification corpus contains 445 WAST files: 80 active, 155
excluded for parser/specification gaps, and 210 excluded for runtime gaps. The
active files generate 27,382 tests, of which 37 have tracked exclusions. The
release gate checks this inventory and rejects untracked disabled tests.
These results are not a claim of complete WebAssembly specification compliance.

Two official WASI Preview 3 interactive scenarios, the stdout/input handshake
and guest-hosted TCP echo server, remain outside the current in-process test
runner's coverage. Their execution requires concurrent host interaction.
The exact exclusions and their removal criteria are maintained in
[the test exclusion tracker](https://github.com/Shusek/kotlin-runtime-web-assembly/blob/main/docs/testing-exclusions.md).

Android native libraries are checked for ABI and 16 KB alignment. Stable release
acceptance also runs instrumentation tests on an ARM64 Android device or emulator;
compiling an instrumentation APK alone does not verify native execution.

On macOS, the host Preview 3 bridge build preserves symbols to avoid the Rust
1.96 stripping issue with the macOS 27 loader. This is applied by the build and
does not require a developer environment override.
