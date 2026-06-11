package uk.shusek.krwa.component

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okio.Path.Companion.toPath
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class KotlinWitBindingsCompileTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun generatedResourceContractsCompileAsKotlin() {
        val witPackage = richPluginPackage()
        val generated = KotlinWitBindings.generate(witPackage, "example.resources.generated")
        val generatedFile = tempDir.resolve("Generated.kt")
        val usageFile = tempDir.resolve("UseGenerated.kt")
        Files.writeString(generatedFile, generated, StandardCharsets.UTF_8)
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.resources.generated

            import uk.shusek.krwa.component.WitResource
            import uk.shusek.krwa.component.WitTuple4

            class DbImpl : Db {
              override fun echo(request: Db.Request): Db.Request =
                  Db.Request(request.body, request.contentType)

              override fun decide(request: Db.Request): Db.Outcome =
                  Db.Outcome.Accepted(request)

              override fun maybe(flag: Boolean): String? =
                  if (flag) "value" else null

              override fun permissions(): Db.Permissions =
                  Db.Permissions(read = true, write = false, networkAccess = true)

              override fun status(): Db.Status = Db.Status.WAITING_ROOM

              override fun blobConstructor(init: UByteArray): Db.Blob =
                  WitResource<Db.BlobTag>(init.size.toLong())

              override fun blobRead(self: Db.Blob, n: UInt): UByteArray =
                  ubyteArrayOf(n.toUByte())

              override fun blobDescribe(self: Db.Blob, n: UInt):
                  WitTuple4<Db.Blob, UInt, String, Boolean> =
                  WitTuple4(self, n, "blob", true)
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun generatedSelfContainedContractsCompileAsKotlin() {
        val generated =
            KotlinWitBindings.builder(richPluginPackage())
                .withPackageName("example.resources.selfcontained")
                .withRuntimeTypes(true)
                .build()
                .generate()
        val generatedFile = tempDir.resolve("SelfContainedGenerated.kt")
        val usageFile = tempDir.resolve("UseSelfContainedGenerated.kt")
        Files.writeString(generatedFile, generated, StandardCharsets.UTF_8)
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.resources.selfcontained

            class SelfContainedDbImpl : Db {
              override fun echo(request: Db.Request): Db.Request = request

              override fun decide(request: Db.Request): Db.Outcome =
                  Db.Outcome.Accepted(request)

              override fun maybe(flag: Boolean): String? = null

              override fun permissions(): Db.Permissions =
                  Db.Permissions(read = true, write = false, networkAccess = true)

              override fun status(): Db.Status = Db.Status.READY

              override fun blobConstructor(init: UByteArray): Db.Blob =
                  WitResource<Db.BlobTag>(init.size.toUInt())

              override fun blobRead(self: Db.Blob, n: UInt): UByteArray =
                  ubyteArrayOf(n.toUByte())

              override fun blobDescribe(self: Db.Blob, n: UInt):
                  WitTuple4<Db.Blob, UInt, String, Boolean> =
                  WitTuple4(self, n, "blob", true)
            }

            fun okResult(): WitResult<String, String> = WitResult.Ok("ok")
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun writtenBindingsFromWitFileCompileAsKotlin() {
        val witFile = tempDir.resolve("plugin.wit")
        Files.writeString(
            witFile,
            """
            package example:written;
            world plugin {
              export run: func() -> result<string, string>;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val generatedFile = tempDir.resolve("generated/PluginBindings.kt")
        val usageFile = tempDir.resolve("UsePluginBindings.kt")

        assertEquals(
            generatedFile.toOkioPath(),
            KotlinWitBindings.write(
                witFile.toOkioPath(),
                "example.generated.file",
                generatedFile.toOkioPath(),
            ),
        )
        assertTrue(Files.exists(generatedFile))
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.file

            import uk.shusek.krwa.component.WitResult

            class PluginImpl : Plugin.Guest {
              override fun run(): WitResult<String, String> = WitResult.Ok("ok")
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun cliWrittenBindingsFromWitFileCompileAsKotlin() {
        val witFile = tempDir.resolve("cli-plugin.wit")
        Files.writeString(
            witFile,
            """
            package example:cli;
            world plugin {
              export run: func() -> result<string, string>;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val generatedFile = tempDir.resolve("cli/PluginBindings.kt")
        val usageFile = tempDir.resolve("UseCliPluginBindings.kt")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            KotlinWitBindgen.run(
                arrayOf(
                    "--package",
                    "example.generated.cli",
                    "--out",
                    generatedFile.toString(),
                    "--plugin-helpers",
                    witFile.toString(),
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))
        assertTrue(Files.exists(generatedFile))
        assertTrue(Files.readString(generatedFile).contains("public fun guest(plugin:"))
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.cli

            import uk.shusek.krwa.component.WitResult

            class PluginImpl : Plugin.Guest {
              override fun run(): WitResult<String, String> = WitResult.Ok("ok")
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun cliCanGenerateGuestExportAdapters() {
        val witFile = tempDir.resolve("guest-export-plugin.wit")
        Files.writeString(
            witFile,
            """
            package example:guest-export@1.0.0;
            interface api {
              run: async func(value: u32) -> u32;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val generatedFile = tempDir.resolve("guest/PluginBindings.kt")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        assertEquals(
            0,
            KotlinWitBindgen.run(
                arrayOf(
                    "--package",
                    "example.generated.guest",
                    "--out",
                    generatedFile.toString(),
                    "--guest-exports",
                    witFile.toString(),
                ),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
            ),
            stderr.toString(StandardCharsets.UTF_8),
        )
        assertEquals("", stdout.toString(StandardCharsets.UTF_8))
        val generated = Files.readString(generatedFile)
        assertTrue(generated.contains("public object KrwaGuestExports"))
        assertFalse(generated.contains("@WasmExport(\"[async]api.run\")"))
        assertTrue(generated.contains("public sealed interface WitResult<out O, out E>"))
        assertTrue(generated.contains("krwaRunSuspend"))
    }

    @Test
    fun suvioStyleGuestExportBindingsCompileForWasmWasi() {
        val witPackage =
            WitPackage.parse(
                """
                package suvio:sample-catalog@1.0.0;

                interface api {
                  record plugin-context {
                    settings: list<plugin-setting>,
                    selected: option<setting-value>,
                  }

                  record plugin-setting {
                    value: setting-value,
                    description: option<string>,
                  }

                  variant setting-value {
                    text(string),
                    flag(bool),
                    number(s64),
                    text-list(list<string>),
                    none,
                  }

                  record settings-schema {
                    settings: list<plugin-setting>,
                    selected: option<setting-value>,
                  }

                  record plugin-error {
                    message: string,
                    description: option<string>,
                  }

                  get-settings: async func(context: option<plugin-context>) ->
                    result<settings-schema, plugin-error>;
                }

                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )
        val generated =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.suvio")
                .withGuestExportAdapters(true)
                .build()
                .generate()
        val usage =
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.suvio

            object SampleCatalogComponentGuest : Plugin.Guest {
              override val api: Api = object : Api {
                override suspend fun getSettings(
                  context: Api.PluginContext?
                ): WitResult<Api.SettingsSchema, Api.PluginError> {
                  val inherited = context?.settings ?: emptyList()
                  val settings = inherited + listOf(
                    Api.PluginSetting(
                      value = Api.SettingValue.TextList(listOf("alpha", "beta")),
                      description = "demo",
                    ),
                    Api.PluginSetting(
                      value = Api.SettingValue.None,
                      description = null,
                    ),
                  )
                  return WitResult.Ok(
                    Api.SettingsSchema(
                      settings = settings,
                      selected = context?.selected ?: Api.SettingValue.Flag(true),
                    )
                  )
                }
              }
            }

            fun installGuest() {
              KrwaGuestExports.installPlugin(SampleCatalogComponentGuest)
            }
            """
                .trimIndent()

        compileWasmWasi(generated, usage)
    }

    @Test
    fun generatedBindingsFromWasiStyleDepsDirectoryCompileAsKotlin() {
        val witDir = tempDir.resolve("wasi-style-wit")
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
        val generatedFile = tempDir.resolve("WasiStylePluginBindings.kt")
        val usageFile = tempDir.resolve("UseWasiStylePluginBindings.kt")

        KotlinWitBindings.write(
            witDir.toOkioPath(),
            "example.generated.wasi",
            generatedFile.toOkioPath(),
        )
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.wasi

            import uk.shusek.krwa.component.WitResult

            class HostImpl : Plugin.Host {
              override val streams: Streams = object : Streams {}
            }

            class GuestImpl : Plugin.Guest {
              override val api: Api = object : Api {
                override fun connect(input: Api.InputStream):
                    WitResult<Api.OutputStream, String> = WitResult.Err("closed")
              }
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun generatedBindingsDisambiguateDuplicateDependencyInterfaceNames() {
        val witDir = tempDir.resolve("duplicate-types-wit")
        Files.createDirectories(witDir.resolve("deps/http"))
        Files.createDirectories(witDir.resolve("deps/filesystem"))
        Files.writeString(
            witDir.resolve("plugin.wit"),
            """
            package example:duplicate-types@1.0.0;
            interface api {
              use wasi:http/types@0.2.11.{fields as http-fields};
              use wasi:filesystem/types@0.2.11.{descriptor as file-descriptor};
              inspect: func(headers: borrow<http-fields>, descriptor: borrow<file-descriptor>) -> string;
            }
            world plugin {
              export api;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            witDir.resolve("deps/http/types.wit"),
            """
            package wasi:http@0.2.11;
            interface types {
              resource fields;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            witDir.resolve("deps/filesystem/types.wit"),
            """
            package wasi:filesystem@0.2.11;
            interface types {
              resource descriptor;
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val generatedFile = tempDir.resolve("DuplicateTypesBindings.kt")
        val usageFile = tempDir.resolve("UseDuplicateTypesBindings.kt")

        KotlinWitBindings.write(
            witDir.toOkioPath(),
            "example.generated.duplicates",
            generatedFile.toOkioPath(),
        )
        val generated = Files.readString(generatedFile, StandardCharsets.UTF_8)
        assertTrue(generated.contains("public interface WasiHttpTypes"))
        assertTrue(generated.contains("public interface WasiFilesystemTypes"))
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.duplicates

            class GuestImpl : Plugin.Guest {
              override val api: Api = object : Api {
                override fun inspect(headers: Api.HttpFields, descriptor: Api.FileDescriptor): String = "ok"
              }
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun generatedPreview3QualifiedIncludesCompileAsKotlin() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:clocks@0.3.0;
                interface monotonic-clock {
                  wait-for: async func(duration: u64);
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
                package wasi:http@0.3.0;
                interface incoming-handler {
                  handle: async func(request: stream<u8>) -> future<result<_, string>>;
                }
                world service {
                  include wasi:clocks/imports@0.3.0;
                  include wasi:random/imports@0.3.0;
                  export incoming-handler;
                }
                """
                    .trimIndent()
            )
        val generatedFile = tempDir.resolve("Preview3Bindings.kt")
        val usageFile = tempDir.resolve("UsePreview3Bindings.kt")
        val generated = KotlinWitBindings.generate(witPackage, "example.generated.preview3")
        Files.writeString(generatedFile, generated, StandardCharsets.UTF_8)
        assertTrue(generated.contains("public suspend fun waitFor(duration: ULong)"))
        assertTrue(
            generated.contains(
                "public suspend fun handle(request: WitStream<UByte>): WitFuture<WitResult<Unit, String>>"
            )
        )
        Files.writeString(
            usageFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.preview3

            import uk.shusek.krwa.component.WitFuture
            import uk.shusek.krwa.component.WitResult
            import uk.shusek.krwa.component.WitStream

            class Preview3Host : Service.Host {
              override val monotonicClock: MonotonicClock = object : MonotonicClock {
                override suspend fun waitFor(duration: ULong) {}
              }

              override val random: Random = object : Random {
                override fun getRandomBytes(len: ULong): UByteArray = ubyteArrayOf()
              }
            }

            class Preview3Guest : Service.Guest {
              override val incomingHandler: IncomingHandler = object : IncomingHandler {
                override suspend fun handle(request: WitStream<UByte>):
                    WitFuture<WitResult<Unit, String>> =
                    WitFuture.of(1L)
              }
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )

        compileKotlin(generatedFile, usageFile)
    }

    @Test
    fun generatedKotlinHostAndGuestProxyWorkAcrossPluginBoundary() {
        val witPackage =
            WitPackage.parse(
                """
                package example:kotlin@1.0.0;
                interface host {
                  echo: func(bytes: list<u8>, count: u32) -> list<u8>;
                }
                interface api {
                  run: func() -> list<u8>;
                }
                world plugin {
                  import host;
                  export api;
                }
                """
                    .trimIndent()
            )
        val generatedFile = tempDir.resolve("GeneratedKotlinBoundary.kt")
        val hostFile = tempDir.resolve("KotlinBoundaryHost.kt")
        Files.writeString(
            generatedFile,
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.boundary")
                .withPluginHelpers(true)
                .build()
                .generate(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            hostFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.boundary

            import uk.shusek.krwa.component.WasmPlugin

            class KotlinBoundaryHost : Plugin.Host {
              override val host: Host = object : Host {
                override fun echo(bytes: UByteArray, count: UInt): UByteArray =
                    ubyteArrayOf(bytes[0], count.toUByte())
              }
            }

            object KotlinBoundaryRunner {
              fun build(builder: WasmPlugin.Builder, host: Plugin.Host): WasmPlugin =
                  Plugin.build(builder, host)

              fun run(plugin: WasmPlugin): UByteArray =
                  Plugin.guest(plugin).api.run()

              fun version(): String = Plugin.WIT_PACKAGE_VERSION
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val classes = compileKotlin(generatedFile, hostFile)

        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val host =
                loader
                    .loadClass("example.generated.boundary.KotlinBoundaryHost")
                    .getDeclaredConstructor()
                    .newInstance()
            val pluginBuilder =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (import "host" "echo"
                                (func ${'$'}echo (param i32 i32 i32 i32)))
                              (memory (export "memory") 1)
                              (global ${'$'}heap (mut i32) (i32.const 512))
                              (data (i32.const 16) "\07\08")
                              (func (export "canonical_abi_realloc")
                                (param ${'$'}old i32) (param ${'$'}old_size i32)
                                (param ${'$'}align i32) (param ${'$'}new_size i32)
                                (result i32)
                                (local ${'$'}ptr i32)
                                (local.set ${'$'}ptr (global.get ${'$'}heap))
                                (global.set ${'$'}heap
                                  (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                                (local.get ${'$'}ptr))
                              (func ${'$'}run (result i32)
                                (call ${'$'}echo
                                  (i32.const 16) (i32.const 2)
                                  (i32.const 9) (i32.const 32))
                                (i32.const 32))
                              (export "api.run" (func ${'$'}run))
                            )
                            """
                                .trimIndent()
                        )
                    )

            val runnerType = loader.loadClass("example.generated.boundary.KotlinBoundaryRunner")
            val runner = runnerType.getField("INSTANCE").get(null)
            val hostType = loader.loadClass("example.generated.boundary.Plugin\$Host")
            val plugin =
                runnerType
                    .getMethod("build", WasmPlugin.Builder::class.java, hostType)
                    .invoke(runner, pluginBuilder, host) as WasmPlugin
            val result = singleMethod(runnerType, "run").invoke(runner, plugin)

            assertArrayEquals(byteArrayOf(7, 9), result as ByteArray)
            assertEquals("1.0.0", runnerType.getMethod("version").invoke(runner))
        }
    }

    @Test
    fun generatedKotlinGuestProxyLetsHostConsumePluginOwnedMovieDetails() {
        val witPackage =
            WitPackage.parse(
                """
                package example:movies@1.0.0;
                interface movies {
                  record movie-detail {
                    id: u64,
                    title: string,
                  }
                  get-movie-details: func(id: u64) -> movie-detail;
                }
                world plugin {
                  export movies;
                }
                """
                    .trimIndent()
            )
        val generatedFile = tempDir.resolve("GeneratedMoviePlugin.kt")
        val runnerFile = tempDir.resolve("MoviePluginRunner.kt")
        Files.writeString(
            generatedFile,
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.movies")
                .withPluginHelpers(true)
                .build()
                .generate(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            runnerFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.movies

            import uk.shusek.krwa.component.WasiComponentInvoker
            import uk.shusek.krwa.component.WasmPlugin

            object MoviePluginRunner {
              fun build(builder: WasmPlugin.Builder): WasmPlugin =
                  Plugin.build(builder, object : Plugin.Host {})

              fun movieDetails(plugin: WasmPlugin, id: ULong): Movies.MovieDetail =
                  Plugin.guest(plugin).movies.getMovieDetails(id)

              fun movieDetailsViaInvoker(invoker: WasiComponentInvoker, id: ULong): Movies.MovieDetail =
                  Plugin.guest(invoker).movies.getMovieDetails(id)
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val classes = compileKotlin(generatedFile, runnerFile)

        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val runnerType = loader.loadClass("example.generated.movies.MoviePluginRunner")
            val runner = runnerType.getField("INSTANCE").get(null)
            val pluginBuilder =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (memory (export "memory") 1)
                              (data (i32.const 96) "The Movie")
                              (func ${'$'}get_movie_details (param ${'$'}id i64) (result i32)
                                (i64.store (i32.const 32) (local.get ${'$'}id))
                                (i32.store (i32.const 40) (i32.const 96))
                                (i32.store (i32.const 44) (i32.const 9))
                                (i32.const 32))
                              (export "movies.get-movie-details" (func ${'$'}get_movie_details))
                            )
                            """
                                .trimIndent()
                        )
                    )
            val plugin =
                runnerType
                    .getMethod("build", WasmPlugin.Builder::class.java)
                    .invoke(runner, pluginBuilder) as WasmPlugin
            val detail = singleMethod(runnerType, "movieDetails").invoke(runner, plugin, 2L)
            val invokerDetail =
                singleMethod(runnerType, "movieDetailsViaInvoker").invoke(runner, plugin, 3L)

            assertMovieDetail(detail, 2L, "The Movie")
            assertMovieDetail(invokerDetail, 3L, "The Movie")
        }
    }

    @Test
    fun generatedSuspendGuestProxyLetsHostConsumePluginOwnedMovieDetails() {
        val witPackage =
            WitPackage.parse(
                """
                package example:movies@1.0.0;
                interface movies {
                  record movie-detail {
                    id: u64,
                    title: string,
                  }
                  get-movie-details: async func(id: u64) -> movie-detail;
                }
                world plugin {
                  export movies;
                }
                """
                    .trimIndent()
            )
        val generatedFile = tempDir.resolve("GeneratedSuspendMoviePlugin.kt")
        val runnerFile = tempDir.resolve("SuspendMoviePluginRunner.kt")
        val generated =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.suspendmovies")
                .withPluginHelpers(true)
                .build()
                .generate()
        assertTrue(generated.contains("public suspend fun getMovieDetails(id: ULong): MovieDetail"))
        Files.writeString(generatedFile, generated, StandardCharsets.UTF_8)
        Files.writeString(
            runnerFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.suspendmovies

            import uk.shusek.krwa.component.WasmPlugin

            object SuspendMoviePluginRunner {
              fun build(builder: WasmPlugin.Builder): WasmPlugin =
                  Plugin.build(builder, object : Plugin.Host {})

              suspend fun movieDetails(plugin: WasmPlugin, id: ULong): Movies.MovieDetail =
                  Plugin.guest(plugin).movies.getMovieDetails(id)
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val classes = compileKotlin(generatedFile, runnerFile)

        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val runnerType =
                loader.loadClass("example.generated.suspendmovies.SuspendMoviePluginRunner")
            val runner = runnerType.getField("INSTANCE").get(null)
            val pluginBuilder =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (memory (export "memory") 1)
                              (data (i32.const 96) "The Movie")
                              (func ${'$'}get_movie_details (param ${'$'}id i64) (result i32)
                                (i64.store (i32.const 32) (local.get ${'$'}id))
                                (i32.store (i32.const 40) (i32.const 96))
                                (i32.store (i32.const 44) (i32.const 9))
                                (i32.const 32))
                              (export "movies.get-movie-details" (func ${'$'}get_movie_details))
                            )
                            """
                                .trimIndent()
                        )
                    )
            val plugin =
                runnerType
                    .getMethod("build", WasmPlugin.Builder::class.java)
                    .invoke(runner, pluginBuilder) as WasmPlugin
            val continuation = RecordingContinuation(Job())
            val detail =
                singleMethod(runnerType, "movieDetails").invoke(runner, plugin, 2L, continuation)

            assertTrue(detail === COROUTINE_SUSPENDED)
            assertMovieDetail(continuation.awaitSuccess(2, TimeUnit.SECONDS), 2L, "The Movie")
        }
    }

    @Test
    fun generatedSuspendGuestProxyPropagatesCancellationToRunningPluginCall() {
        val witPackage =
            WitPackage.parse(
                """
                package example:cancellation@1.0.0;
                interface host {
                  wait-forever: func() -> u64;
                }
                interface api {
                  wait-forever: async func() -> u64;
                }
                world plugin {
                  import host;
                  export api;
                }
                """
                    .trimIndent()
            )
        val generatedFile = tempDir.resolve("GeneratedCancellationPlugin.kt")
        val runnerFile = tempDir.resolve("CancellationPluginRunner.kt")
        Files.writeString(
            generatedFile,
            KotlinWitBindings.builder(witPackage)
                .withPackageName("example.generated.cancellation")
                .withPluginHelpers(true)
                .build()
                .generate(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            runnerFile,
            """
            @file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

            package example.generated.cancellation

            import java.util.concurrent.CountDownLatch
            import java.util.concurrent.atomic.AtomicBoolean
            import uk.shusek.krwa.component.WasmPlugin

            class CancellationHost(
              private val started: CountDownLatch,
              private val interrupted: AtomicBoolean,
            ) : Plugin.Host {
              override val host: Host = object : Host {
                override fun waitForever(): ULong {
                  started.countDown()
                  try {
                    while (true) {
                      Thread.sleep(10_000L)
                    }
                  } catch (e: InterruptedException) {
                    interrupted.set(true)
                    throw e
                  }
                }
              }
            }

            object CancellationPluginRunner {
              fun build(builder: WasmPlugin.Builder, host: Plugin.Host): WasmPlugin =
                  Plugin.build(builder, host)

              suspend fun waitForever(plugin: WasmPlugin): ULong =
                  Plugin.guest(plugin).api.waitForever()
            }
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val classes = compileKotlin(generatedFile, runnerFile)

        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val runnerType =
                loader.loadClass("example.generated.cancellation.CancellationPluginRunner")
            val runner = runnerType.getField("INSTANCE").get(null)
            val hostType = loader.loadClass("example.generated.cancellation.Plugin\$Host")
            val started = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val host =
                loader
                    .loadClass("example.generated.cancellation.CancellationHost")
                    .getDeclaredConstructor(CountDownLatch::class.java, AtomicBoolean::class.java)
                    .newInstance(started, interrupted)
            val pluginBuilder =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            """
                            (module
                              (import "host" "wait-forever" (func ${'$'}host_wait (result i64)))
                              (memory (export "memory") 1)
                              (func ${'$'}wait_forever (result i64)
                                (call ${'$'}host_wait))
                              (export "api.wait-forever" (func ${'$'}wait_forever))
                            )
                            """
                                .trimIndent()
                        )
                    )
            val plugin =
                runnerType
                    .getMethod("build", WasmPlugin.Builder::class.java, hostType)
                    .invoke(runner, pluginBuilder, host) as WasmPlugin
            val job = Job()
            val continuation = RecordingContinuation(job)
            val suspended =
                singleMethod(runnerType, "waitForever").invoke(runner, plugin, continuation)

            assertTrue(suspended === COROUTINE_SUSPENDED)
            assertTrue(started.await(2, TimeUnit.SECONDS))

            job.cancel(CancellationException("test cancellation"))

            val failure = continuation.awaitFailure(2, TimeUnit.SECONDS)
            assertTrue(failure is CancellationException)
            assertTrue(interrupted.get())
        }
    }

    private fun compileKotlin(generatedFile: Path, usageFile: Path): Path {
        val outputDir = tempDir.resolve("classes-${generatedFile.fileName}")
        Files.createDirectories(outputDir)
        val stderr = ByteArrayOutputStream()
        val exitCode =
            K2JVMCompiler()
                .exec(
                    PrintStream(stderr, true, StandardCharsets.UTF_8),
                    "-no-reflect",
                    "-no-stdlib",
                    "-jvm-target",
                    "25",
                    "-cp",
                    kotlinCompileClasspath(),
                    "-d",
                    outputDir.toString(),
                    generatedFile.toString(),
                    usageFile.toString(),
                )

        assertEquals(ExitCode.OK, exitCode, stderr.toString(StandardCharsets.UTF_8))
        return outputDir
    }

    private fun compileWasmWasi(generated: String, usage: String) {
        val projectDir = tempDir.resolve("wasm-wasi-compile")
        copyTestFixtureProject("wasm-wasi-generated-compile", projectDir)
        val sourceDir = projectDir.resolve("src/wasmWasiMain/kotlin/example/generated/suvio")
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve("Generated.kt"), generated, StandardCharsets.UTF_8)
        Files.writeString(sourceDir.resolve("UseGenerated.kt"), usage, StandardCharsets.UTF_8)

        val repoRoot = repoRoot()
        val gradlew =
            repoRoot.resolve(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        val outputFile = projectDir.resolve("gradle-output.log")
        val process =
            ProcessBuilder(
                    gradlew.toString(),
                    "--no-daemon",
                    "--stacktrace",
                    "-q",
                    "compileKotlinWasmWasi",
                )
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        val output =
            if (Files.exists(outputFile)) Files.readString(outputFile, StandardCharsets.UTF_8) else ""
        if (!finished) {
            process.destroyForcibly()
        }
        val failureOutput =
            if (finished && process.exitValue() == 0) {
                output
            } else {
                compileFailureOutput(output, sourceDir)
            }
        assertTrue(finished, failureOutput)
        assertEquals(0, process.exitValue(), failureOutput)
    }

    private fun compileFailureOutput(output: String, sourceDir: Path): String {
        val result = StringBuilder(output)
        appendSourceExcerpts(result, output, sourceDir.resolve("Generated.kt"))
        appendSourceExcerpts(result, output, sourceDir.resolve("UseGenerated.kt"))
        return result.toString()
    }

    private fun appendSourceExcerpts(result: StringBuilder, output: String, sourceFile: Path) {
        if (!Files.exists(sourceFile)) {
            return
        }
        val fileName = sourceFile.fileName.toString()
        val lineNumbers =
            Regex("${Regex.escape(fileName)}:(\\d+):")
                .findAll(output)
                .map { it.groupValues[1].toInt() }
                .distinct()
                .take(8)
                .toList()
        if (lineNumbers.isEmpty()) {
            return
        }
        val source = Files.readAllLines(sourceFile, StandardCharsets.UTF_8)
        result.append("\n\n").append(fileName).append(" excerpts:")
        for (lineNumber in lineNumbers) {
            val start = maxOf(1, lineNumber - 2)
            val end = minOf(source.size, lineNumber + 2)
            result.append("\n-- ").append(fileName).append(":").append(lineNumber).append(" --\n")
            for (current in start..end) {
                result.append(current.toString().padStart(5))
                    .append(": ")
                    .append(source[current - 1])
                    .append('\n')
            }
        }
    }

    private fun repoRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate.parent != null && !Files.exists(candidate.resolve("gradlew"))) {
            candidate = candidate.parent
        }
        return candidate
    }

    private fun singleMethod(type: Class<*>, namePrefix: String): Method {
        for (method in type.methods) {
            if (method.name == namePrefix || method.name.startsWith("$namePrefix-")) {
                return method
            }
        }
        throw AssertionError("missing method $namePrefix on ${type.name}")
    }

    private fun assertMovieDetail(detail: Any?, expectedId: Long, expectedTitle: String) {
        requireNotNull(detail) { "missing movie detail" }
        val id = detail.javaClass.getDeclaredField("id")
        val title = detail.javaClass.getDeclaredField("title")
        id.isAccessible = true
        title.isAccessible = true

        assertEquals(expectedId, id.getLong(detail))
        assertEquals(expectedTitle, title.get(detail))
    }

    private class RecordingContinuation(
        override val context: CoroutineContext = EmptyCoroutineContext
    ) : Continuation<Any?> {
        private val completed = CountDownLatch(1)
        private val result = AtomicReference<Result<Any?>>()

        override fun resumeWith(result: Result<Any?>) {
            this.result.set(result)
            completed.countDown()
        }

        fun awaitFailure(timeout: Long, unit: TimeUnit): Throwable {
            if (!completed.await(timeout, unit)) {
                throw AssertionError("continuation was not resumed")
            }
            return result.get().exceptionOrNull()
                ?: throw AssertionError("continuation completed successfully")
        }

        fun awaitSuccess(timeout: Long, unit: TimeUnit): Any? {
            if (!completed.await(timeout, unit)) {
                throw AssertionError("continuation was not resumed")
            }
            return result.get().getOrThrow()
        }
    }

    private fun richPluginPackage(): WitPackage =
        WitPackage.parse(
            """
            package example:resources;
            interface db {
              record request {
                body: list<u8>,
                content-type: string,
              }
              enum status {
                ready,
                waiting-room,
              }
              flags permissions {
                read,
                write,
                network-access,
              }
              variant outcome {
                accepted(request),
                denied(string),
                empty,
              }
              echo: func(request: request) -> request;
              decide: func(request: request) -> outcome;
              maybe: func(flag: bool) -> option<string>;
              permissions: func() -> permissions;
              status: func() -> status;
              resource blob {
                constructor(init: list<u8>);
                read: func(n: u32) -> list<u8>;
                describe: func(n: u32) -> tuple<blob, u32, string, bool>;
              }
            }
            """
                .trimIndent()
        )

    private fun kotlinCompileClasspath(): String {
        val paths = LinkedHashSet<Path>()
        paths.add(codeSource(KotlinWitBindings::class.java))
        paths.add(codeSource(Unit::class.java))
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))
    }

    private fun codeSource(type: Class<*>): Path =
        Path.of(type.protectionDomain.codeSource.location.toURI())
}

private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
