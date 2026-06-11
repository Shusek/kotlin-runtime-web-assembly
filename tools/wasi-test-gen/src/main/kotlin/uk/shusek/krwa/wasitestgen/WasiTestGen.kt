package uk.shusek.krwa.wasitestgen

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.Optional
import java.util.stream.Collectors
import net.lingala.zip4j.ZipFile

class WasiTestGenConfig {
    var testSuiteRepo: String = "https://github.com/WebAssembly/wasi-testsuite"
    var testSuiteRepoRef: String = "prod/testsuite-base"
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

        val config = ObjectMapper().readValue(File(args[0]), WasiTestGenConfig::class.java)
        WasiTestGen.execute(
            config.testSuiteRepo,
            config.testSuiteRepoRef,
            File(config.testSuiteFolder),
            File(config.sourceDestinationFolder),
            File(config.projectDirectory),
            config.includes,
            config.excludes,
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
        downloadTestsuite(testSuiteRepo, testSuiteRepoRef, testSuiteFolder)

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

    private fun downloadTestsuite(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        testSuiteFolder: File,
    ) {
        if (File(testSuiteFolder, "tests").isDirectory) {
            return
        }
        if (testSuiteFolder.exists() && !testSuiteFolder.deleteRecursively()) {
            throw RuntimeException("Failed to remove incomplete testsuite: $testSuiteFolder")
        }

        val parent = testSuiteFolder.parentFile ?: File(".")
        parent.mkdirs()
        val archiveName = "${testSuiteRepoRef.replace('/', '-')}.zip"
        val archive = File(parent, archiveName)
        val url = URI.create("$testSuiteRepo/archive/refs/heads/$testSuiteRepoRef.zip").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        try {
            connection.inputStream.use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            ZipFile(archive).use { zip ->
                zip.renameFile(
                    "wasi-testsuite-${testSuiteRepoRef.replace('/', '-')}/",
                    testSuiteFolder.name,
                )
                zip.extractAll(parent.absolutePath)
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to download testsuite: ${e.message}", e)
        } finally {
            connection.disconnect()
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
}

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
            appendLine("    val dirs = ${listOf(specification.dirs())}")
            appendLine("    val env = ${mapOf(specification.env())}")
            appendLine("    val exitCode = ${specification.exitCode()}")
            appendLine("    val stdout = ${optionalOf(specification.stdout())}")
            appendLine("    WasiTestRunner.execute(test, args, dirs, env, exitCode, stdout)")
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
