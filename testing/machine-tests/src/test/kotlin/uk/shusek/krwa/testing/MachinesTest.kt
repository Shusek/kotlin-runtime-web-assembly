package uk.shusek.krwa.testing

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportTable
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.runtime.TableInstance
import uk.shusek.krwa.runtime.TrapException
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.testing.gen.DynamicHelloJS
import uk.shusek.krwa.testing.gen.QuickJS
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

class MachinesTest {
    private fun loadModule(fileName: String): WasmModule =
        Parser.parse(CorpusResources.getResource(fileName))

    private fun quickJsInstanceBuilder(): Instance.Builder =
        Instance.builder(loadModule("compiled/quickjs-provider.javy-dynamic.wasm"))

    private fun moduleInstanceBuilder(): Instance.Builder =
        Instance.builder(loadModule("compiled/hello-world.js.javy-dynamic.wasm"))

    private fun setupWasi(stderr: ByteArrayOutputStream): WasiPreview1 {
        val stdin = InputStream.nullInputStream()
        val stdout = ByteArrayOutputStream()

        val wasiOpts =
            WasiOptions.builder().withStdout(stdout).withStderr(stderr).withStdin(stdin).build()

        return WasiPreview1.builder().withOptions(wasiOpts).build()
    }

    @Test
    fun shouldRunQuickJsPrecompiled() {
        val stderr = ByteArrayOutputStream()

        val wasi = setupWasi(stderr)
        val quickjs =
            quickJsInstanceBuilder()
                .withMachineFactory(
                    Function<Instance, Machine> { instance -> QuickJS.create(instance) }
                )
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        val store = Store().register("javy_quickjs_provider_v1", quickjs)

        moduleInstanceBuilder().withImportValues(store.toImportValues()).build()

        assertEquals(EXPECTED_OUTPUT, stderr.toString(UTF_8))

        moduleInstanceBuilder()
            .withMachineFactory(
                Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
            )
            .withImportValues(store.toImportValues())
            .build()

        assertEquals(EXPECTED_OUTPUT + EXPECTED_OUTPUT, stderr.toString(UTF_8))
    }

    @Test
    fun shouldRunQuickJsInterpreted() {
        val stderr = ByteArrayOutputStream()

        val wasi = setupWasi(stderr)
        val quickjs =
            quickJsInstanceBuilder()
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        val store = Store().register("javy_quickjs_provider_v1", quickjs)

        moduleInstanceBuilder()
            .withMachineFactory(
                Function<Instance, Machine> { instance -> DynamicHelloJS.create(instance) }
            )
            .withImportValues(store.toImportValues())
            .build()

        assertEquals(EXPECTED_OUTPUT, stderr.toString(UTF_8))

        moduleInstanceBuilder()
            .withMachineFactory(
                Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
            )
            .withImportValues(store.toImportValues())
            .build()

        assertEquals(EXPECTED_OUTPUT + EXPECTED_OUTPUT, stderr.toString(UTF_8))
    }

    @Test
    fun shouldRunQuickJsRuntimeCompiled() {
        val stderr = ByteArrayOutputStream()

        val wasi = setupWasi(stderr)
        val quickjs =
            quickJsInstanceBuilder()
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        val store = Store().register("javy_quickjs_provider_v1", quickjs)

        moduleInstanceBuilder()
            .withMachineFactory(
                Function<Instance, Machine> { instance -> DynamicHelloJS.create(instance) }
            )
            .withImportValues(store.toImportValues())
            .build()

        assertEquals(EXPECTED_OUTPUT, stderr.toString(UTF_8))

        moduleInstanceBuilder().withImportValues(store.toImportValues()).build()

        assertEquals(EXPECTED_OUTPUT + EXPECTED_OUTPUT, stderr.toString(UTF_8))
    }

