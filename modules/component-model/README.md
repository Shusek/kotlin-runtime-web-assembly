# Kotlin Runtime Web Assembly Component Model

This module adds Kotlin-first WASI Preview 2 and Preview 3 entry points for plugin
contracts.

## WASI Support Status

- WASI Preview 2 is the stable host surface. `WasiPreview2` wires CLI,
  clocks, random, IO streams, filesystem preopens/types, sockets, and HTTP
  imports through the canonical ABI.
- WASI Preview 3 is tracked at stable `0.3.0`. `WasiPreview3` exposes
  stable metadata, synchronous host imports for `wasi:cli/environment`
  and `exit`, `wasi:cli/stdin`, `stdout`, and `stderr` stream handoff,
  `wasi:clocks/system-clock`, `monotonic-clock`, and timezone,
  `wasi:random/random`, `insecure`, and `insecure-seed`, `wasi:filesystem`
  preopens and descriptor metadata/mutation imports, plus `wasi:http/service`
  inbound `handler.handle`, `wasi:http/middleware` downstream
  `handler.handle`, and `wasi:http/client.send` backed by Ktor when
  networking is explicitly enabled. `wasi:sockets`
  imports expose DNS lookup and TCP/UDP socket resources for
  create, bind, connect, listen/accept handles, address accessors, and socket
  options.
- Preview 3 async values lower/lift as typed `WitFuture<T>` and `WitStream<T>`
  canonical handles. Host futures are completion-backed and coroutine-friendly.
  Buffered host streams are capacity-bounded, expose coroutine-friendly
  readable/writable readiness, and return canonical blocked/completed statuses
  when writes hit backpressure. TCP receive/listen streams also participate in
  coroutine-readable readiness on Ktor-backed socket runtimes. Stream handoff covers stdio,
  filesystem, HTTP body/trailer handoff, TCP receive/send/listen canonical
  stream intrinsics, and typed host-side streams.
- Preview 3 canonical thread intrinsics are intentionally minimal on the
  required platform execution path. `thread.index` and `thread.yield` are
  available for stackless hosts; stackful thread creation, suspend, resume, and
  continuation-capture intrinsics are rejected because KRWA no longer ships an
  alternate execution engine or coroutine stack scheduler.

WASI 0.2 is Component Model based: plugin boundaries should be described with WIT and lifted/lowered through the canonical ABI instead of ad-hoc JSON payloads. Kotlin Runtime Web Assembly now provides:

- `Wit.normalize(...)`, `Wit.json(...)`, `Wit.encodePackage(...)`, and `Wit.parse(Path/byte[])` backed by the in-repo `wasm-tools` module running under Kotlin Runtime Web Assembly WASI Preview 1. Component parsing reads the full WIT dependency graph, not only the root world.
- `WitPackage.parse(...)`, a Kotlin Multiplatform model for WIT worlds,
  interfaces, functions, records, variants, enums, flags, resources, aliases,
  and common constructed WIT types.
- `KotlinWitBindings`, a Kotlin contract generator that emits interfaces, DTOs, world
  `Host`/`Guest` contracts, and WASIp3 `async func` declarations as `suspend fun`.
  Generated files use shared runtime types from `uk.shusek.krwa.component` by default, with
  an inline-runtime option for single-file experiments. WIT resources are emitted as
  Kotlin handle aliases backed by `WitResource<T>`, so plugin contracts can use
  domain names like `Blob` instead of spelling `WitResource<Blob>` at every boundary.
  WIT `future<T>` and `stream<T>` are emitted as typed handle contracts
  `WitFuture<T>` and `WitStream<T>`.
  Typed tuple contracts use Kotlin `Pair`/`Triple` plus `WitTupleN` helpers instead of
  falling back to JSON-like bags for common arities.
- Optional guest export adapters for Kotlin/Wasm modules. `--guest-exports`
  emits `@WasmExport` wrapper functions for exported WIT world functions,
  including Component Model async-callback exports for WIT `async func`
  contracts, so the component packager can discover core Wasm exports matching
  the WIT contract.
