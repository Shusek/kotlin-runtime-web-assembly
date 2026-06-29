@file:OptIn(ExperimentalWasmJsInterop::class)

package uk.shusek.krwa.runtime

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

private fun testJsObject(): JsAny = js("({ marker: 42 })")

@Suppress("UNUSED_PARAMETER")
private fun sameJsValue(left: JsAny?, right: JsAny?): Boolean = js("left === right")

class NativeWasmTest {
    @Test
    fun preservesOriginalBytesForNativeInstantiation() {
        val module = WasmParser.parse(ADD_WASM)

        assertContentEquals(ADD_WASM, module.originalBytes())
    }

    @Test
    fun reportsNativeHostFeatures() {
        assertTrue(NativeWasmFeatures.available())
        assertTrue(NativeWasmFeatures.supportsValueType(ValType.I32))
        assertTrue(NativeWasmFeatures.supportsValueType(ValType.I64))
        assertTrue(NativeWasmFeatures.supportsValueType(ValType.F32))
        assertTrue(NativeWasmFeatures.supportsValueType(ValType.F64))
        assertTrue(NativeWasmFeatures.supportsValueType(ValType.ExternRef))
        assertFalse(NativeWasmFeatures.supportsValueType(ValType.V128))
        assertTrue(NativeWasmFeatures.supportsTableElement(ValType.FuncRef))
        assertTrue(NativeWasmFeatures.supportsTableElement(ValType.ExternRef))
        assertEquals(
            NativeWasmFeatures.supportsExceptionTags(),
            NativeWasmFeatures.supportsTag(FunctionType.empty()),
        )
        assertFalse(
            NativeWasmFeatures.supportsTag(FunctionType.of(emptyList(), listOf(ValType.I32)))
        )
    }

    @Test
    fun callsExportedI32FunctionThroughNativeEngine() {
        val module = WasmParser.parse(ADD_WASM)
        val instance = NativeWasmInstance.instantiate(module)

        assertEquals(42, instance.export("add").apply(19, 23)[0].toInt())
    }

