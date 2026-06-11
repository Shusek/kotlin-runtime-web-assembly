package uk.shusek.krwa.wasi

import okio.FileSystem
import okio.Path

class WasiDirectory internal constructor(
    val fileSystem: FileSystem,
    val path: Path,
)
