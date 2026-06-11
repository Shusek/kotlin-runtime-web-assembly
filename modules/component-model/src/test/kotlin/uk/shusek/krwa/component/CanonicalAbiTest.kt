package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.ValType

class CanonicalAbiTest {
    @Test
    fun computesCoreFunctionTypeForLiftedExport() {
        val witPackage = pluginPackage()
        val function = transformFunction(witPackage)
        val abi = CanonicalAbi.of(witPackage)

        assertEquals(
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                listOf(ValType.I32),
            ),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT),
        )
        assertEquals(
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                listOf(),
            ),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LOWERED_IMPORT),
        )
    }

    @Test
    fun treatsExternalUseTypesAsResourceHandles() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface stdin {
                  use wasi:io/streams@0.2.12.{input-stream};
                  get-stdin: func() -> input-stream;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)

        assertEquals(
            FunctionType.of(listOf(), listOf(ValType.I32)),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT),
        )
    }

    @Test
    fun resolvesUsedTypesByInterfaceScopeWhenNamesCollide() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host;

                interface http-types {
                  variant error-code {
                    timeout,
                  }
                }

                interface fs-types {
                  variant error-code {
                    no-entry,
                  }
                }

                interface fs {
                  use fs-types.{error-code};

                  open: func() -> result<_, error-code>;
                }
                """
                    .trimIndent()
            )
        val open = witPackage.interfaces().first { it.name() == "fs" }.functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        assertEquals(
            FunctionType.of(listOf(ValType.I32), emptyList()),
            abi.coreFunctionType(open, CanonicalAbi.Direction.LOWERED_IMPORT),
        )
        val imports =
            ImportValues.builder()
                .addFunction(
                    abi.hostFunction("fs", "open", open) {
                        WitResult.err(WitValue.variant("no-entry"))
                    }
                )
                .build()
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (import "fs" "open" (func ${'$'}open (param i32)))
                      (memory (export "memory") 1)
                      (func (export "run") (result i32)
                        (call ${'$'}open (i32.const 16))
                        (i32.load8_u (i32.const 16)))
                    )
                    """
                        .trimIndent()
                )
            )

        val result = Instance.builder(module).withImportValues(imports).build().export("run").apply()

        assertArrayEquals(longArrayOf(1), result)
    }

    @Test
    fun lowersResourceWrappersByHandleAccessor() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:cli@0.2.12;
                interface stdin {
                  use wasi:io/streams@0.2.12.{input-stream};
                  set-stdin: func(stream: input-stream);
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val context =
            CanonicalAbi.Context.of(ByteArrayMemory(MemoryLimits(1)), BumpAllocator(1024)::allocate)

        assertArrayEquals(
            longArrayOf(42),
            abi.lowerFlatValues(context, function.parameters(), listOf(Resource(42)), 16),
        )
    }

    @Test
    fun lowersWitResultPayloadsAsResultVariants() {
        val witPackage =
            WitPackage.parse(
                """
                package example:results;
                interface api {
                  resource response;
                  make-response: func() -> result<response, string>;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val context =
            CanonicalAbi.Context.of(ByteArrayMemory(MemoryLimits(1)), BumpAllocator(1024)::allocate)

        assertArrayEquals(
            longArrayOf(0, 42, 0),
            abi.lowerFlatValues(
                context,
                function.results(),
                listOf(WitResult.ok(WitResource<Any>(42))),
                16,
            ),
        )
    }

    @Test
    fun storesSelectedVariantPayloadWithoutRequiringTrailingPadding() {
        val witPackage =
            WitPackage.parse(
                """
                package example:filesystem-result;
                interface api {
                  resource descriptor;
                  variant error-code {
                    access,
                    other(option<string>),
                  }
                  open: func() -> result<descriptor, error-code>;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val context = CanonicalAbi.Context.of(memory, BumpAllocator(1024)::allocate)
        val ptr = Memory.PAGE_SIZE - 16

        abi.storeValues(context, ptr, function.results(), listOf(WitResult.ok(WitResource<Any>(42))))
        val loaded = abi.loadValues(context, ptr, function.results())[0] as WitValue.Variant

        assertEquals(0L, memory.readU8(ptr))
        assertEquals(42, memory.readInt(ptr + 4))
        assertEquals("ok", loaded.label())
        assertEquals(42L, loaded.value())
    }

    @Test
    fun liftsAndLowersFutureAndStreamHandles() {
        val witPackage =
            WitPackage.parse(
                """
                package example:async-handles;
                interface api {
                  pass: func(input: stream<u8>, pending: future<result<_, string>>);
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val context =
            CanonicalAbi.Context.of(ByteArrayMemory(MemoryLimits(1)), BumpAllocator(1024)::allocate)

        val lifted = abi.liftFlatValues(context, function.parameters(), longArrayOf(7, 9), 16)

        assertEquals(7L, (lifted[0] as WitStream<*>).handle())
        assertEquals(9L, (lifted[1] as WitFuture<*>).handle())
        assertArrayEquals(
            longArrayOf(11, 13),
            abi.lowerFlatValues(
                context,
                function.parameters(),
                listOf(WitStream.of<UByte>(11), WitFuture.of<WitResult<Unit, String>>(13)),
                16,
            ),
        )
    }

    @Test
    fun preservesFloatPrimitivesThroughFlatAndMemoryAbi() {
        val witPackage =
            WitPackage.parse(
                """
                package example:floats;
                world api {
                  export mix: func(left: f32, right: f64) -> tuple<f32, f64>;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.worlds()[0].exports()[0].function()!!
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        assertEquals(
            FunctionType.of(listOf(ValType.F32, ValType.F64), listOf(ValType.I32)),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT),
        )
        assertEquals(
            FunctionType.of(listOf(ValType.F32, ValType.F64, ValType.I32), listOf()),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LOWERED_IMPORT),
        )

        val lowered = abi.lowerFlatValues(context, function.parameters(), listOf(1.25f, -2.5), 16)

        assertArrayEquals(
            longArrayOf(
                java.lang.Float.floatToRawIntBits(1.25f).toLong() and 0xFFFF_FFFFL,
                java.lang.Double.doubleToRawLongBits(-2.5),
            ),
            lowered,
        )

        val lifted = abi.liftFlatValues(context, function.parameters(), lowered, 16)
        assertEquals(1.25f, lifted[0])
        assertEquals(-2.5, lifted[1])

        val resultPtr =
            abi.lowerFlatValues(context, function.results(), listOf(listOf(-3.5f, 8.25)), 1)
        val ptr = resultPtr[0].toInt()

        assertEquals(-3.5f, memory.readFloat(ptr))
        assertEquals(8.25, memory.readDouble(ptr + 8))

        @Suppress("UNCHECKED_CAST")
        val result = abi.liftFlatValues(context, function.results(), resultPtr, 1)[0] as List<Any?>
        assertEquals(-3.5f, result[0])
        assertEquals(8.25, result[1])
    }

    @Test
    fun stripsWitPackageVersionsWhenResolvingTypeNames() {
        val witPackage =
            WitPackage.parse(
                """
                package wasi:io@0.2.12;
                interface streams {
                  resource input-stream;
                  get: func() -> wasi:io/streams@0.2.12.input-stream;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)

        assertEquals(
            FunctionType.of(listOf(), listOf(ValType.I32)),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT),
        )
    }

    @Test
    fun roundTripsRecordsAndResultsThroughFlatAbi() {
        val witPackage = pluginPackage()
        val function = transformFunction(witPackage)
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        val request = WitValue.record("body", byteArrayOf(1, 2, 3), "content-type", "text/plain")
        val lowered = abi.lowerFlatValues(context, function.parameters(), listOf(request), 16)
        val lifted = abi.liftFlatValues(context, function.parameters(), lowered, 16)

        @Suppress("UNCHECKED_CAST") val liftedRequest = lifted[0] as Map<String, Any?>
        assertArrayEquals(byteArrayOf(1, 2, 3), liftedRequest["body"] as ByteArray)
        assertEquals("text/plain", liftedRequest["content-type"])

        val response = WitValue.ok(WitValue.variant("ok", byteArrayOf(9, 8, 7)))
        val resultFlat = abi.lowerFlatValues(context, function.results(), listOf(response), 16)
        val result =
            abi.liftFlatValues(context, function.results(), resultFlat, 16)[0] as WitValue.Variant
        val payload = result.value() as WitValue.Variant

        assertEquals("ok", result.label())
        assertEquals("ok", payload.label())
        assertArrayEquals(byteArrayOf(9, 8, 7), payload.value() as ByteArray)
    }

    @Test
    fun lowersReflectiveVariantCases() {
        val witPackage = pluginPackage()
        val function = transformFunction(witPackage)
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        val response = Ok(Ok(byteArrayOf(4, 5, 6)))
        val resultFlat = abi.lowerFlatValues(context, function.results(), listOf(response), 16)
        val result =
            abi.liftFlatValues(context, function.results(), resultFlat, 16)[0] as WitValue.Variant
        val payload = result.value() as WitValue.Variant

        assertEquals("ok", result.label())
        assertEquals("ok", payload.label())
        assertArrayEquals(byteArrayOf(4, 5, 6), payload.value() as ByteArray)
    }

    @Test
    fun lowersKotlinEnumsAsWitVariantLabels() {
        val witPackage =
            WitPackage.parse(
                """
                package example:enums;
                interface api {
                  enum media-type {
                    movie,
                    package-file,
                  }
                  select: func(value: media-type) -> media-type;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        val lowered =
            abi.lowerFlatValues(context, function.parameters(), listOf(MediaType.PACKAGE_FILE), 16)
        val lifted =
            abi.liftFlatValues(context, function.parameters(), lowered, 16)[0] as WitValue.Variant

        assertArrayEquals(longArrayOf(1), lowered)
        assertEquals("package-file", lifted.label())
    }

    @Test
    fun lowersTupleObjectsByComponentAccessors() {
        val witPackage =
            WitPackage.parse(
                """
                package example:tuples;
                world plugin {
                  export observe: func(value: tuple<u32, string, bool, u8>);
                }
                """
                    .trimIndent()
            )
        val function = witPackage.worlds()[0].exports()[0].function()!!
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        val lowered =
            abi.lowerFlatValues(
                context,
                function.parameters(),
                listOf(WitTuple4(7, "hi", true, 3)),
                16,
            )
        val lifted = abi.liftFlatValues(context, function.parameters(), lowered, 16)

        @Suppress("UNCHECKED_CAST") val tuple = lifted[0] as List<Any?>
        assertEquals(7L, tuple[0])
        assertEquals("hi", tuple[1])
        assertEquals(true, tuple[2])
        assertEquals(3, tuple[3])
    }

    @Test
    fun supportsFlagsLargerThanOneWord() {
        val witPackage =
            WitPackage.parse(
                """
                package example:flags;
                interface perms {
                  flags permissions {
                ${flagsCases(35)}  }
                  accept: func(value: permissions) -> bool;
                  snapshot: func() -> permissions;
                }
                """
                    .trimIndent()
            )
        val accept = witPackage.interfaces()[0].functions()[0]
        val snapshot = witPackage.interfaces()[0].functions()[1]
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        assertEquals(
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            abi.coreFunctionType(accept, CanonicalAbi.Direction.LIFTED_EXPORT),
        )

        val flags = listOf("f0", "f31", "f34")
        val lowered = abi.lowerFlatValues(context, accept.parameters(), listOf(flags), 16)

        assertArrayEquals(longArrayOf(0x8000_0001L, 0x0000_0004L), lowered)

        @Suppress("UNCHECKED_CAST")
        val lifted =
            abi.liftFlatValues(context, accept.parameters(), lowered, 16)[0] as Map<String, Boolean>
        assertEquals(true, lifted["f0"])
        assertEquals(false, lifted["f1"])
        assertEquals(true, lifted["f31"])
        assertEquals(true, lifted["f34"])

        val retptr = abi.lowerFlatValues(context, snapshot.results(), listOf(flags), 1)
        @Suppress("UNCHECKED_CAST")
        val liftedResult =
            abi.liftFlatValues(context, snapshot.results(), retptr, 1)[0] as Map<String, Boolean>
        assertEquals(true, liftedResult["f0"])
        assertEquals(true, liftedResult["f31"])
        assertEquals(true, liftedResult["f34"])
    }

    @Test
    fun treatsCharAsUnicodeScalarValue() {
        val witPackage =
            WitPackage.parse(
                """
                package example:chars;
                world api {
                  export echo: func(value: char) -> char;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.worlds()[0].exports()[0].function()!!
        val abi = CanonicalAbi.of(witPackage)
        val memory = ByteArrayMemory(MemoryLimits(1))
        val bump = BumpAllocator(1024)
        val context = CanonicalAbi.Context.of(memory, bump::allocate)

        val rocket = "\uD83D\uDE80"
        val lowered = abi.lowerFlatValues(context, function.parameters(), listOf(rocket), 16)

        assertArrayEquals(longArrayOf(0x1F680), lowered)
        assertEquals(0x1F680, abi.liftFlatValues(context, function.parameters(), lowered, 16)[0])
        assertArrayEquals(
            longArrayOf('A'.code.toLong()),
            abi.lowerFlatValues(context, function.results(), listOf('A'), 16),
        )

        assertThrows(ComponentModelException::class.java) {
            abi.lowerFlatValues(context, function.parameters(), listOf("\uD800"), 16)
        }
        assertThrows(ComponentModelException::class.java) {
            abi.lowerFlatValues(
                context,
                function.parameters(),
                listOf(Character.MAX_CODE_POINT + 1L),
                16,
            )
        }
    }

    @Test
    fun callsCoreWasmExportWithWitString() {
        val witPackage =
            WitPackage.parse(
                """
                package example:echo;
                world api {
                  export echo-len: func(input: string) -> u32;
                }
                """
                    .trimIndent()
            )
        val function = witPackage.worlds()[0].exports()[0].function()!!
        val abi = CanonicalAbi.of(witPackage)
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (memory (export "memory") 1)
                      (global ${'$'}heap (mut i32) (i32.const 1024))
                      (func (export "canonical_abi_realloc")
                        (param ${'$'}old i32) (param ${'$'}old_size i32)
                        (param ${'$'}align i32) (param ${'$'}new_size i32)
                        (result i32)
                        (local ${'$'}ptr i32)
                        (local.set ${'$'}ptr
                          (i32.and
                            (i32.add (global.get ${'$'}heap)
                              (i32.sub (local.get ${'$'}align) (i32.const 1)))
                            (i32.xor
                              (i32.sub (local.get ${'$'}align) (i32.const 1))
                              (i32.const -1))))
                        (global.set ${'$'}heap
                          (i32.add (local.get ${'$'}ptr) (local.get ${'$'}new_size)))
                        (local.get ${'$'}ptr))
                      (func (export "echo-len")
                        (param ${'$'}ptr i32) (param ${'$'}len i32) (result i32)
                        (local.get ${'$'}len))
                    )
                    """
                        .trimIndent()
                )
            )
        val instance = Instance.builder(module).build()

        assertEquals(
            instance.exportType("echo-len"),
            abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT),
        )
        assertEquals(5L, abi.call(instance, "echo-len", function, "hello"))
    }

    @Test
    fun adaptsHostImportParametersThroughCanonicalAbi() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host;
                interface host {
                  log: func(message: string);
                }
                world plugin {
                  import host;
                  export run: func();
                }
                """
                    .trimIndent()
            )
        val log = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val observed = AtomicReference<String>()
        val imports =
            ImportValues.builder()
                .addFunction(
                    abi.hostFunction("host", "log", log) { arguments ->
                        observed.set(arguments[0] as String)
                        null
                    }
                )
                .build()
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (import "host" "log" (func ${'$'}log (param i32 i32)))
                      (memory (export "memory") 1)
                      (data (i32.const 16) "hello")
                      (func (export "run")
                        (call ${'$'}log (i32.const 16) (i32.const 5)))
                    )
                    """
                        .trimIndent()
                )
            )

        Instance.builder(module).withImportValues(imports).build().export("run").apply()

        assertEquals("hello", observed.get())
        assertEquals(
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf()),
            abi.coreFunctionType(log, CanonicalAbi.Direction.LOWERED_IMPORT),
        )
    }

    @Test
    fun adaptsHostImportResultsThroughCanonicalAbiRetptr() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host;
                interface host {
                  decorate: func(message: string) -> string;
                }
                world plugin {
                  import host;
                  export run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val decorate = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val imports =
            ImportValues.builder()
                .addFunction(abi.hostFunction("host", "decorate", decorate) { "${it[0]}!" })
                .build()
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (import "host" "decorate"
                        (func ${'$'}decorate (param i32 i32 i32)))
                      (memory (export "memory") 1)
                      (global ${'$'}heap (mut i32) (i32.const 1024))
                      (data (i32.const 16) "hello")
                      (func (export "canonical_abi_realloc")
                        (param ${'$'}old i32) (param ${'$'}old_size i32)
                        (param ${'$'}align i32) (param ${'$'}new_size i32)
                        (result i32)
                        (local ${'$'}ptr i32)
                        (local.set ${'$'}ptr
                          (i32.and
                            (i32.add (global.get ${'$'}heap)
                              (i32.sub (local.get ${'$'}align) (i32.const 1)))
                            (i32.xor
                              (i32.sub (local.get ${'$'}align) (i32.const 1))
                              (i32.const -1))))
                        (global.set ${'$'}heap
                          (i32.add (local.get ${'$'}ptr) (local.get ${'$'}new_size)))
                        (local.get ${'$'}ptr))
                      (func (export "run") (result i32)
                        (call ${'$'}decorate (i32.const 16) (i32.const 5) (i32.const 64))
                        (i32.load (i32.const 68)))
                    )
                    """
                        .trimIndent()
                )
            )

        val instance = Instance.builder(module).withImportValues(imports).build()

        assertEquals(6L, instance.export("run").apply()[0])
        assertEquals(
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf()),
            abi.coreFunctionType(decorate, CanonicalAbi.Direction.LOWERED_IMPORT),
        )
    }

    @Test
    fun adaptsHostImportByteListResultsThroughCanonicalAbiRetptr() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host-bytes;
                interface host {
                  get-chunk: func() -> list<u8>;
                }
                world plugin {
                  import host;
                  export run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val getChunk = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val bytes = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val imports =
            ImportValues.builder()
                .addFunction(abi.hostFunction("host", "get-chunk", getChunk) { bytes })
                .build()
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (import "host" "get-chunk" (func ${'$'}get-chunk (param i32)))
                      (memory (export "memory") 1)
                      (global ${'$'}heap (mut i32) (i32.const 1024))
                      (func (export "canonical_abi_realloc")
                        (param ${'$'}old i32) (param ${'$'}old_size i32)
                        (param ${'$'}align i32) (param ${'$'}new_size i32)
                        (result i32)
                        (local ${'$'}ptr i32)
                        (local.set ${'$'}ptr
                          (i32.and
                            (i32.add (global.get ${'$'}heap)
                              (i32.sub (local.get ${'$'}align) (i32.const 1)))
                            (i32.xor
                              (i32.sub (local.get ${'$'}align) (i32.const 1))
                              (i32.const -1))))
                        (global.set ${'$'}heap
                          (i32.add (local.get ${'$'}ptr) (local.get ${'$'}new_size)))
                        (local.get ${'$'}ptr))
                      (func (export "run") (result i32)
                        (call ${'$'}get-chunk (i32.const 64))
                        (i32.load (i32.const 68)))
                    )
                    """
                        .trimIndent()
                )
            )

        val instance = Instance.builder(module).withImportValues(imports).build()

        assertEquals(bytes.size.toLong(), instance.export("run").apply()[0])
        assertEquals(bytes.size, instance.memory().readInt(68))
        assertArrayEquals(bytes, instance.memory().readBytes(instance.memory().readInt(64), bytes.size))
        assertEquals(
            FunctionType.of(listOf(ValType.I32), listOf()),
            abi.coreFunctionType(getChunk, CanonicalAbi.Direction.LOWERED_IMPORT),
        )
    }

    @Test
    fun adaptsNestedEmptyByteListResultsWithStablePointers() {
        val witPackage =
            WitPackage.parse(
                """
                package example:host-fields;
                interface host {
                  get-fields: func() -> list<tuple<string, list<u8>>>;
                }
                world plugin {
                  import host;
                  export run: func() -> u32;
                }
                """
                    .trimIndent()
            )
        val getFields = witPackage.interfaces()[0].functions()[0]
        val abi = CanonicalAbi.of(witPackage)
        val imports =
            ImportValues.builder()
                .addFunction(
                    abi.hostFunction("host", "get-fields", getFields) {
                        listOf(
                            listOf("foo", ByteArray(0)),
                            listOf("foo", ByteArray(0)),
                        )
                    }
                )
                .build()
        val module =
            Parser.parse(
                Wat2Wasm.parse(
                    """
                    (module
                      (import "host" "get-fields" (func ${'$'}get-fields (param i32)))
                      (memory (export "memory") 1)
                      (global ${'$'}heap (mut i32) (i32.const 1024))
                      (func (export "canonical_abi_realloc")
                        (param ${'$'}old i32) (param ${'$'}old_size i32)
                        (param ${'$'}align i32) (param ${'$'}new_size i32)
                        (result i32)
                        (local ${'$'}ptr i32)
                        (local.set ${'$'}ptr
                          (i32.and
                            (i32.add (global.get ${'$'}heap)
                              (i32.sub (local.get ${'$'}align) (i32.const 1)))
                            (i32.xor
                              (i32.sub (local.get ${'$'}align) (i32.const 1))
                              (i32.const -1))))
                        (global.set ${'$'}heap
                          (i32.add (local.get ${'$'}ptr) (local.get ${'$'}new_size)))
                        (local.get ${'$'}ptr))
                      (func (export "run") (result i32)
                        (local ${'$'}base i32)
                        (call ${'$'}get-fields (i32.const 64))
                        (local.set ${'$'}base (i32.load (i32.const 64)))
                        (if (i32.ne (i32.load (i32.const 68)) (i32.const 2))
                          (then unreachable))
                        (if (i32.eqz
                            (i32.load (i32.add (local.get ${'$'}base) (i32.const 8))))
                          (then unreachable))
                        (if (i32.ne
                            (i32.load (i32.add (local.get ${'$'}base) (i32.const 12)))
                            (i32.const 0))
                          (then unreachable))
                        (if (i32.eqz
                            (i32.load (i32.add (local.get ${'$'}base) (i32.const 24))))
                          (then unreachable))
                        (if (i32.ne
                            (i32.load (i32.add (local.get ${'$'}base) (i32.const 28)))
                            (i32.const 0))
                          (then unreachable))
                        (i32.load (i32.const 68)))
                    )
                    """
                        .trimIndent()
                )
            )

        val instance = Instance.builder(module).withImportValues(imports).build()

        assertEquals(2L, instance.export("run").apply()[0])
    }

    private fun pluginPackage(): WitPackage =
        WitPackage.parse(
            String(
                javaClass.getResourceAsStream("/plugin.wit")!!.readAllBytes(),
                StandardCharsets.UTF_8,
            )
        )

    private fun transformFunction(witPackage: WitPackage): WitPackage.Function =
        witPackage.interfaces()[1].functions()[0]

    private fun flagsCases(count: Int): String = buildString {
        repeat(count) { append("    f").append(it).append(",\n") }
    }

    private class BumpAllocator(private var next: Int) {
        fun allocate(oldPtr: Int, oldSize: Int, alignment: Int, newSize: Int): Int {
            val ptr = alignTo(next, alignment)
            next = ptr + newSize
            return ptr
        }

        private fun alignTo(value: Int, alignment: Int): Int {
            if (alignment <= 1) {
                return value
            }
            val remainder = value % alignment
            return if (remainder == 0) value else value + alignment - remainder
        }
    }

    class Resource(private val handle: Int) {
        fun handle(): Int = handle
    }

    class Ok(private val value: Any?) {
        fun getValue(): Any? = value
    }

    enum class MediaType {
        MOVIE,
        PACKAGE_FILE,
    }
}
