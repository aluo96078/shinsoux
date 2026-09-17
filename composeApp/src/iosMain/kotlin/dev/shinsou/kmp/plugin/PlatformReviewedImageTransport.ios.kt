@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Network.nw_content_context_create
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import dev.shinsou.interop.network.shinsou_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_set_local_endpoint
import platform.Network.nw_parameters_set_prefer_no_proxy
import platform.Network.nw_proxy_config_create_http_connect
import platform.Network.nw_proxy_config_set_failover_allowed
import platform.Network.nw_proxy_config_set_username_and_password
import platform.Network.nw_protocol_stack_clear_application_protocols
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.UIKit.UIDevice
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64

public actual fun createPlatformReviewedImageTransport(): PluginHttpTransport? =
    if (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0 >= 17) {
        IosReviewedImageTransport()
    } else null

private class IosReviewedImageTransport : PluginHttpTransport {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
        error("Reviewed browser image transport requires a validated DNS resolution")

    override suspend fun executeResolved(
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse {
        validateReviewedBrowserImageRequest(request, resolution)
        var lastFailure: Throwable? = null
        for (address in resolution.addresses.distinct()) {
            try {
                return executeReviewedImageAtAddress(request, address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IosReviewedImageAddressException) {
                lastFailure = failure
            }
        }
        throw IllegalStateException("Reviewed browser image connection failed", lastFailure)
    }
}

private suspend fun executeReviewedImageAtAddress(
    request: PluginHttpRequest,
    address: String,
): PluginHttpResponse = withTimeout(REVIEWED_IMAGE_TIMEOUT_MILLIS) {
    val credentials = randomProxyCredential() to randomProxyCredential()
    val proxy = IosPinnedConnectProxy(address, credentials.first, credentials.second)
    try {
        val port = proxy.start()
        withContext(Dispatchers.Main) {
            val dataStore = WKWebsiteDataStore.nonPersistentDataStore()
            val endpoint = requireNotNull(nw_endpoint_create_host("127.0.0.1", port.toString()))
            val config = requireNotNull(nw_proxy_config_create_http_connect(endpoint, null))
            nw_proxy_config_set_username_and_password(config, credentials.first, credentials.second)
            nw_proxy_config_set_failover_allowed(config, false)
            dataStore.proxyConfigurations = listOf(config)
            val configuration = WKWebViewConfiguration().apply { websiteDataStore = dataStore }
            val webView = WKWebView(platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0), configuration)
            val ready = CompletableDeferred<Unit>()
            val delegate = ReviewedImageNavigationDelegate(ready)
            webView.navigationDelegate = delegate
            try {
                webView.loadHTMLString(REVIEWED_IMAGE_BOOTSTRAP, NSURL.URLWithString(REVIEWED_IMAGE_ORIGIN))
                ready.await()
                webView.evaluateJavaScriptAwait(reviewedBrowserImageStartScript(request))
                while (true) {
                    delay(REVIEWED_IMAGE_POLL_MILLIS)
                    decodeReviewedBrowserImageResult(
                        webView.evaluateJavaScriptAwait(reviewedBrowserImagePollScript()),
                        request.maxResponseBytes,
                    )?.let { return@withContext it }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Reviewed browser image did not complete")
            } finally {
                withContext(NonCancellable) {
                    runCatching {
                        withTimeout(500L) {
                            webView.evaluateJavaScriptAwait(reviewedBrowserImageCleanupScript())
                        }
                    }
                }
                delegate.release()
                webView.stopLoading()
                webView.navigationDelegate = null
            }
        }
    } finally {
        proxy.close()
    }
}

private class ReviewedImageNavigationDelegate(
    private val ready: CompletableDeferred<Unit>,
) : NSObject(), WKNavigationDelegateProtocol {
    private var released = false
    fun release() { released = true; if (!ready.isCompleted) ready.cancel() }
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!released && !ready.isCompleted) ready.complete(Unit)
    }
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        val bootstrap = target == REVIEWED_IMAGE_ORIGIN || target == "$REVIEWED_IMAGE_ORIGIN/" ||
            target == "about:blank"
        decisionHandler(if (!released && bootstrap) {
            WKNavigationActionPolicy.WKNavigationActionPolicyAllow
        } else {
            WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        })
    }
}

private suspend fun WKWebView.evaluateJavaScriptAwait(script: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        evaluateJavaScript(script) { value, error ->
            if (!continuation.isActive) return@evaluateJavaScript
            if (error != null) continuation.resumeWithException(
                IllegalStateException("Reviewed browser image JavaScript failed"),
            ) else continuation.resume(value as? String)
        }
    }

