package uk.shusek.krwa.wasi

import kotlin.math.min
import kotlin.time.ExperimentalTime
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import okio.FileHandle
import okio.FileMetadata
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import uk.shusek.krwa.runtime.ExecutionCompletedException
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.Memory

internal class WasiPreview1Engine(opts: WasiOptions) : WasiPreview1Host {
    private val random = opts.random()
    private val clock = opts.clock()
    private val arguments = opts.arguments().map { it.encodeToByteArray() }
    private val environment =
        opts.environment().entries.map { it.key.encodeToByteArray() to it.value.encodeToByteArray() }
    private val descriptors = DescriptorTable()
    private val throwOnExit0 = opts.throwOnExit0()

    init {
        descriptors.allocate(InStream(opts.stdinSource(), opts.stdinIsTty(), opts::stdinAvailable))
        descriptors.allocate(OutStream(opts.stdoutSink(), opts.stdoutIsTty()))
        descriptors.allocate(OutStream(opts.stderrSink(), opts.stderrIsTty()))

        for (entry in opts.preopenedDirectories()) {
            descriptors.allocate(PreopenedDirectory(entry.key.encodeToByteArray(), entry.value))
        }
    }

    fun close() {
        descriptors.closeAll()
    }

    override fun adapterCloseBadfd(fd: Int): Int = wasiResult(WasiErrno.EBADF)

    override fun adapterOpenBadfd(fd: Int): Int = wasiResult(WasiErrno.EBADF)

    override fun argsGet(memory: Memory, argvStart: Int, argvBufStart: Int): Int {
        var argv = argvStart
        var argvBuf = argvBufStart
        for (argument in arguments) {
            memory.writeI32(argv, argvBuf)
            argv += 4
            memory.write(argvBuf, argument)
            argvBuf += argument.size
            memory.writeByte(argvBuf, 0)
            argvBuf++
        }
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun argsSizesGet(memory: Memory, argc: Int, argvBufSize: Int): Int {
        memory.writeI32(argc, arguments.size)
        memory.writeI32(argvBufSize, arguments.sumOf { it.size + 1 })
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun clockResGet(memory: Memory, clockId: Int, resultPtr: Int): Int =
        when (clockId) {
            WasiClockId.REALTIME,
            WasiClockId.MONOTONIC -> {
                memory.writeLong(resultPtr, 1L)
                wasiResult(WasiErrno.ESUCCESS)
            }
            WasiClockId.PROCESS_CPUTIME_ID,
            WasiClockId.THREAD_CPUTIME_ID -> wasiResult(WasiErrno.ENOTSUP)
            else -> wasiResult(WasiErrno.EINVAL)
        }

    override fun clockTimeGet(memory: Memory, clockId: Int, precision: Long, resultPtr: Int): Int =
        when (clockId) {
            WasiClockId.REALTIME,
            WasiClockId.MONOTONIC -> {
                memory.writeLong(resultPtr, clockTime(clockId))
                wasiResult(WasiErrno.ESUCCESS)
            }
            WasiClockId.PROCESS_CPUTIME_ID,
            WasiClockId.THREAD_CPUTIME_ID -> wasiResult(WasiErrno.ENOTSUP)
            else -> wasiResult(WasiErrno.EINVAL)
        }

    override fun environGet(memory: Memory, environStart: Int, environBufStart: Int): Int {
        var environ = environStart
        var environBuf = environBufStart
        for ((name, value) in environment) {
            val data = ByteArray(name.size + value.size + 2)
            name.copyInto(data, destinationOffset = 0)
            data[name.size] = '='.code.toByte()
            value.copyInto(data, destinationOffset = name.size + 1)
            data[data.size - 1] = 0

            memory.writeI32(environ, environBuf)
            environ += 4
            memory.write(environBuf, data)
            environBuf += data.size
        }
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun environSizesGet(memory: Memory, environCount: Int, environBufSize: Int): Int {
        memory.writeI32(environCount, environment.size)
        memory.writeI32(environBufSize, environment.sumOf { it.first.size + it.second.size + 2 })
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdAdvise(fd: Int, offset: Long, len: Long, advice: Int): Int {
        if (len < 0 || offset < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.ESPIPE)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> wasiResult(WasiErrno.ESUCCESS)
        }
    }

    override fun fdAllocate(fd: Int, offset: Long, len: Long): Int {
        if (len <= 0 || offset < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.EINVAL)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                try {
                    val size = offset + len
                    val descriptor = descriptors.get(fd) as OpenFile
                    if (size > descriptor.handle.size()) {
                        descriptor.handle.resize(size)
                    }
                    wasiResult(WasiErrno.ESUCCESS)
                } catch (_: IOException) {
                    wasiResult(WasiErrno.EIO)
                } catch (_: IllegalStateException) {
                    wasiResult(WasiErrno.ENOTCAPABLE)
                }
            }
        }
    }

    override fun fdClose(fd: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        descriptors.free(fd)
        if (descriptor is OpenFile) {
            try {
                descriptor.handle.close()
            } catch (_: IOException) {
                return wasiResult(WasiErrno.EIO)
            }
        }
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdDatasync(fd: Int): Int = fileSync(fd)

    override fun fdFdstatGet(memory: Memory, fd: Int, buf: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        val fileType: WasiFileType
        val rightsBase: Long
        var flags = 0
        var rightsInheriting = 0L

        when (descriptor) {
            is InStream -> {
                fileType = if (descriptor.tty) WasiFileType.CHARACTER_DEVICE else WasiFileType.UNKNOWN
                rightsBase = WasiRights.FD_READ.toLong()
            }
            is OutStream -> {
                fileType = if (descriptor.tty) WasiFileType.CHARACTER_DEVICE else WasiFileType.UNKNOWN
                rightsBase = WasiRights.FD_WRITE.toLong()
            }
            is DirectoryDescriptor -> {
                fileType = WasiFileType.DIRECTORY
                rightsBase = descriptor.rightsBase
                rightsInheriting = descriptor.rightsInheriting
            }
            is OpenFile -> {
                fileType = WasiFileType.REGULAR_FILE
                rightsBase = descriptor.rightsBase and WasiRights.FILE_RIGHTS_BASE.toLong()
                flags = descriptor.fdFlags
            }
        }

        memory.write(buf, ByteArray(24))
        memory.writeByte(buf, fileType.value().toByte())
        memory.writeShort(buf + 2, flags.toShort())
        memory.writeLong(buf + 8, rightsBase)
        memory.writeLong(buf + 16, rightsInheriting)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdFdstatSetFlags(fd: Int, flags: Int): Int =
        when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.EINVAL)
            is DirectoryDescriptor -> wasiResult(WasiErrno.ESUCCESS)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                descriptor.fdFlags = flags
                wasiResult(WasiErrno.ESUCCESS)
            }
        }

