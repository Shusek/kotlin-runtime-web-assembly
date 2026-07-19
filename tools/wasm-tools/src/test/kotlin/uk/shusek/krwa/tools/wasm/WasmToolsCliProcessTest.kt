package uk.shusek.krwa.tools.wasm

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WasmToolsCliProcessTest {
    @Test
    fun rejectsDirectoryGuestNamesThatCouldEscapeTheTemporaryRoot(@TempDir tempDir: Path) {
        val outsideLink = tempDir.resolve("outside-link")
        val invalidGuestNames =
            listOf(
                "",
                " ",
                outsideLink.toString(),
                "/absolute",
                "\\absolute",
                "C:\\absolute",
                "../outside",
                "nested/../../outside",
                "./input",
                "input//child",
            )

        for (guestName in invalidGuestNames) {
            assertThrows(IllegalArgumentException::class.java) {
                WasmToolsCli.run(
                    args = listOf("wasm-tools", "--version"),
                    directories = mapOf(guestName to tempDir),
                )
            }
        }

        assertFalse(Files.exists(outsideLink))
    }

    @Test
    fun acceptsOnlyDotOrNonOverlappingRelativeGuestPaths() {
        assertDoesNotThrow {
            WasmToolsCli.validateDirectoryGuestNames(listOf("."))
        }
        assertDoesNotThrow {
            WasmToolsCli.validateDirectoryGuestNames(listOf("input", "nested/output"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WasmToolsCli.validateDirectoryGuestNames(listOf(".", "input"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WasmToolsCli.validateDirectoryGuestNames(
                listOf("input", "input/nested"),
            )
        }
    }

    @Test
    fun rejectsDotWithAnotherDirectoryBeforeSelectingExternalMode(@TempDir tempDir: Path) {
        val property = "krwa.wasmTools.forceEmbedded"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")

            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    WasmToolsCli.run(
                        args = listOf("wasm-tools", "--version"),
                        directories =
                            linkedMapOf(
                                "." to tempDir,
                                "input" to tempDir.resolve("input"),
                            ),
                    )
                }

            assertTrue(error.message.orEmpty().contains("must be the only directory mapping"))
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun forcedEmbeddedModeSkipsExternalCliSelection() {
        val property = "krwa.wasmTools.forceEmbedded"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            assertNull(WasmToolsCli.run(listOf("wasm-tools", "--version")))
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun capturesBoundedStdoutAndStderr(@TempDir tempDir: Path) {
        val result =
            runChild(
                tempDir = tempDir,
                childArgs = listOf("echo"),
                timeoutMillis = 10_000L,
                maxStdoutBytes = 1_024,
                maxStderrBytes = 1_024,
            )

        assertEquals(0, result.exitCode)
        assertEquals("stdout-ok", String(result.stdout(), UTF_8))
        assertEquals("stderr-ok", String(result.stderr(), UTF_8))
    }

    @Test
    fun rejectsAndTerminatesStdoutBeyondTheLimit(@TempDir tempDir: Path) {
        val pidFile = tempDir.resolve("stdout.pid")

        val error =
            assertThrows(WasmToolsCli.WasmToolsProcessException::class.java) {
                runChild(
                    tempDir = tempDir,
                    childArgs = listOf("flood-stdout", pidFile.toString()),
                    timeoutMillis = 10_000L,
                    maxStdoutBytes = 1_024,
                    maxStderrBytes = 1_024,
                )
            }

        assertTrue(error.message.orEmpty().contains("stdout exceeded"))
        assertProcessTerminates(readPid(pidFile))
    }

    @Test
    fun rejectsAndTerminatesStderrBeyondTheLimit(@TempDir tempDir: Path) {
        val pidFile = tempDir.resolve("stderr.pid")

        val error =
            assertThrows(WasmToolsCli.WasmToolsProcessException::class.java) {
                runChild(
                    tempDir = tempDir,
                    childArgs = listOf("flood-stderr", pidFile.toString()),
                    timeoutMillis = 10_000L,
                    maxStdoutBytes = 1_024,
                    maxStderrBytes = 1_024,
                )
            }

        assertTrue(error.message.orEmpty().contains("stderr exceeded"))
        assertProcessTerminates(readPid(pidFile))
    }

    @Test
    fun timesOutAndTerminatesTheProcess(@TempDir tempDir: Path) {
        val pidFile = tempDir.resolve("timeout.pid")

        val error =
            assertThrows(WasmToolsCli.WasmToolsProcessException::class.java) {
                runChild(
                    tempDir = tempDir,
                    childArgs = listOf("sleep", pidFile.toString()),
                    timeoutMillis = 2_000L,
                    maxStdoutBytes = 1_024,
                    maxStderrBytes = 1_024,
                )
            }

        assertTrue(error.message.orEmpty().contains("timed out after 2000 ms"))
        assertProcessTerminates(readPid(pidFile))
    }

    @Test
    fun interruptionTerminatesTheProcessAndRestoresInterruptStatus(@TempDir tempDir: Path) {
        val pidFile = tempDir.resolve("interrupt.pid")
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val worker =
            thread(name = "wasm-tools-interrupt-test") {
                try {
                    runChild(
                        tempDir = tempDir,
                        childArgs = listOf("sleep", pidFile.toString()),
                        timeoutMillis = 30_000L,
                        maxStdoutBytes = 1_024,
                        maxStderrBytes = 1_024,
                    )
                } catch (error: Throwable) {
                    failure.set(error)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        val pid = readPid(pidFile)

        try {
            worker.interrupt()
            worker.join(10_000L)

            assertFalse(worker.isAlive, "interrupted wasm-tools runner did not finish")
            assertInstanceOf(InterruptedException::class.java, failure.get())
            assertTrue(interrupted.get(), "wasm-tools runner did not restore interrupt status")
            assertProcessTerminates(pid)
        } finally {
            worker.interrupt()
            ProcessHandle.of(pid).ifPresent { handle ->
                if (handle.isAlive) {
                    handle.destroyForcibly()
                }
            }
        }
    }

    private fun runChild(
        tempDir: Path,
        childArgs: List<String>,
        timeoutMillis: Long,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): WasmToolsCli.Result =
        WasmToolsCli.runExternalProcess(
            executable = javaExecutable(),
            args =
                listOf(
                    "-cp",
                    childClassPath(),
                    WasmToolsCliTestProcess::class.java.name,
                ) + childArgs,
            stdin = null,
            workDirectory = tempDir,
            timeoutMillis = timeoutMillis,
            maxStdoutBytes = maxStdoutBytes,
            maxStderrBytes = maxStderrBytes,
        )

    private fun readPid(pidFile: Path): Long {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!Files.isRegularFile(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertTrue(Files.isRegularFile(pidFile), "child process did not publish its PID")
        return Files.readString(pidFile).trim().toLong()
    }

    private fun assertProcessTerminates(pid: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (isProcessAlive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertFalse(isProcessAlive(pid), "child process $pid is still alive")
    }

    private fun isProcessAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun javaExecutable(): String {
        val executableName =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString()
    }

    private fun childClassPath(): String {
        val entries = LinkedHashSet<String>()
        System.getProperty("java.class.path")
            ?.takeIf(String::isNotBlank)
            ?.let(entries::add)
        listOf(
            WasmToolsCliTestProcess::class.java,
            Unit::class.java,
        ).forEach { type ->
            type.protectionDomain.codeSource?.location?.toURI()?.let { location ->
                entries += Path.of(location).toString()
            }
        }
        return entries.joinToString(File.pathSeparator)
    }
}

internal object WasmToolsCliTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "echo" -> {
                System.out.print("stdout-ok")
                System.err.print("stderr-ok")
            }
            "flood-stdout" -> flood(System.out, Path.of(args[1]))
            "flood-stderr" -> flood(System.err, Path.of(args[1]))
            "sleep" -> {
                publishPid(Path.of(args[1]))
                Thread.sleep(60_000L)
            }
            else -> error("unknown test child mode")
        }
    }

    private fun flood(output: OutputStream, pidFile: Path) {
        publishPid(pidFile)
        output.write(ByteArray(16 * 1_024) { 'x'.code.toByte() })
        output.flush()
        Thread.sleep(60_000L)
    }

    private fun publishPid(pidFile: Path) {
        Files.writeString(pidFile, ProcessHandle.current().pid().toString())
    }
}
