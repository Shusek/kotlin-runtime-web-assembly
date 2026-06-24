package uk.shusek.krwa.sample

import uk.shusek.krwa.component.WasiPreview
import uk.shusek.krwa.component.WitPackage
import uk.shusek.krwa.component.WitPackage.TypeRef.TypeKind
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.runtime.TrapException
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasi.preview3.KotlinWasiPreview3
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

internal class PortableRuntimeShowcase(
    private val capabilities: ShowcaseCapabilities,
) {
    fun interpreterRuntime() {
        val empty =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.EMPTY_WASM))
                .withInitialize(false)
                .withStart(false)
                .build()

        requireShowcaseValue(0, empty.functionCount(), "empty module function count")
        requireShowcaseValue(0, empty.globalCount(), "empty module global count")
        requireShowcaseValue(0, empty.tableCount(), "empty module table count")
        capabilities.demonstrate(
            "Portable Runtime",
            "Instantiate core Wasm from common code",
            "The same KMP code parses a module and constructs an Instance on JVM, iOS, and wasmJs.",
        )

        val add = Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.ADD_WASM)).build()

        requireShowcaseValue(42, add.export("add").apply(19, 23)[0].toInt(), "portable add")
        requireShowcaseValue(
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            add.exportType("add"),
            "portable add type",
        )
        capabilities.demonstrate(
            "Portable Runtime",
            "Call typed Wasm exports",
            "Kotlin calls exported Wasm functions and reads their signatures through the shared runtime API.",
        )

        val factorial =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.BRANCHING_FACTORIAL_WASM))
                .build()
        requireShowcaseValue(720, factorial.export("fac").apply(6)[0].toInt(), "branching factorial")
        capabilities.demonstrate(
            "Portable Runtime",
            "Execute non-trivial control flow",
            "The interpreter runs branching code paths, loops, and return values consistently across hosts.",
        )

        val memory =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.MEMORY_STORE_LOAD_WASM)).build()

        requireShowcaseValue(
            0x12345678,
            memory.export("storeLoad").apply(0x12345678)[0].toInt(),
            "portable memory export",
        )
        requireShowcaseValue(
            0x12345678,
            memory.exports().memory("memory").readInt(16),
            "portable memory read",
        )
        capabilities.demonstrate(
            "Portable Runtime",
            "Share linear memory with Kotlin",
            "Guest exports a memory and Kotlin reads and writes it through the portable memory facade.",
        )

        var observed = ""
        val log =
            HostFunction(
                "host",
                "log",
                FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                WasmFunctionHandle { instance, args ->
                    observed = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    null
                },
            )
        val hostMemory =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.HOST_LOG_MEMORY_WASM))
                .withImportValues(ImportValues.builder().addFunction(log).build())
                .build()

        hostMemory.export("run").apply()
        requireShowcaseValue("hello from guest", observed, "host import memory read")
        hostMemory.memory().writeString(128, "host wrote memory")
        requireShowcaseValue(
            "host wrote memory",
            hostMemory.memory().readString(128, 17),
            "host memory write",
        )
        capabilities.demonstrate(
            "Host Interop",
            "Implement host functions in Kotlin",
            "Guest code calls a Kotlin import, and the host reads and writes guest memory in the same flow.",
        )

        val imports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "host",
                        "double",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args -> longArrayOf(args[0] * 2) },
                    )
                )
                .build()
        val hostImport =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.HOST_DOUBLE_IMPORT_WASM))
                .withImportValues(imports)
                .build()

        requireShowcaseValue(
            84,
            hostImport.export("callDouble").apply(42)[0].toInt(),
            "portable host import",
        )
        capabilities.demonstrate(
            "Host Interop",
            "Wire Kotlin imports through ImportValues",
            "A guest import calls a Kotlin function and receives a scalar result without platform-specific sample code.",
        )

        val store = Store()
        store.instantiate("math", WasmParser.parse(ShowcaseWasmFixtures.MATH_INC_WASM))
        val consumer =
            store.instantiate(
                "consumer",
                WasmParser.parse(ShowcaseWasmFixtures.CONSUMER_MATH_INC_WASM),
            )
        requireShowcaseValue(42, consumer.export("run").apply()[0].toInt(), "store cross-module import")
        capabilities.demonstrate(
            "Host Interop",
            "Compose modules through Store",
            "One module is registered as an import provider and another module consumes it through the common Store API.",
        )

        val trap = Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.TRAP_WASM)).build()
        requireShowcaseThrows<TrapException>("unreachable trap") { trap.export("fail").apply() }
        capabilities.demonstrate(
            "Portable Runtime",
            "Surface guest traps to Kotlin",
            "A Wasm trap becomes a Kotlin exception, giving hosts a predictable failure boundary.",
        )
    }

    fun builderSelectedRuntime() {
        val add =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.ADD_WASM))
                .withExecutionBackend(ExecutionBackend.AUTO)
                .build()

        requireShowcaseValue(42, add.export("add").apply(19, 23)[0].toInt(), "builder add")
        requireShowcaseValue(
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            add.exportType("add"),
            "builder add type",
        )

        val imports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "env",
                        "inc",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args -> longArrayOf(args[0] + 1) },
                    )
                )
                .build()
        val hostImport =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.NATIVE_INC_IMPORT_WASM))
                .withExecutionBackend(ExecutionBackend.AUTO)
                .withImportValues(imports)
                .build()

        requireShowcaseValue(42, hostImport.export("run").apply()[0].toInt(), "builder host import")

        val memory =
            Instance.builder(WasmParser.parse(ShowcaseWasmFixtures.EXPORTED_MEMORY_WASM))
                .withExecutionBackend(ExecutionBackend.AUTO)
                .build()

        memory.memory().writeI32(8, 0x1122_3344)
        requireShowcaseValue(0x1122_3344, memory.memory().readInt(8), "builder memory roundtrip")

        val backendDescription =
            when (add.executionBackend()) {
                ExecutionBackend.NATIVE ->
                    "AUTO selected the native browser/Node WebAssembly engine while keeping Instance.builder(...) common."
                ExecutionBackend.INTERPRETER ->
                    "AUTO selected the interpreter on this host while keeping Instance.builder(...) common."
                ExecutionBackend.PULLEY ->
                    "Wasmtime Pulley was selected while keeping Instance.builder(...) common."
                ExecutionBackend.AUTO ->
                    error("AUTO is a requested backend, not a concrete runtime backend")
            }
        capabilities.demonstrate(
            "Platform Backend",
            "Select the best execution engine from common code",
            backendDescription,
        )
    }

    fun componentModelContracts() {
        val wit = WitPackage.parse(pluginWit())
        requireShowcaseValue("sample:runtime", wit.packageName(), "WIT package name")
        val api = wit.interfaces().single { it.name() == "api" }
        val len = api.functions().single { it.name() == "len" }
        requireShowcaseValue("input", len.parameters().single().name(), "WIT function parameter")
        requireShowcaseType(TypeKind.PRIMITIVE, "string", len.parameters().single().type(), "WIT string parameter")
        requireShowcaseType(TypeKind.PRIMITIVE, "u32", len.results().single().type(), "WIT u32 result")
        require(wit.worlds().single { it.name() == "plugin" }.exports().single().name() == "api")
        capabilities.demonstrate(
            "Component Model",
            "Inspect WIT contracts",
            "Common code parses a WIT package, walks worlds/interfaces, and validates function shapes before hosting a component.",
        )

        requireShowcaseValue("0.3.0", WasiPreview.PREVIEW3.version(), "WASIp3 version")
        require(WasiPreview.PREVIEW3.isStable()) { "Expected WASIp3 to be stable metadata" }
        require(WasiPreview.PREVIEW3.isComponentModel()) { "Expected WASIp3 to use Component Model" }

        val wasip3 = WitPackage.parse(wasip3Wit())
        val waitFor =
            wasip3.interfaces()
                .single { it.name() == "monotonic-clock" }
                .functions()
                .single { it.name() == "wait-for" }
        require(waitFor.isAsync) { "Expected WASIp3 wait-for to be async" }
        requireShowcaseType(
            TypeKind.PRIMITIVE,
            "u64",
            waitFor.parameters().single().type(),
            "WASIp3 wait-for duration",
        )

        val randomBytes =
            wasip3.interfaces()
                .single { it.name() == "random" }
                .functions()
                .single { it.name() == "get-random-bytes" }
        requireShowcaseType(TypeKind.PRIMITIVE, "u64", randomBytes.parameters().single().type(), "random len")
        requireShowcaseType(TypeKind.LIST, null, randomBytes.results().single().type(), "random bytes")
        requireShowcaseType(
            TypeKind.PRIMITIVE,
            "u8",
            randomBytes.results().single().type().arguments().single(),
            "random byte element",
        )

        val handle =
            wasip3.interfaces()
                .single { it.name() == "incoming-handler" }
                .functions()
                .single { it.name() == "handle" }
        require(handle.isAsync) { "Expected WASIp3 incoming handler to be async" }
        requireShowcaseType(TypeKind.STREAM, null, handle.parameters().single().type(), "handler stream")
        requireShowcaseType(
            TypeKind.PRIMITIVE,
            "u8",
            handle.parameters().single().type().arguments().single(),
            "handler stream element",
        )
        requireShowcaseType(TypeKind.FUTURE, null, handle.results().single().type(), "handler future")
        requireShowcaseType(
            TypeKind.RESULT,
            null,
            handle.results().single().type().arguments().single(),
            "handler result",
        )
        capabilities.demonstrate(
            "Component Model",
            "Model WASI Preview 3 async contracts",
            "Common WIT inspection covers stable WASIp3 metadata plus async functions, futures, streams, lists, and results.",
        )
    }

    fun kotlinEcosystemIntegrations() {
        val detail =
            if (ShowcasePlatform.supportsSynchronousWasiPreview3HttpClientWait) {
                runWasiPreview3KtorHttpClientScenario(ShowcaseWasmFixtures.WASI3_HTTP_CLIENT_WASM)
                "Common showcase code passes a Ktor HttpClient(MockEngine) into WasiPreview3.builder().withHttpClient(...), then a Wasm guest observes it through the WASIp3 HTTP client import."
            } else {
                configureWasiPreview3KtorHttpClient()
                "Common showcase code passes a Ktor HttpClient(MockEngine) into WasiPreview3.builder().withHttpClient(...); this host exposes WASIp3 HTTP as a suspend-only backend, so the sync showcase reports that capability without forcing a blocking guest wait."
            }
        capabilities.demonstrate(
            "Kotlin Ecosystem Integrations",
            "Use Ktor HttpClient as a WASIp3 backend",
            detail,
        )
    }

    fun wasiPreview3Storage(
        storageRoot: String,
        capability: ShowcaseCapability,
    ) {
        val runtime =
            KotlinWasiPreview3.builder()
                .withArguments("kmp-showcase", "--storage")
                .withEnvironment("KRWA_SAMPLE", "kmp")
                .withPreopenedDirectory("/", storageRoot)
                .withStreamBufferCapacity(1024)
                .build()
        val fs = runtime.fileSystem("/")

        try {
            requireShowcaseValue("0.3.0", runtime.version, "Kotlin WASIp3 facade version")

            fs.delete(".", recursive = true)
            fs.createDirectories(".")
            fs.writeText("reports/result.txt", "storage")
            fs.appendText("reports/result.txt", "-ok")

            requireShowcaseValue("storage-ok", fs.readText("/reports/result.txt"), "storage readback")
            require(fs.exists("reports/result.txt")) { "storage file did not exist after write" }
            require(fs.metadata("reports/result.txt").isRegularFile) {
                "storage metadata did not report a regular file"
            }
            requireShowcaseValue(
                listOf("result.txt"),
                fs.list("reports").map { it.name },
                "storage list",
            )

            fs.delete("reports/result.txt")
            require(!fs.exists("reports/result.txt")) { "storage file survived delete" }

            val readOnlyRuntime =
                KotlinWasiPreview3.builder()
                    .withReadOnlyPreopenedDirectory("/", storageRoot)
                    .build()
            try {
                val readOnly = readOnlyRuntime.fileSystem("/")
                var blocked = false
                try {
                    readOnly.writeText("blocked.txt", "blocked")
                } catch (_: IllegalArgumentException) {
                    blocked = true
                }
                require(blocked) { "read-only storage preopen allowed a write" }
            } finally {
                readOnlyRuntime.close()
            }
        } finally {
            fs.delete(".", recursive = true)
            runtime.close()
        }

        capabilities += capability
    }
}