    @Test
    fun shouldUseMachineCallOnlyForExport() {
        val stdoutStream = ByteArrayOutputStream()

        val watFile = File("../wasm-corpus/src/main/resources/wat/iterfact.wat")

        ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
            .use { fs ->
                val target = fs.getPath("tmp")
                Files.createDirectory(target)
                val path = target.resolve(watFile.name)
                FileInputStream(watFile).use { input ->
                    Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
                }

                val wasiOpts =
                    WasiOptions.builder()
                        .withStdout(stdoutStream)
                        .withStderr(stdoutStream)
                        .withDirectory(target.toString(), target)
                        .withArguments(listOf("wat2wasm", path.toString(), "--output=-"))
                        .build()
                WasiPreview1.builder().withOptions(wasiOpts).build().use { wasi ->
                    val imports =
                        ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                    val wat2WasmModule =
                        Parser.parse(File("../../tools/wabt/src/main/resources/wat2wasm"))
                    val startFunctionIndex = AtomicInteger()
                    for (i in 0..<wat2WasmModule.exportSection().exportCount()) {
                        val export = wat2WasmModule.exportSection().getExport(i)
                        if (
                            export.name() == "_start" &&
                                export.exportType() == ExternalType.FUNCTION
                        ) {
                            startFunctionIndex.set(export.index())
                        }
                    }
                    assertEquals(18, startFunctionIndex.get())

                    Instance.builder(
                            Parser.parse(File("../../tools/wabt/src/main/resources/wat2wasm"))
                        )
                        .withMachineFactory(
                            Function<Instance, Machine> { instance ->
                                val machine = Wat2Wasm.create(instance)
                                Machine { funcId, args ->
                                    assertEquals(startFunctionIndex.get(), funcId)
                                    machine.call(funcId, args)
                                }
                            }
                        )
                        .withImportValues(imports)
                        .build()
                }

                val result = stdoutStream.toByteArray()

                assertTrue(result.isNotEmpty())
                assertTrue(String(result, UTF_8).contains("iterFact"))
            }
    }

    private fun buildKotlinWasm(
        stdout: ByteArrayOutputStream,
        instanceBuilder: Instance.Builder,
    ): Instance {
        val wasi =
            WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStdout(stdout).build())
                .build()

