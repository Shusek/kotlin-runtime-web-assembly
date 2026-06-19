package uk.shusek.krwa.gradle.component

import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class KrwaComponentModelPluginTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

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
            .withArguments("tasks", "--group", "krwa")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("generateKrwaKotlinWitBindings"), result.output)
        assertTrue(result.output.contains("packageKrwaComponent"), result.output)
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
            .withArguments("generateKrwaKotlinWitBindings", "--stacktrace")
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
            .withArguments("generateKrwaKotlinWitBindings", "--stacktrace")
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
            .withArguments("generateKrwaKotlinWitBindings", "--stacktrace")
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
            .withArguments("compileKotlinJvm", "--stacktrace")
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
            .withArguments("packageKrwaComponent", "--stacktrace")
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
            .withArguments("packageKrwaComponent", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageKrwaComponent")?.outcome)
        val component = tempDir.resolve("build/component/plugin.wasm")
        assertTrue(component.readBytes().isNotEmpty(), "Expected packaged component to be created.")
    }
}
