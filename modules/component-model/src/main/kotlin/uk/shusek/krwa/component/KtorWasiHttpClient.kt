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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer

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
        val responseRef = CompletableDeferred<WasiHttpResponse>()
        val job =
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    statement.execute { response ->
                        val source = KtorStreamingBodySource(response.bodyAsChannel())
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
                            source.close()
                            return@execute
                        }
                        source.awaitFinished()
                    }
                } catch (error: Throwable) {
                    responseRef.completeExceptionally(error)
                }
            }
        responseRef.invokeOnCompletion { error ->
            if (error != null) {
                job.cancel()
            }
        }
        return responseRef.await()
    }
}

private class KtorStreamingBodySource(
    private val channel: ByteReadChannel,
) : WasiMemoryRawSource {
    private val finished = CompletableDeferred<Unit>()
    private var buffer = ByteArray(0)
    private var closed: Boolean = false

    suspend fun awaitFinished() {
        finished.await()
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) {
            return 0L
        }
        if (closed) {
            return -1L
        }
        val count = readAvailableBlocking(byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (count < 0) return -1L
        if (count == 0) return 0L
        sink.write(buffer, 0, count)
        return count.toLong()
    }

    override fun readAtMostToMemory(
        context: WasiPreview3CanonicalContext,
        ptr: Int,
        byteCount: Int,
    ): Int {
        if (byteCount == 0) {
            return 0
        }
        if (closed) {
            return -1
        }
        val count = readAvailableBlocking(byteCount)
        if (count <= 0) return count
        context.writeMemory(ptr, buffer, 0, count)
        return count
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        finished.complete(Unit)
    }

    private fun readAvailableBlocking(byteCount: Int): Int {
        val target = ensureBuffer(byteCount.coerceAtMost(KTOR_HTTP_BODY_CHUNK_SIZE))
        val count = runBlocking {
            channel.readAvailable(target, 0, target.size)
        }
        if (count < 0) {
            close()
        }
        return count
    }

    private fun ensureBuffer(size: Int): ByteArray {
        if (buffer.size < size) {
            buffer = ByteArray(size)
        }
        return buffer
    }
}
