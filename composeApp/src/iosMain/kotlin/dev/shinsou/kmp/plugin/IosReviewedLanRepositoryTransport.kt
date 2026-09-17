@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Network.nw_content_context_create
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_access_establishment_report
import platform.Network.nw_connection_copy_current_path
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
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_error_get_error_code
import platform.Network.nw_establishment_report_get_used_proxy
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_set_prefer_no_proxy
import platform.Network.nw_parameters_set_required_interface_type
import platform.Network.nw_path_copy_effective_remote_endpoint
import platform.Network.nw_protocol_stack_clear_application_protocols
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.Network.nw_tcp_options_set_connection_timeout
import platform.Network.nw_interface_type_wifi
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val REVIEWED_LAN_HOST = "192.168.50.193"
private const val REVIEWED_LAN_PORT = 18_081
private const val REVIEWED_LAN_CONNECT_TIMEOUT_SECONDS: UInt = 3u
private const val REVIEWED_LAN_REQUEST_TIMEOUT_MILLIS: Long = 15_000L
private const val REVIEWED_LAN_RECEIVE_CHUNK_BYTES: UInt = 65_536u
private const val REVIEWED_LAN_HTTP_FRAMING_BYTES: Int = 128 * 1_024

/**
 * Credential-free cleartext transport for one physical-iPhone Debug repository endpoint.
 *
 * It never resolves a name and never shares cookies, authentication, or connection state with the
 * app HTTP clients. The common repository policy independently validates every requested route.
 */
internal class IosReviewedLanRepositoryTransport : PluginHttpTransport {
    private val policy = ReviewedLocalRepositoryPolicy.EXACT_IOS_LAN_192_168_50_193_18081

    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
        require(request.method == "GET" && request.body.isEmpty() && request.headers.isEmpty()) {
            "Reviewed LAN repository accepts only credential-free GET requests"
        }
        require(policy.admitsRequestUrl(request.url)) {
            "Request is outside the reviewed iPhone LAN repository"
        }
        val url = Url(request.url)
        require(url.protocol.name == "http" && url.host == REVIEWED_LAN_HOST && url.port == REVIEWED_LAN_PORT)
        val response = withTimeout(REVIEWED_LAN_REQUEST_TIMEOUT_MILLIS) {
            executeReviewedLanRequest(
                encodedRequest = encodeIosPinnedHttpRequest(request, url),
                maxResponseBytes = request.maxResponseBytes,
            )
        }
        require(response.status !in 300..399 && response.headers.pluginHeaderValues("Location").isEmpty()) {
            "Reviewed LAN repository redirects are forbidden"
        }
        return response.copy(headers = response.headers.filterKeys {
            !it.equals("Set-Cookie", ignoreCase = true)
        })
    }
}

private suspend fun executeReviewedLanRequest(
    encodedRequest: ByteArray,
    maxResponseBytes: Int,
): PluginHttpResponse = suspendCancellableCoroutine { continuation ->
    val endpoint = requireNotNull(nw_endpoint_create_host(REVIEWED_LAN_HOST, REVIEWED_LAN_PORT.toString()))
    val parameters = reviewedLanTcpParameters()
    nw_parameters_set_prefer_no_proxy(parameters, true)
    val connection = requireNotNull(nw_connection_create(endpoint, parameters))
    val queue = requireNotNull(dispatch_queue_create("dev.shinsou.repository.reviewed-lan", null))
    val rawLimit = maxResponseBytes + REVIEWED_LAN_HTTP_FRAMING_BYTES
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
        shinsou_connection_receive(connection, 1u, REVIEWED_LAN_RECEIVE_CHUNK_BYTES) { data, complete, error ->
            if (terminal) return@shinsou_connection_receive
            if (error != null) {
                finish(Result.failure(IllegalStateException(
                    "Reviewed LAN repository receive failed (${nw_error_get_error_code(error)})",
                )))
                return@shinsou_connection_receive
            }
            if (data != null) {
                dispatch_data_apply(data) { _, _, buffer, size ->
                    if (size > (rawLimit - rawSize).toULong()) {
                        finish(Result.failure(IllegalArgumentException("Repository response is too large")))
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
                finish(runCatching { parseIosPinnedHttpResponse(joined, maxResponseBytes) })
            } else {
                receiveNext()
            }
        }
    }

    nw_connection_set_state_changed_handler(connection) { state, error ->
        when (state) {
            nw_connection_state_ready -> if (!started && !terminal) {
                started = true
                nw_connection_access_establishment_report(connection, queue) { report ->
                    if (terminal) return@nw_connection_access_establishment_report
                    if (report == null || nw_establishment_report_get_used_proxy(report) ||
                        !reviewedLanConnectionHasExactRemoteEndpoint(connection)
                    ) {
                        finish(Result.failure(IllegalStateException(
                            "Reviewed LAN repository connection did not use the exact direct endpoint",
                        )))
                        return@nw_connection_access_establishment_report
                    }
                    val requestData = encodedRequest.toNSData()
                    val data = dispatch_data_create(requestData.bytes, requestData.length, queue, null)
                    nw_connection_send(
                        connection,
                        data,
                        requireNotNull(nw_content_context_create("shinsou-reviewed-lan-request")),
                        true,
                    ) { sendError ->
                        requestData.length
                        if (sendError != null) {
                            finish(Result.failure(IllegalStateException(
                                "Reviewed LAN repository send failed (${nw_error_get_error_code(sendError)})",
                            )))
                        } else if (!terminal) {
                            receiveNext()
                        }
                    }
                }
            }
            nw_connection_state_failed -> finish(Result.failure(IllegalStateException(
                "Reviewed LAN repository connection failed (${error?.let(::nw_error_get_error_code) ?: -1})",
            )))
            nw_connection_state_cancelled -> if (!terminal) {
                finish(Result.failure(CancellationException("Reviewed LAN repository connection cancelled")))
            }
        }
    }
    continuation.invokeOnCancellation { nw_connection_cancel(connection) }
    nw_connection_set_queue(connection, queue)
    nw_connection_start(connection)
}

/** Explicit cleartext TCP stack; no URL loading, TLS, DNS, cookie, or credential machinery. */
private fun reviewedLanTcpParameters(): platform.darwin.NSObject {
    val parameters = requireNotNull(nw_parameters_create())
    val stack = requireNotNull(nw_parameters_copy_default_protocol_stack(parameters))
    nw_protocol_stack_clear_application_protocols(stack)
    val tcpOptions = requireNotNull(nw_tcp_create_options())
    nw_tcp_options_set_connection_timeout(tcpOptions, REVIEWED_LAN_CONNECT_TIMEOUT_SECONDS)
    nw_protocol_stack_set_transport_protocol(stack, tcpOptions)
    nw_parameters_set_required_interface_type(parameters, nw_interface_type_wifi)
    return parameters
}

private fun reviewedLanConnectionHasExactRemoteEndpoint(connection: platform.darwin.NSObject): Boolean {
    val path = nw_connection_copy_current_path(connection) ?: return false
    val endpoint = nw_path_copy_effective_remote_endpoint(path) ?: return false
    val host = nw_endpoint_get_hostname(endpoint)?.toKString() ?: return false
    return host == REVIEWED_LAN_HOST && nw_endpoint_get_port(endpoint).toInt() == REVIEWED_LAN_PORT
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}