private class IosPinnedConnectProxy(
    private val approvedAddress: String,
    username: String,
    password: String,
) {
    private val queue = requireNotNull(dispatch_queue_create("dev.shinsou.reviewed-image.proxy", null))
    private val expectedAuthorization = "Basic " + Base64.encode("$username:$password".encodeToByteArray())
    private val ready = CompletableDeferred<UShort>()
    private val lock = SynchronousLock()
    private val connections = mutableSetOf<NSObject>()
    private var closed = false
    private val parameters = rawTcpParameters().also { listenerParameters ->
        nw_parameters_set_local_endpoint(
            listenerParameters,
            requireNotNull(nw_endpoint_create_host("127.0.0.1", "0")),
        )
    }
    private val listener = requireNotNull(nw_listener_create(parameters))

    suspend fun start(): UShort {
        nw_listener_set_state_changed_handler(listener) { state, _ ->
            when (state) {
                nw_listener_state_ready -> if (!ready.isCompleted) ready.complete(nw_listener_get_port(listener))
                nw_listener_state_failed -> if (!ready.isCompleted) ready.completeExceptionally(
                    IosReviewedImageAddressException("Reviewed image proxy failed to listen"),
                )
            }
        }
        nw_listener_set_new_connection_handler(listener) { downstream ->
            if (downstream != null) {
                if (lock.withLock { closed }) nw_connection_cancel(downstream) else accept(downstream)
            }
        }
        nw_listener_set_queue(listener, queue)
        nw_listener_start(listener)
        return ready.await()
    }

    private fun accept(downstream: NSObject) {
        val accepted = lock.withLock {
            if (closed || connections.size >= MAX_PROXY_CONNECTIONS) false
            else { connections += downstream; true }
        }
        if (!accepted) {
            nw_connection_cancel(downstream)
            return
        }
        nw_connection_set_queue(downstream, queue)
        nw_connection_set_state_changed_handler(downstream) { state, _ ->
            if (state == nw_connection_state_ready) readConnectHeader(downstream, ByteArray(0), mayChallenge = true)
            if (state == nw_connection_state_failed) remove(downstream)
        }
        nw_connection_start(downstream)
    }

    private fun readConnectHeader(connection: NSObject, accumulated: ByteArray, mayChallenge: Boolean) {
        shinsou_connection_receive(connection, 1u, PROXY_CHUNK_BYTES) { data, complete, error ->
            val bytes = data?.bytes() ?: ByteArray(0)
            if (error != null || (complete && bytes.isEmpty())) return@shinsou_connection_receive rejectAndClose(connection)
            if (accumulated.size + bytes.size > REVIEWED_IMAGE_CONNECT_MAX_HEADER_BYTES) return@shinsou_connection_receive rejectAndClose(connection)
            val joined = accumulated + bytes
            val end = joined.indexOfHeaderEnd()
            if (end < 0) return@shinsou_connection_receive readConnectHeader(connection, joined, mayChallenge)
            val header = joined.copyOfRange(0, end).decodeToString()
            if (!isValidReviewedImageConnectHeader(header, expectedAuthorization)) {
                return@shinsou_connection_receive if (mayChallenge) challengeAndRetry(connection) else rejectAndClose(connection)
            }
            val remainder = joined.copyOfRange(end + 4, joined.size)
            connectUpstream(connection, remainder)
        }
    }

    private fun connectUpstream(downstream: NSObject, remainder: ByteArray) {
        val endpoint = requireNotNull(nw_endpoint_create_host(approvedAddress, "443"))
        val upstreamParameters = rawTcpParameters()
        nw_parameters_set_prefer_no_proxy(upstreamParameters, true)
        val upstream = requireNotNull(nw_connection_create(endpoint, upstreamParameters))
        val accepted = lock.withLock {
            if (closed) false else { connections += upstream; true }
        }
        if (!accepted) return nw_connection_cancel(upstream)
        nw_connection_set_queue(upstream, queue)
        nw_connection_set_state_changed_handler(upstream) { state, _ ->
            when (state) {
                nw_connection_state_ready -> {
                    send(downstream, CONNECT_ESTABLISHED) {
                    if (remainder.isNotEmpty()) send(upstream, remainder) {
                        relay(downstream, upstream, 0)
                    } else relay(downstream, upstream, 0)
                    relay(upstream, downstream, 0)
                    }
                }
                nw_connection_state_failed -> {
                    rejectAndClose(downstream); remove(upstream)
                }
            }
        }
        nw_connection_start(upstream)
    }

    private fun relay(from: NSObject, to: NSObject, total: Int) {
        shinsou_connection_receive(from, 1u, PROXY_CHUNK_BYTES) { data, complete, error ->
            if (error != null) return@shinsou_connection_receive closePair(from, to)
            val bytes = data?.bytes() ?: ByteArray(0)
            if (total + bytes.size > MAX_RELAY_BYTES) return@shinsou_connection_receive closePair(from, to)
            if (bytes.isEmpty()) return@shinsou_connection_receive closePair(from, to)
            send(to, bytes) {
                if (complete) closePair(from, to) else relay(from, to, total + bytes.size)
            }
        }
    }

    private fun send(connection: NSObject, bytes: ByteArray, complete: () -> Unit = {}) {
        val native = bytes.toNSData()
        val data = dispatch_data_create(native.bytes, native.length, queue, null)
        // Construct an ordinary context instead of bridging Apple's macro singleton. The
        // Kotlin/Native binding can reinterpret that singleton as an Objective-C block.
        val context = requireNotNull(nw_content_context_create("shinsou-reviewed-image"))
        nw_connection_send(connection, data, context, true) { error ->
            native.length
            if (error == null) complete() else remove(connection)
        }
    }

    private fun challengeAndRetry(connection: NSObject) = send(connection, CONNECT_CHALLENGE) {
        readConnectHeader(connection, ByteArray(0), mayChallenge = false)
    }
    private fun rejectAndClose(connection: NSObject) = send(connection, CONNECT_REJECTED) { remove(connection) }
    private fun closePair(first: NSObject, second: NSObject) {
        remove(first); remove(second)
    }
    private fun remove(connection: NSObject) {
        lock.withLock { connections.remove(connection) }
        nw_connection_cancel(connection)
    }
    fun close() {
        val active = lock.withLock {
            closed = true
            connections.toList().also { connections.clear() }
        }
        nw_listener_cancel(listener)
        active.forEach(::nw_connection_cancel)
    }
}

