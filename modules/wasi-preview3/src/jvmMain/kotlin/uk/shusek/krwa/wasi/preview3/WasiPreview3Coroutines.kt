package uk.shusek.krwa.wasi.preview3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import uk.shusek.krwa.component.ComponentModelException
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WitFuture
import uk.shusek.krwa.component.WitStream
import kotlin.jvm.JvmName

private const val DEFAULT_BYTE_CHUNK_SIZE: Int = 8192

public suspend fun <T> WasiPreview3.await(future: WitFuture<T>): T = awaitFuture(future)

public fun <T> WasiPreview3.completed(value: T): WitFuture<T> = completedFutureOf(value)

public fun <T> WitFuture<T>.asDeferred(
    wasi: WasiPreview3,
    scope: CoroutineScope,
): Deferred<T> = scope.async { wasi.await(this@asDeferred) }

public suspend fun <T> Deferred<T>.toCompletedWitFuture(wasi: WasiPreview3): WitFuture<T> =
    wasi.completed(await())

public fun <T> Deferred<T>.toWitFuture(
    wasi: WasiPreview3,
    scope: CoroutineScope,
): WitFuture<T> {
    val future = wasi.pendingFuture<T>()
    scope.launch {
        runCatching { await() }
            .onSuccess { value -> wasi.completeFuture(future, value) }
            .onFailure { wasi.futureCancelWrite(future.handle()) }
    }
    return future
}

public suspend fun WitStream<*>.awaitReadable(wasi: WasiPreview3) {
    wasi.awaitStreamReadable(this)
}

public suspend fun WitStream<*>.awaitWritable(wasi: WasiPreview3) {
    wasi.awaitStreamWritable(this)
}

@OptIn(ExperimentalUnsignedTypes::class)
public fun WitStream<UByte>.asByteArray(wasi: WasiPreview3): ByteArray =
    wasi.streamBytes(this)

@OptIn(ExperimentalUnsignedTypes::class)
public fun WitStream<UByte>.asByteChunks(
    wasi: WasiPreview3,
    chunkSize: Int = DEFAULT_BYTE_CHUNK_SIZE,
): Flow<ByteArray> =
    flow {
        while (true) {
            val chunk = wasi.readByteStreamChunk(this@asByteChunks, chunkSize) ?: break
            emit(chunk)
        }
    }

@OptIn(ExperimentalUnsignedTypes::class)
public fun WitStream<UByte>.asByteFlow(wasi: WasiPreview3): Flow<UByte> =
    asByteChunks(wasi).asUByteFlow()

public fun RawSource.asByteChunks(
    chunkSize: Int = DEFAULT_BYTE_CHUNK_SIZE,
    closeSource: Boolean = true,
): Flow<ByteArray> =
    flow {
        val limit = requireByteChunkSize(chunkSize)
        val buffer = Buffer()
        try {
            while (true) {
                val read = this@asByteChunks.readAtMostTo(buffer, limit.toLong())
                if (read < 0L) {
                    break
                }
                if (read == 0L) {
                    yield()
                    continue
                }
                emit(buffer.readByteArray())
            }
        } finally {
            if (closeSource) {
                this@asByteChunks.close()
            }
        }
    }

@OptIn(ExperimentalUnsignedTypes::class)
private fun Flow<ByteArray>.asUByteFlow(): Flow<UByte> =
    flow {
        collect { chunk ->
            for (byte in chunk) {
                emit(byte.toUByte())
            }
        }
    }

@OptIn(ExperimentalUnsignedTypes::class)
public fun ByteArray.toWitByteStream(wasi: WasiPreview3): WitStream<UByte> =
    wasi.byteStream(this)

@OptIn(ExperimentalUnsignedTypes::class)
@JvmName("ubyteFlowToWitByteStream")
public fun Flow<UByte>.toWitByteStream(wasi: WasiPreview3): WitStream<UByte> =
    toByteChunks().toWitByteStream(wasi)

@JvmName("byteChunkFlowToWitByteStream")
public fun Flow<ByteArray>.toWitByteStream(wasi: WasiPreview3): WitStream<UByte> =
    wasi.byteStream(this)

@OptIn(ExperimentalUnsignedTypes::class)
public fun RawSource.toWitByteStream(
    wasi: WasiPreview3,
    chunkSize: Int = DEFAULT_BYTE_CHUNK_SIZE,
    closeSource: Boolean = true,
): WitStream<UByte> =
    asByteChunks(chunkSize, closeSource).toWitByteStream(wasi)

public suspend fun Flow<ByteArray>.writeTo(
    sink: RawSink,
    closeSink: Boolean = false,
) {
    try {
        collect { chunk -> sink.writeByteChunk(chunk) }
        sink.flush()
    } finally {
        if (closeSink) {
            sink.close()
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
public suspend fun WitStream<UByte>.writeTo(
    sink: RawSink,
    wasi: WasiPreview3,
    chunkSize: Int = DEFAULT_BYTE_CHUNK_SIZE,
    closeSink: Boolean = false,
) {
    asByteChunks(wasi, chunkSize).writeTo(sink, closeSink)
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun Flow<UByte>.toByteChunks(chunkSize: Int = DEFAULT_BYTE_CHUNK_SIZE): Flow<ByteArray> =
    flow {
        val buffer = ByteArray(chunkSize)
        var count = 0
        collect { byte ->
            buffer[count++] = byte.toByte()
            if (count == buffer.size) {
                emit(buffer.copyOf())
                count = 0
            }
        }
        if (count > 0) {
            emit(buffer.copyOf(count))
        }
    }

private fun RawSink.writeByteChunk(chunk: ByteArray) {
    if (chunk.isEmpty()) {
        return
    }
    val buffer = Buffer()
    buffer.write(chunk)
    write(buffer, chunk.size.toLong())
}

private fun requireByteChunkSize(chunkSize: Int): Int {
    require(chunkSize > 0) { "chunkSize must be positive" }
    return chunkSize
}

public fun <T> Iterable<T>.toWitStream(wasi: WasiPreview3): WitStream<T> =
    wasi.streamOf(this)

public fun <T> WitStream<T>.asList(wasi: WasiPreview3): List<T> =
    wasi.streamValues(this)

public fun WitFuture<*>.requireCompleted(wasi: WasiPreview3) {
    if (!wasi.futureCompleted(this)) {
        throw ComponentModelException("WASI Preview 3 future $handle is not completed")
    }
}
