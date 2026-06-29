# Build-Time Compiler

The build-time compiler is a legacy JVM-only path that generated JVM classes
from known Wasm inputs before the application started. It is not selected by the
current runtime builder.

Use this path only for compatibility work where:

- module bytes are stable build inputs,
- startup time is more important than accepting arbitrary modules at runtime,
- generated classes can be packaged with the application,
- the host can rebuild when the Wasm module changes.

Build-time generated classes should be treated like application code. Generate
them from trusted build inputs, review the build step that produces them, and
rebuild whenever the Wasm module or compiler version changes.

Keep new code on the Wasmtime runtime path. Dynamically uploaded or
user-supplied modules should not use the legacy generated JVM class path.
