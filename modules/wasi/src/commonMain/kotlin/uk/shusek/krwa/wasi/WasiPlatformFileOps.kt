package uk.shusek.krwa.wasi

import kotlin.time.Clock
import okio.FileSystem
import okio.Path

internal expect fun wasiPathFileId(fileSystem: FileSystem, path: Path): Long

internal expect fun wasiPathAccessTimeNanos(fileSystem: FileSystem, path: Path): Long?

internal expect fun wasiPathModifiedTimeNanos(fileSystem: FileSystem, path: Path): Long?

internal expect fun wasiSetFileTimes(
    fileSystem: FileSystem,
    path: Path,
    modifiedTime: Long,
    accessTime: Long,
    flags: Int,
    clock: Clock,
    followSymlinks: Boolean,
): WasiErrno

internal expect fun wasiCreateLink(fileSystem: FileSystem, oldPath: Path, newPath: Path): WasiErrno