- `CanonicalAbi`, a synchronous WASI Preview 2 canonical ABI bridge for core-Wasm exports: it computes flattened core signatures and lifts/lowers WIT values through linear memory without JSON.
  Guest post-return cleanup exports named `cabi_post_<core-export-name>` are called
  after result lifting when present, which matches the cleanup pattern used by
  `wit-bindgen` for returned strings, lists, and larger aggregate results.
  WIT `flags` use the full canonical representation, including multiple `i32`
  words for flag sets larger than 32 entries.
  WIT `char` is represented in generated Kotlin contracts as an `Int` Unicode
  scalar value, so code points outside the JVM `Char` range remain representable.
  Synchronous lowered async host imports can return either a completed payload,
  which the canonical ABI wraps in a host future, or an existing `WitFuture<T>`
  handle for coroutine-backed work that completes later. Direct `[async-lower]`
  imports return the canonical completed status `2`; pending `[async-lower]`
  imports are exposed as canonical subtasks and can be driven through
  waitable-set polling or `waitable-set.wait` where the target has a coroutine
  wait bridge. Blocked canonical future and stream read/write calls are
  recorded as waitables too, so waitable sets can deliver `future-read`,
  `future-write`, `stream-read`, and `stream-write` progress events.
  Canonical thread imports are limited to stackless platform execution. JVM
  Ktor/CIO, iOS Ktor network, wasmJs Ktor HTTP, and wasmJs Node-backed TCP
  connect/listen remain suspend-driven. Preview 3 `wasi:http/client.send` uses that
  suspendable path and completes the returned response future from a coroutine
  for clients such as `KtorWasiHttpClient`; Preview 3 `tcp-socket.connect`
  does the same with `KtorSocketRuntime`, returning a pending future/subtask
  until the suspendable connect completes. Preview 3 `tcp-socket.listen` also
  uses the suspendable Ktor bind path when called through an async canonical
  import. Browser wasm still cannot provide raw TCP/UDP or a default filesystem;
  wasmJs UDP is unavailable because Ktor wasm-js does not implement UDP. The
  synchronous bridge only remains at canonical boundaries that still require a
  core Wasm call to return.
- `WasmPlugin`, a WIT-world loader for core Wasm plugin modules and component artifacts
  that wires host imports and exported functions through `CanonicalAbi`. Component
  artifacts are unbundled with `wasm-tools`; when adapters are present, Kotlin Runtime Web Assembly selects
  the core module whose exports match the selected WIT world. Generated Kotlin-style
  host objects can be passed with `withHost(...)`, and exports can be viewed as a
  generated interface proxy with `exports(...)`. Resource intrinsics generated by
  `wit-bindgen` style toolchains are linked automatically for `[resource-new]`,
  `[resource-rep]`, and `[resource-drop]` imports. Kotlin `wasmWasi` cores can be
  wired with `withWasiPreview1(...)` after packaging.
- `WasiPreview2`, a Kotlin-friendly host import layer for the stable WASIp2 surface
  most plugins need first: `wasi:cli/stdin`, `stdout`, `stderr`, `environment`,
  `exit`, optional `terminal-*` handles, `wasi:clocks/wall-clock`,
  `monotonic-clock`, `wasi:random/random`, `insecure`, `insecure-seed`,
  `wasi:io/streams`, `error`, `poll`, `wasi:filesystem/preopens` and
  `types`, `wasi:sockets` network, DNS, TCP, and UDP handles, plus
  `wasi:http/types` and `outgoing-handler` backed by Ktor. It installs
  the versioned WASI 0.2.11 and 0.2.12 module names and common aliases, and
  exposes Java/Kotlin streams, args, env vars, Kotlin clocks, random sources,
  preopened directories, socket/HTTP capabilities, and resource handles without JSON.
- `WitResourceTable<T>`, a small typed handle table for Kotlin hosts implementing WIT
  resources without leaking application objects or JSON through the plugin boundary.
- `WasmComponentTools`, helpers for `wasm-tools component embed`, `component new`, `component unbundle`, and component validation.
- `WasiPreview`, which marks Preview 2 as stable Component Model metadata and
  Preview 3 as stable Component Model metadata.

