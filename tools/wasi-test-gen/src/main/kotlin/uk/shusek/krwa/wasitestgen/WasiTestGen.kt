package uk.shusek.krwa.wasitestgen

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Optional
import java.util.stream.Collectors
import net.lingala.zip4j.ZipFile

class WasiTestGenConfig {
    var testSuiteRepo: String = DEFAULT_WASI_TEST_SUITE_REPOSITORY
    var testSuiteRepoRef: String = DEFAULT_WASI_TEST_SUITE_REVISION
    var testSuiteFolder: String = ""
    var sourceDestinationFolder: String = ""
    var projectDirectory: String = ""
    var includes: List<String> = emptyList()
    var excludes: List<String> = emptyList()
}

object WasiTestGenCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: WasiTestGenCli <config.json>" }

        val preparation = readPreparationConfig(File(args[0]))
        val config = preparation.config
        WasiTestGen.executePrepared(
            config.testSuiteRepo,
            config.testSuiteRepoRef,
            preparation.archiveSha256,
            File(config.testSuiteFolder),
            File(config.sourceDestinationFolder),
            File(config.projectDirectory),
            config.includes,
            config.excludes,
            preparation.offline,
        )
    }
}

internal object WasiTestGenPrepareCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: WasiTestGenPrepareCli <config.json>" }

        val preparation = readPreparationConfig(File(args[0]))
        val config = preparation.config
        WasiTestGen.prepareTestsuite(
            testSuiteRepo = config.testSuiteRepo,
            testSuiteRepoRef = config.testSuiteRepoRef,
            archiveSha256 = preparation.archiveSha256,
            testSuiteFolder = File(config.testSuiteFolder),
            offline = preparation.offline,
            forceExtract = true,
        )
    }
}

object WasiTestGen {
    fun execute(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        testSuiteFolder: File,
        sourceDestinationFolder: File,
        projectDirectory: File,
        includes: List<String>,
        excludes: List<String>,
    ) {
        require(
            testSuiteRepo == DEFAULT_WASI_TEST_SUITE_REPOSITORY &&
                testSuiteRepoRef == DEFAULT_WASI_TEST_SUITE_REVISION
        ) {
            "WASI testsuite downloads must use the repository's pinned revision and SHA-256"
        }
        executePrepared(
            testSuiteRepo = testSuiteRepo,
            testSuiteRepoRef = testSuiteRepoRef,
            archiveSha256 = DEFAULT_WASI_TEST_SUITE_ARCHIVE_SHA256,
            testSuiteFolder = testSuiteFolder,
            sourceDestinationFolder = sourceDestinationFolder,
            projectDirectory = projectDirectory,
            includes = includes,
            excludes = excludes,
            offline = false,
        )
    }

    internal fun executePrepared(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        archiveSha256: String,
        testSuiteFolder: File,
        sourceDestinationFolder: File,
        projectDirectory: File,
        includes: List<String>,
        excludes: List<String>,
        offline: Boolean,
    ) {
        prepareTestsuite(
            testSuiteRepo = testSuiteRepo,
            testSuiteRepoRef = testSuiteRepoRef,
            archiveSha256 = archiveSha256,
            testSuiteFolder = testSuiteFolder,
            offline = offline,
            forceExtract = false,
        )

        val includedMatchers = includes.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludedMatchers = excludes.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val allFiles =
            Files.walk(testSuiteFolder.toPath()).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .map { it.toFile() }
                    .filter { it.extension == "wasm" }
                    .filter { file ->
                        val relative = testSuiteFolder.toPath().relativize(file.toPath())
                        includedMatchers.any { it.matches(relative) } &&
                            excludedMatchers.none { it.matches(relative) }
                    }
                    .sorted()
                    .collect(Collectors.toList())
            }
        if (allFiles.isEmpty()) {
            throw IllegalStateException("No files found in the test suite")
        }

        val pathMatcher =
            FileSystems.getDefault()
                .getPathMatcher("glob:**/tests/*/testsuite/wasm32-wasip1/*.wasm")
        val filesBySuite = LinkedHashMap<String, MutableList<File>>()
        for (file in allFiles) {
            val path = file.toPath()
            if (!pathMatcher.matches(path)) {
                throw IllegalStateException("Invalid test suite file path: $path")
            }
            val suiteName = path.parent.parent.fileName.toString()
            filesBySuite.computeIfAbsent(suiteName) { ArrayList() }.add(file)
        }

