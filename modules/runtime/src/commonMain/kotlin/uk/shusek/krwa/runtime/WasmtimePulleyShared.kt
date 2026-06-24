@file:Suppress(
    "MagicNumber",
    "PropertyName",
    "ReturnCount",
    "ThrowsCount",
    "TooManyFunctions",
    "TopLevelPropertyNaming",
)

package uk.shusek.krwa.runtime

import uk.shusek.krwa.runtime.ImportFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

internal data class PulleyFunctionExport(val name: String, val index: Int, val type: FunctionType)

internal data class PulleyImportSpec(val callbackId: Long, val paramOpcodes: IntArray, val returnOpcodes: IntArray)

internal data class PulleyModuleBytes(val bytes: ByteArray, val syntheticMemoryExports: Map<Int, String>)

internal fun buildPulleyImportSpecs(
    module: WasmModule,
    imports: ImportValues,
    hostInstance: Instance,
    register: (ImportFunction, FunctionType, Instance) -> Long,
): List<PulleyImportSpec> {
    val result = ArrayList<PulleyImportSpec>(module.importSection().importCount())
    for (i in 0 until module.importSection().importCount()) {
        val importValue = module.importSection().getImport(i)
        if (importValue.importType() != ExternalType.FUNCTION) {
            throw UnlinkableException("Wasmtime Pulley currently supports function imports only: $importValue")
        }
        val functionImport = importValue as FunctionImport
        val expectedType = module.typeSection().getType(functionImport.typeIndex())
        val hostFunction =
            findPulleyFunction(imports, importValue.module(), importValue.name())
                ?: throw UnlinkableException(
                    "unknown native import, could not find import named " +
                        "${importValue.module()}.${importValue.name()}",
                )
        if (!expectedType.typesMatch(hostFunction.functionType())) {
            throw UnlinkableException(
                "incompatible native import type for function " +
                    "${importValue.module()}.${importValue.name()}",
            )
        }

        result +=
            PulleyImportSpec(
                callbackId = register(hostFunction, expectedType, hostInstance),
                paramOpcodes = expectedType.params().toPulleyOpcodes(),
                returnOpcodes = expectedType.returns().toPulleyOpcodes(),
            )
    }
    return result
}

internal fun exportedPulleyFunctions(module: WasmModule): Map<String, PulleyFunctionExport> {
    val result = HashMap<String, PulleyFunctionExport>()
    val importedFunctionCount = module.importSection().count(ExternalType.FUNCTION)
    for (i in 0 until module.exportSection().exportCount()) {
        val export = module.exportSection().getExport(i)
        if (export.exportType() != ExternalType.FUNCTION) {
            continue
        }
        val type =
            if (export.index() < importedFunctionCount) {
                val importValue = module.importSection().getImport(export.index()) as FunctionImport
                module.typeSection().getType(importValue.typeIndex())
            } else {
                module.functionSection().getFunctionType(export.index() - importedFunctionCount, module.typeSection())
            }
        result[export.name()] = PulleyFunctionExport(export.name(), export.index(), type)
    }
    return result
}

internal fun exportedPulleyMemoryInitialPages(module: WasmModule, index: Int): Int {
    val importedMemoryCount = module.importSection().count(ExternalType.MEMORY)
    val definedIndex = index - importedMemoryCount
    val memorySection = module.memorySection()
    if (definedIndex < 0 || memorySection == null || definedIndex >= memorySection.memoryCount()) {
        return 0
    }
    return memorySection.getMemory(definedIndex).limits().initialPages()
}

internal fun List<ValType>.toPulleyOpcodes(): IntArray = IntArray(size) { i -> this[i].numericPulleyOpcode() }

internal fun moduleBytesWithSyntheticMemoryExports(bytes: ByteArray, module: WasmModule): PulleyModuleBytes {
    val missingExports = missingDefinedMemoryExports(module)
    if (missingExports.isEmpty()) {
        return PulleyModuleBytes(bytes, missingExports)
    }
    return PulleyModuleBytes(insertSyntheticMemoryExports(bytes, missingExports), missingExports)
}

private fun findPulleyFunction(imports: ImportValues, module: String, name: String): ImportFunction? =
    imports.functions().firstOrNull { function ->
        function.module() == module && function.name() == name
    }

private fun ValType.numericPulleyOpcode(): Int = when (opcode()) {
    ValType.ID.I32,
    ValType.ID.I64,
    ValType.ID.F32,
    ValType.ID.F64,
    -> opcode()

    else -> throw WasmEngineException("Wasmtime Pulley bridge supports numeric boundary values only: $this")
}

private fun missingDefinedMemoryExports(module: WasmModule): Map<Int, String> {
    val memorySection = module.memorySection()
    if (memorySection == null || memorySection.memoryCount() == 0) {
        return emptyMap()
    }
    val result = LinkedHashMap<Int, String>()
    val importedMemoryCount = module.importSection().count(ExternalType.MEMORY)
    val exportedMemoryIndexes = HashSet<Int>()
    val exportNames = ArrayList<String>()
    for (i in 0 until module.exportSection().exportCount()) {
        val export = module.exportSection().getExport(i)
        exportNames += export.name()
        if (export.exportType() == ExternalType.MEMORY) {
            exportedMemoryIndexes += export.index()
        }
    }
    for (definedIndex in 0 until memorySection.memoryCount()) {
        val memoryIndex = importedMemoryCount + definedIndex
        if (memoryIndex in exportedMemoryIndexes) {
            continue
        }
        val syntheticName = uniqueSyntheticExportName(exportNames, memoryIndex)
        result[memoryIndex] = syntheticName
        exportNames += syntheticName
    }
    return result
}