For Kotlin-first WASI Preview 3 usage, prefer the `uk.shusek.krwa:wasi-preview3`
facade artifact. It depends on this module, keeps `WitFuture<T>` and
`WitStream<T>` as the canonical ABI handles, and adds coroutine conveniences
such as `await`, `Deferred`, and byte/typed stream adapters.

Example:

```kotlin
val wit = WitPackage.parse("""
    package example:plugins;

    interface transform {
      record request {
        body: list<u8>,
      }

      run: func(request: request) -> result<string, string>;
    }

    world plugin {
      export transform;
    }
    """);

val kotlin = KotlinWitBindings.generate(wit, "example.plugins")
```

For a self-contained generated file, use
`KotlinWitBindings.builder(wit).withRuntimeTypes(true).build().generate()`.
Build scripts can write generated Kotlin contracts directly from a WIT file or
component artifact. Path-based component-model APIs use Okio `Path`:

```kotlin
KotlinWitBindings.write(
    "wit/plugin.wit".toPath(normalize = true),
    "example.plugins.generated",
    "build/generated/krwa/PluginBindings.kt".toPath(normalize = true),
)
```

For WASI-style packages, pass the whole `wit/` directory. Dependencies under
`wit/deps/...` are normalized through `wasm-tools component wit --out-dir`, so
Kotlin bindings keep the plugin package as the root contract while still
emitting referenced WASI interfaces. If multiple dependency packages export an
interface with the same local name, such as `wasi:http/types` and
`wasi:filesystem/types`, or worlds with the same local name, such as
WASI 0.3 `wasi:clocks/imports` and `wasi:random/imports`, generated Kotlin
names are disambiguated by their qualified WIT package names:

```kotlin
KotlinWitBindings.write(
    "wit".toPath(normalize = true),
    "example.plugins.generated",
    "build/generated/krwa/PluginBindings.kt".toPath(normalize = true),
)
```

To generate source-set friendly split files, write to a directory instead. KRWA
creates package directories below the output root and emits separate files for
WIT interfaces, worlds, runtime aliases, and guest export adapters:

```kotlin
KotlinWitBindings.writeDirectory(
    "wit".toPath(normalize = true),
    "example.plugins.generated",
    "build/generated/krwa".toPath(normalize = true),
)
```

The same path is available as a CLI entry point:

```shell
java -cp krwa-component-model.jar uk.shusek.krwa.component.KotlinWitBindgen \
  --package example.plugins.generated \
  --out build/generated/krwa/PluginBindings.kt \
  wit/plugin.wit
```

Use `--out-dir build/generated/krwa` for split-file output.

For a Kotlin/Wasm guest module that implements an exported world, add
`--guest-exports`. This emits top-level `@WasmExport` adapter functions and a
`KrwaGuestExports` installer:

```shell
java -cp krwa-component-model.jar uk.shusek.krwa.component.KotlinWitBindgen \
  --package example.plugins.generated \
  --guest-exports \
  --out build/generated/krwa/PluginBindings.kt \
  wit/plugin.wit
```

Install the guest implementation before exported functions are invoked. When
the module is loaded through `WasmPlugin`, export `krwa_guest_init`; KRWA calls
that initializer after instantiation and before binding component exports:

```kotlin
import kotlin.wasm.WasmExport

object MyGuest : Plugin.Guest {
    override val movies: Movies = MoviesImpl()
}

@WasmExport("krwa_guest_init")
fun krwaGuestInit() {
    KrwaGuestExports.installPlugin(MyGuest)
}
```

The generated adapters lower and lift canonical ABI values for scalars, strings,
lists, records, tuples, flags, enums, options/results, resources, futures, and
streams. WIT `async func` exports get synchronous `[async]...` fallback aliases
and async-callback ABI exports named `[async-lift]...` plus
`[callback][async-lift]...`; the async-callback path stores suspended coroutine
state in Component Model task-local context and returns results through
`task.return`. `WasmPlugin` drives yielded async-callback exports through their
callback export before lifting the returned payload, and repeats that callback
while the target has a coroutine wait bridge and the task keeps yielding.
Stackless callback exports that return `WAIT | (waitable-set << 4)` are resumed
with the next waitable-set event payload (`event`, `payload1`, `payload2`).
For async callback components, package with
`WasmComponentPackager --async-callback`.

