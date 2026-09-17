@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Network.nw_content_context_create
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import dev.shinsou.interop.network.shinsou_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_get_error_code
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_prefer_no_proxy
import platform.Network.nw_tcp_options_set_connection_timeout
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_protocol_options_add_tls_application_protocol
import platform.Security.sec_protocol_options_set_min_tls_protocol_version
import platform.Security.sec_protocol_options_set_tls_server_name
import platform.Security.tls_protocol_version_TLSv12
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_queue_create
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.AF_UNSPEC
import platform.posix.AI_ADDRCONFIG
import platform.posix.IPPROTO_TCP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val IOS_PLUGIN_CONNECT_TIMEOUT_SECONDS: UInt = 15u
private const val IOS_PLUGIN_REQUEST_TIMEOUT_MILLIS: Long = 30_000L
private const val IOS_PLUGIN_RECEIVE_CHUNK_BYTES: UInt = 65_536u
private const val IOS_PLUGIN_HTTP_FRAMING_BYTES: Int = 128 * 1_024

/** Resolves A/AAAA answers once; the resulting literals are later used as NWConnection endpoints. */
public actual fun createPlatformPluginHostResolver(): PluginHostResolver = PluginHostResolver { host ->
    withContext(Dispatchers.Default) { resolveDarwinHost(host) }
}

/** Connects to an admitted literal IP while retaining URL hostname as TLS SNI and HTTP Host. */
public actual fun createPlatformPinnedPluginHttpTransport(): PluginHttpTransport? =
    IosPinnedPluginHttpTransport()

private class IosPinnedPluginHttpTransport : PluginHttpTransport {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
        throw IllegalStateException("Pinned plugin transport requires a validated DNS resolution")

