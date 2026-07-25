package uk.shusek.krwa.gradle.component

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import uk.shusek.krwa.component.KotlinWitBindgen

@CacheableTask
abstract class KrwaGenerateKotlinWitBindingsTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witPackageDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val splitFiles: Property<Boolean>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val runtimePackageName: Property<String>

    @get:Input
    abstract val runtimeTypes: Property<Boolean>

    @get:Input
    abstract val pluginHelpers: Property<Boolean>

    @get:Input
    abstract val guestExports: Property<Boolean>

    @TaskAction
    fun generate() {
        val splitFiles = splitFiles.get()
        outputDirectory.get().asFile.deleteRecursively()
        val output = if (splitFiles) outputDirectory.get().asFile else outputFile.get().asFile
        if (splitFiles) {
            output.mkdirs()
        } else {
            output.parentFile.mkdirs()
        }
        val args = buildList {
            add("--package")
            add(packageName.get())
            add("--runtime-package")
            add(runtimePackageName.get())
            add(if (splitFiles) "--out-dir" else "--out")
            add(output.absolutePath)
            if (runtimeTypes.get()) add("--runtime-types")
            if (pluginHelpers.get()) add("--plugin-helpers")
            if (guestExports.get()) add("--guest-exports")
            add(witInput().absolutePath)
        }
        val result = runTool("KotlinWitBindgen") { stdout, stderr ->
            KotlinWitBindgen.run(args.toTypedArray(), stdout, stderr)
        }
        if (result.stdout.isNotBlank()) {
            logger.lifecycle(result.stdout.trimEnd())
        }
    }

    private fun witInput(): File = when {
        witPackageDirectory.isPresent -> witPackageDirectory.get().asFile
        witFile.isPresent -> witFile.get().asFile
        else -> throw GradleException(
            "Configure krwaComponentModel.witFile or krwaComponentModel.witPackageDirectory.",
        )
    }
}

internal data class KrwaToolResult(val stdout: String, val stderr: String)

internal const val KrwaToolOutputLimitBytes: Int = 64 * 1024

internal fun runTool(
    name: String,
    runner: (stdout: PrintStream, stderr: PrintStream) -> Int,
): KrwaToolResult {
    val stdoutBytes = BoundedToolOutputStream(KrwaToolOutputLimitBytes)
    val stderrBytes = BoundedToolOutputStream(KrwaToolOutputLimitBytes)
    val status = PrintStream(stdoutBytes, true, Charsets.UTF_8).use { stdout ->
        PrintStream(stderrBytes, true, Charsets.UTF_8).use { stderr ->
            runner(stdout, stderr)
        }
    }
    val result = KrwaToolResult(
        stdout = stdoutBytes.capturedText(),
        stderr = stderrBytes.capturedText(),
    )
    if (status != 0) {
        val output = result.stderr.ifBlank { result.stdout }.trimEnd()
        throw GradleException("$name failed with exit code $status" + output.prependFailureOutput())
    }
    return result
}

private fun String.prependFailureOutput(): String = if (isBlank()) "" else ":\n$this"

private class BoundedToolOutputStream(private val limit: Int) : OutputStream() {
    private val captured = ByteArrayOutputStream(limit)
    private var discardedBytes = 0L

    override fun write(value: Int) {
        if (captured.size() < limit) {
            captured.write(value)
        } else {
            discardedBytes++
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val remaining = limit - captured.size()
        val accepted = minOf(length, remaining.coerceAtLeast(0))
        if (accepted > 0) {
            captured.write(bytes, offset, accepted)
        }
        discardedBytes += (length - accepted).toLong()
    }

    fun capturedText(): String {
        val text = captured.toString(Charsets.UTF_8)
        if (discardedBytes == 0L) return text
        return buildString(text.length + 96) {
            append(text)
            if (isNotEmpty() && last() != '\n') append('\n')
            append("[KRWA tool output truncated after ")
            append(limit)
            append(" bytes; discarded ")
            append(discardedBytes)
            append(" bytes]")
        }
    }
}
