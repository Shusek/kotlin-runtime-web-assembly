# Host Functions

Host functions are imports implemented by the embedding application. They are
the main capability boundary between guest Wasm and the host process.

Keep host functions small and explicit:

- validate every pointer and length before reading or writing memory,
- cap allocations and output sizes,
- treat handles from the guest as untrusted identifiers,
- expose only the filesystem, network, clock, and random capabilities the guest
  needs,
- keep blocking host work outside latency-sensitive runtime paths.

Core Wasm imports are low-level. Use them when you control both sides of the
boundary or when the ABI is intentionally small. Use WIT and the Component
Model when plugin contracts need named records, variants, resources, futures,
or streams.

## Import Names

Wasm imports are addressed by module name and field name. Keep those names
stable; changing them is a binary compatibility break for existing guest
modules.

If multiple modules need to share the same host functions or guest exports, use
a `Store` and register imports there instead of re-creating import tables for
each instance.