private fun pluginWit(): String =
    """
    package sample:runtime;

    interface api {
      len: func(input: string) -> u32;
    }

    world plugin {
      export api;
    }
    """
        .trimIndent()

private fun wasip3Wit(): String =
    """
    package wasi:clocks@0.3.0;

    interface monotonic-clock {
      wait-for: async func(duration: u64);
    }

    world imports {
      import monotonic-clock;
    }

    package wasi:random@0.3.0;

    interface random {
      get-random-bytes: func(len: u64) -> list<u8>;
    }

    world imports {
      import random;
    }

    package wasi:http@0.3.0;

    interface incoming-handler {
      handle: async func(request: stream<u8>) -> future<result<_, string>>;
    }

    world service {
      include wasi:clocks/imports@0.3.0;
      include wasi:random/imports@0.3.0;
      export incoming-handler;
    }
    """
        .trimIndent()

internal fun <T> requireShowcaseValue(
    expected: T,
    actual: T,
    label: String,
) {
    require(expected == actual) {
        "$label expected <$expected>, got <$actual>"
    }
}

internal fun requireShowcaseType(
    expectedKind: TypeKind,
    expectedName: String?,
    actual: WitPackage.TypeRef,
    label: String,
) {
    requireShowcaseValue(expectedKind, actual.kind(), "$label kind")
    requireShowcaseValue(expectedName, actual.name(), "$label name")
}

