package uk.shusek.krwa.wasm

import uk.shusek.krwa.wasm.types.CodeSection
import uk.shusek.krwa.wasm.types.CustomSection
import uk.shusek.krwa.wasm.types.DataCountSection
import uk.shusek.krwa.wasm.types.DataSection
import uk.shusek.krwa.wasm.types.ElementSection
import uk.shusek.krwa.wasm.types.ExportSection
import uk.shusek.krwa.wasm.types.FunctionSection
import uk.shusek.krwa.wasm.types.GlobalSection
import uk.shusek.krwa.wasm.types.ImportSection
import uk.shusek.krwa.wasm.types.MemorySection
import uk.shusek.krwa.wasm.types.NameCustomSection
import uk.shusek.krwa.wasm.types.StartSection
import uk.shusek.krwa.wasm.types.TableSection
import uk.shusek.krwa.wasm.types.TagSection
import uk.shusek.krwa.wasm.types.TypeSection

class WasmModule
private constructor(
    private val typeSection: TypeSection,
    private val importSection: ImportSection,
    private val functionSection: FunctionSection,
    private val tableSection: TableSection,
    private val memorySection: MemorySection?,
    private val globalSection: GlobalSection,
    private val exportSection: ExportSection,
    private val startSection: StartSection?,
    private val elementSection: ElementSection,
    private val codeSection: CodeSection,
    private val dataSection: DataSection,
    private val dataCountSection: DataCountSection?,
    private val tagSection: TagSection?,
    customSections: Map<String, CustomSection>,
    ignoredSections: List<Int>,
    private val digest: String?,
    originalBytes: ByteArray?,
) {
    private val customSections: Map<String, CustomSection> = customSections.toMap()
    private val ignoredSections: List<Int> = ignoredSections.toList()
    private val originalBytes: ByteArray? = originalBytes?.copyOf()

    fun typeSection(): TypeSection = typeSection

    fun functionSection(): FunctionSection = functionSection

    fun exportSection(): ExportSection = exportSection

    fun startSection(): StartSection? = startSection

    fun importSection(): ImportSection = importSection

    fun codeSection(): CodeSection = codeSection

    fun dataSection(): DataSection = dataSection

    fun dataCountSection(): DataCountSection? = dataCountSection

    fun memorySection(): MemorySection? = memorySection

    fun globalSection(): GlobalSection = globalSection

    fun tableSection(): TableSection = tableSection

    fun digest(): String? = digest

    /**
     * Original module bytes when this module was parsed from a complete binary input.
     *
     * Native engines, such as the browser WebAssembly engine, need the binary representation rather
     * than the decoded KRWA model.
     */
    fun originalBytes(): ByteArray? = originalBytes?.copyOf()

    fun customSections(): List<CustomSection> = customSections.values.toList()

    fun customSection(name: String): CustomSection? = customSections[name]

    fun nameSection(): NameCustomSection? = customSections["name"] as NameCustomSection?

    fun elementSection(): ElementSection = elementSection

    fun tagSection(): TagSection? = tagSection

    fun ignoredSections(): List<Int> = ignoredSections

    class Builder private constructor() {
        private var typeSection = TypeSection.builder().build()
        private var importSection = ImportSection.builder().build()
        private var functionSection = FunctionSection.builder().build()
        private var tableSection = TableSection.builder().build()
        private var memorySection: MemorySection? = null
        private var globalSection = GlobalSection.builder().build()
        private var exportSection = ExportSection.builder().build()
        private var startSection: StartSection? = null
        private var elementSection = ElementSection.builder().build()
        private var codeSection = CodeSection.builder().build()
        private var dataSection = DataSection.builder().build()
        private var dataCountSection: DataCountSection? = null
        private var tagSection: TagSection? = null
        private val customSections: MutableMap<String, CustomSection> = mutableMapOf()
        private val ignoredSections: MutableList<Int> = mutableListOf()
        private var validate = true
        private var multiTable = true
        private var multiMemory = true
        private var localGlobalReferencesInConstantExpressions = true
        private var digest: String? = null
        private var originalBytes: ByteArray? = null

        fun setTypeSection(ts: TypeSection): Builder {
            typeSection = ts
            return this
        }

        fun setImportSection(importSection: ImportSection): Builder {
            this.importSection = importSection
            return this
        }

        fun setFunctionSection(fs: FunctionSection): Builder {
            functionSection = fs
            return this
        }

        fun setTableSection(ts: TableSection): Builder {
            tableSection = ts
            return this
        }

        fun setMemorySection(ms: MemorySection?): Builder {
            memorySection = ms
            return this
        }

        fun setGlobalSection(gs: GlobalSection): Builder {
            globalSection = gs
            return this
        }

        fun setExportSection(es: ExportSection): Builder {
            exportSection = es
            return this
        }

        fun setStartSection(ss: StartSection?): Builder {
            startSection = ss
            return this
        }

        fun setElementSection(es: ElementSection): Builder {
            elementSection = es
            return this
        }

        fun setCodeSection(cs: CodeSection): Builder {
            codeSection = cs
            return this
        }

        fun setDataSection(ds: DataSection): Builder {
            dataSection = ds
            return this
        }

        fun setDataCountSection(dcs: DataCountSection?): Builder {
            dataCountSection = dcs
            return this
        }

        fun setTagSection(ts: TagSection?): Builder {
            tagSection = ts
            return this
        }

        fun addCustomSection(name: String, cs: CustomSection): Builder {
            customSections[name] = cs
            return this
        }

        fun addIgnoredSection(id: Int): Builder {
            ignoredSections.add(id)
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

        fun withLocalGlobalReferencesInConstantExpressions(allow: Boolean): Builder {
            this.localGlobalReferencesInConstantExpressions = allow
            return this
        }

        fun withDigest(digest: String?): Builder {
            this.digest = digest
            return this
        }

        fun withOriginalBytes(bytes: ByteArray?): Builder {
            this.originalBytes = bytes?.copyOf()
            return this
        }

        fun build(): WasmModule {
            val module =
                WasmModule(
                    typeSection,
                    importSection,
                    functionSection,
                    tableSection,
                    memorySection,
                    globalSection,
                    exportSection,
                    startSection,
                    elementSection,
                    codeSection,
                    dataSection,
                    dataCountSection,
                    tagSection,
                    customSections,
                    ignoredSections,
                    digest,
                    originalBytes,
                )

            val validator =
                Validator(
                    module,
                    multiTable,
                    multiMemory,
                    localGlobalReferencesInConstantExpressions,
                )
            validator.validateModule()
            if (validate) {
                validator.validateTypes()
                validator.validateFunctions()
                validator.validateGlobals()
                validator.validateElements()
                validator.validateData()
                validator.validateTags()
                validator.validateTables()
            }

            return module
        }

        companion object {
            fun create(): Builder = Builder()
        }
    }

    // Comparison uses everything but the custom section.
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is WasmModule) {
            return false
        }
        return typeSection == other.typeSection &&
            importSection == other.importSection &&
            functionSection == other.functionSection &&
            tableSection == other.tableSection &&
            memorySection == other.memorySection &&
            globalSection == other.globalSection &&
            exportSection == other.exportSection &&
            startSection == other.startSection &&
            elementSection == other.elementSection &&
            codeSection == other.codeSection &&
            dataSection == other.dataSection &&
            dataCountSection == other.dataCountSection &&
            tagSection == other.tagSection &&
            ignoredSections == other.ignoredSections
    }

    override fun hashCode(): Int {
        var result = typeSection.hashCode()
        result = 31 * result + importSection.hashCode()
        result = 31 * result + functionSection.hashCode()
        result = 31 * result + tableSection.hashCode()
        result = 31 * result + (memorySection?.hashCode() ?: 0)
        result = 31 * result + globalSection.hashCode()
        result = 31 * result + exportSection.hashCode()
        result = 31 * result + (startSection?.hashCode() ?: 0)
        result = 31 * result + elementSection.hashCode()
        result = 31 * result + codeSection.hashCode()
        result = 31 * result + dataSection.hashCode()
        result = 31 * result + (dataCountSection?.hashCode() ?: 0)
        result = 31 * result + (tagSection?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun builder(): Builder = Builder.create()
    }
}
