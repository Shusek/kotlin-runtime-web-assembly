package uk.shusek.krwa.tools.wasm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.concurrent.thread

object WasmToolsCli {
    private val executable: String? by lazy {
        configuredExecutable()
            ?: executableFromPath("wasm-tools")?.absolutePath
    }

    @JvmStatic
    fun run(
        args: List<String>,
        stdin: ByteArray? = null,
        directories: Map<String, Path> = emptyMap(),
    ): Result? {
        val wasmTools = executable ?: return null
        val normalizedArgs = if (args.firstOrNull() == "wasm-tools") args.drop(1) else args
        val tempRoot = if (directories.containsKey(".")) null else Files.createTempDirectory("krwa-wasm-tools-")
        try {
            val workDir = directories["."] ?: tempRoot ?: Path.of(".").toAbsolutePath()
            if (tempRoot != null) {
                for ((guestName, hostPath) in directories) {
                    if (guestName == ".") continue
                    val link = tempRoot.resolve(guestName)
                    Files.createDirectories(link.parent ?: tempRoot)
                    Files.createSymbolicLink(link, hostPath)
                }
            }
            val process = ProcessBuilder(listOf(wasmTools) + normalizedArgs)
                .directory(workDir.toFile())
                .start()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val stdoutThread = thread(name = "wasm-tools-stdout") {
                process.inputStream.use { input -> input.copyTo(stdout) }
            }
            val stderrThread = thread(name = "wasm-tools-stderr") {
                process.errorStream.use { input -> input.copyTo(stderr) }
            }
            if (stdin != null) {
                process.outputStream.use { output ->
                    ByteArrayInputStream(stdin).use { input -> input.copyTo(output) }
                }
            } else {
                process.outputStream.close()
            }
            val exitCode = process.waitFor()
            stdoutThread.join()
            stderrThread.join()
            return Result(exitCode, stdout.toByteArray(), stderr.toByteArray())
        } catch (_: IOException) {
            return null
        } finally {
            if (tempRoot != null) {
                runCatching { deleteTempRoot(tempRoot) }
            }
        }
    }

    private fun configuredExecutable(): String? =
        System.getProperty("krwa.wasmTools.cli")?.takeIf(String::isNotBlank)
            ?: System.getenv("KRWA_WASM_TOOLS")?.takeIf(String::isNotBlank)

    private fun executableFromPath(name: String): File? =
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map { path -> File(path, name) }
            .firstOrNull { file -> file.isFile && file.canExecute() }

    private fun deleteTempRoot(root: Path) {
        Files.walk(root).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    class Result internal constructor(
        val exitCode: Int,
        private val stdoutBytes: ByteArray,
        private val stderrBytes: ByteArray,
    ) {
        fun stdout(): ByteArray = stdoutBytes.clone()

        fun stderr(): ByteArray = stderrBytes.clone()
    }
}
