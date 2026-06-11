package uk.shusek.krwa.compiler

import java.io.IOException

interface Cache {
    /**
     * Return the cached data for the given key if it exists else null.
     *
     * @param key "algo:digest"
     */
    @Throws(IOException::class) fun get(key: String): ByteArray?

    /**
     * Atomically publish data into the cache location for the key. If another thread/process
     * already published for this key then this is a no-op.
     *
     * @param key "algo:digest"
     * @param data the data to cache
     */
    @Throws(IOException::class) fun putIfAbsent(key: String, data: ByteArray)
}