### Generating host bindings from Gradle Kotlin Multiplatform

A Kotlin Multiplatform host can keep plugin contracts as WIT files and generate
JVM bindings during compilation. For example, place the host-visible plugin
contract in `src/jvmMain/wit/movies.wit`:

```wit
package kendive:movies@1.0.0;

interface movies {
  record movie-detail {
    id: u64,
    title: string,
  }

  get-movie-details: async func(id: u64) -> movie-detail;
}

world plugin {
  export movies;
}
```

The plugin owns the implementation and exports `movies`; the host only consumes
the generated guest proxy. `async func` is emitted as `suspend fun`, and suspend
export calls propagate coroutine cancellation through interruptible plugin
execution on the JVM.

```kotlin
val witBindgen by configurations.creating

dependencies {
    // Use the published artifact in an external host project.
    witBindgen("uk.shusek.krwa:component-model:<version>")

    // Or, inside this repository:
    // witBindgen(project(":component-model"))
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/wit-bindings/jvmMain/kotlin"))

            dependencies {
                implementation("uk.shusek.krwa:component-model:<version>")
                // Or, inside this repository:
                // implementation(project(":component-model"))
            }
        }
    }
}

val generateMoviePluginBindings by tasks.registering(JavaExec::class) {
    group = "code generation"
    description = "Generate Kotlin WIT bindings for movie plugins"

    val witFile = layout.projectDirectory.file("src/jvmMain/wit/movies.wit")
    val outputFile = layout.buildDirectory.file(
        "generated/wit-bindings/jvmMain/kotlin/com/example/plugins/movies/v1/MoviePluginBindings.kt"
    )

    inputs.file(witFile)
    outputs.file(outputFile)

    classpath = witBindgen
    mainClass.set("uk.shusek.krwa.component.KotlinWitBindgen")

    args(
        "--package", "com.example.plugins.movies.v1",
        "--plugin-helpers",
        "--out", outputFile.get().asFile.absolutePath,
        witFile.asFile.absolutePath,
    )
}

tasks.named("compileKotlinJvm") {
    dependsOn(generateMoviePluginBindings)
}
```

The generated API can then be used from host code:

```kotlin
val plugin = Plugin.build(builder, object : Plugin.Host {})
val details = Plugin.guest(plugin).movies.getMovieDetails(2u)
```

For incompatible contracts, generate separate packages such as
`com.example.plugins.movies.v1` and `com.example.plugins.movies.v2`, backed by
separate WIT packages like `kendive:movies@1.0.0` and `kendive:movies@2.0.0`.

Calling a core Wasm export through the WIT signature:

```kotlin
val abi = CanonicalAbi.of(wit)
val result = abi.call(instance, "export", transformFunction, request)
```

Providing a host import to a plugin:

```kotlin
val log = abi.hostFunction("host", "log", logFunction) { arguments ->
    println(arguments[0])
    null
}
```

Loading a plugin world:

```kotlin
val plugin = WasmPlugin.builder(wit)
    .withWorld("plugin")
    .withModule(wasmBytes)
    .withHostImport("host", "log") { arguments ->
        println(arguments[1])
        null
    }
    .build()

val result = plugin.call("transform.export", request)
```

Installing the built-in WASIp2 host imports:

```kotlin
val stdout = okio.Buffer()
val wasi = WasiPreview2.builder()
    .withStdout(stdout)
    .withArguments("plugin.wasm", "--scan")
    .withEnvironment("KRWA_MODE", "component")
    .withPreopenedDirectory("/", "plugins/data")
    .withTerminalStdout(true)
    .withNetworking()
    .withFixedWallClock(kotlin.time.Instant.parse("2026-06-08T00:00:00Z"))
    .withSecureRandom(SecureRandom())
    .build()

val plugin = WasmPlugin.builder(wit)
    .withWorld("plugin")
    .withModule(wasmBytes)
    .withWasiPreview2(wasi)
    .build()
```

