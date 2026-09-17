package dev.shinsou.kmp.plugin

import java.net.InetAddress
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

public actual fun createPlatformPluginHostResolver(): PluginHostResolver = PluginHostResolver { host ->
    withContext(Dispatchers.IO) {
        // Java exposes getHostAddress() as nullable; discard an unusable answer so the common
        // admission layer can fail closed on an empty/invalid resolution instead of receiving a
        // null address and failing later while pinning the socket.
        InetAddress.getAllByName(host).mapNotNull(InetAddress::getHostAddress).distinct()
    }
}

public actual fun createPlatformPinnedPluginHttpTransport(): PluginHttpTransport? =
    JvmPinnedPluginHttpTransport()

private class JvmPinnedPluginHttpTransport : PluginHttpTransport {
    /** Reused pool/dispatcher; per-request clients below share these resources via newBuilder(). */
    private val sharedClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
        throw IllegalStateException("Pinned plugin transport requires a validated DNS resolution")

    override suspend fun executeResolved(
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse = withContext(Dispatchers.IO) {
        val addresses = resolution.addresses.map { address ->
            // The common admission boundary has already required canonical IP literals. Passing
            // raw bytes avoids a second hostname lookup for attacker-controlled DNS rebinding.
            InetAddress.getByAddress(parseValidatedIpLiteral(address))
        }
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                require(hostname.equals(resolution.host, ignoreCase = true)) {
                    "Pinned transport attempted to resolve a different host"
                }
                return addresses
            }
        }
        // newBuilder() keeps the shared connection pool and dispatcher while installing a
        // request-local DNS view. Building a fresh OkHttpClient here would defeat connection
        // reuse and create unnecessary executor/pool overhead for every plugin request.
        val client = sharedClient.newBuilder()
            .dns(pinnedDns)
            .build()
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val body = pinnedOkHttpRequestBody(request)
        builder.method(request.method, body)
        client.newCall(builder.build()).execute().use { response ->
            val responseHeadersBuilder = BoundedPluginResponseHeaders()
            for (index in 0 until response.headers.size) {
                val name = response.headers.name(index)
                responseHeadersBuilder.add(name, response.headers.value(index))
            }
            val rawResponseHeaders = responseHeadersBuilder.build()
            val contentEncoding = rawResponseHeaders.pluginContentEncoding()
            val responseHeaders = rawResponseHeaders.filterKeys { name ->
                !name.equals("Content-Encoding", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
            }
            val headerDeclared = rawResponseHeaders.pluginDeclaredContentLength()
            val bodyDeclared = response.body?.contentLength()?.takeIf { it >= 0 }
            require(headerDeclared == null || bodyDeclared == null || headerDeclared == bodyDeclared) {
                "Plugin response contains inconsistent content lengths"
            }
            val declared = headerDeclared ?: bodyDeclared
            require(declared == null || declared <= request.maxResponseBytes) {
                "Plugin response is too large"
            }
            val bytes = decodePinnedPluginResponseBody(
                contentEncoding = contentEncoding,
                declaredLength = declared,
                input = response.body?.byteStream(),
                maxResponseBytes = request.maxResponseBytes,
            )
            PluginHttpResponse(
                status = response.code,
                body = bytes,
                // The body has already been decoded. Do not leave stale framing/encoding
                // metadata for plugin parsers to interpret as if the body were still compressed.
                headers = responseHeaders,
            )
        }
    }
}

/**
 * OkHttp requires a non-null body for POST/PUT/PATCH even when the wire body is empty. Keep this
 * transport detail out of the common request model: iOS writes an empty request directly, while
 * Android/Desktop need an explicit zero-byte [RequestBody] to preserve the same semantics.
 */
internal fun pinnedOkHttpRequestBody(request: PluginHttpRequest): RequestBody? {
    val mediaType = request.headers.entries
        .firstOrNull { it.key.equals("Content-Type", true) }
        ?.value
        ?.toMediaTypeOrNull()
    if (request.body.isNotEmpty()) return request.body.toRequestBody(mediaType)
    return if (request.method.uppercase() in OKHTTP_METHODS_REQUIRING_BODY) {
        ByteArray(0).toRequestBody(mediaType)
    } else {
        null
    }
}

private val OKHTTP_METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

/** Reads an OkHttp response body with a decompressed-byte cap. */
internal fun decodePinnedPluginResponseBody(
    contentEncoding: String?,
    declaredLength: Long?,
    input: InputStream?,
    maxResponseBytes: Int = PLUGIN_NETWORK_MAX_RESPONSE_BYTES,
): ByteArray {
    require(maxResponseBytes in 1..PLUGIN_NETWORK_MAX_RESPONSE_BYTES) {
        "Invalid plugin response byte limit"
    }
    val encoding = contentEncoding?.trim()?.lowercase()
    require(declaredLength == null || declaredLength <= maxResponseBytes) {
        "Plugin response is too large"
    }
    if (input == null) return ByteArray(0)
    val decoded = when (encoding) {
        null, "", "identity" -> input
        "gzip" -> GZIPInputStream(input)
        "deflate" -> InflaterInputStream(input)
        else -> throw IllegalArgumentException("Unsupported plugin response encoding: $contentEncoding")
    }
    decoded.use { source ->
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (true) {
            // Read a single sentinel byte after the exact boundary instead of allowing the
            // decompressor/InputStream to read ahead by a fixed-size chunk.
            val readCapacity = minOf(16 * 1024, maxResponseBytes - total + 1)
            val chunk = ByteArray(readCapacity)
            val read = source.read(chunk, 0, readCapacity)
            if (read < 0) break
            check(read != 0) { "Plugin response transport made no read progress" }
            require(read <= maxResponseBytes - total) { "Plugin response is too large" }
            total += read
            chunks += if (read == chunk.size) chunk else chunk.copyOf(read)
        }
        return ByteArray(total).also { result ->
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
        }
    }
}

private fun parseValidatedIpLiteral(value: String): ByteArray {
    require(isPluginIpLiteral(value)) { "Pinned transport received a non-address DNS answer" }
    val parsed = InetAddress.getByName(value)
    return parsed.address
}