internal inline fun <reified T : Throwable> requireShowcaseThrows(
    label: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (actual: Throwable) {
        require(actual is T) {
            "$label: expected ${T::class.simpleName}, got $actual"
        }
        return
    }
    error("$label: expected ${T::class.simpleName}")
}

internal object ShowcaseWasmFixtures {
    val EMPTY_WASM = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)

    val ADD_WASM =
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
            0x0b,
        )

    val BRANCHING_FACTORIAL_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x06, 0x01, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x07, 0x07, 0x01, 0x03,
            0x66, 0x61, 0x63, 0x00,
            0x00, 0x0a, 0x27, 0x01,
            0x25, 0x01, 0x01, 0x7f,
            0x41, 0x01, 0x21, 0x01,
            0x02, 0x40, 0x03, 0x40,
            0x20, 0x00, 0x45, 0x0d,
            0x01, 0x20, 0x01, 0x20,
            0x00, 0x6c, 0x21, 0x01,
            0x20, 0x00, 0x41, 0x01,
            0x6b, 0x21, 0x00, 0x0c,
            0x00, 0x0b, 0x0b, 0x20,
            0x01, 0x0b,
        )

    val MEMORY_STORE_LOAD_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x06, 0x01, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x05, 0x03, 0x01, 0x00,
            0x01, 0x07, 0x16, 0x02,
            0x06, 0x6d, 0x65, 0x6d,
            0x6f, 0x72, 0x79, 0x02,
            0x00, 0x09, 0x73, 0x74,
            0x6f, 0x72, 0x65, 0x4c,
            0x6f, 0x61, 0x64, 0x00,
            0x00, 0x0a, 0x10, 0x01,
            0x0e, 0x00, 0x41, 0x10,
            0x20, 0x00, 0x36, 0x02,
            0x00, 0x41, 0x10, 0x28,
            0x02, 0x00, 0x0b,
        )

    val HOST_LOG_MEMORY_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x09, 0x02, 0x60,
            0x02, 0x7f, 0x7f, 0x00,
            0x60, 0x00, 0x00, 0x02,
            0x0c, 0x01, 0x04, 0x68,
            0x6f, 0x73, 0x74, 0x03,
            0x6c, 0x6f, 0x67, 0x00,
            0x00, 0x03, 0x02, 0x01,
            0x01, 0x05, 0x03, 0x01,
            0x00, 0x01, 0x07, 0x10,
            0x02, 0x06, 0x6d, 0x65,
            0x6d, 0x6f, 0x72, 0x79,
            0x02, 0x00, 0x03, 0x72,
            0x75, 0x6e, 0x00, 0x01,
            0x0a, 0x0a, 0x01, 0x08,
            0x00, 0x41, 0x20, 0x41,
            0x10, 0x10, 0x00, 0x0b,
            0x0b, 0x16, 0x01, 0x00,
            0x41, 0x20, 0x0b, 0x10,
            0x68, 0x65, 0x6c, 0x6c,
            0x6f, 0x20, 0x66, 0x72,
            0x6f, 0x6d, 0x20, 0x67,
            0x75, 0x65, 0x73, 0x74,
        )

    val HOST_DOUBLE_IMPORT_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x06, 0x01, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x02, 0x0f, 0x01, 0x04,
            0x68, 0x6f, 0x73, 0x74,
            0x06, 0x64, 0x6f, 0x75,
            0x62, 0x6c, 0x65, 0x00,
            0x00, 0x03, 0x02, 0x01,
            0x00, 0x07, 0x0e, 0x01,
            0x0a, 0x63, 0x61, 0x6c,
            0x6c, 0x44, 0x6f, 0x75,
            0x62, 0x6c, 0x65, 0x00,
            0x01, 0x0a, 0x08, 0x01,
            0x06, 0x00, 0x20, 0x00,
            0x10, 0x00, 0x0b,
        )

    val MATH_INC_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x06, 0x01, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x07, 0x07, 0x01, 0x03,
            0x69, 0x6e, 0x63, 0x00,
            0x00, 0x0a, 0x09, 0x01,
            0x07, 0x00, 0x20, 0x00,
            0x41, 0x01, 0x6a, 0x0b,
        )

    val CONSUMER_MATH_INC_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x0a, 0x02, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x60, 0x00, 0x01, 0x7f,
            0x02, 0x0c, 0x01, 0x04,
            0x6d, 0x61, 0x74, 0x68,
            0x03, 0x69, 0x6e, 0x63,
            0x00, 0x00, 0x03, 0x02,
            0x01, 0x01, 0x07, 0x07,
            0x01, 0x03, 0x72, 0x75,
            0x6e, 0x00, 0x01, 0x0a,
            0x08, 0x01, 0x06, 0x00,
            0x41, 0x29, 0x10, 0x00,
            0x0b,
        )

    val TRAP_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x04, 0x01, 0x60,
            0x00, 0x00, 0x03, 0x02,
            0x01, 0x00, 0x07, 0x08,
            0x01, 0x04, 0x66, 0x61,
            0x69, 0x6c, 0x00, 0x00,
            0x0a, 0x05, 0x01, 0x03,
            0x00, 0x00, 0x0b,
        )

    val NATIVE_INC_IMPORT_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x0a, 0x02, 0x60,
            0x01, 0x7f, 0x01, 0x7f,
            0x60, 0x00, 0x01, 0x7f,
            0x02, 0x0b, 0x01, 0x03,
            0x65, 0x6e, 0x76, 0x03,
            0x69, 0x6e, 0x63, 0x00,
            0x00, 0x03, 0x02, 0x01,
            0x01, 0x07, 0x07, 0x01,
            0x03, 0x72, 0x75, 0x6e,
            0x00, 0x01, 0x0a, 0x08,
            0x01, 0x06, 0x00, 0x41,
            0x29, 0x10, 0x00, 0x0b,
        )

    val EXPORTED_MEMORY_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x05, 0x03, 0x01, 0x00,
            0x01, 0x07, 0x0a, 0x01,
            0x06, 0x6d, 0x65, 0x6d,
            0x6f, 0x72, 0x79, 0x02,
            0x00,
        )

    val WASI3_HTTP_CLIENT_WASM =
        byteArrayOf(
            0, 97, 115, 109, 1, 0, 0, 0, 1, 43, 7, 96, 0, 1, 127, 96, 7, 127, 127, 127,
            127, 127, 127, 127, 0, 96, 4, 127, 127, 127, 127, 1, 127, 96, 1, 127, 1, 127, 96, 2,
            127, 127, 1, 127, 96, 2, 127, 127, 0, 96, 1, 127, 0, 2, -125, 4, 11, 21, 119, 97,
            115, 105, 58, 104, 116, 116, 112, 47, 116, 121, 112, 101, 115, 64, 48, 46, 51, 46, 48, 19,
            91, 99, 111, 110, 115, 116, 114, 117, 99, 116, 111, 114, 93, 102, 105, 101, 108, 100, 115, 0,
            0, 21, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 116, 121, 112, 101, 115, 64, 48, 46,
            51, 46, 48, 19, 91, 115, 116, 97, 116, 105, 99, 93, 114, 101, 113, 117, 101, 115, 116, 46,
            110, 101, 119, 0, 1, 21, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 116, 121, 112, 101,
            115, 64, 48, 46, 51, 46, 48, 29, 91, 109, 101, 116, 104, 111, 100, 93, 114, 101, 113, 117,
            101, 115, 116, 46, 115, 101, 116, 45, 97, 117, 116, 104, 111, 114, 105, 116, 121, 0, 2, 21,
            119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 116, 121, 112, 101, 115, 64, 48, 46, 51, 46,
            48, 35, 91, 109, 101, 116, 104, 111, 100, 93, 114, 101, 113, 117, 101, 115, 116, 46, 115, 101,
            116, 45, 112, 97, 116, 104, 45, 119, 105, 116, 104, 45, 113, 117, 101, 114, 121, 0, 2, 22,
            119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 99, 108, 105, 101, 110, 116, 64, 48, 46, 51,
            46, 48, 4, 115, 101, 110, 100, 0, 3, 22, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47,
            99, 108, 105, 101, 110, 116, 64, 48, 46, 51, 46, 48, 32, 91, 97, 115, 121, 110, 99, 45,
            108, 111, 119, 101, 114, 93, 91, 102, 117, 116, 117, 114, 101, 45, 114, 101, 97, 100, 45, 48,
            93, 115, 101, 110, 100, 0, 4, 22, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 99, 108,
            105, 101, 110, 116, 64, 48, 46, 51, 46, 48, 16, 119, 97, 105, 116, 97, 98, 108, 101, 45,
            115, 101, 116, 46, 110, 101, 119, 0, 0, 22, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47,
            99, 108, 105, 101, 110, 116, 64, 48, 46, 51, 46, 48, 13, 119, 97, 105, 116, 97, 98, 108,
            101, 46, 106, 111, 105, 110, 0, 5, 22, 119, 97, 115, 105, 58, 104, 116, 116, 112, 47, 99,
            108, 105, 101, 110, 116, 64, 48, 46, 51, 46, 48, 17, 119, 97, 105, 116, 97, 98, 108, 101,
            45, 115, 101, 116, 46, 119, 97, 105, 116, 0, 4, 22, 119, 97, 115, 105, 58, 104, 116, 116,
            112, 47, 99, 108, 105, 101, 110, 116, 64, 48, 46, 51, 46, 48, 17, 119, 97, 105, 116, 97,
            98, 108, 101, 45, 115, 101, 116, 46, 100, 114, 111, 112, 0, 6, 21, 119, 97, 115, 105, 58,
            104, 116, 116, 112, 47, 116, 121, 112, 101, 115, 64, 48, 46, 51, 46, 48, 32, 91, 109, 101,
            116, 104, 111, 100, 93, 114, 101, 115, 112, 111, 110, 115, 101, 46, 103, 101, 116, 45, 115, 116,
            97, 116, 117, 115, 45, 99, 111, 100, 101, 0, 3, 3, 3, 2, 2, 0, 5, 3, 1, 0,
            1, 6, 7, 1, 127, 1, 65, -128, 2, 11, 7, 44, 3, 6, 109, 101, 109, 111, 114, 121,
            2, 0, 21, 99, 97, 110, 111, 110, 105, 99, 97, 108, 95, 97, 98, 105, 95, 114, 101, 97,
            108, 108, 111, 99, 0, 11, 7, 97, 112, 105, 46, 114, 117, 110, 0, 12, 10, -44, 1, 2,
            32, 1, 1, 127, 35, 0, 32, 2, 65, 1, 107, 106, 32, 2, 65, 1, 107, 65, 127, 115,
            113, 33, 4, 32, 4, 32, 3, 106, 36, 0, 32, 4, 11, -80, 1, 1, 5, 127, 16, 0,
            65, 0, 65, 0, 65, 0, 65, 0, 65, 0, 65, -32, 0, 16, 1, 65, -32, 0, 40, 2,
            0, 33, 0, 32, 0, 65, 1, 65, 16, 65, 14, 16, 2, 65, 0, 71, 4, 64, 0, 11,
            32, 0, 65, 1, 65, -64, 0, 65, 11, 16, 3, 65, 0, 71, 4, 64, 0, 11, 32, 0,
            16, 4, 33, 2, 32, 2, 65, -128, 1, 16, 5, 33, 3, 32, 3, 65, 127, 70, 4, 64,
            16, 6, 33, 4, 32, 2, 32, 4, 16, 7, 32, 4, 65, -64, 1, 16, 8, 65, 4, 71,
            4, 64, 0, 11, 65, -64, 1, 40, 2, 0, 32, 2, 71, 4, 64, 0, 11, 65, -60, 1,
            40, 2, 0, 65, 0, 71, 4, 64, 0, 11, 32, 4, 16, 9, 5, 32, 3, 65, 0, 71,
            4, 64, 0, 11, 11, 65, -128, 1, 45, 0, 0, 65, 0, 71, 4, 64, 0, 11, 65, -124,
            1, 40, 2, 0, 33, 1, 32, 1, 16, 10, 11, 11, 37, 2, 0, 65, 16, 11, 14, 49,
            50, 55, 46, 48, 46, 48, 46, 49, 58, 55, 55, 55, 55, 0, 65, -64, 0, 11, 11, 47,
            112, 114, 111, 98, 101, 63, 120, 61, 112, 51, 0, -110, 2, 4, 110, 97, 109, 101, 1, -97,
            1, 12, 0, 10, 102, 105, 101, 108, 100, 115, 95, 110, 101, 119, 1, 11, 114, 101, 113, 117,
            101, 115, 116, 95, 110, 101, 119, 2, 13, 115, 101, 116, 95, 97, 117, 116, 104, 111, 114, 105,
            116, 121, 3, 8, 115, 101, 116, 95, 112, 97, 116, 104, 4, 4, 115, 101, 110, 100, 5, 16,
            115, 101, 110, 100, 95, 102, 117, 116, 117, 114, 101, 95, 114, 101, 97, 100, 6, 16, 119, 97,
            105, 116, 97, 98, 108, 101, 95, 115, 101, 116, 95, 110, 101, 119, 7, 13, 119, 97, 105, 116,
            97, 98, 108, 101, 95, 106, 111, 105, 110, 8, 17, 119, 97, 105, 116, 97, 98, 108, 101, 95,
            115, 101, 116, 95, 119, 97, 105, 116, 9, 17, 119, 97, 105, 116, 97, 98, 108, 101, 95, 115,
            101, 116, 95, 100, 114, 111, 112, 10, 6, 115, 116, 97, 116, 117, 115, 12, 3, 114, 117, 110,
            2, 96, 2, 11, 5, 0, 3, 111, 108, 100, 1, 8, 111, 108, 100, 95, 115, 105, 122, 101,
            2, 5, 97, 108, 105, 103, 110, 3, 8, 110, 101, 119, 95, 115, 105, 122, 101, 4, 3, 112,
            116, 114, 12, 5, 0, 7, 114, 101, 113, 117, 101, 115, 116, 1, 8, 114, 101, 115, 112, 111,
            110, 115, 101, 2, 6, 102, 117, 116, 117, 114, 101, 3, 11, 115, 101, 110, 100, 95, 115, 116,
            97, 116, 117, 115, 4, 12, 119, 97, 105, 116, 97, 98, 108, 101, 95, 115, 101, 116, 7, 7,
            1, 0, 4, 104, 101, 97, 112,
        )

    val EXPORTED_GLOBAL_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x06, 0x06, 0x01, 0x7f,
            0x00, 0x41, 0x2a, 0x0b,
            0x07, 0x0a, 0x01, 0x06,
            0x61, 0x6e, 0x73, 0x77,
            0x65, 0x72, 0x03, 0x00,
        )

    val EXPORTED_TABLE_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x04, 0x04, 0x01, 0x70,
            0x00, 0x02, 0x07, 0x09,
            0x01, 0x05, 0x74, 0x61,
            0x62, 0x6c, 0x65, 0x01,
            0x00,
        )
}
