package uk.shusek.krwa.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyNoUnjustifiedDisabledTests : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScripts: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val rootPath = rootDirectory.get().asFile.toPath()
        val testFindings =
            DisabledTestScanner.scan(
                testSources.asTestSources(rootPath),
            )
        val buildScriptFindings =
            DisabledTestScanner.scanBuildScripts(
                buildScripts.asTestSources(rootPath),
            )
        val findings = (testFindings + buildScriptFindings).sortedWith(
            compareBy(DisabledTestFinding::path, DisabledTestFinding::line),
        )
        if (findings.isEmpty()) {
            return
        }

        throw GradleException(
            buildString {
                appendLine(
                    "Found disabled tests or Gradle tasks without a useful tracked justification:",
                )
                findings.forEach { finding ->
                    appendLine(" - ${finding.path}:${finding.line}: ${finding.annotation}")
                }
                append(
                    "Add a reason containing an issue URL, #123 reference, or tracker ID such as KRWA-123. " +
                        "Re-enable the test if there is no tracked reason to keep it disabled.",
                )
            },
        )
    }
}

private fun ConfigurableFileCollection.asTestSources(rootPath: java.nio.file.Path): List<TestSource> =
    files
        .asSequence()
        .filter(File::isFile)
        .sortedBy(File::getAbsolutePath)
        .map { file ->
            TestSource(
                path = rootPath.relativize(file.toPath()).toString(),
                contents = file.readText(),
            )
        }
        .toList()

internal data class TestSource(
    val path: String,
    val contents: String,
)

internal data class DisabledTestFinding(
    val path: String,
    val line: Int,
    val annotation: String,
)

internal object DisabledTestScanner {
    private val disabledAnnotation =
        Regex("""@(?:[A-Za-z_][A-Za-z0-9_]*\.)*(Disabled|Ignore)\b""")
    private val testAnnotation =
        Regex("""@(?:[A-Za-z_][A-Za-z0-9_]*\.)*Test\b""")
    private val disabledTestArgument =
        Regex("""\benabled\s*=\s*false\b""")
    private val durableTrackingReference =
        Regex("""(?i)(https?://[^\s"']+|(?:^|[^\p{Alnum}_])#\d+\b|\b[A-Z][A-Z0-9]+-\d+\b)""")
    private val stringLiteral =
        Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")

    fun scan(sources: Iterable<TestSource>): List<DisabledTestFinding> =
        sources.flatMap(::scan)

    fun scanBuildScripts(sources: Iterable<TestSource>): List<DisabledTestFinding> =
        sources.flatMap(::scanBuildScript)

    private fun scan(source: TestSource): List<DisabledTestFinding> {
        val mask = maskCommentsAndStrings(source.contents)
        val findings = ArrayList<DisabledTestFinding>()

        disabledAnnotation.findAll(mask).forEach { match ->
            val arguments = annotationArguments(source.contents, mask, match.range.last + 1)
            if (!hasTrackedJustification(arguments)) {
                findings +=
                    DisabledTestFinding(
                        path = source.path,
                        line = lineNumber(source.contents, match.range.first),
                        annotation = "@${match.groupValues[1]}",
                    )
            }
        }

        testAnnotation.findAll(mask).forEach { match ->
            val arguments = annotationArguments(source.contents, mask, match.range.last + 1)
                ?: return@forEach
            if (
                disabledTestArgument.containsMatchIn(maskCommentsAndStrings(arguments)) &&
                !hasTrackedJustification(arguments)
            ) {
                findings +=
                    DisabledTestFinding(
                        path = source.path,
                        line = lineNumber(source.contents, match.range.first),
                        annotation = "@Test(enabled = false)",
                    )
            }
        }

        return findings
    }

    private fun scanBuildScript(source: TestSource): List<DisabledTestFinding> {
        val mask = maskCommentsAndStrings(source.contents)
        return disabledTestArgument.findAll(mask).mapNotNull { match ->
            val context = nearbyLines(source.contents, match.range.first)
            if (hasTrackedBuildScriptJustification(context)) {
                null
            } else {
                DisabledTestFinding(
                    path = source.path,
                    line = lineNumber(source.contents, match.range.first),
                    annotation = "enabled = false",
                )
            }
        }.toList()
    }

    private fun annotationArguments(
        source: String,
        mask: String,
        startIndex: Int,
    ): String? {
        var index = startIndex
        while (index < mask.length && mask[index].isWhitespace()) {
            index++
        }
        if (index >= mask.length || mask[index] != '(') {
            return null
        }
        val end = closingParenthesis(source, index) ?: return source.substring(index + 1)
        return source.substring(index + 1, end)
    }

