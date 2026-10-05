package uk.shusek.krwa.wasi.preview3

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import okio.BufferedSink
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

public data class WasiFileMetadata(
    public val isRegularFile: Boolean,
    public val isDirectory: Boolean,
    public val isSymbolicLink: Boolean,
    public val size: Long,
    public val lastModifiedTime: WasiInstant,
)

public data class WasiFileEntry(
    public val path: String,
    public val name: String,
    public val metadata: WasiFileMetadata,
)

public class WasiFileSystem internal constructor(
    public val guestRoot: String,
    private val fileSystem: FileSystem,
    private val hostRootPath: Path,
    public val writable: Boolean,
) {
    public val hostRoot: String = hostRootPath.toString()

    public fun hostPath(path: String): String = resolve(path).toString()

    public fun exists(path: String): Boolean = fileSystem.exists(resolve(path))

    public fun metadata(path: String): WasiFileMetadata =
        metadata(fileSystem.metadata(resolve(path)))

    public fun list(path: String = "."): List<WasiFileEntry> {
        val directory = resolve(path)
        return fileSystem.list(directory)
            .map { child ->
                val relative = child.relativeTo(hostRootPath).toString()
                WasiFileEntry(
                    relative,
                    child.name,
                    metadata(fileSystem.metadata(child)),
                )
            }
            .sortedBy { it.path }
    }

    public fun createDirectories(path: String) {
        requireWritable()
        fileSystem.createDirectories(resolve(path))
    }

    public fun readBytes(path: String): ByteArray =
        fileSystem.read(resolve(path)) {
            readByteArray()
        }

    public fun readText(
        path: String,
        charsetName: String = DEFAULT_CHARSET,
    ): String = readBytes(path).decodeText(charsetName)

    public fun writeBytes(
        path: String,
        bytes: ByteArray,
        createParentDirectories: Boolean = true,
    ) {
        requireWritable()
        val target = resolve(path)
        if (createParentDirectories) {
            target.parent?.let { fileSystem.createDirectories(it) }
        }
        fileSystem.write(target) {
            write(bytes)
        }
    }

    public fun appendBytes(
        path: String,
        bytes: ByteArray,
        createParentDirectories: Boolean = true,
    ) {
        val output = sink(path, append = true, createParentDirectories)
        try {
            output.write(bytes)
        } finally {
            output.close()
        }
    }

    public fun writeText(
        path: String,
        text: String,
        charsetName: String = DEFAULT_CHARSET,
        createParentDirectories: Boolean = true,
    ) {
        writeBytes(path, text.encodeText(charsetName), createParentDirectories)
    }

    public fun appendText(
        path: String,
        text: String,
        charsetName: String = DEFAULT_CHARSET,
        createParentDirectories: Boolean = true,
    ) {
        appendBytes(path, text.encodeText(charsetName), createParentDirectories)
    }

    public fun delete(
        path: String,
        recursive: Boolean = false,
    ) {
        requireWritable()
        val target = resolve(path)
        if (recursive && fileSystem.metadataOrNull(target)?.isDirectory == true) {
            fileSystem.deleteRecursively(target, mustExist = false)
        } else {
            fileSystem.delete(target, mustExist = false)
        }
    }

    public fun readByteChunks(
        path: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): Flow<ByteArray> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        return flow {
            val input = fileSystem.source(resolve(path)).buffer()
            try {
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    emit(buffer.copyOf(read))
                }
            } finally {
                input.close()
            }
        }
    }

    public suspend fun writeByteChunks(
        path: String,
        chunks: Flow<ByteArray>,
        append: Boolean = false,
        createParentDirectories: Boolean = true,
    ) {
        val output = sink(path, append, createParentDirectories)
        try {
            chunks.collect { chunk -> output.write(chunk) }
        } finally {
            output.close()
        }
    }

    private fun sink(
        path: String,
        append: Boolean = false,
        createParentDirectories: Boolean = true,
    ): BufferedSink {
        requireWritable()
        val target = resolve(path)
        if (createParentDirectories) {
            target.parent?.let { fileSystem.createDirectories(it) }
        }
        return if (append) {
            fileSystem.appendingSink(target).buffer()
        } else {
            fileSystem.sink(target).buffer()
        }
    }

    private fun resolve(path: String): Path {
        require('\u0000' !in path) { "path contains NUL" }
        val relativeText = guestRelativePath(path)
        val relative = relativeText.toPath(normalize = true)
        require(relative.isRelative) { "absolute host paths are not allowed: $path" }
        val resolved = hostRootPath.resolve(relative, normalize = true)
        require(isInsideRoot(hostRootPath, resolved)) {
            "path escapes WASI preopen $guestRoot: $path"
        }
        require(isInsideRealRoot(resolved)) {
            "path escapes WASI preopen $guestRoot through a symbolic link: $path"
        }
        return resolved
    }

    private fun isInsideRoot(root: Path, candidate: Path): Boolean {
        val rootText = root.normalized().toString().trimEnd('/')
        val candidateText = candidate.normalized().toString()
        return candidateText == rootText || candidateText.startsWith("$rootText/")
    }

    /**
     * Lexical containment does not account for symbolic links: a link inside the preopen, which a
     * guest may create through `symlink-at`, can point anywhere on the host, and this host-side
     * facade follows links when it reads, writes, lists or deletes. The existing part of
     * [candidate] is therefore canonicalized, following every link including the final one, and
     * must stay under the real path of the preopen root. Links that stay inside the preopen keep
     * working.
     */
    private fun isInsideRealRoot(candidate: Path): Boolean =
        try {
            isInsideRoot(realPathAllowingMissingLeaf(hostRootPath), realPathAllowingMissingLeaf(candidate))
        } catch (_: IOException) {
            false
        }

    private fun realPathAllowingMissingLeaf(path: Path): Path {
        val normalized = path.normalized()
        if (fileSystem.exists(normalized)) {
            return fileSystem.canonicalize(normalized)
        }
        val missing = ArrayList<String>()
        var current: Path? = normalized
        while (current != null && !fileSystem.exists(current)) {
            missing.add(current.name)
            current = current.parent
        }
        if (current == null) {
            return normalized
        }
        var resolved = fileSystem.canonicalize(current)
        for (index in missing.indices.reversed()) {
            resolved = resolved.resolve(missing[index], normalize = true)
        }
        return resolved.normalized()
    }

    private fun guestRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val withoutRoot =
            when {
                normalized == guestRoot -> ""
                guestRoot != "/" && normalized.startsWith("$guestRoot/") ->
                    normalized.substring(guestRoot.length + 1)
                normalized.startsWith("/") -> normalized.dropWhile { it == '/' }
                else -> normalized
            }
        return withoutRoot.ifBlank { "." }
    }

    private fun requireWritable() {
        require(writable) { "WASI preopen $guestRoot is read-only" }
    }

    private fun metadata(metadata: FileMetadata): WasiFileMetadata =
        WasiFileMetadata(
            metadata.isRegularFile,
            metadata.isDirectory,
            metadata.symlinkTarget != null,
            metadata.size ?: 0L,
            metadata.lastModifiedAtMillis?.let(WasiInstant::fromEpochMilliseconds)
                ?: WasiInstant.fromEpochSeconds(0L),
        )

    private fun ByteArray.decodeText(charsetName: String): String {
        requireUtf8(charsetName)
        return decodeToString()
    }

    private fun String.encodeText(charsetName: String): ByteArray {
        requireUtf8(charsetName)
        return encodeToByteArray()
    }

    private fun requireUtf8(charsetName: String) {
        require(charsetName.equals(DEFAULT_CHARSET, ignoreCase = true) || charsetName.equals("UTF8", ignoreCase = true)) {
            "Only UTF-8 is supported on multiplatform WASI file systems"
        }
    }

    public companion object {
        public const val DEFAULT_CHUNK_SIZE: Int = 8192
        public const val DEFAULT_CHARSET: String = "UTF-8"

        internal fun create(
            guestRoot: String,
            hostRoot: Path,
            writable: Boolean,
            fileSystem: FileSystem = defaultWasiFileSystem(),
        ): WasiFileSystem =
            WasiFileSystem(
                normalizeGuestRoot(guestRoot),
                fileSystem,
                hostRoot.normalized(),
                writable,
            )

        internal fun create(
            guestRoot: String,
            hostRoot: String,
            writable: Boolean,
            fileSystem: FileSystem = defaultWasiFileSystem(),
        ): WasiFileSystem =
            create(guestRoot, hostRoot.toPath(normalize = true), writable, fileSystem)

        internal fun normalizeGuestRoot(guestRoot: String): String {
            val normalized = guestRoot.replace('\\', '/').trimEnd('/')
            return if (normalized.isBlank()) "/" else normalized
        }
    }
}
