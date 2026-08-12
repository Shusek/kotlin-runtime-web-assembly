# KRWA Component Model Tooling

`uk.shusek.krwa:component-model-tooling` is the optional JVM tooling companion
to the lightweight `component-model` runtime. It provides the service-loaded
executor used by WIT normalization, component packaging/unbundling, validation,
and `WasmPlugin.builderFromComponent(...)`.

The artifact intentionally owns the dependency on `wasm-tools` and its embedded
`wasm-tools.wasm` resource. Applications that already have a `WitPackage` and
load a core `WasmModule` should depend only on `component-model`.

```kotlin
dependencies {
    implementation(platform("uk.shusek.krwa:bom:<version>"))
    implementation("uk.shusek.krwa:component-model-tooling")
}
```

The `uk.shusek.krwa.component-model` Gradle plugin includes this artifact on its
worker classpath automatically.
