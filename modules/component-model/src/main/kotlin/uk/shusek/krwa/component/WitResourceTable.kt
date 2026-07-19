package uk.shusek.krwa.component

private const val DEFAULT_WIT_RESOURCE_TABLE_MAX_ENTRIES: Int = 65_536
private const val MAX_WIT_RESOURCE_HANDLE: Long = 0xffff_ffffL

class WitResourceTable<T>(
    maxEntries: Int = DEFAULT_WIT_RESOURCE_TABLE_MAX_ENTRIES,
) {
    private val lock = WasiPreviewLock()
    private val values = LinkedHashMap<Long, T>()
    private val maxEntries: Int
    private var nextHandle = 1L
    private var closed = false

    init {
        if (maxEntries <= 0) {
            throw IllegalArgumentException("maxEntries must be positive")
        }
        this.maxEntries = maxEntries
    }

    fun insert(value: T): WitResource<T> = WitResource(insertHandle(value))

    fun insertResource(value: T): WitResource<Nothing> = WitResource(insertHandle(value))

    private fun insertHandle(value: T): Long =
        insertHandles(listOf(value)).single()

    internal fun insertResourceHandles(insertedValues: List<T>): List<Long> {
        return insertHandles(insertedValues)
    }

    private fun insertHandles(insertedValues: List<T>): List<Long> {
        for (value in insertedValues) {
            if (value == null) {
                throw NullPointerException("value")
            }
        }
        return withWasiPreviewLock(lock) {
            ensureOpen()
            if (insertedValues.size > maxEntries - values.size) {
                throw ComponentModelException(
                    "WIT resource table limit exceeded: requested ${insertedValues.size}, " +
                        "current ${values.size}, limit $maxEntries"
                )
            }
            if (
                nextHandle == 0L ||
                    nextHandle > MAX_WIT_RESOURCE_HANDLE ||
                    insertedValues.size.toLong() > MAX_WIT_RESOURCE_HANDLE - nextHandle + 1L
            ) {
                throw ComponentModelException("WIT resource table exhausted")
            }
            val handles = ArrayList<Long>(insertedValues.size)
            for (value in insertedValues) {
                val handle = nextHandle++
                values[handle] = value
                handles.add(handle)
            }
            handles
        }
    }

    fun get(resource: WitResource<*>): T = get(resource.handle())

    fun get(handle: Long): T =
        withWasiPreviewLock(lock) {
            values[handle]
                ?: throw ComponentModelException("unknown WIT resource handle ${handle.toULong()}")
        }

    fun remove(resource: WitResource<*>): T = remove(resource.handle())

    fun remove(handle: Long): T =
        withWasiPreviewLock(lock) {
            values.remove(handle)
                ?: throw ComponentModelException("unknown WIT resource handle ${handle.toULong()}")
        }

    fun contains(resource: WitResource<*>): Boolean = contains(resource.handle())

    fun contains(handle: Long): Boolean =
        withWasiPreviewLock(lock) {
            values.containsKey(handle)
        }

    internal fun updateIfPresent(handle: Long, update: (T) -> Unit): Boolean =
        withWasiPreviewLock(lock) {
            val value = values[handle] ?: return@withWasiPreviewLock false
            update(value)
            true
        }

    fun size(): Int =
        withWasiPreviewLock(lock) {
            values.size
        }

    fun snapshot(): List<T> =
        withWasiPreviewLock(lock) {
            values.values.toList()
        }

    fun clear() {
        drain()
    }

    fun drain(): List<T> =
        withWasiPreviewLock(lock) {
            drainLocked()
        }

    fun close(): List<T> =
        withWasiPreviewLock(lock) {
            if (closed) {
                emptyList()
            } else {
                closed = true
                drainLocked()
            }
        }

    private fun drainLocked(): List<T> {
        val drained = values.values.toList()
        values.clear()
        return drained
    }

    private fun ensureOpen() {
        if (closed) {
            throw ComponentModelException("WIT resource table is closed")
        }
    }
}