        val instance =
            instanceBuilder
                .withStart(false)
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        instance.export("_initialize").apply()
        return instance
    }

    private fun assertKotlinWasiOutput(output: String) {
        assertTrue(output.contains("Hello from Kotlin via WASI"), output)
        assertTrue(output.contains("Current 'realtime' timestamp is:"), output)
        assertTrue(output.contains("Current 'monotonic' timestamp is:"), output)

        val realtimeIdx = output.indexOf("Current 'realtime' timestamp is: ")
        val realtimeLine = output.substring(realtimeIdx).lines().first()
        val realtimeValue =
            realtimeLine.substring(realtimeLine.lastIndexOf(' ') + 1).trim().toLong()
        assertTrue(realtimeValue > 0, "Expected positive realtime, got: $realtimeValue")

        val monotonicIdx = output.indexOf("Current 'monotonic' timestamp is: ")
        val monotonicLine = output.substring(monotonicIdx).lines().first()
        val monotonicValue =
            monotonicLine.substring(monotonicLine.lastIndexOf(' ') + 1).trim().toLong()
        assertTrue(monotonicValue > 0, "Expected positive monotonic, got: $monotonicValue")
    }

    @Test
    fun shouldRunKotlinWasmInterpreted() {
        val stdout = ByteArrayOutputStream()
        val module = loadModule("compiled/hello-world.kt.wasm")
        buildKotlinWasm(stdout, Instance.builder(module))

        assertKotlinWasiOutput(stdout.toString(UTF_8))
    }

    @Test
    fun shouldRunKotlinWasmCompiled() {
        val stdout = ByteArrayOutputStream()
        val module = loadModule("compiled/hello-world.kt.wasm")
        buildKotlinWasm(
            stdout,
            Instance.builder(module)
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                ),
        )

        assertKotlinWasiOutput(stdout.toString(UTF_8))
    }

    @Test
    fun shouldCallIndirectInterpreterToAot() {
        val store = Store()
        val table = TableInstance(Table(ValType.FuncRef, TableLimits(3, 3)), Value.REF_NULL_VALUE)
        store.addTable(ImportTable("test", "table", table))

        val instance =
            Instance.builder(loadModule("compiled/call_indirect-export.wat.wasm"))
                .withImportValues(store.toImportValues())
                .withMachineFactory(
                    Function<Instance, Machine> { instance -> InterpreterMachine(instance) }
                )
                .build()
        store.register("test", instance)

        Instance.builder(loadModule("compiled/call_indirect-import.wat.wasm"))
            .withImportValues(store.toImportValues())
            .withMachineFactory(
                Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
            )
            .build()

        assertEquals(42L, instance.export("call-self").apply()[0])
        assertEquals(88L, instance.export("call-other").apply()[0])

        val ex =
            assertThrows(TrapException::class.java) { instance.export("call-other-fail").apply() }
        val className = ex.stackTrace[0].className
        assertTrue(className.contains("CompiledMachine"), className)
    }

    @Test
    fun shouldCallIndirectAotToInterpreter() {
        val store = Store()
        val table = TableInstance(Table(ValType.FuncRef, TableLimits(3, 3)), Value.REF_NULL_VALUE)
        store.addTable(ImportTable("test", "table", table))

        val instance =
            Instance.builder(loadModule("compiled/call_indirect-export.wat.wasm"))
                .withImportValues(store.toImportValues())
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        store.register("test", instance)

        Instance.builder(loadModule("compiled/call_indirect-import.wat.wasm"))
            .withImportValues(store.toImportValues())
            .withMachineFactory(
                Function<Instance, Machine> { instance -> InterpreterMachine(instance) }
            )
            .build()

        assertEquals(42L, instance.export("call-self").apply()[0])
        assertEquals(88L, instance.export("call-other").apply()[0])

        val ex =
            assertThrows(TrapException::class.java) { instance.export("call-other-fail").apply() }
        val className = ex.stackTrace[0].className
        assertTrue(className.contains("InterpreterMachine"), className)
    }

    @Test
    fun tailcallReturnCallAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(55L, function.apply(10L, 0L, 1L)[0])
        assertEquals(6765L, function.apply(20L, 0L, 1L)[0])
        assertEquals(0x80dbbba8L.toInt().toLong(), function.apply(318L, 0L, 1L)[0])
    }

    @Test
    fun tailcallReturnCallCountAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call_count.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(1_000_000L, function.apply(1_000_000L, 0L)[0])
    }

    @Test
    fun tailcallReturnCallCountAccAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call_count_acc.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        val result = function.apply(1_000_000L, 0L)
        assertEquals(0L, result[0])
        assertEquals(1_000_000L, result[1])
    }

    @Test
    fun tailcallCompatibleSignaturesAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_compatible_signatures.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(33L, function.apply(2L, 3L, 4L, 5L)[0])
        assertEquals(24L, function.apply(5L, 2L, 3L, 4L)[0])
    }

    @Test
    fun tailcallMoreParamsAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_more_params.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        val result = function.apply()
        assertEquals(10L, result[0])
        assertEquals(35L, result[1])
    }

    @Test
    fun tailcallSqlitePatternAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_sqlite_pattern.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(156L, function.apply(1L, 2L, 3L, 4L, 5L, 6L, 7L)[0])
    }

    @Test
    fun tailcallImportAot() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_import.wat.wasm"))
                .withImportValues(
                    ImportValues.builder()
                        .addFunction(
                            HostFunction(
                                "env",
                                "imported_callee",
                                FunctionType.of(
                                    listOf(
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                        ValType.I32,
                                    ),
                                    listOf(ValType.I32),
                                ),
                                WasmFunctionHandle { _: Instance, args: LongArray ->
                                    longArrayOf(
                                        args[0] +
                                            args[1] +
                                            args[2] +
                                            args[3] +
                                            args[4] +
                                            args[5] +
                                            args[6] +
                                            args[7]
                                    )
                                },
                            )
                        )
                        .build()
                )
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(156L, function.apply(1L, 2L, 3L, 4L, 5L, 6L, 7L)[0])
    }

    @Test
    fun tailcallDeepReturnCallDoesNotGrowStack() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call_count.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("f")

        assertEquals(10_000_000L, function.apply(10_000_000L, 0L)[0])
    }

    @Test
    fun tailcallDeepReturnCallIndirectDoesNotGrowStack() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_deep_stack.wat.wasm"))
                .withMachineFactory(
                    Function<Instance, Machine> { instance ->
                        MachineFactoryCompiler.compile(instance)
                    }
                )
                .build()
        val function = instance.exports().function("run")

        assertEquals(10_000_000L, function.apply(10_000_000L, 0L)[0])
    }

    companion object {
        private const val EXPECTED_OUTPUT = "Hello world dynamic Javy!\n"
    }
}
