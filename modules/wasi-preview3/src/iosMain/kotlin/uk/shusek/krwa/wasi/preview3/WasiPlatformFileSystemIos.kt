package uk.shusek.krwa.wasi.preview3

import okio.FileSystem
import okio.FileSystem.Companion.SYSTEM

internal actual fun defaultWasiFileSystem(): FileSystem = FileSystem.SYSTEM
