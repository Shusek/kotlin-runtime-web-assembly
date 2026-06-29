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

For JVM, Android, and iOS, set Wasmtime store limits before instantiation:

```kotlin
configureWasmtimeExecution(
    module,
    WasmtimeExecutionConfig(maxMemoryBytes = 64L * 1024L * 1024L),
)
```

`withMemoryLimits(...)` is still available for targets that can apply a
host-provided cap to a module's first defined memory, such as the wasmJs native
path, and for host-created/imported memory objects. It is not a replacement for
Wasmtime store limits. Imported memories are supplied by the host, so the host
must apply any limits before passing them to the guest. If a module uses multiple
memories, validate the module shape before instantiation and reject layouts your
host is not prepared to provide.

Runtime memory objects are owned by the active platform engine. On JVM, Android,
and iOS this means the linked Wasmtime backend; on `wasmJs` this means the
browser or Node WebAssembly engine.
