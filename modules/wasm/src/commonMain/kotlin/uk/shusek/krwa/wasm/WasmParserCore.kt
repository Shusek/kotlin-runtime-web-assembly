package uk.shusek.krwa.wasm

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import uk.shusek.krwa.wasm.types.ActiveDataSegment
import uk.shusek.krwa.wasm.types.ActiveElement
import uk.shusek.krwa.wasm.types.AnnotatedInstruction
import uk.shusek.krwa.wasm.types.ArrayType
import uk.shusek.krwa.wasm.types.CatchOpCode
import uk.shusek.krwa.wasm.types.CodeSection
import uk.shusek.krwa.wasm.types.CompType
import uk.shusek.krwa.wasm.types.CustomSection
import uk.shusek.krwa.wasm.types.DataCountSection
import uk.shusek.krwa.wasm.types.DataSection
import uk.shusek.krwa.wasm.types.DeclarativeElement
import uk.shusek.krwa.wasm.types.Element
import uk.shusek.krwa.wasm.types.ElementSection
import uk.shusek.krwa.wasm.types.Export
import uk.shusek.krwa.wasm.types.ExportSection
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FieldType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionSection
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.Global
import uk.shusek.krwa.wasm.types.GlobalImport
import uk.shusek.krwa.wasm.types.GlobalSection
import uk.shusek.krwa.wasm.types.ImportSection
import uk.shusek.krwa.wasm.types.Instruction
import uk.shusek.krwa.wasm.types.Instruction.Companion.EMPTY_OPERANDS
import uk.shusek.krwa.wasm.types.Memory
import uk.shusek.krwa.wasm.types.MemoryImport
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.MemorySection
import uk.shusek.krwa.wasm.types.MutabilityType
import uk.shusek.krwa.wasm.types.NameCustomSection
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.PackedType
import uk.shusek.krwa.wasm.types.PassiveDataSegment
import uk.shusek.krwa.wasm.types.PassiveElement
import uk.shusek.krwa.wasm.types.RawSection
import uk.shusek.krwa.wasm.types.RecType
import uk.shusek.krwa.wasm.types.Section
import uk.shusek.krwa.wasm.types.SectionId
import uk.shusek.krwa.wasm.types.StartSection
import uk.shusek.krwa.wasm.types.StorageType
import uk.shusek.krwa.wasm.types.StructType
import uk.shusek.krwa.wasm.types.SubType
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableImport
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.TableSection
import uk.shusek.krwa.wasm.types.TagImport
import uk.shusek.krwa.wasm.types.TagSection
import uk.shusek.krwa.wasm.types.TagType
import uk.shusek.krwa.wasm.types.TypeSection
import uk.shusek.krwa.wasm.types.UnknownCustomSection
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

