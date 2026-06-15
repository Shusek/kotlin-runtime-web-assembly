package uk.shusek.krwa.wasi

import kotlin.time.Clock
import okio.FileSystem
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
): WasiErrno {
    val modifiedSet = flagSet(flags, WasiFstFlags.MTIM)
    val modifiedNow = flagSet(flags, WasiFstFlags.MTIM_NOW)
    val accessSet = flagSet(flags, WasiFstFlags.ATIM)
    val accessNow = flagSet(flags, WasiFstFlags.ATIM_NOW)
    return if ((modifiedSet && modifiedNow) || (accessSet && accessNow)) {
        WasiErrno.EINVAL
    } else {
        WasiErrno.ENOTSUP
    }
}

internal actual fun wasiCreateLink(fileSystem: FileSystem, oldPath: Path, newPath: Path): WasiErrno =
    WasiErrno.ENOTSUP

private fun flagSet(flags: Int, mask: Int): Boolean = flags and mask != 0
