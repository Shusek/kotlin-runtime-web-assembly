package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.WasmByteReader
import uk.shusek.krwa.wasm.WasmParserLimits
import uk.shusek.krwa.wasm.readLimitedSize
import uk.shusek.krwa.wasm.readName
import uk.shusek.krwa.wasm.readVarUInt32
import uk.shusek.krwa.wasm.readVectorSize

/** The "name" custom section. */
class NameCustomSection
private constructor(
    moduleName: String?,
    funcNames: List<NameEntry>,
    localNames: List<ListEntry<NameEntry>>,
    labelNames: List<ListEntry<NameEntry>>,
    tableNames: List<NameEntry>,
    memoryNames: List<NameEntry>,
    globalNames: List<NameEntry>,
    elementNames: List<NameEntry>,
    dataNames: List<NameEntry>,
    tagNames: List<NameEntry>,
) : CustomSection() {
    private val moduleName = moduleName
    private val funcNames = funcNames.toList()
    private val localNames = localNames.toList()
    private val labelNames = labelNames.toList()
    private val tableNames = tableNames.toList()
    private val memoryNames = memoryNames.toList()
    private val globalNames = globalNames.toList()
    private val elementNames = elementNames.toList()
    private val dataNames = dataNames.toList()
    private val tagNames = tagNames.toList()

    override fun name(): String = "name"

    fun moduleName(): String? = moduleName

    fun nameOfFunction(functionIdx: Int): String? = oneLevelSearch(funcNames, functionIdx)

    fun functionNameCount(): Int = funcNames.size

    fun nameOfLocal(functionIdx: Int, localIdx: Int): String? =
        twoLevelSearch(localNames, functionIdx, localIdx)

    fun nameOfLabel(functionIdx: Int, labelIdx: Int): String? =
        twoLevelSearch(labelNames, functionIdx, labelIdx)

    fun nameOfTable(tableIdx: Int): String? = oneLevelSearch(tableNames, tableIdx)

    fun nameOfMemory(memoryIdx: Int): String? = oneLevelSearch(memoryNames, memoryIdx)

    fun nameOfGlobal(globalIdx: Int): String? = oneLevelSearch(globalNames, globalIdx)

    fun nameOfElement(elementIdx: Int): String? = oneLevelSearch(elementNames, elementIdx)

    fun nameOfData(dataIdx: Int): String? = oneLevelSearch(dataNames, dataIdx)

    fun nameOfTag(tagIdx: Int): String? = oneLevelSearch(tagNames, tagIdx)

    private class NameEntry(
        private val index: Int,
        val name: String,
        private val encounterOrder: Int,
        private val groupIndex: Int,
    ) {
        fun index(): Int = index

        fun name(): String = name

        fun encounterOrder(): Int = encounterOrder

        fun groupIndex(): Int = groupIndex

        override fun toString(): String = "[$index] -> $name"
    }

    private class ListEntry<T>(
        private val index: Int,
        initialCapacity: Int,
    ) : AbstractList<T>() {
        private val values = ArrayList<T>(initialCapacity)

        override val size: Int
            get() = values.size

        fun index(): Int = index

        override fun get(index: Int): T = values[index]

        fun add(value: T) {
            values.add(value)
        }

        override fun toString(): String = "[$index] -> $values"
    }

    companion object {
        fun parse(bytes: ByteArray): NameCustomSection = parse(bytes, WasmParserLimits())

        internal fun parse(
            bytes: ByteArray,
            limits: WasmParserLimits,
        ): NameCustomSection {
            var moduleName: String? = null
            val funcNames = ArrayList<NameEntry>()
            val localNames = ArrayList<NameEntry>()
            val labelNames = ArrayList<NameEntry>()
            val tableNames = ArrayList<NameEntry>()
            val memoryNames = ArrayList<NameEntry>()
            val globalNames = ArrayList<NameEntry>()
            val elementNames = ArrayList<NameEntry>()
            val dataNames = ArrayList<NameEntry>()
            val tagNames = ArrayList<NameEntry>()
            val reader = WasmByteReader(bytes, limits)

            while (reader.hasRemaining()) {
                val id = reader.readByte().toInt() and 0xFF
                val slice =
                    reader.slice(
                        readLimitedSize(
                            reader,
                            "maxCustomSectionBytes",
                            limits.maxCustomSectionBytes,
                        )
                    )
                when (id) {
                    0 -> {
                        assert(moduleName == null)
                        moduleName = readName(slice)
                    }
                    1 -> oneLevelParse(slice, funcNames)
                    2 -> twoLevelParse(slice, localNames)
                    3 -> twoLevelParse(slice, labelNames)
                    5 -> oneLevelParse(slice, tableNames)
                    6 -> oneLevelParse(slice, memoryNames)
                    7 -> oneLevelParse(slice, globalNames)
                    8 -> oneLevelParse(slice, elementNames)
                    9 -> oneLevelParse(slice, dataNames)
                    11 -> oneLevelParse(slice, tagNames)
                    else -> {
                        // Ignore unknown subsection for forwards-compatibility.
                    }
                }
            }

            return NameCustomSection(
                moduleName,
                normalizeOneLevel(funcNames),
                normalizeTwoLevel(localNames),
                normalizeTwoLevel(labelNames),
                normalizeOneLevel(tableNames),
                normalizeOneLevel(memoryNames),
                normalizeOneLevel(globalNames),
                normalizeOneLevel(elementNames),
                normalizeOneLevel(dataNames),
                normalizeOneLevel(tagNames),
            )
        }

        private fun oneLevelParse(slice: WasmByteReader, list: MutableList<NameEntry>) {
            val cnt = readVectorSize(slice)
            for (i in 0 until cnt) {
                list.add(
                    NameEntry(
                        readVarUInt32(slice).toInt(),
                        readName(slice),
                        list.size,
                        0,
                    )
                )
            }
        }

        private fun twoLevelParse(slice: WasmByteReader, list: MutableList<NameEntry>) {
            val listCnt = readVectorSize(slice)
            for (i in 0 until listCnt) {
                val groupIdx = readVarUInt32(slice).toInt()
                val cnt = readVectorSize(slice)
                for (j in 0 until cnt) {
                    list.add(
                        NameEntry(
                            readVarUInt32(slice).toInt(),
                            readName(slice),
                            list.size,
                            groupIdx,
                        )
                    )
                }
            }
        }

        private fun oneLevelSearch(list: List<NameEntry>, searchIdx: Int): String? {
            val idx = binarySearch(list, searchIdx) { it.index() }
            return if (idx < 0) null else list[idx].name()
        }

        private fun twoLevelSearch(
            listList: List<ListEntry<NameEntry>>,
            groupIdx: Int,
            subIdx: Int,
        ): String? {
            val fi = binarySearch(listList, groupIdx) { it.index() }
            if (fi < 0) {
                return null
            }
            val subList = listList[fi]
            val li = binarySearch(subList, subIdx) { it.index() }
            return if (li < 0) null else subList[li].name
        }

        private fun normalizeOneLevel(list: MutableList<NameEntry>): List<NameEntry> {
            // Appending first avoids quadratic ArrayList shifts for reverse-ordered indexes.
            // Encounter order is the tie-breaker so the last duplicate still wins.
            list.sortWith { left, right ->
                val indexComparison = left.index().compareTo(right.index())
                if (indexComparison != 0) {
                    indexComparison
                } else {
                    left.encounterOrder().compareTo(right.encounterOrder())
                }
            }

            var readPosition = 0
            var writePosition = 0
            while (readPosition < list.size) {
                val index = list[readPosition].index()
                var lastDuplicate = readPosition
                while (
                    lastDuplicate + 1 < list.size &&
                        list[lastDuplicate + 1].index() == index
                ) {
                    lastDuplicate++
                }
                list[writePosition++] = list[lastDuplicate]
                readPosition = lastDuplicate + 1
            }

            while (list.size > writePosition) {
                list.removeAt(list.lastIndex)
            }
            return list
        }

        private fun normalizeTwoLevel(
            list: MutableList<NameEntry>,
        ): List<ListEntry<NameEntry>> {
            list.sortWith { left, right ->
                val groupComparison = left.groupIndex().compareTo(right.groupIndex())
                if (groupComparison != 0) {
                    groupComparison
                } else {
                    val indexComparison = left.index().compareTo(right.index())
                    if (indexComparison != 0) {
                        indexComparison
                    } else {
                        left.encounterOrder().compareTo(right.encounterOrder())
                    }
                }
            }

            val normalized = ArrayList<ListEntry<NameEntry>>()
            var position = 0
            while (position < list.size) {
                val groupIndex = list[position].groupIndex()
                var groupEnd = position + 1
                while (groupEnd < list.size && list[groupEnd].groupIndex() == groupIndex) {
                    groupEnd++
                }
                val group = ListEntry<NameEntry>(groupIndex, groupEnd - position)
                while (position < groupEnd) {
                    val entryIndex = list[position].index()
                    var lastDuplicate = position
                    while (
                        lastDuplicate + 1 < groupEnd &&
                            list[lastDuplicate + 1].index() == entryIndex
                    ) {
                        lastDuplicate++
                    }
                    group.add(list[lastDuplicate])
                    position = lastDuplicate + 1
                }
                normalized.add(group)
            }
            return normalized
        }

        private fun <T> binarySearch(list: List<T>, idx: Int, indexExtractor: (T) -> Int): Int {
            var low = 0
            var high = list.size - 1

            while (low <= high) {
                val mid = low + high ushr 1
                val cmp = indexExtractor(list[mid]).compareTo(idx)

                if (cmp < 0) {
                    low = mid + 1
                } else if (cmp > 0) {
                    high = mid - 1
                } else {
                    return mid
                }
            }
            return -low - 1
        }
    }
}