    private fun closingParenthesis(source: String, openingIndex: Int): Int? {
        var depth = 0
        var index = openingIndex
        var state = LexicalState.CODE
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                LexicalState.CODE ->
                    when {
                        current == '"' && source.startsWith("\"\"\"", index) -> {
                            state = LexicalState.TRIPLE_STRING
                            index += 2
                        }
                        current == '"' -> state = LexicalState.STRING
                        current == '\'' -> state = LexicalState.CHAR
                        current == '/' && next == '/' -> {
                            state = LexicalState.LINE_COMMENT
                            index++
                        }
                        current == '/' && next == '*' -> {
                            state = LexicalState.BLOCK_COMMENT
                            index++
                        }
                        current == '(' -> depth++
                        current == ')' -> {
                            depth--
                            if (depth == 0) {
                                return index
                            }
                        }
                    }
                LexicalState.STRING ->
                    when {
                        current == '\\' -> index++
                        current == '"' -> state = LexicalState.CODE
                    }
                LexicalState.CHAR ->
                    when {
                        current == '\\' -> index++
                        current == '\'' -> state = LexicalState.CODE
                    }
                LexicalState.TRIPLE_STRING ->
                    if (source.startsWith("\"\"\"", index)) {
                        state = LexicalState.CODE
                        index += 2
                    }
                LexicalState.LINE_COMMENT ->
                    if (current == '\n') {
                        state = LexicalState.CODE
                    }
                LexicalState.BLOCK_COMMENT ->
                    if (current == '*' && next == '/') {
                        state = LexicalState.CODE
                        index++
                    }
            }
            index++
        }
        return null
    }

    private fun hasTrackedJustification(arguments: String?): Boolean {
        if (arguments == null) {
            return false
        }
        val reason =
            stringLiteral.findAll(arguments)
                .joinToString(" ") { match ->
                    match.value
                        .removePrefix("\"\"\"")
                        .removeSuffix("\"\"\"")
                        .removePrefix("\"")
                        .removeSuffix("\"")
                }
        if (!durableTrackingReference.containsMatchIn(reason)) {
            return false
        }
        val explanation = durableTrackingReference.replace(reason, "")
        return explanation.count(Char::isLetterOrDigit) >= MinimumExplanationCharacters
    }

    private fun hasTrackedBuildScriptJustification(context: String): Boolean {
        val justificationText =
            commentOrStringLiteral.findAll(context)
                .joinToString(" ") { match ->
                    match.value
                        .removePrefix("//")
                        .removePrefix("/*")
                        .removeSuffix("*/")
                        .removePrefix("\"\"\"")
                        .removeSuffix("\"\"\"")
                        .removePrefix("\"")
                        .removeSuffix("\"")
                }
        if (!durableTrackingReference.containsMatchIn(justificationText)) {
            return false
        }
        val explanation = durableTrackingReference.replace(justificationText, "")
        return explanation.count(Char::isLetterOrDigit) >= MinimumExplanationCharacters
    }

    private fun nearbyLines(source: String, offset: Int): String {
        var start = offset
        repeat(BuildScriptContextLinesBefore) {
            val previousNewline = source.lastIndexOf('\n', startIndex = start - 1)
            start = if (previousNewline < 0) 0 else previousNewline
        }
        var end = offset
        repeat(BuildScriptContextLinesAfter) {
            val nextNewline = source.indexOf('\n', startIndex = end + 1)
            end = if (nextNewline < 0) source.length else nextNewline
        }
        return source.substring(start, end)
    }

    private fun lineNumber(source: String, offset: Int): Int {
        var line = 1
        for (index in 0 until offset) {
            if (source[index] == '\n') {
                line++
            }
        }
        return line
    }

    private fun maskCommentsAndStrings(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        var state = LexicalState.CODE
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                LexicalState.CODE ->
                    when {
                        current == '"' && source.startsWith("\"\"\"", index) -> {
                            masked[index] = ' '
                            masked[index + 1] = ' '
                            masked[index + 2] = ' '
                            state = LexicalState.TRIPLE_STRING
                            index += 2
                        }
                        current == '"' -> {
                            masked[index] = ' '
                            state = LexicalState.STRING
                        }
                        current == '\'' -> {
                            masked[index] = ' '
                            state = LexicalState.CHAR
                        }
                        current == '/' && next == '/' -> {
                            masked[index] = ' '
                            masked[index + 1] = ' '
                            state = LexicalState.LINE_COMMENT
                            index++
                        }
                        current == '/' && next == '*' -> {
                            masked[index] = ' '
                            masked[index + 1] = ' '
                            state = LexicalState.BLOCK_COMMENT
                            index++
                        }
                    }
                LexicalState.STRING ->
                    when {
                        current == '\\' -> {
                            masked[index] = ' '
                            if (index + 1 < masked.size) {
                                masked[index + 1] = ' '
                                index++
                            }
                        }
                        current == '"' -> {
                            masked[index] = ' '
                            state = LexicalState.CODE
                        }
                        current != '\n' -> masked[index] = ' '
                    }
                LexicalState.CHAR ->
                    when {
                        current == '\\' -> {
                            masked[index] = ' '
                            if (index + 1 < masked.size) {
                                masked[index + 1] = ' '
                                index++
                            }
                        }
                        current == '\'' -> {
                            masked[index] = ' '
                            state = LexicalState.CODE
                        }
                        current != '\n' -> masked[index] = ' '
                    }
                LexicalState.TRIPLE_STRING ->
                    if (source.startsWith("\"\"\"", index)) {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        masked[index + 2] = ' '
                        state = LexicalState.CODE
                        index += 2
                    } else if (current != '\n') {
                        masked[index] = ' '
                    }
                LexicalState.LINE_COMMENT ->
                    if (current == '\n') {
                        state = LexicalState.CODE
                    } else {
                        masked[index] = ' '
                    }
                LexicalState.BLOCK_COMMENT ->
                    if (current == '*' && next == '/') {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        state = LexicalState.CODE
                        index++
                    } else if (current != '\n') {
                        masked[index] = ' '
                    }
            }
            index++
        }
        return String(masked)
    }

    private enum class LexicalState {
        CODE,
        STRING,
        CHAR,
        TRIPLE_STRING,
        LINE_COMMENT,
        BLOCK_COMMENT,
    }

    private const val MinimumExplanationCharacters = 8
    private const val BuildScriptContextLinesBefore = 4
    private const val BuildScriptContextLinesAfter = 2
    private val commentOrStringLiteral =
        Regex("""//[^\r\n]*|/\*[\s\S]*?\*/|\"\"\"[\s\S]*?\"\"\"|\"(?:\\.|[^\"\\])*\"""")
}
