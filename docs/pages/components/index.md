# Component Model

Use `component-model` when plugin boundaries should be described with WIT and
lifted/lowered through the canonical ABI instead of ad-hoc JSON payloads.

The module provides:

- WIT parsing and package models,
- Kotlin binding generation for host and guest contracts,
- canonical ABI lowering and lifting,
- `WasmPlugin` for loading core Wasm modules and component artifacts,
- WASI Preview 2 host wiring,
- WASI Preview 3 canonical support,
- helpers for `wasm-tools component embed`, `component new`, component
  unbundling, and validation.

WIT is the stable contract. Generated Kotlin should be treated as build output
for hosts and guests that implement that contract.

## Typical Flow

1. Define the plugin world in WIT.
2. Generate Kotlin contracts for the host and guest.
3. Implement the host imports and guest exports.
4. Package the guest core Wasm as a component when needed.
5. Load the plugin with `WasmPlugin` and view exports through the generated
   Kotlin interface.

For Kotlin/Wasm guests, generated export adapters can expose WIT world
functions as `@WasmExport` wrappers so the component packager can discover the
core Wasm exports matching the contract.
