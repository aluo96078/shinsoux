package dev.shinsou.kmp.sync.v3

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.content.TextContent
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer

/**
 * Hand-written client for the sync server's REST and WebSocket API. Every request carries
 * `Shinsou-Api-Version: 1`; failures surface as [SyncApiException] with the server's error code.
 */
internal class SyncApi(http: HttpClient) {
    private val client = http
    private val webSocketClient by lazy {
        http.config {
            install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MILLIS }
        }
    }

    suspend fun instance(baseUrl: String): WireInstance =
        call(baseUrl, HttpMethod.Get, "/.well-known/shinsou", null, null, null, WireInstance.serializer())!!

    suspend fun register(baseUrl: String, request: WireRegisterRequest): WireSession = call(
        baseUrl, HttpMethod.Post, "/api/v1/auth/register", null, request, WireRegisterRequest.serializer(), WireSession.serializer(),
    )!!

    suspend fun login(baseUrl: String, request: WireLoginRequest): WireSession = call(
        baseUrl, HttpMethod.Post, "/api/v1/auth/login", null, request, WireLoginRequest.serializer(), WireSession.serializer(),
    )!!

    suspend fun refresh(baseUrl: String, refreshToken: String): WireTokens = call(
        baseUrl, HttpMethod.Post, "/api/v1/auth/refresh", null, WireRefreshRequest(refreshToken),
        WireRefreshRequest.serializer(), WireTokens.serializer(),
    )!!

    suspend fun logout(baseUrl: String, token: String) {
        call<Unit, Unit>(baseUrl, HttpMethod.Post, "/api/v1/auth/logout", token, null, null, null)
    }

    suspend fun listDevices(baseUrl: String, token: String): List<WireDevice> = call(
        baseUrl, HttpMethod.Get, "/api/v1/devices", token, null, null, WireDevices.serializer(),
    )!!.devices

    suspend fun renameDevice(baseUrl: String, token: String, id: String, name: String): WireDevice = call(
        baseUrl, HttpMethod.Patch, "/api/v1/devices/$id", token, WireRename(name), WireRename.serializer(), WireDevice.serializer(),
    )!!

    suspend fun signOutDevice(baseUrl: String, token: String, id: String) {
        call<Unit, Unit>(baseUrl, HttpMethod.Delete, "/api/v1/devices/$id", token, null, null, null)
    }

    suspend fun push(baseUrl: String, token: String, request: WirePushRequest): WirePushResponse = call(
        baseUrl, HttpMethod.Post, "/api/v1/sync/push", token, request, WirePushRequest.serializer(), WirePushResponse.serializer(),
    )!!

    suspend fun changes(baseUrl: String, token: String, since: Long, limit: Int = PAGE_SIZE): WireChanges = call<Unit, WireChanges>(
        baseUrl, HttpMethod.Get, "/api/v1/sync/changes?since=$since&limit=$limit", token, null, null, WireChanges.serializer(),
    )!!

    // ---- sharing ----------------------------------------------------------------------------

    suspend fun searchUsers(baseUrl: String, token: String, query: String, limit: Int): List<WireUserSummary> = call<Unit, WireUserList>(
        baseUrl, HttpMethod.Get, "/api/v1/users?q=${query.encodeURLParameter()}&limit=$limit", token, null, null, WireUserList.serializer(),
    )!!.users

    /** [relation] is `following`, `followers` or `blocks`. */
    suspend fun listRelation(baseUrl: String, token: String, relation: String, offset: Int, limit: Int): WireUserPage =
        call<Unit, WireUserPage>(
            baseUrl, HttpMethod.Get, "/api/v1/me/$relation?offset=$offset&limit=$limit", token, null, null, WireUserPage.serializer(),
        )!!

    /** Adds ([on]) or removes a follow (`following`) or a block (`blocks`). */
    suspend fun setRelation(baseUrl: String, token: String, relation: String, userId: String, on: Boolean) {
        call<Unit, Unit>(
            baseUrl, if (on) HttpMethod.Put else HttpMethod.Delete, "/api/v1/me/$relation/${userId.encodeURLPathPart()}", token, null, null, null,
        )
    }

    suspend fun sharingPreferences(baseUrl: String, token: String): WireSharingPreferences = call<Unit, WireSharingPreferences>(
        baseUrl, HttpMethod.Get, "/api/v1/me/sharing", token, null, null, WireSharingPreferences.serializer(),
    )!!

    suspend fun setSharingPreferences(baseUrl: String, token: String, receiveFrom: String): WireSharingPreferences = call(
        baseUrl, HttpMethod.Put, "/api/v1/me/sharing", token, WireSharingPreferences(receiveFrom),
        WireSharingPreferences.serializer(), WireSharingPreferences.serializer(),
    )!!

    suspend fun listShares(baseUrl: String, token: String, sent: Boolean, offset: Int, limit: Int): WireSharePage = call<Unit, WireSharePage>(
        baseUrl, HttpMethod.Get, "/api/v1/shares?box=${if (sent) "sent" else "inbox"}&offset=$offset&limit=$limit", token,
        null, null, WireSharePage.serializer(),
    )!!

    suspend fun pendingShares(baseUrl: String, token: String): Int = call<Unit, WireShareSummary>(
        baseUrl, HttpMethod.Get, "/api/v1/shares/summary", token, null, null, WireShareSummary.serializer(),
    )!!.pending

    suspend fun createShare(baseUrl: String, token: String, request: WireShareRequest): WireShare = call(
        baseUrl, HttpMethod.Post, "/api/v1/shares", token, request, WireShareRequest.serializer(), WireShare.serializer(),
    )!!

    /** [action] is `accept` or `dismiss`. */
    suspend fun answerShare(baseUrl: String, token: String, id: String, action: String): WireShare = call<Unit, WireShare>(
        baseUrl, HttpMethod.Post, "/api/v1/shares/${id.encodeURLPathPart()}/$action", token, null, null, WireShare.serializer(),
    )!!

    suspend fun deleteShare(baseUrl: String, token: String, id: String) {
        call<Unit, Unit>(baseUrl, HttpMethod.Delete, "/api/v1/shares/${id.encodeURLPathPart()}", token, null, null, null)
    }

    /**
     * Holds a WebSocket open and reports every `{"seq": N}` message. Returns when the server (or
     * network) closes the connection; cancellation closes it from our side.
     */
    suspend fun stream(baseUrl: String, token: String, onSeq: suspend (Long) -> Unit) {
        val wsUrl = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            else -> "ws://" + baseUrl.removePrefix("http://")
        } + "/api/v1/sync/stream"
        try {
            webSocketClient.webSocket(
                urlString = wsUrl,
                request = {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(API_VERSION_HEADER, API_VERSION)
                    // The session lives for hours; the request timeout is for plain calls.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                },
            ) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val message = runCatching {
                            SyncWireJson.decodeFromString(WireStreamMessage.serializer(), frame.readText())
                        }.getOrNull() ?: continue
                        onSeq(message.seq)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SyncApiException) {
            throw error
        } catch (error: Throwable) {
            throw SyncApiException(0, "network", error.message ?: "WebSocket failed", cause = error)
        }
    }

    private suspend fun <B, R> call(
        baseUrl: String,
        method: HttpMethod,
        path: String,
        token: String?,
        body: B?,
        bodySerializer: KSerializer<B>?,
        responseSerializer: KSerializer<R>?,
    ): R? {
        val status: Int
        val text: String
        try {
            val response = client.request {
                this.method = method
                url(baseUrl + path)
                header(API_VERSION_HEADER, API_VERSION)
                header(HttpHeaders.Accept, "application/json")
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null && bodySerializer != null) {
                    setBody(TextContent(SyncWireJson.encodeToString(bodySerializer, body), ContentType.Application.Json))
                }
            }
            status = response.status.value
            text = response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw SyncApiException(0, "network", error.message ?: error::class.simpleName.orEmpty(), cause = error)
        }
        if (status !in 200..299) throw failure(status, text)
        if (responseSerializer == null || status == 204 || text.isBlank()) return null
        return try {
            SyncWireJson.decodeFromString(responseSerializer, text)
        } catch (error: Throwable) {
            throw SyncApiException(status, "bad_response", "Unexpected server response", cause = error)
        }
    }

    private fun failure(status: Int, text: String): SyncApiException {
        val envelope = runCatching { SyncWireJson.decodeFromString(WireErrorEnvelope.serializer(), text) }.getOrNull()
        val code = envelope?.error?.code?.takeIf { it.isNotEmpty() } ?: when (status) {
            401 -> "unauthorized"
            410 -> "resync_required"
            426 -> "unsupported_api_version"
            429 -> "rate_limited"
            else -> "http_$status"
        }
        return SyncApiException(
            status = status,
            code = code,
            message = envelope?.error?.message?.takeIf { it.isNotEmpty() } ?: "HTTP $status",
            retryAfterSeconds = envelope?.error?.retryAfterSeconds,
        )
    }

    companion object {
        const val API_VERSION_HEADER: String = "Shinsou-Api-Version"
        const val API_VERSION: String = "1"
        const val PAGE_SIZE: Int = 500
        private const val PING_INTERVAL_MILLIS: Long = 25_000L
    }
}
