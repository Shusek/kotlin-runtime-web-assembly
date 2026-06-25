@file:Suppress("TooManyFunctions")

package uk.shusek.krwa.wasi.preview3

import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout

public class BrowserPersistentFileSystem(
    private val trace: (String) -> Unit = {},
) : FileSystem() {
    private val entries: MutableMap<String, Entry> = linkedMapOf(RootPath to Entry.Directory)

    override fun canonicalize(path: Path): Path {
        val key = path.key()
        if (!entries.containsKey(key)) {
            throw IOException("No such file or directory: $path")
        }
        return key.toPath()
    }

    override fun metadataOrNull(path: Path): FileMetadata? = when (val entry = entries[path.key()]) {
        Entry.Directory -> FileMetadata(isDirectory = true)
        is Entry.File -> FileMetadata(isRegularFile = true, size = entry.size.toLong())
        null -> null
    }

    override fun list(dir: Path): List<Path> = listOrNull(dir) ?: throw IOException("No such directory: $dir")

    override fun listOrNull(dir: Path): List<Path>? {
        val key = dir.key()
        if (entries[key] != Entry.Directory) return null
        val prefix = if (key == RootPath) RootPath else "$key/"
        return entries.keys
            .asSequence()
            .filter { child -> child != key && child.startsWith(prefix) }
            .map { child -> child.removePrefix(prefix).substringBefore('/') }
            .filter { name -> name.isNotBlank() }
            .distinct()
            .map { name -> (if (key == RootPath) "/$name" else "$key/$name").toPath() }
            .sorted()
            .toList()
    }

    override fun openReadOnly(file: Path): FileHandle {
        val key = file.key()
        requireFileEntry(key)
        return ChunkedFileHandle(key, readWrite = false)
    }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val key = file.key()
        val existing = entries[key]
        when {
            existing is Entry.Directory -> throw IOException("Path is a directory: $file")
            existing != null && mustCreate -> throw IOException("File already exists: $file")
            existing == null && mustExist -> throw IOException("No such file: $file")
            existing == null -> {
                requireParentDirectory(key)
                entries[key] = Entry.File.empty(trace)
            }
        }
        return ChunkedFileHandle(key, readWrite = true)
    }

    override fun source(file: Path): Source = ChunkedFileSource(file.key())

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val key = file.key()
        if (mustCreate && entries.containsKey(key)) {
            throw IOException("File already exists: $file")
        }
        if (entries[key] is Entry.Directory) {
            throw IOException("Path is a directory: $file")
        }
        requireParentDirectory(key)
        entries[key] = Entry.File.empty(trace)
        return ChunkedFileSink(key, append = false)
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val key = file.key()
        val existing = entries[key]
        when {
            mustExist -> requireFileEntry(key)
            existing is Entry.Directory -> throw IOException("Path is a directory: $file")
            existing == null -> {
                requireParentDirectory(key)
                entries[key] = Entry.File.empty(trace)
            }
        }
        return ChunkedFileSink(key, append = true)
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        val key = dir.key()
        val existing = entries[key]
        when {
            existing == Entry.Directory && mustCreate -> throw IOException("Directory already exists: $dir")
            existing == Entry.Directory -> return
            existing != null -> throw IOException("Path is not a directory: $dir")
            else -> {
                requireParentDirectory(key)
                entries[key] = Entry.Directory
            }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        val sourceKey = source.key()
        val targetKey = target.key()
        val sourceEntry = entries[sourceKey] ?: throw IOException("No such file: $source")
        requireParentDirectory(targetKey)
        delete(target, mustExist = false)
        if (sourceEntry is Entry.Directory) {
            val moved = entries
                .filterKeys { key -> key == sourceKey || key.startsWith("$sourceKey/") }
                .mapKeys { (key, _) -> targetKey + key.removePrefix(sourceKey) }
            entries.keys
                .filter { key -> key == sourceKey || key.startsWith("$sourceKey/") }
                .toList()
                .forEach(entries::remove)
            entries.putAll(moved)
        } else {
            entries.remove(sourceKey)
            entries[targetKey] = sourceEntry
        }
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val key = path.key()
        if (key == RootPath) {
            throw IOException("Cannot delete filesystem root.")
        }
        val existing = entries[key]
        if (existing == null) {
            if (mustExist) throw IOException("No such file or directory: $path")
            return
        }
        if (existing is Entry.Directory && entries.keys.any { child -> child.startsWith("$key/") }) {
            throw IOException("Directory is not empty: $path")
        }
        entries.remove(key)
    }

    override fun createSymlink(source: Path, target: Path): Unit =
        throw IOException("Symlinks are not supported in the browser persistent filesystem.")

    public fun readBytes(path: String): ByteArray = requireFile(path.toPath().key())

    public fun writeBytes(path: Path, bytes: ByteArray) {
        val key = path.key()
        requireParentDirectory(key)
        entries[key] = Entry.File.fromBytes(bytes, trace)
    }

    public suspend fun loadPersistentDirectory(root: Path, storage: BrowserPersistentFileStorage) {
        val rootKey = root.key()
        val records = storage.loadDirectory(rootKey) ?: return
        replaceDirectory(root, storage, records)
    }

    public suspend fun persistPersistentDirectory(root: Path, storage: BrowserPersistentFileStorage) {
        val rootKey = root.key()
        if (!entries.containsKey(rootKey)) {
            storage.deleteDirectory(rootKey)
            return
        }
        persistentDirectoryFiles(rootKey).forEach { file ->
            var chunkIndex = 0
            while (chunkIndex < file.entry.chunkCount) {
                storage.saveFileChunk(
                    rootPath = rootKey,
                    relativePath = file.relativePath,
                    chunkIndex = chunkIndex,
                    bytes = file.entry.readChunk(chunkIndex),
                )
                chunkIndex += 1
            }
        }
        val records = persistentDirectoryRecords(rootKey)
        storage.saveDirectory(rootKey, records)
        storage.pruneFileChunks(
            rootPath = rootKey,
            files = records.mapNotNull { record ->
                if (record.kind != BrowserPersistentFileEntryKind.File) return@mapNotNull null
                BrowserPersistentFile(rootPath = rootKey, relativePath = record.path, chunks = record.chunks)
            },
        )
    }

    public suspend fun deletePersistentDirectory(root: Path, storage: BrowserPersistentFileStorage) {
        val rootKey = root.key()
        deleteRecursively(root, mustExist = false)
        storage.deleteDirectory(rootKey)
    }

    private fun requireFile(key: String): ByteArray {
        val entry = entries[key] ?: throw IOException("No such file: $key")
        if (entry !is Entry.File) {
            throw IOException("Path is not a file: $key")
        }
        return entry.readBytes()
    }

    private fun requireFileEntry(key: String): Entry.File {
        val entry = entries[key] ?: throw IOException("No such file: $key")
        if (entry !is Entry.File) {
            throw IOException("Path is not a file: $key")
        }
        return entry
    }

    private fun persistentDirectoryRecords(rootKey: String): List<BrowserPersistentFileEntry> =
        entries
            .asSequence()
            .filter { (key, _) -> key == rootKey || key.startsWith("$rootKey/") }
            .map { (key, entry) ->
                val relativePath = key.removePrefix(rootKey).removePrefix("/")
                when (entry) {
                    Entry.Directory -> BrowserPersistentFileEntry(
                        path = relativePath,
                        kind = BrowserPersistentFileEntryKind.Directory,
                    )
                    is Entry.File -> BrowserPersistentFileEntry(
                        path = relativePath,
                        kind = BrowserPersistentFileEntryKind.File,
                        size = entry.size,
                        chunks = entry.chunkCount,
                    )
                }
            }
            .sortedWith(
                compareBy<BrowserPersistentFileEntry> { entry -> entry.path.count { char -> char == '/' } }
                    .thenBy { entry -> entry.path }
                    .thenBy { entry -> entry.kind.name },
            )
            .toList()

    private fun persistentDirectoryFiles(rootKey: String): List<BrowserPersistentFileRecord> =
        entries
            .asSequence()
            .mapNotNull { (key, entry) ->
                if (entry !is Entry.File || key != rootKey && !key.startsWith("$rootKey/")) return@mapNotNull null
                BrowserPersistentFileRecord(
                    relativePath = key.removePrefix(rootKey).removePrefix("/"),
                    entry = entry,
                )
            }
            .sortedBy(BrowserPersistentFileRecord::relativePath)
            .toList()

    private suspend fun replaceDirectory(
        root: Path,
        storage: BrowserPersistentFileStorage,
        records: List<BrowserPersistentFileEntry>,
    ) {
        val rootKey = root.key()
        entries.keys
            .filter { key -> key == rootKey || key.startsWith("$rootKey/") }
            .toList()
            .forEach(entries::remove)
        entries[rootKey] = Entry.Directory

        records.forEach { record ->
            val key = record.path.persistentRecordKey(rootKey)
            when (record.kind) {
                BrowserPersistentFileEntryKind.Directory -> {
                    createParentDirectories(key)
                    entries[key] = Entry.Directory
                }
                BrowserPersistentFileEntryKind.File -> {
                    val file = Entry.File.empty(trace)
                    repeat(record.chunks) { chunkIndex ->
                        val bytes = storage.loadFileChunk(
                            rootPath = rootKey,
                            relativePath = record.path,
                            chunkIndex = chunkIndex,
                        ) ?: return@forEach
                        file.write(file.size.toLong(), bytes, 0, bytes.size)
                    }
                    file.resize(record.size)
                    createParentDirectories(key)
                    entries[key] = file
                }
            }
        }
    }

    private fun requireParentDirectory(key: String) {
        val parent = key.parentKey() ?: return
        if (entries[parent] != Entry.Directory) {
            throw IOException("No such parent directory: $parent")
        }
    }

    private fun createParentDirectories(key: String) {
        val parent = key.parentKey() ?: return
        createParentDirectories(parent)
        val existing = entries[parent]
        if (existing == null) {
            entries[parent] = Entry.Directory
        } else if (existing != Entry.Directory) {
            throw IOException("Path is not a directory: $parent")
        }
    }

    private fun Path.key(): String {
        val normalized = normalized().toString().replace('\\', '/')
        val absolute = when {
            normalized == "." -> RootPath
            normalized.startsWith('/') -> normalized
            else -> "/$normalized"
        }
        return absolute.trimEnd('/').ifBlank { RootPath }
    }

    private fun String.parentKey(): String? {
        if (this == RootPath) return null
        val parent = substringBeforeLast('/', missingDelimiterValue = "")
        return parent.ifBlank { RootPath }
    }

    private data class BrowserPersistentFileRecord(
        val relativePath: String,
        val entry: Entry.File,
    )

    private sealed interface Entry {
        data object Directory : Entry
        class File private constructor(
            private val chunks: MutableList<ByteArray>,
            private val trace: (String) -> Unit,
            size: Int,
        ) : Entry {
            private var nextTraceSize = TraceChunkBytes
            var size = size
                private set
            val chunkCount: Int
                get() = requiredChunkCount(size)

            fun readBytes(): ByteArray {
                val bytes = ByteArray(size)
                read(fileOffset = 0, array = bytes, arrayOffset = 0, byteCount = size)
                return bytes
            }

            fun readChunk(chunkIndex: Int): ByteArray {
                val start = chunkIndex * FileChunkBytes
                check(start in 0 until size) {
                    "Browser persistent file chunk index is out of range: $chunkIndex."
                }
                val count = minOf(FileChunkBytes, size - start)
                return chunks[chunkIndex].copyOf(count)
            }

            fun read(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
                if (fileOffset >= size) return -1
                var position = checkedFilePosition(fileOffset)
                var destinationOffset = arrayOffset
                var remaining = minOf(byteCount, size - position)
                val total = remaining
                while (remaining > 0) {
                    val chunkIndex = position / FileChunkBytes
                    val chunkOffset = position % FileChunkBytes
                    val count = minOf(remaining, FileChunkBytes - chunkOffset)
                    chunks[chunkIndex].copyInto(
                        array,
                        destinationOffset = destinationOffset,
                        startIndex = chunkOffset,
                        endIndex = chunkOffset + count,
                    )
                    position += count
                    destinationOffset += count
                    remaining -= count
                }
                return total
            }

            fun write(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
                var offset = checkedFilePosition(fileOffset)
                val requiredSize = checkedFileSize(fileOffset + byteCount)
                ensureCapacity(requiredSize)
                var sourceOffset = arrayOffset
                var remaining = byteCount
                while (remaining > 0) {
                    val chunkIndex = offset / FileChunkBytes
                    val chunkOffset = offset % FileChunkBytes
                    val count = minOf(remaining, FileChunkBytes - chunkOffset)
                    array.copyInto(
                        chunks[chunkIndex],
                        destinationOffset = chunkOffset,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + count,
                    )
                    offset += count
                    sourceOffset += count
                    remaining -= count
                }
                if (requiredSize > size) {
                    size = requiredSize
                    traceSizeProgress()
                }
            }

            fun resize(size: Int) {
                if (size > this.size) {
                    ensureCapacity(size)
                } else if (size < this.size) {
                    zeroRange(start = size, end = this.size)
                    val requiredChunks = requiredChunkCount(size)
                    while (chunks.size > requiredChunks) {
                        chunks.removeAt(chunks.lastIndex)
                    }
                }
                this.size = size
                traceSizeProgress()
            }

            private fun ensureCapacity(requiredSize: Int) {
                while (chunks.size < requiredChunkCount(requiredSize)) {
                    chunks += ByteArray(FileChunkBytes)
                }
            }

            private fun zeroRange(start: Int, end: Int) {
                var position = start
                while (position < end) {
                    val chunkIndex = position / FileChunkBytes
                    if (chunkIndex >= chunks.size) return
                    val chunkOffset = position % FileChunkBytes
                    val count = minOf(end - position, FileChunkBytes - chunkOffset)
                    chunks[chunkIndex].fill(0, fromIndex = chunkOffset, toIndex = chunkOffset + count)
                    position += count
                }
            }

            private fun traceSizeProgress() {
                if (size < nextTraceSize) return
                trace("browser-persistent-file-growth size=$size")
                while (nextTraceSize <= size) {
                    nextTraceSize += TraceChunkBytes
                }
            }

            companion object {
                fun empty(trace: (String) -> Unit): File = File(mutableListOf(), trace, size = 0)

                fun fromBytes(bytes: ByteArray, trace: (String) -> Unit): File = empty(trace).apply {
                    write(fileOffset = 0, array = bytes, arrayOffset = 0, byteCount = bytes.size)
                }
            }
        }
    }

    private inner class ChunkedFileSource(private val key: String) : Source {
        private var offset = 0L
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            if (byteCount == 0L) return 0L
            val file = requireFileEntry(key)
            if (offset >= file.size) return -1L
            val count = minOf(byteCount, IoBufferBytes.toLong(), file.size - offset).toInt()
            val bytes = ByteArray(count)
            val read = file.read(
                fileOffset = offset,
                array = bytes,
                arrayOffset = 0,
                byteCount = count,
            )
            if (read == -1) return -1L
            sink.write(bytes, 0, read)
            offset += read
            return read.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private inner class ChunkedFileSink(private val key: String, append: Boolean) : Sink {
        private var offset = if (append) requireFileEntry(key).size.toLong() else 0L
        private var closed = false
        private val scratch = ByteArray(IoBufferBytes)

        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "closed" }
            var remaining = byteCount
            val file = requireFileEntry(key)
            while (remaining > 0) {
                val count = minOf(scratch.size.toLong(), remaining).toInt()
                val read = source.read(scratch, 0, count)
                if (read == -1) {
                    throw IOException("Unexpected end of source while writing browser persistent file: $key")
                }
                file.write(
                    fileOffset = offset,
                    array = scratch,
                    arrayOffset = 0,
                    byteCount = read,
                )
                offset += read
                remaining -= read
            }
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private inner class ChunkedFileHandle(private val key: String, readWrite: Boolean) : FileHandle(readWrite) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            requireFileEntry(key).read(
                fileOffset = fileOffset,
                array = array,
                arrayOffset = arrayOffset,
                byteCount = byteCount,
            )

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            requireFileEntry(key).write(
                fileOffset = fileOffset,
                array = array,
                arrayOffset = arrayOffset,
                byteCount = byteCount,
            )
        }

        override fun protectedFlush() = Unit

        override fun protectedResize(size: Long) {
            requireFileEntry(key).resize(checkedFileSize(size))
        }

        override fun protectedSize(): Long = requireFileEntry(key).size.toLong()

        override fun protectedClose() = Unit
    }
}

