# Kotlin Runtime Web Assembly

Kotlin Runtime Web Assembly is a Kotlin-first WebAssembly runtime focused
on plugin execution without JNI, native runtimes, or JSON-only plugin
boundaries. The main Kotlin Multiplatform artifacts target JVM, iOS ARM, and
web/wasm browser builds. JVM variants are compiled for Java 25.

## Project Status

This project is experimental and under active development. Snapshot artifacts
are published for evaluation and integration work; production use [should wait](https://youtube.com/shorts/xODPOxVDzFE)
for reviewed releases and pinned versions. Public APIs in experimental modules
may change while the Kotlin Multiplatform and Component Model surfaces settle.
If you still decide to run it in production, who am I to judge? Pin versions
carefully and expect breaking changes.

## What It Provides

- a pure Kotlin WebAssembly parser and runtime for JVM, iOS ARM, and web/wasm,
- interpreter and compiler execution modes,
- WASI Preview 1 host support,
- Component Model tooling for WIT-based plugin contracts,
- Kotlin WIT bindings, including WASIp2 and WASIp3 RC contract generation,
- helper tooling for packaging Kotlin/Wasm guests as Component Model plugins.

## Using The Runtime

Every push to `main` publishes the current `0.3.0-SNAPSHOT` artifacts to GitHub
Pages Maven repository. Use this for development builds. Use a real release
version from Maven Central once a release is published.

### Gradle Snapshot

Add the public Kotlin Runtime Web Assembly Maven repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://shusek.github.io/kotlin-runtime-web-assembly/maven")
    }
}
```

Then declare the Kotlin Runtime Web Assembly modules:

```kotlin
// build.gradle.kts
val runtimeVersion = "0.3.0-SNAPSHOT"

dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
    implementation("uk.shusek.krwa:wasm")
    implementation("uk.shusek.krwa:wasi")
    implementation("uk.shusek.krwa:component-model")
    implementation("uk.shusek.krwa:wasi-preview3")
}
```

### Kotlin Multiplatform Runtime

Use `runtime` from a Kotlin Multiplatform consumer when you need the portable
interpreter on iOS or in a browser-hosted Kotlin/Wasm application. It depends on
`wasm`, which exposes the common parser API and Okio-based byte input.

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.0"
}

val runtimeVersion = "0.3.0-SNAPSHOT"

kotlin {
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
                implementation("uk.shusek.krwa:runtime")
                implementation("uk.shusek.krwa:wasi-preview3")
            }
        }
    }
}
```

The iOS target set is ARM-only: `iosArm64` for devices and
`iosSimulatorArm64` for Apple Silicon simulators. The web target is
Kotlin/Wasm browser (`wasmJs { browser() }`), not classic Kotlin/JS. This
repository also configures `wasmJs { nodejs() }` so Gradle can run wasm tests
locally, but published web usage is the browser wasm target.

Parse modules with the common `WasmParser` API:

```kotlin
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.WasmParser

fun instantiate(bytes: ByteArray): Instance =
    Instance.builder(WasmParser.parse(bytes)).build()
```

The JVM-only `Parser` facade still provides `InputStream`, `File`, and `Path`
entrypoints for the JVM artifacts. KMP consumers should use `WasmParser` with
`ByteArray` or `okio.Source`.

The `component-model`, `wasi`, and `wasi-preview3` artifacts also publish JVM,
iOS ARM, and web/wasm variants for their portable APIs. Browser wasm builds can
parse modules, instantiate the interpreter, and use host-provided imports. Some
default host integrations are intentionally unavailable in browser wasm:
filesystem access, raw sockets, default HTTP, and blocking delay must be
provided by the application when a WASI workload needs those capabilities.

### Gradle Composite Build

For local changes that are not committed yet, keep checked out next to
your application and let Gradle substitute the modules from source:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

includeBuild("../kotlin-runtime-web-assembly")
```

Then declare the KRWA modules in your application:

```kotlin
// build.gradle.kts
val runtimeVersion = "0.3.0-SNAPSHOT"

dependencies {
    implementation("uk.shusek.krwa:runtime:$runtimeVersion")
    implementation("uk.shusek.krwa:wasm:$runtimeVersion")
    implementation("uk.shusek.krwa:wasi:$runtimeVersion")
    implementation("uk.shusek.krwa:component-model:$runtimeVersion")
    implementation("uk.shusek.krwa:wasi-preview3:$runtimeVersion")
}
```

Adjust the `includeBuild` path to where this repository is checked out.

### Local Gradle Publish

If you want to consume an uncommitted checkout as normal Gradle dependencies,
publish it to your local repository:

```shell
git clone https://github.com/Shusek/kotlin-runtime-web-assembly.git
cd kotlin-runtime-web-assembly
./gradlew publishToMavenLocal
```

Then enable `mavenLocal()` in the consuming Gradle build and use the BOM to keep
module versions aligned:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

val runtimeVersion = "0.3.0-SNAPSHOT"

dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
    implementation("uk.shusek.krwa:wasm")
    implementation("uk.shusek.krwa:wasi")
    implementation("uk.shusek.krwa:component-model")
    implementation("uk.shusek.krwa:wasi-preview3")
}
```

For a public release, replace `0.3.0-SNAPSHOT` with an actual released version and
use `mavenCentral()` without the snapshot or `mavenLocal()` repositories.

### WASI Preview 3 Kotlin Facade

Use `wasi-preview3` when you want the Kotlin-first WASI 0.3 RC entrypoint. It
re-exports the Component Model runtime types and adds coroutine adapters for
typed `future<T>` and byte/typed `stream<T>` handles:

```kotlin
val runtime = KotlinWasiPreview3.builder()
    .withNetworking()
    .withSecureRandom(kotlin.random.Random.Default)
    .build()

val future = runtime.completed("ready")
val value = runtime.await(future)
val deferred = future.asDeferred(runtime.wasi, coroutineScope)
```

The underlying ABI still uses `WitFuture<T>` and `WitStream<T>` handles, so this
facade remains compatible with canonical `future-read`, `stream-read`, and
`async-lower` imports.

Generated WIT bindings can point at the first-party facade runtime package with
`withRuntimePackageName("uk.shusek.krwa.wasi.preview3")`.

The same facade includes a capability-based file API over preopened directories:

```kotlin
val runtime = KotlinWasiPreview3.builder()
    .withPreopenedDirectory("/", "data")
    .build()
val fs = runtime.fileSystem("/")
fs.writeText("out/result.txt", "done")
val bytes = fs.readBytes("out/result.txt")
```

## Kotlin/WASI Sample

The standalone sample builds a Kotlin `wasmWasi` guest and runs it through the JVM
host:

```shell
cd sample
./gradlew runShowcase
```

The showcase covers core Wasm execution, WASI Preview 1, WIT parsing, Kotlin
contract generation, WASIp3 RC contract generation, Component Model packaging, and
WASIp2 host wiring.

## Goals

- Make Kotlin/Wasm plugins practical on the JVM.
- Use WIT and the Component Model for plugin boundaries instead of ad-hoc JSON.
- Keep the host runtime portable and dependency-light.
- Support WASI Preview 2 as a first-class host surface and track WASI Preview 3 as the RC async Component Model surface matures.

## License

MIT. See [LICENSE](LICENSE).
