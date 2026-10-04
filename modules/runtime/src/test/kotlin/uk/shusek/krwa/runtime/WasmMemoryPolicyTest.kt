package uk.shusek.krwa.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits

class WasmMemoryPolicyTest {
    @Test
    fun maxMemoriesAcceptsTheLimitAndRejectsOneMore() {
        val module = moduleWithDefinedMemories(MemoryLimits(1, 2), MemoryLimits(1, 2))
        val policy =
            memoryPolicy(
                maxPagesPerMemory = 2,
                maxTotalPages = 4,
                maxMemories = 2,
            )

        withCapturingProvider { provider ->
            Instance.builder(module).withMemoryPolicy(policy).build().use { }
            assertEquals(1, provider.modules.size)
        }

        val exception =
            assertThrows(UninstantiableException::class.java) {
                Instance.builder(module)
                    .withMemoryPolicy(policy.copy(maxMemories = 1))
                    .build()
            }
        assertTrue(exception.message.orEmpty().contains("declares 2 memories"))
        assertTrue(exception.message.orEmpty().contains("policy limit 1"))
    }

    @Test
    fun definedMemoryInitialSizeCannotExceedThePerMemoryCap() {
        val module = moduleWithDefinedMemories(MemoryLimits(3, 4))

        val exception =
            assertThrows(UninstantiableException::class.java) {
                Instance.builder(module)
                    .withMemoryPolicy(
                        memoryPolicy(
                            maxPagesPerMemory = 2,
                            maxTotalPages = 4,
                        )
                    )
                    .build()
            }

        assertTrue(exception.message.orEmpty().contains("initial size 3"))
        assertTrue(exception.message.orEmpty().contains("limit of 2 pages"))
    }

    @Test
    fun importedMemoryMaximumCannotExceedThePerMemoryCap() {
        val module = moduleWithImportedMemories(ImportedMemory("env", "memory", MemoryLimits(1, 3)))
        val imports =
            ImportValues.builder()
                .addMemory(ImportMemory("env", "memory", ByteBufferMemory(MemoryLimits(1, 3))))
                .build()

        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(module)
                    .withImportValues(imports)
                    .withMemoryPolicy(
                        memoryPolicy(
                            maxPagesPerMemory = 2,
                            maxTotalPages = 3,
                        )
                    )
                    .build()
            }

