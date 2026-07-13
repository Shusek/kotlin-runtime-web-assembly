# Runtime Basics

Parse modules with the common `WasmParser` API:

```kotlin
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.WasmParser

fun instantiate(bytes: ByteArray): Instance =
    Instance.builder(WasmParser.parse(bytes)).build()
```

The JVM-only `Parser` facade also provides `InputStream`, `File`, and `Path`
entrypoints. Multiplatform consumers should use `WasmParser` with `ByteArray`
or Okio sources.

## Modules And Instances

A parsed Wasm module is inert code and metadata. An `Instance` is executable
runtime state: memory, globals, tables, imports, and exports are wired during
instantiation.

Build imports before constructing an instance when the guest expects host
functions, memories, tables, or globals. Call exported functions through the
runtime export APIs after instantiation.

`Instance` owns the selected platform engine and all native export handles.
Close it when the plugin session ends. `close()` is idempotent; calls through
the instance, an export function retained earlier, or an exported memory fail
after close instead of reaching a released native handle. An instance is not a
concurrent execution primitive: serialize calls per instance or give each
worker its own instance.

```kotlin
val instance = Instance.builder(module).build()
try {
    instance.export("run").apply()
} finally {
    instance.close()
}
```

## Guest Memory

Core Wasm exchanges structured data through linear memory. Hosts usually pass a
pointer and length, then decode bytes from the exported or imported memory.
Guest code should expose allocation and deallocation functions when the host
must write buffers into guest memory.

When the boundary is richer than scalars and byte buffers, prefer WIT and the
Component Model. The canonical ABI gives records, variants, lists, strings,
resources, futures, and streams a contract instead of turning every call into a
custom memory protocol.
