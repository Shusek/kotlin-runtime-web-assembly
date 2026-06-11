package uk.shusek.krwa.wasi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.IOException
import okio.Path.Companion.toPath

class MemoryWasmFileSystemTest {
    @Test
    fun supportsSymlinkMetadataAndFollowedIo() {
        val fileSystem = MemoryWasmFileSystem()
        fileSystem.createDirectory("/tmp".toPath())
        fileSystem.write("/tmp/target.txt".toPath()) {
            writeUtf8("target")
        }

        fileSystem.createSymlink("/tmp/link.txt".toPath(), "target.txt".toPath())

        assertEquals("target.txt", fileSystem.metadata("/tmp/link.txt".toPath()).symlinkTarget.toString())
        assertEquals("target", fileSystem.read("/tmp/link.txt".toPath()) { readUtf8() })
        assertEquals(
            fileSystem.canonicalize("/tmp/target.txt".toPath()),
            fileSystem.canonicalize("/tmp/link.txt".toPath()),
        )
    }

    @Test
    fun setTimesCanUpdateSymlinkOrFollowedTarget() {
        val fileSystem = MemoryWasmFileSystem()
        fileSystem.createDirectory("/tmp".toPath())
        fileSystem.write("/tmp/target.txt".toPath()) {
            writeUtf8("target")
        }
        fileSystem.createSymlink("/tmp/link.txt".toPath(), "target.txt".toPath())

        fileSystem.setTimes("/tmp/link.txt".toPath(), accessMillis = null, modificationMillis = 2_000L, followSymlinks = false)
        fileSystem.setTimes("/tmp/link.txt".toPath(), accessMillis = null, modificationMillis = 3_000L, followSymlinks = true)

        assertEquals(2_000L, fileSystem.metadata("/tmp/link.txt".toPath()).lastModifiedAtMillis)
        assertEquals(3_000L, fileSystem.metadata("/tmp/target.txt".toPath()).lastModifiedAtMillis)
    }

    @Test
    fun rejectsSymlinkCycles() {
        val fileSystem = MemoryWasmFileSystem()
        fileSystem.createSymlink("/a".toPath(), "b".toPath())
        fileSystem.createSymlink("/b".toPath(), "a".toPath())

        assertFailsWith<IOException> {
            fileSystem.canonicalize("/a".toPath())
        }
    }
}
