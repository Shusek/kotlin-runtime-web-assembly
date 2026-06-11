# Migrating From Chicory

The runtime lineage comes from Chicory, but coordinates and packages moved:

```text
com.dylibso.chicory.runtime   -> uk.shusek.krwa.runtime
com.dylibso.chicory.compiler  -> uk.shusek.krwa.compiler
com.dylibso.chicory.wasm      -> uk.shusek.krwa.wasm
com.dylibso.chicory.wasi      -> uk.shusek.krwa.wasi
```

The artifact group is `uk.shusek.krwa`. Use the BOM when mixing modules:

```kotlin
dependencies {
    implementation(platform("uk.shusek.krwa:bom:$runtimeVersion"))
    implementation("uk.shusek.krwa:runtime")
}
```

Package names are intentionally project-local now. Do not rely on old Chicory
imports in new code.