    override suspend fun executeResolved(
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse = withTimeout(IOS_PLUGIN_REQUEST_TIMEOUT_MILLIS) {
        val url = Url(request.url)
        require(url.protocol.name.equals("https", ignoreCase = true)) {
            "Pinned plugin transport only permits HTTPS"
        }
        val originalHost = url.host.lowercase().trimEnd('.')
        require(originalHost == resolution.host.lowercase().trimEnd('.')) {
            "Pinned transport attempted to use a different host"
        }
        require(resolution.addresses.isNotEmpty()) { "Pinned transport received no addresses" }
        require(resolution.addresses.all(::isPluginIpLiteral)) {
            "Pinned transport received a non-address DNS answer"
        }
        val encodedRequest = encodeIosPinnedHttpRequest(request, url)
        var failure: Throwable? = null
        for (address in resolution.addresses.distinct()) {
            currentCoroutineContext().ensureActive()
            try {
                return@withTimeout executeAtAddress(
                    address = address,
                    port = url.port,
                    tlsServerName = originalHost,
                    request = encodedRequest,
                    requestMethod = request.method,
                    maxResponseBytes = request.maxResponseBytes,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (attemptFailure: IosPinnedAddressException) {
                failure = attemptFailure
            }
        }
        throw IllegalStateException("Pinned plugin connection failed for every approved address", failure)
    }
}

private suspend fun executeAtAddress(
    address: String,
    port: Int,
    tlsServerName: String,
    request: ByteArray,
    requestMethod: String,
    maxResponseBytes: Int,
): PluginHttpResponse = suspendCancellableCoroutine { continuation ->
    // Network.framework accepts raw IPv6 literals here; URL-style brackets would turn the value
    // back into a hostname and risk an unintended resolver lookup.
    val endpoint = requireNotNull(nw_endpoint_create_host(address, port.toString()))
    val parameters = requireNotNull(nw_parameters_create_secure_tcp(
        configure_tls = { tlsOptions ->
            val securityOptions = requireNotNull(nw_tls_copy_sec_protocol_options(tlsOptions))
            sec_protocol_options_set_tls_server_name(securityOptions, tlsServerName)
            sec_protocol_options_set_min_tls_protocol_version(securityOptions, tls_protocol_version_TLSv12)
            // The bounded parser below deliberately supports only HTTP/1.x.
            sec_protocol_options_add_tls_application_protocol(securityOptions, "http/1.1")
        },
        configure_tcp = { tcpOptions ->
            nw_tcp_options_set_connection_timeout(tcpOptions, IOS_PLUGIN_CONNECT_TIMEOUT_SECONDS)
        },
    ))
    // A system proxy would invalidate the literal endpoint binding promised by executeResolved.
    nw_parameters_set_prefer_no_proxy(parameters, true)
    val connection = requireNotNull(nw_connection_create(endpoint, parameters))
    val queue = requireNotNull(dispatch_queue_create("dev.shinsou.plugin.pinned", null))
    val rawLimit = maxResponseBytes + IOS_PLUGIN_HTTP_FRAMING_BYTES
    val raw = ArrayList<ByteArray>()
    var rawSize = 0
    var started = false
    var terminal = false

    fun finish(result: Result<PluginHttpResponse>) {
        if (terminal) return
        terminal = true
        nw_connection_cancel(connection)
        result.fold(
            onSuccess = { continuation.resume(it) },
            onFailure = { continuation.resumeWithException(it) },
        )
    }

    fun receiveNext() {
        shinsou_connection_receive(connection, 1u, IOS_PLUGIN_RECEIVE_CHUNK_BYTES) { data, complete, error ->
            if (terminal) return@shinsou_connection_receive
            if (error != null) {
                finish(Result.failure(IllegalStateException(
                    "Pinned plugin receive failed (${nw_error_get_error_code(error)})",
                )))
                return@shinsou_connection_receive
            }
            if (data != null) {
                dispatch_data_apply(data) { _, _, buffer, size ->
                    if (size > (rawLimit - rawSize).toULong()) {
                        finish(Result.failure(IllegalArgumentException("Plugin response is too large")))
                        false
                    } else {
                        val bytes = buffer?.reinterpret<ByteVar>()?.readBytes(size.toInt()) ?: ByteArray(0)
                        raw += bytes
                        rawSize += bytes.size
                        true
                    }
                }
            }
            if (terminal) return@shinsou_connection_receive
            if (complete) {
                val joined = ByteArray(rawSize)
                var offset = 0
                raw.forEach { part ->
                    part.copyInto(joined, offset)
                    offset += part.size
                }
                finish(runCatching {
                    parseIosPinnedHttpResponse(
                        raw = joined,
                        maxResponseBytes = maxResponseBytes,
                        expectBody = !requestMethod.equals("HEAD", ignoreCase = true),
                    )
                })
            } else {
                receiveNext()
            }
        }
    }

    nw_connection_set_state_changed_handler(connection) { state, error ->
        when (state) {
            nw_connection_state_ready -> if (!started && !terminal) {
                started = true
                val requestData = request.toNSData()
                val data = dispatch_data_create(
                    requestData.bytes,
                    requestData.length,
                    queue,
                    null,
                )
                nw_connection_send(
                    connection,
                    data,
                    requireNotNull(nw_content_context_create("shinsou-pinned-request")),
                    true,
                ) { sendError ->
                    // Capture requestData so its copied buffer remains alive until Network.framework
                    // has finished consuming the dispatch_data_t.
                    requestData.length
                    if (sendError != null) {
                        finish(Result.failure(IllegalStateException(
                            "Pinned plugin send failed (${nw_error_get_error_code(sendError)})",
                        )))
                    } else if (!terminal) {
                        receiveNext()
                    }
                }
            }
            nw_connection_state_failed -> finish(Result.failure(IosPinnedAddressException(
                "Pinned plugin connection failed (${error?.let(::nw_error_get_error_code) ?: -1})",
            )))
            nw_connection_state_cancelled -> if (!terminal) {
                finish(Result.failure(CancellationException("Pinned plugin connection cancelled")))
            }
        }
    }
    continuation.invokeOnCancellation {
        // nw_connection_cancel is thread-safe. Let its serial-queue state callback own `terminal`
        // so callback state is never mutated concurrently by the cancelling coroutine.
        nw_connection_cancel(connection)
    }
    nw_connection_set_queue(connection, queue)
    nw_connection_start(connection)
}

private fun resolveDarwinHost(host: String): List<String> = memScoped {
    require(host.isNotBlank() && '\u0000' !in host) { "Invalid plugin DNS hostname" }
    val hints = alloc<addrinfo>().apply {
        ai_flags = AI_ADDRCONFIG
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
        ai_protocol = IPPROTO_TCP
    }
    val result = alloc<CPointerVar<addrinfo>>()
    val status = getaddrinfo(host, null, hints.ptr, result.ptr)
    if (status != 0) return@memScoped emptyList()
    val first = result.value ?: return@memScoped emptyList()
    try {
        val answers = linkedSetOf<String>()
        var cursor = first
        while (true) {
            val value = cursor.pointed
            value.ai_addr?.let { address ->
                val output = allocArray<ByteVar>(NI_MAXHOST)
                if (getnameinfo(
                        address,
                        value.ai_addrlen,
                        output,
                        NI_MAXHOST.toUInt(),
                        null,
                        0u,
                        NI_NUMERICHOST,
                    ) == 0
                ) {
                    output.toKString().takeIf(::isPluginIpLiteral)?.let(answers::add)
                }
            }
            cursor = value.ai_next ?: break
        }
        answers.toList()
    } finally {
        freeaddrinfo(first)
    }
}

internal fun encodeIosPinnedHttpRequest(request: PluginHttpRequest, url: Url = Url(request.url)): ByteArray {
    require(request.method.isNotEmpty() && request.method.all { it in 'A'..'Z' }) {
        "Invalid plugin HTTP method"
    }
    require(request.headers.size <= PLUGIN_NETWORK_MAX_HEADERS) { "Too many plugin request headers" }
    require(request.body.size <= PLUGIN_NETWORK_MAX_REQUEST_BODY_BYTES) {
        "Plugin request body is too large"
    }
    require(request.headers.keys.none(::isForbiddenPluginControlledHeader)) {
        "Plugin request cannot control transport headers"
    }
    require(request.headers.keys.none { it.equals("Expect", ignoreCase = true) }) {
        "Plugin request cannot use interim HTTP responses"
    }
    val afterAuthority = url.toString().substringAfter("://")
    val targetStart = afterAuthority.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val rawRequestTarget = when {
        targetStart < 0 || afterAuthority[targetStart] == '#' -> "/"
        afterAuthority[targetStart] == '?' -> "/${afterAuthority.substring(targetStart).substringBefore('#')}"
        else -> afterAuthority.substring(targetStart).substringBefore('#')
    }
    val host = if (url.port == 443) url.host else "${url.host}:${url.port}"
    require(rawRequestTarget.startsWith('/') && rawRequestTarget.none { it.code < 32 || it.code == 127 }) {
        "Invalid plugin HTTP request target"
    }
    // Ktor's Url can retain Unicode in an already-parsed path. HTTP/1 request lines require
    // ASCII; encode UTF-8 bytes without decoding/rebuilding existing escapes or signed queries.
    val requestTarget = buildString {
        rawRequestTarget.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value in 33..126) append(value.toChar()) else {
                append('%')
                append("0123456789ABCDEF"[value ushr 4])
                append("0123456789ABCDEF"[value and 15])
            }
        }
    }
    var totalHeaderBytes = 0
    val headers = buildString {
        append(request.method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append("\r\n")
        append("Connection: close\r\n")
        append("Accept-Encoding: identity\r\n")
        request.headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.all { it.code in 33..126 && it != ':' }) {
                "Invalid plugin request header name"
            }
            require(value.none { it == '\r' || it == '\n' || it.code == 0 }) {
                "Invalid plugin request header value"
            }
            val nameBytes = name.encodeToByteArray().size
            val valueBytes = value.encodeToByteArray().size
            require(nameBytes <= PLUGIN_NETWORK_MAX_HEADER_NAME_BYTES) {
                "Plugin request header name is too large"
            }
            require(valueBytes <= PLUGIN_NETWORK_MAX_HEADER_VALUE_BYTES) {
                "Plugin request header value is too large"
            }
            require(nameBytes <= PLUGIN_NETWORK_MAX_HEADER_BYTES - totalHeaderBytes &&
                valueBytes <= PLUGIN_NETWORK_MAX_HEADER_BYTES - totalHeaderBytes - nameBytes) {
                "Plugin request headers are too large"
            }
            totalHeaderBytes += nameBytes + valueBytes
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                append(name).append(": ").append(value).append("\r\n")
            }
        }
        if (request.body.isNotEmpty()) append("Content-Length: ").append(request.body.size).append("\r\n")
        append("\r\n")
    }.encodeToByteArray()
    return headers + request.body
}

