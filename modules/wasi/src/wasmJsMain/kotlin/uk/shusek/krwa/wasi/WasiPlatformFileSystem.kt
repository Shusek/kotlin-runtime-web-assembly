package uk.shusek.krwa.wasi

import okio.FileSystem

internal actual fun defaultWasiFileSystem(): FileSystem = UnsupportedWasmFileSystem
