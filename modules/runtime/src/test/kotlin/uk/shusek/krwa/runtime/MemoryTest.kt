package uk.shusek.krwa.runtime

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.MemoryLimits

class MemoryTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    fun concurrentGrowAndAccessStressTest(name: String, memorySupplier: Supplier<Memory>) {
        val counterAddr = 0
        val growIterations = 200

        val memory = memorySupplier.get()
        memory.writeI32(counterAddr, 0)

        val grower =
            CompletableFuture.runAsync {
                for (i in 0 until growIterations) {
                    if (memory.grow(1) < 0) {
                        throw AssertionError("failed to grow memory")
                    }
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        return@runAsync
                    }
                }
            }

        val counter =
            CompletableFuture.runAsync {
                var i = 0
                while (!grower.isDone) {
                    val read = memory.readI32(counterAddr).toInt()
                    if (read != i) {
                        throw AssertionError("inconsistent count at i=$i, read=$read")
                    }
                    i++
                    memory.writeI32(counterAddr, i)
                }
            }

        CompletableFuture.allOf(counter, grower).get(5, TimeUnit.SECONDS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun overlappingCopyDestAfterSrc(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()

        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        memory.write(100, data, 0, data.size)

        memory.copy(104, 100, 8)

        assertArrayEquals(data, memory.readBytes(104, 8))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun overlappingCopyDestBeforeSrc(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()

        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        memory.write(104, data, 0, data.size)

        memory.copy(100, 104, 8)

        assertArrayEquals(data, memory.readBytes(100, 8))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun overlappingCopyCrossPageBoundary(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()

        val pageSize = 65536
        val src = pageSize - 4
        val data = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        memory.write(src, data, 0, data.size)

        val dest = src + 4
        memory.copy(dest, src, data.size)

        assertArrayEquals(data, memory.readBytes(dest, data.size))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun nonOverlappingCopyCrossPage(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()

        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        memory.write(100, data, 0, data.size)

        val dest = 65536 + 200
        memory.copy(dest, 100, data.size)

        assertArrayEquals(data, memory.readBytes(dest, data.size))
        assertArrayEquals(data, memory.readBytes(100, data.size))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun checkBoundsOverflowShouldTrap(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()
        assertThrows(WasmEngineException::class.java) { memory.readBytes(1, Int.MAX_VALUE) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun utf8Strings(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()
        val text = "za\u017c\u00f3\u0142\u0107"

        memory.writeUtf8String(64, text)
        assertEquals(text, memory.readUtf8String(64, text.encodeToByteArray().size))

        memory.writeUtf8CString(128, text)
        assertEquals(text, memory.readUtf8CString(128))
        assertEquals(0, memory.read(128 + text.encodeToByteArray().size).toInt())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun charsetStringExtensions(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()
        val text = "caf\u00e9"
        val charset = StandardCharsets.ISO_8859_1

        memory.writeString(64, text, charset)
        assertEquals(text, memory.readString(64, text.toByteArray(charset).size, charset))

        memory.writeCString(128, text, charset)
        assertEquals(text, memory.readCString(128, charset))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    fun primitiveAndAtomicOperations(name: String, memorySupplier: Supplier<Memory>) {
        val memory = memorySupplier.get()
        val crossPage = Memory.PAGE_SIZE - 2

        memory.writeI32(crossPage, 0x01020304)
        assertEquals(0x01020304, memory.readInt(crossPage))

        memory.writeLong(crossPage + 8, 0x0102030405060708L)
        assertEquals(0x0102030405060708L, memory.readLong(crossPage + 8))

        memory.writeF32(256, 1.25f)
        assertEquals(1.25f, memory.readFloat(256))

        memory.writeF64(512, 3.5)
        assertEquals(3.5, memory.readDouble(512))

        memory.atomicWriteInt(768, 10)
        assertEquals(10, memory.atomicAddInt(768, 5))
        assertEquals(15, memory.atomicXchgInt(768, 20))
        assertEquals(20, memory.atomicCmpxchgInt(768, 20, 30))
        assertEquals(30, memory.atomicReadInt(768))
    }

    @org.junit.jupiter.api.Test
    fun portableMemoryRejectsSharedMemory() {
        assertThrows(WasmEngineException::class.java) { PortableMemory(MemoryLimits(1, 1, true)) }
    }

    companion object {
        @JvmStatic
        fun memoryImplementations(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "ByteArrayMemory",
                    Supplier<Memory> { ByteArrayMemory(MemoryLimits(2, 2)) },
                ),
                Arguments.of(
                    "ByteBufferMemory",
                    Supplier<Memory> { ByteBufferMemory(MemoryLimits(2, 2)) },
                ),
                Arguments.of(
                    "PortableMemory",
                    Supplier<Memory> { PortableMemory(MemoryLimits(2, 2)) },
                ),
            )

        @JvmStatic
        fun growableMemoryImplementations(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "ByteArrayMemory",
                    Supplier<Memory> { ByteArrayMemory(MemoryLimits(1, 1000, true)) },
                ),
                Arguments.of(
                    "ByteBufferMemory",
                    Supplier<Memory> { ByteBufferMemory(MemoryLimits(1, 1000, true)) },
                ),
            )
    }
}