internal fun parseIosPinnedHttpResponse(
    raw: ByteArray,
    maxResponseBytes: Int,
    expectBody: Boolean = true,
): PluginHttpResponse {
    require(maxResponseBytes in 1..PLUGIN_NETWORK_MAX_RESPONSE_BYTES)
    val separator = raw.indexOfSequence(CRLF_CRLF)
    require(separator in 1..PLUGIN_NETWORK_MAX_RESPONSE_HEADER_BYTES) {
        "Invalid or oversized plugin response headers"
    }
    val headerText = raw.copyOfRange(0, separator).decodeToString(throwOnInvalidSequence = true)
    val lines = headerText.split("\r\n")
    val statusParts = lines.first().split(' ')
    require(statusParts.size >= 2 && statusParts[0] in setOf("HTTP/1.0", "HTTP/1.1")) {
        "Invalid plugin HTTP status line"
    }
    val status = statusParts[1].toIntOrNull()
    require(status != null && status in 200..599) { "Invalid plugin HTTP status" }
    val builder = BoundedPluginResponseHeaders()
    lines.drop(1).forEach { line ->
        require(line.isNotEmpty() && line.first() != ' ' && line.first() != '\t') {
            "Invalid folded plugin response header"
        }
        val colon = line.indexOf(':')
        require(colon > 0) { "Invalid plugin response header" }
        builder.add(line.substring(0, colon), line.substring(colon + 1).trim())
    }
    val rawHeaders = builder.build()
    require(rawHeaders.pluginContentEncoding() in setOf(null, "", "identity")) {
        "Pinned iOS transport requires an identity-encoded response"
    }
    val transferEncodings = rawHeaders.pluginHeaderValues("Transfer-Encoding")
    require(transferEncodings.size <= 1) { "Ambiguous plugin transfer encoding" }
    require(transferEncodings.isEmpty() || rawHeaders.pluginHeaderValues("Content-Length").isEmpty()) {
        "Plugin response cannot combine transfer encoding and content length"
    }
    val bodyWire = raw.copyOfRange(separator + CRLF_CRLF.size, raw.size)
    val body = when {
        !expectBody || status == 204 || status == 304 -> {
            require(bodyWire.isEmpty()) { "Body forbidden for plugin response status" }
            ByteArray(0)
        }
        transferEncodings.isNotEmpty() -> {
            require(transferEncodings.single().trim().equals("chunked", ignoreCase = true)) {
                "Unsupported plugin transfer encoding"
            }
            decodeIosHttpChunkedBody(bodyWire, maxResponseBytes)
        }
        else -> {
            val declared = rawHeaders.pluginDeclaredContentLength()
            require(declared == null || declared == bodyWire.size.toLong()) {
                "Plugin response content length is inconsistent"
            }
            require(bodyWire.size <= maxResponseBytes) { "Plugin response is too large" }
            bodyWire
        }
    }
    return PluginHttpResponse(
        status = status,
        body = body,
        headers = rawHeaders.filterKeys { name ->
            name.lowercase() !in setOf("content-length", "content-encoding", "transfer-encoding", "connection")
        },
    )
}