        assertTrue(exception.message.orEmpty().contains("imported memory 0"))
        assertTrue(exception.message.orEmpty().contains("per-memory policy limit of 2 pages"))
    }

    @Test
    fun importedMemoryMaximumsCountAgainstTheAggregateCap() {
        val module =
            moduleWithImportedMemories(
                ImportedMemory("env", "first", MemoryLimits(1, 2)),
                ImportedMemory("env", "second", MemoryLimits(1, 2)),
            )
        val imports =
            ImportValues.builder()
                .addMemory(
                    ImportMemory("env", "first", ByteBufferMemory(MemoryLimits(1, 2))),
                    ImportMemory("env", "second", ByteBufferMemory(MemoryLimits(1, 2))),
                )
                .build()

        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(module)
                    .withImportValues(imports)
                    .withMemoryPolicy(
                        memoryPolicy(
                            maxPagesPerMemory = 2,
                            maxTotalPages = 3,
                            maxMemories = 2,
                        )
                    )
                    .build()
            }

        assertTrue(
            exception.message.orEmpty().contains(
                "imported memories exceed the aggregate memory policy limit"
            )
        )
    }

    @Test
    fun multiMemoryGrowthBudgetIsSplitWithoutExceedingTheAggregateCap() {
        val module = moduleWithDefinedMemories(MemoryLimits(1, 4), MemoryLimits(1, 4))
        val allocatedLimits = ArrayList<MemoryLimits>()

        withCapturingProvider { provider ->
            Instance.Builder.create(module) { limits ->
                allocatedLimits.add(limits)
                ByteBufferMemory(limits)
            }
                .withMemoryPolicy(
                    memoryPolicy(
                        maxPagesPerMemory = 4,
                        maxTotalPages = 5,
                        maxMemories = 2,
                    )
                )
                .build()
                .use { }

            assertMemoryLimits(
                allocatedLimits,
                expectedInitials = listOf(1, 1),
                expectedMaxima = listOf(4, 1),
            )
            assertMemoryLimits(
                provider.modules.single().definedMemoryLimits(),
                expectedInitials = listOf(1, 1),
                expectedMaxima = listOf(4, 1),
            )
            assertEquals(5, allocatedLimits.sumOf { it.maximumPages() })
        }
    }

    @Test
    fun memoryPolicySanitizesWasmtimeConfigRegardlessOfCallOrder() {
        val module = moduleWithDefinedMemories(MemoryLimits(1, 8))
        val policy =
            memoryPolicy(
                maxPagesPerMemory = 4,
                maxTotalPages = 4,
            )
        val unsafeConfig =
            WasmtimeExecutionConfig(
                target = WasmtimePulleyTarget,
                precompiledModuleBytes = byteArrayOf(1, 2, 3),
                maxMemoryBytes = pagesToBytes(9),
                maxFuel = 37,
            )

        val policyThenConfig =
            capturedConfig(module) {
                withMemoryPolicy(policy)
                withWasmtimeExecutionConfig(unsafeConfig)
            }
        val configThenPolicy =
            capturedConfig(module) {
                withWasmtimeExecutionConfig(unsafeConfig)
                withMemoryPolicy(policy)
            }

        for (config in listOf(policyThenConfig, configThenPolicy)) {
            assertNull(config.precompiledModuleBytes)
            assertEquals(pagesToBytes(4), config.maxMemoryBytes)
            assertEquals(WasmtimePulleyTarget, config.target)
            assertEquals(37L, config.maxFuel)
        }
        assertEquals(policyThenConfig, configThenPolicy)
    }

    @Test
    fun definedMemoriesGetTheWasmtimeDefaultsWithoutAnExplicitPolicy() {
        val module = moduleWithDefinedMemories(MemoryLimits(1), MemoryLimits(1, 100))

        withCapturingProvider { provider ->
            Instance.builder(module).build().use { }
            assertMemoryLimits(
                provider.modules.single().definedMemoryLimits(),
                expectedInitials = listOf(1, 1),
                expectedMaxima = listOf(DEFAULT_PAGES_PER_MEMORY, 100),
            )
        }
    }

    @Test
    fun definedMemoryInitialSizeCannotExceedTheDefaultCapWithoutAnExplicitPolicy() {
        val module = moduleWithDefinedMemories(MemoryLimits(DEFAULT_PAGES_PER_MEMORY + 1))

        val exception =
            assertThrows(UninstantiableException::class.java) {
                withCapturingProvider { Instance.builder(module).build() }
            }

        assertTrue(
            exception.message.orEmpty().contains("initial size ${DEFAULT_PAGES_PER_MEMORY + 1}")
        )
        assertTrue(exception.message.orEmpty().contains("limit of $DEFAULT_PAGES_PER_MEMORY pages"))
    }

    @Test
    fun configuredWasmtimeMemoryBytesDriveTheDefaultCap() {
        val config = WasmtimeExecutionConfig(maxMemoryBytes = pagesToBytes(3))

        withCapturingProvider { provider ->
            Instance.builder(moduleWithDefinedMemories(MemoryLimits(2)))
                .withWasmtimeExecutionConfig(config)
                .build()
                .use { }
            assertMemoryLimits(
                provider.modules.single().definedMemoryLimits(),
                expectedInitials = listOf(2),
                expectedMaxima = listOf(3),
            )
        }

        assertThrows(UninstantiableException::class.java) {
            withCapturingProvider {
                Instance.builder(moduleWithDefinedMemories(MemoryLimits(4)))
                    .withWasmtimeExecutionConfig(config)
                    .build()
            }
        }
    }

    @Test
    fun importedMemoriesAreNotCappedWithoutAnExplicitPolicy() {
        val module = moduleWithImportedMemories(ImportedMemory("env", "memory", MemoryLimits(1)))
        val imports =
            ImportValues.builder()
                .addMemory(ImportMemory("env", "memory", ByteBufferMemory(MemoryLimits(1))))
                .build()

        withCapturingProvider { provider ->
            Instance.builder(module).withImportValues(imports).build().use { }
            assertEquals(1, provider.modules.size)
        }
    }

    @Test
    fun definedTableSizesAreCappedByTheWasmtimeTableElementDefault() {
        val oversized = DefaultWasmtimeCoreMaxTableElements.toInt() + 1

        val exception =
            assertThrows(UninstantiableException::class.java) {
                withCapturingProvider { Instance.builder(moduleWithDefinedTables(oversized)).build() }
            }
        assertTrue(exception.message.orEmpty().contains("declares $oversized initial elements"))

        val config = WasmtimeExecutionConfig(maxTableElements = 4)
        withCapturingProvider {
            Instance.builder(moduleWithDefinedTables(4))
                .withWasmtimeExecutionConfig(config)
                .build()
                .use { }
        }
        assertThrows(UninstantiableException::class.java) {
            withCapturingProvider {
                Instance.builder(moduleWithDefinedTables(5))
                    .withWasmtimeExecutionConfig(config)
                    .build()
            }
        }

        withCapturingProvider {
            Instance.builder(moduleWithDefinedTables(oversized))
                .withWasmtimeExecutionConfig(
                    WasmtimeExecutionConfig(maxTableElements = WasmtimeUnlimitedResourceLimit)
                )
                .build()
                .use { }
        }
    }

    @Test
    fun constantExpressionArrayLengthsAreBounded() {
        withCapturingProvider {
            Instance.builder(moduleWithConstantArrayGlobal(3)).build().use { }
        }

        for (length in listOf(Int.MAX_VALUE, -1)) {
            val exception =
                assertThrows(UninstantiableException::class.java) {
                    withCapturingProvider {
                        Instance.builder(moduleWithConstantArrayGlobal(length)).build()
                    }
                }
            assertTrue(exception.message.orEmpty().contains("constant expression limit"))
        }
    }

    private fun capturedConfig(
        module: WasmModule,
        configure: Instance.Builder.() -> Unit,
    ): WasmtimeExecutionConfig =
        withCapturingProvider { provider ->
            Instance.builder(module)
                .apply(configure)
                .build()
                .use { }
            provider.configs.single()
                ?: throw AssertionError("expected a Wasmtime execution configuration")
        }

    private fun <T> withCapturingProvider(block: (CapturingPulleyProvider) -> T): T {
        val provider = CapturingPulleyProvider()
        val previous = PulleyExecutionProviders.install(provider)
        return try {
            block(provider)
        } finally {
            PulleyExecutionProviders.install(previous)
        }
    }

    private class CapturingPulleyProvider : PulleyExecutionProvider {
        val modules = ArrayList<WasmModule>()
        val configs = ArrayList<WasmtimeExecutionConfig?>()

        override fun availability(): ExecutionBackendAvailability =
            ExecutionBackendAvailability(available = true)

        override fun create(
            module: WasmModule,
            imports: ImportValues,
            hostInstance: Instance,
        ): PlatformInstanceExecution {
            modules.add(module)
            configs.add(hostInstance.wasmtimeExecutionConfig())
            return object : PlatformInstanceExecution {
                override val backend: ExecutionBackend = ExecutionBackend.PULLEY

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

    private data class ImportedMemory(
        val module: String,
        val name: String,
        val limits: MemoryLimits,
    )

    companion object {
        private fun memoryPolicy(
            maxPagesPerMemory: Int,
            maxTotalPages: Int,
            maxMemories: Int = 1,
        ): WasmMemoryPolicy =
            WasmMemoryPolicy(
                maxBytesPerMemory = pagesToBytes(maxPagesPerMemory),
                maxTotalBytes = pagesToBytes(maxTotalPages),
                maxMemories = maxMemories,
            )

        private fun pagesToBytes(pages: Int): Long = pages.toLong() * Memory.PAGE_SIZE.toLong()

        private fun moduleWithDefinedMemories(vararg limits: MemoryLimits): WasmModule {
            val body =
                unsignedLeb128(limits.size) +
                    limits.fold(ByteArray(0)) { bytes, memoryLimits ->
                        bytes + encodeMemoryLimits(memoryLimits)
                    }
            return parseModule(section(MEMORY_SECTION_ID, body))
        }

        private fun moduleWithImportedMemories(vararg imports: ImportedMemory): WasmModule {
            val body =
                unsignedLeb128(imports.size) +
                    imports.fold(ByteArray(0)) { bytes, import ->
                        bytes +
                            encodeName(import.module) +
                            encodeName(import.name) +
                            byteArrayOf(MEMORY_IMPORT_KIND.toByte()) +
                            encodeMemoryLimits(import.limits)
                    }
            return parseModule(section(IMPORT_SECTION_ID, body))
        }

        private fun parseModule(vararg sections: ByteArray): WasmModule =
            Parser.parse(
                WASM_HEADER +
                    sections.fold(ByteArray(0)) { bytes, section -> bytes + section }
            )

        private fun section(id: Int, body: ByteArray): ByteArray =
            byteArrayOf(id.toByte()) + unsignedLeb128(body.size) + body

        private fun encodeName(value: String): ByteArray {
            val bytes = value.encodeToByteArray()
            return unsignedLeb128(bytes.size) + bytes
        }

        private fun encodeMemoryLimits(limits: MemoryLimits): ByteArray {
            require(!limits.shared()) { "shared memory is not needed by these tests" }
            return if (limits.maximumPages() == MemoryLimits.MAX_PAGES) {
                byteArrayOf(MIN_ONLY_LIMITS.toByte()) + unsignedLeb128(limits.initialPages())
            } else {
                byteArrayOf(MIN_AND_MAX_LIMITS.toByte()) +
                    unsignedLeb128(limits.initialPages()) +
                    unsignedLeb128(limits.maximumPages())
            }
        }

        private fun unsignedLeb128(value: Int): ByteArray {
            require(value >= 0)
            val bytes = ArrayList<Byte>()
            var remaining = value
            do {
                var current = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining != 0) current = current or 0x80
                bytes.add(current.toByte())
            } while (remaining != 0)
            return bytes.toByteArray()
        }

        private fun moduleWithDefinedTables(vararg initialSizes: Int): WasmModule {
            val body =
                unsignedLeb128(initialSizes.size) +
                    initialSizes.fold(ByteArray(0)) { bytes, initial ->
                        bytes +
                            byteArrayOf(FUNCREF_TYPE.toByte(), MIN_ONLY_LIMITS.toByte()) +
                            unsignedLeb128(initial)
                    }
            return parseModule(section(TABLE_SECTION_ID, body))
        }

        /** `(type $a (array i32)) (global (ref null $a) (array.new_default $a (i32.const length)))` */
        private fun moduleWithConstantArrayGlobal(length: Int): WasmModule {
            val types =
                unsignedLeb128(1) +
                    byteArrayOf(ARRAY_TYPE.toByte(), I32_TYPE.toByte(), IMMUTABLE.toByte())
            val globals =
                unsignedLeb128(1) +
                    byteArrayOf(REF_NULL_TYPE.toByte(), 0x00, IMMUTABLE.toByte()) +
                    byteArrayOf(I32_CONST.toByte()) +
                    signedLeb128(length) +
                    byteArrayOf(GC_PREFIX.toByte()) +
                    unsignedLeb128(ARRAY_NEW_DEFAULT) +
                    unsignedLeb128(0) +
                    byteArrayOf(END.toByte())
            return parseModule(section(TYPE_SECTION_ID, types), section(GLOBAL_SECTION_ID, globals))
        }

        private fun signedLeb128(value: Int): ByteArray {
            val bytes = ArrayList<Byte>()
            var remaining = value
            while (true) {
                val current = remaining and 0x7f
                remaining = remaining shr 7
                val done =
                    (remaining == 0 && (current and 0x40) == 0) ||
                        (remaining == -1 && (current and 0x40) != 0)
                bytes.add((if (done) current else current or 0x80).toByte())
                if (done) {
                    return bytes.toByteArray()
                }
            }
        }

        private fun WasmModule.definedMemoryLimits(): List<MemoryLimits> {
            val section = memorySection() ?: return emptyList()
            return List(section.memoryCount()) { index -> section.getMemory(index).limits() }
        }

        private fun assertMemoryLimits(
            actual: List<MemoryLimits>,
            expectedInitials: List<Int>,
            expectedMaxima: List<Int>,
        ) {
            assertEquals(expectedInitials, actual.map { it.initialPages() })
            assertEquals(expectedMaxima, actual.map { it.maximumPages() })
        }

        private val WASM_HEADER =
            byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        private val DEFAULT_PAGES_PER_MEMORY =
            (DefaultWasmtimeMaxMemoryBytes / Memory.PAGE_SIZE.toLong()).toInt()
        private const val TYPE_SECTION_ID = 1
        private const val IMPORT_SECTION_ID = 2
        private const val TABLE_SECTION_ID = 4
        private const val MEMORY_SECTION_ID = 5
        private const val GLOBAL_SECTION_ID = 6
        private const val MEMORY_IMPORT_KIND = 2
        private const val MIN_ONLY_LIMITS = 0
        private const val MIN_AND_MAX_LIMITS = 1
        private const val FUNCREF_TYPE = 0x70
        private const val ARRAY_TYPE = 0x5E
        private const val I32_TYPE = 0x7F
        private const val REF_NULL_TYPE = 0x63
        private const val IMMUTABLE = 0x00
        private const val I32_CONST = 0x41
        private const val GC_PREFIX = 0xFB
        private const val ARRAY_NEW_DEFAULT = 0x07
        private const val END = 0x0B
    }
}
