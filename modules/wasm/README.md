# wasm

This module contains the portable WebAssembly parser and common Wasm model used
by the runtime. It is published as `uk.shusek.krwa:wasm` and provides both the
Kotlin Multiplatform `WasmParser` API and the JVM-only `Parser` facade.

## Usage

There are two ways you can interface with this library. The simplest way is to parse the whole
module using `Parser.parse`:

<!--
```java
//DEPS uk.shusek.krwa:wasm-corpus:0.3.0-dev.<12-character-commit>
//DEPS uk.shusek.krwa:wasm:0.3.0-dev.<12-character-commit>
```
-->

<!--
```java
var readmeResults = "readmes/wasm/current";
new File(readmeResults).mkdirs();

public void writeResultFile(String name, String content) throws Exception {
  FileWriter fileWriter = new FileWriter(new File(".").toPath().resolve(readmeResults).resolve(name).toFile());
  PrintWriter printWriter = new PrintWriter(fileWriter);
  printWriter.print(content);
  printWriter.flush();
  printWriter.close();
}
```
-->

```java
import uk.shusek.krwa.wasm.Parser;

var is = ClassLoader.getSystemClassLoader().getResourceAsStream("compiled/count_vowels.rs.wasm");
var module = Parser.parse(is);
var customSection = module.customSections().get(0);
System.out.println("First custom section: " + customSection.name());
```

<!--
```java
writeResultFile("parser-base.result", customSection.name() + "\n");
```
-->

The second is to use the `ParserListener` interface and the `parse()` method. In this mode you can also call
`includeSectionId(int sectionId)` for each section you wish to parse. It will skip all other
sections. This is useful for performance if you only want to parse a piece of the module.
If you don't call this method once it will parse all sections.

```java
import uk.shusek.krwa.wasm.ParserListener;
import uk.shusek.krwa.wasm.types.CustomSection;
import uk.shusek.krwa.wasm.types.SectionId;

var parser = Parser.builder()
    // Include custom sections. Omit this to receive all sections.
    .includeSectionId(SectionId.CUSTOM)
    // .includeSectionId(SectionId.CODE) // call for each section you want
    .build();

var result = new StringBuilder();
// implement the listener
ParserListener listener = section -> {
    if (section.sectionId() == SectionId.CUSTOM) {
        var customSection = (CustomSection) section;
        var name = customSection.name();
        result.append(name).append("\n");
        System.out.println("Got custom section with name: " + name);
    } else {
        throw new RuntimeException("Should not have received section with id: " + section.sectionId());
    }
};

// call parse()
var is = ClassLoader.getSystemClassLoader().getResourceAsStream("compiled/count_vowels.rs.wasm");
parser.parse(is, listener);
```
<!--
```java
writeResultFile("parser-listener.result", result.toString());
```
-->

## Resource Limits

Static `WasmParser.parse(...)` and `Parser.parse(...)` calls use finite default
limits. Build a parser with `WasmParserLimits` when inputs are untrusted or the
application needs a smaller, workload-specific budget:

```kotlin
val parser =
    WasmParser.builder()
        .withLimits(
            WasmParserLimits(
                maxModuleBytes = 8L * 1024L * 1024L,
                maxSectionBytes = 4 * 1024 * 1024,
                maxCustomSectionBytes = 256 * 1024,
                maxNameBytes = 8 * 1024,
                maxFunctions = 10_000,
                maxFunctionBytes = 512 * 1024,
                maxFunctionLocals = 10_000,
                maxInstructionsPerFunction = 100_000,
                maxControlDepth = 256,
            ),
        )
        .build()

val module = parser.parseBytes(bytes)
```

The same `withLimits(...)` method is available on the JVM-only
`Parser.builder()`. Limits also cover vectors and the counts and shapes of
types, imports, tables, memories, globals, exports, element/data segments, and
tags. A violation throws `WasmParseLimitException`, whose `limitName`,
`configuredLimit`, and `actual` properties can be used for diagnostics. Do not
retry an untrusted input with unbounded limits.
