package uk.shusek.krwa.component

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
    fun rejectsSymlinkCycles() {
        val fileSystem = MemoryWasmFileSystem()
        fileSystem.createSymlink("/a".toPath(), "b".toPath())
        fileSystem.createSymlink("/b".toPath(), "a".toPath())

        assertFailsWith<IOException> {
            fileSystem.canonicalize("/a".toPath())
        }
    }
}
