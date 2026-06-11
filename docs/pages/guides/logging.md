# Logging

The `log` artifact provides a small logging facade used by runtime-adjacent
tools and optional integrations:

```kotlin
dependencies {
    implementation("uk.shusek.krwa:log")
}
```

`Logger` exposes levels from `TRACE` through `ERROR`. On the JVM,
`BasicLogger` maps those levels to `java.util.logging` and writes through the
logger named `krwa`; `SystemLogger` is the default convenience implementation.

Configure JVM logging with normal `java.util.logging` configuration:

```properties
krwa.level=FINE
java.util.logging.ConsoleHandler.level=FINE
```

For embedded hosts, implement `Logger` when messages need to flow into the
application logger instead of JUL:

```kotlin
import uk.shusek.krwa.log.Logger

class HostLogger : Logger {
    override fun isLoggable(level: Logger.Level): Boolean = true

    override fun log(level: Logger.Level, msg: String, throwable: Throwable?) {
        println("[${level.name}] $msg")
        throwable?.printStackTrace()
    }
}
```

Keep logging off hot interpreter paths unless you are debugging a specific
problem. Instruction-level observation belongs to the unsafe execution listener,
not general-purpose logging.