    @Test
    fun selectsNativeBackendAutomaticallyWhenSupported() {
        val module = WasmParser.parse(ADD_WASM)
        val execution = WasmJsExecution.instantiate(module)

        assertEquals(42, execution.native().export("add").apply(19, 23)[0].toInt())
        assertEquals(42, execution.export("add").apply(19, 23)[0].toInt())
        assertEquals(FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)), execution.exportType("add"))
    }

    @Test
    fun instanceBuilderUsesNativeBackendByDefaultOnWasmJs() {
        val instance = Instance.builder(WasmParser.parse(ADD_WASM)).build()

        assertEquals(ExecutionBackend.NATIVE, instance.executionBackend())
        assertEquals(42, instance.export("add").apply(19, 23)[0].toInt())
        assertEquals(
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            instance.exportType("add"),
        )
    }

    @Test
    fun exposesExportedMemoryThroughNativeBackend() {
        val execution = WasmJsExecution.instantiate(WasmParser.parse(EXPORTED_MEMORY_WASM))
        val memory = execution.memory("memory")

        memory.writeI32(4, 0x1122_3344)
        assertEquals(0x1122_3344, memory.readInt(4))
    }

    @Test
    fun nativeBackendHonorsInstanceMemoryLimit() {
        val instance =
            Instance.builder(WasmParser.parse(GROW_MEMORY_WASM))
                .withExecutionBackend(ExecutionBackend.NATIVE)
                .withMemoryLimits(MemoryLimits(1, 1))
                .build()
        val memory = instance.memory()

        assertEquals(ExecutionBackend.NATIVE, instance.executionBackend())
        assertEquals(1, memory.pages())
        assertEquals(1, memory.maximumPages())
        assertEquals(-1, instance.export("grow").apply()[0].toInt())
        assertEquals(1, memory.pages())
    }

    @Test
    fun exposesExportedGlobalThroughNativeBackend() {
        val execution = WasmJsExecution.instantiate(WasmParser.parse(MUTABLE_GLOBAL_WASM))
        val global = execution.global("g")

        assertEquals(ValType.I32, global.type())
        assertEquals(MutabilityType.Var, global.mutability())
        assertEquals(1, global.value().toInt())

        global.setValue(9)

        assertEquals(9, global.value().toInt())
        assertEquals(9, global.native().value().toInt())
    }

    @Test
    fun exposesExportedTableThroughNativeBackend() {
        val execution = WasmJsExecution.instantiate(WasmParser.parse(CALL_INDIRECT_EXPORT_WASM))
        val table = execution.table("shared-table")

        assertEquals(5, table.size())
        assertEquals(ValType.FuncRef, table.elementType())
        assertEquals(5, table.limits().min().toInt())
        assertEquals(5, table.native().size())
    }

    @Test
    fun exposesExportedTagThroughNativeBackend() {
        if (!NativeWasmFeatures.supportsExceptionTags()) {
            return
        }

        val execution = WasmJsExecution.instantiate(WasmParser.parse(EXCEPTIONS_WASM))
        val tag = execution.tag("e0")

        assertEquals(FunctionType.empty(), tag.type())
        assertEquals(FunctionType.empty(), tag.native().type())
    }

    @Test
    fun wasmJsExecutionDoesNotFallbackAfterNativeRuntimeTrap() {
        val failure =
            assertFailsWith<Throwable> {
                WasmJsExecution.instantiate(WasmParser.parse(START_TRAP_WASM))
            }

        assertTrue(failure is NativeWasmRuntimeException)
    }

    @Test
    fun nativeExecutionRejectsNonNativeImports() {
        val module = WasmParser.parse(IMPORTED_GLOBAL_WASM)
        val imports =
            ImportValues.builder()
                .addGlobal(ImportGlobal("env", "g", GlobalInstance(Value.i32(77))))
                .build()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                WasmJsExecution.instantiate(module, imports)
            }

        assertTrue(failure.message!!.contains("NativeWasmGlobal"))
    }

    @Test
    fun bridgesI64ValuesThroughBigInt() {
        val module = WasmParser.parse(MEMORY_WASM)
        val instance = NativeWasmInstance.instantiate(module)

        assertEquals(
            0x0102_0304_0506_0708L,
            instance.export("run64").apply(0x0102_0304_0506_0708L)[0],
        )
    }

    @Test
    fun bridgesMultiValueHostFunctionResultsThroughNativeEngine() {
        val module = WasmParser.parse(HOST_MULTI_VALUE_WASM)
        val imports =
            NativeWasmImports.builder()
                .addFunction(
                    "env",
                    "pair",
                    FunctionType.of(emptyList(), listOf(ValType.I32, ValType.I64)),
                    NativeWasmHostFunction { _, _ -> longArrayOf(7, 9) },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        assertContentEquals(longArrayOf(7, 9), instance.export("run").apply())
    }

    @Test
    fun exposesNativeMemoryExportThroughMemoryApi() {
        val module = WasmParser.parse(EXPORTED_MEMORY_WASM)
        val instance = NativeWasmInstance.instantiate(module)
        val memory = instance.memory("memory")

        assertEquals(1, memory.pages())
        memory.writeI32(16, 0x1234_5678)

        assertEquals(0x1234_5678, memory.readInt(16))
    }

    @Test
    fun exposesNativeGlobalExport() {
        val module = WasmParser.parse(EXPORTED_GLOBAL_WASM)
        val instance = NativeWasmInstance.instantiate(module)

        assertEquals(42, instance.global("answer").value().toInt())
    }

    @Test
    fun suppliesNativeGlobalImport() {
        val module = WasmParser.parse(IMPORTED_GLOBAL_WASM)
        val imports =
            NativeWasmImports.builder()
                .addGlobal(
                    "env",
                    "g",
                    NativeWasmGlobal.create(ValType.I32, MutabilityType.Const, 77),
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        assertEquals(77, instance.export("get").apply()[0].toInt())
    }

    @Test
    fun importsInstanceExportThroughNativeImportValuesAdapter() {
        val source = Instance.builder(WasmParser.parse(ADD_WASM)).build()
        val importValues =
            ImportValues.builder()
                .addFunction(ImportFunction.exportAsImport("env", "add", source, "add"))
                .build()
        val native = NativeWasmInstance.instantiate(
            WasmParser.parse(IMPORTED_ADD_WASM),
            NativeWasmImports.fromImportValues(importValues),
        )

        assertEquals(42, native.export("run").apply()[0].toInt())
    }

    @Test
    fun sharesNativeTableAcrossInstances() {
        val producerModule = WasmParser.parse(CALL_INDIRECT_EXPORT_WASM)
        val producer = NativeWasmInstance.instantiate(producerModule)
        val table = producer.table("shared-table")

        assertEquals(5, table.size())
        assertEquals(42, producer.export("call-self").apply()[0].toInt())

        val consumerModule = WasmParser.parse(CALL_INDIRECT_IMPORT_WASM)
        val imports =
            NativeWasmImports.builder()
                .addTable("test", "shared-table", table)
                .build()
        NativeWasmInstance.instantiate(consumerModule, imports)

        assertEquals(88, producer.export("call-other").apply()[0].toInt())
    }

    @Test
    fun rejectsMismatchedNativeFunctionImport() {
        val module = WasmParser.parse(HOST_FUNCTION_WASM)
        val imports =
            NativeWasmImports.builder()
                .addFunction(
                    "console",
                    "log",
                    FunctionType.empty(),
                    NativeWasmHostFunction { _, _ -> null },
                )
                .build()

        assertFailsWith<UnlinkableException> {
            NativeWasmInstance.instantiate(module, imports)
        }
    }

    @Test
    fun rejectsMismatchedNativeMemoryImport() {
        val module = WasmParser.parse(IMPORTED_MEMORY_WASM)
        val imports =
            NativeWasmImports.builder()
                .addMemory("env", "memory", NativeWasmMemory.create(MemoryLimits(0, 1)))
                .build()

        assertFailsWith<UnlinkableException> {
            NativeWasmInstance.instantiate(module, imports)
        }
    }

    @Test
    fun rejectsMismatchedNativeGlobalImport() {
        val module = WasmParser.parse(IMPORTED_GLOBAL_WASM)
        val imports =
            NativeWasmImports.builder()
                .addGlobal(
                    "env",
                    "g",
                    NativeWasmGlobal.create(ValType.I64, MutabilityType.Const, 77),
                )
                .build()

        assertFailsWith<UnlinkableException> {
            NativeWasmInstance.instantiate(module, imports)
        }
    }

    @Test
    fun rejectsMismatchedNativeTableImport() {
        val module = WasmParser.parse(IMPORTED_TABLE_WASM)
        val imports =
            NativeWasmImports.builder()
                .addTable(
                    "env",
                    "table",
                    NativeWasmTable.create(ValType.ExternRef, TableLimits(2, 2)),
                )
                .build()

        assertFailsWith<UnlinkableException> {
            NativeWasmInstance.instantiate(module, imports)
        }
    }

    @Test
    fun rejectsMismatchedNativeTagImport() {
        if (!NativeWasmFeatures.supportsExceptionTags()) {
            return
        }

        val module = WasmParser.parse(IMPORTED_TAG_WASM)
        val imports =
            NativeWasmImports.builder()
                .addTag(
                    "test",
                    "e0",
                    NativeWasmTag.create(FunctionType.accepting(ValType.I32)),
                )
                .build()

        assertFailsWith<UnlinkableException> {
            NativeWasmInstance.instantiate(module, imports)
        }
    }

    @Test
    fun appliesNativeAtomicsForSharedMemory() {
        if (!NativeWasmFeatures.supportsSharedMemory()) {
            return
        }

        val memory = NativeWasmMemory.create(MemoryLimits(1, 1, true))

        memory.atomicWriteInt(0, 7)
        assertEquals(7, memory.atomicAddInt(0, 5))
        assertEquals(12, memory.atomicReadInt(0))
        assertEquals(12, memory.atomicCmpxchgInt(0, 12, 99))
        assertEquals(99, memory.atomicXchgInt(0, 3))
        assertEquals(3, memory.atomicReadInt(0))

        memory.atomicWriteLong(8, 10L)
        assertEquals(10L, memory.atomicAddLong(8, 4L))
        assertEquals(14L, memory.atomicReadLong(8))

        memory.atomicWriteShort(16, 0x0033)
        assertEquals(0x0033, memory.atomicOrShort(16, 0x000c).toInt())
        assertEquals(0x003f, memory.atomicReadShort(16).toInt())

        memory.atomicWriteByte(18, 0x03)
        assertEquals(0x03, memory.atomicXorByte(18, 0x07).toInt())
        assertEquals(0x04, memory.atomicReadByte(18).toInt())
        assertEquals(0, memory.atomicNotify(0, 1))
    }

    @Test
    fun bridgesExternRefValuesThroughNativeReferenceHandles() {
        val module = WasmParser.parse(EXTERNREF_WASM)
        val hostObject = testJsObject()
        val imports =
            NativeWasmImports.builder()
                .addFunction(
                    "env",
                    "get_host_object",
                    FunctionType.of(emptyList(), listOf(ValType.ExternRef)),
                    NativeWasmHostFunction { instance, _ ->
                        longArrayOf(instance.storeReference(hostObject))
                    },
                )
                .addFunction(
                    "env",
                    "is_null",
                    FunctionType.of(listOf(ValType.ExternRef), listOf(ValType.I32)),
                    NativeWasmHostFunction { instance, args ->
                        longArrayOf(if (instance.referenceValue(args[0]) == null) 1 else 0)
                    },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        val jsObject = testJsObject()
        val inputRef = instance.storeReference(jsObject)
        val roundTripRef = instance.export("process_externref").apply(inputRef)[0]

        assertTrue(sameJsValue(jsObject, instance.referenceValue(roundTripRef)))
        assertEquals(
            1,
            instance.export("is_null").apply(Value.REF_NULL_VALUE.toLong())[0].toInt(),
        )

        val hostRef = instance.export("get_host_object").apply()[0]

        assertTrue(sameJsValue(hostObject, instance.referenceValue(hostRef)))
        assertEquals(0, instance.export("is_null").apply(hostRef)[0].toInt())
    }

    @Test
    fun bridgesAnyRefValuesThroughNativeReferenceHandles() {
        val module = WasmParser.parse(ANYREF_WASM)
        val hostObject = testJsObject()
        val imports =
            NativeWasmImports.builder()
                .addFunction(
                    "env",
                    "get_host_object",
                    FunctionType.of(emptyList(), listOf(ValType.AnyRef)),
                    NativeWasmHostFunction { instance, _ ->
                        longArrayOf(instance.storeReference(hostObject))
                    },
                )
                .addFunction(
                    "env",
                    "is_null",
                    FunctionType.of(listOf(ValType.AnyRef), listOf(ValType.I32)),
                    NativeWasmHostFunction { instance, args ->
                        longArrayOf(if (instance.referenceValue(args[0]) == null) 1 else 0)
                    },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        val jsObject = testJsObject()
        val inputRef = instance.storeReference(jsObject)
        val roundTripRef = instance.export("process_externref").apply(inputRef)[0]

        assertTrue(sameJsValue(jsObject, instance.referenceValue(roundTripRef)))
        assertEquals(
            1,
            instance.export("is_null").apply(Value.REF_NULL_VALUE.toLong())[0].toInt(),
        )

        val hostRef = instance.export("get_host_object").apply()[0]

        assertTrue(sameJsValue(hostObject, instance.referenceValue(hostRef)))
        assertEquals(0, instance.export("is_null").apply(hostRef)[0].toInt())
    }

    @Test
    fun createsNativeReferenceGlobalsAndTables() {
        val jsObject = testJsObject()
        val anyRefGlobal =
            NativeWasmGlobal.createReference(ValType.AnyRef, MutabilityType.Var, jsObject)

        assertTrue(sameJsValue(jsObject, anyRefGlobal.jsValue()))

        val nextObject = testJsObject()
        anyRefGlobal.setJsValue(nextObject)
        assertTrue(sameJsValue(nextObject, anyRefGlobal.jsValue()))

        val funcRefGlobal =
            NativeWasmGlobal.createReference(ValType.FuncRef, MutabilityType.Var, null)
        assertEquals(Value.REF_NULL_VALUE.toLong(), funcRefGlobal.value())

        val funcRefTable = NativeWasmTable.create(ValType.FuncRef, TableLimits(1, 1))
        assertEquals(1, funcRefTable.size())

        val anyRefTable = NativeWasmTable.create(ValType.AnyRef, TableLimits(1, 1))
        anyRefTable.set(0, jsObject)
        assertTrue(sameJsValue(jsObject, anyRefTable.get(0)))
    }

    @Test
    fun exposesNativeTagExportAndRunsExceptionHandling() {
        if (!NativeWasmFeatures.supportsExceptionTags()) {
            return
        }

        val module = WasmParser.parse(EXCEPTIONS_WASM)
        val instance = NativeWasmInstance.instantiate(module)
        val tag = instance.tag("e0")

        assertEquals(0, tag.type().params().size)
        assertEquals(0, tag.type().returns().size)
        assertEquals(3, instance.export("catch-complex-1").apply(0)[0].toInt())
        assertEquals(4, instance.export("catch-complex-1").apply(1)[0].toInt())
        assertEquals(0, instance.export("catchless-try").apply(0)[0].toInt())
        assertEquals(1, instance.export("catchless-try").apply(1)[0].toInt())
    }

    @Test
    fun suppliesNativeTagImport() {
        if (!NativeWasmFeatures.supportsExceptionTags()) {
            return
        }

        val module = WasmParser.parse(IMPORTED_TAG_WASM)
        val imports =
            NativeWasmImports.builder()
                .addTag(
                    "test",
                    "e0",
                    NativeWasmTag.create(FunctionType.of(emptyList(), emptyList())),
                )
                .addFunction(
                    "test",
                    "throw",
                    FunctionType.of(emptyList(), emptyList()),
                    NativeWasmHostFunction { _, _ -> null },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        assertEquals(1, instance.export("catch-imported").apply()[0].toInt())
    }

    @Test
    fun throwsNativeTagImportFromHostFunction() {
        if (!NativeWasmFeatures.supportsExceptionTags()) {
            return
        }

        val module = WasmParser.parse(IMPORTED_TAG_WASM)
        val tag = NativeWasmTag.create(FunctionType.of(emptyList(), emptyList()))
        val imports =
            NativeWasmImports.builder()
                .addTag("test", "e0", tag)
                .addFunction(
                    "test",
                    "throw",
                    FunctionType.of(emptyList(), emptyList()),
                    NativeWasmHostFunction { instance, _ -> tag.throwException(instance) },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        assertEquals(2, instance.export("catch-imported").apply()[0].toInt())
    }

    @Test
    fun callsHostFunctionImportThroughNativeEngine() {
        val module = WasmParser.parse(HOST_FUNCTION_WASM)
        var calls = 0
        var lastPtr = -1
        var lastLen = -1
        val imports =
            NativeWasmImports.builder()
                .addFunction(
                    "console",
                    "log",
                    FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                    NativeWasmHostFunction { _, args ->
                        calls++
                        lastPtr = args[0].toInt()
                        lastLen = args[1].toInt()
                        null
                    },
                )
                .build()
        val instance = NativeWasmInstance.instantiate(module, imports)

        instance.export("logIt").apply()

        assertEquals(10, calls)
        assertEquals(13, lastPtr)
        assertEquals(0, lastLen)
    }

    private companion object {
        private val ADD_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x07, 0x01, 0x60,
                0x02, 0x7f, 0x7f, 0x01,
                0x7f, 0x03, 0x02, 0x01,
                0x00, 0x07, 0x07, 0x01,
                0x03, 0x61, 0x64, 0x64,
                0x00, 0x00, 0x0a, 0x09,
                0x01, 0x07, 0x00, 0x20,
                0x00, 0x20, 0x01, 0x6a,
                0x0b, 0x00, 0x18, 0x04,
                0x6e, 0x61, 0x6d, 0x65,
                0x01, 0x06, 0x01, 0x00,
                0x03, 0x61, 0x64, 0x64,
                0x02, 0x09, 0x01, 0x00,
                0x02, 0x00, 0x01, 0x61,
                0x01, 0x01, 0x62,
            )

        private val MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0b, 0x02, 0x60,
                0x01, 0x7f, 0x01, 0x7f,
                0x60, 0x01, 0x7e, 0x01,
                0x7e, 0x03, 0x03, 0x02,
                0x00, 0x01, 0x05, 0x03,
                0x01, 0x00, 0x01, 0x07,
                0x11, 0x02, 0x05, 0x72,
                0x75, 0x6e, 0x33, 0x32,
                0x00, 0x00, 0x05, 0x72,
                0x75, 0x6e, 0x36, 0x34,
                0x00, 0x01, 0x0a, 0x21,
                0x02, 0x0f, 0x00, 0x41,
                0x00, 0x20, 0x00, 0x36,
                0x02, 0x00, 0x41, 0x00,
                0x28, 0x02, 0x00, 0x0f,
                0x0b, 0x0f, 0x00, 0x41,
                0x00, 0x20, 0x00, 0x37,
                0x03, 0x00, 0x41, 0x00,
                0x29, 0x03, 0x00, 0x0f,
                0x0b,
            )

        private val HOST_FUNCTION_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x09, 0x02, 0x60,
                0x02, 0x7f, 0x7f, 0x00,
                0x60, 0x00, 0x00, 0x02,
                0x0f, 0x01, 0x07, 0x63,
                0x6f, 0x6e, 0x73, 0x6f,
                0x6c, 0x65, 0x03, 0x6c,
                0x6f, 0x67, 0x00, 0x00,
                0x03, 0x02, 0x01, 0x01,
                0x05, 0x03, 0x01, 0x00,
                0x01, 0x07, 0x09, 0x01,
                0x05, 0x6c, 0x6f, 0x67,
                0x49, 0x74, 0x00, 0x01,
                0x0a, 0x21, 0x01, 0x1f,
                0x01, 0x01, 0x7f, 0x01,
                0x01, 0x41, 0x01, 0x1a,
                0x41, 0x0a, 0x21, 0x00,
                0x03, 0x40, 0x41, 0x0d,
                0x41, 0x00, 0x10, 0x00,
                0x20, 0x00, 0x41, 0x01,
                0x6b, 0x22, 0x00, 0x0d,
                0x00, 0x0b, 0x0b, 0x0b,
                0x14, 0x01, 0x00, 0x41,
                0x00, 0x0b, 0x0e, 0x48,
                0x65, 0x6c, 0x6c, 0x6f,
                0x2c, 0x20, 0x57, 0x6f,
                0x72, 0x6c, 0x64, 0x21,
                0x00, 0x00, 0x23, 0x04,
                0x6e, 0x61, 0x6d, 0x65,
                0x01, 0x06, 0x01, 0x00,
                0x03, 0x6c, 0x6f, 0x67,
                0x02, 0x08, 0x01, 0x01,
                0x01, 0x00, 0x03, 0x76,
                0x61, 0x72, 0x09, 0x0a,
                0x01, 0x00, 0x07, 0x2e,
                0x72, 0x6f, 0x64, 0x61,
                0x74, 0x61,
            )

        private val HOST_MULTI_VALUE_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x06, 0x01, 0x60,
                0x00, 0x02, 0x7f, 0x7e,
                0x02, 0x0c, 0x01, 0x03,
                0x65, 0x6e, 0x76, 0x04,
                0x70, 0x61, 0x69, 0x72,
                0x00, 0x00, 0x03, 0x02,
                0x01, 0x00, 0x07, 0x07,
                0x01, 0x03, 0x72, 0x75,
                0x6e, 0x00, 0x01, 0x0a,
                0x06, 0x01, 0x04, 0x00,
                0x10, 0x00, 0x0b,
            )

        private val EXPORTED_MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x05, 0x03, 0x01, 0x00,
                0x01, 0x07, 0x0a, 0x01,
                0x06, 0x6d, 0x65, 0x6d,
                0x6f, 0x72, 0x79, 0x02,
                0x00,
            )

        private val GROW_MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60,
                0x00, 0x01, 0x7f, 0x03,
                0x02, 0x01, 0x00, 0x05,
                0x04, 0x01, 0x01, 0x01,
                0x0a, 0x07, 0x11, 0x02,
                0x06, 0x6d, 0x65, 0x6d,
                0x6f, 0x72, 0x79, 0x02,
                0x00, 0x04, 0x67, 0x72,
                0x6f, 0x77, 0x00, 0x00,
                0x0a, 0x08, 0x01, 0x06,
                0x00, 0x41, 0x01, 0x40,
                0x00, 0x0b,
            )

        private val MUTABLE_GLOBAL_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x06, 0x06, 0x01, 0x7f,
                0x01, 0x41, 0x01, 0x0b,
                0x07, 0x05, 0x01, 0x01,
                0x67, 0x03, 0x00,
            )

        private val START_TRAP_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x04, 0x01, 0x60,
                0x00, 0x00, 0x03, 0x02,
                0x01, 0x00, 0x08, 0x01,
                0x00, 0x0a, 0x05, 0x01,
                0x03, 0x00, 0x00, 0x0b,
            )

        private val EXPORTED_GLOBAL_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x06, 0x06, 0x01, 0x7f,
                0x00, 0x41, 0x2a, 0x0b,
                0x07, 0x0a, 0x01, 0x06,
                0x61, 0x6e, 0x73, 0x77,
                0x65, 0x72, 0x03, 0x00,
            )

        private val IMPORTED_GLOBAL_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60,
                0x00, 0x01, 0x7f, 0x02,
                0x0a, 0x01, 0x03, 0x65,
                0x6e, 0x76, 0x01, 0x67,
                0x03, 0x7f, 0x00, 0x03,
                0x02, 0x01, 0x00, 0x07,
                0x07, 0x01, 0x03, 0x67,
                0x65, 0x74, 0x00, 0x00,
                0x0a, 0x06, 0x01, 0x04,
                0x00, 0x23, 0x00, 0x0b,
            )

        private val IMPORTED_ADD_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0b, 0x02, 0x60,
                0x02, 0x7f, 0x7f, 0x01,
                0x7f, 0x60, 0x00, 0x01,
                0x7f, 0x02, 0x0b, 0x01,
                0x03, 0x65, 0x6e, 0x76,
                0x03, 0x61, 0x64, 0x64,
                0x00, 0x00, 0x03, 0x02,
                0x01, 0x01, 0x07, 0x07,
                0x01, 0x03, 0x72, 0x75,
                0x6e, 0x00, 0x01, 0x0a,
                0x0a, 0x01, 0x08, 0x00,
                0x41, 0x13, 0x41, 0x17,
                0x10, 0x00, 0x0b,
            )

        private val IMPORTED_MEMORY_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x02, 0x0f, 0x01, 0x03,
                0x65, 0x6e, 0x76, 0x06,
                0x6d, 0x65, 0x6d, 0x6f,
                0x72, 0x79, 0x02, 0x00,
                0x01,
            )

        private val IMPORTED_TABLE_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x02, 0x0f, 0x01, 0x03,
                0x65, 0x6e, 0x76, 0x05,
                0x74, 0x61, 0x62, 0x6c,
                0x65, 0x01, 0x70, 0x00,
                0x02,
            )

        private val EXTERNREF_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0f, 0x03, 0x60,
                0x00, 0x01, 0x6f, 0x60,
                0x01, 0x6f, 0x01, 0x7f,
                0x60, 0x01, 0x6f, 0x01,
                0x6f, 0x02, 0x25, 0x02,
                0x03, 0x65, 0x6e, 0x76,
                0x0f, 0x67, 0x65, 0x74,
                0x5f, 0x68, 0x6f, 0x73,
                0x74, 0x5f, 0x6f, 0x62,
                0x6a, 0x65, 0x63, 0x74,
                0x00, 0x00, 0x03, 0x65,
                0x6e, 0x76, 0x07, 0x69,
                0x73, 0x5f, 0x6e, 0x75,
                0x6c, 0x6c, 0x00, 0x01,
                0x03, 0x02, 0x01, 0x02,
                0x07, 0x31, 0x03, 0x11,
                0x70, 0x72, 0x6f, 0x63,
                0x65, 0x73, 0x73, 0x5f,
                0x65, 0x78, 0x74, 0x65,
                0x72, 0x6e, 0x72, 0x65,
                0x66, 0x00, 0x02, 0x07,
                0x69, 0x73, 0x5f, 0x6e,
                0x75, 0x6c, 0x6c, 0x00,
                0x01, 0x0f, 0x67, 0x65,
                0x74, 0x5f, 0x68, 0x6f,
                0x73, 0x74, 0x5f, 0x6f,
                0x62, 0x6a, 0x65, 0x63,
                0x74, 0x00, 0x00, 0x0a,
                0x06, 0x01, 0x04, 0x00,
                0x20, 0x00, 0x0b, 0x00,
                0x41, 0x04, 0x6e, 0x61,
                0x6d, 0x65, 0x01, 0x2e,
                0x03, 0x00, 0x0f, 0x67,
                0x65, 0x74, 0x5f, 0x68,
                0x6f, 0x73, 0x74, 0x5f,
                0x6f, 0x62, 0x6a, 0x65,
                0x63, 0x74, 0x01, 0x07,
                0x69, 0x73, 0x5f, 0x6e,
                0x75, 0x6c, 0x6c, 0x02,
                0x11, 0x70, 0x72, 0x6f,
                0x63, 0x65, 0x73, 0x73,
                0x5f, 0x65, 0x78, 0x74,
                0x65, 0x72, 0x6e, 0x72,
                0x65, 0x66, 0x02, 0x0a,
                0x01, 0x02, 0x01, 0x00,
                0x05, 0x69, 0x6e, 0x70,
                0x75, 0x74,
            )

        private val ANYREF_WASM =
            EXTERNREF_WASM.copyOf().also {
                it[14] = 0x6e
                it[17] = 0x6e
                it[22] = 0x6e
                it[24] = 0x6e
            }

        private val EXCEPTIONS_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x0d, 0x03, 0x60,
                0x00, 0x00, 0x60, 0x01,
                0x7f, 0x00, 0x60, 0x01,
                0x7f, 0x01, 0x7f, 0x03,
                0x04, 0x03, 0x02, 0x02,
                0x02, 0x0d, 0x09, 0x04,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x07, 0x28, 0x03, 0x02,
                0x65, 0x30, 0x04, 0x00,
                0x0f, 0x63, 0x61, 0x74,
                0x63, 0x68, 0x2d, 0x63,
                0x6f, 0x6d, 0x70, 0x6c,
                0x65, 0x78, 0x2d, 0x31,
                0x00, 0x00, 0x0d, 0x63,
                0x61, 0x74, 0x63, 0x68,
                0x6c, 0x65, 0x73, 0x73,
                0x2d, 0x74, 0x72, 0x79,
                0x00, 0x02, 0x0a, 0x5e,
                0x03, 0x35, 0x00, 0x02,
                0x40, 0x1f, 0x7f, 0x01,
                0x00, 0x01, 0x00, 0x02,
                0x40, 0x1f, 0x7f, 0x01,
                0x00, 0x00, 0x00, 0x20,
                0x00, 0x45, 0x04, 0x40,
                0x08, 0x00, 0x05, 0x20,
                0x00, 0x41, 0x01, 0x46,
                0x04, 0x40, 0x08, 0x01,
                0x05, 0x08, 0x02, 0x0b,
                0x0b, 0x41, 0x02, 0x0b,
                0x0c, 0x01, 0x0b, 0x41,
                0x03, 0x0b, 0x0f, 0x0b,
                0x41, 0x04, 0x0b, 0x0e,
                0x00, 0x20, 0x00, 0x41,
                0x00, 0x47, 0x04, 0x40,
                0x08, 0x00, 0x0b, 0x41,
                0x00, 0x0b, 0x17, 0x00,
                0x02, 0x40, 0x1f, 0x7f,
                0x01, 0x00, 0x00, 0x00,
                0x1f,
                0x7f, 0x00, 0x20, 0x00,
                0x10, 0x01, 0x0b, 0x0b,
                0x0f, 0x0b, 0x41, 0x01,
                0x0b, 0x00, 0x49, 0x04,
                0x6e, 0x61, 0x6d, 0x65,
                0x01, 0x1a, 0x02, 0x01,
                0x08, 0x74, 0x68, 0x72,
                0x6f, 0x77, 0x2d, 0x69,
                0x66, 0x02, 0x0d, 0x63,
                0x61, 0x74, 0x63, 0x68,
                0x6c, 0x65, 0x73, 0x73,
                0x2d, 0x74, 0x72, 0x79,
                0x03, 0x10, 0x02, 0x00,
                0x02, 0x00, 0x02, 0x68,
                0x31, 0x02, 0x02, 0x68,
                0x30, 0x02, 0x01, 0x00,
                0x01, 0x68, 0x0b, 0x14,
                0x04, 0x00, 0x02, 0x65,
                0x30, 0x01, 0x02, 0x65,
                0x31, 0x02, 0x02, 0x65,
                0x32, 0x03, 0x05, 0x65,
                0x2d, 0x69, 0x33, 0x32,
            )

        private val IMPORTED_TAG_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x08, 0x02, 0x60,
                0x00, 0x00, 0x60, 0x00,
                0x01, 0x7f, 0x02, 0x19,
                0x02, 0x04, 0x74, 0x65,
                0x73, 0x74, 0x02, 0x65,
                0x30, 0x04, 0x00, 0x00,
                0x04, 0x74, 0x65, 0x73,
                0x74, 0x05, 0x74, 0x68,
                0x72, 0x6f, 0x77, 0x00,
                0x00, 0x03, 0x02, 0x01,
                0x01, 0x07, 0x12, 0x01,
                0x0e, 0x63, 0x61, 0x74,
                0x63, 0x68, 0x2d, 0x69,
                0x6d, 0x70, 0x6f, 0x72,
                0x74, 0x65, 0x64, 0x00,
                0x01, 0x0a, 0x15, 0x01,
                0x13, 0x00, 0x02, 0x40,
                0x1f, 0x7f, 0x01, 0x00,
                0x00, 0x00, 0x41, 0x01,
                0x10, 0x00, 0x0b, 0x0f,
                0x0b, 0x41, 0x02, 0x0b,
                0x00, 0x30, 0x04, 0x6e,
                0x61, 0x6d, 0x65, 0x01,
                0x11, 0x01, 0x00, 0x0e,
                0x69, 0x6d, 0x70, 0x6f,
                0x72, 0x74, 0x65, 0x64,
                0x2d, 0x74, 0x68, 0x72,
                0x6f, 0x77, 0x03, 0x06,
                0x01, 0x01, 0x01, 0x00,
                0x01, 0x68, 0x0b, 0x0e,
                0x01, 0x00, 0x0b, 0x69,
                0x6d, 0x70, 0x6f, 0x72,
                0x74, 0x65, 0x64, 0x2d,
                0x65, 0x30,
            )

        private val CALL_INDIRECT_EXPORT_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60,
                0x00, 0x01, 0x7f, 0x03,
                0x05, 0x04, 0x00, 0x00,
                0x00, 0x00, 0x04, 0x04,
                0x01, 0x70, 0x00, 0x05,
                0x07, 0x3b, 0x04, 0x0c,
                0x73, 0x68, 0x61, 0x72,
                0x65, 0x64, 0x2d, 0x74,
                0x61, 0x62, 0x6c, 0x65,
                0x01, 0x00, 0x09, 0x63,
                0x61, 0x6c, 0x6c, 0x2d,
                0x73, 0x65, 0x6c, 0x66,
                0x00, 0x01, 0x0a, 0x63,
                0x61, 0x6c, 0x6c, 0x2d,
                0x6f, 0x74, 0x68, 0x65,
                0x72, 0x00, 0x02, 0x0f,
                0x63, 0x61, 0x6c, 0x6c,
                0x2d, 0x6f, 0x74, 0x68,
                0x65, 0x72, 0x2d, 0x66,
                0x61, 0x69, 0x6c, 0x00,
                0x03, 0x09, 0x07, 0x01,
                0x00, 0x41, 0x00, 0x0b,
                0x01, 0x00, 0x0a, 0x1e,
                0x04, 0x04, 0x00, 0x41,
                0x2a, 0x0b, 0x07, 0x00,
                0x41, 0x00, 0x11, 0x00,
                0x00, 0x0b, 0x07, 0x00,
                0x41, 0x01, 0x11, 0x00,
                0x00, 0x0b, 0x07, 0x00,
                0x41, 0x02, 0x11, 0x00,
                0x00, 0x0b, 0x00, 0x38,
                0x04, 0x6e, 0x61, 0x6d,
                0x65, 0x01, 0x2b, 0x04,
                0x00, 0x04, 0x73, 0x65,
                0x6c, 0x66, 0x01, 0x08,
                0x63, 0x61, 0x6c, 0x6c,
                0x53, 0x65, 0x6c, 0x66,
                0x02, 0x09, 0x63, 0x61,
                0x6c, 0x6c, 0x4f, 0x74,
                0x68, 0x65, 0x72, 0x03,
                0x0d, 0x63, 0x61, 0x6c,
                0x6c, 0x4f, 0x74, 0x68,
                0x65, 0x72, 0x46, 0x61,
                0x69, 0x6c, 0x04, 0x04,
                0x01, 0x00, 0x01, 0x78,
            )

        private val CALL_INDIRECT_IMPORT_WASM =
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6d,
                0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60,
                0x00, 0x01, 0x7f, 0x02,
                0x17, 0x01, 0x04, 0x74,
                0x65, 0x73, 0x74, 0x0c,
                0x73, 0x68, 0x61, 0x72,
                0x65, 0x64, 0x2d, 0x74,
                0x61, 0x62, 0x6c, 0x65,
                0x01, 0x70, 0x00, 0x03,
                0x03, 0x03, 0x02, 0x00,
                0x00, 0x09, 0x0d, 0x02,
                0x00, 0x41, 0x01, 0x0b,
                0x01, 0x00, 0x00, 0x41,
                0x02, 0x0b, 0x01, 0x01,
                0x0a, 0x0b, 0x02, 0x05,
                0x00, 0x41, 0xd8.toByte(), 0x00,
                0x0b, 0x03, 0x00, 0x00,
                0x0b, 0x00, 0x1a, 0x04,
                0x6e, 0x61, 0x6d, 0x65,
                0x01, 0x13, 0x02, 0x00,
                0x05, 0x6f, 0x74, 0x68,
                0x65, 0x72, 0x01, 0x09,
                0x6f, 0x74, 0x68, 0x65,
                0x72, 0x46, 0x61, 0x69,
                0x6c,
            )
    }
}
