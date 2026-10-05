# WASI Preview 1

Use the `wasi` module for WASI Preview 1 (`wasip1`) guests. It provides host
imports for common CLI-style workloads:

- arguments and environment variables,
- stdin, stdout, and stderr,
- clocks and random data,
- explicitly preopened filesystem directories.

WASI is capability-based. A guest cannot access an arbitrary host path unless
the host preopens it. Keep preopens narrow and prefer virtual or temporary
directories for untrusted workloads.

Symbolic links are resolved on the host before every path operation. A link in
any component of a guest path that leads outside the preopen root is refused
(`EACCES`, or `EPERM` from `path_open`), `path_open` follows a final link only
with `SYMLINK_FOLLOW`, and `path_symlink` rejects absolute targets as well as
relative targets that would leave the preopen. Links that stay inside the
preopen keep working.

Configure the capabilities through `WasiOptions`:

```kotlin
import okio.Path.Companion.toPath
import uk.shusek.krwa.wasi.WasiOptions

val options = WasiOptions.builder()
    .withArguments(listOf("guest.wasm", "--mode=batch"))
    .withEnvironment("RUST_BACKTRACE", "1")
    .withDirectory("/workspace", "/tmp/guest-work".toPath())
    .withThrowOnExit0(false)
    .build()
```

`withDirectory(guest, host)` maps the guest-visible preopen path to a host path
or Okio filesystem. Keep the guest path stable because Wasm programs often
treat it as part of their CLI contract.

On the JVM, `WasiOptions.Builder.inheritSystem()` connects stdin, stdout, and
stderr to the current process. Use explicit sinks and sources for tests,
services, and plugin hosts so guest output and input remain scoped.

Browser builds can parse modules, instantiate through the host WebAssembly
engine, and use host-provided imports. Browser filesystem access, raw sockets,
and blocking delay must be supplied by the application when a workload needs
those capabilities.
