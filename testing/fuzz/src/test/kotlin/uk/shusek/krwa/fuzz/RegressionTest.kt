package uk.shusek.krwa.fuzz

import java.io.File
import java.io.FileInputStream
import java.util.Arrays
import java.util.Properties
import java.util.function.Function
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser

class RegressionTest : TestModule() {
    private val interpreterRunner: WasmRunner = DefaultRunner()
    private val compilerRunner: WasmRunner =
        DefaultRunner(
            Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
        )

    @ParameterizedTest
    @MethodSource("crashFolders")
    fun regressionTests(folder: File) {
        val targetWasm = File(folder.absolutePath + "/test.wasm")
        if (!targetWasm.exists()) {
            throw IllegalStateException("Missing test.wasm in crash reproducer folder: $folder")
        }

        val propsFile = File(folder, "crash-info.properties")
        if (propsFile.exists()) {
            val props = Properties()
            FileInputStream(propsFile).use { input -> props.load(input) }
            val phase = props.getProperty("functionName")
            if ("parse" == phase) {
                assertThrows(RuntimeException::class.java) { Parser.parse(targetWasm) }
                return
            }
            if ("instantiate" == phase) {
                val mod = Parser.parse(targetWasm)
                assertThrows(RuntimeException::class.java) {
                    Instance.builder(mod).withInitialize(true).withStart(false).build()
                }
                return
            }
        }

        val module = Parser.parse(targetWasm)
        val instance = Instance.builder(module).withInitialize(true).withStart(false).build()

        val results =
            testModule(
                targetWasm,
                module,
                instance,
                interpreterRunner,
                compilerRunner,
                "regression",
                false,
            )

        for (res in results) {
            if (res.engineResult != null) {
                assertEquals(res.oracleResult, res.engineResult)
            }
        }
        assertDoesNotThrow { Instance.builder(module).build() }
    }

    companion object {
        @JvmStatic
        fun crashFolders(): Stream<Arguments> =
            Stream.of(File("src/test/resources"), File("target/crash-reproducers"))
                .filter { it.isDirectory }
                .flatMap { dir ->
                    val dirs = dir.listFiles()
                    if (dirs != null) Arrays.stream(dirs) else Stream.empty()
                }
                .filter { it.isDirectory && it.name.startsWith("crash") }
                .filter { File(it, "test.wasm").exists() }
                .map { Arguments.of(it) }
    }
}
