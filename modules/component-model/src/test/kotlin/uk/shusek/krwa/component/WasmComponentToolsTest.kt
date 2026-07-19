package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WasmComponentToolsTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun preservesExistingOutputAndCleansStagingAfterValidationFailure() {
        val outputDirectory = tempDir.resolve("generated")
        val output = outputDirectory.resolve("plugin.component.wasm")
        val sentinel = "previous-valid-component".toByteArray(StandardCharsets.UTF_8)
        Files.createDirectories(outputDirectory)
        Files.write(output, sentinel)
        val entriesBefore = directoryEntries(outputDirectory)

        val failure =
            assertThrows(ComponentModelException::class.java) {
                WasmComponentTools.writeOutputAtomically(output.toOkioPath()) { stagedOutput ->
                    FileSystem.SYSTEM.write(stagedOutput) {
                        writeUtf8("replacement-awaiting-validation")
                    }
                    throw ComponentModelException("forced validation failure")
                }
            }

        assertEquals("forced validation failure", failure.message)
        assertArrayEquals(sentinel, Files.readAllBytes(output))
        assertEquals(entriesBefore, directoryEntries(outputDirectory))
    }

    @Test
    fun atomicallyReplacesOutputAndCleansStagingAfterSuccess() {
        val outputDirectory = tempDir.resolve("generated")
        val output = outputDirectory.resolve("plugin.component.wasm")
        val replacement = "validated-replacement".toByteArray(StandardCharsets.UTF_8)
        Files.createDirectories(outputDirectory)
        Files.writeString(output, "previous-valid-component", StandardCharsets.UTF_8)

        val result =
            WasmComponentTools.writeOutputAtomically(output.toOkioPath()) { stagedOutput ->
                FileSystem.SYSTEM.write(stagedOutput) { write(replacement) }
            }

        assertEquals(output.toOkioPath(), result)
        assertArrayEquals(replacement, Files.readAllBytes(output))
        assertEquals(setOf(output.fileName.toString()), directoryEntries(outputDirectory))
    }

    private fun directoryEntries(directory: Path): Set<String> =
        Files.list(directory).use { paths ->
            paths.map { path -> path.fileName.toString() }.toList().toSet()
        }

    private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
}
