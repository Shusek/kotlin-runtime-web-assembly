# Compiler Cache

Runtime compilation can reuse generated JVM bytecode through the `Cache`
interface. The `dircache-experimental` artifact provides `DirectoryCache`, a
disk-backed implementation:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:compiler")
    implementation("uk.shusek.krwa:dircache-experimental")
}
```

```kotlin
import java.nio.file.Files
import java.nio.file.Path
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.experimental.dircache.DirectoryCache

val cacheDir = Files.createDirectories(Path.of(".krwa-cache"))

val machineFactory = MachineFactoryCompiler.builder(module)
    .withCache(DirectoryCache(cacheDir))
    .compile()
```

The cache key uses the parsed module digest when one is available. Cached
values are stored as generated class JAR bytes and loaded back into the JVM.

Compiled JVM output is executable host-side state. Treat cache directories as
trusted build or runtime artifacts:

- scope cache paths per application and version,
- do not share writable cache directories with untrusted users,
- clear caches after changing compiler versions or host ABI assumptions,
- protect cache permissions the same way you protect application classes.

`DirectoryCache` publishes entries atomically, but it does not provide eviction,
signing, or cross-version integrity policy. Put it on storage owned by the host
application, and prefer restrictive permissions such as owner-only access.

For dynamic plugin systems, decide whether compiled output is worth the
operational complexity. The interpreter avoids a persistent executable cache and
is easier to reason about for untrusted modules.
