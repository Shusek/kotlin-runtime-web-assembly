package uk.shusek.krwa.gradle.component

import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class KrwaComponentModelPluginTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun configureNestedGradleHeap() {
        tempDir.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED
            org.gradle.workers.max=1
            org.gradle.daemon=false
            kotlin.daemon.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
            """.trimIndent(),
        )
    }

    @Test
    fun `lists KRWA tasks under KRWA task group`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("tasks", "--group", "krwa")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("generateKrwaKotlinWitBindings"), result.output)
        assertTrue(result.output.contains("packageKrwaComponent"), result.output)
    }

    @Test
    fun `registers isolated tasks for named components`() {
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            """.trimIndent(),
        )
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                components {
                    create("catalog")
                    create("playback")
                    create("settings")
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("tasks", "--group", "krwa")
            .withPluginClasspath()
            .build()

        listOf("Catalog", "Playback", "Settings").forEach { surface ->
            assertTrue(result.output.contains("generate${surface}KrwaKotlinWitBindings"), result.output)
            assertTrue(result.output.contains("package${surface}KrwaComponent"), result.output)
        }
    }

    @Test
    fun `resolves a named component core module from a Kotlin Wasm target`() {
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            """.trimIndent(),
        )
        tempDir.resolve("build.gradle.kts").writeText(
            """
            import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

            plugins {
                kotlin("multiplatform") version "2.4.0"
                id("uk.shusek.krwa.component-model")
            }

            kotlin {
                @OptIn(ExperimentalWasmDsl::class)
                wasmWasi("catalogWasm") {
                    binaries.executable()
                }
            }

            krwaComponentModel {
                component("catalog") {
                    coreModule.fromKotlinWasm("catalogWasm")
                }
            }

            tasks.register("verifyCatalogCoreModule") {
                dependsOn("prepareCatalogKrwaCoreModule")
                doLast {
                    val core = krwaComponentModel.components.named("catalog").get().coreModule.file.get().asFile
                    check(core.invariantSeparatorsPath.endsWith("/krwa/core-modules/catalog.wasm")) {
                        "Unexpected Kotlin/Wasm output: ${'$'}core"
                    }
                    check(core.isFile && core.length() > 0L) {
                        "Prepared Kotlin/Wasm output is missing or empty: ${'$'}core"
                    }
                }
            }
            """.trimIndent(),
        )
        val sourceDirectory = tempDir.resolve("src/commonMain/kotlin")
        sourceDirectory.toFile().mkdirs()
        sourceDirectory.resolve("Main.kt").writeText("fun main() = Unit")

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("verifyCatalogCoreModule", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyCatalogCoreModule")?.outcome)
    }

    @Test
    fun `generates Kotlin WIT bindings through Gradle plugin`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                witFile.set(layout.projectDirectory.file("plugin.wit"))
                bindingsPackage.set("example.generated")
                bindingsOutputFile.set(layout.buildDirectory.file("generated/PluginBindings.kt"))
                pluginHelpers.set(true)
            }
            """.trimIndent(),
        )
        tempDir.resolve("plugin.wit").writeText(
            """
            package example:plugin;

            world plugin {
              export run: func() -> result<string, string>;
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("generateKrwaKotlinWitBindings", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateKrwaKotlinWitBindings")?.outcome)
        val generated = tempDir.resolve("build/generated/PluginBindings.kt")
        assertTrue(Files.exists(generated))
        assertTrue(generated.readText().contains("public object Plugin"))
        assertTrue(generated.readText().contains("public fun guest(plugin:"))
    }

    @Test
    fun `generates Kotlin WIT bindings from WIT package directory`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                witPackageDirectory.set(layout.projectDirectory.dir("wit"))
                bindingsPackage.set("example.generated")
                bindingsOutputFile.set(layout.buildDirectory.file("generated/PluginBindings.kt"))
            }
            """.trimIndent(),
        )
        tempDir.resolve("wit").toFile().mkdirs()
        tempDir.resolve("wit/plugin.wit").writeText(
            """
            package example:plugin;

            world plugin {
              export run: func();
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("generateKrwaKotlinWitBindings", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateKrwaKotlinWitBindings")?.outcome)
        val generated = tempDir.resolve("build/generated/PluginBindings.kt")
        assertTrue(Files.exists(generated))
        assertTrue(generated.readText().contains("public object Plugin"))
    }

    @Test
    fun `generates split Kotlin WIT bindings through Gradle plugin`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                witFile.set(layout.projectDirectory.file("plugin.wit"))
                bindingsPackage.set("example.generated.split")
                bindingsOutputDirectory.set(layout.buildDirectory.dir("generated/wit-bindings/main/kotlin"))
                bindingsSplitFiles.set(true)
                guestExports.set(true)
            }
            """.trimIndent(),
        )
        tempDir.resolve("plugin.wit").writeText(
            """
            package example:plugin;

            interface api {
              run: async func(value: u32) -> u32;
            }

            world plugin {
              export api;
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("generateKrwaKotlinWitBindings", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateKrwaKotlinWitBindings")?.outcome)
        val generatedPackage = tempDir.resolve("build/generated/wit-bindings/main/kotlin/example/generated/split")
        assertTrue(Files.exists(generatedPackage.resolve("RuntimeTypes.kt")))
        assertTrue(Files.exists(generatedPackage.resolve("Api.kt")))
        assertTrue(Files.exists(generatedPackage.resolve("Plugin.kt")))
        assertTrue(Files.exists(generatedPackage.resolve("KrwaGuestExports.kt")))
    }

    @Test
    fun `changing bindings package removes generated files from the previous package`() {
        tempDir.resolve("settings.gradle.kts").writeText(
            """pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""",
        )
        val buildFile = tempDir.resolve("build.gradle.kts")
        fun writeBuild(packageName: String) {
            buildFile.writeText(
                """
                plugins {
                    id("uk.shusek.krwa.component-model")
                }

                krwaComponentModel {
                    witFile.set(layout.projectDirectory.file("plugin.wit"))
                    bindingsPackage.set("$packageName")
                    bindingsOutputDirectory.set(layout.buildDirectory.dir("generated/wit-bindings/main/kotlin"))
                }
                """.trimIndent(),
            )
        }
        tempDir.resolve("plugin.wit").writeText(
            """
            package example:plugin;

            world plugin {
              export run: func();
            }
            """.trimIndent(),
        )
        writeBuild("example.generated.old")

        GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("generateKrwaKotlinWitBindings", "--stacktrace")
            .withPluginClasspath()
            .build()

        val outputRoot = tempDir.resolve("build/generated/wit-bindings/main/kotlin")
        val oldOutput = outputRoot.resolve("example/generated/old/KrwaComponentBindings.kt")
        assertTrue(Files.exists(oldOutput))

        writeBuild("example.generated.new")
        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("generateKrwaKotlinWitBindings", "--stacktrace")
            .withPluginClasspath()
            .build()

        val newOutput = outputRoot.resolve("example/generated/new/KrwaComponentBindings.kt")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateKrwaKotlinWitBindings")?.outcome)
        assertFalse(Files.exists(oldOutput))
        assertTrue(Files.exists(newOutput))
    }

    @Test
    fun `auto wires generated Kotlin WIT bindings into configured Kotlin source set`() {
        val componentModelJar = componentModelJvmJar()
        tempDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    mavenCentral()
                }
            }
            rootProject.name = "krwa-auto-source-set-test"
            """.trimIndent(),
        )
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.4.0"
                id("uk.shusek.krwa.component-model")
            }

            kotlin {
                jvm()

                sourceSets {
                    jvmMain.dependencies {
                        implementation(files("${componentModelJar.toString().replace("\\", "/")}"))
                    }
                }
            }

            krwaComponentModel {
                witFile.set(layout.projectDirectory.file("plugin.wit"))
                bindingsPackage.set("example.generated")
                bindingsSourceSetName.set("jvmMain")
                bindingsOutputDirectory.set(layout.buildDirectory.dir("generated/wit-bindings/jvmMain/kotlin"))
                bindingsOutputFileName.set("PluginBindings.kt")
                autoWireKotlinSourceSet.set(true)
            }
            """.trimIndent(),
        )
        tempDir.resolve("plugin.wit").writeText(
            """
            package example:plugin;

            world plugin {
              export run: func();
            }
            """.trimIndent(),
        )
        val sourceDir = tempDir.resolve("src/jvmMain/kotlin/example")
        sourceDir.toFile().mkdirs()
        sourceDir.resolve("Usage.kt").writeText(
            """
            package example

            import example.generated.Plugin

            object TestGuest : Plugin.Guest {
                override fun run() = Unit
            }

            fun generatedGuest(): Plugin.Guest = TestGuest
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("compileKotlinJvm", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateKrwaKotlinWitBindings")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlinJvm")?.outcome)
    }

    private fun componentModelJvmJar(): java.nio.file.Path {
        val projectRoot = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve("settings.gradle.kts")) }
        val libs = projectRoot.resolve("modules/component-model/build/libs")
        return Files.list(libs).use { files ->
            files
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("component-model-jvm-") &&
                        name.endsWith(".jar") &&
                        !name.endsWith("-sources.jar")
                }
                .findFirst()
                .orElseThrow { AssertionError("Expected component-model JVM jar in $libs") }
        }
    }

    @Test
    fun `packages WebAssembly component through Gradle plugin`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                componentWitFile.set(layout.projectDirectory.file("plugin.wit"))
                componentWorld.set("plugin")
                componentCoreModuleFile.set(layout.projectDirectory.file("plugin.core.wasm"))
                componentOutputFile.set(layout.buildDirectory.file("component/plugin.wasm"))
            }
            """.trimIndent(),
        )
        tempDir.resolve("plugin.wit").writeText(
            """
            package example:component;

            interface api {
              len: func(input: string) -> u32;
            }

            world plugin {
              export api;
            }
            """.trimIndent(),
        )
        Files.write(
            tempDir.resolve("plugin.core.wasm"),
            Wat2Wasm.parse(
                """
                (module
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func (export "canonical_abi_realloc")
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (local.get ${'$'}len))
                  (export "len" (func ${'$'}len))
                  (export "api.len" (func ${'$'}len))
                  (export "example:component/api#len" (func ${'$'}len))
                )
                """.trimIndent(),
            ),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("packageKrwaComponent", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageKrwaComponent")?.outcome)
        val component = tempDir.resolve("build/component/plugin.wasm")
        assertTrue(component.readBytes().isNotEmpty(), "Expected packaged component to be created.")
    }

    @Test
    fun `packages WebAssembly component from WIT package directory through Gradle plugin`() {
        tempDir.resolve("settings.gradle.kts").writeText("""pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""")
        tempDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("uk.shusek.krwa.component-model")
            }

            krwaComponentModel {
                componentWitPackageDirectory.set(layout.projectDirectory.dir("wit"))
                componentWorld.set("plugin")
                componentCoreModuleFile.set(layout.projectDirectory.file("plugin.core.wasm"))
                componentOutputFile.set(layout.buildDirectory.file("component/plugin.wasm"))
            }
            """.trimIndent(),
        )
        tempDir.resolve("wit").toFile().mkdirs()
        tempDir.resolve("wit/plugin.wit").writeText(
            """
            package example:component;

            interface api {
              len: func(input: string) -> u32;
            }

            world plugin {
              export api;
            }
            """.trimIndent(),
        )
        Files.write(
            tempDir.resolve("plugin.core.wasm"),
            Wat2Wasm.parse(
                """
                (module
                  (memory (export "memory") 1)
                  (global ${'$'}heap (mut i32) (i32.const 1024))
                  (func (export "canonical_abi_realloc")
                    (param ${'$'}old i32) (param ${'$'}old_size i32)
                    (param ${'$'}align i32) (param ${'$'}new_size i32)
                    (result i32)
                    (local ${'$'}ptr i32)
                    (local.set ${'$'}ptr (global.get ${'$'}heap))
                    (global.set ${'$'}heap
                      (i32.add (global.get ${'$'}heap) (local.get ${'$'}new_size)))
                    (local.get ${'$'}ptr))
                  (func ${'$'}len (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                    (local.get ${'$'}len))
                  (export "len" (func ${'$'}len))
                  (export "api.len" (func ${'$'}len))
                  (export "example:component/api#len" (func ${'$'}len))
                )
                """.trimIndent(),
            ),
        )

        val result = GradleRunner.create()
            .withProjectDir(tempDir.toFile())
            .withReleaseGateArguments("packageKrwaComponent", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageKrwaComponent")?.outcome)
        val component = tempDir.resolve("build/component/plugin.wasm")
        assertTrue(component.readBytes().isNotEmpty(), "Expected packaged component to be created.")
    }
}
