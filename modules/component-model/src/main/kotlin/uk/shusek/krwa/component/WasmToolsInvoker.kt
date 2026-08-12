package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import okio.Path
import uk.shusek.krwa.component.tooling.WasmToolsExecutionProvider

object WasmToolsInvoker {
    private val executionProvider: WasmToolsExecutionProvider by lazy(::loadExecutionProvider)

    @JvmStatic
    fun run(args: List<String>, directories: Map<String, Path>): Result {
        val executionResult = executionProvider.execute(args, directories)
        val result =
            Result(
                executionResult.exitCode(),
                executionResult.stdout(),
                executionResult.stderr(),
            )
        if (result.exitCode() != 0) {
            throw ComponentModelException(result.stderrText() + result.stdoutText())
        }
        return result
    }

    @JvmStatic
    fun directory(guestName: String, hostPath: Path): Map<String, Path> {
        val directories = LinkedHashMap<String, Path>()
        directories[guestName] = hostPath
        return directories
    }

    private fun loadExecutionProvider(): WasmToolsExecutionProvider =
        try {
            val providers =
                ServiceLoader.load(
                    WasmToolsExecutionProvider::class.java,
                    WasmToolsInvoker::class.java.classLoader,
                ).iterator()
            if (!providers.hasNext()) {
                throw ComponentModelException(MISSING_TOOLING_MESSAGE)
            }
            val provider = providers.next()
            if (providers.hasNext()) {
                throw ComponentModelException(
                    "Multiple wasm-tools execution providers are installed on the JVM classpath",
                )
            }
            provider
        } catch (error: ServiceConfigurationError) {
            throw ComponentModelException(
                "Unable to load the wasm-tools execution provider",
                error,
            )
        }

    class Result
    internal constructor(private val exitCode: Int, stdout: ByteArray, stderr: ByteArray) {
        private val stdout: ByteArray = stdout.clone()
        private val stderr: ByteArray = stderr.clone()

        fun exitCode(): Int = exitCode

        fun stdout(): ByteArray = stdout.clone()

        fun stdoutText(): String = String(stdout, StandardCharsets.UTF_8)

        fun stderrText(): String = String(stderr, StandardCharsets.UTF_8)
    }

    private const val MISSING_TOOLING_MESSAGE =
        "Wasm component tooling is not installed. Add " +
            "uk.shusek.krwa:component-model-tooling to the JVM runtime classpath. " +
            "Loading a plugin with WasmPlugin.builder(witPackage).withModule(module) does not " +
            "require this tooling artifact."
}
