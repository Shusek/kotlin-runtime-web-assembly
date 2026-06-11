package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WitPackageTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun parsesPluginWorld() {
        val source =
            String(
                javaClass.getResourceAsStream("/plugin.wit")!!.readAllBytes(),
                StandardCharsets.UTF_8,
            )

        val witPackage = WitPackage.parse(source)

        assertEquals("example:plugins", witPackage.packageName())
        assertEquals(2, witPackage.interfaces().size)
        assertEquals(1, witPackage.worlds().size)
        assertEquals("plugin", witPackage.worlds()[0].name())
        assertEquals(1, witPackage.worlds()[0].imports().size)
        assertEquals(1, witPackage.worlds()[0].exports().size)
        assertNotNull(witPackage.interfaces()[1].functions()[0])
    }

    @Test
    fun parsesNestedWitTypes() {
        val witPackage =
            WitPackage.parse(
                """
                package example:types;
                interface api {
                  run: func(input: option<list<string>>) -> result<u32, string>;
                }
                """
                    .trimIndent()
            )

        val function = witPackage.interfaces()[0].functions()[0]

        assertEquals("run", function.name())
        assertEquals("input", function.parameters()[0].name())
        assertEquals(2, function.results()[0].type().arguments().size)
    }

    @Test
    fun parsesResourceFunctions() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    constructor(init: list<u8>);
                    write: func(bytes: list<u8>);
                    read: func(n: u32) -> list<u8>;
                    merge: static func(lhs: borrow<blob>, rhs: borrow<blob>) -> blob;
                  }
                }
                """
                    .trimIndent()
            )

        val resource = witPackage.interfaces()[0].members()[0] as WitPackage.TypeDeclaration

        assertEquals(WitPackage.TypeDeclaration.Kind.RESOURCE, resource.kind())
        assertEquals("blob", resource.name())
        assertEquals(4, resource.functions().size)
        assertTrue(resource.functions()[0].isConstructor)
        assertEquals("init", resource.functions()[0].parameters()[0].name())
        assertEquals("write", resource.functions()[1].name())
        assertEquals("read", resource.functions()[2].name())
        assertEquals("u32", resource.functions()[2].parameters()[0].type().name())
        assertTrue(resource.functions()[3].isStatic)
        assertEquals(
            WitPackage.TypeRef.TypeKind.BORROW,
            resource.functions()[3].parameters()[0].type().kind(),
        )
    }

    @Test
    fun parsesWorldLocalTypeAndResourceDeclarations() {
        val witPackage =
            WitPackage.parse(
                """
                world multi-function-device {
                  type byte-count = u64;
                  resource blob {
                    constructor(init: list<u8>);
                    write: func(bytes: list<u8>);
                  }
                  import open: func(size: byte-count) -> blob;
                }
                """
                    .trimIndent()
            )

        val world = witPackage.worlds()[0]
        val alias = world.declarations()[0] as WitPackage.TypeDeclaration
        val resource = world.declarations()[1] as WitPackage.TypeDeclaration

        assertEquals("multi-function-device", world.name())
        assertEquals(2, world.declarations().size)
        assertEquals(WitPackage.TypeDeclaration.Kind.ALIAS, alias.kind())
        assertEquals("byte-count", alias.name())
        assertEquals("u64", alias.target()!!.name())
        assertEquals(WitPackage.TypeDeclaration.Kind.RESOURCE, resource.kind())
        assertEquals("blob", resource.name())
        assertEquals(2, resource.functions().size)
        assertTrue(resource.functions()[0].isConstructor)
        assertEquals("open", world.imports()[0].name())
        assertEquals("byte-count", world.imports()[0].function()!!.parameters()[0].type().name())
    }

    @Test
    fun parsesUseAliasesAndQualifiedWorldItems() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface stdin {
                  use wasi:io/streams@0.2.12.{input-stream as stream};
                  get-stdin: func() -> stream;
                }
                interface run {
                  run: func();
                }
                world command {
                  import wasi:cli/stdin@0.2.12;
                  export run;
                }
                """
                    .trimIndent()
            )

        val use = witPackage.interfaces()[0].members()[0] as WitPackage.UseDeclaration
        val worldImport = witPackage.worlds()[0].imports()[0]

        assertEquals("wasi:io/streams@0.2.12", use.path())
        assertEquals("input-stream", use.items()[0].name())
        assertEquals("stream", use.items()[0].alias())
        assertEquals("wasi:cli/stdin@0.2.12", worldImport.name())
        assertEquals("stdin", WitNames.lastSegment(worldImport.name()))
        assertEquals("stdin", WitNames.lastSegment(worldImport.type()!!.name()!!))
    }

    @Test
    fun parsesDirectoryPackageWithDepsAsRootPackage() {
        val witDir = tempDir.resolve("wit")
        val depsIo = witDir.resolve("deps/io")
        Files.createDirectories(depsIo)
        Files.writeString(
            witDir.resolve("plugin.wit"),
            """
            package example:kotlin-plugin@1.0.0;
            interface api {
              use wasi:io/streams@0.2.11.{input-stream, output-stream};
              connect: func(input: borrow<input-stream>) -> result<output-stream, string>;
            }
            world plugin {
              import wasi:io/streams@0.2.11;
              export api;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            depsIo.resolve("streams.wit"),
            """
            package wasi:io@0.2.11;
            interface streams {
              resource input-stream;
              resource output-stream;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        val witPackage = Wit.parse(witDir.toOkioPath())
        val kotlin = KotlinWitBindings.generate(witPackage, "example.generated.wasi")
        val api = witPackage.interfaces().first { it.name() == "api" }
        val streams = witPackage.interfaces().first { it.name() == "streams" }

        assertEquals("example:kotlin-plugin@1.0.0", witPackage.packageName())
        assertEquals("example:kotlin-plugin@1.0.0", api.packageName())
        assertEquals("example:kotlin-plugin/api@1.0.0", api.qualifiedName())
        assertEquals("wasi:io@0.2.11", streams.packageName())
        assertEquals("wasi:io/streams@0.2.11", streams.qualifiedName())
        assertTrue(kotlin.contains("public object Plugin"))
        assertTrue(kotlin.contains("public val streams: example.generated.wasi.Streams"))
        assertTrue(kotlin.contains("public val api: example.generated.wasi.Api"))
    }

    @Test
    fun parsesInlinePackageBlocksWithAttributes() {
        val witPackage =
            WitPackage.parse(
                """
                @since(version = 1.0)
                package local:a {
                  @since(version = 2.0)
                  use wasi:http/types@1.0.0;
                  @since(version = 3.0)
                  interface printer {
                    print: func(text: string);
                  }
                  @since(version = 4.0)
                  world device {
                    export printer;
                  }
                }
                """
                    .trimIndent()
            )

        assertEquals("local:a", witPackage.packageName())
        assertEquals(1, witPackage.interfaces().size)
        assertEquals("printer", witPackage.interfaces()[0].name())
        assertEquals("wasi:http/types@1.0.0", witPackage.declarations()[0].name())
        assertEquals(1, witPackage.worlds().size)
        assertEquals("device", witPackage.worlds()[0].name())
        assertEquals("printer", witPackage.worlds()[0].exports()[0].name())
    }

    @Test
    fun parsesWorldInlineInterfacesAsSyntheticDeclarations() {
        val witPackage =
            WitPackage.parse(
                """
                package example:inline;
                world plugin {
                  @since(version = 1.0)
                  import host: interface {
                    log: func(message: string);
                  }
                  @since(version = 1.0)
                  export guest: interface {
                    scan: func(document: string) -> u32;
                  }
                }
                """
                    .trimIndent()
            )

        val world = witPackage.worlds()[0]

        assertEquals(2, witPackage.interfaces().size)
        assertEquals("plugin-host", witPackage.interfaces()[0].name())
        assertEquals("log", witPackage.interfaces()[0].functions()[0].name())
        assertEquals("plugin-guest", witPackage.interfaces()[1].name())
        assertEquals("scan", witPackage.interfaces()[1].functions()[0].name())
        assertEquals("host", world.imports()[0].name())
        assertEquals("plugin-host", world.imports()[0].type()!!.name())
        assertEquals("guest", world.exports()[0].name())
        assertEquals("plugin-guest", world.exports()[0].type()!!.name())
    }

    @Test
    fun flattensWorldIncludes() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface environment {
                  get-environment: func() -> list<string>;
                }
                interface run {
                  run: func();
                }
                world command {
                  include imports;
                  export run;
                }
                world imports {
                  import environment;
                }
                """
                    .trimIndent()
            )

        val command = witPackage.worlds()[0]

        assertEquals("command", command.name())
        assertEquals(1, command.includes().size)
        assertEquals("imports", command.includes()[0])
        assertEquals(1, command.imports().size)
        assertEquals("environment", command.imports()[0].name())
        assertEquals(1, command.exports().size)
        assertEquals("run", command.exports()[0].name())
    }

    @Test
    fun flattensWorldIncludesWithAliases() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface environment {
                  get-environment: func() -> list<string>;
                }
                interface run {
                  run: func();
                }
                world command {
                  include imports with { environment as env, run as entrypoint };
                }
                world imports {
                  import environment;
                  export run;
                }
                """
                    .trimIndent()
            )

        val command = witPackage.worlds()[0]

        assertEquals("imports", command.includeDeclarations()[0].path())
        assertEquals("environment", command.includeDeclarations()[0].items()[0].name())
        assertEquals("env", command.includeDeclarations()[0].items()[0].alias())
        assertEquals("env", command.imports()[0].name())
        assertEquals("entrypoint", command.exports()[0].name())
    }

    @Test
    fun flattensQualifiedWorldIncludesWithDuplicateLocalNames() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:clocks@0.3.0;
                interface monotonic-clock {
                  now: func() -> u64;
                }
                world imports {
                  import monotonic-clock;
                }
                package wasi:random@0.3.0;
                interface random {
                  get-random-bytes: func(len: u64) -> list<u8>;
                }
                world imports {
                  import random;
                }
                package wasi:cli@0.3.0;
                interface environment {
                  get-environment: func() -> list<string>;
                }
                world imports {
                  import environment;
                }
                world command {
                  include imports;
                }
                package wasi:http@0.3.0;
                world service {
                  include wasi:clocks/imports@0.3.0;
                  include wasi:random/imports@0.3.0;
                  include wasi:cli/imports@0.3.0;
                }
                """
                    .trimIndent()
            )

        val command = witPackage.worlds().first { it.name() == "command" }
        val service = witPackage.worlds().first { it.name() == "service" }

        assertEquals("environment", command.imports()[0].name())
        assertEquals("monotonic-clock", service.imports()[0].name())
        assertEquals("random", service.imports()[1].name())
        assertEquals("environment", service.imports()[2].name())
    }
}

private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