Inline world interfaces are supported too:

```wit
world plugin {
  import host: interface {
    log: func(message: string);
  }

  export guest: interface {
    scan: func(path: string) -> result<string, string>;
  }
}
```

`KotlinWitBindings` emits `PluginHost`/`PluginGuest` contracts for those inline
interfaces, while `WasmPlugin` accepts canonical names such as `host.log` and
`guest.scan`.

Using generated Kotlin contracts:

```kotlin
val host = object : Plugin.Host {
    override val host = object : Host {
        override fun log(level: UByte, message: String) {
            println("[$level] $message")
        }
    }
}

val plugin = WasmPlugin.builder(wit)
    .withWorld("plugin")
    .withModule(wasmBytes)
    .withHost(host)
    .build()

val guest = plugin.exports(Plugin.Guest::class.java)
val result = guest.transform.export(Request(ubyteArrayOf(1u, 2u, 3u), "application/octet-stream"))
```

Implementing a resource-backed host in Kotlin:

```kotlin
class BlobStore {
    private val blobs = WitResourceTable<ByteArray>()

    fun blobConstructor(seed: UInt): WitResource<Db.BlobTag> {
        return blobs.insertResource<Db.BlobTag>(ByteArray(seed.toInt()))
    }

    fun blobRead(self: WitResource<Db.BlobTag>, offset: UInt): UByte {
        return blobs.get(self)[offset.toInt()].toUByte()
    }

    fun blobDrop(self: WitResource<Db.BlobTag>) {
        blobs.remove(self)
    }
}
```

For Kotlin/Wasm `wasmWasi` artifacts, the intended packaging flow is:

1. Compile Kotlin to a core Wasm module.
2. Use `WasmComponentTools.componentFromCore(...)` or `WasmComponentPackager`.
   If the core imports `wasi_snapshot_preview1`, KRWA automatically attaches the
   bundled Wasmtime WASI Preview 1 reactor adapter while creating the component.
3. Use `Wit.normalize(...)` or `Wit.json(...)` to inspect the component contract.
4. Use `WasmPlugin.builderFromComponent(...)` to load the component. If the component
   unbundles to multiple core modules, Kotlin Runtime Web Assembly picks the module matching the selected
   WIT world exports. Because the selected Kotlin core still imports
   `wasi_snapshot_preview1`, wire it with `withWasiPreview1(...)`.

The embed/new steps are also available as one validated CLI command:

```shell
java -cp krwa-component-model.jar uk.shusek.krwa.component.WasmComponentPackager \
  --wit wit/plugin.wit \
  --world plugin \
  --core build/wasm/plugin.wasm \
  --out build/wasm/plugin.component.wasm
```

Loading the packaged component:

```kotlin
val wasi = WasiPreview1.builder()
    .withOptions(wasiOptions)
    .build()

val plugin = WasmPlugin.builderFromComponent(componentPath)
    .withWasiPreview1(wasi)
    .build()
```

For guest modules that export WIT async functions through async-callback core
exports, pass `--async-callback`:

```shell
java -cp krwa-component-model.jar uk.shusek.krwa.component.WasmComponentPackager \
  --wit wit/plugin.wit \
  --world plugin \
  --core build/wasm/plugin.wasm \
  --out build/wasm/plugin.component.wasm \
  --async-callback
```

The packager forwards wasm-tools async callback embedding options and validates
the output with the async Component Model feature enabled.

The automatic adapter is not limited to `random_get`: it covers the normal
WASI Preview 1 surface that Kotlin `wasmWasi` can pull in, including
`poll_oneoff`, clocks, stdio, args/env, filesystem, process exit, and random.
The adapter makes the component contract valid, while `withWasiPreview1(...)`
provides the actual runtime imports for the unbundled Kotlin core module.

WASI 0.3/WASIp3 is exposed as stable Component Model metadata. WIT
parsing and Kotlin contract generation understand `async func` and bare
`result`/`future`/`stream` types without opt-in gates.
