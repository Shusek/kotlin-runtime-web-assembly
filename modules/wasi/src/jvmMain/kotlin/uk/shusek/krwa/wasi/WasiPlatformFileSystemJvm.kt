package uk.shusek.krwa.wasi

import okio.FileSystem
import okio.FileSystem.Companion.SYSTEM

internal actual fun defaultWasiFileSystem(): FileSystem = FileSystem.SYSTEM
