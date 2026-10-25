package dev.shinsou.kmp.plugin

import java.io.IOException
import java.net.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Fixed, credential-free development transport; never shared with source or app HTTP clients. */
internal class JvmReviewedLocalRepositoryTransport(
    private val policy: ReviewedLocalRepositoryPolicy,
) : PluginHttpTransport, AutoCloseable {
    private val host = requireNotNull(policy.baseUrl).substringAfter("://").substringBefore(':')
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                require(hostname == host) { "Unexpected local repository host" }
                // Some OkHttp versions invoke Dns even for literals. Construct the fixed address
                // from bytes, never resolve a name or consult a system/proxy DNS service.
                return listOf(InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray()))
            }
        })
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
        require(request.method == "GET" && request.body.isEmpty() && request.headers.isEmpty()) {
            "Local repository transport accepts only credential-free GET requests"
        }
        require(policy.admitsRequestUrl(request.url)) { "Request is outside the reviewed local repository" }
        val call = client.newCall(
            Request.Builder().url(request.url).get().header("Accept-Encoding", "identity")
                // Python's development server closes HTTP/1.0 responses without an explicit
                // Connection header. Do not reuse that potentially stale socket or enable retries.
                .header("Connection", "close").build(),
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            // Never follow even same-origin redirects; repo paths are exact.
                            require(response.code !in 300..399) { "Local repository redirects are forbidden" }
                            val boundedHeaders = BoundedPluginResponseHeaders()
                            for (index in 0 until response.headers.size) {
                                boundedHeaders.add(response.headers.name(index), response.headers.value(index))
                            }
                            val headers = boundedHeaders.build()
                            val declared = headers.pluginDeclaredContentLength()
                            val body = decodePinnedPluginResponseBody(
                                headers.pluginContentEncoding(), declared, response.body?.byteStream(),
                                request.maxResponseBytes,
                            )
                            PluginHttpResponse(response.code, body, headers.filterKeys {
                                !it.equals("Set-Cookie", true) && !it.equals("Content-Encoding", true) &&
                                    !it.equals("Content-Length", true)
                            })
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

}
