# Security

WebAssembly isolates linear memory and control flow, but host imports still run
with host process privileges. Treat every import as part of the security model.

## Baseline

- expose only the host functions and WASI capabilities the guest needs,
- validate pointers, lengths, handles, enum values, and resource identifiers,
- enforce CPU or wall-clock limits outside the core runtime,
- set memory limits appropriate for the workload,
- keep filesystem preopens narrow and capability-based,
- protect runtime compiler cache directories,
- prefer the interpreter for maximum assurance with untrusted modules.

Network, filesystem, clock, and random capabilities should be configured
explicitly. Avoid "default everything" host setups for plugins from outside the
application trust boundary.

## What Wasm Does Not Provide

The sandbox does not automatically limit CPU time, host memory allocations,
compiler work, filesystem access granted through WASI, or side effects inside
host functions. Those policies belong to the embedding application.

For untrusted modules:

- run execution on a worker that can be cancelled or interrupted,
- use [CPU limits](../execution/cpu-limits.md) instead of relying on guest
  cooperation,
- cap memory with `MemoryLimits` and bounded host allocation protocols,
- keep compiler cache directories private to the host application,
- prefer interpreter-first execution until compiled paths have matching
  operational limits.

## Host Function Checklist

Before a host function touches a guest-provided pointer, length, handle, path,
or enum value, validate that it is in range and valid for the protocol. Keep
host functions small, avoid unbounded loops over guest-controlled input, and
make blocking I/O obey the same timeout policy as guest execution.
