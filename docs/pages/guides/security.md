# Security

WebAssembly isolates linear memory and control flow, but host imports still run
with host process privileges. Treat every import as part of the security model.

## Baseline

- expose only the host functions and WASI capabilities the guest needs,
- validate pointers, lengths, handles, enum values, and resource identifiers,
- enforce CPU or wall-clock limits outside the core runtime,
- use Wasmtime fuel for deterministic guest-instruction budgets where available,
- set memory limits appropriate for the workload,
- keep filesystem preopens narrow and capability-based.

Apply engine and resource settings atomically with `WasmExecutionPolicy`. For
example, `WasmExecutionPolicy.Wasmtime(config, memoryPolicy)` binds the target,
memory policy, stack limits, and fuel budget into one selection. On `wasmJs`,
use `HostWebAssembly`; Wasmtime and CWasm are not available there.

Network, filesystem, clock, and random capabilities should be configured
explicitly. Avoid "default everything" host setups for plugins from outside the
application trust boundary.

## What Wasm Does Not Provide

The sandbox does not automatically limit CPU time, host memory allocations,
Wasmtime compilation/instantiation work, filesystem access granted through WASI,
or side effects inside host functions. Those policies belong to the embedding
application.

For untrusted modules:

- run execution on a worker that can be cancelled or interrupted,
- use [CPU limits](../execution/cpu-limits.md) instead of relying on guest
  cooperation,
- cap guest execution with `WasmtimeExecutionConfig(maxFuel = ...)` on
  Wasmtime-backed targets,
- cap all imported and defined memories with `WasmMemoryPolicy` and keep host
  allocation protocols bounded.

## Precompiled Artifacts Are Native Code

`WasmtimeExecutionConfig.precompiledModuleBytes` and
`WasmtimePreview3ComponentConfig.precompiledComponentBytes` carry serialized
Wasmtime artifacts (`.cwasm`). Wasmtime deserializes them with an API it
documents as unsafe: the artifact is executable code (native machine code or
Pulley bytecode) plus trusted runtime metadata, and it is not validated the way
a `.wasm` module is. A crafted artifact can read and write arbitrary host
memory and run arbitrary code in the host process. Fuel, memory limits, the
network policy and filesystem preopens do not apply to it, because they are
enforced by the very code the artifact replaces.

Treat precompiled bytes exactly like a native library you link into the
application:

- produce them yourself, from a `.wasm` module or component you have
  validated, with a pinned Wasmtime toolchain
  (`iosWasmtimeCompileModuleToCwasm`, `androidWasmtimeCompileModuleToCwasm`,
  or `wasmtime compile` with the same version, target and settings as the
  host),
- store them where plugins cannot write, for example app-private storage keyed
  by the SHA-256 of the source `.wasm`,
- never load a `.cwasm` that ships inside a plugin bundle, that was downloaded
  from a location a plugin author controls, or that an untrusted party supplied,
- when in doubt, pass the `.wasm` bytes and let the runtime compile them.

Preview 3 components currently run only from precompiled bytes, so hosts that
load third-party components must compile them in a trusted step before
deployment. Core modules fall back to compiling the `.wasm` bytes when a
memory policy is applied or the module needs synthetic memory exports, so the
artifact may be ignored without an error; do not rely on it for sandboxing.

## Untrusted Parser Input

The default Wasm and WIT parsers have finite limits. For an application that
accepts untrusted input, set smaller limits that match the largest contract it
intends to support:

```kotlin
val wasmParser =
    WasmParser.builder()
        .withLimits(
            WasmParserLimits(
                maxModuleBytes = 8L * 1024L * 1024L,
                maxSectionBytes = 4 * 1024 * 1024,
                maxFunctionBytes = 512 * 1024,
                maxInstructionsPerFunction = 100_000,
            ),
        )
        .build()

val module = wasmParser.parseBytes(wasmBytes)

val wit =
    WitPackage.parse(
        witSource,
        WitParserLimits(
            maxSourceChars = 256 * 1024,
            maxTokens = 50_000,
            maxTypeNesting = 64,
            maxPackageNesting = 32,
            maxIncludeDepth = 32,
        ),
    )
```

`WasmParserLimits` also bounds section names, vectors, type/import/function
counts, function locals, and control depth. `WitParserLimits` additionally
bounds declarations, members, nested package blocks, and aggregate world
expansion. Limit violations throw `WasmParseLimitException` or
`WitParseLimitException`; reject the input rather than retrying it with
unbounded limits.

## Host Function Checklist

Before a host function touches a guest-provided pointer, length, handle, path,
or enum value, validate that it is in range and valid for the protocol. Keep
host functions small, avoid unbounded loops over guest-controlled input, and
make blocking I/O obey the same timeout policy as guest execution.

## Reflective Host Binding on the JVM

`WasmPlugin.Builder.withHost(...)` binds WIT imports to methods of a host object
by name, and record, variant, and tuple lowering reads the values a host returns
through accessors named after the WIT fields. WIT names can come from the plugin
when a component is loaded with `builderFromComponent`, so treat them as
untrusted: the runtime resolves only public instance members declared outside
`java.lang.Object`, never widens access to private, protected, or
package-private members, and rejects private constructors when it materializes
host types from guest data. Within those rules the whole public surface of a
bound host object is reachable under a matching WIT name, so pass a dedicated
adapter object that exposes exactly the imported functions, return dedicated
record types from host functions instead of application objects, and, when the
WIT comes from the component, verify it against the host contract before
binding.
