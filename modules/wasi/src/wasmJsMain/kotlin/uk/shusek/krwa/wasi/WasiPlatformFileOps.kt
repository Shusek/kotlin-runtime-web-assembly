@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.wasi

import kotlin.time.Clock
import okio.FileSystem
import okio.IOException
import okio.Path

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
    if (fileSystem !is MemoryWasmFileSystem) {
        return WasiErrno.ENOTSUP
    }
    val nowMillis = clock.now().toEpochMilliseconds()
    val modifiedMillis =
        when {
            modifiedSet -> modifiedTime / 1_000_000L
            modifiedNow -> nowMillis
            else -> null
        }
    val accessMillis =
        when {
            accessSet -> accessTime / 1_000_000L
            accessNow -> nowMillis
            else -> null
        }
    return try {
        fileSystem.setTimes(path, accessMillis, modifiedMillis, followSymlinks)
        WasiErrno.ESUCCESS
    } catch (_: IOException) {
        WasiErrno.EIO
    } catch (_: IllegalArgumentException) {
        WasiErrno.EINVAL
    }
}

internal actual fun wasiCreateLink(fileSystem: FileSystem, oldPath: Path, newPath: Path): WasiErrno =
    if (fileSystem is MemoryWasmFileSystem) {
        try {
            if (fileSystem.metadataOrNull(oldPath) == null) {
                WasiErrno.ENOENT
            } else if (fileSystem.metadataOrNull(newPath) != null) {
                WasiErrno.EEXIST
            } else {
                fileSystem.createHardLink(oldPath, newPath)
                WasiErrno.ESUCCESS
            }
        } catch (_: IOException) {
            WasiErrno.EIO
        } catch (_: IllegalArgumentException) {
            WasiErrno.EINVAL
        }
    } else {
        WasiErrno.ENOTSUP
    }

private fun flagSet(flags: Int, mask: Int): Boolean = flags and mask != 0
