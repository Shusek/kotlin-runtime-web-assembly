package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.pluginOrNull
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
        val requestClient = delegate.withoutRedirects()
        val statement =
            try {
                requestClient.prepareRequest(request.uri) {
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
            } catch (error: Throwable) {
                requestClient.close()
                throw error
            }
        val responseRef = CompletableDeferred<WasiHttpResponse>()
        val job =
            CoroutineScope(Dispatchers.Default).launch {
                var source: KtorStreamingBodySource? = null
                try {
                    statement.execute { response ->
                        val responseSource =
                            KtorStreamingBodySource(
                                channel = response.bodyAsChannel(),
                                onClose = requestClient::close,
                            )
                        source = responseSource
                        if (
                            !responseRef.complete(
                                WasiHttpResponse(
                                    response.status.value,
                                    response.headers.entries()
                                        .associate { entry -> entry.key to entry.value.toList() },
                                    responseSource,
                                )
                            )
                        ) {
                            responseSource.close()
                            return@execute
                        }
                        responseSource.awaitFinished()
                    }
                } catch (error: Throwable) {
                    source?.close() ?: requestClient.close()
                    responseRef.completeExceptionally(error)
                }
            }
        responseRef.invokeOnCompletion { error ->
            if (error != null) {
                job.cancel()
            }
        }
        return try {
            responseRef.await()
        } catch (error: Throwable) {
            job.cancel()
            requestClient.close()
            throw error
        }
    }
}

internal class KtorStreamingBodySource(
    private val channel: ByteReadChannel,
    private val onClose: () -> Unit,
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
        onClose()
    }

    private fun readAvailableBlocking(byteCount: Int): Int {
        val target = ensureBuffer(byteCount.coerceAtMost(KTOR_HTTP_BODY_CHUNK_SIZE))
        val count =
            try {
                runBlocking {
                    channel.readAvailable(target, 0, target.size)
                }
            } catch (error: Throwable) {
                close()
                throw error
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

private fun KtorHttpClient.withoutRedirects(): KtorHttpClient {
    val client = config {
        followRedirects = false
    }
    if (client.pluginOrNull(HttpRedirect) != null) {
        client.close()
        throw IllegalArgumentException(
            "Ktor WASI HTTP clients must not install HttpRedirect explicitly."
        )
    }
    return client
}
