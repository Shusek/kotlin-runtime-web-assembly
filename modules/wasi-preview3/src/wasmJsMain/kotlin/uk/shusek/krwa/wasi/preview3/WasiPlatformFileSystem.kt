package uk.shusek.krwa.wasi.preview3

import okio.FileSystem

internal actual fun defaultWasiFileSystem(): FileSystem = UnsupportedWasmFileSystem
