package uk.shusek.krwa.wasi.preview3

import java.nio.file.Files
import java.util.Comparator
import kotlin.time.Clock as KotlinClock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant as KotlinInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.component.ComponentModelException
import uk.shusek.krwa.component.KotlinWitBindings
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WasiPreview3CanonicalContext
import uk.shusek.krwa.component.WitFuture
import uk.shusek.krwa.component.WitPackage
import uk.shusek.krwa.component.WitStream

private const val STREAM_BLOCKED_STATUS: Long = 0xffff_ffffL

class KotlinWasiPreview3Test {
    @Test
    fun resourceBudgetConfiguresUnderlyingPreview3Limits() {
        val runtime =
            KotlinWasiPreview3.builder()
                .withResourceBudget(
                    parallelism = 1,
                    maxPendingFutures = 1,
                    maxPendingStreams = 1,
                    maxWaitables = 1,
                )
                .build()

        runtime.wasi.pendingFuture<Any?>()
        assertThrows(ComponentModelException::class.java) {
            runtime.wasi.pendingFuture<Any?>()
        }

        runtime.byteStream(byteArrayOf(1))
        assertThrows(ComponentModelException::class.java) {
            runtime.byteStream(byteArrayOf(2))
        }

        runtime.close()
    }

    @Test
    fun awaitsCompletedWitFuture() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val future = runtime.completed("ready")

