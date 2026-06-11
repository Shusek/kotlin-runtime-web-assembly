package uk.shusek.krwa.testing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.UncheckedIOException
import java.lang.invoke.MethodHandleProxies
import java.lang.invoke.MethodHandles
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import org.approvaltests.Approvals
import org.approvaltests.core.Options
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import uk.shusek.krwa.build.time.compiler.Config
import uk.shusek.krwa.build.time.compiler.Generator
import uk.shusek.krwa.compiler.InterpreterFallback
import uk.shusek.krwa.corpus.WatGenerator.methodTooLarge
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class InterpreterFallbackTest {
    private val expectedMessageContent = "interpreter fallback mode: WASM function index: 2"

    private fun defaultConfig(): Config.Builder =
        Config.builder()
            .withWasmFile(wasmFile)
            .withTargetSourceFolder(classDir)
            .withTargetClassFolder(classDir)
            .withTargetWasmFolder(classDir)

    private fun generateAll(generator: Generator) {
        generator.generateSources()
        val interpretedFunctions = generator.generateResources()
        generator.generateMetaWasm(interpretedFunctions)
    }

    @Test
    fun testDefaultInterpreterFallback() {
        val config =
            defaultConfig()
                .withName("uk.shusek.krwa.testing.Test1")
                // .withInterpreterFallback(InterpreterFallback.FAIL)
                .build()
        val generator = Generator(config)

        val exception = assertThrows(WasmEngineException::class.java) { generateAll(generator) }

        assertTrue(
            exception.message!!.contains(expectedMessageContent),
            "Expected message content not found in: " + exception.message,
        )
    }

    @Test
    fun testWarnInterpreterFallback() {
        val config =
            defaultConfig()
                .withName("uk.shusek.krwa.testing.Test2")
                .withInterpreterFallback(InterpreterFallback.WARN)
                .build()
        val generator = Generator(config)

        val output = captureOutput { generateAll(generator) }

        assertTrue(output.startsWith("Warning: using interpreted mode for WASM function index: 2"))
    }

    @Test
    fun testSilentInterpreterFallback() {
        val config =
            defaultConfig()
                .withName("uk.shusek.krwa.testing.Test3")
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
        val generator = Generator(config)

        val output = captureOutput { generateAll(generator) }
        assertEquals("", output)

        // Load the generated module metadata to verify AOT interpreter fallback mode.
        val url = classDir.toUri().toURL()
        val cl = URLClassLoader(arrayOf(url))
        val machineClass = cl.loadClass("uk.shusek.krwa.testing.Test3Machine")
        val machineFactory = createMachineFactory(machineClass)

        val hostStackTrace = ArrayList<String>()
        val hostFunc = createHostFunc(hostStackTrace)

        val module: WasmModule =
            try {
                machineClass.getResourceAsStream("Test3.meta").use { input -> Parser.parse(input) }
            } catch (e: IOException) {
                throw UncheckedIOException("Failed to load .meta WASM module", e)
            }
        val instance =
            Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(hostFunc).build())
                .withMachineFactory(machineFactory)
                .withStart(false)
                .build()

        assertEquals(35, instance.export("func_2").apply(0L)[0])

        var stackTrace = normalizeStackTrace(hostStackTrace.joinToString("\n"))
        assertTrue(
            containsInOrder(
                listOf("CompilerInterpreterMachine.CALL", "Test3MachineFuncGroup_0.func"),
                hostStackTrace,
            )
        )

        Approvals.verify(stackTrace)

        hostStackTrace.clear()
        assertEquals(35, instance.export("func_2").apply(1L)[0])

        stackTrace = normalizeStackTrace(hostStackTrace.joinToString("\n"))
        assertTrue(
            containsInOrder(
                listOf("InterpreterMachine.CALL_INDIRECT", "Test3MachineFuncGroup_0.func"),
                hostStackTrace,
            )
        )

        Approvals.verify(
            stackTrace,
            Options()
                .forFile()
                .withBaseName("InterpreterFallbackTest.testSilentInterpreterFallback-indirect"),
        )
    }

    private fun containsInOrder(expected: List<String>, actual: List<String>): Boolean {
        val mutableExpected = ArrayList<String>()
        mutableExpected.addAll(expected)

        var currentExpected = mutableExpected.removeAt(0)
        for (c in actual) {
            if (c.contains(currentExpected)) {
                if (mutableExpected.size > 0) {
                    currentExpected = mutableExpected.removeAt(0)
                } else {
                    return true
                }
            }
        }
        return mutableExpected.isEmpty()
    }

    private fun normalizeStackTrace(stackTrace: String): String =
        stackTrace.replace(
            "uk.shusek.krwa.runtime.Instance\$Exports.function\$lambda\$0",
            "uk.shusek.krwa.runtime.Instance\$Exports.lambda\$function\$0",
        )

    @Test
    fun testFailWithInterpretedFunctions() {
        val config =
            defaultConfig()
                .withName("uk.shusek.krwa.testing.Test3")
                .withInterpretedFunctions(setOf(1))
                .build()
        val generator = Generator(config)

        val exception = assertThrows(WasmEngineException::class.java) { generateAll(generator) }

        assertTrue(
            exception.message!!.contains(expectedMessageContent),
            "Expected message content not found in: " + exception.message,
        )
    }

    @Test
    fun testWithInterpretedFunctionsOk() {
        val config =
            defaultConfig()
                .withName("uk.shusek.krwa.testing.Test3")
                .withInterpretedFunctions(setOf(1, 2))
                .build()
        val generator = Generator(config)

        val output = captureOutput { generateAll(generator) }
        assertEquals("", output)
    }

    private fun createMachineFactory(machineClass: Class<*>): Function<Instance, Machine> {
        try {
            val clazz = machineClass.asSubclass(Machine::class.java)
            val constructor = clazz.getConstructor(Instance::class.java)
            val handle = MethodHandles.publicLookup().unreflectConstructor(constructor)
            @Suppress("UNCHECKED_CAST")
            return MethodHandleProxies.asInterfaceInstance(Function::class.java, handle)
                as Function<Instance, Machine>
        } catch (e: ReflectiveOperationException) {
            throw WasmEngineException(e)
        }
    }

    fun interface Failable {
        fun run()
    }

    fun captureOutput(r: Failable): String {
        val orignalStdErr = System.err
        val orignalStdOut = System.out
        try {
            val baos = ByteArrayOutputStream()
            val p = PrintStream(baos, true, UTF_8)
            System.setErr(p)
            System.setOut(p)
            r.run()
            return baos.toString(UTF_8)
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            System.setErr(orignalStdErr)
            System.setOut(orignalStdOut)
        }
    }

    companion object {
        private lateinit var classDir: Path
        private lateinit var wasmFile: Path

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            val srcDir = Path.of("target", "test-fixtures", "src")
            Files.createDirectories(srcDir)
            classDir = Path.of("target", "test-fixtures", "classes")
            Files.createDirectories(classDir)

            val wat = methodTooLarge(20_000)
            val wasm = Wat2Wasm.parse(wat)

            wasmFile = srcDir.resolve("main.wasm")
            Files.write(wasmFile, wasm)
        }

        private fun createHostFunc(hostStackTrace: MutableList<String>): HostFunction =
            HostFunction(
                "funcs",
                "host_func",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                WasmFunctionHandle { _: Instance, _: LongArray ->
                    val thread = Thread.currentThread()
                    var i = 0
                    for (element in thread.stackTrace) {
                        i++
                        if (i < 3 || i > 21) {
                            continue
                        }
                        hostStackTrace.add(element.className + "." + element.methodName)
                    }
                    longArrayOf(35)
                },
            )
    }
}
