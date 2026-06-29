# Runtime Compiler

The old JVM runtime compiler generated JVM bytecode for the Kotlin machine
execution path. Current runtime execution no longer selects that path; it is
retained only for legacy compatibility work while platform execution is
required.

Normal `Instance.builder(module).build()` now selects the platform backend:
Wasmtime on JVM, Android, and iOS when linked, and the browser or Node
WebAssembly engine on wasmJs. Builder options that require a custom Kotlin
machine, such as `withMachineFactory(...)`, fail fast instead of falling back to
legacy execution.

For current JVM hosts, use Wasmtime execution and configure store limits through
`WasmtimeExecutionConfig`.
