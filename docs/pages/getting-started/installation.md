# Installation

When a public release is available, use Maven Central. For snapshots, add the
public GitHub Pages Maven repository:

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
val runtimeVersion = "0.3.0-SNAPSHOT"

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

## Optional JVM SIMD

Use `simd` only when a JVM host needs to execute WebAssembly SIMD `v128`
instructions. It provides `SimdInterpreterMachine`, an interpreter machine
factory backed by the JDK incubating Vector API:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:simd")
}
```

```kotlin
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.simd.SimdInterpreterMachine

val instance = Instance.builder(module)
    .withMachineFactory(::SimdInterpreterMachine)
    .build()
```

This artifact is JVM-only and requires Java 25. It is not available for iOS or
Kotlin/Wasm browser builds. Because it uses `jdk.incubator.vector`, JVM
applications and tests should add that module at run time:

```kotlin
tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}
```

Keep parser validation enabled for SIMD modules. `Parser.parse(...)` and
`WasmParser.parse(...)` validate by default; do not use
`.withValidation(false)` with `SimdInterpreterMachine` unless you have already
validated the module elsewhere and accept engine-specific behavior for invalid
input.

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

In Kotlin Multiplatform projects, keep `simd` in `jvmMain` dependencies rather
than `commonMain`.

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
