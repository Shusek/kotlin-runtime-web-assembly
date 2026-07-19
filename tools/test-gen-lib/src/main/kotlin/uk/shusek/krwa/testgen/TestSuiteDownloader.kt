package uk.shusek.krwa.testgen

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import net.lingala.zip4j.ZipFile

open class TestSuiteDownloader {
    @Throws(IOException::class)
    open fun downloadTestsuite(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        testSuiteFolder: File,
    ) {
        require(
            testSuiteRepo == DEFAULT_WASM_TEST_SUITE_REPOSITORY &&
                testSuiteRepoRef == DEFAULT_WASM_TEST_SUITE_REVISION
        ) {
            "WebAssembly testsuite downloads must use the repository's pinned revision and SHA-256"
        }
        prepareWasmTestsuite(
            testSuiteRepo = testSuiteRepo,
            testSuiteRepoRef = testSuiteRepoRef,
            archiveSha256 = DEFAULT_WASM_TEST_SUITE_ARCHIVE_SHA256,
            testSuiteFolder = testSuiteFolder,
            offline = false,
            forceExtract = false,
        )
    }
}

internal fun prepareWasmTestsuite(
    testSuiteRepo: String,
    testSuiteRepoRef: String,
    archiveSha256: String,
    testSuiteFolder: File,
    offline: Boolean,
    forceExtract: Boolean,
) {
    require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) {
        "archiveSha256 must be a lowercase SHA-256 value"
    }
    val parent = testSuiteFolder.parentFile ?: File(".")
    check(parent.isDirectory || parent.mkdirs()) {
        "Failed to create WebAssembly testsuite parent directory: $parent"
    }
    val archive = parent.resolve("wasm-testsuite-$testSuiteRepoRef.zip")
    prepareWasmTestsuiteArchive(
        testSuiteRepo = testSuiteRepo,
        testSuiteRepoRef = testSuiteRepoRef,
        archiveSha256 = archiveSha256,
        archive = archive,
        offline = offline,
    )

    val expectedMarker = "$testSuiteRepo\n$testSuiteRepoRef\n$archiveSha256\n"
    val marker = testSuiteFolder.resolve(WASM_TEST_SUITE_MARKER)
    if (
        !forceExtract &&
            testSuiteFolder.listFiles()?.any { it.isFile && it.extension == "wast" } == true &&
            marker.isFile &&
            marker.readText() == expectedMarker
    ) {
        return
    }

    val extractionDirectory = parent.resolve("${testSuiteFolder.name}.extract.tmp")
    try {
        extractionDirectory.deleteRecursively()
        check(extractionDirectory.mkdirs()) {
            "Failed to create temporary extraction directory: $extractionDirectory"
        }
        ZipFile(archive).use { zip ->
            zip.extractAll(extractionDirectory.absolutePath)
        }
        val repositoryName = testSuiteRepo.substringAfterLast('/').removeSuffix(".git")
        val extracted = extractionDirectory.resolve("$repositoryName-$testSuiteRepoRef")
        check(extracted.listFiles()?.any { it.isFile && it.extension == "wast" } == true) {
            "Pinned WebAssembly testsuite archive did not contain root .wast files"
        }
        extracted.resolve(WASM_TEST_SUITE_MARKER).writeText(expectedMarker)
        if (testSuiteFolder.exists() && !testSuiteFolder.deleteRecursively()) {
            throw IOException("Failed to replace incomplete testsuite: $testSuiteFolder")
        }
        moveWasmTestsuiteAtomically(extracted, testSuiteFolder)
    } finally {
        extractionDirectory.deleteRecursively()
    }
}

private fun prepareWasmTestsuiteArchive(
    testSuiteRepo: String,
    testSuiteRepoRef: String,
    archiveSha256: String,
    archive: File,
    offline: Boolean,
) {
    if (archive.isFile && wasmTestsuiteSha256(archive) == archiveSha256) {
        return
    }
    if (offline) {
        throw IllegalStateException(
            "Pinned WebAssembly testsuite archive is missing or failed SHA-256 verification at " +
                "${archive.absolutePath}. Run " +
                "'./gradlew --no-daemon prepareReleaseDependencies' while online, then retry " +
                "releaseGate with --offline.",
        )
    }

    val temporary = archive.resolveSibling("${archive.name}.download.tmp")
    try {
        Files.deleteIfExists(temporary.toPath())
        val url = URI.create("$testSuiteRepo/archive/$testSuiteRepoRef.zip").toURL()
        val connection = url.openConnection()
        connection.connectTimeout = WASM_TEST_SUITE_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = WASM_TEST_SUITE_READ_TIMEOUT_MILLIS
        try {
            connection.getInputStream().use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
        val actualSha256 = wasmTestsuiteSha256(temporary)
        check(actualSha256 == archiveSha256) {
            "WebAssembly testsuite archive SHA-256 mismatch: expected $archiveSha256, " +
                "got $actualSha256"
        }
        moveWasmTestsuiteAtomically(temporary, archive)
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun wasmTestsuiteSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
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

private fun moveWasmTestsuiteAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal const val DEFAULT_WASM_TEST_SUITE_REPOSITORY =
    "https://github.com/WebAssembly/testsuite"
internal const val DEFAULT_WASM_TEST_SUITE_REVISION =
    "88e97b0f742f4c3ee01fea683da130f344dd7b02"
internal const val DEFAULT_WASM_TEST_SUITE_ARCHIVE_SHA256 =
    "8dda64df353a3fbe38c3acdbcda4524eba951b53c7d4d1474ab86b0878f474e4"
private const val WASM_TEST_SUITE_MARKER = ".krwa-testsuite-source"
private const val WASM_TEST_SUITE_CONNECT_TIMEOUT_MILLIS = 30_000
private const val WASM_TEST_SUITE_READ_TIMEOUT_MILLIS = 5 * 60_000
