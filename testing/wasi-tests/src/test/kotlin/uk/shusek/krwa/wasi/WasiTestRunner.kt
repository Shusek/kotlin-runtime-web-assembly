package uk.shusek.krwa.wasi

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileSystem
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

object WasiTestRunner {
    private val LOGGER = SystemLogger()

    @JvmStatic
    fun execute(
        test: File,
        args: List<String>,
        dirs: List<String>,
        env: Map<String, String>,
        exitCode: Int,
        stdout: Optional<String>,
    ) {
        try {
            ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
                .use { fs -> execute(test, args, dirs, env, exitCode, stdout, fs) }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    private fun execute(
        test: File,
        args: List<String>,
        dirs: List<String>,
        env: Map<String, String>,
        exitCode: Int,
        stdout: Optional<String>,
        fs: FileSystem,
    ) {
        val stdoutStream = MockPrintStream()
        val stderrStream = MockPrintStream()

        val allArgs = mutableListOf("test")
        allArgs.addAll(args)

        val options =
            WasiOptions.builder()
                .withStdout(stdoutStream)
                .withStderr(stderrStream)
                .withArguments(allArgs)

        env.forEach(options::withEnvironment)
        // TODO: dangling filesystem is not supported
        if (!test.name.contains("environ")) {
            options.withEnvironment("NO_DANGLING_FILESYSTEM", "true")
        }

        for (dir in dirs) {
            val source = test.parentFile.toPath().resolve(dir)
            val target = fs.getPath(dir)
            Files.copyDirectory(source, target)
            options.withDirectory(target.toString(), target)
        }

        val actualExitCode =
            try {
                execute(test, options.build())
            } catch (e: WasiExitException) {
                e.exitCode()
            } catch (e: RuntimeException) {
                var message = "Failed to execute test: $test"
                if (stdoutStream.output().isNotEmpty() || stderrStream.output().isNotEmpty()) {
                    message += "\n<<<<<\n"
                    message += (stdoutStream.output() + stderrStream.output()).trim()
                    message += "\n>>>>>"
                }
                throw RuntimeException(message, e)
            }

        assertEquals(exitCode, actualExitCode, "exit code")
        stdout.ifPresent { expected -> assertEquals(expected, stdoutStream.output(), "stdout") }
    }

    private fun execute(test: File, wasiOptions: WasiOptions): Int {
        try {
            WasiPreview1.builder().withLogger(LOGGER).withOptions(wasiOptions).build().use { wasi ->
                Instance.builder(Parser.parse(test))
                    .withImportValues(
                        ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                    )
                    .build()
            }
        } catch (e: WasiExitException) {
            return e.exitCode()
        }
        return 0
    }
}
