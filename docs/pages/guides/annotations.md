# Annotation Tools

The annotation modules support generated bindings and integration code around
the runtime. Use them when a tool in this repository asks for annotation
metadata instead of hand-written registration code.

Published artifacts:

- `annotations`: runtime-visible annotations used by generated or reflected
  integration code.
- `annotations-processor`: JVM annotation processing support for build-time
  generation.

Most applications should start with explicit runtime APIs. Add annotation
processing only when it removes real wiring code from a stable host boundary.
