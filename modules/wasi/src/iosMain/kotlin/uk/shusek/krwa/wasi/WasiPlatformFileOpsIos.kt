@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.shusek.krwa.wasi

import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlin.time.Clock
import okio.FileSystem
import okio.Path
import platform.posix.EACCES
import platform.posix.EEXIST
import platform.posix.EINVAL
import platform.posix.EIO
import platform.posix.ELOOP
import platform.posix.EMLINK
import platform.posix.ENAMETOOLONG
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.ENOTEMPTY
import platform.posix.ENOTSUP
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.EXDEV
import platform.posix.errno
import platform.posix.link
import platform.posix.lstat
import platform.posix.lutimes
import platform.posix.stat
import platform.posix.timeval
import platform.posix.utimes

private const val NANOS_PER_SECOND = 1_000_000_000L

internal actual fun wasiPathFileId(fileSystem: FileSystem, path: Path): Long =
    path.normalized().toString().hashCode().toLong()

internal actual fun wasiPathAccessTimeNanos(fileSystem: FileSystem, path: Path): Long? =
    fileSystem.metadataOrNull(path)?.lastAccessedAtMillis?.times(1_000_000L)

internal actual fun wasiPathModifiedTimeNanos(fileSystem: FileSystem, path: Path): Long? =
    fileSystem.metadataOrNull(path)?.lastModifiedAtMillis?.times(1_000_000L)

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
    val posixPath = wasiPosixPath(fileSystem, path) ?: return WasiErrno.ENOTSUP
    val modified = toPosixTime(modifiedTime, modifiedSet, modifiedNow, clock)
    val access = toPosixTime(accessTime, accessSet, accessNow, clock)
    return memScoped {
        val current = alloc<stat>()
        val statResult =
            if (followSymlinks) {
                stat(posixPath, current.ptr)
            } else {
                lstat(posixPath, current.ptr)
            }
        if (statResult != 0) {
            return@memScoped wasiErrno()
        }

        val times = allocArray<timeval>(2)
        fillTimeval(times[0], access, current.st_atimespec.tv_sec, current.st_atimespec.tv_nsec)
        fillTimeval(times[1], modified, current.st_mtimespec.tv_sec, current.st_mtimespec.tv_nsec)
        val result =
            if (followSymlinks) {
                utimes(posixPath, times)
            } else {
                lutimes(posixPath, times)
            }
        if (result == 0) {
            WasiErrno.ESUCCESS
        } else {
            wasiErrno()
        }
    }
}

internal actual fun wasiCreateLink(fileSystem: FileSystem, oldPath: Path, newPath: Path): WasiErrno =
    when (val oldPosixPath = wasiPosixPath(fileSystem, oldPath)) {
        null -> WasiErrno.ENOTSUP
        else -> {
            val newPosixPath = wasiPosixPath(fileSystem, newPath)
            if (newPosixPath == null) {
                WasiErrno.ENOTSUP
            } else if (link(oldPosixPath, newPosixPath) == 0) {
                WasiErrno.ESUCCESS
            } else {
                wasiErrno()
            }
        }
    }

private fun wasiPosixPath(fileSystem: FileSystem, path: Path): String? =
    if (fileSystem == FileSystem.SYSTEM) {
        path.toString()
    } else {
        null
    }

private data class PosixTime(val seconds: Long, val nanoseconds: Long)

private fun toPosixTime(time: Long, set: Boolean, now: Boolean, clock: Clock): PosixTime? {
    if (set) {
        return PosixTime(time / NANOS_PER_SECOND, time % NANOS_PER_SECOND)
    }
    if (now) {
        val instant = clock.now()
        return PosixTime(instant.epochSeconds, instant.nanosecondsOfSecond.toLong())
    }
    return null
}

private fun fillTimeval(
    target: timeval,
    time: PosixTime?,
    fallbackSeconds: Long,
    fallbackNanoseconds: Long,
) {
    val seconds = time?.seconds ?: fallbackSeconds
    val nanoseconds = time?.nanoseconds ?: fallbackNanoseconds
    target.tv_sec = seconds.convert()
    target.tv_usec = (nanoseconds / 1_000L).convert()
}

private fun wasiErrno(): WasiErrno =
    when (errno) {
        EACCES -> WasiErrno.EACCES
        EEXIST -> WasiErrno.EEXIST
        EINVAL -> WasiErrno.EINVAL
        EIO -> WasiErrno.EIO
        ELOOP -> WasiErrno.ELOOP
        EMLINK -> WasiErrno.EMLINK
        ENAMETOOLONG -> WasiErrno.ENAMETOOLONG
        ENOENT -> WasiErrno.ENOENT
        ENOTDIR -> WasiErrno.ENOTDIR
        ENOTEMPTY -> WasiErrno.ENOTEMPTY
        ENOTSUP -> WasiErrno.ENOTSUP
        EPERM -> WasiErrno.EPERM
        EROFS -> WasiErrno.EROFS
        EXDEV -> WasiErrno.EXDEV
        else -> WasiErrno.EIO
    }

private fun flagSet(flags: Int, mask: Int): Boolean = flags and mask != 0
