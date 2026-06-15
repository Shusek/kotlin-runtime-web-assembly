package uk.shusek.krwa.component

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod as KtorHttpMethod
import kotlinx.coroutines.runBlocking

public class KtorWasiHttpClient(private val delegate: KtorHttpClient) : WasiHttpClient {
    override fun send(request: WasiHttpRequest): WasiHttpResponse = runBlocking {
        val response =
            delegate.request(request.uri) {
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
        WasiHttpResponse(
            response.status.value,
            response.headers.entries().associate { entry -> entry.key to entry.value.toList() },
            response.bodyAsBytes(),
        )
    }
}
