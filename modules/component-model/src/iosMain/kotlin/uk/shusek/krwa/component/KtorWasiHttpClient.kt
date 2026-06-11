package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod as KtorHttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource

private const val KTOR_HTTP_BODY_CHUNK_SIZE: Int = 64 * 1024

public class KtorWasiHttpClient(private val delegate: KtorHttpClient) : WasiSuspendingHttpClient {
    override fun send(request: WasiHttpRequest): WasiHttpResponse =
        runBlocking { sendSuspending(request) }

    override suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse {
        val statement =
            delegate.prepareRequest(request.uri) {
                method = KtorHttpMethod(request.method)
                val timeout = request.timeout
                if (timeout != null) {
                    timeout {
                        val millis = maxOf(1L, timeout.inWholeMilliseconds)
                        requestTimeoutMillis = millis
                        connectTimeoutMillis = millis
                        socketTimeoutMillis = millis
                    }
                }
                headers {
                    for (entry in request.headers) {
                        append(entry.name, entry.value)
                    }
                }
                setBody(request.body)
            }
        val chunks = Channel<ByteArray>(capacity = Channel.BUFFERED)
        val jobRef = CompletableDeferred<Job>()
        val responseRef = CompletableDeferred<WasiHttpResponse>()
        val source = KtorStreamingBodySource(chunks, jobRef)
        val job =
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    statement.execute { response ->
                        if (
                            !responseRef.complete(
                                WasiHttpResponse(
                                    response.status.value,
                                    response.headers.entries()
                                        .associate { entry -> entry.key to entry.value.toList() },
                                    source,
                                )
                            )
                        ) {
                            chunks.cancel()
                            return@execute
                        }
                        streamBody(response.bodyAsChannel(), chunks)
                    }
                    chunks.close()
                } catch (error: Throwable) {
                    chunks.close(error)
                    responseRef.completeExceptionally(error)
                }
            }
        jobRef.complete(job)
        responseRef.invokeOnCompletion { error ->
            if (error != null) {
                chunks.cancel()
                job.cancel()
            }
        }
        return responseRef.await()
    }
}

private suspend fun streamBody(channel: ByteReadChannel, chunks: Channel<ByteArray>) {
    val buffer = ByteArray(KTOR_HTTP_BODY_CHUNK_SIZE)
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count <= 0) {
            break
        }
        chunks.send(buffer.copyOf(count))
    }
}

private class KtorStreamingBodySource(
    private val chunks: Channel<ByteArray>,
    private val jobRef: CompletableDeferred<Job>,
) : RawSource {
    private var pending: ByteArray? = null
    private var pendingOffset: Int = 0
    private var closed: Boolean = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) {
            return 0L
        }
        if (closed) {
            return -1L
        }
        var bytes = pending
        if (bytes == null || pendingOffset >= bytes.size) {
            val result = runBlocking { chunks.receiveCatching() }
            val error = result.exceptionOrNull()
            if (error != null) {
                throw error
            }
            bytes = result.getOrNull() ?: return -1L
            pending = bytes
            pendingOffset = 0
        }
        val count =
            minOf(
                byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                bytes.size - pendingOffset,
            )
        sink.write(bytes, pendingOffset, count)
        pendingOffset += count
        if (pendingOffset >= bytes.size) {
            pending = null
            pendingOffset = 0
        }
        return count.toLong()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        chunks.cancel()
        runBlocking {
            jobRef.await().cancel()
        }
    }
}
