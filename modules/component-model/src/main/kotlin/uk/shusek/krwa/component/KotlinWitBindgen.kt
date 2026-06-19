package uk.shusek.krwa.component

import java.io.PrintStream
import kotlin.system.exitProcess
import okio.Path
import okio.Path.Companion.toPath

object KotlinWitBindgen {
    @JvmStatic
    fun main(args: Array<String>) {
        val status = run(args, System.out, System.err)
        if (status != 0) {
            exitProcess(status)
        }
    }

    @JvmStatic
    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        return try {
            if (isHelp(args)) {
                usage(out)
                return 0
            }
            val options = Options.parse(args)
            val witSource = Wit.normalize(options.input)
            val generator =
                KotlinWitBindings.builder(Wit.parse(witSource))
                    .withWitSource(witSource)
                    .withPackageName(options.packageName)
                    .withRuntimePackageName(options.runtimePackageName)
                    .withRuntimeTypes(options.runtimeTypes)
                    .withPluginHelpers(options.pluginHelpers)
                    .withGuestExportAdapters(options.guestExports)
                    .build()
            when {
                options.outputFile != null -> generator.writeTo(options.outputFile)
                options.outputDirectory != null -> generator.writeToDirectory(options.outputDirectory)
                else -> out.print(generator.generate())
            }
            0
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            usage(err)
            2
        } catch (e: ComponentModelException) {
            err.println(e.message)
            usage(err)
            2
        }
    }

    private fun isHelp(args: Array<String>): Boolean = args.any { it == "--help" || it == "-h" }

    private fun usage(stream: PrintStream) {
        stream.println(
                "Usage: KotlinWitBindgen [--package <name>] [--out <file>|--out-dir <directory>] " +
                "[--runtime-types] [--runtime-package <name>] [--plugin-helpers] " +
                "[--guest-exports] " +
                "<wit-or-component>"
        )
    }

    private data class Options(
        val input: Path,
        val outputFile: Path?,
        val outputDirectory: Path?,
        val packageName: String,
        val runtimePackageName: String,
        val runtimeTypes: Boolean,
        val pluginHelpers: Boolean,
        val guestExports: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var input: Path? = null
                var outputFile: Path? = null
                var outputDirectory: Path? = null
                var packageName = "uk.shusek.krwa.generated"
                var runtimePackageName = "uk.shusek.krwa.component"
                var runtimeTypes = false
                var pluginHelpers = false
                var guestExports = false

                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    when (arg) {
                        "--package",
                        "-p" -> packageName = requireValue(args, ++index, arg)
                        "--out",
                        "-o" -> outputFile = requireValue(args, ++index, arg).toPath(normalize = true)
                        "--out-dir" ->
                            outputDirectory = requireValue(args, ++index, arg).toPath(normalize = true)
                        "--runtime-package" -> runtimePackageName = requireValue(args, ++index, arg)
                        "--runtime-types" -> runtimeTypes = true
                        "--plugin-helpers" -> pluginHelpers = true
                        "--guest-exports" -> guestExports = true
                        else -> {
                            if (arg.startsWith("-")) {
                                throw IllegalArgumentException("unknown option $arg")
                            }
                            if (input != null) {
                                throw IllegalArgumentException(
                                    "expected one WIT/component input path"
                                )
                            }
                            input = arg.toPath(normalize = true)
                        }
                    }
                    index++
                }
                if (outputFile != null && outputDirectory != null) {
                    throw IllegalArgumentException("configure only one of --out or --out-dir")
                }

                return Options(
                    input ?: throw IllegalArgumentException("missing WIT/component input path"),
                    outputFile,
                    outputDirectory,
                    packageName,
                    runtimePackageName,
                    runtimeTypes,
                    pluginHelpers,
                    guestExports,
                )
            }

            private fun requireValue(args: Array<String>, index: Int, option: String): String {
                if (index >= args.size || args[index].startsWith("-")) {
                    throw IllegalArgumentException("missing value for $option")
                }
                return args[index]
            }
        }
    }
}