/** Parser for Web Assembly binaries. */
internal class WasmParserCore
private constructor(
    includeSections: Set<Int>?,
    customParsers: Map<String, (ByteArray) -> CustomSection>,
    private val validate: Boolean,
    private val multiTable: Boolean,
    private val multiMemory: Boolean,
    private val threadsMemory: Boolean,
    private val forwardTypeReferences: Boolean,
    private val localGlobalReferencesInConstantExpressions: Boolean,
    private val limits: WasmParserLimits,
) {
    private val includeSections: Set<Int>? = includeSections?.toSet()
    private val customParsers: Map<String, (ByteArray) -> CustomSection> = customParsers.toMap()
    private var typeSection: TypeSection = TypeSection.builder().build()

    private constructor() :
        this(
            null,
            defaultCustomParsers(WasmParserLimits()),
            true,
            true,
            true,
            true,
            true,
            true,
            WasmParserLimits(),
        )

    class Builder private constructor() {
        private var customParsers: Map<String, (ByteArray) -> CustomSection>? = null
        private var includeSections: MutableSet<Int>? = null
        private var validate = true
        private var multiTable = true
        private var multiMemory = true
        private var threadsMemory = true
        private var forwardTypeReferences = true
        private var localGlobalReferencesInConstantExpressions = true
        private var limits = WasmParserLimits()

        /** @param sectionId the sectionId to be included while parsing, e.g. SectionId.MEMORY */
        fun includeSectionId(sectionId: Int): Builder {
            if (includeSections == null) {
                includeSections = mutableSetOf()
            }
            includeSections!!.add(sectionId)
            return this
        }

        fun withCustomParsers(
            customParsers: Map<String, (ByteArray) -> CustomSection>
        ): Builder {
            this.customParsers = customParsers
            return this
        }

        fun withValidation(validate: Boolean): Builder {
            this.validate = validate
            return this
        }

        fun withMultiTable(multiTable: Boolean): Builder {
            this.multiTable = multiTable
            return this
        }

        fun withMultiMemory(multiMemory: Boolean): Builder {
            this.multiMemory = multiMemory
            return this
        }

        fun withThreadsMemory(threadsMemory: Boolean): Builder {
            this.threadsMemory = threadsMemory
            return this
        }

        fun withForwardTypeReferences(allow: Boolean): Builder {
            this.forwardTypeReferences = allow
            return this
        }

        fun withLocalGlobalReferencesInConstantExpressions(allow: Boolean): Builder {
            this.localGlobalReferencesInConstantExpressions = allow
            return this
        }

        fun withLimits(limits: WasmParserLimits): Builder {
            this.limits = limits
            return this
        }

        fun build(): WasmParserCore {
            val configuredCustomParsers = customParsers ?: defaultCustomParsers(limits)
            return WasmParserCore(
                includeSections,
                configuredCustomParsers,
                validate,
                multiTable,
                multiMemory,
                threadsMemory,
                forwardTypeReferences,
                localGlobalReferencesInConstantExpressions,
                limits,
            )
        }

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    fun parseBytes(bytes: ByteArray): WasmModule {
        val moduleBuilder = WasmModule.builder()
        moduleBuilder.withValidation(validate)
        moduleBuilder.withMultiTable(multiTable)
        moduleBuilder.withMultiMemory(multiMemory)
        moduleBuilder.withLocalGlobalReferencesInConstantExpressions(
            localGlobalReferencesInConstantExpressions
        )
        try {
            parseBytes(bytes, ParserListener { section -> onSection(moduleBuilder, section) })
            moduleBuilder.withDigest(WasmDigest.sha256(bytes))
            moduleBuilder.withOriginalBytes(bytes)
        } catch (e: WasmParseLimitException) {
            throw e
        } catch (e: MalformedException) {
            throw MalformedException(
                "section size mismatch, unexpected end of section or function, " + e.message,
                e,
            )
        }
        return moduleBuilder.build()
    }

    fun parse(source: RawSource): WasmModule =
        try {
            parseBytes(readSourceBytes(source))
        } finally {
            source.close()
        }

    fun parseBytes(bytes: ByteArray, listener: ParserListener) {
        parseBytes(bytes, listener, true)
    }

    fun parse(source: RawSource, listener: ParserListener) {
        parse(source, listener, true)
    }

    fun parseWithoutDecoding(bytes: ByteArray, listener: ParserListener) {
        parseBytes(bytes, listener, false)
    }

    fun parseWithoutDecoding(source: RawSource, listener: ParserListener) {
        parse(source, listener, false)
    }

    private fun parse(source: RawSource, listener: ParserListener, decode: Boolean) {
        parseBytes(readSourceBytes(source), listener, decode)
    }

    private fun parseBytes(bytes: ByteArray, listener: ParserListener, decode: Boolean = true) {
        requireWithinLimit("maxModuleBytes", limits.maxModuleBytes, bytes.size.toLong())
        val validator = SectionsValidator()
        val buffer = WasmByteReader(bytes, limits)

        val magic = ByteArray(4)
        readBytes(buffer, magic)
        if (!magic.contentEquals(MAGIC_BYTES)) {
            throw MalformedException(
                "magic header not detected, found: " +
                    magic.contentToString() +
                    " expected: " +
                    MAGIC_BYTES.contentToString()
            )
        }

        val version = ByteArray(4)
        readBytes(buffer, version)
        if (!version.contentEquals(VERSION_BYTES)) {
            throw MalformedException(
                "unknown binary version, found: " +
                    version.contentToString() +
                    " expected: " +
                    VERSION_BYTES.contentToString()
            )
        }

        var firstTime = true
        while (buffer.hasRemaining()) {
            val sectionId = readByte(buffer)
            val sectionSize = readVarUInt32(buffer)
            requireWithinLimit(
                "maxSectionBytes",
                limits.maxSectionBytes.toLong(),
                sectionSize,
            )
            if (sectionId.toInt() == SectionId.CUSTOM) {
                requireWithinLimit(
                    "maxCustomSectionBytes",
                    limits.maxCustomSectionBytes.toLong(),
                    sectionSize,
                )
            }

            validator.validateSectionType(sectionId)

            val sectionWasmByteReader = buffer.slice(sectionSize.toBoundedInt())

            if (shouldParseSection(sectionId.toInt())) {
                if (!decode) {
                    listener.onSection(
                        parseRawSection(sectionWasmByteReader, sectionId, sectionSize)
                    )
                    continue
                }

                when (sectionId.toInt()) {
                    SectionId.CUSTOM -> {
                        val customSection =
                            parseCustomSection(sectionWasmByteReader, sectionSize, firstTime)
                        firstTime = false
                        listener.onSection(customSection)
                    }
                    SectionId.TYPE -> {
                        val parsedTypeSection =
                            parseTypeSection(sectionWasmByteReader, forwardTypeReferences)
                        typeSection = parsedTypeSection
                        listener.onSection(parsedTypeSection)
                    }
                    SectionId.IMPORT ->
                        listener.onSection(
                            parseImportSection(sectionWasmByteReader, typeSection, threadsMemory)
                        )
                    SectionId.FUNCTION ->
                        listener.onSection(parseFunctionSection(sectionWasmByteReader))
                    SectionId.TABLE ->
                        listener.onSection(
                            parseTableSection(sectionWasmByteReader, typeSection, multiMemory)
                        )
                    SectionId.MEMORY ->
                        listener.onSection(parseMemorySection(sectionWasmByteReader, threadsMemory))
                    SectionId.TAG -> listener.onSection(parseTagSection(sectionWasmByteReader))
                    SectionId.GLOBAL ->
                        listener.onSection(
                            parseGlobalSection(sectionWasmByteReader, typeSection, multiMemory)
                        )
                    SectionId.EXPORT -> listener.onSection(parseExportSection(sectionWasmByteReader))
                    SectionId.START -> listener.onSection(parseStartSection(sectionWasmByteReader))
                    SectionId.ELEMENT ->
                        listener.onSection(
                            parseElementSection(
                                sectionWasmByteReader,
                                sectionSize,
                                typeSection,
                                multiMemory,
                            )
                        )
                    SectionId.CODE ->
                        listener.onSection(
                            parseCodeSection(sectionWasmByteReader, typeSection, multiMemory)
                        )
                    SectionId.DATA ->
                        listener.onSection(parseDataSection(sectionWasmByteReader, multiMemory))
                    SectionId.DATA_COUNT ->
                        listener.onSection(parseDataCountSection(sectionWasmByteReader))
                    else ->
                        throw MalformedException(
                            "section size mismatch, malformed section id $sectionId"
                        )
                }

                if (sectionWasmByteReader.hasRemaining()) {
                    throw MalformedException("section size mismatch")
                }
            }
        }
    }

    internal fun parserLimits(): WasmParserLimits = limits

    private fun readSourceBytes(source: RawSource): ByteArray {
        val output = Buffer()
        val chunk = Buffer()
        var total = 0L
        while (true) {
            val requested = minOf(SOURCE_READ_CHUNK_SIZE, limits.maxModuleBytes - total + 1)
            val read = source.readAtMostTo(chunk, requested)
            if (read == -1L) {
                return output.readByteArray()
            }
            if (read == 0L) {
                throw WasmEngineException("RawSource returned no data before reaching end of input")
            }
            total += read
            requireWithinLimit("maxModuleBytes", limits.maxModuleBytes, total)
            output.write(chunk, read)
        }
    }

    private class SectionsValidator {
        private val sectionsOrder =
            arrayListOf(
                SectionId.TYPE,
                SectionId.IMPORT,
                SectionId.FUNCTION,
                SectionId.TABLE,
                SectionId.MEMORY,
                SectionId.GLOBAL,
                SectionId.EXPORT,
                SectionId.START,
                SectionId.ELEMENT,
                SectionId.DATA_COUNT,
                SectionId.CODE,
                SectionId.DATA,
            )
        private var maxSection = -1

        fun validateSectionType(sectionId: Byte) {
            val id = sectionId.toInt()
            if (sectionsOrder.contains(id)) {
                val current = sectionsOrder.indexOf(id)
                if (maxSection < 0 || current > maxSection) {
                    maxSection = current
                } else {
                    throw MalformedException("unexpected content after last section")
                }
            }
        }
    }

    private fun shouldParseSection(sectionId: Int): Boolean =
        includeSections == null || sectionId in includeSections

    private fun parseCustomSection(
        buffer: WasmByteReader,
        sectionSize: Long,
        checkMalformed: Boolean,
    ): CustomSection {
        val sectionPos = buffer.position()
        val name = readName(buffer, checkMalformed)
        val size = sectionSize - (buffer.position() - sectionPos)
        if (size < 0) {
            throw MalformedException("unexpected end")
        }
        val bytes = ByteArray(size.toInt())
        readBytes(buffer, bytes)
        val parser = customParsers[name]
        return parser?.invoke(bytes)
            ?: UnknownCustomSection.builder().withName(name).withBytes(bytes).build()
    }

    companion object {
        val MAGIC_BYTES: ByteArray = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
        val VERSION_BYTES: ByteArray = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        private const val SOURCE_READ_CHUNK_SIZE = 8L * 1024

        private fun defaultCustomParsers(
            limits: WasmParserLimits
        ): Map<String, (ByteArray) -> CustomSection> =
            mapOf("name" to { bytes -> NameCustomSection.parse(bytes, limits) })

        fun builder(): Builder = Builder.create()

        fun parse(source: RawSource): WasmModule = WasmParserCore().parse(source)

        fun parse(buffer: ByteArray): WasmModule = WasmParserCore().parseBytes(buffer)

        fun parseWithoutDecoding(bytes: ByteArray, listener: ParserListener) {
            WasmParserCore().parseWithoutDecoding(bytes, listener)
        }

        fun parseWithoutDecoding(source: RawSource, listener: ParserListener) {
            WasmParserCore().parseWithoutDecoding(source, listener)
        }

        private fun onSection(module: WasmModule.Builder, section: Section) {
            when (section.sectionId()) {
                SectionId.CUSTOM -> {
                    val customSection = section as CustomSection
                    module.addCustomSection(customSection.name(), customSection)
                }
                SectionId.TYPE -> module.setTypeSection(section as TypeSection)
                SectionId.IMPORT -> module.setImportSection(section as ImportSection)
                SectionId.FUNCTION -> module.setFunctionSection(section as FunctionSection)
                SectionId.TABLE -> module.setTableSection(section as TableSection)
                SectionId.MEMORY -> module.setMemorySection(section as MemorySection)
                SectionId.GLOBAL -> module.setGlobalSection(section as GlobalSection)
                SectionId.EXPORT -> module.setExportSection(section as ExportSection)
                SectionId.START -> module.setStartSection(section as StartSection)
                SectionId.ELEMENT -> module.setElementSection(section as ElementSection)
                SectionId.CODE -> module.setCodeSection(section as CodeSection)
                SectionId.DATA -> module.setDataSection(section as DataSection)
                SectionId.DATA_COUNT -> module.setDataCountSection(section as DataCountSection)
                SectionId.TAG -> module.setTagSection(section as TagSection)
                else -> module.addIgnoredSection(section.sectionId())
            }
        }

        private fun parseRawSection(
            buffer: WasmByteReader,
            sectionId: Byte,
            sectionSize: Long,
        ): RawSection {
            val bytes = ByteArray(sectionSize.toBoundedInt())
            readBytes(buffer, bytes)
            return RawSection(sectionId.toLong(), bytes)
        }

        private fun parseFieldType(buffer: WasmByteReader): FieldType {
            val id = readVarUInt32(buffer).toInt()
            return if (id == PackedType.I8.ID() || id == PackedType.I16.ID()) {
                val packedType = PackedType.fromId(id)
                val mut = MutabilityType.forId(readByte(buffer).toInt())
                FieldType.builder()
                    .withStorageType(StorageType.builder().withPackedType(packedType).build())
                    .withMutability(mut)
                    .build()
            } else {
                val valType = readValueTypeBuilderFromOpCode(buffer, id).build()
                val mut = MutabilityType.forId(readByte(buffer).toInt())
                FieldType.builder()
                    .withStorageType(StorageType.builder().withValType(valType).build())
                    .withMutability(mut)
                    .build()
            }
        }

        private fun parseArrayType(buffer: WasmByteReader): ArrayType =
            ArrayType.builder().withFieldType(parseFieldType(buffer)).build()

        private fun parseStructType(buffer: WasmByteReader): StructType {
            val count =
                readVectorSize(
                    buffer,
                    "maxStructFields",
                    buffer.limits.maxStructFields,
                )
            val builder = StructType.builder()
            for (i in 0 until count) {
                builder.addFieldType(parseFieldType(buffer))
            }
            return builder.build()
        }

        private fun parseFunctionType(buffer: WasmByteReader): FunctionType {
            val paramCount =
                readVectorSize(
                    buffer,
                    "maxFunctionParams",
                    buffer.limits.maxFunctionParams,
                )
            val paramsBuilder = ArrayList<ValType>(paramCount)
            for (i in 0 until paramCount) {
                paramsBuilder.add(readValueTypeBuilder(buffer).build())
            }

            val returnCount =
                readVectorSize(
                    buffer,
                    "maxFunctionResults",
                    buffer.limits.maxFunctionResults,
                )
            val returnsBuilder = ArrayList<ValType>(returnCount)
            for (i in 0 until returnCount) {
                returnsBuilder.add(readValueTypeBuilder(buffer).build())
            }

            return FunctionType.of(paramsBuilder, returnsBuilder)
        }

        private fun parseCompType(id: Int, buffer: WasmByteReader): CompType {
            if (id > Byte.MAX_VALUE) {
                throw MalformedException("integer representation too long")
            }
            return when (id) {
                0x5E -> CompType.builder().withArrayType(parseArrayType(buffer)).build()
                0x5F -> CompType.builder().withStructType(parseStructType(buffer)).build()
                0x60 -> CompType.builder().withFuncType(parseFunctionType(buffer)).build()
                else ->
                    throw MalformedException(
                        "Invalid composite type. Form " +
                            id.toHexByteString() +
                            " was not 0x5E, 0x5f or 0x60"
                    )
            }
        }

        private fun parseSubType(id: Int, buffer: WasmByteReader): SubType =
            if (id == 0x50 || id == 0x4F) {
                val count =
                    readVectorSize(
                        buffer,
                        "maxSupertypes",
                        buffer.limits.maxSupertypes,
                    )
                val typeIdxs = IntArray(count)
                for (i in 0 until count) {
                    typeIdxs[i] = readVarUInt32(buffer).toInt()
                }
                SubType.builder()
                    .withTypeIdx(typeIdxs)
                    .withFinal(id == 0x4F)
                    .withCompType(parseCompType(readVarUInt32(buffer).toInt(), buffer))
                    .build()
            } else {
                SubType.builder()
                    .withTypeIdx(IntArray(0))
                    .withFinal(true)
                    .withCompType(parseCompType(id, buffer))
                    .build()
            }

        private fun parseRecType(
            buffer: WasmByteReader,
            parsedTypeCount: Int,
        ): RecType {
            val discriminator = readVarUInt32(buffer).toInt()
            return if (discriminator == 0x4E) {
                val count =
                    readVectorSize(
                        buffer,
                        "maxRecGroupTypes",
                        buffer.limits.maxRecGroupTypes,
                    )
                requireWithinLimit(
                    "maxTypes",
                    buffer.limits.maxTypes.toLong(),
                    parsedTypeCount.toLong() + count,
                )
                val subTypes = arrayOfNulls<SubType>(count)
                for (i in 0 until count) {
                    subTypes[i] = parseSubType(readVarUInt32(buffer).toInt(), buffer)
                }
                @Suppress("UNCHECKED_CAST")
                RecType.builder().withSubTypes(subTypes as Array<SubType>).build()
            } else {
                requireWithinLimit(
                    "maxTypes",
                    buffer.limits.maxTypes.toLong(),
                    parsedTypeCount.toLong() + 1,
                )
                RecType.builder().withSubTypes(arrayOf(parseSubType(discriminator, buffer))).build()
            }
        }

        private fun parseTypeSection(
            buffer: WasmByteReader,
            forwardTypeReferences: Boolean,
        ): TypeSection {
            val typeCount =
                readVectorSize(
                    buffer,
                    "maxTypes",
                    buffer.limits.maxTypes,
                )
            val typeSectionBuilder = TypeSection.builder()
            var parsedTypeCount = 0
            for (i in 0 until typeCount) {
                val recType = parseRecType(buffer, parsedTypeCount)
                parsedTypeCount += recType.subTypes().size
                typeSectionBuilder.addRecType(recType)
            }

            val typeSection = typeSectionBuilder.build()
            validateAndResolveTypeSection(typeSection, forwardTypeReferences)
            return typeSection
        }

        private fun validateAndResolveTypeSection(
            typeSection: TypeSection,
            forwardTypeReferences: Boolean,
        ) {
            var typeIndex = 0
            for (i in 0 until typeSection.typeCount()) {
                val rt = typeSection.getRecType(i)
                for (st in rt.subTypes()) {
                    validateAndResolveTypeReferences(
                        st,
                        typeSection,
                        typeIndex,
                        forwardTypeReferences,
                    )
                    typeIndex++
                }
            }
        }

        private fun validateAndResolveTypeReferences(
            subType: SubType,
            typeSection: TypeSection,
            typeIndex: Int,
            forwardTypeReferences: Boolean,
        ) {
            for (superTypeIndex in subType.typeIdx()) {
                validateDefinedTypeReference(
                    superTypeIndex,
                    typeSection,
                    typeIndex,
                    forwardTypeReferences,
                )
            }

            val ct = subType.compType()
            val ft = ct.funcType()
            if (ft != null) {
                for (param in ft.params()) {
                    validateAndResolveValTypeReference(
                        param,
                        typeSection,
                        typeIndex,
                        forwardTypeReferences,
                    )
                }
                for (returnType in ft.returns()) {
                    validateAndResolveValTypeReference(
                        returnType,
                        typeSection,
                        typeIndex,
                        forwardTypeReferences,
                    )
                }
            }

            val at = ct.arrayType()
            if (at != null) {
                at.fieldType().storageType().valType()?.let {
                    validateAndResolveValTypeReference(
                        it,
                        typeSection,
                        typeIndex,
                        forwardTypeReferences,
                    )
                }
            }

            val st = ct.structType()
            if (st != null) {
                for (fieldType in st.fieldTypes()) {
                    fieldType.storageType().valType()?.let {
                        validateAndResolveValTypeReference(
                            it,
                            typeSection,
                            typeIndex,
                            forwardTypeReferences,
                        )
                    }
                }
            }
        }

        private fun validateAndResolveValTypeReference(
            valType: ValType,
            typeSection: TypeSection,
            typeIndex: Int,
            forwardTypeReferences: Boolean,
        ) {
            val resolvedTypeIndex = valType.resolvedFunctionTypeId()
            if (resolvedTypeIndex >= 0) {
                validateDefinedTypeReference(
                    resolvedTypeIndex,
                    typeSection,
                    typeIndex,
                    forwardTypeReferences,
                )
            }
            valType.resolve(typeSection)
        }

        private fun validateDefinedTypeReference(
            referencedTypeIndex: Int,
            typeSection: TypeSection,
            typeIndex: Int,
            forwardTypeReferences: Boolean,
        ) {
            if (!forwardTypeReferences && referencedTypeIndex >= typeIndex) {
                throw InvalidException("unknown type $referencedTypeIndex")
            }
            typeSection.getSubType(referencedTypeIndex)
        }

        private fun parseImportSection(
            buffer: WasmByteReader,
            typeSection: TypeSection,
            threadsMemory: Boolean,
        ): ImportSection {
            val importCount =
                readVectorSize(
                    buffer,
                    "maxImports",
                    buffer.limits.maxImports,
                )
            val importSection = ImportSection.builder()
            for (i in 0 until importCount) {
                val moduleName = readName(buffer)
                val importName = readName(buffer)
                val descType =
                    try {
                        ExternalType.byId(readVarUInt32(buffer).toInt())
                    } catch (e: RuntimeException) {
                        throw MalformedException("malformed import kind", e)
                    }
                when (descType) {
                    ExternalType.FUNCTION -> {
                        if (moduleName.isEmpty() && importName.isEmpty()) {
                            throw MalformedException("malformed import kind")
                        }
                        importSection.addImport(
                            FunctionImport(moduleName, importName, readVarUInt32(buffer).toInt())
                        )
                    }
                    ExternalType.TABLE -> {
                        val rawTableType = readValueType(buffer, typeSection)
                        val limitType = readByte(buffer).toInt()
                        val min = readVarUInt32(buffer).toInt()
                        val limits =
                            when (limitType) {
                                0x00 -> TableLimits(min.toLong())
                                0x01,
                                0x03 ->
                                    TableLimits(
                                        min.toLong(),
                                        readVarUInt32(buffer),
                                        limitType == 0x03,
                                    )
                                else ->
                                    throw MalformedException(
                                        "integer too large, invalid table limit: $limitType"
                                    )
                            }
                        importSection.addImport(
                            TableImport(moduleName, importName, rawTableType, limits)
                        )
                    }
                    ExternalType.MEMORY -> {
                        importSection.addImport(
                            MemoryImport(
                                moduleName,
                                importName,
                                parseMemoryLimits(buffer, threadsMemory),
                            )
                        )
                    }
                    ExternalType.GLOBAL -> {
                        val globalValType = readValueType(buffer, typeSection)
                        val globalMut = MutabilityType.forId(readByte(buffer).toInt())
                        importSection.addImport(
                            GlobalImport(moduleName, importName, globalMut, globalValType)
                        )
                    }
                    ExternalType.TAG -> {
                        try {
                            val attribute = readByte(buffer)
                            val tagTypeIdx = readVarUInt32(buffer).toInt()
                            importSection.addImport(
                                TagImport(moduleName, importName, attribute, tagTypeIdx)
                            )
                        } catch (e: MalformedException) {
                            throw MalformedException("malformed import kind", e)
                        }
                    }
                }
            }
            return importSection.build()
        }

        private fun parseFunctionSection(buffer: WasmByteReader): FunctionSection {
            val functionCount =
                readVectorSize(
                    buffer,
                    "maxFunctions",
                    buffer.limits.maxFunctions,
                )
            val functionSection = FunctionSection.builder()
            for (i in 0 until functionCount) {
                functionSection.addFunctionType(readVarUInt32(buffer).toInt())
            }
            return functionSection.build()
        }

        private fun readTableLimits(buffer: WasmByteReader): TableLimits {
            val limitType = readByte(buffer).toInt()
            if (limitType != 0x00 && limitType != 0x01) {
                throw MalformedException("integer representation too long, integer too large")
            }
            val min = readVarUInt32(buffer)
            return if (limitType > 0) TableLimits(min, readVarUInt32(buffer)) else TableLimits(min)
        }

        private fun parseTableSection(
            buffer: WasmByteReader,
            typeSection: TypeSection,
            multiMemory: Boolean,
        ): TableSection {
            val tableCount =
                readVectorSize(
                    buffer,
                    "maxTables",
                    buffer.limits.maxTables,
                )
            val tableSection = TableSection.builder()
            for (i in 0 until tableCount) {
                val firstByte = readVarUInt32(buffer).toInt()
                if (firstByte == 0x40) {
                    val secondByte = readVarUInt32(buffer)
                    assert(secondByte == 0x00L)
                    val tableType = readValueType(buffer, typeSection)
                    val limits = readTableLimits(buffer)
                    val init = parseExpression(buffer, multiMemory)
                    tableSection.addTable(Table(tableType, limits, init.toList()))
                } else {
                    val tableType = readValueTypeFromOpCode(buffer, firstByte, typeSection)
                    val limits = readTableLimits(buffer)
                    tableSection.addTable(Table(tableType, limits))
                }
            }
            return tableSection.build()
        }

        private fun parseMemorySection(
            buffer: WasmByteReader,
            threadsMemory: Boolean,
        ): MemorySection {
            val memoryCount =
                readVectorSize(
                    buffer,
                    "maxMemories",
                    buffer.limits.maxMemories,
                )
            val memorySection = MemorySection.builder()
            for (i in 0 until memoryCount) {
                memorySection.addMemory(Memory(parseMemoryLimits(buffer, threadsMemory)))
            }
            return memorySection.build()
        }

        private fun parseMemoryLimits(
            buffer: WasmByteReader,
            threadsMemory: Boolean,
        ): MemoryLimits {
            val limitType = readByte(buffer).toInt()
            val initial = readVarUInt32(buffer).toInt()
            return when (limitType) {
                0x00 -> MemoryLimits(initial)
                0x01,
                0x03 -> {
                    if (limitType == 0x03 && !threadsMemory) {
                        throw MalformedException("integer too large, invalid memory limit: $limitType")
                    }
                    MemoryLimits(initial, readVarUInt32(buffer).toInt(), limitType == 0x03)
                }
                0x02 ->
                    if (threadsMemory) {
                        throw InvalidException("shared memory must have maximum")
                    } else {
                        throw MalformedException("integer too large, invalid memory limit: $limitType")
                    }
                else ->
                    if (limitType > 0) {
                        throw MalformedException(
                            "integer too large, invalid memory limit: $limitType"
                        )
                    } else {
                        throw MalformedException("integer representation too long: $limitType")
                    }
            }
        }

        private fun parseGlobalSection(
            buffer: WasmByteReader,
            typeSection: TypeSection,
            multiMemory: Boolean,
        ): GlobalSection {
            val globalCount =
                readVectorSize(
                    buffer,
                    "maxGlobals",
                    buffer.limits.maxGlobals,
                )
            val globalSection = GlobalSection.builder()
            for (i in 0 until globalCount) {
                val valueType = readValueType(buffer, typeSection)
                val mutabilityType = MutabilityType.forId(readByte(buffer).toInt())
                val init = parseExpression(buffer, multiMemory)
                globalSection.addGlobal(Global(valueType, mutabilityType, init.toList()))
            }
            return globalSection.build()
        }

        private fun parseExportSection(buffer: WasmByteReader): ExportSection {
            val exportCount =
                readVectorSize(
                    buffer,
                    "maxExports",
                    buffer.limits.maxExports,
                )
            val exportSection = ExportSection.builder()
            for (i in 0 until exportCount) {
                val name = readName(buffer, false)
                val exportType = ExternalType.byId(readVarUInt32(buffer).toInt())
                val index = readVarUInt32(buffer).toInt()
                exportSection.addExport(Export(name, index, exportType))
            }
            return exportSection.build()
        }

        private fun parseStartSection(buffer: WasmByteReader): StartSection =
            StartSection.builder().setStartIndex(readVarUInt32(buffer)).build()

        private fun parseElementSection(
            buffer: WasmByteReader,
            sectionSize: Long,
            typeSection: TypeSection,
            multiMemory: Boolean,
        ): ElementSection {
            val initialPosition = buffer.position()
            val elementCount =
                readVectorSize(
                    buffer,
                    "maxElementSegments",
                    buffer.limits.maxElementSegments,
                )
            val elementSection = ElementSection.builder()
            for (i in 0 until elementCount) {
                elementSection.addElement(parseSingleElement(buffer, typeSection, multiMemory))
            }
            if (buffer.position().toLong() != initialPosition.toLong() + sectionSize) {
                throw MalformedException("section size mismatch")
            }
            return elementSection.build()
        }

        private fun parseSingleElement(
            buffer: WasmByteReader,
            typeSection: TypeSection,
            multiMemory: Boolean,
        ): Element {
            val flags = readVarUInt32(buffer).toInt()
            val active = flags and 0b001 == 0
            val declarative = !active && flags and 0b010 != 0
            val passive = !active && !declarative
            val hasTableIdx = active && flags and 0b010 != 0
            val alwaysFuncRef = active && !hasTableIdx
            val exprInit = flags and 0b100 != 0
            val hasElemKind = !exprInit && !alwaysFuncRef
            val hasRefType = exprInit && !alwaysFuncRef

            var tableIdx = 0
            var offset: List<Instruction> = emptyList()
            if (active) {
                if (hasTableIdx) {
                    tableIdx = readVarUInt32(buffer).toBoundedInt()
                }
                offset = parseExpression(buffer, multiMemory).toList()
            }

            val type: ValType =
                if (alwaysFuncRef) {
                    if (exprInit) {
                        ValType.FuncRef
                    } else {
                        ValType.builder()
                            .withOpcode(ValType.ID.Ref)
                            .withTypeIdx(ValType.TypeIdxCode.FUNC.code())
                            .build()
                    }
                } else if (hasElemKind) {
                    val elemKind = readVarUInt32(buffer).toInt()
                    if (elemKind == 0x00) {
                        ValType.builder()
                            .withOpcode(ValType.ID.Ref)
                            .withTypeIdx(ValType.TypeIdxCode.FUNC.code())
                            .build()
                    } else {
                        throw WasmEngineException("Invalid element kind")
                    }
                } else {
                    assert(hasRefType)
                    val valueType = readValueType(buffer, typeSection)
                    if (!valueType.isReference()) {
                        throw MalformedException(
                            "malformed reference type: element section has non-reference type"
                        )
                    }
                    valueType
                }

            val initCnt = readVectorSize(buffer)
            val inits = ArrayList<List<Instruction>>(initCnt)
            if (exprInit) {
                for (i in 0 until initCnt) {
                    inits.add(parseExpression(buffer, multiMemory).toList())
                }
            } else {
                for (i in 0 until initCnt) {
                    inits.add(
                        listOf(
                            Instruction(-1, OpCode.REF_FUNC, longArrayOf(readVarUInt32(buffer))),
                            Instruction(-1, OpCode.END, EMPTY_OPERANDS),
                        )
                    )
                }
            }
            if (declarative) {
                return DeclarativeElement(type, inits)
            }
            if (passive) {
                return PassiveElement(type, inits)
            }
            assert(active)
            return ActiveElement(type, inits, tableIdx, offset)
        }

        private fun parseCodeSectionLocalTypes(
            buffer: WasmByteReader,
            typeSection: TypeSection,
        ): List<ValType> {
            val distinctTypesCount = readVectorSize(buffer)
            val locals = ArrayList<ValType>()
            var totalLocals = 0L
            for (i in 0 until distinctTypesCount) {
                val numberOfLocals =
                    readVectorSize(
                        buffer,
                        "maxFunctionLocals",
                        buffer.limits.maxFunctionLocals,
                        specificationReason = "too many locals",
                    )
                totalLocals += numberOfLocals
                requireWithinLimit(
                    "maxFunctionLocals",
                    buffer.limits.maxFunctionLocals.toLong(),
                    totalLocals,
                    specificationReason = "too many locals",
                )
                val type = readValueType(buffer, typeSection)
                for (j in 0 until numberOfLocals) {
                    locals.add(type)
                }
            }
            return locals
        }

        private fun parseCodeSection(
            buffer: WasmByteReader,
            typeSection: TypeSection,
            multiMemory: Boolean,
        ): CodeSection {
            val funcBodyCount =
                readVectorSize(
                    buffer,
                    "maxFunctions",
                    buffer.limits.maxFunctions,
                )
            val root = ControlTree()
            val codeSection = CodeSection.builder()
            for (i in 0 until funcBodyCount) {
                val blockScope = ArrayDeque<Instruction>()
                var depth = 0
                val functionBodySize =
                    readLimitedSize(
                        buffer,
                        "maxFunctionBytes",
                        buffer.limits.maxFunctionBytes,
                    )
                if (functionBodySize > buffer.remaining()) {
                    throw MalformedException("length out of bounds")
                }
                val funcEndPoint = buffer.position() + functionBodySize
                val locals = parseCodeSectionLocalTypes(buffer, typeSection)
                val instructions = ArrayList<AnnotatedInstruction.Builder>()
                var lastInstruction: Boolean
                var currentControlFlow: ControlTree? = null
                do {
                    requireWithinLimit(
                        "maxInstructionsPerFunction",
                        buffer.limits.maxInstructionsPerFunction.toLong(),
                        instructions.size.toLong() + 1,
                    )
                    val baseInstruction = parseInstruction(buffer, multiMemory)
                    val instruction = AnnotatedInstruction.builder().from(baseInstruction)
                    lastInstruction = buffer.position() >= funcEndPoint
                    if (instructions.isEmpty()) {
                        currentControlFlow = root.spawn(0, instruction)
                    }

                    when (baseInstruction.opcode()) {
                        OpCode.MEMORY_INIT,
                        OpCode.DATA_DROP -> codeSection.setRequiresDataCount(true)
                        else -> {}
                    }

                    when (baseInstruction.opcode()) {
                        OpCode.BLOCK,
                        OpCode.LOOP,
                        OpCode.IF,
                        OpCode.TRY_TABLE -> {
                            requireWithinLimit(
                                "maxControlDepth",
                                buffer.limits.maxControlDepth.toLong(),
                                depth.toLong() + 1,
                            )
                            depth++
                            instruction.withDepth(depth)
                            blockScope.addFirst(baseInstruction)
                            instruction.withScope(blockScope.first())
                        }
                        OpCode.END -> {
                            instruction.withDepth(depth)
                            depth--
                            instruction.withScope(
                                if (blockScope.isEmpty()) {
                                    baseInstruction
                                } else {
                                    blockScope.removeFirst()
                                }
                            )
                        }
                        else -> instruction.withDepth(depth)
                    }

                    when (baseInstruction.opcode()) {
                        OpCode.BLOCK,
                        OpCode.LOOP ->
                            currentControlFlow =
                                currentControlFlow!!.spawn(instructions.size, instruction)
                        OpCode.IF -> {
                            currentControlFlow =
                                currentControlFlow!!.spawn(instructions.size, instruction)
                            val defaultJmp = instructions.size + 1
                            currentControlFlow.addCallback { end ->
                                instruction.updateLabelFalse(end)
                            }
                            instruction.withLabelTrue(defaultJmp)
                            instruction.withLabelFalse(defaultJmp)
                        }
                        OpCode.ELSE -> {
                            currentControlFlow!!.instruction().withLabelFalse(instructions.size + 1)
                            currentControlFlow.addCallback(instruction::withLabelTrue)
                        }
                        OpCode.BR_IF,
                        OpCode.BR_ON_NULL,
                        OpCode.BR_ON_NON_NULL -> {
                            instruction.withLabelFalse(instructions.size + 1)
                            addBranchCallback(currentControlFlow, baseInstruction, instruction, 0)
                        }
                        OpCode.BR ->
                            addBranchCallback(currentControlFlow, baseInstruction, instruction, 0)
                        OpCode.BR_ON_CAST,
                        OpCode.BR_ON_CAST_FAIL -> {
                            instruction.withLabelFalse(instructions.size + 1)
                            addBranchCallback(currentControlFlow, baseInstruction, instruction, 1)
                        }
                        OpCode.BR_TABLE -> {
                            val length = baseInstruction.operandCount()
                            val labelTable = ArrayList<Int?>()
                            for (idx in 0 until length) {
                                labelTable.add(null)
                                var offset = baseInstruction.operand(idx).toInt()
                                var reference = currentControlFlow
                                while (offset > 0) {
                                    if (reference == null) {
                                        throw InvalidException("unknown label")
                                    }
                                    reference = reference.parent()
                                    offset--
                                }
                                val finalIdx = idx
                                reference!!.addCallback { end -> labelTable[finalIdx] = end }
                            }
                            @Suppress("UNCHECKED_CAST")
                            instruction.withLabelTable(labelTable as List<Int>)
                        }
                        OpCode.TRY_TABLE -> {
                            val catches = CatchOpCode.decode(baseInstruction.operands())
                            val allLabels = CatchOpCode.allLabels(baseInstruction.operands())
                            for (idx in allLabels.indices) {
                                var offset = allLabels[idx]
                                var reference = currentControlFlow
                                while (offset > 0) {
                                    if (reference == null) {
                                        throw InvalidException("unknown label")
                                    }
                                    reference = reference.parent()
                                    offset--
                                }
                                val finalIdx = idx
                                reference!!.addCallback { end ->
                                    catches[finalIdx].resolvedLabel(end)
                                }
                            }
                            instruction.withCatches(catches)
                            currentControlFlow =
                                currentControlFlow!!.spawn(instructions.size, instruction)
                        }
                        OpCode.END -> {
                            currentControlFlow!!.setFinalInstructionNumber(
                                instructions.size,
                                instruction,
                            )
                            currentControlFlow = currentControlFlow.parent()
                            if (lastInstruction && instructions.size > 1) {
                                val former = instructions[instructions.size - 1]
                                if (former.opcode() == OpCode.END) {
                                    instruction.withScope(former.scope()!!)
                                }
                            }
                        }
                        else -> {}
                    }
                    if (lastInstruction && instruction.opcode() != OpCode.END) {
                        throw MalformedException("END opcode expected, section size mismatch")
                    }
                    instructions.add(instruction)
                } while (!lastInstruction)

                if (depth > 0) {
                    throw MalformedException("unexpected end")
                }

                val functionBody =
                    FunctionBody(
                        locals,
                        instructions.map { it.build() },
                    )
                codeSection.addFunctionBody(functionBody)
            }
            return codeSection.build()
        }

        private fun addBranchCallback(
            currentControlFlow: ControlTree?,
            baseInstruction: Instruction,
            instruction: AnnotatedInstruction.Builder,
            operandIndex: Int,
        ) {
            var offset = baseInstruction.operand(operandIndex).toInt()
            var reference = currentControlFlow
            while (offset > 0) {
                if (reference == null) {
                    throw InvalidException("unknown label")
                }
                reference = reference.parent()
                offset--
            }
            reference!!.addCallback(instruction::withLabelTrue)
        }

        private fun parseDataSection(buffer: WasmByteReader, multiMemory: Boolean): DataSection {
            val dataSegmentCount =
                readVectorSize(
                    buffer,
                    "maxDataSegments",
                    buffer.limits.maxDataSegments,
                )
            val dataSection = DataSection.builder()
            for (i in 0 until dataSegmentCount) {
                when (val mode = readVarUInt32(buffer)) {
                    0L -> {
                        val offset = parseExpression(buffer, multiMemory)
                        val data = readDataBytes(buffer)
                        dataSection.addDataSegment(ActiveDataSegment(0, offset.toList(), data))
                    }
                    1L -> {
                        val data = readDataBytes(buffer)
                        dataSection.addDataSegment(PassiveDataSegment(data))
                    }
                    2L -> {
                        val memoryId = readVarUInt32(buffer)
                        val offset = parseExpression(buffer, multiMemory)
                        val data = readDataBytes(buffer)
                        dataSection.addDataSegment(
                            ActiveDataSegment(memoryId, offset.toList(), data)
                        )
                    }
                    else ->
                        throw WasmEngineException(
                            "Failed to parse data segment with data mode: $mode"
                        )
                }
            }
            return dataSection.build()
        }

        private fun readDataBytes(buffer: WasmByteReader): ByteArray {
            val size =
                readLimitedSize(
                    buffer,
                    "maxSectionBytes",
                    buffer.limits.maxSectionBytes,
                )
            if (size > buffer.remaining()) {
                throw MalformedException("length out of bounds")
            }
            return ByteArray(size).also { readBytes(buffer, it) }
        }

        private fun parseDataCountSection(buffer: WasmByteReader): DataCountSection =
            DataCountSection.builder()
                .withDataCount(
                    readLimitedSize(
                        buffer,
                        "maxDataSegments",
                        buffer.limits.maxDataSegments,
                    )
                )
                .build()

        private fun parseTagSection(buffer: WasmByteReader): TagSection {
            val tagsCount =
                readVectorSize(
                    buffer,
                    "maxTags",
                    buffer.limits.maxTags,
                )
            val tagSection = TagSection.builder()
            for (i in 0 until tagsCount) {
                val attribute = readByte(buffer)
                val typeIdx = readVarUInt32(buffer).toInt()
                tagSection.addTagType(TagType(attribute, typeIdx))
            }
            return tagSection.build()
        }

        private fun parseInstruction(buffer: WasmByteReader, multiMemory: Boolean): Instruction {
            val address = buffer.position()
            var value = readByte(buffer).toInt() and 0xff
            if (value >= 0xfb && value < 0xff) {
                value = (value shl 8) + readVarUInt32(buffer).toInt()
            }
            val op =
                OpCode.byOpCode(value)
                    ?: throw MalformedException(
                        "illegal opcode, op value " +
                            value.toHexByteString().removePrefix("0x") +
                            " "
                    )
            val signature = OpCode.signature(op)

            when (op) {
                OpCode.DROP,
                OpCode.SELECT -> return Instruction(address, op, longArrayOf(0))
                else -> {}
            }

            if (signature.isEmpty()) {
                return Instruction(address, op, EMPTY_OPERANDS)
            }

            if (!multiMemory && (op == OpCode.MEMORY_SIZE || op == OpCode.MEMORY_GROW)) {
                val reserved = readByte(buffer).toInt() and 0xFF
                if (reserved != 0) {
                    throw MalformedException("zero byte expected")
                }
                return Instruction(address, op, longArrayOf(0))
            }

            val operands = ArrayList<Long>()
            for (sig in signature) {
                when (sig) {
                    uk.shusek.krwa.wasm.types.WasmEncoding.BYTE ->
                        operands.add(readByte(buffer).toLong() and 0xFFL)
                    uk.shusek.krwa.wasm.types.WasmEncoding.VARUINT ->
                        operands.add(readVarUInt32(buffer))
                    uk.shusek.krwa.wasm.types.WasmEncoding.VARSINT32 ->
                        operands.add(readVarSInt32(buffer))
                    uk.shusek.krwa.wasm.types.WasmEncoding.VARSINT64 ->
                        operands.add(readVarSInt64(buffer))
                    uk.shusek.krwa.wasm.types.WasmEncoding.FLOAT64 -> operands.add(readFloat64(buffer))
                    uk.shusek.krwa.wasm.types.WasmEncoding.FLOAT32 -> operands.add(readFloat32(buffer))
                    uk.shusek.krwa.wasm.types.WasmEncoding.VEC_VARUINT -> {
                        val vcount = readVectorSize(buffer)
                        for (j in 0 until vcount) {
                            operands.add(readVarUInt32(buffer))
                        }
                    }
                    uk.shusek.krwa.wasm.types.WasmEncoding.VEC_CATCH -> {
                        val n = readVectorSize(buffer)
                        operands.add(n.toLong())
                        for (j in 0 until n) {
                            val catchOp = readByte(buffer)
                            operands.add(0L or catchOp.toLong())
                            when (CatchOpCode.byOpCode(catchOp.toInt())) {
                                CatchOpCode.CATCH,
                                CatchOpCode.CATCH_REF -> {
                                    operands.add(readVarUInt32(buffer))
                                    operands.add(readVarUInt32(buffer))
                                }
                                CatchOpCode.CATCH_ALL,
                                CatchOpCode.CATCH_ALL_REF -> operands.add(readVarUInt32(buffer))
                            }
                        }
                    }
                    uk.shusek.krwa.wasm.types.WasmEncoding.V128 -> {
                        val bytes = ByteArray(16)
                        for (j in 0 until 16) {
                            bytes[j] = readByte(buffer)
                        }
                        for (parsedValue in Value.bytesToVec(bytes)) {
                            operands.add(parsedValue)
                        }
                    }
                    uk.shusek.krwa.wasm.types.WasmEncoding.BLOCK_TYPE -> {
                        val operand = readVarUInt32(buffer).toInt()
                        if (ValType.ID.isValidOpcode(operand)) {
                            val valueType = readValueTypeBuilderFromOpCode(buffer, operand)
                            operands.add(valueType.id())
                        } else {
                            operands.add(operand.toLong())
                        }
                    }
                    uk.shusek.krwa.wasm.types.WasmEncoding.VEC_VALUE_TYPE -> {
                        val vcount = readVectorSize(buffer)
                        for (j in 0 until vcount) {
                            operands.add(readValueTypeBuilder(buffer).id())
                        }
                    }
                    uk.shusek.krwa.wasm.types.WasmEncoding.MEMARG -> {
                        val flags = readVarUInt32(buffer)
                        if (!multiMemory && flags > 31L) {
                            throw MalformedException("malformed memop flags")
                        }
                        val align = flags and 0x3F
                        var memidx = 0L
                        if ((flags shr 6) != 0L) {
                            memidx = readVarUInt32(buffer)
                        }
                        val offset = readVarUInt32(buffer)
                        operands.add(align)
                        operands.add(offset)
                        operands.add(memidx)
                    }
                }
            }

            var operandsArray = LongArray(operands.size)
            for (i in operands.indices) {
                operandsArray[i] = operands[i]
            }
            when (op) {
                OpCode.REF_TEST,
                OpCode.REF_TEST_NULL,
                OpCode.CAST_TEST,
                OpCode.CAST_TEST_NULL,
                OpCode.BR_ON_CAST,
                OpCode.BR_ON_CAST_FAIL ->
                    operandsArray = operandsArray.copyOf(operandsArray.size + 1)
                else -> {}
            }
            verifyAlignment(op, operandsArray)
            return Instruction(address, op, operandsArray)
        }

        private fun verifyAlignment(op: OpCode, operands: LongArray) {
            var align = -1
            when (op) {
                OpCode.I32_LOAD8_U,
                OpCode.I32_LOAD8_S,
                OpCode.I64_LOAD8_U,
                OpCode.I64_LOAD8_S,
                OpCode.I32_STORE8,
                OpCode.I64_STORE8,
                OpCode.V128_LOAD8_SPLAT,
                OpCode.V128_STORE8_LANE,
                OpCode.V128_LOAD8_LANE -> align = 8
                OpCode.I32_LOAD16_U,
                OpCode.I32_LOAD16_S,
                OpCode.I64_LOAD16_U,
                OpCode.I64_LOAD16_S,
                OpCode.I32_STORE16,
                OpCode.I64_STORE16,
                OpCode.V128_LOAD16_SPLAT,
                OpCode.V128_STORE16_LANE,
                OpCode.V128_LOAD16_LANE -> align = 16
                OpCode.I32_LOAD,
                OpCode.F32_LOAD,
                OpCode.I64_LOAD32_U,
                OpCode.I64_LOAD32_S,
                OpCode.I64_STORE32,
                OpCode.I32_STORE,
                OpCode.F32_STORE,
                OpCode.V128_LOAD32_SPLAT,
                OpCode.V128_STORE32_LANE,
                OpCode.V128_LOAD32_LANE -> align = 32
                OpCode.I64_LOAD,
                OpCode.F64_LOAD,
                OpCode.I64_STORE,
                OpCode.F64_STORE,
                OpCode.V128_LOAD8x8_S,
                OpCode.V128_LOAD8x8_U,
                OpCode.V128_LOAD16x4_S,
                OpCode.V128_LOAD16x4_U,
                OpCode.V128_LOAD32x2_S,
                OpCode.V128_LOAD32x2_U,
                OpCode.V128_LOAD64_SPLAT,
                OpCode.V128_STORE64_LANE,
                OpCode.V128_LOAD64_LANE -> align = 64
                OpCode.V128_LOAD,
                OpCode.V128_STORE -> align = 128
                else -> {}
            }
            if (align > 0) {
                val operand0 = operands[0].toInt()
                val maxAlignExp = (align shr 3).countTrailingZeroBits()
                if (operand0 > maxAlignExp) {
                    throw InvalidException(
                        "alignment must not be larger than natural alignment ($operand0)"
                    )
                }
            }
        }

        private fun readValueTypeBuilderFromOpCode(
            buffer: WasmByteReader,
            valueTypeOpCode: Int,
        ): ValType.Builder {
            val builder = ValType.builder().withOpcode(valueTypeOpCode)
            return if (valueTypeOpCode == ValType.ID.Ref || valueTypeOpCode == ValType.ID.RefNull) {
                builder.withTypeIdx(readVarSInt32(buffer).toInt())
            } else {
                builder
            }
        }

        private fun readValueTypeBuilder(buffer: WasmByteReader): ValType.Builder =
            readValueTypeBuilderFromOpCode(buffer, readVarUInt32(buffer).toInt())

        private fun readValueTypeFromOpCode(
            buffer: WasmByteReader,
            valueTypeOpCode: Int,
            typeSection: TypeSection,
        ): ValType =
            readValueTypeBuilderFromOpCode(buffer, valueTypeOpCode).build().resolve(typeSection)

        private fun readValueType(buffer: WasmByteReader, typeSection: TypeSection): ValType =
            readValueTypeBuilder(buffer).build().resolve(typeSection)

        private fun parseExpression(
            buffer: WasmByteReader,
            multiMemory: Boolean,
        ): Array<Instruction> {
            val expr = ArrayList<Instruction>()
            var instructionCount = 0
            while (buffer.hasRemaining()) {
                requireWithinLimit(
                    "maxInstructionsPerFunction",
                    buffer.limits.maxInstructionsPerFunction.toLong(),
                    instructionCount.toLong() + 1,
                )
                val instruction = parseInstruction(buffer, multiMemory)
                instructionCount++
                if (instruction.opcode() == OpCode.END) {
                    return expr.toTypedArray()
                }
                expr.add(instruction)
            }
            throw MalformedException("illegal opcode: expected end opcode")
        }
    }
}

private fun Long.toBoundedInt(): Int {
    if (this < 0 || this > Int.MAX_VALUE) {
        throw MalformedException("length out of bounds")
    }
    return toInt()
}

private fun Int.toHexByteString(): String = "0x" + toString(16).uppercase().padStart(2, '0')
