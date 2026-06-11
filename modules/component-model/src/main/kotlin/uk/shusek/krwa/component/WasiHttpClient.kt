package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import kotlin.time.Duration
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

public interface WasiMemoryRawSource : RawSource {
    public fun readAtMostToMemory(
        context: WasiPreview3CanonicalContext,
        ptr: Int,
        byteCount: Int,
    ): Int
}

public interface WasiHttpClient {
    public fun send(request: WasiHttpRequest): WasiHttpResponse

    public suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse =
        send(request)
}

public interface WasiSuspendingHttpClient : WasiHttpClient {
    override suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse
}

public class WasiHttpRequest(
    public val method: String,
    public val uri: String,
    public val headers: List<WasiHttpHeader>,
    public val body: ByteArray,
    public val timeout: Duration?,
)

public class WasiHttpResponse {
    public val status: Int
    public val headers: Map<String, List<String>>
    private val bodyContent: HttpBodyContent

    public constructor(status: Int, headers: Map<String, List<String>>, body: ByteArray) {
        this.status = status
        this.headers = headers
        this.bodyContent = ByteArrayHttpBodyContent(body.copyOf())
    }

    public constructor(status: Int, headers: Map<String, List<String>>, bodySource: RawSource) {
        this.status = status
        this.headers = headers
        this.bodyContent = RawSourceHttpBodyContent(bodySource)
    }

    public val body: ByteArray
        get() = bodyContent.bytes()

    public fun consumeBodySource(): RawSource = bodyContent.consumeSource()
}

public class WasiHttpHeader(public val name: String, public val value: String)

internal expect fun defaultWasiHttpClient(): WasiHttpClient

internal expect fun ktorWasiHttpClient(httpClient: KtorHttpClient): WasiHttpClient

private const val HTTP_BODY_CHUNK_SIZE: Long = 8192L

private interface HttpBodyContent {
    fun bytes(): ByteArray

    fun consumeSource(): RawSource
}

private class ByteArrayHttpBodyContent(private val data: ByteArray) : HttpBodyContent {
    override fun bytes(): ByteArray = data.copyOf()

    override fun consumeSource(): RawSource = byteArraySource(data)
}

private class RawSourceHttpBodyContent(private var source: RawSource?) : HttpBodyContent {
    private var cached: ByteArray? = null

    override fun bytes(): ByteArray {
        val cachedBytes = cached
        if (cachedBytes != null) {
            return cachedBytes.copyOf()
        }
        val current = source ?: error("HTTP body source has already been consumed")
        val bytes = current.readAllAndClose()
        source = null
        cached = bytes
        return bytes.copyOf()
    }

    override fun consumeSource(): RawSource {
        val cachedBytes = cached
        if (cachedBytes != null) {
            return byteArraySource(cachedBytes)
        }
        val current = source ?: error("HTTP body source has already been consumed")
        source = null
        return current
    }
}

private fun byteArraySource(bytes: ByteArray): RawSource {
    val buffer = Buffer()
    buffer.write(bytes)
    return buffer
}

private fun RawSource.readAllAndClose(): ByteArray {
    val out = Buffer()
    try {
        while (true) {
            val read = readAtMostTo(out, HTTP_BODY_CHUNK_SIZE)
            if (read <= 0L) {
                break
            }
        }
        return out.readByteArray()
    } finally {
        close()
    }
}
