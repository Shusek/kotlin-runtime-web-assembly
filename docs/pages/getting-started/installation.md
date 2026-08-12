# Installation

When a public release is available, use Maven Central. For development builds,
add the public GitHub Pages Maven repository and pin the commit-derived version:

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

Use the BOM so all modules stay on the same version:

```kotlin
// build.gradle.kts
val runtimeVersion = "0.3.0-dev.<12-character-commit>"

dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
    implementation("uk.shusek.krwa:wasi")
    implementation("uk.shusek.krwa:component-model")
    implementation("uk.shusek.krwa:wasi-preview3")
}
```

`runtime` already depends on `wasm`; add `wasm` directly only when you need the
parser/model APIs without the runtime.

`component-model` is the lightweight runtime dependency and does not ship the
embedded `wasm-tools.wasm` executable. JVM applications that call WIT
normalization, component packaging/unbundling, or
`WasmPlugin.builderFromComponent(...)` must opt into tooling:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:component-model-tooling")
}
```

The `uk.shusek.krwa.component-model` Gradle plugin includes this tooling
artifact automatically. A host that loads a core module with
`WasmPlugin.builder(witPackage).withModule(module)` needs only
`component-model`.

## Kotlin Multiplatform

In Kotlin Multiplatform builds, put portable dependencies in
`commonMain.dependencies`:

```kotlin
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("uk.shusek.krwa:bom:$runtimeVersion"))
            implementation("uk.shusek.krwa:runtime")
        }
    }
}
```

The portable artifacts publish JVM, iOS ARM, and Kotlin/Wasm browser variants.
The iOS target set is ARM-only: `iosArm64` for devices and `iosSimulatorArm64`
for Apple Silicon simulators. The web target is Kotlin/Wasm browser
(`wasmJs { browser() }`), not classic Kotlin/JS.

## Runtime Engines

KRWA no longer ships a Kotlin fallback execution engine. JVM, Android, and iOS
execution require a Wasmtime-backed platform engine. On desktop JVM, the built-in
FFM bridge loads the Wasmtime C API from `krwa.wasmtime.library`,
`KRWA_WASMTIME_LIBRARY`, or common system library locations and requires JVM
native access. Android and iOS embedders should link and install their platform
Wasmtime provider before instantiating modules.

The wasmJs target does not need a backend selector: `Instance.builder(module)`
uses the browser or Node `WebAssembly` engine through `ExecutionBackend.AUTO`.
Use the wasmJs-only `WasmJsExecution` facade only when host code needs native
wrapper objects for globals, tables, tags, or shared imports.

## Local Checkout

For local changes that are not committed yet, keep this repository checked out
next to your application and use a Gradle composite build:

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

If you prefer local Maven artifacts, publish the checkout once and enable
`mavenLocal()` in the consuming build:

```shell
git clone https://github.com/Shusek/kotlin-runtime-web-assembly.git
cd kotlin-runtime-web-assembly
./gradlew publishToMavenLocal
```
