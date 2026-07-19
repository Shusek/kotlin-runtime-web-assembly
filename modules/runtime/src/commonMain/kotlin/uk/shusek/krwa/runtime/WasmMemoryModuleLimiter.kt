package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.MemorySection

/**
 * Rewrites the defined-memory section before handing a module to a native engine.
 *
 * Engine-level store limiters commonly expose one uniform per-memory limit. Rewriting the module
 * preserves the independently resolved maxima, including an aggregate budget split across
 * multiple memories, on every backend.
 */
internal fun WasmModule.withDefinedMemoryLimits(
    definedMemoryLimits: Array<MemoryLimits>?,
): WasmModule {
    val limits = definedMemoryLimits ?: return this
    val section = memorySection()
    if (limits.isEmpty() || section == null) return this
    check(section.memoryCount() == limits.size) {
        "resolved memory limit count does not match the module memory section"
    }
    val unchanged =
        limits.indices.all { index ->
            val declared = section.getMemory(index).limits()
            val effective = limits[index]
            declared.initialPages() == effective.initialPages() &&
                declared.maximumPages() == effective.maximumPages() &&
                declared.shared() == effective.shared()
        }
    if (unchanged) return this

    val bytes =
        originalBytes()
            ?: throw IllegalStateException(
                "native WebAssembly execution needs original module bytes to enforce memory policy"
            )
    val rewrittenBytes = rewriteDefinedMemorySection(bytes, limits)
    val effectiveSection = MemorySection.builder()
    for (limit in limits) {
        effectiveSection.addMemory(uk.shusek.krwa.wasm.types.Memory(limit))
    }

    val builder =
        WasmModule.Builder.create()
            .setTypeSection(typeSection())
            .setImportSection(importSection())
            .setFunctionSection(functionSection())
            .setTableSection(tableSection())
            .setMemorySection(effectiveSection.build())
            .setGlobalSection(globalSection())
            .setExportSection(exportSection())
            .setStartSection(startSection())
            .setElementSection(elementSection())
            .setCodeSection(codeSection())
            .setDataSection(dataSection())
            .setDataCountSection(dataCountSection())
            .setTagSection(tagSection())
            .withDigest(null)
            .withOriginalBytes(rewrittenBytes)
            .withValidation(false)
    for (customSection in customSections()) {
        builder.addCustomSection(customSection.name(), customSection)
    }
    for (ignoredSection in ignoredSections()) {
        builder.addIgnoredSection(ignoredSection)
    }
    return builder.build()
}

private fun rewriteDefinedMemorySection(
    bytes: ByteArray,
    limits: Array<MemoryLimits>,
): ByteArray {
    require(bytes.size >= WASM_HEADER_SIZE) { "invalid WebAssembly module header" }
    val output = WasmByteArrayBuilder(bytes.size + limits.size * 4)
    output.write(bytes, 0, WASM_HEADER_SIZE)
    var position = WASM_HEADER_SIZE
    var replaced = false
    while (position < bytes.size) {
        val sectionStart = position
        val sectionId = bytes[position++].toInt() and 0xff
        val sectionSize = readUnsignedLeb128(bytes, position)
        val bodyStart = sectionSize.nextPosition
        val bodyEnd = bodyStart + sectionSize.value
        require(bodyEnd >= bodyStart && bodyEnd <= bytes.size) {
            "invalid WebAssembly section size"
        }
        if (sectionId == MEMORY_SECTION_ID) {
            check(!replaced) { "duplicate WebAssembly memory section" }
            val body = WasmByteArrayBuilder(limits.size * 4 + 5)
            body.writeUnsignedLeb128(limits.size)
            for (limit in limits) {
                body.writeByte(if (limit.shared()) SHARED_MEMORY_WITH_MAX else MEMORY_WITH_MAX)
                body.writeUnsignedLeb128(limit.initialPages())
                body.writeUnsignedLeb128(limit.maximumPages())
            }
            output.writeByte(MEMORY_SECTION_ID)
            output.writeUnsignedLeb128(body.size)
            output.write(body.toByteArray(), 0, body.size)
            replaced = true
        } else {
            output.write(bytes, sectionStart, bodyEnd - sectionStart)
        }
        position = bodyEnd
    }
    check(replaced) { "resolved memory limits require a WebAssembly memory section" }
    return output.toByteArray()
}

private data class UnsignedLeb128(
    val value: Int,
    val nextPosition: Int,
)

private fun readUnsignedLeb128(bytes: ByteArray, start: Int): UnsignedLeb128 {
    var value = 0
    var shift = 0
    var position = start
    while (position < bytes.size && shift <= 28) {
        val current = bytes[position++].toInt() and 0xff
        value = value or ((current and 0x7f) shl shift)
        if ((current and 0x80) == 0) {
            return UnsignedLeb128(value, position)
        }
        shift += 7
    }
    throw IllegalArgumentException("invalid unsigned LEB128 value")
}

private class WasmByteArrayBuilder(initialCapacity: Int) {
    private var bytes = ByteArray(maxOf(initialCapacity, 16))
    var size: Int = 0
        private set

    fun writeByte(value: Int) {
        ensureCapacity(1)
        bytes[size++] = value.toByte()
    }

    fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        ensureCapacity(length)
        source.copyInto(bytes, size, offset, offset + length)
        size += length
    }

    fun writeUnsignedLeb128(value: Int) {
        require(value >= 0)
        var remaining = value
        do {
            var current = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            writeByte(current)
        } while (remaining != 0)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(additional: Int) {
        require(additional >= 0 && size <= Int.MAX_VALUE - additional)
        val required = size + additional
        if (required <= bytes.size) return
        var capacity = bytes.size
        while (capacity < required) {
            val grown = capacity + maxOf(capacity / 2, 16)
            capacity = if (grown < capacity || grown > Int.MAX_VALUE) required else grown
        }
        bytes = bytes.copyOf(capacity)
    }
}

private const val WASM_HEADER_SIZE = 8
private const val MEMORY_SECTION_ID = 5
private const val MEMORY_WITH_MAX = 0x01
private const val SHARED_MEMORY_WITH_MAX = 0x03
