package uk.shusek.krwa.gradle.component

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class KrwaPackageWasmComponentTaskTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun configureProject() {
        tempDir.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED
            org.gradle.workers.max=1
            org.gradle.daemon=false
            """.trimIndent(),
        )
        tempDir.resolve("settings.gradle.kts").writeText(
            """pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }""",
        )
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
    }

    @Test
    fun `packages a large core module within an isolated worker heap and preserves sibling outputs`() {
        val largeSectionBytes = 24 * 1024 * 1024
        val coreModule = writeCoreModule()
        appendCustomSection(coreModule, "krwa-large-regression", largeSectionBytes)
        val outputDirectory = tempDir.resolve("build/component").createDirectories()
        val sibling = outputDirectory.resolve("owned-by-another-task.txt")
        sibling.writeText("keep me")

        val result = runner()
            .withReleaseGateArguments(
                "packageKrwaComponent",
                "--stacktrace",
                "-Dkrwa.component.packager.workerMaxHeap=1g",
            )
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageKrwaComponent")?.outcome)
        assertEquals("keep me", sibling.readText())
        assertTrue(
            Files.size(outputDirectory.resolve("plugin.wasm")) > largeSectionBytes,
            "Expected the large custom section to survive component packaging.",
        )
    }

    @Test
    fun `failed packaging preserves the previous output and sibling files`() {
        tempDir.resolve("plugin.core.wasm").writeBytes(byteArrayOf(0, 1, 2, 3))
        val outputDirectory = tempDir.resolve("build/component").createDirectories()
        val previousOutput = byteArrayOf(0x00, 0x61, 0x73, 0x6d)
        val output = outputDirectory.resolve("plugin.wasm")
        output.writeBytes(previousOutput)
        val sibling = outputDirectory.resolve("owned-by-another-task.txt")
        sibling.writeText("keep me")

        val result = runner()
            .withReleaseGateArguments("packageKrwaComponent", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("WasmComponentPackager failed"), result.output)
        assertArrayEquals(previousOutput, output.readBytes())
        assertEquals("keep me", sibling.readText())
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(tempDir.toFile())
        .withPluginClasspath()

    private fun writeCoreModule(): Path {
        val output = tempDir.resolve("plugin.core.wasm")
        Files.write(
            output,
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
        return output
    }

    private fun appendCustomSection(
        module: Path,
        name: String,
        contentsSize: Int,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val payloadSize = varUInt32Size(nameBytes.size) + nameBytes.size + contentsSize
        Files.newOutputStream(module, StandardOpenOption.APPEND).use { output ->
            output.write(0)
            output.writeVarUInt32(payloadSize)
            output.writeVarUInt32(nameBytes.size)
            output.write(nameBytes)
            val chunk = ByteArray(64 * 1024)
            var remaining = contentsSize
            while (remaining > 0) {
                val written = minOf(remaining, chunk.size)
                output.write(chunk, 0, written)
                remaining -= written
            }
        }
    }

    private fun OutputStream.writeVarUInt32(value: Int) {
        var remaining = value
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            write(next)
        } while (remaining != 0)
    }

    private fun varUInt32Size(value: Int): Int {
        var remaining = value
        var bytes = 0
        do {
            remaining = remaining ushr 7
            bytes++
        } while (remaining != 0)
        return bytes
    }
}