    override fun fdFdstatSetRights(fd: Int, rightsBase: Long, rightsInheriting: Long): Int {
        if (rightsBase < 0 || rightsInheriting < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.ENOTSUP)
            is DirectoryDescriptor -> {
                if (!rightsSubset(rightsBase, descriptor.rightsBase) ||
                    !rightsSubset(rightsInheriting, descriptor.rightsInheriting)
                ) {
                    return wasiResult(WasiErrno.ENOTCAPABLE)
                }
                descriptor.rightsBase = rightsBase
                descriptor.rightsInheriting = rightsInheriting
                wasiResult(WasiErrno.ESUCCESS)
            }
            is OpenFile -> {
                if (!rightsSubset(rightsBase, descriptor.rightsBase) ||
                    !rightsSubset(rightsInheriting, 0L)
                ) {
                    return wasiResult(WasiErrno.ENOTCAPABLE)
                }
                descriptor.rightsBase = rightsBase
                wasiResult(WasiErrno.ESUCCESS)
            }
        }
    }

    override fun fdFilestatGet(memory: Memory, fd: Int, buf: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        val fileType =
            when (descriptor) {
                is InStream,
                is OutStream -> WasiFileType.CHARACTER_DEVICE
                is DirectoryDescriptor -> metadataOrNull(descriptor)?.let(::fileType) ?: WasiFileType.DIRECTORY
                is OpenFile -> metadataOrNull(descriptor)?.let(::fileType) ?: WasiFileType.REGULAR_FILE
            }
        val metadata =
            when (descriptor) {
                is DirectoryDescriptor -> metadataOrNull(descriptor)
                is OpenFile -> metadataOrNull(descriptor)
                else -> null
            }
        val fileId =
            when (descriptor) {
                is DirectoryDescriptor -> wasiPathFileId(descriptor.directory.fileSystem, descriptor.path)
                is OpenFile -> wasiPathFileId(descriptor.directory.fileSystem, descriptor.path)
                else -> 0L
            }
        val accessTime =
            when (descriptor) {
                is DirectoryDescriptor -> wasiPathAccessTimeNanos(descriptor.directory.fileSystem, descriptor.path)
                is OpenFile -> wasiPathAccessTimeNanos(descriptor.directory.fileSystem, descriptor.path)
                else -> null
            }
        val modifiedTime =
            when (descriptor) {
                is DirectoryDescriptor -> wasiPathModifiedTimeNanos(descriptor.directory.fileSystem, descriptor.path)
                is OpenFile -> wasiPathModifiedTimeNanos(descriptor.directory.fileSystem, descriptor.path)
                else -> null
            }
        writeFileStat(memory, buf, metadata, fileType, fileId, accessTime, modifiedTime)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdFilestatSetSize(fd: Int, size: Long): Int =
        when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.EINVAL)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                try {
                    val descriptor = descriptors.get(fd) as OpenFile
                    descriptor.handle.resize(size)
                    wasiResult(WasiErrno.ESUCCESS)
                } catch (_: IOException) {
                    wasiResult(WasiErrno.EIO)
                } catch (_: IllegalStateException) {
                    wasiResult(WasiErrno.ENOTCAPABLE)
                }
            }
        }

    override fun fdFilestatSetTimes(fd: Int, accessTime: Long, modifiedTime: Long, fstFlags: Int): Int =
        when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.EINVAL)
            is DirectoryDescriptor -> {
                val descriptor = descriptors.get(fd) as DirectoryDescriptor
                wasiResult(
                    wasiSetFileTimes(
                        descriptor.directory.fileSystem,
                        descriptor.path,
                        modifiedTime,
                        accessTime,
                        fstFlags,
                        clock,
                        followSymlinks = true,
                    )
                )
            }
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                wasiResult(
                    wasiSetFileTimes(
                        descriptor.directory.fileSystem,
                        descriptor.path,
                        modifiedTime,
                        accessTime,
                        fstFlags,
                        clock,
                        followSymlinks = true,
                    )
                )
            }
        }

    override fun fdPread(
        memory: Memory,
        fd: Int,
        iovs: Int,
        iovsLen: Int,
        offsetStart: Long,
        nreadPtr: Int,
    ): Int {
        if (offsetStart < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream -> wasiResult(WasiErrno.ESPIPE)
            is OutStream -> wasiResult(WasiErrno.EBADF)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                if (!flagSet(descriptor.rightsBase, WasiRights.FD_READ)) {
                    return wasiResult(WasiErrno.ENOTCAPABLE)
                }
                var offset = offsetStart
                var totalRead = 0
                for (i in 0 until iovsLen) {
                    val base = iovs + (i * 8)
                    val iovBase = memory.readInt(base)
                    val iovLen = memory.readInt(base + 4)
                    if (iovLen < 0) {
                        return wasiResult(WasiErrno.EINVAL)
                    }
                    try {
                        val data = ByteArray(iovLen)
                        val read = descriptor.handle.read(offset, data, 0, iovLen)
                        if (read < 0) {
                            break
                        }
                        memory.write(iovBase, data, 0, read)
                        offset += read
                        totalRead += read
                        if (read < iovLen) {
                            break
                        }
                    } catch (_: IOException) {
                        return wasiResult(WasiErrno.EIO)
                    }
                }
                memory.writeI32(nreadPtr, totalRead)
                wasiResult(WasiErrno.ESUCCESS)
            }
        }
    }

    override fun fdPrestatDirName(memory: Memory, fd: Int, path: Int, pathLen: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        if (descriptor !is PreopenedDirectory) {
            return wasiResult(WasiErrno.EBADF)
        }
        if (pathLen < descriptor.name.size) {
            return wasiResult(WasiErrno.ENAMETOOLONG)
        }
        memory.write(path, descriptor.name)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdPrestatGet(memory: Memory, fd: Int, buf: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        if (descriptor !is PreopenedDirectory) {
            return wasiResult(WasiErrno.EBADF)
        }
        memory.writeI32(buf, 0)
        memory.writeI32(buf + 4, descriptor.name.size)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdPwrite(
        memory: Memory,
        fd: Int,
        iovs: Int,
        iovsLen: Int,
        offsetStart: Long,
        nwrittenPtr: Int,
    ): Int {
        if (offsetStart < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream -> wasiResult(WasiErrno.EBADF)
            is OutStream -> wasiResult(WasiErrno.ESPIPE)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                if (!flagSet(descriptor.rightsBase, WasiRights.FD_WRITE)) {
                    return wasiResult(WasiErrno.ENOTCAPABLE)
                }
                var offset = offsetStart
                var totalWritten = 0
                for (i in 0 until iovsLen) {
                    val base = iovs + (i * 8)
                    val iovBase = memory.readInt(base)
                    val iovLen = memory.readInt(base + 4)
                    if (iovLen < 0) {
                        return wasiResult(WasiErrno.EINVAL)
                    }
                    val data = memory.readBytes(iovBase, iovLen)
                    try {
                        descriptor.handle.write(offset, data, 0, data.size)
                        offset += data.size
                        totalWritten += data.size
                    } catch (_: IOException) {
                        return wasiResult(WasiErrno.EIO)
                    } catch (_: IllegalStateException) {
                        return wasiResult(WasiErrno.ENOTCAPABLE)
                    }
                }
                memory.writeI32(nwrittenPtr, totalWritten)
                wasiResult(WasiErrno.ESUCCESS)
            }
        }
    }

    override fun fdRead(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nreadPtr: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        if (descriptor is OutStream) {
            return wasiResult(WasiErrno.EBADF)
        }
        if (descriptor is DirectoryDescriptor) {
            return wasiResult(WasiErrno.EISDIR)
        }

        var totalRead = 0
        for (i in 0 until iovsLen) {
            val base = iovs + (i * 8)
            val iovBase = memory.readInt(base)
            val iovLen = memory.readInt(base + 4)
            if (iovLen < 0) {
                return wasiResult(WasiErrno.EINVAL)
            }
            try {
                val data = ByteArray(iovLen)
                val read =
                    when (descriptor) {
                        is InStream -> descriptor.read(data)
                        is OpenFile -> {
                            if (!flagSet(descriptor.rightsBase, WasiRights.FD_READ)) {
                                return wasiResult(WasiErrno.ENOTCAPABLE)
                            }
                            descriptor.read(data)
                        }
                    }
                if (read < 0) {
                    break
                }
                memory.write(iovBase, data, 0, read)
                totalRead += read
                if (read < iovLen) {
                    break
                }
            } catch (_: IOException) {
                return wasiResult(WasiErrno.EIO)
            }
        }

        memory.writeI32(nreadPtr, totalRead)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdReaddir(
        memory: Memory,
        dirFd: Int,
        buf: Int,
        bufLen: Int,
        cookieStart: Long,
        bufUsedPtr: Int,
    ): Int {
        if (cookieStart < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }
        val descriptor = descriptors.get(dirFd) ?: return wasiResult(WasiErrno.EBADF)
        if (descriptor !is DirectoryDescriptor) {
            return wasiResult(WasiErrno.ENOTDIR)
        }
        var used = 0
        try {
            val entries =
                listOf(descriptor.path, descriptor.path) + descriptor.directory.fileSystem.list(descriptor.path)
            for ((index, entryPath) in entries.drop(cookieStart.toInt()).withIndex()) {
                val cookie = cookieStart + index + 1L
                val name =
                    when (index + cookieStart.toInt()) {
                        0 -> ".".encodeToByteArray()
                        1 -> "..".encodeToByteArray()
                        else -> entryPath.name.encodeToByteArray()
                    }
                val metadata = descriptor.directory.fileSystem.metadataOrNull(entryPath) ?: continue
                val entry = directoryEntry(
                    cookie,
                    name,
                    fileType(metadata),
                    wasiPathFileId(descriptor.directory.fileSystem, entryPath),
                )
                val writeSize = min(entry.size, bufLen - used)
                memory.write(buf + used, entry, 0, writeSize)
                used += writeSize
                if (used == bufLen) {
                    break
                }
            }
        } catch (_: IOException) {
            return wasiResult(WasiErrno.EIO)
        } catch (_: IllegalArgumentException) {
            return wasiResult(WasiErrno.EINVAL)
        }

        memory.writeI32(bufUsedPtr, used)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdRenumber(from: Int, to: Int): Int {
        val fromDescriptor = descriptors.get(from) ?: return wasiResult(WasiErrno.EBADF)
        if (from == to) {
            return wasiResult(WasiErrno.ESUCCESS)
        }
        descriptors.get(to) ?: return wasiResult(WasiErrno.EBADF)
        descriptors.free(from)
        descriptors.set(to, fromDescriptor)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun fdSeek(memory: Memory, fd: Int, offset: Long, whence: Int, newOffsetPtr: Int): Int {
        if (whence < 0 || whence > 2) {
            return wasiResult(WasiErrno.EINVAL)
        }
        return when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.ESPIPE)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                try {
                    descriptor.position =
                        when (whence) {
                            WasiWhence.SET -> offset
                            WasiWhence.CUR -> descriptor.position + offset
                            WasiWhence.END -> descriptor.handle.size() + offset
                            else -> return wasiResult(WasiErrno.EINVAL)
                        }
                    if (descriptor.position < 0) {
                        return wasiResult(WasiErrno.EINVAL)
                    }
                    memory.writeLong(newOffsetPtr, descriptor.position)
                    wasiResult(WasiErrno.ESUCCESS)
                } catch (_: IOException) {
                    wasiResult(WasiErrno.EIO)
                }
            }
        }
    }

    override fun fdSync(fd: Int): Int = fileSync(fd)

    override fun fdTell(memory: Memory, fd: Int, offsetPtr: Int): Int =
        when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream -> wasiResult(WasiErrno.ESPIPE)
            is DirectoryDescriptor -> wasiResult(WasiErrno.EISDIR)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                memory.writeLong(offsetPtr, descriptor.position)
                wasiResult(WasiErrno.ESUCCESS)
            }
        }

    override fun fdWrite(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nwrittenPtr: Int): Int {
        val descriptor = descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)
        if (descriptor is InStream) {
            return wasiResult(WasiErrno.EBADF)
        }
        if (descriptor is DirectoryDescriptor) {
            return wasiResult(WasiErrno.EISDIR)
        }
        var totalWritten = 0
        for (i in 0 until iovsLen) {
            val base = iovs + (i * 8)
            val iovBase = memory.readInt(base)
            val iovLen = memory.readInt(base + 4)
            if (iovLen < 0) {
                return wasiResult(WasiErrno.EINVAL)
            }
            val data = memory.readBytes(iovBase, iovLen)
            try {
                totalWritten +=
                    when (descriptor) {
                        is OutStream -> descriptor.write(data)
                        is OpenFile -> {
                            if (!flagSet(descriptor.rightsBase, WasiRights.FD_WRITE)) {
                                return wasiResult(WasiErrno.ENOTCAPABLE)
                            }
                            descriptor.write(data)
                        }
                    }
            } catch (_: IOException) {
                return wasiResult(WasiErrno.EIO)
            } catch (_: IllegalStateException) {
                return wasiResult(WasiErrno.ENOTCAPABLE)
            }
        }

        memory.writeI32(nwrittenPtr, totalWritten)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun pathCreateDirectory(dirFd: Int, rawPath: String): Int {
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(pathError(dirFd, WasiErrno.EACCES))
        return try {
            if (resolved.directory.fileSystem.exists(resolved.path)) {
                return wasiResult(WasiErrno.EEXIST)
            }
            resolved.directory.fileSystem.createDirectory(resolved.path, mustCreate = true)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pathFilestatGet(
        memory: Memory,
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        buf: Int,
    ): Int {
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(pathError(dirFd, WasiErrno.EACCES))
        return try {
            val statPath =
                if (flagSet(lookupFlags, WasiLookupFlags.SYMLINK_FOLLOW)) {
                    val root = resolved.directory.fileSystem.canonicalize(resolved.directory.path)
                    val followed = resolved.directory.fileSystem.canonicalize(resolved.path)
                    if (!isInsidePreopen(root, followed)) {
                        return wasiResult(WasiErrno.EACCES)
                    }
                    followed
                } else {
                    resolved.path
                }
            val metadata = resolved.directory.fileSystem.metadataOrNull(statPath)
                ?: return wasiResult(WasiErrno.ENOENT)
            writeFileStat(
                memory,
                buf,
                metadata,
                fileType(metadata),
                wasiPathFileId(resolved.directory.fileSystem, statPath),
                wasiPathAccessTimeNanos(resolved.directory.fileSystem, statPath),
                wasiPathModifiedTimeNanos(resolved.directory.fileSystem, statPath),
            )
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: okio.FileNotFoundException) {
            wasiResult(WasiErrno.ENOENT)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pathFilestatSetTimes(
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        accessTime: Long,
        modifiedTime: Long,
        fstFlags: Int,
    ): Int {
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(pathError(dirFd, WasiErrno.EACCES))
        return wasiResult(
            wasiSetFileTimes(
                resolved.directory.fileSystem,
                resolved.path,
                modifiedTime,
                accessTime,
                fstFlags,
                clock,
                followSymlinks = flagSet(lookupFlags, WasiLookupFlags.SYMLINK_FOLLOW),
            )
        )
    }

    override fun pathLink(
        oldFd: Int,
        lookupFlags: Int,
        rawOldPath: String,
        newFd: Int,
        rawNewPath: String,
    ): Int {
        if (rawNewPath.endsWith("/")) {
            return wasiResult(WasiErrno.ENOENT)
        }
        val old = resolvePath(oldFd, rawOldPath) ?: return wasiResult(pathError(oldFd, WasiErrno.EACCES))
        val new = resolvePath(newFd, rawNewPath) ?: return wasiResult(pathError(newFd, WasiErrno.EACCES))
        if (old.directory.fileSystem !== new.directory.fileSystem) {
            return wasiResult(WasiErrno.EXDEV)
        }
        val oldMetadata = old.directory.fileSystem.metadataOrNull(old.path)
            ?: return wasiResult(WasiErrno.ENOENT)
        if (oldMetadata.isDirectory) {
            return wasiResult(WasiErrno.EPERM)
        }
        return wasiResult(wasiCreateLink(old.directory.fileSystem, old.path, new.path))
    }

    override fun pathOpen(
        memory: Memory,
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        openFlags: Int,
        rightsBase: Long,
        rightsInheriting: Long,
        fdFlags: Int,
        retFdPtr: Int,
    ): Int {
        if (rawPath.endsWith("\u0000")) {
            return wasiResult(WasiErrno.EINVAL)
        }
        val directoryDescriptor = directoryDescriptor(dirFd) ?: return wasiResult(pathError(dirFd, WasiErrno.EPERM))
        if (!flagSet(directoryDescriptor.rightsBase, WasiRights.PATH_OPEN)) {
            return wasiResult(WasiErrno.ENOTCAPABLE)
        }
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(pathError(dirFd, WasiErrno.EPERM))
        val fileSystem = resolved.directory.fileSystem
        return try {
            var openPath = resolved.path
            var metadata = fileSystem.metadataOrNull(openPath)
            if (metadata?.symlinkTarget != null) {
                if (!flagSet(lookupFlags, WasiLookupFlags.SYMLINK_FOLLOW)) {
                    return wasiResult(WasiErrno.ELOOP)
                }
                val root = fileSystem.canonicalize(resolved.directory.path)
                val followed = fileSystem.canonicalize(openPath)
                if (!isInsidePreopen(root, followed)) {
                    return wasiResult(WasiErrno.EACCES)
                }
                openPath = followed
                metadata = fileSystem.metadataOrNull(openPath)
            }
            if (metadata?.isDirectory == true) {
                if (flagSet(rightsBase, WasiRights.FD_WRITE)) {
                    return wasiResult(WasiErrno.EISDIR)
                }
                if (!rightsSubset(rightsBase, directoryDescriptor.rightsInheriting) ||
                    !rightsSubset(rightsInheriting, directoryDescriptor.rightsInheriting)
                ) {
                    return wasiResult(WasiErrno.ENOTCAPABLE)
                }
                val fd =
                    descriptors.allocate(
                        OpenDirectory(
                            resolved.directory,
                            openPath,
                            rightsBase and WasiRights.DIRECTORY_RIGHTS_BASE.toLong(),
                            rightsInheriting,
                        )
                    )
                memory.writeI32(retFdPtr, fd)
                return wasiResult(WasiErrno.ESUCCESS)
            }
            if (flagSet(openFlags, WasiOpenFlags.EXCL) && metadata != null) {
                return wasiResult(WasiErrno.EEXIST)
            }
            if (!flagSet(openFlags, WasiOpenFlags.CREAT) && metadata == null) {
                return wasiResult(WasiErrno.ENOENT)
            }
            if (rawPath.endsWith("/") || flagSet(openFlags, WasiOpenFlags.DIRECTORY)) {
                return wasiResult(WasiErrno.ENOTDIR)
            }
            if (flagSet(openFlags, WasiOpenFlags.CREAT) && metadata == null &&
                !flagSet(directoryDescriptor.rightsBase, WasiRights.PATH_CREATE_FILE)
            ) {
                return wasiResult(WasiErrno.ENOTCAPABLE)
            }
            if (flagSet(openFlags, WasiOpenFlags.TRUNC) &&
                !flagSet(directoryDescriptor.rightsBase, WasiRights.PATH_FILESTAT_SET_SIZE)
            ) {
                return wasiResult(WasiErrno.ENOTCAPABLE)
            }
            if (!rightsSubset(rightsBase, directoryDescriptor.rightsInheriting) ||
                !rightsSubset(rightsInheriting, directoryDescriptor.rightsInheriting)
            ) {
                return wasiResult(WasiErrno.ENOTCAPABLE)
            }
            val read = flagSet(rightsBase, WasiRights.FD_READ)
            val write =
                flagSet(rightsBase, WasiRights.FD_WRITE) ||
                    flagSet(openFlags, WasiOpenFlags.CREAT) ||
                    flagSet(openFlags, WasiOpenFlags.TRUNC)
            val handle =
                if (write) {
                    fileSystem.openReadWrite(
                        openPath,
                        mustCreate = flagSet(openFlags, WasiOpenFlags.EXCL),
                        mustExist = !flagSet(openFlags, WasiOpenFlags.CREAT),
                    )
                } else {
                    fileSystem.openReadOnly(openPath)
                }
            if (flagSet(openFlags, WasiOpenFlags.TRUNC)) {
                handle.resize(0L)
            }
            val fd = descriptors.allocate(OpenFile(resolved.directory, openPath, handle, fdFlags, rightsBase))
            memory.writeI32(retFdPtr, fd)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        } catch (_: IllegalStateException) {
            wasiResult(WasiErrno.ENOTCAPABLE)
        }
    }

    override fun pathReadlink(
        memory: Memory,
        dirFd: Int,
        rawPath: String,
        buf: Int,
        bufLen: Int,
        resultPtr: Int,
    ): Int {
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(WasiErrno.EBADF)
        return try {
            val target = resolved.directory.fileSystem.metadataOrNull(resolved.path)?.symlinkTarget
                ?: return wasiResult(WasiErrno.EINVAL)
            val data = target.name.encodeToByteArray()
            val size = min(data.size, bufLen)
            memory.write(buf, data, 0, size)
            memory.writeI32(resultPtr, size)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pathRemoveDirectory(dirFd: Int, rawPath: String): Int {
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(WasiErrno.EBADF)
        return try {
            val metadata = resolved.directory.fileSystem.metadataOrNull(resolved.path)
                ?: return wasiResult(WasiErrno.ENOENT)
            if (!metadata.isDirectory) {
                return wasiResult(WasiErrno.ENOTDIR)
            }
            if (resolved.directory.fileSystem.listOrNull(resolved.path)?.isNotEmpty() == true) {
                return wasiResult(WasiErrno.ENOTEMPTY)
            }
            resolved.directory.fileSystem.delete(resolved.path, mustExist = true)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pathRename(oldFd: Int, oldRawPath: String, newFd: Int, newRawPath: String): Int {
        val old = resolvePath(oldFd, oldRawPath) ?: return wasiResult(WasiErrno.EBADF)
        val new = resolvePath(newFd, newRawPath) ?: return wasiResult(WasiErrno.EBADF)
        if (old.directory.fileSystem !== new.directory.fileSystem) {
            return wasiResult(WasiErrno.EXDEV)
        }
        return try {
            val oldMetadata = old.directory.fileSystem.metadataOrNull(old.path)
                ?: return wasiResult(WasiErrno.ENOENT)
            val newMetadata = new.directory.fileSystem.metadataOrNull(new.path)
            if (oldMetadata.isDirectory && newMetadata?.isDirectory == true &&
                new.directory.fileSystem.listOrNull(new.path)?.isNotEmpty() == true
            ) {
                return wasiResult(WasiErrno.ENOTEMPTY)
            }
            if (oldMetadata.isDirectory && newMetadata?.isRegularFile == true) {
                return wasiResult(WasiErrno.ENOTDIR)
            }
            if (oldMetadata.isRegularFile && newMetadata?.isDirectory == true) {
                return wasiResult(WasiErrno.EISDIR)
            }
            old.directory.fileSystem.atomicMove(old.path, new.path)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pathSymlink(oldRawPath: String, dirFd: Int, newRawPath: String): Int {
        val target =
            try {
                oldRawPath.toPath(normalize = true)
            } catch (_: IllegalArgumentException) {
                return wasiResult(WasiErrno.EINVAL)
            }
        if (target.isAbsolute) {
            return wasiResult(WasiErrno.EACCES)
        }
        if (newRawPath.endsWith("/")) {
            return wasiResult(
                if (resolvePath(dirFd, newRawPath.dropLastWhile { it == '/' })?.let { it.directory.fileSystem.exists(it.path) } == true) {
                    WasiErrno.EEXIST
                } else {
                    WasiErrno.ENOENT
                },
            )
        }
        val new = resolvePath(dirFd, newRawPath) ?: return wasiResult(WasiErrno.EBADF)
        return try {
            if (new.directory.fileSystem.exists(new.path)) {
                return wasiResult(WasiErrno.EEXIST)
            }
            new.directory.fileSystem.createSymlink(new.path, target)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        } catch (_: IllegalArgumentException) {
            wasiResult(WasiErrno.EINVAL)
        }
    }

    override fun pathUnlinkFile(dirFd: Int, rawPath: String): Int {
        if (rawPath.endsWith("/")) {
            val withoutTrailingSlash = rawPath.dropLastWhile { it == '/' }
            val resolved = resolvePath(dirFd, withoutTrailingSlash) ?: return wasiResult(pathError(dirFd, WasiErrno.EACCES))
            val metadata = resolved.directory.fileSystem.metadataOrNull(resolved.path)
                ?: return wasiResult(WasiErrno.ENOENT)
            return wasiResult(if (metadata.isDirectory) WasiErrno.EISDIR else WasiErrno.ENOTDIR)
        }
        val resolved = resolvePath(dirFd, rawPath) ?: return wasiResult(pathError(dirFd, WasiErrno.EACCES))
        return try {
            val metadata = resolved.directory.fileSystem.metadataOrNull(resolved.path)
                ?: return wasiResult(WasiErrno.ENOENT)
            if (metadata.isDirectory) {
                return wasiResult(WasiErrno.EISDIR)
            }
            resolved.directory.fileSystem.delete(resolved.path, mustExist = true)
            wasiResult(WasiErrno.ESUCCESS)
        } catch (_: IOException) {
            wasiResult(WasiErrno.EIO)
        }
    }

    override fun pollOneoff(
        memory: Memory,
        inPtrStart: Int,
        outPtrStart: Int,
        nsubscriptions: Int,
        neventsPtr: Int,
    ): Int {
        if (nsubscriptions <= 0) {
            return wasiResult(WasiErrno.EINVAL)
        }

        var inPtr = inPtrStart
        var outPtr = outPtrStart
        var nevents = 0
        val clockSubs = mutableListOf<Pair<Long, Long>>()
        val readSubs = mutableListOf<Pair<InStream, Long>>()
        for (i in 0 until nsubscriptions) {
            val userData = memory.readLong(inPtr)
            val eventType = memory.read(inPtr + 8)
            inPtr += 16
            when (eventType) {
                WasiEventType.CLOCK -> {
                    val clockId = memory.readInt(inPtr)
                    var timeout = memory.readLong(inPtr + 8)
                    val flags = memory.readShort(inPtr + 24)
                    if (clockId != WasiClockId.REALTIME && clockId != WasiClockId.MONOTONIC) {
                        return wasiResult(WasiErrno.EINVAL)
                    }
                    if (flagSet(flags.toLong(), WasiSubClockFlags.SUBSCRIPTION_CLOCK_ABSTIME.toLong())) {
                        timeout -= clockTime(clockId)
                    }
                    clockSubs.add(timeout to userData)
                }
                WasiEventType.FD_READ,
                WasiEventType.FD_WRITE -> {
                    val fd = memory.readInt(inPtr)
                    val descriptor = descriptors.get(fd)
                    if (descriptor is InStream && eventType == WasiEventType.FD_READ) {
                        readSubs.add(descriptor to userData)
                    } else if (descriptor is OutStream && eventType == WasiEventType.FD_WRITE) {
                        writeEvent(memory, outPtr, userData, eventType, WasiErrno.ESUCCESS)
                        outPtr += 32
                        nevents++
                    } else {
                        val errno =
                            when (descriptor) {
                                null -> WasiErrno.EBADF
                                is OpenFile -> WasiErrno.ESUCCESS
                                else -> WasiErrno.ENOTSUP
                            }
                        writeEvent(memory, outPtr, userData, eventType, errno)
                        outPtr += 32
                        nevents++
                    }
                }
                else -> return wasiResult(WasiErrno.EINVAL)
            }
            inPtr += 32
        }

        val minTimeout = clockSubs.minOfOrNull { it.first } ?: Long.MAX_VALUE
        val start = wasiMonotonicNanos()
        do {
            for ((stream, userData) in readSubs) {
                try {
                    val available = stream.available()
                    if (available <= 0) {
                        continue
                    }
                    writeEvent(memory, outPtr, userData, WasiEventType.FD_READ, WasiErrno.ESUCCESS)
                    memory.writeLong(outPtr + 16, available.toLong())
                    outPtr += 32
                    nevents++
                } catch (_: IOException) {
                    writeEvent(memory, outPtr, userData, WasiEventType.FD_READ, WasiErrno.EIO)
                    outPtr += 32
                    nevents++
                }
            }

            val elapsed = wasiMonotonicNanos() - start
            for ((timeout, userData) in clockSubs) {
                if (timeout <= elapsed) {
                    writeEvent(memory, outPtr, userData, WasiEventType.CLOCK, WasiErrno.ESUCCESS)
                    outPtr += 32
                    nevents++
                }
            }
            if (nevents == 0 && minTimeout == Long.MAX_VALUE && readSubs.isEmpty()) {
                break
            }
        } while (nevents == 0)

        memory.writeI32(neventsPtr, nevents)
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun procExit(code: Int) {
        if (code == 0 && !throwOnExit0) {
            throw ExecutionCompletedException("proc_exit: 0")
        }
        throw WasiExitException(code)
    }

    override fun procRaise(sig: Int): Int = wasiResult(WasiErrno.ENOSYS)

    override fun randomGet(memory: Memory, buf: Int, bufLen: Int): Int {
        if (bufLen < 0) {
            return wasiResult(WasiErrno.EINVAL)
        }

        var data = ByteArray(min(bufLen, 4096))
        var written = 0
        while (written < bufLen) {
            val size = min(data.size, bufLen - written)
            if (size < data.size) {
                data = ByteArray(size)
            }
            random.nextBytes(data)
            memory.write(buf + written, data, 0, size)
            written += size
        }
        return wasiResult(WasiErrno.ESUCCESS)
    }

    override fun schedYield(): Int = wasiResult(WasiErrno.ESUCCESS)

    override fun sockAccept(sock: Int, fdFlags: Int, roFdPtr: Int): Int = wasiResult(WasiErrno.ENOSYS)

    override fun sockRecv(
        sock: Int,
        riDataPtr: Int,
        riDataLen: Int,
        riFlags: Int,
        roDataLenPtr: Int,
        roFlagsPtr: Int,
    ): Int = wasiResult(WasiErrno.ENOSYS)

    override fun sockSend(sock: Int, siDataPtr: Int, siDataLen: Int, siFlags: Int, retDataLenPtr: Int): Int =
        wasiResult(WasiErrno.ENOSYS)

    override fun sockShutdown(sock: Int, how: Int): Int =
        if (descriptors.get(sock) == null) {
            wasiResult(WasiErrno.EBADF)
        } else {
            wasiResult(WasiErrno.ENOTSOCK)
        }

    fun toHostFunctions(): Array<HostFunction> = WasiPreview1HostFunctions.toHostFunctions(this)

    fun toHostFunctions(moduleName: String): Array<HostFunction> =
        WasiPreview1HostFunctions.toHostFunctions(this, moduleName)

    private fun fileSync(fd: Int): Int =
        when (descriptors.get(fd) ?: return wasiResult(WasiErrno.EBADF)) {
            is InStream,
            is OutStream,
            is DirectoryDescriptor -> wasiResult(WasiErrno.EINVAL)
            is OpenFile -> {
                val descriptor = descriptors.get(fd) as OpenFile
                try {
                    descriptor.handle.flush()
                    wasiResult(WasiErrno.ESUCCESS)
                } catch (_: IOException) {
                    wasiResult(WasiErrno.EIO)
                } catch (_: IllegalStateException) {
                    wasiResult(WasiErrno.ENOTCAPABLE)
                }
            }
        }

    private fun directoryDescriptor(fd: Int): DirectoryDescriptor? {
        val descriptor = descriptors.get(fd) ?: return null
        if (descriptor !is DirectoryDescriptor) {
            return null
        }
        return descriptor
    }

    private fun pathError(fd: Int, invalidPathErrno: WasiErrno): WasiErrno {
        val descriptor = descriptors.get(fd) ?: return WasiErrno.EBADF
        return if (descriptor is DirectoryDescriptor) invalidPathErrno else WasiErrno.ENOTDIR
    }

    private fun resolvePath(fd: Int, rawPath: String): ResolvedPath? {
        val descriptor = directoryDescriptor(fd) ?: return null
        val relativePath = guestRelativePath(descriptor, rawPath) ?: return null
        val raw =
            try {
                relativePath.toPath(normalize = true)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (raw.isAbsolute || raw.segments.firstOrNull() == "..") {
            return null
        }
        val resolved = descriptor.path.resolve(raw, normalize = true)
        if (!isInsidePreopen(descriptor.path, resolved)) {
            return null
        }
        return ResolvedPath(descriptor.directory, resolved)
    }

    private fun guestRelativePath(descriptor: DirectoryDescriptor, rawPath: String): String? {
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith("/") && descriptor !is PreopenedDirectory) {
            return null
        }
        if (descriptor is PreopenedDirectory) {
            val guestRoot = descriptor.name.decodeToString().replace('\\', '/').trimEnd('/')
            if (guestRoot.isNotBlank() && guestRoot != "/") {
                if (normalized == guestRoot) {
                    return "."
                }
                if (normalized.startsWith("$guestRoot/")) {
                    return normalized.substring(guestRoot.length + 1).ifBlank { "." }
                }
            }
        }
        return normalized.ifBlank { "." }
    }

    private fun isInsidePreopen(root: Path, path: Path): Boolean {
        val rootText = root.normalized().toString().trimEnd('/')
        val pathText = path.normalized().toString()
        return pathText == rootText || pathText.startsWith("$rootText/")
    }

    private fun metadataOrNull(descriptor: DirectoryDescriptor): FileMetadata? =
        try {
            descriptor.directory.fileSystem.metadataOrNull(descriptor.path)
        } catch (_: IOException) {
            null
        }

    private fun metadataOrNull(descriptor: OpenFile): FileMetadata? =
        try {
            descriptor.directory.fileSystem.metadataOrNull(descriptor.path)
        } catch (_: IOException) {
            null
        }

    private fun fileType(metadata: FileMetadata): WasiFileType =
        when {
            metadata.symlinkTarget != null -> WasiFileType.SYMBOLIC_LINK
            metadata.isDirectory -> WasiFileType.DIRECTORY
            metadata.isRegularFile -> WasiFileType.REGULAR_FILE
            else -> WasiFileType.UNKNOWN
        }

    private fun writeFileStat(
        memory: Memory,
        buf: Int,
        metadata: FileMetadata?,
        fileType: WasiFileType,
        fileId: Long,
        accessTimeNanos: Long?,
        modifiedTimeNanos: Long?,
    ) {
        memory.writeLong(buf, 0L)
        memory.writeLong(buf + 8, fileId)
        memory.write(buf + 16, ByteArray(8))
        memory.writeByte(buf + 16, fileType.value().toByte())
        memory.writeLong(buf + 24, 1L)
        memory.writeLong(buf + 32, metadata?.size ?: 0L)
        memory.writeLong(buf + 40, accessTimeNanos ?: metadata?.lastAccessedAtMillis?.times(1_000_000L) ?: 0L)
        memory.writeLong(buf + 48, modifiedTimeNanos ?: metadata?.lastModifiedAtMillis?.times(1_000_000L) ?: 0L)
        memory.writeLong(buf + 56, modifiedTimeNanos ?: metadata?.lastModifiedAtMillis?.times(1_000_000L) ?: 0L)
    }

    private fun directoryEntry(cookie: Long, name: ByteArray, fileType: WasiFileType, fileId: Long): ByteArray {
        val entry = ByteArray(24 + name.size)
        putLong(entry, 0, cookie)
        putLong(entry, 8, fileId)
        putInt(entry, 16, name.size)
        entry[20] = fileType.value().toByte()
        name.copyInto(entry, destinationOffset = 24)
        return entry
    }

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    private fun putLong(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            data[offset + i] = (value ushr (i * 8)).toByte()
        }
    }

    private fun flagSet(flags: Int, mask: Int): Boolean = flagSet(flags.toLong(), mask.toLong())

    private fun flagSet(flags: Long, mask: Int): Boolean = flagSet(flags, mask.toLong())

    private fun flagSet(flags: Long, mask: Long): Boolean {
        if (mask <= 0 || mask and (mask - 1) != 0L) {
            throw IllegalArgumentException("mask must be a single bit")
        }
        return flags and mask != 0L
    }

    private fun rightsSubset(requested: Long, available: Long): Boolean =
        requested and available == requested

    @OptIn(ExperimentalTime::class)
    private fun clockTime(clockId: Int): Long =
        when (clockId) {
            WasiClockId.REALTIME -> {
                val now = clock.now()
                now.epochSeconds * 1_000_000_000L + now.nanosecondsOfSecond
            }
            WasiClockId.MONOTONIC -> wasiMonotonicNanos()
            else -> throw IllegalArgumentException("Invalid clockId: $clockId")
        }

    private fun wasiResult(errno: WasiErrno): Int = errno.value()

    companion object {
        private fun writeEvent(
            memory: Memory,
            index: Int,
            userData: Long,
            eventType: Byte,
            errno: WasiErrno,
        ) {
            memory.fill(0, index, index + 32)
            memory.writeLong(index, userData)
            memory.writeShort(index + 8, errno.value().toShort())
            memory.writeByte(index + 10, eventType)
        }
    }

    private sealed interface Descriptor

    private interface DirectoryDescriptor : Descriptor {
        val directory: WasiDirectory
        val path: Path
        var rightsBase: Long
        var rightsInheriting: Long
    }

    private class InStream(
        private val input: RawSource,
        val tty: Boolean,
        private val available: (() -> Int)?,
    ) : Descriptor {
        @Throws(IOException::class)
        fun read(data: ByteArray): Int {
            if (data.isEmpty()) {
                return 0
            }
            val buffer = Buffer()
            val read = input.readAtMostTo(buffer, data.size.toLong())
            if (read < 0) {
                return -1
            }
            val bytes = buffer.readByteArray(read.toInt())
            bytes.copyInto(data, 0, 0, bytes.size)
            return bytes.size
        }

        fun available(): Int = available?.invoke() ?: 0
    }

    private class OutStream(private val output: RawSink, val tty: Boolean) : Descriptor {
        @Throws(IOException::class)
        fun write(data: ByteArray): Int {
            val buffer = Buffer()
            buffer.write(data)
            output.write(buffer, data.size.toLong())
            return data.size
        }
    }

    private class PreopenedDirectory(
        val name: ByteArray,
        override val directory: WasiDirectory,
    ) : DirectoryDescriptor {
        override val path: Path = directory.path
        override var rightsBase: Long = WasiRights.DIRECTORY_RIGHTS_BASE.toLong()
        override var rightsInheriting: Long = rightsBase or WasiRights.FILE_RIGHTS_BASE.toLong()
    }

    private class OpenDirectory(
        override val directory: WasiDirectory,
        override val path: Path,
        override var rightsBase: Long,
        override var rightsInheriting: Long,
    ) : DirectoryDescriptor

    private class OpenFile(
        val directory: WasiDirectory,
        val path: Path,
        val handle: FileHandle,
        var fdFlags: Int,
        var rightsBase: Long,
    ) : Descriptor {
        var position: Long = 0L

        fun read(data: ByteArray): Int {
            val read = handle.read(position, data, 0, data.size)
            if (read > 0) {
                position += read
            }
            return read
        }

        fun write(data: ByteArray): Int {
            val offset =
                if (fdFlags and WasiFdFlags.APPEND != 0) {
                    handle.size()
                } else {
                    position
                }
            handle.write(offset, data, 0, data.size)
            position = offset + data.size
            return data.size
        }
    }

    private data class ResolvedPath(val directory: WasiDirectory, val path: Path)

    private class DescriptorTable {
        private val descriptors = mutableListOf<Descriptor?>()
        private val freeFds = mutableSetOf<Int>()

        fun get(fd: Int): Descriptor? =
            if (fd < 0 || fd >= descriptors.size) {
                null
            } else {
                descriptors[fd]
            }

        fun allocate(descriptor: Descriptor): Int {
            val fd = freeFds.minOrNull()
            if (fd != null) {
                freeFds.remove(fd)
                descriptors[fd] = descriptor
                return fd
            }
            descriptors.add(descriptor)
            return descriptors.lastIndex
        }

        fun free(fd: Int) {
            descriptors[fd] = null
            freeFds.add(fd)
        }

        fun set(fd: Int, descriptor: Descriptor) {
            descriptors[fd] = descriptor
            freeFds.remove(fd)
        }

        fun closeAll() {
            var exception: RuntimeException? = null
            for (descriptor in descriptors) {
                try {
                    if (descriptor is OpenFile) {
                        descriptor.handle.close()
                    }
                } catch (t: Throwable) {
                    if (exception == null) {
                        exception = RuntimeException()
                    }
                    exception.addSuppressed(t)
                }
            }
            exception?.let { throw it }
        }
    }
}
