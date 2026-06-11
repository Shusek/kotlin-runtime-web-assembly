package uk.shusek.krwa.runtime

/** Runtime representation of a WasmGC array instance. */
class WasmArray private constructor(
    private val typeIdxValue: Int,
    private val longElements: LongArray?,
    private val byteElements: ByteArray?,
    private val shortElements: ShortArray?,
    private val storageKind: Int,
) : WasmGcRef {
    constructor(typeIdxValue: Int, elementsValue: LongArray) :
        this(typeIdxValue, elementsValue, null, null, STORAGE_LONG)

    constructor(typeIdxValue: Int, elementsValue: ByteArray) :
        this(typeIdxValue, null, elementsValue, null, STORAGE_BYTE)

    constructor(typeIdxValue: Int, elementsValue: ShortArray) :
        this(typeIdxValue, null, null, elementsValue, STORAGE_SHORT)

    override fun typeIdx(): Int = typeIdxValue

    fun get(idx: Int): Long =
        when (storageKind) {
            STORAGE_LONG -> longElements!![idx]
            STORAGE_BYTE -> byteElements!![idx].toLong()
            else -> shortElements!![idx].toLong()
        }

    fun set(idx: Int, value: Long) {
        when (storageKind) {
            STORAGE_LONG -> longElements!![idx] = value
            STORAGE_BYTE -> byteElements!![idx] = value.toByte()
            else -> shortElements!![idx] = value.toShort()
        }
    }

    fun length(): Int =
        when (storageKind) {
            STORAGE_LONG -> longElements!!.size
            STORAGE_BYTE -> byteElements!!.size
            else -> shortElements!!.size
        }

    fun elements(): LongArray {
        val longs = longElements
        if (longs != null) return longs

        val result = LongArray(length())
        copyInto(result)
        return result
    }

    internal fun copyInto(
        destination: LongArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = length(),
    ) {
        when (storageKind) {
            STORAGE_LONG ->
                longElements!!.copyInto(destination, destinationOffset, startIndex, endIndex)
            STORAGE_BYTE -> {
                val bytes = byteElements!!
                var targetIndex = destinationOffset
                for (sourceIndex in startIndex until endIndex) {
                    destination[targetIndex++] = bytes[sourceIndex].toLong()
                }
            }
            else -> {
                val shorts = shortElements!!
                var targetIndex = destinationOffset
                for (sourceIndex in startIndex until endIndex) {
                    destination[targetIndex++] = shorts[sourceIndex].toLong()
                }
            }
        }
    }

    private companion object {
        const val STORAGE_LONG = 0
        const val STORAGE_BYTE = 1
        const val STORAGE_SHORT = 2
    }
}
