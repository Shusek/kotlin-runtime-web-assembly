package uk.shusek.krwa.wasi

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.nio.file.FileSystem as JavaFileSystem
import java.nio.file.Path as JavaPath

internal class NioWasiFileSystem(
    private val fileSystem: JavaFileSystem,
) : FileSystem() {
    internal fun toJavaPath(path: Path): JavaPath = fileSystem.getPath(path.toString())

    private fun Path.toJavaPath(): JavaPath = toJavaPath(this)

    override fun canonicalize(path: Path): Path =
        try {
            path.toJavaPath().toRealPath().toString().toPath(normalize = true)
        } catch (_: NoSuchFileException) {
            throw FileNotFoundException("no such file: $path")
        }

    override fun metadataOrNull(path: Path): FileMetadata? {
        val javaPath = path.toJavaPath()
        val attributes =
            try {
                Files.readAttributes(javaPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return null
            }
        val symlinkTarget =
            if (attributes.isSymbolicLink) {
                Files.readSymbolicLink(javaPath).toString().toPath(normalize = true)
            } else {
                null
            }
        return FileMetadata(
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            symlinkTarget = symlinkTarget,
            size = attributes.size(),
            createdAtMillis = attributes.creationTime().toMillis().takeIf { it != 0L },
            lastModifiedAtMillis = attributes.lastModifiedTime().toMillis().takeIf { it != 0L },
            lastAccessedAtMillis = attributes.lastAccessTime().toMillis().takeIf { it != 0L },
        )
    }

    override fun list(dir: Path): List<Path> =
        Files.list(dir.toJavaPath()).use { stream ->
            stream
                .map { it.toString().toPath(normalize = true) }
                .sorted()
                .toList()
        }

    override fun listOrNull(dir: Path): List<Path>? =
        try {
            list(dir)
        } catch (_: java.io.IOException) {
            null
        }

    override fun openReadOnly(file: Path): FileHandle =
        try {
            NioWasiFileHandle(false, FileChannel.open(file.toJavaPath(), StandardOpenOption.READ))
        } catch (_: NoSuchFileException) {
            throw FileNotFoundException("no such file: $file")
        }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        require(!mustCreate || !mustExist) { "Cannot require mustCreate and mustExist at the same time." }
        val options =
            buildList {
                add(StandardOpenOption.READ)
                add(StandardOpenOption.WRITE)
                if (mustCreate) {
                    add(StandardOpenOption.CREATE_NEW)
                } else if (!mustExist) {
                    add(StandardOpenOption.CREATE)
                }
            }
        return try {
            NioWasiFileHandle(true, FileChannel.open(file.toJavaPath(), *options.toTypedArray()))
        } catch (_: NoSuchFileException) {
            throw FileNotFoundException("no such file: $file")
        }
    }

    override fun source(file: Path): Source =
        Files.newInputStream(file.toJavaPath()).source()

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val options =
            if (mustCreate) {
                arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }
        return Files.newOutputStream(file.toJavaPath(), *options).sink()
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val options =
            if (mustExist) {
                arrayOf(StandardOpenOption.APPEND)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        return Files.newOutputStream(file.toJavaPath(), *options).sink()
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        if (!mustCreate && Files.isDirectory(dir.toJavaPath())) {
            return
        }
        Files.createDirectory(dir.toJavaPath())
    }

    override fun atomicMove(source: Path, target: Path) {
        Files.move(
            source.toJavaPath(),
            target.toJavaPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun delete(path: Path, mustExist: Boolean) {
        if (mustExist) {
            Files.delete(path.toJavaPath())
        } else {
            Files.deleteIfExists(path.toJavaPath())
        }
    }

    override fun createSymlink(source: Path, target: Path) {
        Files.createSymbolicLink(source.toJavaPath(), target.toJavaPath())
    }
}

private class NioWasiFileHandle(
    readWrite: Boolean,
    private val channel: FileChannel,
) : FileHandle(readWrite) {
    @Synchronized
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        channel.position(fileOffset)
        val buffer = ByteBuffer.wrap(array, arrayOffset, byteCount)
        var read = 0
        while (read < byteCount) {
            val next = channel.read(buffer)
            if (next < 0) {
                return if (read == 0) -1 else read
            }
            read += next
        }
        return read
    }

    @Synchronized
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        channel.position(fileOffset)
        channel.write(ByteBuffer.wrap(array, arrayOffset, byteCount))
    }

    @Synchronized
    override fun protectedFlush() {
        channel.force(true)
    }

    @Synchronized
    override fun protectedResize(size: Long) {
        val currentSize = channel.size()
        if (size > currentSize) {
            protectedWrite(currentSize, ByteArray((size - currentSize).toInt()), 0, (size - currentSize).toInt())
        } else {
            channel.truncate(size)
        }
    }

    @Synchronized
    override fun protectedSize(): Long = channel.size()

    @Synchronized
    override fun protectedClose() {
        channel.close()
    }
}
