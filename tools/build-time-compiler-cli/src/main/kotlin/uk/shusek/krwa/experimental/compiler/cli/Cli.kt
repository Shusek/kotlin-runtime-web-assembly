package uk.shusek.krwa.experimental.compiler.cli

import java.io.IOException
import java.nio.file.Path
import picocli.CommandLine
import uk.shusek.krwa.build.time.compiler.Config
import uk.shusek.krwa.build.time.compiler.Generator
import uk.shusek.krwa.compiler.InterpreterFallback
import uk.shusek.krwa.wasm.Version

@CommandLine.Command(
    name = "krwa-compiler",
    versionProvider = Cli.VersionProvider::class,
    mixinStandardHelpOptions = true,
    helpCommand = true,
    header =
        ["A CLI to generate resources using the Kotlin Runtime Web Assembly build-time compiler"],
)
class Cli : Runnable {
    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> = arrayOf(Version.version())
    }

    @field:CommandLine.Parameters(arity = "1", description = ["The wasm file to be used"])
    private lateinit var wasmFile: Path

    @field:CommandLine.Option(
        order = 1,
        names = ["--prefix"],
        description = ["The prefix to be used to generate resources"],
        defaultValue = "uk.shusek.krwa.Wasm",
    )
    private var prefix = "uk.shusek.krwa.Wasm"

    @field:CommandLine.Option(
        order = 2,
        names = ["--source-dir"],
        description = ["The target folder to use for source files"],
        defaultValue = ".",
    )
    private var targetSourceFolder: Path = Path.of(".")

    @field:CommandLine.Option(
        order = 3,
        names = ["--class-dir"],
        description = ["The target folder to use for class files"],
        defaultValue = ".",
    )
    private var targetClassFolder: Path = Path.of(".")

    @field:CommandLine.Option(
        order = 4,
        names = ["--wasm-dir"],
        description = ["The target folder to use for the wasm meta file"],
        defaultValue = ".",
    )
    private var targetWasmFolder: Path = Path.of(".")

    @field:CommandLine.Option(
        order = 5,
        names = ["--interpreter-fallback"],
        description =
            [
                "Action to take if the compiler needs to use the interpreter because a function is too big"
            ],
        defaultValue = "FAIL",
    )
    private var interpreterFallback: InterpreterFallback = InterpreterFallback.FAIL

    @field:CommandLine.Option(
        order = 6,
        names = ["--interpreted-functions"],
        split = ",",
        description = ["The indexes of functions that should be interpreted, separated by commas"],
    )
    private var interpretedFunctions: Set<Int>? = null

    @field:CommandLine.Option(
        order = 7,
        names = ["--module-interface"],
        description = ["The optional module interface type to generate"],
    )
    private var moduleInterface: String? = null

    override fun run() {
        val config =
            Config.builder()
                .withWasmFile(wasmFile)
                .withName(prefix)
                .withTargetClassFolder(targetClassFolder)
                .withTargetSourceFolder(targetSourceFolder)
                .withTargetWasmFolder(targetWasmFolder)
                .withInterpreterFallback(interpreterFallback)
                .withInterpretedFunctions(interpretedFunctions)
                .withModuleInterface(moduleInterface)
                .build()

        val generator = Generator(config)

        try {
            val interpretedFunctions = generator.generateResources()
            generator.generateMetaWasm(interpretedFunctions)
            generator.generateSources()
            if (!moduleInterface.isNullOrEmpty()) {
                generator.generateModuleInterface(moduleInterface!!)
            }
        } catch (e: IOException) {
            throw CommandLine.PicocliException("Failed to execute the command", e)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exitCode = CommandLine(Cli()).execute(*args)
            kotlin.system.exitProcess(exitCode)
        }
    }
}
