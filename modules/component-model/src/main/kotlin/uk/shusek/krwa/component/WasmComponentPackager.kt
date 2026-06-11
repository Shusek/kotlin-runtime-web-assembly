package uk.shusek.krwa.component

import java.io.PrintStream
import kotlin.system.exitProcess
import okio.Path
import okio.Path.Companion.toPath

object WasmComponentPackager {
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
            WasmComponentTools.writeComponentFromCore(
                options.wit,
                options.world,
                options.core,
                options.output,
                options.validate,
                options.asyncCallback,
                *options.adapters.toTypedArray(),
            )
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
            "Usage: WasmComponentPackager --wit <wit-file-or-dir> --world <name> " +
                "--core <core.wasm> --out <component.wasm> " +
                "[--adapt <adapter.wasm>]... [--async-callback] [--skip-validate]"
        )
    }

    private data class Options(
        val wit: Path,
        val world: String,
        val core: Path,
        val output: Path,
        val adapters: List<Path>,
        val validate: Boolean,
        val asyncCallback: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var wit: Path? = null
                var world: String? = null
                var core: Path? = null
                var output: Path? = null
                val adapters = ArrayList<Path>()
                var validate = true
                var asyncCallback = false

                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    when (arg) {
                        "--wit",
                        "-w" -> wit = requireValue(args, ++index, arg).toPath(normalize = true)
                        "--world" -> world = requireValue(args, ++index, arg)
                        "--core",
                        "-c" -> core = requireValue(args, ++index, arg).toPath(normalize = true)
                        "--out",
                        "-o" -> output = requireValue(args, ++index, arg).toPath(normalize = true)
                        "--adapt" ->
                            adapters.add(requireValue(args, ++index, arg).toPath(normalize = true))
                        "--async-callback" -> asyncCallback = true
                        "--skip-validate" -> validate = false
                        else -> throw IllegalArgumentException("unknown option $arg")
                    }
                    index++
                }

                val worldName = world
                if (worldName == null || worldName.isBlank()) {
                    throw IllegalArgumentException("missing --world")
                }

                return Options(
                    wit ?: throw IllegalArgumentException("missing --wit"),
                    worldName,
                    core ?: throw IllegalArgumentException("missing --core"),
                    output ?: throw IllegalArgumentException("missing --out"),
                    adapters.toList(),
                    validate,
                    asyncCallback,
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
