package uk.shusek.krwa.wasm

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WasmParserLimitsTest {
    @Test
    fun enforcesModuleByteBoundaryForByteArraysAndSources() {
        parser(DEFAULT_LIMITS.copy(maxModuleBytes = EMPTY_MODULE.size.toLong()))
            .parseBytes(EMPTY_MODULE)

        assertLimit(
            name = "maxModuleBytes",
            configured = EMPTY_MODULE.size - 1L,
            actual = EMPTY_MODULE.size.toLong(),
        ) {
            parser(DEFAULT_LIMITS.copy(maxModuleBytes = EMPTY_MODULE.size - 1L))
                .parseBytes(EMPTY_MODULE)
        }

        assertLimit(
            name = "maxModuleBytes",
            configured = EMPTY_MODULE.size - 1L,
            actual = EMPTY_MODULE.size.toLong(),
        ) {
            val source = Buffer().apply { write(EMPTY_MODULE) }
            parser(DEFAULT_LIMITS.copy(maxModuleBytes = EMPTY_MODULE.size - 1L)).parse(source)
        }
    }

    @Test
    fun enforcesSectionAndCustomSectionByteBoundaries() {
        val typeBody = bytes(0)
        val typeModule = wasmModule(section(1, typeBody))
        parser(DEFAULT_LIMITS.copy(maxSectionBytes = typeBody.size)).parseBytes(typeModule)
        assertLimit("maxSectionBytes", 0, typeBody.size.toLong()) {
            parser(DEFAULT_LIMITS.copy(maxSectionBytes = 0)).parseBytes(typeModule)
        }

        val customBody = bytes(0)
        val customModule = wasmModule(section(0, customBody))
        parser(DEFAULT_LIMITS.copy(maxCustomSectionBytes = customBody.size))
            .parseBytes(customModule)
        assertLimit("maxCustomSectionBytes", 0, customBody.size.toLong()) {
            parser(DEFAULT_LIMITS.copy(maxCustomSectionBytes = 0)).parseBytes(customModule)
        }
    }

    @Test
    fun enforcesNameByteBoundaryBeforeAllocation() {
        val module = wasmModule(customSection("x"))
        parser(DEFAULT_LIMITS.copy(maxNameBytes = 1)).parseBytes(module)

        assertLimit("maxNameBytes", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxNameBytes = 0)).parseBytes(module)
        }
    }

    @Test
    fun enforcesTypeAndGenericVectorBoundaries() {
        val module = wasmModule(typeSection(params = 0, results = 0))
        parser(DEFAULT_LIMITS.copy(maxTypes = 1, maxVectorElements = 1)).parseBytes(module)

        assertLimit("maxTypes", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxTypes = 0)).parseBytes(module)
        }
        assertLimit("maxVectorElements", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxVectorElements = 0)).parseBytes(module)
        }
    }

    @Test
    fun enforcesFunctionParameterBoundaryBeforeListAllocation() {
        val module = wasmModule(typeSection(params = 1, results = 0))
        parser(DEFAULT_LIMITS.copy(maxFunctionParams = 1)).parseBytes(module)

        assertLimit("maxFunctionParams", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxFunctionParams = 0)).parseBytes(module)
        }
    }

    @Test
    fun enforcesFunctionCountAndBodyByteBoundaries() {
        val body = bytes(0, 0x0B)
        val module = functionModule(body)
        parser(
            DEFAULT_LIMITS.copy(
                maxFunctions = 1,
                maxFunctionBytes = body.size,
            )
        )
            .parseBytes(module)

        assertLimit("maxFunctions", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxFunctions = 0)).parseBytes(module)
        }
        assertLimit("maxFunctionBytes", body.size - 1L, body.size.toLong()) {
            parser(DEFAULT_LIMITS.copy(maxFunctionBytes = body.size - 1)).parseBytes(module)
        }
    }

    @Test
    fun enforcesFunctionLocalBoundaryAcrossLocalGroups() {
        val oneLocalBody = bytes(1, 1, 0x7F, 0x0B)
        parser(DEFAULT_LIMITS.copy(maxFunctionLocals = 1))
            .parseBytes(functionModule(oneLocalBody))

        assertLimit("maxFunctionLocals", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxFunctionLocals = 0))
                .parseBytes(functionModule(oneLocalBody))
        }

        val twoGroupsBody = bytes(2, 1, 0x7F, 1, 0x7F, 0x0B)
        assertLimit("maxFunctionLocals", 1, 2) {
            parser(DEFAULT_LIMITS.copy(maxFunctionLocals = 1))
                .parseBytes(functionModule(twoGroupsBody))
        }

        val excessiveLocalGroupBody =
            bytes(1) + unsignedLeb128(UInt.MAX_VALUE) + bytes(0x7F, 0x0B)
        val excessiveLocals =
            assertFailsWith<WasmParseLimitException> {
                parser(DEFAULT_LIMITS).parseBytes(functionModule(excessiveLocalGroupBody))
            }
        kotlin.test.assertContains(excessiveLocals.message.orEmpty(), "too many locals")
    }

    @Test
    fun enforcesInstructionAndControlDepthBoundaries() {
        val minimalBody = bytes(0, 0x0B)
        parser(DEFAULT_LIMITS.copy(maxInstructionsPerFunction = 1))
            .parseBytes(functionModule(minimalBody))
        assertLimit("maxInstructionsPerFunction", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxInstructionsPerFunction = 0))
                .parseBytes(functionModule(minimalBody))
        }

        val nestedBody = bytes(0, 0x02, 0x40, 0x0B, 0x0B)
        parser(DEFAULT_LIMITS.copy(maxControlDepth = 1)).parseBytes(functionModule(nestedBody))
        assertLimit("maxControlDepth", 0, 1) {
            parser(DEFAULT_LIMITS.copy(maxControlDepth = 0))
                .parseBytes(functionModule(nestedBody))
        }
    }

    private fun parser(limits: WasmParserLimits): WasmParser =
        WasmParser.builder().withValidation(false).withLimits(limits).build()

    private fun functionModule(body: ByteArray): ByteArray =
        wasmModule(
            typeSection(params = 0, results = 0),
            section(3, bytes(1, 0)),
            section(10, bytes(1) + unsignedLeb128(body.size) + body),
        )

    private fun typeSection(params: Int, results: Int): ByteArray =
        section(
            1,
            bytes(1, 0x60, params) +
                ByteArray(params) { 0x7F.toByte() } +
                bytes(results) +
                ByteArray(results) { 0x7F.toByte() },
        )

    private fun customSection(name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        return section(0, unsignedLeb128(nameBytes.size) + nameBytes)
    }

    private fun wasmModule(vararg sections: ByteArray): ByteArray =
        EMPTY_MODULE + sections.fold(ByteArray(0)) { module, section -> module + section }

    private fun section(id: Int, body: ByteArray): ByteArray =
        bytes(id) + unsignedLeb128(body.size) + body

    private fun unsignedLeb128(value: Int): ByteArray {
        return unsignedLeb128(value.toUInt())
    }

    private fun unsignedLeb128(value: UInt): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>()
        do {
            var next = (remaining and 0x7Fu).toInt()
            remaining = remaining shr 7
            if (remaining != 0u) {
                next = next or 0x80
            }
            result.add(next.toByte())
        } while (remaining != 0u)
        return result.toByteArray()
    }

    private fun bytes(vararg values: Int): ByteArray =
        values.map { value -> value.toByte() }.toByteArray()

    private inline fun assertLimit(
        name: String,
        configured: Long,
        actual: Long,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<WasmParseLimitException>(block = block)
        assertEquals(name, failure.limitName)
        assertEquals(configured, failure.configuredLimit)
        assertEquals(actual, failure.actual)
    }

    private companion object {
        val DEFAULT_LIMITS = WasmParserLimits()
        val EMPTY_MODULE =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
