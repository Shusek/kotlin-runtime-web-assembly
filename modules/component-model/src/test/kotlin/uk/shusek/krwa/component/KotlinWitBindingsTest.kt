package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinWitBindingsTest {
    @Test
    fun generatesKotlinContractsForPluginWit() {
        val source =
            String(
                javaClass.getResourceAsStream("/plugin.wit")!!.readAllBytes(),
                StandardCharsets.UTF_8,
            )
        val witPackage = WitPackage.parse(source)

        val kotlin = KotlinWitBindings.generate(witPackage, "example.plugins")

        assertTrue(kotlin.startsWith("@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)"))
        assertTrue(kotlin.contains("package example.plugins"))
        assertTrue(kotlin.contains("import uk.shusek.krwa.component.WitResult"))
        assertTrue(kotlin.contains("public interface Host"))
        assertTrue(kotlin.contains("public interface Transform"))
        assertTrue(kotlin.contains("public data class Request"))
        assertTrue(kotlin.contains("public sealed interface Response"))
        assertTrue(kotlin.contains("public object Plugin"))
        assertTrue(kotlin.contains("public val host: example.plugins.Host"))
        assertTrue(kotlin.contains("public val transform: example.plugins.Transform"))
        assertTrue(
            kotlin.contains("public fun export(request: Request): WitResult<Response, String>")
        )
    }

    @Test
    fun splitBindingsExposeStablePublicApiSnapshot() {
        val witPackage =
            WitPackage.parse(
                """
                package example:snapshot@1.2.3;

                interface types {
                  resource movie-id;

                  record context {
                    user-id: option<string>,
                    flags: list<string>,
                  }
                }

                interface media {
                  use types.{movie-id};

                  record movie {
                    id: movie-id,
                    title: string,
                    tags: list<string>,
                  }

                  variant lookup-error {
                    not-found(movie-id),
                    blocked,
                    backend(string),
                  }

                  search: async func(query: string, limit: option<u32>) ->
                    result<list<movie>, lookup-error>;
                }

                interface host {
                  log: func(message: string);
                }

                world plugin {
                  use types.{context};
                  import host;
                  export media;
                  export init: func(context: context) -> result<string, string>;
                }
                """
                    .trimIndent()
            )

        val snapshot =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.snapshot")
                .withPluginHelpers(true)
                .build()
                .generateFiles()
                .publicApiSnapshot()

        assertEquals(
            """
            == example/generated/snapshot/Host.kt ==
            public interface Host {
            public fun log(message: String)

            == example/generated/snapshot/Media.kt ==
            public interface Media {
            public typealias MovieId = Types.MovieId
            public data class Movie(
            public val id: MovieId,
            public val title: String,
            public val tags: List<String>
            public sealed interface LookupError {
            public data class NotFound(public val value: MovieId) : LookupError
            public object Blocked : LookupError
            public data class Backend(public val value: String) : LookupError
            public suspend fun search(query: String, limit: UInt?): WitResult<List<Movie>, LookupError>

            == example/generated/snapshot/Plugin.kt ==
            public object Plugin {
            public typealias Context = Types.Context
            public interface Host {
            public val host: example.generated.snapshot.Host
            public interface Guest {
            public val media: example.generated.snapshot.Media
            public fun init(context: Context): WitResult<String, String>
            public const val WIT_PACKAGE: String = "example:snapshot@1.2.3"
            public const val WIT_PACKAGE_VERSION: String = "1.2.3"
            public const val WIT_PACKAGE_MAJOR_VERSION: Int = 1
            public const val WIT_WORLD: String = "plugin"
            public const val WIT_QUALIFIED_WORLD: String = "example:snapshot/plugin@1.2.3"
            public fun installHost(builder: uk.shusek.krwa.component.WasmPlugin.Builder, host: Host): uk.shusek.krwa.component.WasmPlugin.Builder =
            public fun build(builder: uk.shusek.krwa.component.WasmPlugin.Builder, host: Host): uk.shusek.krwa.component.WasmPlugin =
            public fun guest(plugin: uk.shusek.krwa.component.WasmPlugin): Guest =
            public fun guest(invoker: uk.shusek.krwa.component.WasiComponentInvoker): Guest =

            == example/generated/snapshot/Types.kt ==
            public interface Types {
            public typealias MovieId = WitResource<MovieIdTag>
            public object MovieIdTag
            public data class Context(
            public val userId: String?,
            public val flags: List<String>
            """.trimIndent(),
            snapshot,
        )
    }

    @Test
    fun mapsWitCharToUnicodeScalarInt() {
        val witPackage =
            WitPackage.parse(
                """
                package example:chars;
                world plugin {
                  export mark: func(value: char) -> char;
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.chars")

        assertTrue(kotlin.contains("public fun mark(value: Int): Int"))
    }

    private fun List<KotlinWitBindings.GeneratedFile>.publicApiSnapshot(): String =
        sortedBy { file -> file.relativePath.toString() }
            .joinToString("\n\n") { file ->
                val publicLines =
                    file.content
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.startsWith("public ") }
                        .joinToString("\n")
                "== ${file.relativePath} ==\n$publicLines"
            }

    @Test
    fun generatesGuestExportAdaptersWhenRequested() {
        val witPackage =
            WitPackage.parse(
                """
                package example:catalogs@1.0.0;
                interface api {
                  record catalog {
                    id: u32,
                    title: string,
                  }
                  add: func(lhs: u32, rhs: u32) -> u32;
                  echo-name: func(name: string) -> string;
                  get-catalogs: async func(limit: u32) -> list<catalog>;
                }
                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )

        val kotlin =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.catalogs.generated")
                .withGuestExportAdapters(true)
                .build()
                .generate()

        assertTrue(kotlin.contains("kotlin.wasm.ExperimentalWasmInterop::class"))
        assertTrue(kotlin.contains("public object KrwaGuestExports"))
        assertTrue(kotlin.contains("public fun installPlugin(guest: Plugin.Guest)"))
        assertTrue(kotlin.contains("@WasmExport(\"canonical_abi_realloc\")"))
        assertTrue(kotlin.contains("@WasmExport(\"cabi_realloc\")"))
        assertTrue(kotlin.contains("@WasmExport(\"api.add\")"))
        assertTrue(kotlin.contains("@WasmExport(\"example:catalogs/api@1.0.0#add\")"))
        assertFalse(kotlin.contains("@WasmExport(\"[async]api.get-catalogs\")"))
        assertTrue(
            kotlin.contains(
                "@WasmImport(\"[export]example:catalogs/api@1.0.0\", " +
                    "\"[task-return]get-catalogs\")"
            )
        )
        assertTrue(
            kotlin.contains(
                "@WasmExport(\"[async-lift]example:catalogs/api@1.0.0#get-catalogs\")"
            )
        )
        assertTrue(
            kotlin.contains(
                "@WasmExport(\"[callback][async-lift]example:catalogs/api@1.0.0#get-catalogs\")"
            )
        )
        assertTrue(kotlin.contains("@WasmImport(\"\\${'$'}root\", \"[context-get-0]\")"))
        assertTrue(kotlin.contains("return krwaStartSuspend("))
        assertFalse(kotlin.contains("krwaRunSuspend { KrwaGuestExports.plugin.api.getCatalogs"))
        assertTrue(kotlin.contains("val resultPtr = krwaAlloc(8)"))
        assertTrue(kotlin.contains("val result0Ptr = krwaStoreCatalogList(result)"))
        assertTrue(kotlin.contains("private fun krwaStoreString(ptr: Int, value: String)"))
        assertTrue(kotlin.contains("private fun krwaStoreCatalog(ptr: Int, value: "))
        assertTrue(kotlin.contains("krwaStoreI32(ptr + 0, value.id.toInt())"))
        assertTrue(kotlin.contains("krwaStoreString(ptr + 4, value.title)"))
        assertTrue(kotlin.contains("private fun krwaStoreCatalogList(value: List<"))
        assertTrue(kotlin.contains("val ptr = krwaAlloc(12 * value.size)"))
        assertTrue(kotlin.contains("krwaStoreCatalog(ptr + (index * 12), value[index])"))
        assertTrue(kotlin.contains("val param0: String = krwaLoadString(arg0, arg1)"))
        assertTrue(kotlin.contains("krwaStoreString(resultPtr + 0, result)"))
        val echoParamIndex = kotlin.indexOf("val param0: String = krwaLoadString(arg0, arg1)")
        val echoFreeIndex =
            kotlin.indexOf("krwaFreeAllComponentModelReallocAllocatedMemory()", echoParamIndex)
        val echoCallIndex =
            kotlin.indexOf("val result = KrwaGuestExports.plugin.api.echoName(param0)")
        assertTrue(echoParamIndex >= 0)
        assertTrue(echoFreeIndex > echoParamIndex)
        assertTrue(echoCallIndex > echoFreeIndex)
        val asyncExportIndex =
            kotlin.indexOf(
                "@WasmExport(\"[async-lift]example:catalogs/api@1.0.0#get-catalogs\")"
            )
        val asyncFreeIndex =
            kotlin.indexOf("krwaFreeAllComponentModelReallocAllocatedMemory()", asyncExportIndex)
        val asyncStartIndex = kotlin.indexOf("return krwaStartSuspend(", asyncExportIndex)
        assertTrue(asyncExportIndex >= 0)
        assertTrue(asyncFreeIndex > asyncExportIndex)
        assertTrue(asyncStartIndex > asyncFreeIndex)
    }

    @Test
    fun generatesResourceAliasesForExternalUseDeclarations() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface stdin {
                  use wasi:io/streams@0.2.12.{input-stream};
                  get-stdin: func() -> input-stream;
                  set-stdin: func(stream: borrow<input-stream>);
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.cli")

        assertTrue(kotlin.contains("public typealias InputStream = WitResource<InputStreamTag>"))
        assertTrue(kotlin.contains("public object InputStreamTag"))
        assertTrue(kotlin.contains("public fun getStdin(): InputStream"))
        assertTrue(kotlin.contains("public fun setStdin(stream: InputStream)"))
    }

    @Test
    fun generatesAliasesForLocalUseDeclarations() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:clocks@0.2.12;
                interface wall-clock {
                  record datetime { seconds: u64, nanoseconds: u32 }
                }
                interface timezone {
                  use wall-clock.{datetime as local-datetime};
                  display: func(when: local-datetime);
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.clocks")

        assertTrue(kotlin.contains("public typealias LocalDatetime = WallClock.Datetime"))
        assertTrue(kotlin.contains("public fun display(`when`: LocalDatetime)"))
    }

    @Test
    fun resolvesRelativeUseDeclarationsWithinTheirOwnPackage() {
        val witPackage =
            WitPackage.parse(
                """
                package example:root;
                package wasi:filesystem@0.3.0 {
                  interface types {
                    resource descriptor;
                  }
                }
                package wasi:http@0.3.0 {
                interface types {
                  resource request;
                  resource response;
                  variant error-code { internal-error }
                }
                interface client {
                  use types.{request, response, error-code};
                  send: async func(request: request) -> result<response, error-code>;
                }
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.preview3")

        assertTrue(kotlin.contains("public typealias Request = WasiHttpTypes.Request"))
        assertTrue(kotlin.contains("public typealias Response = WasiHttpTypes.Response"))
        assertFalse(kotlin.contains("public typealias Request = WasiFilesystemTypes.Request"))
        assertFalse(kotlin.contains("public typealias Response = WasiFilesystemTypes.Response"))
    }

    @Test
    fun generatesResourceFunctionContracts() {
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

        val kotlin = KotlinWitBindings.generate(witPackage, "example.resources")

        assertTrue(kotlin.contains("public typealias Blob = WitResource<BlobTag>"))
        assertTrue(kotlin.contains("public object BlobTag"))
        assertTrue(kotlin.contains("public fun blobConstructor(init: UByteArray): Blob"))
        assertTrue(kotlin.contains("public fun blobWrite(self: Blob, bytes: UByteArray)"))
        assertTrue(kotlin.contains("public fun blobRead(self: Blob, n: UInt): UByteArray"))
        assertTrue(kotlin.contains("public fun blobMerge(lhs: Blob, rhs: Blob): Blob"))
        assertFalse(kotlin.contains("WitResource<Blob>"))
    }

    @Test
    fun generatesWorldLocalTypeAndResourceContracts() {
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
                  export report: func(blob: borrow<blob>) -> byte-count;
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.device")

        assertTrue(kotlin.contains("public object MultiFunctionDevice"))
        assertTrue(kotlin.contains("public typealias ByteCount = ULong"))
        assertTrue(kotlin.contains("public typealias Blob = WitResource<BlobTag>"))
        assertTrue(kotlin.contains("public object BlobTag"))
        assertTrue(kotlin.contains("public fun open(size: ByteCount): Blob"))
        assertTrue(kotlin.contains("public fun report(blob: Blob): ByteCount"))
    }

    @Test
    fun generatesTypedTupleContracts() {
        val witPackage =
            WitPackage.parse(
                """
                package example:tuples;
                interface api {
                  type sample = tuple<u32, string, bool, list<u8>>;
                  measure: func(input: sample) -> sample;
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.tuples")

        assertTrue(kotlin.contains("import uk.shusek.krwa.component.WitTuple4\n"))
        assertTrue(
            kotlin.contains(
                "public typealias Sample = WitTuple4<UInt, String, Boolean, UByteArray>"
            )
        )
        assertTrue(kotlin.contains("public fun measure(input: Sample): Sample"))
    }

    @Test
    fun generatesTypedFunctionMultiResultContracts() {
        val witPackage =
            WitPackage.parse(
                """
                package example:tuples;
                interface api {
                  inspect: func() -> (size: u32, name: string, active: bool, code: u8);
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.tuples")

        assertTrue(kotlin.contains("public fun inspect(): WitTuple4<UInt, String, Boolean, UByte>"))
    }

    @Test
    fun generatesResourceAwareTupleBridgeReturnTypes() {
        val witPackage =
            WitPackage.parse(
                """
                package example:resources;
                interface db {
                  resource blob {
                    describe: func() -> tuple<blob, u32, string, bool>;
                  }
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.resources")

        assertTrue(
            kotlin.contains(
                "public fun blobDescribe(self: Blob): WitTuple4<Blob, UInt, String, Boolean>"
            )
        )
    }

    @Test
    fun generatesWasi03AsyncWorldContracts() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.3.0;
                interface exit {
                  exit: func(status: result);
                }
                interface run {
                  run: async func() -> result;
                }
                world command {
                  import exit;
                  export run;
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.cli.v0_3_0")

        assertTrue(kotlin.contains("public fun exit(status: WitResult<Unit, Unit>)"))
        assertTrue(kotlin.contains("public suspend fun run(): WitResult<Unit, Unit>"))
        assertTrue(kotlin.contains("public val exit: wasi.cli.v0_3_0.Exit"))
        assertTrue(kotlin.contains("public val run: wasi.cli.v0_3_0.Run"))
    }

    @Test
    fun generatesIncludedWorldContracts() {
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

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.cli")

        assertTrue(kotlin.contains("public object Command"))
        assertTrue(kotlin.contains("public val environment: wasi.cli.Environment"))
        assertTrue(kotlin.contains("public val run: wasi.cli.Run"))
    }

    @Test
    fun generatesIncludedWorldContractsWithAliases() {
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

        val kotlin = KotlinWitBindings.generate(witPackage, "wasi.cli")

        assertTrue(kotlin.contains("public val env: wasi.cli.Environment"))
        assertTrue(kotlin.contains("public val entrypoint: wasi.cli.Run"))
    }

    @Test
    fun generatesWorldInlineInterfaceContracts() {
        val witPackage =
            WitPackage.parse(
                """
                package example:inline;
                world plugin {
                  import host: interface {
                    log: func(message: string);
                  }
                  export guest: interface {
                    scan: func(document: string) -> u32;
                  }
                }
                """
                    .trimIndent()
            )

        val kotlin = KotlinWitBindings.generate(witPackage, "example.inline")

        assertTrue(kotlin.contains("public interface PluginHost"))
        assertTrue(kotlin.contains("public fun log(message: String)"))
        assertTrue(kotlin.contains("public interface PluginGuest"))
        assertTrue(kotlin.contains("public fun scan(document: String): UInt"))
        assertTrue(kotlin.contains("public val host: example.inline.PluginHost"))
        assertTrue(kotlin.contains("public val guest: example.inline.PluginGuest"))
    }

    @Test
    fun canGenerateSelfContainedKotlinRuntimeTypes() {
        val witPackage =
            WitPackage.parse(
                """
                package example:plugin;
                world plugin {
                  export run: func() -> result<string, string>;
                }
                """
                    .trimIndent()
            )

        val kotlin =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.plugin")
                .withRuntimeTypes(true)
                .build()
                .generate()

        assertTrue(kotlin.contains("public sealed interface WitResult<out O, out E>"))
        assertTrue(kotlin.contains("public data class WitResource<T>(public val handle: UInt)"))
        assertTrue(kotlin.contains("public data class WitTuple4<out A, out B, out C, out D>"))
        assertTrue(kotlin.contains("public fun run(): WitResult<String, String>"))
    }

    @Test
    fun canGenerateJvmPluginHelpersWithVersionMetadata() {
        val witPackage =
            WitPackage.parse(
                """
                package example:movies@1.1.0;
                interface movies {
                  get-movie-details: func(id: u64) -> result<string, string>;
                }
                interface api {
                  run: func() -> result<string, string>;
                }
                world movie-plugin {
                  import movies;
                  export api;
                }
                """
                    .trimIndent()
            )

        val kotlin =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.movies")
                .withPluginHelpers(true)
                .build()
                .generate()

        assertTrue(
            kotlin.contains("public const val WIT_PACKAGE: String = \"example:movies@1.1.0\"")
        )
        assertTrue(kotlin.contains("public const val WIT_PACKAGE_VERSION: String = \"1.1.0\""))
        assertTrue(kotlin.contains("public const val WIT_PACKAGE_MAJOR_VERSION: Int = 1"))
        assertTrue(kotlin.contains("public const val WIT_WORLD: String = \"movie-plugin\""))
        assertTrue(
            kotlin.contains(
                "public fun installHost(builder: uk.shusek.krwa.component.WasmPlugin.Builder, host: Host)"
            )
        )
        assertTrue(
            kotlin.contains(
                "public fun build(builder: uk.shusek.krwa.component.WasmPlugin.Builder, host: Host)"
            )
        )
        assertTrue(
            kotlin.contains("public fun guest(plugin: uk.shusek.krwa.component.WasmPlugin): Guest")
        )
    }
}