private fun uniqueSyntheticExportName(exportNames: List<String>, memoryIndex: Int): String {
    val base = "$SYNTHETIC_MEMORY_EXPORT_PREFIX$memoryIndex"
    var name = base
    var suffix = 1
    while (name in exportNames) {
        name = "${base}_${suffix++}"
    }
    return name
}

private fun insertSyntheticMemoryExports(bytes: ByteArray, missingExports: Map<Int, String>): ByteArray {
    if (bytes.size < WASM_MAGIC_AND_VERSION_SIZE) {
        return bytes
    }
    val out = ByteSink(bytes.size + missingExports.size * 24)
    out.write(bytes, 0, WASM_MAGIC_AND_VERSION_SIZE)
    var position = WASM_MAGIC_AND_VERSION_SIZE
    var wroteExportSection = false
    while (position < bytes.size) {
        val sectionStart = position
        val sectionId = bytes[position++].toInt() and 0xff
        val sectionSize = readUnsignedLeb128(bytes, position)
        val bodyStart = sectionSize.nextPosition
        val bodyEnd = bodyStart + sectionSize.value
        if (bodyEnd > bytes.size) {
            return bytes
        }
        if (!wroteExportSection && sectionId != 0 && sectionId > WASM_EXPORT_SECTION_ID) {
            writeExportSection(out, null, 0, 0, missingExports)
            wroteExportSection = true
        }
        if (sectionId == WASM_EXPORT_SECTION_ID) {
            writeExportSection(out, bytes, bodyStart, bodyEnd, missingExports)
            wroteExportSection = true
        } else {
            out.write(bytes, sectionStart, bodyEnd - sectionStart)
        }
        position = bodyEnd
    }
    if (!wroteExportSection) {
        writeExportSection(out, null, 0, 0, missingExports)
    }
    return out.toByteArray()
}

private fun writeExportSection(
    out: ByteSink,
    originalBytes: ByteArray?,
    bodyStart: Int,
    bodyEnd: Int,
    memoryExports: Map<Int, String>,
) {
    val body = ByteSink()
    var originalExportCount = 0
    var originalExportsStart = bodyEnd
    if (originalBytes != null) {
        val count = readUnsignedLeb128(originalBytes, bodyStart)
        originalExportCount = count.value
        originalExportsStart = count.nextPosition
    }
    writeUnsignedLeb128(body, originalExportCount + memoryExports.size)
    if (originalBytes != null) {
        body.write(originalBytes, originalExportsStart, bodyEnd - originalExportsStart)
    }
    for ((memoryIndex, name) in memoryExports) {
        writeName(body, name)
        writeUnsignedLeb128(body, ExternalType.MEMORY.id())
        writeUnsignedLeb128(body, memoryIndex)
    }
    out.write(WASM_EXPORT_SECTION_ID)
    writeUnsignedLeb128(out, body.size)
    out.write(body.toByteArray())
}

private fun writeName(out: ByteSink, value: String) {
    val bytes = value.encodeToByteArray()
    writeUnsignedLeb128(out, bytes.size)
    out.write(bytes)
}

private fun writeUnsignedLeb128(out: ByteSink, value: Int) {
    var remaining = value.toLong() and 0xffff_ffffL
    do {
        var current = (remaining and 0x7f).toInt()
        remaining = remaining ushr 7
        if (remaining != 0L) {
            current = current or 0x80
        }
        out.write(current)
    } while (remaining != 0L)
}

private fun readUnsignedLeb128(bytes: ByteArray, position: Int): Leb {
    var result = 0
    var shift = 0
    var currentPosition = position
    while (currentPosition < bytes.size) {
        val current = bytes[currentPosition++].toInt() and 0xff
        result = result or ((current and 0x7f) shl shift)
        if ((current and 0x80) == 0) {
            return Leb(result, currentPosition)
        }
        shift += 7
    }
    return Leb(0, currentPosition)
}

private data class Leb(val value: Int, val nextPosition: Int)

private class ByteSink(initialCapacity: Int = 64) {
    private var bytes = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun write(value: Int) {
        ensureCapacity(size + 1)
        bytes[size++] = value.toByte()
    }

    fun write(source: ByteArray) {
        write(source, 0, source.size)
    }

    fun write(source: ByteArray, offset: Int, length: Int) {
        ensureCapacity(size + length)
        source.copyInto(bytes, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) {
            return
        }
        var newSize = bytes.size
        while (newSize < required) {
            newSize *= 2
        }
        bytes = bytes.copyOf(newSize)
    }
}

private const val WASM_MAGIC_AND_VERSION_SIZE = 8
private const val WASM_EXPORT_SECTION_ID = 7
private const val SYNTHETIC_MEMORY_EXPORT_PREFIX = "__krwa_memory_"