        WasiKotlinTestSources.write(
            filesBySuite,
            sourceDestinationFolder,
            { file -> relativePath(projectDirectory, file) },
            ::readSpecification,
        )
    }

    internal fun prepareTestsuite(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        archiveSha256: String,
        testSuiteFolder: File,
        offline: Boolean,
        forceExtract: Boolean,
    ) {
        val parent = testSuiteFolder.parentFile ?: File(".")
        parent.mkdirs()
        val archiveName = "wasi-testsuite-$testSuiteRepoRef.zip"
        val archive = File(parent, archiveName)
        prepareArchive(
            testSuiteRepo = testSuiteRepo,
            testSuiteRepoRef = testSuiteRepoRef,
            archiveSha256 = archiveSha256,
            archive = archive,
            offline = offline,
        )

        val expectedMarker =
            "$testSuiteRepo\n$testSuiteRepoRef\n$archiveSha256\n"
        val marker = testSuiteFolder.resolve(WASI_TEST_SUITE_MARKER)
        if (
            !forceExtract &&
                testSuiteFolder.resolve("tests").isDirectory &&
                marker.isFile &&
                marker.readText() == expectedMarker
        ) {
            return
        }

        val extractionDirectory =
            parent.resolve("${testSuiteFolder.name}.extract.tmp")
        try {
            extractionDirectory.deleteRecursively()
            extractionDirectory.mkdirs()
            ZipFile(archive).use { zip ->
                zip.extractAll(extractionDirectory.absolutePath)
            }
            val repositoryName =
                testSuiteRepo.substringAfterLast('/').removeSuffix(".git")
            val extracted =
                extractionDirectory.resolve("$repositoryName-$testSuiteRepoRef")
            check(extracted.resolve("tests").isDirectory) {
                "Pinned WASI testsuite archive did not contain the expected tests directory"
            }
            extracted.resolve(WASI_TEST_SUITE_MARKER).writeText(expectedMarker)
            if (testSuiteFolder.exists() && !testSuiteFolder.deleteRecursively()) {
                throw IOException("Failed to replace incomplete testsuite: $testSuiteFolder")
            }
            moveAtomically(extracted, testSuiteFolder)
        } finally {
            extractionDirectory.deleteRecursively()
        }
    }

    private fun readSpecification(json: File): Specification {
        if (!json.isFile) {
            return Specification.createDefault()
        }
        return ObjectMapper().readValue(json, Specification::class.java)
    }

    private fun relativePath(projectDirectory: File, file: File): String =
        projectDirectory.toPath().relativize(file.toPath()).toString()

    private fun prepareArchive(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        archiveSha256: String,
        archive: File,
        offline: Boolean,
    ) {
        if (archive.isFile && sha256(archive) == archiveSha256) {
            return
        }
        if (offline) {
            throw IllegalStateException(
                "Pinned WASI testsuite archive is missing or failed SHA-256 verification at " +
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
            connection.connectTimeout = WASI_TEST_SUITE_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = WASI_TEST_SUITE_READ_TIMEOUT_MILLIS
            try {
                connection.getInputStream().use { input ->
                    temporary.outputStream().buffered().use(input::copyTo)
                }
            } finally {
                (connection as? HttpURLConnection)?.disconnect()
            }
            val actualSha256 = sha256(temporary)
            check(actualSha256 == archiveSha256) {
                "WASI testsuite archive SHA-256 mismatch: expected $archiveSha256, " +
                    "got $actualSha256"
            }
            moveAtomically(temporary, archive)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }
}

private data class WasiTestPreparationConfig(
    val config: WasiTestGenConfig,
    val archiveSha256: String,
    val offline: Boolean,
)

private fun readPreparationConfig(file: File): WasiTestPreparationConfig {
    val mapper =
        ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val tree = mapper.readTree(file)
    val config = mapper.treeToValue(tree, WasiTestGenConfig::class.java)
    val archiveSha256 =
        tree.path("testSuiteArchiveSha256").asText(DEFAULT_WASI_TEST_SUITE_ARCHIVE_SHA256)
    require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) {
        "testSuiteArchiveSha256 must be a lowercase SHA-256 value"
    }
    return WasiTestPreparationConfig(
        config = config,
        archiveSha256 = archiveSha256,
        offline = tree.path("offline").asBoolean(false),
    )
}

