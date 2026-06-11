package uk.shusek.krwa.wasi

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystem
import java.nio.file.Files.createDirectory
import java.nio.file.Files.createSymbolicLink
import java.nio.file.Files.createTempDirectory
import java.nio.file.Files.deleteIfExists
import java.nio.file.Files.walk
import java.nio.file.Files.writeString
import java.nio.file.Path
import java.util.Comparator
import java.util.Random
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.wasm.types.MemoryLimits

@Timeout(10)
class WasiPreview1Test {
    @Test
    fun wasiRandom() {
        val seed = 0x12345678
        val wasi =
            WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withRandom(Random(seed.toLong())).build())
                .build()

        val memory = ByteBufferMemory(MemoryLimits(8, 8))
        assertEquals(0, wasi.randomGet(memory, 0, 123_456))
        assertEquals(0, wasi.randomGet(memory, 222_222, 87_654))

        val random = Random(seed.toLong())
        val first = ByteArray(123_456)
        random.nextBytes(first)
        val second = ByteArray(87_654)
        random.nextBytes(second)

        assertArrayEquals(first, memory.readBytes(0, 123_456))
        assertArrayEquals(second, memory.readBytes(222_222, 87_654))
    }

    @Test
    fun wasiPositionedWriteWithAppendShouldSucceed() {
        newZeroFs().use { fs ->
            val dir = "fs-tests.dir"
            val source =
                File("../../build/external-testsuites/wasi/tests/c/testsuite/wasm32-wasip1")
                    .toPath()
                    .resolve(dir)
            val target = fs.getPath(dir)
            Files.copyDirectory(source, target)

            wasiWithDirectory(target.toString(), target).use { wasi ->
                val memory = ByteBufferMemory(MemoryLimits(1))

                val fdPtr = 0
                var result =
                    wasi.pathOpen(
                        memory,
                        3,
                        WasiLookupFlags.SYMLINK_FOLLOW,
                        "pwrite.cleanup",
                        WasiOpenFlags.CREAT,
                        WasiRights.FD_WRITE.toLong(),
                        0,
                        WasiFdFlags.APPEND,
                        fdPtr,
                    )
                assertEquals(WasiErrno.ESUCCESS.value(), result)

                val fd = memory.readInt(fdPtr)
                assertEquals(4, fd)

                result = wasi.fdPwrite(memory, fd, 0, 0, 0, 0)
                assertEquals(WasiErrno.ESUCCESS.value(), result)
            }
        }
    }

    @Test
    fun wasiReadLink() {
        newZeroFs().use { fs ->
            val root = fs.getPath("test")
            createDirectory(root)
            createSymbolicLink(root.resolve("abc"), fs.getPath("xyz"))
            wasiWithDirectory(root.toString(), root).use { wasi ->
                val memory = ByteBufferMemory(MemoryLimits(1))
                val bufPtr = 0
                val bufUsedPtr = 16

                val result = wasi.pathReadlink(memory, 3, "abc", bufPtr, 16, bufUsedPtr)
                assertEquals(WasiErrno.ESUCCESS.value(), result)

                val length = memory.readInt(bufUsedPtr)
                assertEquals(3, length)
                val name = memory.readString(bufPtr, length)
                assertEquals("xyz", name)
            }
        }
    }

    @Test
    fun wasiPollOneoffNoSubscriptions() {
        val wasi = WasiPreview1.builder().build()
        val memory = ByteBufferMemory(MemoryLimits(0))
        val result = wasi.pollOneoff(memory, 0, 0, 0, 0)
        assertEquals(WasiErrno.EINVAL.value(), result)
    }

    @Test
    fun wasiPollOneoffStdinNoData() {
        val stdin = ByteArrayInputStream("".toByteArray(UTF_8))
        val wasiOpts = WasiOptions.builder().withStdin(stdin).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteBufferMemory(MemoryLimits(1))
        val nsubscriptions = 2
        val neventsPtr = 0
        val inPtr = 4
        val outPtr = inPtr + nsubscriptions * 48
        var inOffset = inPtr
        memory.writeLong(inOffset, 0x8888_7777_6666_5555UL.toLong()) // userdata
        memory.writeByte(inOffset + 8, WasiEventType.CLOCK)
        memory.writeI32(inOffset + 16, WasiClockId.REALTIME)
        memory.writeLong(inOffset + 16 + 8, MILLISECONDS.toNanos(200))
        memory.writeLong(inOffset + 16 + 16, 0) // precision
        memory.writeShort(inOffset + 16 + 24, 0.toShort())
        inOffset += 48
        memory.writeLong(inOffset, 0xAAAA_BBBB_CCCC_DDDDUL.toLong()) // userdata
        memory.writeByte(inOffset + 8, WasiEventType.FD_READ)
        memory.writeI32(inOffset + 16, 0) // fd
        val result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), result)
        assertEquals(1, memory.readInt(neventsPtr))
        assertEquals(0x8888_7777_6666_5555UL.toLong(), memory.readLong(outPtr))
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8).toInt())
        assertEquals(WasiEventType.CLOCK, memory.read(outPtr + 10))
    }

    @Test
    fun wasPollOneoffStdinWithData() {
        val stdin = ByteArrayInputStream("Hello, World!".toByteArray(UTF_8))
        val wasiOpts = WasiOptions.builder().withStdin(stdin).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteBufferMemory(MemoryLimits(1))
        val nsubscriptions = 1
        val neventsPtr = 0
        val inPtr = 4
        val outPtr = inPtr + nsubscriptions * 48
        val inOffset = inPtr
        memory.writeLong(inOffset, 0x8888_7777_6666_5555UL.toLong()) // userdata
        memory.writeByte(inOffset + 8, WasiEventType.FD_READ)
        memory.writeI32(inOffset + 16, 0) // fd
        val result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), result)
        assertEquals(1, memory.readInt(neventsPtr))
        assertEquals(0x8888_7777_6666_5555UL.toLong(), memory.readLong(outPtr))
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8).toInt())
        assertEquals(WasiEventType.FD_READ, memory.read(outPtr + 10))
    }

    @Test
    fun wasiPollOneoffRegularFile() {
        try {
            newZeroFs().use { fs ->
                val root = fs.getPath("test")
                createDirectory(root)
                val file = root.resolve("hello.txt")
                writeString(file, "Hello, World!")
                wasiWithDirectory(root.toString(), root).use { wasi ->
                    val memory = ByteBufferMemory(MemoryLimits(1))
                    val fdPtr = 1024
                    var result = wasi.pathOpen(memory, 3, 0, "hello.txt", 0, 0, 0, 0, fdPtr)
                    assertEquals(WasiErrno.ESUCCESS.value(), result)
                    val fd = memory.readInt(fdPtr)
                    assertEquals(4, fd)

                    val nsubscriptions = 2
                    val neventsPtr = 0
                    val inPtr = 4
                    val outPtr = inPtr + nsubscriptions * 48
                    var inOffset = inPtr
                    memory.writeLong(inOffset, 0x8888_7777_6666_5555UL.toLong()) // userdata
                    memory.writeByte(inOffset + 8, WasiEventType.FD_READ)
                    memory.writeI32(inOffset + 16, fd)
                    inOffset += 48
                    memory.writeLong(inOffset, 0xAAAA_BBBB_CCCC_DDDDUL.toLong()) // userdata
                    memory.writeByte(inOffset + 8, WasiEventType.FD_WRITE)
                    memory.writeI32(inOffset + 16, fd)
                    result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr)
                    assertEquals(WasiErrno.ESUCCESS.value(), result)
                    assertEquals(2, memory.readInt(neventsPtr))
                    var out = outPtr
                    assertEquals(0x8888_7777_6666_5555UL.toLong(), memory.readLong(out))
                    assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(out + 8).toInt())
                    assertEquals(WasiEventType.FD_READ, memory.read(out + 10))
                    out += 32
                    assertEquals(0xAAAA_BBBB_CCCC_DDDDUL.toLong(), memory.readLong(out))
                    assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(out + 8).toInt())
                    assertEquals(WasiEventType.FD_WRITE, memory.read(out + 10))
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    @Test
    fun wasiPollOneoffClockAbstime() {
        val deadline = System.nanoTime() + MILLISECONDS.toNanos(25)
        val wasi = WasiPreview1.builder().build()
        val memory = ByteBufferMemory(MemoryLimits(1))
        val nsubscriptions = 1
        val neventsPtr = 0
        val inPtr = 4
        val outPtr = inPtr + nsubscriptions * 48
        val inOffset = inPtr
        memory.writeLong(inOffset, 0x8888_7777_6666_5555UL.toLong()) // userdata
        memory.writeByte(inOffset + 8, WasiEventType.CLOCK)
        memory.writeI32(inOffset + 16, WasiClockId.MONOTONIC)
        memory.writeLong(inOffset + 16 + 8, deadline)
        memory.writeLong(inOffset + 16 + 16, 0) // precision
        memory.writeShort(
            inOffset + 16 + 24,
            WasiSubClockFlags.SUBSCRIPTION_CLOCK_ABSTIME.toShort(),
        )
        val result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), result)
        assertEquals(1, memory.readInt(neventsPtr))
        assertEquals(0x8888_7777_6666_5555UL.toLong(), memory.readLong(outPtr))
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8).toInt())
        assertEquals(WasiEventType.CLOCK, memory.read(outPtr + 10))
        assertTrue(System.nanoTime() >= deadline)
    }

    @Test
    fun fdReadNegativeIovLenShouldReturnEinval() {
        newZeroFs().use { fs ->
            val root = fs.getPath("test")
            createDirectory(root)
            writeString(root.resolve("hello.txt"), "Hello")
            wasiWithDirectory(root.toString(), root).use { wasi ->
                val memory = ByteBufferMemory(MemoryLimits(1))

                val fdPtr = 0
                var result = wasi.pathOpen(memory, 3, 0, "hello.txt", 0, 0, 0, 0, fdPtr)
                assertEquals(WasiErrno.ESUCCESS.value(), result)
                val fd = memory.readInt(fdPtr)

                val iovs = 100
                val iovBase = 200
                memory.writeI32(iovs, iovBase)
                memory.writeI32(iovs + 4, 0x80000001.toInt()) // negative in signed int
                val nreadPtr = 300
                result = wasi.fdRead(memory, fd, iovs, 1, nreadPtr)
                assertEquals(WasiErrno.EINVAL.value(), result)
            }
        }
    }

    @Test
    fun symlinkEscapeShouldReturnEacces() {
        val tmpDir = createTempDirectory("wasi-symlink-test")
        try {
            val sandbox = tmpDir.resolve("sandbox")
            createDirectory(sandbox)
            val outside = tmpDir.resolve("outside")
            createDirectory(outside)
            writeString(outside.resolve("secret.txt"), "secret")
            createSymbolicLink(sandbox.resolve("escape"), outside.resolve("secret.txt"))

            wasiWithDirectory(sandbox.toString(), sandbox).use { wasi ->
                val memory = ByteBufferMemory(MemoryLimits(1))
                val buf = 0
                val result =
                    wasi.pathFilestatGet(memory, 3, WasiLookupFlags.SYMLINK_FOLLOW, "escape", buf)
                assertEquals(WasiErrno.EACCES.value(), result)
            }
        } finally {
            walk(tmpDir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        deleteIfExists(path)
                    } catch (_: IOException) {
                        // Best-effort cleanup.
                    }
                }
            }
        }
    }

    private companion object {
        private fun newZeroFs(): FileSystem =
            ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())

        private fun wasiWithDirectory(guest: String, host: Path): WasiPreview1 {
            val options = WasiOptions.builder().withDirectory(guest, host).build()
            return WasiPreview1.builder().withOptions(options).build()
        }
    }
}
