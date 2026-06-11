package uk.shusek.krwa.component

class WitResourceTable<T> {
    private val values = LinkedHashMap<Long, T>()
    private var nextHandle = 1L

    fun insert(value: T): WitResource<T> = WitResource(insertHandle(value))

    fun insertResource(value: T): WitResource<Nothing> = WitResource(insertHandle(value))

    private fun insertHandle(value: T): Long {
        val nonNullValue = value ?: throw NullPointerException("value")
        if (nextHandle == 0L || nextHandle > 0xffff_ffffL) {
            throw ComponentModelException("WIT resource table exhausted")
        }
        val handle = nextHandle++
        values[handle] = nonNullValue
        return handle
    }

    fun get(resource: WitResource<*>): T = get(resource.handle())

    fun get(handle: Long): T =
        values[handle]
            ?: throw ComponentModelException("unknown WIT resource handle ${handle.toULong()}")

    fun remove(resource: WitResource<*>): T = remove(resource.handle())

    fun remove(handle: Long): T =
        values.remove(handle)
            ?: throw ComponentModelException("unknown WIT resource handle ${handle.toULong()}")

    fun contains(resource: WitResource<*>): Boolean = contains(resource.handle())

    fun contains(handle: Long): Boolean = values.containsKey(handle)

    fun size(): Int = values.size

    fun snapshot(): List<T> = values.values.toList()

    fun clear() {
        values.clear()
    }
}
