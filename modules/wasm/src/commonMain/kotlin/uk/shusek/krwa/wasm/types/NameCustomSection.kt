package uk.shusek.krwa.wasm.types

import uk.shusek.krwa.wasm.WasmByteReader
import uk.shusek.krwa.wasm.readName
import uk.shusek.krwa.wasm.readVarUInt32

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

    private class NameEntry(private val index: Int, val name: String) {
        fun index(): Int = index

        fun name(): String = name

        override fun toString(): String = "[$index] -> $name"
    }

    private class ListEntry<T>(private val index: Int) : AbstractList<T>() {
        private val values = ArrayList<T>()

        override val size: Int
            get() = values.size

        fun index(): Int = index

        override fun get(index: Int): T = values[index]

        fun add(index: Int, value: T) {
            values.add(index, value)
        }

        fun set(index: Int, value: T): T = values.set(index, value)

        override fun toString(): String = "[$index] -> $values"
    }

    companion object {
        fun parse(bytes: ByteArray): NameCustomSection {
            var moduleName: String? = null
            val funcNames = ArrayList<NameEntry>()
            val localNames = ArrayList<ListEntry<NameEntry>>()
            val labelNames = ArrayList<ListEntry<NameEntry>>()
            val tableNames = ArrayList<NameEntry>()
            val memoryNames = ArrayList<NameEntry>()
            val globalNames = ArrayList<NameEntry>()
            val elementNames = ArrayList<NameEntry>()
            val dataNames = ArrayList<NameEntry>()
            val tagNames = ArrayList<NameEntry>()
            val reader = WasmByteReader(bytes)

            while (reader.hasRemaining()) {
                val id = reader.readByte().toInt() and 0xFF
                val slice = reader.slice(readVarUInt32(reader).toInt())
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
                funcNames,
                localNames,
                labelNames,
                tableNames,
                memoryNames,
                globalNames,
                elementNames,
                dataNames,
                tagNames,
            )
        }

        private fun oneLevelParse(slice: WasmByteReader, list: MutableList<NameEntry>) {
            val cnt = readVarUInt32(slice).toInt()
            for (i in 0 until cnt) {
                oneLevelStore(list, readVarUInt32(slice).toInt(), readName(slice))
            }
        }

        private fun twoLevelParse(slice: WasmByteReader, list: MutableList<ListEntry<NameEntry>>) {
            val listCnt = readVarUInt32(slice).toInt()
            for (i in 0 until listCnt) {
                val groupIdx = readVarUInt32(slice).toInt()
                val cnt = readVarUInt32(slice).toInt()
                for (j in 0 until cnt) {
                    twoLevelStore(
                        list,
                        groupIdx,
                        readVarUInt32(slice).toInt(),
                        readName(slice),
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

        private fun oneLevelStore(
            list: MutableList<NameEntry>,
            storeIdx: Int,
            name: String,
        ): String? {
            val idx = binarySearch(list, storeIdx) { it.index() }
            if (idx < 0) {
                list.add(-idx - 1, NameEntry(storeIdx, name))
                return null
            }
            return list.set(idx, NameEntry(storeIdx, name)).name()
        }

        private fun twoLevelStore(
            listList: MutableList<ListEntry<NameEntry>>,
            groupIdx: Int,
            subIdx: Int,
            name: String,
        ): String? {
            val fi = binarySearch(listList, groupIdx) { it.index() }
            val subList: ListEntry<NameEntry>
            if (fi < 0) {
                subList = ListEntry(groupIdx)
                listList.add(-fi - 1, subList)
            } else {
                subList = listList[fi]
            }
            val li = binarySearch(subList, subIdx) { it.index() }
            if (li < 0) {
                subList.add(-li - 1, NameEntry(subIdx, name))
                return null
            }
            return subList.set(li, NameEntry(subIdx, name)).name()
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
