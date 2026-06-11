# Linking Modules

The runtime separates module parsing from instantiation. Imports must be
satisfied before an instance can run.

Use a `Store` when modules should share registered host imports or when exports
from one module should be visible as imports to another module. This keeps
linking explicit and avoids rebuilding the same import set for every instance.

Recommended shape:

- parse every Wasm module once,
- register stable host functions in the store,
- instantiate producers before consumers when consumers import producer
  exports,
- keep module names stable because they are part of the import contract.

For Component Model plugins, prefer `WasmPlugin`. It resolves WIT-world imports,
guest exports, canonical ABI calls, resources, and WASI wiring at the contract
level.
