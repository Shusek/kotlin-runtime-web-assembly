# Linear Memory

Core WebAssembly memory is a contiguous byte buffer owned by the instance. The
guest sees offsets inside that buffer; the host must translate every pointer and
length before touching memory.

Common patterns:

- exported functions return scalar values directly,
- strings and byte arrays are represented as pointer plus length,
- the guest exports allocation and deallocation helpers,
- the host writes input data into guest memory, calls an exported function, then
  reads the result range back.

Validate bounds on both reads and writes. Never trust a pointer just because it
came from the guest. A valid pointer can still point to a value with the wrong
shape for the protocol you expected.

For structured plugin contracts, use WIT and the Component Model. The canonical
ABI still uses linear memory underneath, but the generated bindings own the
lowering and lifting rules.

## Limits And Factories

WebAssembly memory is measured in 64 KiB pages. `MemoryLimits` stores the
initial and maximum page counts, with the WebAssembly maximum capped at 65536
pages.

```kotlin
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.types.MemoryLimits

val instance = Instance.builder(module)
    .withMemoryLimits(MemoryLimits(initial = 1, maximum = 256))
    .build()
```

`withMemoryLimits(...)` overrides the first memory defined by the module during
instantiation. Imported memories are supplied by the host, so the host must
apply any limits before passing them to the guest. If a module uses multiple
memories, validate the module shape or use a custom `withMemoryFactory(...)`
policy that rejects limits your host is not prepared to provide.

The JVM default memory implementation is `ByteBufferMemory`. `ByteArrayMemory`
is also available on the JVM and can be useful for specific workloads:

```kotlin
import uk.shusek.krwa.runtime.ByteArrayMemory

val instance = Instance.builder(module)
    .withMemoryFactory { limits -> ByteArrayMemory(limits) }
    .build()
```

Select a custom JVM memory factory only after benchmarking the host and guest
you care about. Portable targets use the common `PortableMemory`
implementation.
