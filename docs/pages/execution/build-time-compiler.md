# Build-Time Compiler

The build-time compiler generates JVM classes from known Wasm inputs before the
application starts. This is useful when modules are part of the build, not
uploaded dynamically by users.

Use build-time compilation when:

- module bytes are stable build inputs,
- startup time is more important than accepting arbitrary modules at runtime,
- generated classes can be packaged with the application,
- the host can rebuild when the Wasm module changes.

Build-time generated classes should be treated like application code. Generate
them from trusted build inputs, review the build step that produces them, and
rebuild whenever the Wasm module or compiler version changes.

This path is JVM-only. Keep dynamically uploaded or user-supplied modules on
the interpreter or runtime compiler path unless the host has a separate trusted
build pipeline for them.

Keep the interpreted path available for development and compatibility testing.
It is often the easiest way to compare behavior when changing compiler
configuration.
