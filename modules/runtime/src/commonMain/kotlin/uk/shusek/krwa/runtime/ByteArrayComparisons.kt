package uk.shusek.krwa.runtime

internal fun compareByteArraysUnsignedPortable(
    left: ByteArray,
    leftOffset: Int,
    right: ByteArray,
    rightOffset: Int,
    length: Int,
): Int {
    var index = 0
    while (index < length) {
        val diff =
            (left[leftOffset + index].toInt() and 0xFF) -
                (right[rightOffset + index].toInt() and 0xFF)
        if (diff != 0) return diff
        index++
    }
    return 0
}