private fun NSObject.bytes(): ByteArray {
    val parts = mutableListOf<ByteArray>()
    var size = 0
    dispatch_data_apply(this) { _, _, buffer, count ->
        val part = buffer?.reinterpret<ByteVar>()?.readBytes(count.toInt()) ?: ByteArray(0)
        parts += part; size += part.size; true
    }
    return ByteArray(size).also { output ->
        var offset = 0
        parts.forEach { it.copyInto(output, offset); offset += it.size }
    }
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun ByteArray.indexOfHeaderEnd(): Int {
    for (index in 0..size - 4) if (
        this[index] == 13.toByte() && this[index + 1] == 10.toByte() &&
        this[index + 2] == 13.toByte() && this[index + 3] == 10.toByte()
    ) return index
    return -1
}

private fun randomProxyCredential(): String {
    val bytes = ByteArray(24)
    bytes.usePinned { pinned ->
        check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), pinned.addressOf(0)) == errSecSuccess)
    }
    return Base64.UrlSafe.encode(bytes).trimEnd('=')
}

/** Explicit TCP-only stack; avoids relying on Kotlin's bridge for the disable-TLS sentinel. */
private fun rawTcpParameters(): NSObject {
    val parameters = requireNotNull(nw_parameters_create())
    val stack = requireNotNull(nw_parameters_copy_default_protocol_stack(parameters))
    nw_protocol_stack_clear_application_protocols(stack)
    nw_protocol_stack_set_transport_protocol(stack, requireNotNull(nw_tcp_create_options()))
    return parameters
}

private class IosReviewedImageAddressException(message: String) : Exception(message)

private const val REVIEWED_IMAGE_ORIGIN = "https://i.motiezw.com"
private const val REVIEWED_IMAGE_TIMEOUT_MILLIS = 30_000L
private const val REVIEWED_IMAGE_POLL_MILLIS = 40L
private const val MAX_RELAY_BYTES = 16 * 1_024 * 1_024
private const val MAX_PROXY_CONNECTIONS = 4
private const val PROXY_CHUNK_BYTES: UInt = 32_768u
private const val REVIEWED_IMAGE_BOOTSTRAP = "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src https://i.motiezw.com\"><title>Shinsou image transport</title>"
private val CONNECT_ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n".encodeToByteArray()
private val CONNECT_CHALLENGE = "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Shinsou\"\r\nConnection: keep-alive\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()
private val CONNECT_REJECTED = "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Shinsou\"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".encodeToByteArray()