public interface BrowserPersistentFileStorage {
    public suspend fun loadDirectory(rootPath: String): List<BrowserPersistentFileEntry>?
    public suspend fun saveDirectory(rootPath: String, entries: List<BrowserPersistentFileEntry>)
    public suspend fun deleteDirectory(rootPath: String)
    public suspend fun loadFileChunk(rootPath: String, relativePath: String, chunkIndex: Int): ByteArray?
    public suspend fun saveFileChunk(rootPath: String, relativePath: String, chunkIndex: Int, bytes: ByteArray)
    public suspend fun pruneFileChunks(rootPath: String, files: List<BrowserPersistentFile>)
}

public data class BrowserPersistentFileEntry(
    val path: String,
    val kind: BrowserPersistentFileEntryKind,
    val size: Int = 0,
    val chunks: Int = 0,
)

public enum class BrowserPersistentFileEntryKind {
    Directory,
    File,
}

public data class BrowserPersistentFile(
    val rootPath: String,
    val relativePath: String,
    val chunks: Int,
)

private fun requiredChunkCount(size: Int): Int =
    if (size == 0) 0 else ((size - 1) / FileChunkBytes) + 1

private fun checkedFilePosition(value: Long): Int {
    if (value < 0 || value > Int.MAX_VALUE) {
        throw IOException("Browser persistent file offset is out of range: $value.")
    }
    return value.toInt()
}

private fun checkedFileSize(value: Long): Int {
    if (value < 0 || value > Int.MAX_VALUE) {
        throw IOException("Browser persistent file size is out of range: $value.")
    }
    return value.toInt()
}

private fun String.persistentRecordKey(rootKey: String): String {
    val normalized = replace('\\', '/').trim('/')
    check(!startsWith('/')) {
        "Browser persistent filesystem entry must be relative: $this."
    }
    val segments = normalized
        .split('/')
        .filter(String::isNotBlank)
    check(segments.none { segment -> segment == "." || segment == ".." }) {
        "Browser persistent filesystem entry must not contain . or .. segments: $this."
    }
    return if (segments.isEmpty()) {
        rootKey
    } else {
        "$rootKey/${segments.joinToString("/")}"
    }
}

private const val FileChunkBytes = 256 * 1024
private const val IoBufferBytes = 64 * 1024
private const val TraceChunkBytes = 4 * 1024 * 1024
private const val RootPath = "/"
