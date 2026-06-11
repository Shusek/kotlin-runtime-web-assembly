# wasm

This module contains the portable WebAssembly parser and common Wasm model used
by the runtime. It is published as `uk.shusek.krwa:wasm` and provides both the
Kotlin Multiplatform `WasmParser` API and the JVM-only `Parser` facade.

## Usage

There are two ways you can interface with this library. The simplest way is to parse the whole
module using `Parser.parse`:

<!--
```java
//DEPS uk.shusek.krwa:wasm-corpus:0.3.0-SNAPSHOT
//DEPS uk.shusek.krwa:wasm:0.3.0-SNAPSHOT
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
`includeSection(int sectionId)` for each section you wish to parse. It will skip all other
sections. This is useful for performance if you only want to parse a piece of the module.
If you don't call this method once it will parse all sections.

```java
import uk.shusek.krwa.wasm.ParserListener;
import uk.shusek.krwa.wasm.types.CustomSection;
import uk.shusek.krwa.wasm.types.SectionId;

var parser = new Parser();

// include the custom sections, don't call this to receive all sections
parser.includeSection(SectionId.CUSTOM);
// parser.includeSection(SectionId.CODE); // call for each section you want

String result = "";
// implement the listener
ParserListener listener = section -> {
    if (section.sectionId() == SectionId.CUSTOM) {
        var customSection = (CustomSection) section;
        var name = customSection.name();
        result += name + "\n";
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
writeResultFile("parser-listener.result", result);
```
-->
