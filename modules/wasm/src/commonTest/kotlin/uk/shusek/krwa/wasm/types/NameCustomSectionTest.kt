package uk.shusek.krwa.wasm.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameCustomSectionTest {
    @Test
    fun keepsSortedLookupAndLastWinsSemanticsForReversedAndDuplicateEntries() {
        val section =
            NameCustomSection.parse(
                subsections(
                    subsection(
                        FUNCTION_NAMES,
                        nameMap(
                            listOf(
                                9 to "function-nine-old",
                                5 to "function-five",
                                1 to "function-one",
                            )
                        ),
                    ),
                    subsection(
                        LOCAL_NAMES,
                        indirectNameMap(
                            listOf(
                                9 to
                                    listOf(
                                        7 to "local-nine-seven-old",
                                        3 to "local-nine-three",
                                    ),
                                2 to
                                    listOf(
                                        8 to "local-two-eight",
                                        4 to "local-two-four-old",
                                    ),
                                9 to
                                    listOf(
                                        7 to "local-nine-seven-new",
                                        0 to "local-nine-zero",
                                    ),
                                2 to listOf(4 to "local-two-four-new"),
                            )
                        ),
                    ),
                    subsection(
                        FUNCTION_NAMES,
                        nameMap(listOf(9 to "function-nine-new")),
                    ),
                )
            )

        assertEquals(3, section.functionNameCount())
        assertEquals("function-one", section.nameOfFunction(1))
        assertEquals("function-five", section.nameOfFunction(5))
        assertEquals("function-nine-new", section.nameOfFunction(9))
        assertNull(section.nameOfFunction(8))

        assertEquals("local-two-four-new", section.nameOfLocal(2, 4))
        assertEquals("local-two-eight", section.nameOfLocal(2, 8))
        assertEquals("local-nine-zero", section.nameOfLocal(9, 0))
        assertEquals("local-nine-three", section.nameOfLocal(9, 3))
        assertEquals("local-nine-seven-new", section.nameOfLocal(9, 7))
        assertNull(section.nameOfLocal(2, 7))
        assertNull(section.nameOfLocal(8, 0))
    }

    @Test
    fun parsesALargeReverseOrderedNameMap() {
        val count = 50_000
        val payload =
            ByteAccumulator(count * 4).apply {
                writeUnsignedLeb128(count)
                for (index in count - 1 downTo 0) {
                    writeUnsignedLeb128(index)
                    writeUnsignedLeb128(0)
                }
            }.toByteArray()

        val section = NameCustomSection.parse(subsection(FUNCTION_NAMES, payload))

        assertEquals(count, section.functionNameCount())
        assertEquals("", section.nameOfFunction(0))
        assertEquals("", section.nameOfFunction(count / 2))
        assertEquals("", section.nameOfFunction(count - 1))
    }

    private fun nameMap(entries: List<Pair<Int, String>>): ByteArray =
        ByteAccumulator().apply {
            writeUnsignedLeb128(entries.size)
            for ((index, name) in entries) {
                writeUnsignedLeb128(index)
                writeName(name)
            }
        }.toByteArray()

    private fun indirectNameMap(
        groups: List<Pair<Int, List<Pair<Int, String>>>>,
    ): ByteArray =
        ByteAccumulator().apply {
            writeUnsignedLeb128(groups.size)
            for ((groupIndex, entries) in groups) {
                writeUnsignedLeb128(groupIndex)
                writeUnsignedLeb128(entries.size)
                for ((index, name) in entries) {
                    writeUnsignedLeb128(index)
                    writeName(name)
                }
            }
        }.toByteArray()

    private fun subsection(id: Int, payload: ByteArray): ByteArray =
        ByteAccumulator(payload.size + 6).apply {
            writeByte(id)
            writeUnsignedLeb128(payload.size)
            write(payload)
        }.toByteArray()

    private fun subsections(vararg subsections: ByteArray): ByteArray =
        ByteAccumulator(subsections.sumOf { it.size }).apply {
            for (subsection in subsections) {
                write(subsection)
            }
        }.toByteArray()

    private class ByteAccumulator(initialCapacity: Int = 32) {
        private val bytes = ArrayList<Byte>(initialCapacity)

        fun writeByte(value: Int) {
            bytes.add(value.toByte())
        }

        fun writeUnsignedLeb128(value: Int) {
            var remaining = value
            do {
                var next = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) {
                    next = next or 0x80
                }
                writeByte(next)
            } while (remaining != 0)
        }

        fun writeName(value: String) {
            val encoded = value.encodeToByteArray()
            writeUnsignedLeb128(encoded.size)
            write(encoded)
        }

        fun write(values: ByteArray) {
            for (value in values) {
                bytes.add(value)
            }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private companion object {
        const val FUNCTION_NAMES = 1
        const val LOCAL_NAMES = 2
    }
}