        assertEquals("ready", runtime.await(future))
        assertEquals("ready", runtime.wasi.await(future))
    }

    @Test
    fun exposesWitFutureAsDeferred() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val future = runtime.completed(42)

        assertEquals(42, future.asDeferred(runtime.wasi, this).await())
    }

    @Test
    fun awaitsCanonicalFutureCompletedByWriter() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val pair = runtime.wasi.futureNew()
        val readable = pair and 0xffff_ffffL
        val writable = pair ushr 32
        val payloadType = WitPackage.TypeRef.primitive("string")

        val deferred = async { runtime.await(WitFuture.of<String>(readable)) }
        delay(1)

        assertFalse(deferred.isCompleted)

        val status =
            runtime.wasi.futureWrite(FuturePayloadContext("from-writer"), writable, 0, payloadType)

        assertEquals(0L, status)
        assertTrue(runtime.wasi.futureCompleted(readable))
        assertEquals("from-writer", deferred.await())
    }

    @Test
    fun awaitReadableStreamWakesWhenCanonicalWriterWrites() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val payloadType = WitPackage.TypeRef.primitive("u8")
        val pair = runtime.wasi.streamNew(payloadType)
        val readable = pair and 0xffff_ffffL
        val writable = pair ushr 32
        val stream = WitStream.of<UByte>(readable)
        val bytes = byteArrayOf(10, 20, 30)

        val deferred =
            async {
                stream.awaitReadable(runtime.wasi)
                stream.asByteArray(runtime.wasi)
            }
        delay(1)

        assertFalse(deferred.isCompleted)

        val status =
            runtime.wasi.streamWrite(
                ByteStreamPayloadContext(bytes),
                writable,
                0,
                bytes.size,
                payloadType,
            )

        assertEquals(bytes.size.toLong() shl 4, status)
        assertArrayEquals(bytes, deferred.await())
    }

    @Test
    fun awaitReadableStreamWakesWhenWritableEndDrops() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val pair = runtime.wasi.streamNew(WitPackage.TypeRef.primitive("u8"))
        val readable = pair and 0xffff_ffffL
        val writable = pair ushr 32
        val stream = WitStream.of<UByte>(readable)

        val deferred =
            async {
                stream.awaitReadable(runtime.wasi)
                stream.asByteArray(runtime.wasi)
            }
        delay(1)

        assertFalse(deferred.isCompleted)

        runtime.wasi.streamDropWritable(writable)

        assertArrayEquals(ByteArray(0), deferred.await())
    }

    @Test
    fun awaitReadableStreamFailsWhenReadIsCancelled() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val pair = runtime.wasi.streamNew(WitPackage.TypeRef.primitive("u8"))
        val readable = pair and 0xffff_ffffL
        val stream = WitStream.of<UByte>(readable)

        val deferred = async { runCatching { stream.awaitReadable(runtime.wasi) } }
        delay(1)

        assertFalse(deferred.isCompleted)

        runtime.wasi.streamCancelRead(readable)

        val failure = deferred.await().exceptionOrNull()
        assertTrue(failure is ComponentModelException)
        assertTrue(failure?.message.orEmpty().contains("cancelled"))
    }

    @Test
    fun streamWriteRespectsConfiguredBufferCapacity() {
        val runtime =
            KotlinWasiPreview3.builder()
                .withResourceBudget(parallelism = 1, streamBufferCapacity = 2)
                .build()
        val payloadType = WitPackage.TypeRef.primitive("u8")
        val pair = runtime.wasi.streamNew(payloadType)
        val readable = pair and 0xffff_ffffL
        val writable = pair ushr 32
        val bytes = byteArrayOf(1, 2, 3)

        val first =
            runtime.wasi.streamWrite(
                ByteStreamPayloadContext(bytes),
                writable,
                0,
                bytes.size,
                payloadType,
            )
        val blocked =
            runtime.wasi.streamWrite(
                ByteStreamPayloadContext(bytes),
                writable,
                2,
                1,
                payloadType,
            )

        assertEquals(2L shl 4, first)
        assertEquals(STREAM_BLOCKED_STATUS, blocked)

        val readContext = CapturingByteStreamContext()
        val read = runtime.wasi.streamRead(readContext, readable, 0, 1, payloadType)

        assertEquals(1L shl 4, read)
        assertArrayEquals(byteArrayOf(1), readContext.bytes())

        val second =
            runtime.wasi.streamWrite(
                ByteStreamPayloadContext(bytes),
                writable,
                2,
                1,
                payloadType,
            )

        assertEquals(1L shl 4, second)
        assertArrayEquals(byteArrayOf(2, 3), runtime.wasi.streamBytes(WitStream.of<UByte>(readable)))
    }

    @Test
    fun awaitWritableStreamWakesAfterReaderConsumesCapacity() = runBlocking {
        val runtime =
            KotlinWasiPreview3.builder()
                .withResourceBudget(parallelism = 1, streamBufferCapacity = 1)
                .build()
        val payloadType = WitPackage.TypeRef.primitive("u8")
        val pair = runtime.wasi.streamNew(payloadType)
        val readable = pair and 0xffff_ffffL
        val writable = pair ushr 32
        val stream = WitStream.of<UByte>(writable)

        val write =
            runtime.wasi.streamWrite(
                ByteStreamPayloadContext(byteArrayOf(9)),
                writable,
                0,
                1,
                payloadType,
            )
        assertEquals(1L shl 4, write)

        val writerReady =
            async {
                stream.awaitWritable(runtime.wasi)
                "ready"
            }
        delay(1)

        assertFalse(writerReady.isCompleted)

        val readContext = CapturingByteStreamContext()
        val read = runtime.wasi.streamRead(readContext, readable, 0, 1, payloadType)

        assertEquals(1L shl 4, read)
        assertArrayEquals(byteArrayOf(9), readContext.bytes())
        assertEquals("ready", writerReady.await())
    }

    @Test
    fun convertsDeferredToCompletedWitFuture() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val deferred = CompletableDeferred("from-deferred")

        val future = deferred.toCompletedWitFuture(runtime.wasi)

        assertEquals("from-deferred", runtime.await(future))
    }

    @Test
    fun convertsDeferredToPendingWitFuture() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val deferred = CompletableDeferred<String>()

        val future = deferred.toWitFuture(runtime.wasi, this)
        val awaited = async { runtime.await(future) }
        delay(1)

        assertFalse(runtime.wasi.futureCompleted(future))
        assertFalse(awaited.isCompleted)

        deferred.complete("from-deferred")

        assertEquals("from-deferred", awaited.await())
        assertTrue(runtime.wasi.futureCompleted(future))
    }

    @Test
    fun exposesByteStreamsAsArraysAndFlows() = runBlocking {
        val runtime = KotlinWasiPreview3.builder().build()
        val bytes = byteArrayOf(1, 2, 3)
        val stream = bytes.toWitByteStream(runtime.wasi)

        assertArrayEquals(bytes, stream.asByteArray(runtime.wasi))
        assertEquals(
            listOf(1u.toUByte(), 2u.toUByte(), 3u.toUByte()),
            stream.asByteFlow(runtime.wasi).toList(),
        )

        val fromFlow =
            flowOf(byteArrayOf(4), byteArrayOf(5)).toWitByteStream(runtime.wasi)
        assertEquals(
            listOf(4u.toUByte(), 5u.toUByte()),
            fromFlow.asByteFlow(runtime.wasi).toList(),
        )

        val fromByteFlow = flowOf(6u.toUByte(), 7u.toUByte()).toWitByteStream(runtime.wasi)
        assertEquals(
            listOf(6u.toUByte(), 7u.toUByte()),
            fromByteFlow.asByteFlow(runtime.wasi).toList(),
        )
    }

    @Test
    fun byteChunkFlowToWitStreamWritesProgressivelyWithBackpressure() = runBlocking {
        val runtime =
            KotlinWasiPreview3.builder()
                .withResourceBudget(parallelism = 1, streamBufferCapacity = 1)
                .withCoroutineScope(this)
                .build()
        val secondChunkWritten = CompletableDeferred<Unit>()
        val stream =
            flow {
                emit(byteArrayOf(1))
                emit(byteArrayOf(2))
                secondChunkWritten.complete(Unit)
            }
                .toWitByteStream(runtime.wasi)

        delay(1)

        assertFalse(secondChunkWritten.isCompleted)
        assertArrayEquals(byteArrayOf(1), runtime.wasi.readByteStreamChunk(stream, 1))
        assertEquals(Unit, secondChunkWritten.await())
        assertArrayEquals(byteArrayOf(2), runtime.wasi.readByteStreamChunk(stream, 1))
        assertNull(runtime.wasi.readByteStreamChunk(stream, 1))
    }

    @Test
    fun kotlinxIoHelpersBridgeRawSourceAndRawSink() = runBlocking {
        val runtime =
            KotlinWasiPreview3.builder()
                .withCoroutineScope(this)
                .build()
        var sourceClosed = false
        val source =
            object : RawSource {
                private val chunks = ArrayDeque(listOf(byteArrayOf(1), byteArrayOf(2, 3)))

                override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long {
                    val chunk = chunks.removeFirstOrNull() ?: return -1L
                    sink.write(chunk)
                    return chunk.size.toLong()
                }

                override fun close() {
                    sourceClosed = true
                }
            }
        val written = ArrayList<Byte>()
        var sinkFlushed = false
        var sinkClosed = false
        val sink =
            object : RawSink {
                override fun write(source: KotlinxBuffer, byteCount: Long) {
                    written += source.readByteArray().toList()
                }

                override fun flush() {
                    sinkFlushed = true
                }

                override fun close() {
                    sinkClosed = true
                }
            }

        val stream = source.toWitByteStream(runtime.wasi, chunkSize = 2)
        stream.writeTo(sink, runtime.wasi, chunkSize = 1, closeSink = true)

        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), written)
        assertTrue(sourceClosed)
        assertTrue(sinkFlushed)
        assertTrue(sinkClosed)
    }

    @Test
    fun exposesTypedListStreams() {
        val runtime = KotlinWasiPreview3.builder().build()
        val stream = listOf("alpha", "beta").toWitStream(runtime.wasi)

        assertEquals(listOf("alpha", "beta"), stream.asList(runtime.wasi))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun acceptsKotlinFirstClockRandomAndPathConfiguration() {
        val root = Files.createTempDirectory("krwa-wasi-preview3-kotlin-builder")
        try {
            val runtime =
                KotlinWasiPreview3.builder()
                    .withWallClock(
                        object : KotlinClock {
                            override fun now(): KotlinInstant =
                                KotlinInstant.fromEpochSeconds(1_700_000_000L)
                        },
                        resolution = 123.nanoseconds,
                    )
                    .withWallClockResolution(124.nanoseconds)
                    .withMonotonicClock { 1_000_000L.nanoseconds }
                    .withMonotonicResolution(456.nanoseconds)
                    .withSecureRandom(kotlin.random.Random(7L))
                    .withInsecureRandom(kotlin.random.Random(8L))
                    .withInsecureSeed(11uL, 12uL)
                    .withPreopenedDirectory("/", root.toString())
                    .build()

            assertEquals(WasiPreview3.DEFAULT_VERSION, runtime.version)
            assertTrue(runtime.fileSystem().writable)
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun exposesFirstPartyFileSystemFacade() = runBlocking {
        val root = Files.createTempDirectory("krwa-wasi-preview3-fs")
        try {
            val runtime =
                KotlinWasiPreview3.builder().withPreopenedDirectory("/", root.toString()).build()
            val fs = runtime.fileSystem("/")

            fs.writeText("dir/hello.txt", "hello")
            fs.appendText("/dir/hello.txt", " world")

            assertEquals("hello world", fs.readText("dir/hello.txt"))
            assertTrue(fs.exists("dir/hello.txt"))
            assertTrue(fs.metadata("dir/hello.txt").isRegularFile)
            assertEquals(listOf("hello.txt"), fs.list("dir").map { it.name })

            val chunks = fs.readByteChunks("dir/hello.txt", chunkSize = 5).toList()
            assertEquals(listOf("hello", " worl", "d"), chunks.map { it.decodeToString() })

            fs.writeByteChunks("dir/chunks.txt", flowOf("a".toByteArray(), "b".toByteArray()))
            assertEquals("ab", fs.readText("dir/chunks.txt"))

            val stream = fs.readWitByteStream("dir/hello.txt", runtime.wasi)
            fs.writeWitByteStream("dir/copy.txt", stream, runtime.wasi)
            assertEquals("hello world", fs.readText("dir/copy.txt"))
        } finally {
            Files.walk(root).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun fileSystemFacadeEnforcesPreopenBoundaryAndReadonlyMode() {
        val root = Files.createTempDirectory("krwa-wasi-preview3-fs-guard")
        try {
            val runtime =
                KotlinWasiPreview3.builder()
                    .withReadOnlyPreopenedDirectory("/", root.toString())
                    .build()
            val fs = runtime.fileSystem()

            assertFalse(fs.exists("missing.txt"))
            assertThrows(IllegalArgumentException::class.java) { fs.readBytes("../outside.txt") }
            assertThrows(IllegalArgumentException::class.java) {
                fs.writeText("blocked.txt", "blocked")
            }
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun generatedBindingsCanTargetFirstPartyRuntimePackage() {
        val witPackage =
            WitPackage.parse(
                """
                package sample:first-party;

                interface api {
                  run: func(input: future<string>, body: stream<u8>) -> future<u32>;
                }

                world plugin {
                  export api;
                }
                """
                    .trimIndent()
            )

        val generated =
            KotlinWitBindings.builder(witPackage)
                .withPackageName("sample.generated")
                .withRuntimePackageName("uk.shusek.krwa.wasi.preview3")
                .build()
                .generate()

        assertTrue(generated.contains("import uk.shusek.krwa.wasi.preview3.WitFuture"))
        assertTrue(generated.contains("import uk.shusek.krwa.wasi.preview3.WitStream"))
        assertTrue(
            generated.contains(
                "fun run(input: WitFuture<String>, body: WitStream<UByte>): WitFuture<UInt>"
            )
        )
    }
}

private class FuturePayloadContext(private val value: Any?) : WasiPreview3CanonicalContext {
    override fun writeMemory(ptr: Int, bytes: ByteArray) {
        throw UnsupportedOperationException("writeMemory is not used by this test")
    }

    override fun readMemory(ptr: Int, len: Int): ByteArray {
        throw UnsupportedOperationException("readMemory is not used by this test")
    }

    override fun storeListElements(
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        values: List<Any?>,
    ) {
        throw UnsupportedOperationException("storeListElements is not used by this test")
    }

    override fun loadListElements(
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): List<Any?> = throw UnsupportedOperationException("loadListElements is not used by this test")

    override fun storeFutureValue(ptr: Int, payloadType: WitPackage.TypeRef, value: Any?) {
        throw UnsupportedOperationException("storeFutureValue is not used by this test")
    }

    override fun loadFutureValue(ptr: Int, payloadType: WitPackage.TypeRef): Any? = value
}

private class ByteStreamPayloadContext(private val bytes: ByteArray) :
    WasiPreview3CanonicalContext {
    override fun writeMemory(ptr: Int, bytes: ByteArray) {
        throw UnsupportedOperationException("writeMemory is not used by this test")
    }

    override fun readMemory(ptr: Int, len: Int): ByteArray {
        val end = (ptr + len).coerceAtMost(bytes.size)
        return bytes.copyOfRange(ptr, end)
    }

    override fun storeListElements(
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        values: List<Any?>,
    ) {
        throw UnsupportedOperationException("storeListElements is not used by this test")
    }

    override fun loadListElements(
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): List<Any?> = throw UnsupportedOperationException("loadListElements is not used by this test")

    override fun storeFutureValue(ptr: Int, payloadType: WitPackage.TypeRef, value: Any?) {
        throw UnsupportedOperationException("storeFutureValue is not used by this test")
    }

    override fun loadFutureValue(ptr: Int, payloadType: WitPackage.TypeRef): Any? {
        throw UnsupportedOperationException("loadFutureValue is not used by this test")
    }
}

private class CapturingByteStreamContext : WasiPreview3CanonicalContext {
    private val bytes: MutableList<Byte> = ArrayList()

    fun bytes(): ByteArray = bytes.toByteArray()

    override fun writeMemory(ptr: Int, bytes: ByteArray) {
        this.bytes.addAll(bytes.toList())
    }

    override fun readMemory(ptr: Int, len: Int): ByteArray {
        throw UnsupportedOperationException("readMemory is not used by this test")
    }

    override fun storeListElements(
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        values: List<Any?>,
    ) {
        throw UnsupportedOperationException("storeListElements is not used by this test")
    }

    override fun loadListElements(
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): List<Any?> = throw UnsupportedOperationException("loadListElements is not used by this test")

    override fun storeFutureValue(ptr: Int, payloadType: WitPackage.TypeRef, value: Any?) {
        throw UnsupportedOperationException("storeFutureValue is not used by this test")
    }

    override fun loadFutureValue(ptr: Int, payloadType: WitPackage.TypeRef): Any? {
        throw UnsupportedOperationException("loadFutureValue is not used by this test")
    }
}