private fun decodeIosHttpChunkedBody(raw: ByteArray, maxResponseBytes: Int): ByteArray {
    val chunks = ArrayList<ByteArray>()
    var total = 0
    var offset = 0
    while (true) {
        val lineEnd = raw.indexOfSequence(CRLF, offset)
        require(lineEnd >= offset && lineEnd - offset <= 1_024) { "Invalid plugin chunk framing" }
        val sizeToken = raw.copyOfRange(offset, lineEnd).decodeToString().substringBefore(';').trim()
        require(sizeToken.isNotEmpty() && sizeToken.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid plugin chunk size"
        }
        val size = sizeToken.toLongOrNull(16)
        require(size != null && size <= maxResponseBytes - total) { "Plugin response is too large" }
        offset = lineEnd + CRLF.size
        if (size == 0L) {
            require(offset + CRLF.size == raw.size && raw.matchesAt(offset, CRLF)) {
                "Plugin response trailers are not supported"
            }
            break
        }
        val end = offset + size.toInt()
        require(end + CRLF.size <= raw.size && raw.matchesAt(end, CRLF)) {
            "Truncated plugin chunk"
        }
        chunks += raw.copyOfRange(offset, end)
        total += size.toInt()
        offset = end + CRLF.size
    }
    return ByteArray(total).also { output ->
        var destination = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, destination)
            destination += chunk.size
        }
    }
}

private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
private val CRLF_CRLF = CRLF + CRLF

private fun ByteArray.indexOfSequence(sequence: ByteArray, start: Int = 0): Int {
    if (sequence.isEmpty()) return start.coerceAtMost(size)
    for (index in start..size - sequence.size) {
        if (matchesAt(index, sequence)) return index
    }
    return -1
}

private fun ByteArray.matchesAt(offset: Int, sequence: ByteArray): Boolean =
    offset >= 0 && offset + sequence.size <= size && sequence.indices.all { this[offset + it] == sequence[it] }

private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/** Only connection-establishment failures are safe to retry for requests with side effects. */
private class IosPinnedAddressException(message: String) : IllegalStateException(message)
