package uk.shusek.krwa.tools.wasm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        validateDirectoryGuestNames(directories.keys)
        // Release verification sets this before any lookup so PATH or host configuration cannot
        // replace the checksum-pinned embedded wasm-tools module.
        if (java.lang.Boolean.getBoolean(FORCE_EMBEDDED_PROPERTY)) {
            return null
        }
        val wasmTools = executable ?: return null
        val normalizedArgs = if (args.firstOrNull() == "wasm-tools") args.drop(1) else args
        val tempRoot = if (directories.containsKey(".")) null else Files.createTempDirectory("krwa-wasm-tools-")
        try {
            val workDir = directories["."] ?: tempRoot ?: Path.of(".").toAbsolutePath()
            if (tempRoot != null) {
                for ((link, hostPath) in guestLinks(tempRoot, directories)) {
                    Files.createDirectories(link.parent ?: tempRoot)
                    Files.createSymbolicLink(link, hostPath)
                }
            }
            return runExternalProcess(
                executable = wasmTools,
                args = normalizedArgs,
                stdin = stdin,
                workDirectory = workDir,
                timeoutMillis = DEFAULT_PROCESS_TIMEOUT_MILLIS,
                maxStdoutBytes = DEFAULT_PROCESS_OUTPUT_LIMIT_BYTES,
                maxStderrBytes = DEFAULT_PROCESS_OUTPUT_LIMIT_BYTES,
            )
        } catch (_: IOException) {
            return null
        } finally {
            if (tempRoot != null) {
                runCatching { deleteTempRoot(tempRoot) }
            }
        }
    }

    internal fun validateDirectoryGuestNames(guestNames: Collection<String>) {
        require("." !in guestNames || guestNames.size == 1) {
            "wasm-tools directory guestName '.' must be the only directory mapping"
        }
        val normalizedGuestPaths = ArrayList<Path>()
        for (guestName in guestNames) {
            if (guestName == ".") {
                continue
            }
            require(guestName.isNotBlank()) {
                "wasm-tools directory guestName must not be blank"
            }
            require(!hasPortableAbsolutePrefix(guestName)) {
                "wasm-tools directory guestName must be relative"
            }
            val portableSegments = guestName.split('/', '\\')
            require(
                portableSegments.all { segment ->
                    segment.isNotEmpty() && segment != "." && segment != ".."
                }
            ) {
                "wasm-tools directory guestName must contain only non-traversing path segments"
            }
            val guestPath =
                try {
                    Path.of(guestName)
                } catch (error: InvalidPathException) {
                    throw IllegalArgumentException(
                        "wasm-tools directory guestName is not a valid path",
                        error,
                    )
                }
            require(!guestPath.isAbsolute && guestPath.root == null) {
                "wasm-tools directory guestName must be relative"
            }
            require(guestPath.none { segment -> segment.toString() == ".." }) {
                "wasm-tools directory guestName must not contain '..'"
            }
            val normalizedGuestPath = guestPath.normalize()
            require(
                normalizedGuestPaths.none { existing ->
                    normalizedGuestPath.startsWith(existing) ||
                        existing.startsWith(normalizedGuestPath)
                }
            ) {
                "wasm-tools directory guestNames must not overlap"
            }
            normalizedGuestPaths.add(normalizedGuestPath)
        }
    }

    private fun hasPortableAbsolutePrefix(guestName: String): Boolean =
        guestName.startsWith('/') ||
            guestName.startsWith('\\') ||
            (
                guestName.length >= 2 &&
                    guestName[0].isLetter() &&
                    guestName[1] == ':'
            )

    private fun guestLinks(
        tempRoot: Path,
        directories: Map<String, Path>,
    ): List<Pair<Path, Path>> {
        val links =
            directories
                .filterKeys { guestName -> guestName != "." }
                .map { (guestName, hostPath) ->
                    val link = tempRoot.resolve(Path.of(guestName)).normalize()
                    require(link != tempRoot && link.startsWith(tempRoot)) {
                        "wasm-tools directory guestName must remain within its temporary root"
                    }
                    link to hostPath
                }
        return links
    }

    internal fun runExternalProcess(
        executable: String,
        args: List<String>,
        stdin: ByteArray?,
        workDirectory: Path,
        timeoutMillis: Long,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): Result {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(maxStdoutBytes > 0) { "maxStdoutBytes must be positive" }
        require(maxStderrBytes > 0) { "maxStderrBytes must be positive" }

        val process =
            ProcessBuilder(listOf(executable) + args)
                .directory(workDirectory.toFile())
                .start()
        val overflow = AtomicReference<ProcessOutputOverflow?>()
        val outputFailure = AtomicReference<Throwable?>()
        val stdout =
            BoundedProcessOutput(maxStdoutBytes) {
                if (
                    overflow.compareAndSet(
                        null,
                        ProcessOutputOverflow("stdout", maxStdoutBytes),
                    )
                ) {
                    terminateProcessTree(process)
                }
            }
        val stderr =
            BoundedProcessOutput(maxStderrBytes) {
                if (
                    overflow.compareAndSet(
                        null,
                        ProcessOutputOverflow("stderr", maxStderrBytes),
                    )
                ) {
                    terminateProcessTree(process)
                }
            }
        val stdoutThread =
            processOutputThread(
                "wasm-tools-stdout",
                process.inputStream,
                stdout,
                outputFailure,
            ) {
                terminateProcessTree(process)
            }
        val stderrThread =
            processOutputThread(
                "wasm-tools-stderr",
                process.errorStream,
                stderr,
                outputFailure,
            ) {
                terminateProcessTree(process)
            }
        val stdinThread =
            thread(
                name = "wasm-tools-stdin",
                isDaemon = true,
            ) {
                try {
                    process.outputStream.use { output ->
                        if (stdin != null) {
                            ByteArrayInputStream(stdin).use { input -> input.copyTo(output) }
                        }
                    }
                } catch (_: IOException) {
                    // A command may exit without consuming all stdin. Its exit code is authoritative.
                }
            }
        val ioThreads = listOf(stdoutThread, stderrThread, stdinThread)
        var restoreInterrupt = false
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminateProcessTree(process)
                throw WasmToolsProcessException(
                    "wasm-tools process timed out after $timeoutMillis ms",
                )
            }
            joinProcessThreads(ioThreads, PROCESS_IO_JOIN_TIMEOUT_MILLIS)
            if (ioThreads.any(Thread::isAlive)) {
                terminateProcessTree(process)
                throw WasmToolsProcessException(
                    "wasm-tools process I/O did not finish within " +
                        "$PROCESS_IO_JOIN_TIMEOUT_MILLIS ms",
                )
            }
            overflow.get()?.let { exceeded ->
                throw WasmToolsProcessException(
                    "wasm-tools ${exceeded.streamName} exceeded its " +
                        "${exceeded.limitBytes}-byte output limit",
                )
            }
            outputFailure.get()?.let { failure ->
                throw IOException("failed to capture wasm-tools process output", failure)
            }
            return Result(process.exitValue(), stdout.toByteArray(), stderr.toByteArray())
        } catch (error: InterruptedException) {
            restoreInterrupt = true
            terminateProcessTree(process)
            throw error
        } finally {
            if (process.isAlive) {
                terminateProcessTree(process)
            }
            closeProcessStreams(process)
            val waitWasInterrupted =
                waitForProcessUninterruptibly(process, PROCESS_TERMINATION_TIMEOUT_MILLIS)
            val joinWasInterrupted =
                joinProcessThreadsUninterruptibly(
                    ioThreads,
                    PROCESS_IO_JOIN_TIMEOUT_MILLIS,
                )
            restoreInterrupt = restoreInterrupt || waitWasInterrupted || joinWasInterrupted
            if (process.isAlive) {
                terminateProcessTree(process)
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt()
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

    private fun processOutputThread(
        name: String,
        input: java.io.InputStream,
        output: BoundedProcessOutput,
        failure: AtomicReference<Throwable?>,
        onFailure: () -> Unit,
    ): Thread =
        thread(name = name, isDaemon = true) {
            try {
                input.use { stream -> stream.copyTo(output) }
            } catch (error: Throwable) {
                if (failure.compareAndSet(null, error)) {
                    onFailure()
                }
            }
        }

    @Throws(InterruptedException::class)
    private fun joinProcessThreads(threads: List<Thread>, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        for (worker in threads) {
            while (worker.isAlive) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    return
                }
                worker.join(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                )
            }
        }
    }

    private fun joinProcessThreadsUninterruptibly(
        threads: List<Thread>,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = false
        for (worker in threads) {
            while (worker.isAlive) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    return interrupted
                }
                try {
                    worker.join(
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    )
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        return interrupted
    }

    private fun waitForProcessUninterruptibly(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = false
        while (process.isAlive) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                break
            }
            try {
                process.waitFor(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    private fun terminateProcessTree(process: Process) {
        val descendants =
            runCatching {
                process.toHandle().descendants().use { handles -> handles.toList() }
            }.getOrDefault(emptyList())
        descendants.asReversed().forEach { handle ->
            runCatching {
                if (handle.isAlive) {
                    handle.destroyForcibly()
                }
            }
        }
        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private class BoundedProcessOutput(
        private val limitBytes: Int,
        private val onLimitExceeded: () -> Unit,
    ) : java.io.OutputStream() {
        private val captured = ByteArrayOutputStream(minOf(limitBytes, PROCESS_COPY_BUFFER_SIZE))
        private var exceeded = false

        @Synchronized
        override fun write(value: Int) {
            if (captured.size() < limitBytes) {
                captured.write(value)
            } else {
                reportLimitExceeded()
            }
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            val accepted = minOf(length, (limitBytes - captured.size()).coerceAtLeast(0))
            if (accepted > 0) {
                captured.write(bytes, offset, accepted)
            }
            if (accepted < length) {
                reportLimitExceeded()
            }
        }

        @Synchronized
        fun toByteArray(): ByteArray = captured.toByteArray()

        private fun reportLimitExceeded() {
            if (!exceeded) {
                exceeded = true
                onLimitExceeded()
            }
        }
    }

    private data class ProcessOutputOverflow(
        val streamName: String,
        val limitBytes: Int,
    )

    internal class WasmToolsProcessException internal constructor(message: String) :
        IllegalStateException(message)

    class Result internal constructor(
        val exitCode: Int,
        private val stdoutBytes: ByteArray,
        private val stderrBytes: ByteArray,
    ) {
        fun stdout(): ByteArray = stdoutBytes.clone()

        fun stderr(): ByteArray = stderrBytes.clone()
    }
}

private const val DEFAULT_PROCESS_TIMEOUT_MILLIS: Long = 120_000L
private const val DEFAULT_PROCESS_OUTPUT_LIMIT_BYTES: Int = 64 * 1024 * 1024
private const val FORCE_EMBEDDED_PROPERTY: String = "krwa.wasmTools.forceEmbedded"
private const val PROCESS_IO_JOIN_TIMEOUT_MILLIS: Long = 5_000L
private const val PROCESS_TERMINATION_TIMEOUT_MILLIS: Long = 5_000L
private const val PROCESS_COPY_BUFFER_SIZE: Int = 8 * 1_024
