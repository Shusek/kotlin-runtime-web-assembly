package uk.shusek.krwa.wasi

import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path as JavaPath
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.time.Clock
import okio.FileSystem
import okio.Path

internal actual fun wasiPathFileId(fileSystem: FileSystem, path: Path): Long {
    val javaPath = javaPath(fileSystem, path)
    return try {
        val attributes = Files.readAttributes(javaPath, "unix:*", LinkOption.NOFOLLOW_LINKS)
        (attributes["ino"] as Number).toLong()
    } catch (_: Exception) {
        path.normalized().toString().hashCode().toLong()
    }
}

internal actual fun wasiPathAccessTimeNanos(fileSystem: FileSystem, path: Path): Long? =
    try {
        Files.readAttributes(
            javaPath(fileSystem, path),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
            .lastAccessTime()
            .to(NANOSECONDS)
    } catch (_: Exception) {
        null
    }

internal actual fun wasiPathModifiedTimeNanos(fileSystem: FileSystem, path: Path): Long? =
    try {
        Files.readAttributes(
            javaPath(fileSystem, path),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
            .lastModifiedTime()
            .to(NANOSECONDS)
    } catch (_: Exception) {
        null
    }

internal actual fun wasiSetFileTimes(
    fileSystem: FileSystem,
    path: Path,
    modifiedTime: Long,
    accessTime: Long,
    flags: Int,
    clock: Clock,
    followSymlinks: Boolean,
): WasiErrno {
    val modifiedSet = flagSet(flags, WasiFstFlags.MTIM)
    val modifiedNow = flagSet(flags, WasiFstFlags.MTIM_NOW)
    val accessSet = flagSet(flags, WasiFstFlags.ATIM)
    val accessNow = flagSet(flags, WasiFstFlags.ATIM_NOW)
    if ((modifiedSet && modifiedNow) || (accessSet && accessNow)) {
        return WasiErrno.EINVAL
    }

    val modified = toFileTime(modifiedTime, modifiedSet, modifiedNow, clock)
    val access = toFileTime(accessTime, accessSet, accessNow, clock)
    val linkOptions =
        if (followSymlinks) {
            emptyArray()
        } else {
            arrayOf(LinkOption.NOFOLLOW_LINKS)
        }
    return try {
        Files.getFileAttributeView(javaPath(fileSystem, path), BasicFileAttributeView::class.java, *linkOptions)
            .setTimes(modified, access, null)
        WasiErrno.ESUCCESS
    } catch (_: java.io.IOException) {
        WasiErrno.EIO
    }
}

internal actual fun wasiCreateLink(fileSystem: FileSystem, oldPath: Path, newPath: Path): WasiErrno =
    try {
        Files.createLink(javaPath(fileSystem, newPath), javaPath(fileSystem, oldPath))
        WasiErrno.ESUCCESS
    } catch (_: UnsupportedOperationException) {
        WasiErrno.ENOTSUP
    } catch (_: FileAlreadyExistsException) {
        WasiErrno.EEXIST
    } catch (_: NoSuchFileException) {
        WasiErrno.ENOENT
    } catch (_: DirectoryNotEmptyException) {
        WasiErrno.ENOTEMPTY
    } catch (_: java.io.IOException) {
        WasiErrno.EIO
    }

private fun javaPath(fileSystem: FileSystem, path: Path): JavaPath =
    if (fileSystem is NioWasiFileSystem) {
        fileSystem.toJavaPath(path)
    } else {
        JavaPath.of(path.toString())
    }

private fun toFileTime(time: Long, set: Boolean, now: Boolean, clock: Clock): FileTime? {
    if (set) {
        return FileTime.from(time, NANOSECONDS)
    }
    if (now) {
        val instant = clock.now()
        return FileTime.from(java.time.Instant.ofEpochSecond(instant.epochSeconds, instant.nanosecondsOfSecond.toLong()))
    }
    return null
}

private fun flagSet(flags: Int, mask: Int): Boolean = flags and mask != 0
