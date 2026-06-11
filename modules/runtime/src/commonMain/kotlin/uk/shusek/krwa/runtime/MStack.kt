package uk.shusek.krwa.runtime

open class MStack {
    private var count = 0
    private var elements = LongArray(MIN_CAPACITY)

    private fun increaseCapacity() {
        val newCapacity = elements.size shl 1
        val array = LongArray(newCapacity)
        elements.copyInto(array)
        elements = array
    }

    // internal use only!
    fun array(): LongArray = elements

    fun push(value: Long) {
        elements[count] = value
        count++

        if (count == elements.size) {
            increaseCapacity()
        }
    }

    fun pop(): Long {
        count--
        return elements[count]
    }

    fun i32Add() {
        val result = elements[count - 1].toInt() + elements[count - 2].toInt()
        count--
        elements[count - 1] = result.toLong()
    }

    fun i32Sub() {
        val result = elements[count - 2].toInt() - elements[count - 1].toInt()
        count--
        elements[count - 1] = result.toLong()
    }

    fun i32Eqz() {
        elements[count - 1] = if (elements[count - 1].toInt() == 0) TRUE else FALSE
    }

    fun i32Eq() {
        val result = elements[count - 2].toInt() == elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32Ne() {
        val result = elements[count - 2].toInt() != elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32LtS() {
        val result = elements[count - 2].toInt() < elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32LtU() {
        val result = elements[count - 2].toInt().toUInt() < elements[count - 1].toInt().toUInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32LeS() {
        val result = elements[count - 2].toInt() <= elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32LeU() {
        val result = elements[count - 2].toInt().toUInt() <= elements[count - 1].toInt().toUInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32GtS() {
        val result = elements[count - 2].toInt() > elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32GtU() {
        val result = elements[count - 2].toInt().toUInt() > elements[count - 1].toInt().toUInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32GeS() {
        val result = elements[count - 2].toInt() >= elements[count - 1].toInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i32GeU() {
        val result = elements[count - 2].toInt().toUInt() >= elements[count - 1].toInt().toUInt()
        count--
        elements[count - 1] = if (result) TRUE else FALSE
    }

    fun i64ExtendI32STop() {
        elements[count - 1] = elements[count - 1].toInt().toLong()
    }

    fun i32WrapI64Top() {
        elements[count - 1] = elements[count - 1].toInt().toLong()
    }

    fun discardToSizeKeepingTop(size: Int) {
        if (count <= size) {
            val result = if (count > 0) pop() else 0L
            discardToSize(size)
            push(result)
            return
        }
        val result = elements[count - 1]
        count = size + 1
        elements[size] = result
    }

    fun discardToSizeKeepingTop2(size: Int) {
        if (count <= size + 1) {
            val first = if (count > 0) pop() else 0L
            val second = if (count > 0) pop() else 0L
            discardToSize(size)
            push(second)
            push(first)
            return
        }
        val first = elements[count - 2]
        val second = elements[count - 1]
        count = size + 2
        elements[size] = first
        elements[size + 1] = second
    }

    fun peek(): Long = elements[count - 1]

    fun replaceTop(value: Long) {
        elements[count - 1] = value
    }

    fun size(): Int = count

    internal fun snapshotFrom(size: Int): LongArray {
        require(size in 0..count) { "stack snapshot start is out of range: $size" }
        return elements.copyOfRange(size, count)
    }

    internal fun restoreFrom(size: Int, values: LongArray) {
        require(size >= 0) { "stack restore start is out of range: $size" }
        val requiredCapacity = size + values.size + 1
        while (requiredCapacity > elements.size) {
            increaseCapacity()
        }
        values.copyInto(elements, destinationOffset = size)
        count = size + values.size
    }

    fun discardToSize(size: Int) {
        if (count > size) {
            count = size
        }
    }

    fun shrinkToSize(size: Int) {
        count = size
    }

    companion object {
        const val MIN_CAPACITY: Int = 512
        private const val TRUE: Long = 1L
        private const val FALSE: Long = 0L
    }
}
