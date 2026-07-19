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

## Instance Memory Policy

WebAssembly memory is measured in 64 KiB pages. `MemoryLimits` stores the
initial and maximum page counts, with the WebAssembly maximum capped at 65536
pages.

Use `WasmMemoryPolicy` when instantiating a module. The policy bounds every
imported and defined memory, limits the number of memories, and reserves no more
than the aggregate growth budget:

```kotlin
val memoryPolicy =
    WasmMemoryPolicy(
        maxBytesPerMemory = 64L * 1024L * 1024L,
        maxTotalBytes = 96L * 1024L * 1024L,
        maxMemories = 2,
    )

val instance =
    Instance.builder(module)
        .withMemoryPolicy(memoryPolicy)
        .build()
```

Both byte limits must be positive multiples of 64 KiB. At build time, imported
memories are rejected when their current or declared maximum size exceeds the
per-memory or aggregate budget. Defined memories receive effective maximums
that respect both budgets, so subsequent `memory.grow` operations cannot exceed
them. The same policy is applied by the selected platform engine; it is not
necessary to duplicate it with `WasmtimeExecutionConfig.maxMemoryBytes`.

`withMemoryLimits(...)` remains as a compatibility API for a module with at
most one defined memory. It does not account for imported memories or aggregate
growth and rejects modules with multiple defined memories. Prefer
`withMemoryPolicy(...)` for untrusted or multi-memory modules.

Runtime memory objects are owned by the active platform engine. On JVM, Android,
and iOS this means the linked Wasmtime backend; on `wasmJs` this means the
browser or Node WebAssembly engine.
