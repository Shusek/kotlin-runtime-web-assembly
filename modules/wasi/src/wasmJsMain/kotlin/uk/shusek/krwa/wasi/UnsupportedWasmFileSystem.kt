@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.wasi

import kotlin.time.Clock
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source

private fun wasmFsNowMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal class MemoryWasmFileSystem : FileSystem() {
    private val root = "/".toPath()
    private val entries = mutableMapOf<Path, Node>(
        root to Node.Directory(wasmFsNowMillis(), wasmFsNowMillis(), wasmFsNowMillis())
    )

    override fun canonicalize(path: Path): Path {
        val canonical = resolvePath(path, followFinal = true)
        if (entries[canonical] == null) {
            throw IOException("no such file or directory: $path")
        }
        return canonical
    }

    override fun metadataOrNull(path: Path): FileMetadata? =
        entries[resolvePath(path, followFinal = false)]?.toMetadata()

    override fun list(dir: Path): List<Path> {
        val canonical = resolvePath(dir, followFinal = true)
        val node = entries[canonical] ?: throw IOException("no such directory: $dir")
        if (node !is Node.Directory) {
            throw IOException("not a directory: $dir")
        }
        node.lastAccessedAtMillis = wasmFsNowMillis()
        return entries.keys
            .filter { it != canonical && it.parent == canonical }
            .sorted()
    }

    override fun listOrNull(dir: Path): List<Path>? =
        try {
            list(dir)
        } catch (_: IOException) {
            null
        }

    override fun openReadOnly(file: Path): FileHandle =
        MemoryFileHandle(fileNode(file), readWrite = false)

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val linkPath = resolvePath(file, followFinal = false)
        if (mustCreate && entries[linkPath] != null) {
            throw IOException("file already exists: $file")
        }
        val canonical = resolvePath(file, followFinal = true)
        val existing = entries[canonical]
        if (existing != null) {
            if (mustCreate) {
                throw IOException("file already exists: $file")
            }
            if (existing !is Node.File) {
                throw IOException("not a file: $file")
            }
            return MemoryFileHandle(existing, readWrite = true)
        }
        if (mustExist) {
            throw IOException("no such file: $file")
        }
        requireParentDirectory(canonical)
        val now = wasmFsNowMillis()
        val created = Node.File(ByteArray(0), now, now, now)
        entries[canonical] = created
        return MemoryFileHandle(created, readWrite = true)
    }

    override fun source(file: Path): Source = openReadOnly(file).source()

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val handle = openReadWrite(file, mustCreate = mustCreate, mustExist = false)
        handle.resize(0L)
        return handle.sink()
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        openReadWrite(file, mustCreate = false, mustExist = mustExist).appendingSink()

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        val canonical = resolvePath(dir, followFinal = false)
        val existing = entries[canonical]
        if (existing != null) {
            if (mustCreate) {
                throw IOException("directory already exists: $dir")
            }
            if (existing !is Node.Directory) {
                throw IOException("not a directory: $dir")
            }
            return
        }
        requireParentDirectory(canonical)
        val now = wasmFsNowMillis()
        entries[canonical] = Node.Directory(now, now, now)
    }

    override fun atomicMove(source: Path, target: Path) {
        val sourcePath = resolvePath(source, followFinal = false)
        val targetPath = resolvePath(target, followFinal = false)
        if (sourcePath == targetPath) {
            return
        }
        if (sourcePath == root || targetPath == root) {
            throw IOException("cannot move filesystem root")
        }
        val sourceNode = entries[sourcePath] ?: throw IOException("no such file: $source")
        requireParentDirectory(targetPath)
        if (sourceNode is Node.Directory && isDescendant(targetPath, sourcePath)) {
            throw IOException("cannot move a directory into itself: $source")
        }
        entries[targetPath]?.let { existingTarget ->
            if (existingTarget is Node.Directory && entries.keys.any { it.parent == targetPath }) {
                throw IOException("target directory is not empty: $target")
            }
        }

        val moved = entries.filterKeys { it == sourcePath || isDescendant(it, sourcePath) }
        for (oldPath in moved.keys) {
            entries.remove(oldPath)
        }
        entries.remove(targetPath)
        for ((oldPath, node) in moved) {
            val newPath =
                if (oldPath == sourcePath) targetPath
                else targetPath.resolve(oldPath.relativeTo(sourcePath), normalize = false)
            entries[newPath] = node
        }
        sourceNode.lastModifiedAtMillis = wasmFsNowMillis()
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val canonical = resolvePath(path, followFinal = false)
        if (canonical == root) {
            throw IOException("cannot delete filesystem root")
        }
        val existing = entries[canonical]
        if (existing == null) {
            if (mustExist) {
                throw IOException("no such file or directory: $path")
            }
            return
        }
        if (existing is Node.Directory && entries.keys.any { it.parent == canonical }) {
            throw IOException("directory is not empty: $path")
        }
        entries.remove(canonical)
    }

    override fun createSymlink(source: Path, target: Path) {
        val link = resolvePath(source, followFinal = false)
        if (entries[link] != null) {
            throw IOException("file already exists: $source")
        }
        requireParentDirectory(link)
        val now = wasmFsNowMillis()
        entries[link] = Node.Symlink(target.normalized(), now, now, now)
    }

    fun createHardLink(oldPath: Path, newPath: Path) {
        val old = resolvePath(oldPath, followFinal = false)
        val existing = entries[old] ?: throw IOException("no such file: $oldPath")
        val target = resolvePath(newPath, followFinal = false)
        if (entries[target] != null) {
            throw IOException("file already exists: $newPath")
        }
        requireParentDirectory(target)
        entries[target] = existing
    }

    fun setTimes(
        path: Path,
        accessMillis: Long?,
        modificationMillis: Long?,
        followSymlinks: Boolean = true,
    ) {
        val node =
            entries[resolvePath(path, followFinal = followSymlinks)]
                ?: throw IOException("no such file or directory: $path")
        if (accessMillis != null) {
            node.lastAccessedAtMillis = accessMillis
        }
        if (modificationMillis != null) {
            node.lastModifiedAtMillis = modificationMillis
        }
    }

    private fun fileNode(file: Path): Node.File {
        val node =
            entries[resolvePath(file, followFinal = true)]
                ?: throw IOException("no such file: $file")
        if (node !is Node.File) {
            throw IOException("not a file: $file")
        }
        return node
    }

    private fun requireParentDirectory(path: Path) {
        val parent = path.parent ?: throw IOException("missing parent for path: $path")
        val parentPath = resolvePath(parent, followFinal = true)
        if (entries[parentPath] !is Node.Directory) {
            throw IOException("parent is not a directory: $parent")
        }
    }

    private fun pathKey(path: Path): Path {
        val normalized = root.resolve(path, normalize = true)
        if (normalized.root != root) {
            throw IOException("unsupported path root: $path")
        }
        return normalized
    }

    private fun resolvePath(path: Path, followFinal: Boolean): Path =
        resolvePath(path, followFinal, LinkedHashSet())

    private fun resolvePath(path: Path, followFinal: Boolean, seen: MutableSet<Path>): Path {
        val normalized = pathKey(path)
        if (normalized == root) {
            return root
        }
        var current = root
        val segments = normalized.segments
        for (index in segments.indices) {
            current = current.resolve(segments[index], normalize = true)
            val node = entries[current]
            val shouldFollow = index < segments.lastIndex || followFinal
            if (node is Node.Symlink && shouldFollow) {
                current = resolveSymlinkTarget(current, node.target, seen)
            }
        }
        return current
    }

    private fun resolveSymlinkTarget(
        linkPath: Path,
        target: Path,
        seen: MutableSet<Path>,
    ): Path {
        if (!seen.add(linkPath)) {
            throw IOException("too many levels of symbolic links: $linkPath")
        }
        val parent = linkPath.parent ?: root
        val next = if (target.isAbsolute) target else parent.resolve(target, normalize = true)
        return resolvePath(next, followFinal = true, seen)
    }

    private fun isDescendant(path: Path, directory: Path): Boolean {
        var current = path.parent
        while (current != null) {
            if (current == directory) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun Node.toMetadata(): FileMetadata =
        when (this) {
            is Node.Directory ->
                FileMetadata(
                    isDirectory = true,
                    createdAtMillis = createdAtMillis,
                    lastModifiedAtMillis = lastModifiedAtMillis,
                    lastAccessedAtMillis = lastAccessedAtMillis,
                )
            is Node.File ->
                FileMetadata(
                    isRegularFile = true,
                    size = data.size.toLong(),
                    createdAtMillis = createdAtMillis,
                    lastModifiedAtMillis = lastModifiedAtMillis,
                    lastAccessedAtMillis = lastAccessedAtMillis,
                )
            is Node.Symlink ->
                FileMetadata(
                    symlinkTarget = target,
                    size = target.toString().length.toLong(),
                    createdAtMillis = createdAtMillis,
                    lastModifiedAtMillis = lastModifiedAtMillis,
                    lastAccessedAtMillis = lastAccessedAtMillis,
                )
        }

    private sealed class Node(
        val createdAtMillis: Long,
        var lastModifiedAtMillis: Long,
        var lastAccessedAtMillis: Long,
    ) {
        class Directory(
            createdAtMillis: Long,
            lastModifiedAtMillis: Long,
            lastAccessedAtMillis: Long,
        ) : Node(createdAtMillis, lastModifiedAtMillis, lastAccessedAtMillis)

        class File(
            var data: ByteArray,
            createdAtMillis: Long,
            lastModifiedAtMillis: Long,
            lastAccessedAtMillis: Long,
        ) : Node(createdAtMillis, lastModifiedAtMillis, lastAccessedAtMillis)

        class Symlink(
            val target: Path,
            createdAtMillis: Long,
            lastModifiedAtMillis: Long,
            lastAccessedAtMillis: Long,
        ) : Node(createdAtMillis, lastModifiedAtMillis, lastAccessedAtMillis)
    }

    private class MemoryFileHandle(
        private val file: Node.File,
        readWrite: Boolean,
    ) : FileHandle(readWrite) {
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int {
            require(fileOffset >= 0L) { "fileOffset < 0: $fileOffset" }
            if (fileOffset >= file.data.size) {
                return -1
            }
            val offset = fileOffset.toInt()
            val count = minOf(byteCount, file.data.size - offset)
            file.data.copyInto(
                destination = array,
                destinationOffset = arrayOffset,
                startIndex = offset,
                endIndex = offset + count,
            )
            file.lastAccessedAtMillis = wasmFsNowMillis()
            return count
        }

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) {
            require(fileOffset >= 0L) { "fileOffset < 0: $fileOffset" }
            val endOffset = fileOffset + byteCount
            if (endOffset > Int.MAX_VALUE) {
                throw IOException("file is too large for web/wasm memory filesystem")
            }
            if (endOffset > file.data.size) {
                file.data = file.data.copyOf(endOffset.toInt())
            }
            array.copyInto(
                destination = file.data,
                destinationOffset = fileOffset.toInt(),
                startIndex = arrayOffset,
                endIndex = arrayOffset + byteCount,
            )
            val now = wasmFsNowMillis()
            file.lastModifiedAtMillis = now
            file.lastAccessedAtMillis = now
        }

        override fun protectedFlush() {
        }

        override fun protectedResize(size: Long) {
            require(size >= 0L) { "size < 0: $size" }
            if (size > Int.MAX_VALUE) {
                throw IOException("file is too large for web/wasm memory filesystem")
            }
            file.data = file.data.copyOf(size.toInt())
            val now = wasmFsNowMillis()
            file.lastModifiedAtMillis = now
            file.lastAccessedAtMillis = now
        }

        override fun protectedSize(): Long = file.data.size.toLong()

        override fun protectedClose() {
        }
    }
}
