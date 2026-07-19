package uk.shusek.krwa.testgen

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WasmTestSuitePreparationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun offlinePreparationFailsBeforeAttemptingNetwork() {
        val target = temporaryDirectory.resolve("output/wasm").toFile()

        val error =
            assertThrows(IllegalStateException::class.java) {
                prepareWasmTestsuite(
                    testSuiteRepo = "https://invalid.example/never-contact",
                    testSuiteRepoRef = "0123456789abcdef",
                    archiveSha256 = "0".repeat(64),
                    testSuiteFolder = target,
                    offline = true,
                    forceExtract = true,
                )
            }

        assertTrue(error.message.orEmpty().contains("prepareReleaseDependencies"))
        assertFalse(target.exists())
    }

    @Test
    fun verifiedArchiveIsMovedAndExtractedWithoutPartialFiles() {
        val revision = "0123456789abcdef"
        val repository = temporaryDirectory.resolve("remote/testsuite")
        val remoteArchive = repository.resolve("archive/$revision.zip")
        remoteArchive.parent.toFile().mkdirs()
        ZipOutputStream(remoteArchive.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("testsuite-$revision/address.wast"))
            zip.write("(module)".toByteArray())
            zip.closeEntry()
        }
        val expectedSha256 = sha256(remoteArchive)
        val target = temporaryDirectory.resolve("output/wasm").toFile()

        prepareWasmTestsuite(
            testSuiteRepo = repository.toUri().toString().removeSuffix("/"),
            testSuiteRepoRef = revision,
            archiveSha256 = expectedSha256,
            testSuiteFolder = target,
            offline = false,
            forceExtract = true,
        )

        assertTrue(target.resolve("address.wast").isFile)
        assertTrue(target.resolve(".krwa-testsuite-source").isFile)
        assertTrue(
            temporaryDirectory
                .resolve("output/wasm-testsuite-$revision.zip")
                .toFile()
                .isFile
        )
        assertFalse(
            temporaryDirectory
                .resolve("output/wasm-testsuite-$revision.zip.download.tmp")
                .toFile()
                .exists()
        )
        assertFalse(temporaryDirectory.resolve("output/wasm.extract.tmp").toFile().exists())
    }

    @Test
    fun recursiveWastClassificationMustMatchTheSuiteInBothDirections() {
        val suite = temporaryDirectory.resolve("suite").toFile()
        val nestedWast = suite.resolve("proposals/gc/array.wast")
        nestedWast.parentFile.mkdirs()
        nestedWast.writeText("(module)")
        suite.resolve("address.wast").writeText("(module)")

        assertDoesNotThrow {
            validateWastClassification(
                testsuiteFolder = suite,
                includedWasts = listOf("address.wast", "proposals/gc/array.wast"),
                excludedMalformedWasts = emptyList(),
                excludedInvalidWasts = emptyList(),
                excludedUninstantiableWasts = emptyList(),
                excludedUnlinkableWasts = emptyList(),
                excludedWasts = emptyList(),
                excludedRuntimeWasts = emptyList(),
            )
        }

        val error =
            assertThrows(IllegalStateException::class.java) {
                validateWastClassification(
                    testsuiteFolder = suite,
                    includedWasts = listOf("address.wast", "removed.wast"),
                    excludedMalformedWasts = emptyList(),
                    excludedInvalidWasts = emptyList(),
                    excludedUninstantiableWasts = emptyList(),
                    excludedUnlinkableWasts = emptyList(),
                    excludedWasts = emptyList(),
                    excludedRuntimeWasts = emptyList(),
                )
            }

        assertTrue(error.message.orEmpty().contains("proposals/gc/array.wast"))
        assertTrue(error.message.orEmpty().contains("removed.wast"))
    }

    @Test
    fun typedWastExclusionsModifyIncludedSuitesAndMustBeTheirSubset() {
        val suite = temporaryDirectory.resolve("typed-suite").toFile()
        val nestedWast = suite.resolve("proposals/gc/binary.wast")
        nestedWast.parentFile.mkdirs()
        nestedWast.writeText("(module)")
        suite.resolve("address.wast").writeText("(module)")

        assertDoesNotThrow {
            validateWastClassification(
                testsuiteFolder = suite,
                includedWasts = listOf("address.wast", "proposals/gc/binary.wast"),
                excludedMalformedWasts = listOf("proposals/gc/binary.wast"),
                excludedInvalidWasts = listOf("proposals/gc/binary.wast"),
                excludedUninstantiableWasts = emptyList(),
                excludedUnlinkableWasts = emptyList(),
                excludedWasts = emptyList(),
                excludedRuntimeWasts = emptyList(),
            )
        }

        val error =
            assertThrows(IllegalStateException::class.java) {
                validateWastClassification(
                    testsuiteFolder = suite,
                    includedWasts = listOf("address.wast"),
                    excludedMalformedWasts = emptyList(),
                    excludedInvalidWasts = listOf("proposals/gc/binary.wast"),
                    excludedUninstantiableWasts = emptyList(),
                    excludedUnlinkableWasts = emptyList(),
                    excludedWasts = listOf("proposals/gc/binary.wast"),
                    excludedRuntimeWasts = emptyList(),
                )
            }

        assertTrue(error.message.orEmpty().contains("excludedInvalidWasts"))
        assertTrue(error.message.orEmpty().contains("proposals/gc/binary.wast"))
    }

    @Test
    fun parserAndRuntimeWastExclusionsMustBeDisjoint() {
        val suite = temporaryDirectory.resolve("disjoint-suite").toFile()
        suite.mkdirs()
        suite.resolve("address.wast").writeText("(module)")

        val error =
            assertThrows(IllegalStateException::class.java) {
                validateWastClassification(
                    testsuiteFolder = suite,
                    includedWasts = emptyList(),
                    excludedMalformedWasts = emptyList(),
                    excludedInvalidWasts = emptyList(),
                    excludedUninstantiableWasts = emptyList(),
                    excludedUnlinkableWasts = emptyList(),
                    excludedWasts = listOf("address.wast"),
                    excludedRuntimeWasts = listOf("address.wast"),
                )
            }

        assertTrue(error.message.orEmpty().contains("must be disjoint"))
        assertTrue(error.message.orEmpty().contains("address.wast"))
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.toFile().inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
