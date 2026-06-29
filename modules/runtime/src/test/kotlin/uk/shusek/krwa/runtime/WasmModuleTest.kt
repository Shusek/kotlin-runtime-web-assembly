package uk.shusek.krwa.runtime

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.CatchOpCode
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.TagType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

class WasmModuleTest {
    @Test
    fun shouldWorkFactorial() {
        val instance = Instance.builder(loadModule("compiled/iterfact.wat.wasm")).build()
        val iterFact = instance.export("iterFact")
        val result = iterFact.apply(5)[0]
        assertEquals(120L, result)
    }

    @Test
    fun shouldRunABasicAdd() {
        val instance = Instance.builder(loadModule("compiled/add.wat.wasm")).build()
        val add = instance.export("add")
        val result = add.apply(5, 6)[0]
        assertEquals(11L, result)
    }

    @Test
    fun shouldReportPulleyBackendAvailabilityConsistently() {
        val availability = ExecutionBackend.PULLEY.availability()
        val instance = tryBuildPulley(loadModule("compiled/add.wat.wasm"))
        if (instance == null) {
            assertFalse(availability.available)
            assertNotNull(availability.reason)
        } else {
            assertTrue(availability.available, availability.reason)
            assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
        }
    }

    @Test
    fun shouldReportPulleyUnavailableOnAndroidRuntimeWithoutLinkingWasmtime() {
        val propertyName = "java.runtime.name"
        val previousRuntimeName = System.getProperty(propertyName)
        System.setProperty(propertyName, "Android Runtime")
        try {
            val availability = ExecutionBackend.PULLEY.availability()
            assertFalse(availability.available)
            assertEquals(AndroidPulleyUnavailableReason, availability.reason)

            val exception = assertThrows(WasmEngineException::class.java) {
                Instance.builder(loadModule("compiled/add.wat.wasm"))
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()
            }
            assertEquals(AndroidPulleyUnavailableReason, exception.message)
        } finally {
            if (previousRuntimeName == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousRuntimeName)
            }
        }
    }

    @Test
    fun shouldUseInstalledPulleyProviderOnAndroidRuntime() {
        val propertyName = "java.runtime.name"
        val previousRuntimeName = System.getProperty(propertyName)
        val provider = MockPulleyProvider()
        val previousProvider = PulleyExecutionProviders.install(provider)
        System.setProperty(propertyName, "Android Runtime")
        try {
            val availability = ExecutionBackend.PULLEY.availability()
            assertTrue(availability.available, availability.reason)

            val instance =
                Instance.builder(loadModule("compiled/add.wat.wasm"))
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()

            assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
            assertEquals(11L, instance.export("add").apply(5, 6)[0])
            assertEquals(1, provider.createCalls.get())
        } finally {
            PulleyExecutionProviders.install(previousProvider)
            if (previousRuntimeName == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousRuntimeName)
            }
        }
    }

    @Test
    fun shouldRejectUnavailableInstalledPulleyProviderOnAndroidRuntime() {
        val propertyName = "java.runtime.name"
        val previousRuntimeName = System.getProperty(propertyName)
        val provider = UnavailablePulleyProvider("native library missing")
        val previousProvider = PulleyExecutionProviders.install(provider)
        System.setProperty(propertyName, "Android Runtime")
        try {
            val availability = ExecutionBackend.PULLEY.availability()
            assertFalse(availability.available)
            assertEquals("native library missing", availability.reason)

            val exception = assertThrows(WasmEngineException::class.java) {
                Instance.builder(loadModule("compiled/add.wat.wasm"))
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()
            }
            assertEquals("native library missing", exception.message)
            assertEquals(0, provider.createCalls.get())
        } finally {
            PulleyExecutionProviders.install(previousProvider)
            if (previousRuntimeName == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousRuntimeName)
            }
        }
    }

    @Test
    fun shouldRejectInstalledPulleyProviderReturningWrongBackend() {
        val propertyName = "java.runtime.name"
        val previousRuntimeName = System.getProperty(propertyName)
        val provider = WrongBackendPulleyProvider()
        val previousProvider = PulleyExecutionProviders.install(provider)
        System.setProperty(propertyName, "Android Runtime")
        try {
            val exception = assertThrows(WasmEngineException::class.java) {
                Instance.builder(loadModule("compiled/add.wat.wasm"))
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()
            }
            assertEquals(
                "PulleyExecutionProvider returned NATIVE execution for PULLEY",
                exception.message,
            )
            assertEquals(1, provider.createCalls.get())
        } finally {
            PulleyExecutionProviders.install(previousProvider)
            if (previousRuntimeName == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousRuntimeName)
            }
        }
    }

    @Test
    fun shouldRunPulleyBackendWhenLinkedOrFailFast() {
        val instance =
            tryBuildPulley(loadModule("compiled/add.wat.wasm"))
                ?: return

        assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
        assertEquals(11L, instance.export("add").apply(5, 6)[0])
    }

    @Test
    fun shouldBridgePulleyHostFunctionAndMemoryWhenLinked() {
        val count = AtomicInteger()
        val expected = "Hello, World!"

        val func =
            HostFunction(
                "console",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                WasmFunctionHandle { instance, args ->
                    val memory = instance.memory()
                    val len = args[0].toInt()
                    val offset = args[1].toInt()
                    val message = memory.readString(offset, len)

                    if (expected == message) {
                        count.incrementAndGet()
                    }

                    null
                },
            )

        val instance =
            tryBuildPulley(
                Parser.parse(exportedMemoryHostFunctionModule()),
                ImportValues.builder().addFunction(func).build(),
            ) ?: return

        instance.export("logIt").apply()

        assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
        assertEquals(1, count.get())
    }

    @Test
    fun shouldBridgePulleyHostFunctionAndUnexportedMemoryWhenLinked() {
        val count = AtomicInteger()
        val expected = "Hello, World!"

        val func =
            HostFunction(
                "console",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                WasmFunctionHandle { instance, args ->
                    val memory = instance.memory()
                    val len = args[0].toInt()
                    val offset = args[1].toInt()
                    val message = memory.readString(offset, len)

                    if (expected == message) {
                        count.incrementAndGet()
                    }

                    null
                },
            )

        val instance =
            tryBuildPulley(
                loadModule("compiled/host-function.wat.wasm"),
                ImportValues.builder().addFunction(func).build(),
            ) ?: return

        instance.export("logIt").apply()

        assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
        assertEquals(10, count.get())
    }

    @Test
    fun shouldSupportBrTable() {
        val instance = Instance.builder(loadModule("compiled/br_table.wat.wasm")).build()
        val switchLike = instance.export("switch_like")
        var result = switchLike.apply(0)[0]
        assertEquals(102L, result)
        result = switchLike.apply(1)[0]
        assertEquals(101L, result)
        result = switchLike.apply(2)[0]
        assertEquals(100L, result)
        result = switchLike.apply(-1)[0]
        assertEquals(103L, result)
        result = switchLike.apply(3)[0]
        assertEquals(103L, result)
        result = switchLike.apply(4)[0]
        assertEquals(103L, result)
        result = switchLike.apply(100)[0]
        assertEquals(103L, result)
    }

    @Test
    fun shouldExerciseBranches() {
        val module = Instance.builder(loadModule("compiled/branching.wat.wasm")).build()
        val foo = module.export("foo")

        var result = foo.apply(0)[0]
        assertEquals(42L, result)

        result = foo.apply(1)[0]
        assertEquals(99L, result)

        for (i in 2 until 100) {
            result = foo.apply(i.toLong())[0]
            assertEquals(7L, result)
        }
    }

    @Test
    fun shouldConsoleLogWithString() {
        val count = AtomicInteger()
        val expected = "Hello, World!"

        val func =
            HostFunction(
                "console",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                WasmFunctionHandle { instance, args ->
                    // decompiled is: console_log(13, 0);
                    val memory = instance.memory()
                    val len = args[0].toInt()
                    val offset = args[1].toInt()
                    val message = memory.readString(offset, len)

                    if (expected == message) {
                        count.incrementAndGet()
                    }

                    null
                },
            )
        val instance =
            Instance.builder(loadModule("compiled/host-function.wat.wasm"))
                .withImportValues(ImportValues.builder().addFunction(func).build())
                .build()
        val logIt = instance.export("logIt")
        logIt.apply()

        assertEquals(10, count.get())
    }

    @Test
    fun shouldComputeFactorial() {
        val instance = Instance.builder(loadModule("compiled/iterfact.wat.wasm")).build()
        val iterFact = instance.export("iterFact")

        // don't make this too big we will overflow 32 bits
        for (i in 0 until 10) {
            val result = iterFact.apply(i.toLong())[0]
            // test against an oracle Java implementation
            assertEquals(factorial(i), result)
        }
    }

    @Test
    fun shouldWorkWithStartFunction() {
        val count = AtomicInteger()

        val func =
            HostFunction(
                "env",
                "gotit",
                FunctionType.of(listOf(ValType.I32), emptyList()),
                WasmFunctionHandle { _, args ->
                    val value = args[0]

                    if (value == 42L) {
                        count.incrementAndGet()
                    }

                    null
                },
            )
        Instance.builder(loadModule("compiled/start.wat.wasm"))
            .withImportValues(ImportValues.builder().addFunction(func).build())
            .build()

        assertTrue(count.get() > 0)
    }

    @Test
    fun shouldTrapOnUnreachable() {
        val instanceBuilder = Instance.builder(loadModule("compiled/trap.wat.wasm"))
        assertThrows(TrapException::class.java) { instanceBuilder.build() }
    }

    @Test
    fun shouldSupportGlobals() {
        val instance = Instance.builder(loadModule("compiled/globals.wat.wasm")).build()
        val doit = instance.export("doit")
        val result = doit.apply(32)[0]
        assertEquals(42L, result)
    }

    @Test
    fun shouldCountVowels() {
        val instance = Instance.builder(loadModule("compiled/count_vowels.rs.wasm")).build()
        val alloc = instance.export("alloc")
        val dealloc = instance.export("dealloc")
        val countVowels = instance.export("count_vowels")
        val memory = instance.memory()
        val message = "Hello, World!"
        val len = message.toByteArray(UTF_8).size
        val ptr = alloc.apply(len.toLong())[0].toInt()
        memory.writeString(ptr, message)
        val result = countVowels.apply(ptr.toLong(), len.toLong())
        dealloc.apply(ptr.toLong(), len.toLong())
        assertEquals(3L, result[0])
    }

    @Test
    fun shouldSupportMemoryLimitsOverride() {
        val module = loadModule("compiled/count_vowels.rs.wasm")
        configureWasmtimeExecution(
            module,
            WasmtimeExecutionConfig(maxMemoryBytes = 17L * Memory.PAGE_SIZE),
        )
        try {
            val instance = Instance.builder(module).build()
            assertThrows(TrapException::class.java) {
                instance.export("alloc").apply(Memory.PAGE_SIZE.toLong())
            }
        } finally {
            clearWasmtimeExecution(module)
        }
    }

    @Test
    fun shouldTrapWhenWasmtimeFuelIsExhausted() {
        val module = loadModule("compiled/infinite-loop.c.wasm")
        configureWasmtimeExecution(
            module,
            WasmtimeExecutionConfig(maxFuel = 10_000),
        )
        try {
            val instance = tryBuildPulley(module) ?: return
            val exception = assertThrows(TrapException::class.java) {
                instance.export("run").apply()
            }
            assertTrue(exception.message.orEmpty().contains("fuel", ignoreCase = true))
        } finally {
            clearWasmtimeExecution(module)
        }
    }

    @Test
    fun shouldRunBasicCProgram() {
        // check with: wasmtime basic.c.wasm --invoke run
        val instance = Instance.builder(loadModule("compiled/basic.c.wasm")).build()
        val run = instance.export("run")
        val result = run.apply()[0]
        assertEquals(42L, result)
    }

    @Test
    fun shouldRunComplexFunction() {
        // check with: wasmtime complex.c.wasm --invoke run
        val instance = Instance.builder(loadModule("compiled/complex.c.wasm")).build()
        val run = instance.export("run")
        val result = run.apply()
        assertEquals(-679, result[0].toInt())
    }

    @Test
    fun shouldRunMemoryProgramInC() {
        // check with: wasmtime memory.c.wasm --invoke run
        val instance = Instance.builder(loadModule("compiled/memory.c.wasm")).build()
        val run = instance.export("run")
        val result = run.apply()
        assertEquals(11L, result[0])
    }

    @Test
    fun shouldBuildWithRuntimeDefaults() {
        val instance = Instance.builder(loadModule("compiled/memory.wat.wasm")).build()

        assertEquals(ExecutionBackend.PULLEY, instance.executionBackend())
        assertEquals(42L, instance.export("run32").apply(42)[0])
    }

    @Test
    fun shouldWorkWithMemoryOps() {
        val instance = Instance.builder(loadModule("compiled/memory.wat.wasm")).build()
        var run = instance.export("run32")
        val results = run.apply(42)
        var result = results[0]
        assertEquals(42L, result)

        result = run.apply(Int.MAX_VALUE.toLong())[0]
        assertEquals(Int.MAX_VALUE, result.toInt())

        result = run.apply(Int.MIN_VALUE.toLong())[0]
        assertEquals(Int.MIN_VALUE, result.toInt())

        run = instance.export("run64")
        result = run.apply(42L)[0]
        assertEquals(42L, result)

        run = instance.export("run64")
        result = run.apply(Long.MIN_VALUE)[0]
        assertEquals(Long.MIN_VALUE, result)

        run = instance.export("run64")
        result = run.apply(Long.MAX_VALUE)[0]
        assertEquals(Long.MAX_VALUE, result)
    }

    @Test
    fun shouldRunKitchenSink() {
        // check with: wasmtime kitchensink.wat.wasm --invoke
        // run 100
        val instance = Instance.builder(loadModule("compiled/kitchensink.wat.wasm")).build()

        val run = instance.export("run")
        assertEquals(6L, run.apply(100)[0])
    }

    @Test
    fun shouldOperateMemoryOps() {
        // check with: wasmtime memories.wat.wasm --invoke run 100
        val instance = Instance.builder(loadModule("compiled/memories.wat.wasm")).build()
        val run = instance.export("run")
        assertEquals(-25438, run.apply(100)[0].toInt())
    }

    @Test
    fun shouldRunMixedImports() {
        val cbrtFunc =
            HostFunction(
                "env",
                "cbrt",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.F64)),
                WasmFunctionHandle { _, args ->
                    val x = args[0]
                    val cbrt = Math.cbrt(x.toDouble())
                    longArrayOf(java.lang.Double.doubleToRawLongBits(cbrt))
                },
            )
        val logResult = AtomicReference<String>(null)
        val logFunc =
            HostFunction(
                "env",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.F64), emptyList()),
                WasmFunctionHandle { _, args ->
                    val logLevel = args[0]
                    val value = java.lang.Double.longBitsToDouble(args[1]).toInt()
                    logResult.set("$logLevel: $value")
                    null
                },
            )
        val memory = ImportMemory("env", "memory", ByteBufferMemory(MemoryLimits(1)))

        val hostImports =
            ImportValues.builder().addFunction(cbrtFunc, logFunc).addMemory(memory).build()
        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(loadModule("compiled/mixed-imports.wat.wasm"))
                    .withImportValues(hostImports)
                    .build()
            }
        assertTrue(exception.message!!.contains("function imports only"))
        assertEquals(null, logResult.get())
    }

    @Test
    fun issue294_BRIF() {
        val instance = Instance.builder(loadModule("compiled/issue294_brif.wat.wasm")).build()

        val main = instance.export("main")
        assertEquals(5L, main.apply(5)[0])
    }

    @Test
    fun issue294_BR() {
        val instance = Instance.builder(loadModule("compiled/issue294_br.wat.wasm")).build()

        val main = instance.export("main")
        assertEquals(4L, main.apply()[0])
    }

    @Test
    fun issue294_BRTABLE() {
        val instance = Instance.builder(loadModule("compiled/issue294_brtable.wat.wasm")).build()

        val main = instance.export("main")
        assertEquals(4L, main.apply()[0])
    }

    @Test
    fun shouldEasilyObtainExportedEntities() {
        val instance = Instance.builder(loadModule("compiled/exports.wat.wasm")).build()

        assertNotNull(instance.exports().memory("mem").pages())
        assertNotNull(instance.exports().table("tab").size())
        assertNotNull(instance.exports().global("glob1").value)
        assertNotNull(instance.exports().function("get-1").apply())
    }

    @Test
    fun shouldThrowOnInvalidExports() {
        val instance = Instance.builder(loadModule("compiled/exports.wat.wasm")).build()

        assertThrows(InvalidException::class.java) { instance.exports().memory("nonexistent") }
        assertThrows(InvalidException::class.java) { instance.exports().table("nonexistent") }
        assertThrows(InvalidException::class.java) { instance.exports().global("nonexistent") }
        assertThrows(InvalidException::class.java) { instance.exports().function("nonexistent") }

        assertThrows(InvalidException::class.java) { instance.exports().memory("tab") }
        assertThrows(InvalidException::class.java) { instance.exports().table("mem") }
        assertThrows(InvalidException::class.java) { instance.exports().global("get-1") }
        assertThrows(InvalidException::class.java) { instance.exports().function("glob1") }
    }

    @Test
    fun shouldImportAliases() {
        val logged1 = AtomicBoolean(false)
        val logged2 = AtomicBoolean(false)
        val logFn =
            HostFunction(
                "env",
                "log",
                FunctionType.of(listOf(ValType.I32), emptyList()),
                WasmFunctionHandle { _, _ ->
                    logged1.set(true)
                    null
                },
            )
        val logWrongSignatureFn =
            HostFunction(
                "env",
                "log",
                FunctionType.of(listOf(ValType.I64), emptyList()),
                WasmFunctionHandle { _, _ ->
                    logged2.set(true)
                    null
                },
            )
        val imports =
            ImportValues.builder().addFunction(logFn).addFunction(logWrongSignatureFn).build()

        val instance =
            Instance.builder(loadModule("compiled/alias-imports1.wat.wasm"))
                .withImportValues(imports)
                .build()

        instance.exports().function("log").apply(0)
        instance.exports().function("log-alias").apply(0)
        assertTrue(logged1.get())
        assertFalse(logged2.get())
        assertEquals(2, instance.imports().functionCount())
    }

    @Test
    fun shouldResolveMultipleAliasesByType() {
        val loggedI32 = AtomicBoolean(false)
        val loggedI64 = AtomicBoolean(false)
        val logI32 =
            HostFunction(
                "env",
                "log",
                FunctionType.of(listOf(ValType.I32), emptyList()),
                WasmFunctionHandle { _, _ ->
                    loggedI32.set(true)
                    null
                },
            )
        val logI64 =
            HostFunction(
                "env",
                "log",
                FunctionType.of(listOf(ValType.I64), emptyList()),
                WasmFunctionHandle { _, _ ->
                    loggedI64.set(true)
                    null
                },
            )

        val imports = ImportValues.builder().addFunction(logI32).addFunction(logI64).build()

        val instance =
            Instance.builder(loadModule("compiled/alias-imports2.wat.wasm"))
                .withImportValues(imports)
                .build()

        instance.exports().function("log-i32").apply(0)
        assertTrue(loggedI32.get())
        assertFalse(loggedI64.get())
        instance.exports().function("log-i64").apply(0)
        assertTrue(loggedI32.get())
        assertTrue(loggedI64.get())
    }

    @Test
    fun shouldResolveMultipleAliasesByTypeForAllImports() {
        val module = loadModule("compiled/alias-imports3.wat.wasm")
        val globalI32 = ImportGlobal("env", "global", GlobalInstance(Value.i32(123)))
        val globalI64 = ImportGlobal("env", "global", GlobalInstance(Value.i64(124)))
        val tableFuncref =
            ImportTable(
                "env",
                "table",
                TableInstance(Table(ValType.FuncRef, TableLimits(1)), Value.REF_NULL_VALUE),
            )
        val tableExternref =
            ImportTable(
                "env",
                "table",
                TableInstance(Table(ValType.ExternRef, TableLimits(2)), Value.REF_NULL_VALUE),
            )
        val tagI32 =
            ImportTag(
                "env",
                "tag",
                TagInstance(TagType(0.toByte(), 0), module.typeSection().getType(0)),
            )
        val tagI64 =
            ImportTag(
                "env",
                "tag",
                TagInstance(TagType(0.toByte(), 1), module.typeSection().getType(1)),
            )

        val imports =
            ImportValues.builder()
                .addGlobal(globalI64)
                .addGlobal(globalI32)
                .addTable(tableExternref)
                .addTable(tableFuncref)
                .addTag(tagI64)
                .addTag(tagI32)
                .build()

        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(loadModule("compiled/alias-imports3.wat.wasm"))
                    .withImportValues(imports)
                    .build()
            }
        assertTrue(exception.message!!.contains("function imports only"))
    }

    @Test
    fun correctlyReturnAllLabels() {
        // Arrange
        val operands = longArrayOf(64, 2, 0, 0, 0, 0, 0, 0)

        // Act
        val result = CatchOpCode.allLabels(operands)

        // Assert
        assertEquals(2, result.size)
        assertEquals(0, result[0])
        assertEquals(0, result[1])
    }

    @Test
    fun timeoutExecution() {
        val instance = Instance.builder(loadModule("compiled/infinite-loop.c.wasm")).build()
        val function = instance.exports().function("run")
        val service: ExecutorService = Executors.newSingleThreadExecutor()
        val future = service.submit<LongArray> { function.apply() }
        assertThrows(TimeoutException::class.java) { future.get(100, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun tailcallCompatibleSignatures() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_compatible_signatures.wat.wasm"))
                .build()
        // entry(a, b, c, d) -> tail_caller(a, b, c + d) -> tail_callee(a + (c + d), b) -> helper(a
        // + (c + d), b) -> (a + c + d) * b
        val function = instance.exports().function("f")

        assertEquals(33, function.apply(2, 3, 4, 5)[0]) // (2 + 4 + 5) * 3 = 33
        assertEquals(24, function.apply(5, 2, 3, 4)[0]) // (5 + 3 + 4) * 2 = 24
    }

    @Test
    fun tailcallImport() {
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
                                WasmFunctionHandle { _, args ->
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
                .build()
        // entry(1,2,3,4,5,6,7) -> caller(1,2,3,4,5,6,7) -> imported_callee(1,2,3,132,0,5,6,7) =
        // 1+2+3+132+0+5+6+7 = 156
        val function = instance.exports().function("f")

        assertEquals(156, function.apply(1, 2, 3, 4, 5, 6, 7)[0])
    }

    @Test
    fun tailcallImportIndirect() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_import_indirect.wat.wasm"))
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
                                WasmFunctionHandle { _, args ->
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
                .build()
        // entry(1,2,3,4,5,6,7) -> caller(1,2,3,4,5,6,7) -> imported_callee(1,2,3,132,0,5,6,7) =
        // 1+2+3+132+0+5+6+7 = 156
        val function = instance.exports().function("f")

        assertEquals(156, function.apply(1, 2, 3, 4, 5, 6, 7)[0])
    }

    @Test
    fun tailcallMoreParams() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_more_params.wat.wasm")).build()
        val function = instance.exports().function("f")

        val result = function.apply()
        // entry() -> tail_caller() -> tail_callee(1,2,3,4,5,6,7,8,9)
        // tail_callee returns (1+2+3+4, 5+6+7+8+9) = (10, 35)
        assertEquals(10, result[0])
        assertEquals(35, result[1])
    }

    @Test
    fun tailcallReturnCall() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call.wat.wasm")).build()
        val function = instance.exports().function("f")

        //        {params: []uint64{10, 0, 1}, expResults: []uint64{55}},
        //        {params: []uint64{20, 0, 1}, expResults: []uint64{6765}},
        //        {params: []uint64{318, 0, 1}, expResults: []uint64{0x80dbbba8}},
        assertEquals(55, function.apply(10, 0, 1)[0])
        assertEquals(6765, function.apply(20, 0, 1)[0])
        assertEquals(0x80dbbba8.toInt().toLong(), function.apply(318, 0, 1)[0])
    }

    @Test
    fun tailcallReturnCallCount() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call_count.wat.wasm")).build()
        val function = instance.exports().function("f")

        //        {params: []uint64{1000_000_000, 0}, expResults: []uint64{1000_000_000}},
        // original test: too slow takes > 1 minute
        // assertEquals(1000_000_000, function.apply(1000_000_000, 0)[0]);
        assertEquals(1000_000, function.apply(1000_000, 0)[0])
    }

    @Test
    fun tailcallReturnCallCountAcc() {
        val instance =
            Instance.builder(loadModule("compiled/tail_call_return_call_count_acc.wat.wasm"))
                .build()
        val function = instance.exports().function("f")

        // {params: []uint64{1000_000_000, 0}, expResults: []uint64{0, 1000_000_000}},
        // original test: too slow takes > 1 minute
        val result = function.apply(1000_000, 0)

        assertEquals(0, result[0])
        assertEquals(1000_000, result[1])
    }

    @Test
    fun callIndirectShouldTrapOnDefaultNullTableElement() {
        val instance = Instance.builder(Parser.parse(sharedTableCallerModule())).build()
        val call7 = instance.exports().function("call-7")

        val exception = assertThrows(WasmEngineException::class.java) { call7.apply() }

        assertTrue(exception.message!!.contains("uninitialized element"))
    }

    @Test
    fun activeElementSegmentShouldInitializeImportedTable() {
        val tableOwner = Instance.builder(Parser.parse(sharedTableCallerModule())).build()
        val imports =
            ImportValues.builder()
                .addTable(
                    ImportTable("env", "shared-table", tableOwner.exports().table("shared-table"))
                )
                .build()

        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(Parser.parse(importedTableInitializerModule()))
                    .withImportValues(imports)
                    .build()
            }
        assertTrue(exception.message!!.contains("function imports only"))
    }

    @Test
    fun testExternrefHandling() {
        val imports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "env",
                        "get_host_object",
                        FunctionType.of(emptyList(), listOf(ValType.ExternRef)),
                        WasmFunctionHandle { _, _ -> longArrayOf(123L) },
                    )
                )
                .addFunction(
                    HostFunction(
                        "env",
                        "is_null",
                        FunctionType.of(listOf(ValType.ExternRef), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args -> longArrayOf(if (args[0] == 0L) 1 else 0) },
                    )
                )
                .build()
        val exception =
            assertThrows(WasmEngineException::class.java) {
                Instance.builder(loadModule("compiled/externref-example.wat.wasm"))
                    .withImportValues(imports)
                    .build()
            }
        assertTrue(exception.message!!.contains("numeric host boundary values only"))
    }

    @Test
    fun hostFunctionStackOverflowShouldBeWrapped() {
        val func =
            HostFunction(
                "console",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                WasmFunctionHandle { _, _ -> throw StackOverflowError("simulated stack overflow") },
            )
        val instance =
            Instance.builder(loadModule("compiled/host-function.wat.wasm"))
                .withImportValues(ImportValues.builder().addFunction(func).build())
                .build()
        val logIt = instance.export("logIt")
        assertThrows(StackOverflowError::class.java) { logIt.apply() }
    }

    companion object {
        private const val AndroidPulleyUnavailableReason =
            "Wasmtime Pulley execution is not linked on this Android runtime"

        private fun loadModule(fileName: String): WasmModule =
            Parser.parse(CorpusResources.getResource(fileName))

        private class MockPulleyProvider : PulleyExecutionProvider {
            val createCalls = AtomicInteger()

            override fun availability(): ExecutionBackendAvailability =
                ExecutionBackendAvailability(available = true)

            override fun create(
                module: WasmModule,
                imports: ImportValues,
                hostInstance: Instance,
            ): PlatformInstanceExecution {
                createCalls.incrementAndGet()
                return object : PlatformInstanceExecution {
                    override val backend: ExecutionBackend = ExecutionBackend.PULLEY

                    override fun export(name: String): ExportFunction {
                        assertEquals("add", name)
                        return ExportFunction { args -> longArrayOf(args[0] + args[1]) }
                    }

                    override fun exportType(name: String): FunctionType {
                        assertEquals("add", name)
                        return FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))
                    }

                    override fun memory(name: String): Memory =
                        throw InvalidException("Unknown export with name $name")

                    override fun memory(index: Int): Memory? = null
                }
            }
        }

        private class UnavailablePulleyProvider(
            private val reason: String,
        ) : PulleyExecutionProvider {
            val createCalls = AtomicInteger()

            override fun availability(): ExecutionBackendAvailability =
                ExecutionBackendAvailability(available = false, reason = reason)

            override fun create(
                module: WasmModule,
                imports: ImportValues,
                hostInstance: Instance,
            ): PlatformInstanceExecution {
                createCalls.incrementAndGet()
                throw WasmEngineException("should not create unavailable Pulley execution")
            }
        }

        private class WrongBackendPulleyProvider : PulleyExecutionProvider {
            val createCalls = AtomicInteger()

            override fun availability(): ExecutionBackendAvailability =
                ExecutionBackendAvailability(available = true)

            override fun create(
                module: WasmModule,
                imports: ImportValues,
                hostInstance: Instance,
            ): PlatformInstanceExecution {
                createCalls.incrementAndGet()
                return object : PlatformInstanceExecution {
                    override val backend: ExecutionBackend = ExecutionBackend.NATIVE

                    override fun export(name: String): ExportFunction =
                        throw UnsupportedOperationException("unused")

                    override fun exportType(name: String): FunctionType =
                        throw UnsupportedOperationException("unused")

                    override fun memory(name: String): Memory =
                        throw UnsupportedOperationException("unused")

                    override fun memory(index: Int): Memory? = null
                }
            }
        }

        private fun tryBuildPulley(
            module: WasmModule,
            imports: ImportValues = ImportValues.empty(),
        ): Instance? =
            try {
                Instance.builder(module)
                    .withImportValues(imports)
                    .withExecutionBackend(ExecutionBackend.PULLEY)
                    .build()
            } catch (e: WasmEngineException) {
                val message = e.message ?: ""
                if (
                    message.contains("Wasmtime Pulley execution is not linked") ||
                        message.contains("needs JVM native access")
                ) {
                    return null
                }
                throw e
            }

        private fun sharedTableCallerModule(): ByteArray =
            wasmModule(
                section(0x01, b(0x01, 0x60, 0x00, 0x01, 0x7f)),
                section(0x03, b(0x01, 0x00)),
                section(0x04, b(0x01, 0x70, 0x00, 0x0a)),
                section(
                    0x07,
                    b(
                        0x02,
                        0x0c,
                        0x73,
                        0x68,
                        0x61,
                        0x72,
                        0x65,
                        0x64,
                        0x2d,
                        0x74,
                        0x61,
                        0x62,
                        0x6c,
                        0x65,
                        0x01,
                        0x00,
                        0x06,
                        0x63,
                        0x61,
                        0x6c,
                        0x6c,
                        0x2d,
                        0x37,
                        0x00,
                        0x00,
                    ),
                ),
                section(0x0a, b(0x01, 0x07, 0x00, 0x41, 0x07, 0x11, 0x00, 0x00, 0x0b)),
            )

        private fun importedTableInitializerModule(): ByteArray =
            wasmModule(
                section(0x01, b(0x01, 0x60, 0x00, 0x01, 0x7f)),
                section(
                    0x02,
                    b(
                        0x01,
                        0x03,
                        0x65,
                        0x6e,
                        0x76,
                        0x0c,
                        0x73,
                        0x68,
                        0x61,
                        0x72,
                        0x65,
                        0x64,
                        0x2d,
                        0x74,
                        0x61,
                        0x62,
                        0x6c,
                        0x65,
                        0x01,
                        0x70,
                        0x00,
                        0x0a,
                    ),
                ),
                section(0x03, b(0x01, 0x00)),
                section(0x09, b(0x01, 0x00, 0x41, 0x07, 0x0b, 0x01, 0x00)),
                section(0x0a, b(0x01, 0x05, 0x00, 0x41, 0xc3, 0x00, 0x0b)),
            )

        private fun exportedMemoryHostFunctionModule(): ByteArray =
            wasmModule(
                section(0x01, b(0x02, 0x60, 0x02, 0x7f, 0x7f, 0x00, 0x60, 0x00, 0x00)),
                section(
                    0x02,
                    b(
                        0x01,
                        0x07,
                        0x63,
                        0x6f,
                        0x6e,
                        0x73,
                        0x6f,
                        0x6c,
                        0x65,
                        0x03,
                        0x6c,
                        0x6f,
                        0x67,
                        0x00,
                        0x00,
                    ),
                ),
                section(0x03, b(0x01, 0x01)),
                section(0x05, b(0x01, 0x00, 0x01)),
                section(
                    0x07,
                    b(
                        0x02,
                        0x06,
                        0x6d,
                        0x65,
                        0x6d,
                        0x6f,
                        0x72,
                        0x79,
                        0x02,
                        0x00,
                        0x05,
                        0x6c,
                        0x6f,
                        0x67,
                        0x49,
                        0x74,
                        0x00,
                        0x01,
                    ),
                ),
                section(0x0a, b(0x01, 0x08, 0x00, 0x41, 0x0d, 0x41, 0x00, 0x10, 0x00, 0x0b)),
                section(
                    0x0b,
                    b(
                        0x01,
                        0x00,
                        0x41,
                        0x00,
                        0x0b,
                        0x0d,
                        0x48,
                        0x65,
                        0x6c,
                        0x6c,
                        0x6f,
                        0x2c,
                        0x20,
                        0x57,
                        0x6f,
                        0x72,
                        0x6c,
                        0x64,
                        0x21,
                    ),
                ),
            )

        private fun wasmModule(vararg sections: ByteArray): ByteArray =
            b(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00) +
                sections.fold(ByteArray(0)) { acc, section -> acc + section }

        private fun section(id: Int, body: ByteArray): ByteArray = b(id, body.size) + body

        private fun b(vararg bytes: Int): ByteArray = bytes.map { it.toByte() }.toByteArray()

        private fun factorial(number: Int): Long {
            var result = 1L
            for (factor in 2..number) {
                result *= factor.toLong()
            }
            return result
        }
    }
}
