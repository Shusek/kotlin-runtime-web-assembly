package uk.shusek.krwa.experimental.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.logging.LogManager
import picocli.CommandLine
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasm.Parser

@CommandLine.Command(
    name = "krwa",
    mixinStandardHelpOptions = true,
    helpCommand = true,
    header = ["A pure Java WASM runtime available as a CLI."],
)
class Cli : Runnable {
    @field:CommandLine.Parameters(arity = "1", description = ["a wasm file to be executed"])
    private lateinit var file: File

    @field:CommandLine.Parameters(
        arity = "0..*",
        description = ["values to be passed to the wasm function"],
    )
    private var arguments: IntArray = intArrayOf()

    @field:CommandLine.Option(
        names = ["--invoke"],
        description = ["The exported WASM function to be invoked"],
    )
    private var functionName: String? = null

    @field:CommandLine.Option(
        names = ["--wasi"],
        description = ["Enable the experimental WASI V1 support"],
        defaultValue = "false",
    )
    private var wasi = false

    @field:CommandLine.Option(
        names = ["--log-level"],
        description = ["The log level to be used"],
        defaultValue = "INFO",
    )
    private var logLevel = "INFO" // this should be an enum

    override fun run() {
        // TODO: improve the handling of the logLevel
        try {
            ByteArrayInputStream((".level = $logLevel").toByteArray(StandardCharsets.UTF_8)).use {
                LogManager.getLogManager().readConfiguration(it)
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        val module = Parser.parse(file)
        val imports =
            if (wasi) {
                ImportValues.builder()
                    .addFunction(
                        *WasiPreview1.builder()
                            .withOptions(WasiOptions.builder().inheritSystem().build())
                            .build()
                            .toHostFunctions()
                    )
                    .build()
            } else {
                ImportValues.empty()
            }
        val instance =
            Instance.builder(module)
                .withInitialize(true)
                .withStart(false)
                .withImportValues(imports)
                .build()

        val name = functionName
        if (name != null) {
            val type = instance.exportType(name)
            val export = instance.export(name)
            val params = LongArray(type.params().size)
            for (i in 0 until type.params().size) {
                params[i] = arguments[i].toLong()
            }

            val result = export.apply(*params)
            if (result != null) {
                for (r in result) {
                    println(r)
                }
            }
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
