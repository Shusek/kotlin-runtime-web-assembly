package uk.shusek.krwa.wasitestgen

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WasiTestSuitePreparationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun offlinePreparationFailsBeforeAttemptingNetwork() {
        val target = temporaryDirectory.resolve("output/wasi").toFile()

        val error =
            assertThrows(IllegalStateException::class.java) {
                WasiTestGen.prepareTestsuite(
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
        val repository = temporaryDirectory.resolve("remote/wasi-testsuite")
        val remoteArchive = repository.resolve("archive/$revision.zip")
        remoteArchive.parent.toFile().mkdirs()
        ZipOutputStream(remoteArchive.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("wasi-testsuite-$revision/tests/fixture.wasm"))
            zip.write(byteArrayOf(0, 97, 115, 109))
            zip.closeEntry()
        }
        val expectedSha256 = sha256(remoteArchive)
        val target = temporaryDirectory.resolve("output/wasi").toFile()

        WasiTestGen.prepareTestsuite(
            testSuiteRepo = repository.toUri().toString().removeSuffix("/"),
            testSuiteRepoRef = revision,
            archiveSha256 = expectedSha256,
            testSuiteFolder = target,
            offline = false,
            forceExtract = true,
        )

        assertTrue(target.resolve("tests/fixture.wasm").isFile)
        assertTrue(
            temporaryDirectory
                .resolve("output/wasi-testsuite-$revision.zip")
                .toFile()
                .isFile
        )
        assertFalse(
            temporaryDirectory
                .resolve("output/wasi-testsuite-$revision.zip.download.tmp")
                .toFile()
                .exists()
        )
        assertFalse(temporaryDirectory.resolve("output/wasi.extract.tmp").toFile().exists())
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
