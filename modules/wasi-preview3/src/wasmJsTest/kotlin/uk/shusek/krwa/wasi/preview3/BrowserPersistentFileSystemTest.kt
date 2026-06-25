package uk.shusek.krwa.wasi.preview3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Sink

class BrowserPersistentFileSystemTest {
    @Test
    fun chunkedSinkAppendAndPersistenceRoundTrip() = runTest {
        val fileSystem = BrowserPersistentFileSystem()
        val storage = InMemoryPersistentFileStorage()
        val root = "/cache".toPath()
        val path = root.resolve("index.json")
        val first = patternedBytes(2 * 1024 * 1024 + 17)
        val second = patternedBytes(512 * 1024 + 31)
        val expected = first + second

        fileSystem.createDirectories(root)
        fileSystem.sink(path, mustCreate = false).useSink { sink ->
            sink.write(Buffer().write(first), first.size.toLong())
        }
        fileSystem.appendingSink(path, mustExist = true).useSink { sink ->
            sink.write(Buffer().write(second), second.size.toLong())
        }

        assertEquals(expected.size.toLong(), fileSystem.metadata(path).size)
        assertContentEquals(expected, fileSystem.read(path) { readByteArray() })

        fileSystem.persistPersistentDirectory(root, storage)
        assertTrue(storage.chunkCount(rootPath = "/cache", relativePath = "index.json") > 1)

        fileSystem.deleteRecursively(root, mustExist = true)
        assertFalse(fileSystem.exists(path))

        fileSystem.loadPersistentDirectory(root, storage)
        assertEquals(expected.size.toLong(), fileSystem.metadata(path).size)
        assertContentEquals(expected, fileSystem.read(path) { readByteArray() })
    }

    private class InMemoryPersistentFileStorage : BrowserPersistentFileStorage {
        private val directories = mutableMapOf<String, List<BrowserPersistentFileEntry>>()
        private val chunks = mutableMapOf<ChunkKey, ByteArray>()

        override suspend fun loadDirectory(rootPath: String): List<BrowserPersistentFileEntry>? =
            directories[rootPath]

        override suspend fun saveDirectory(rootPath: String, entries: List<BrowserPersistentFileEntry>) {
            directories[rootPath] = entries
        }

        override suspend fun deleteDirectory(rootPath: String) {
            directories.remove(rootPath)
            chunks.keys
                .filter { key -> key.rootPath == rootPath }
                .toList()
                .forEach(chunks::remove)
        }

        override suspend fun loadFileChunk(rootPath: String, relativePath: String, chunkIndex: Int): ByteArray? =
            chunks[ChunkKey(rootPath, relativePath, chunkIndex)]?.copyOf()

        override suspend fun saveFileChunk(
            rootPath: String,
            relativePath: String,
            chunkIndex: Int,
            bytes: ByteArray,
        ) {
            chunks[ChunkKey(rootPath, relativePath, chunkIndex)] = bytes.copyOf()
        }

        override suspend fun pruneFileChunks(rootPath: String, files: List<BrowserPersistentFile>) {
            val keep = files.associate { file -> file.relativePath to file.chunks }
            chunks.keys
                .filter { key ->
                    key.rootPath == rootPath &&
                        (key.relativePath !in keep || key.chunkIndex >= keep.getValue(key.relativePath))
                }
                .toList()
                .forEach(chunks::remove)
        }

        fun chunkCount(rootPath: String, relativePath: String): Int =
            chunks.keys.count { key -> key.rootPath == rootPath && key.relativePath == relativePath }
    }

    private data class ChunkKey(
        val rootPath: String,
        val relativePath: String,
        val chunkIndex: Int,
    )

    private inline fun <T : Sink, R> T.useSink(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        const val ByteMask = 0xff

        fun patternedBytes(size: Int): ByteArray = ByteArray(size) { index ->
            ((index * 31 + index / 17) and ByteMask).toByte()
        }
    }
}