private fun sha256(file: File): String {
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

private fun moveAtomically(source: File, target: File) {
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

private const val DEFAULT_WASI_TEST_SUITE_REPOSITORY =
    "https://github.com/WebAssembly/wasi-testsuite"
private const val DEFAULT_WASI_TEST_SUITE_REVISION =
    "caf3b66fa3457cc17156864d971387a7e9f5933b"
private const val DEFAULT_WASI_TEST_SUITE_ARCHIVE_SHA256 =
    "5bc6471f2ccf57f2c4241fb74bb59a57b897607f3eb2769fc7a2ff97c9e928b3"
private const val WASI_TEST_SUITE_MARKER = ".krwa-testsuite-source"
private const val WASI_TEST_SUITE_CONNECT_TIMEOUT_MILLIS = 30_000
private const val WASI_TEST_SUITE_READ_TIMEOUT_MILLIS = 5 * 60_000

object WasiKotlinTestSources {
    private const val PACKAGE_NAME = "uk.shusek.krwa.wasi.test"

    fun write(
        filesBySuite: Map<String, List<File>>,
        sourceDestinationFolder: File,
        relativePath: (File) -> String,
        readSpecification: (File) -> Specification,
    ) {
        val packageDir =
            sourceDestinationFolder.toPath().resolve(PACKAGE_NAME.replace('.', File.separatorChar))
        try {
            Files.createDirectories(packageDir)
            for ((testSuite, files) in filesBySuite) {
                val typeName = "Suite${StringUtils.capitalize(testSuite)}Test"
                Files.writeString(
                    packageDir.resolve("$typeName.kt"),
                    renderSuite(typeName, files, relativePath, readSpecification),
                )
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    private fun renderSuite(
        typeName: String,
        files: List<File>,
        relativePath: (File) -> String,
        readSpecification: (File) -> Specification,
    ): String = buildString {
        appendLine("package $PACKAGE_NAME")
        appendLine()
        appendLine("import java.io.File")
        appendLine("import java.util.Optional")
        appendLine("import org.junit.jupiter.api.Test")
        appendLine("import uk.shusek.krwa.wasi.WasiTestRunner")
        appendLine()
        appendLine("class $typeName {")
        files.forEachIndexed { index, file ->
            if (index > 0) {
                appendLine()
            }
            append(renderMethod(file, relativePath, readSpecification).prependIndent("    "))
        }
        appendLine()
        appendLine("}")
    }

    private fun renderMethod(
        file: File,
        relativePath: (File) -> String,
        readSpecification: (File) -> Specification,
    ): String {
        val baseName = file.name.removeSuffix(".wasm")
        val specification = readSpecification(File(file.parentFile, "$baseName.json"))
        return buildString {
            appendLine("@Test")
            appendLine("fun test${StringUtils.escapedCamelCase(baseName)}() {")
            appendLine("    val test = File(${relativePath(file).kotlinLiteral()})")
            appendLine("    val args = ${listOf(specification.args())}")
            appendLine("    val root = ${optionalOf(specification.root())}")
            appendLine("    val dirs = ${listOf(specification.dirs())}")
            appendLine("    val env = ${mapOf(specification.env())}")
            appendLine("    val exitCode = ${specification.exitCode()}")
            appendLine("    val stdout = ${optionalOf(specification.stdout())}")
            appendLine("    WasiTestRunner.execute(test, args, root, dirs, env, exitCode, stdout)")
            appendLine("}")
        }
    }

    private fun listOf(list: List<String>): String =
        if (list.isEmpty()) {
            "emptyList<String>()"
        } else {
            "listOf(${list.joinToString(", ") { it.kotlinLiteral() }})"
        }

    private fun mapOf(map: Map<String, String>): String =
        if (map.isEmpty()) {
            "emptyMap<String, String>()"
        } else {
            "mapOf(${map.entries.joinToString(", ") { it.key.kotlinLiteral() + " to " + it.value.kotlinLiteral() }})"
        }

    private fun optionalOf(optional: Optional<String>): String =
        optional.map { "Optional.of(${it.kotlinLiteral()})" }.orElse("Optional.empty<String>()")
}

private fun String.kotlinLiteral(): String = buildString {
    append('"')
    for (char in this@kotlinLiteral) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> {
                append('\\')
                append('$')
            }
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else ->
                if (char < ' ') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
        }
    }
    append('"')
}
