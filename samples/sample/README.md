# Kotlin Runtime Web Assembly Kotlin/WASI Sample

This is a standalone Gradle project. It uses Kotlin Multiplatform 2.4.0 to build
Kotlin Runtime Web Assembly showcase targets for JVM, `wasmWasi`, `wasmJs`, and
the iOS simulator.

Run:

```shell
./gradlew runShowcase
./gradlew runWasmJsShowcase
./gradlew runIosShowcase
```

The JVM showcase exercises the full Kotlin/WASI and Component Model path.
The wasmJs showcase exercises both the shared KMP runtime path and the
browser/Node native-engine path. The iOS showcase runs on `iosSimulatorArm64`
and demonstrates the portable runtime and Component Model
contract APIs.

The portable KMP slice lives in `src/kmpShowcaseMain` and is shared by the JVM,
wasmJs, and iOS targets. Its `runKmpShowcase` runner orchestrates
parser/runtime execution, exported functions, structured control flow, host
imports, linear memory, `Store`-based cross-module imports, trap propagation,
default `Instance.builder(...)` platform execution, WIT
parsing, WASIp3 metadata/contracts, Ktor `HttpClient` wiring through the
WASIp3 HTTP client builder, and WASIp3 preopened storage. Platform source sets
only provide entry points and storage roots plus the wasmJs suspend-only HTTP
capability flag. The `wasmWasi`
guest is kept separate because it is the WebAssembly workload being hosted by
the JVM showcase, not a host runtime target.

The wasmJs target runs under Node-backed Kotlin/Wasm JS. The shared KMP runtime
pass uses the default `Instance.builder(...)` platform execution, which selects
the native browser/Node WebAssembly engine on wasmJs and uses Wasmtime on JVM
and iOS. It demonstrates function exports, host imports, and exported memory
access through the browser/Node `WebAssembly` engine, plus the memory-backed
`wasi-preview3` facade filesystem.

The iOS target runs a Kotlin/Native showcase binary on the simulator and
demonstrates the same shared KMP runtime surface as JVM and wasmJs: Wasm parsing, instance
construction, exported function calls, structured control flow, host imports,
linear-memory load/store, `Store` imports, traps, WIT parsing, WASIp3 metadata
and async/future/stream contracts, and WASIp3 preopened storage backed by the
iOS simulator sandbox.

The sample uses `includeBuild("../..")`, so Gradle substitutes the
`uk.shusek.krwa:*:0.3.0-rc.1` dependencies from the repository checkout. It does
not require public Maven artifacts.

The WASI scenario is a Kotlin 2.4 `wasmWasi` app that runs three ways:

- directly as a WASI Preview 1 executable,
- packaged as a Component Model component with the bundled Preview 1 adapter,
- through a WASIp3-configured host bridge.

In all three modes, the host feeds a DummyJSON-shaped product payload over HTTP
and a products array over `stdin`. The guest parses it with `kotlinx.serialization`,
streams the array in chunks through `kotlinx.io`, aggregates it with coroutines,
writes text and JSON reports into a preopened sandboxed filesystem, reads the JSON
report back, and proves malformed input stays inside the guest contract.

The filesystem part is intentionally realistic: the same flow also exercises clocks,
random, stdio, env, preopen boundaries, descriptor capability narrowing, sync/stat
metadata, directory lifecycle, readdir, hard links, symlinks, and no-follow vs
`LOOKUP_SYMLINK_FOLLOW` behavior.

The showcase prints capability-oriented sections for:

- shared JVM, wasmJs, and iOS execution of core Wasm parsing, instantiation,
  exports, structured control flow, traps, memory, host imports, and
  `Store`-based cross-module imports,
- raw Preview 1, component Preview 1, and WASIp3 bridge parity for the Kotlin/WASI app,
- host-backed HTTP, streaming JSON parsing, coroutine aggregation, report write/readback,
  malformed JSON handling, and sandbox/capability behavior,
- WIT parsing and Component Model type contracts shared across JVM, wasmJs, and iOS,
- WASIp3 stable metadata and async/future/stream WIT contracts shared across
  JVM, wasmJs, and iOS,
- WASIp3 stable runtime imports for CLI args/env/cwd/stdio streams, clocks, random,
  HTTP client, filesystem preopens and byte streams, TCP/UDP sockets, and canonical
  `future`/`stream` intrinsics,
- Ktor `HttpClient(MockEngine)` passed to `WasiPreview3.builder().withHttpClient(...)`,
  with JVM and iOS driving the request from a Wasm guest and wasmJs reporting the
  same Ktor backend as suspend-only for synchronous guest waits,
- the first-party `wasi-preview3` facade with coroutine `await`/`Deferred`,
  Kotlin clock/random configuration, and byte-stream plus filesystem adapters,
- Component Model packaging/unbundling through `wasm-tools`,
- `WasmPlugin` canonical ABI calls,
- WASIp2 host wiring via `WasiPreview2`.
