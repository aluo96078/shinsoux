package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.v2.CheckpointAnnouncement
import dev.shinsou.kmp.sync.v2.RealtimeWorkspaceClient
import dev.shinsou.kmp.sync.v2.RealtimeWorkspaceMessage
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Hibernatable WorkspaceHub client. Credentials are sent only in the Authorization header. */
class KtorRealtimeWorkspaceClient(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val api: KtorCloudflareSyncApi,
) : RealtimeWorkspaceClient {
    private val mutex = Mutex()
    private var socket: DefaultClientWebSocketSession? = null
    private var receiverJob: Job? = null

    override suspend fun connect(
        session: SyncSession,
        capability: WorkspaceCapability,
        cursor: Long,
        onMessage: suspend (RealtimeWorkspaceMessage) -> Unit,
    ) {
        require(cursor >= 0)
        val endpoint = session.endpoint.trimEnd('/')
        require(isAllowedSyncEndpoint(endpoint)) {
            "Sync endpoint must use HTTPS (HTTP is only allowed for local tests)"
        }
        val websocketEndpoint = websocketEndpointForSync(endpoint)
        close()
        val opened = client.webSocketSession {
            url("$websocketEndpoint/v1/workspaces/${session.workspaceId}/stream?cursor=$cursor")
            header(HttpHeaders.Authorization, "Bearer ${capability.token.asUtf8()}")
        }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (frame in opened.incoming) {
                    if (frame !is Frame.Text) continue
                    val message = decodeMessage(frame.readText())
                    onMessage(message)
                    if (message is RealtimeWorkspaceMessage.Event) {
                        opened.send(
                            Frame.Text(
                                "{\"type\":\"ack\",\"workspaceSeq\":${message.event.workspaceSeq}}",
                            ),
                        )
                    }
                    if (message is RealtimeWorkspaceMessage.ReauthRequired) return@launch
                }
                // A clean remote close is still a delivery interruption and must refresh auth.
                onMessage(RealtimeWorkspaceMessage.ReauthRequired)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runCatching { onMessage(RealtimeWorkspaceMessage.ReauthRequired) }
            } finally {
                val current = currentCoroutineContext()[Job]
                mutex.withLock {
                    if (socket === opened) socket = null
                    if (receiverJob === current) receiverJob = null
                }
            }
        }
        mutex.withLock {
            socket = opened
            receiverJob = launched
        }
        launched.start()
    }

    override suspend fun close() {
        val caller = currentCoroutineContext()[Job]
        val currentJob: Job?
        val currentSocket: DefaultClientWebSocketSession?
        mutex.withLock {
            currentJob = receiverJob
            currentSocket = socket
            receiverJob = null
            socket = null
        }
        // Reauth is delivered by the receiver itself. Joining that same Job deadlocks forever.
        if (currentJob !== caller) currentJob?.cancelAndJoin()
        currentSocket?.close(CloseReason(CloseReason.Codes.NORMAL, "sync lifecycle transition"))
    }

    private fun decodeMessage(text: String): RealtimeWorkspaceMessage {
        require(text.length <= MAX_MESSAGE_CHARS) { "Realtime message is too large" }
        val objectValue = SyncNetworkJson.parseToJsonElement(text).jsonObject
        return when (objectValue.requiredString("type")) {
            "hello" -> RealtimeWorkspaceMessage.Hello(
                headSeq = objectValue.requiredLong("headSeq"),
                stableCheckpointSeq = objectValue.requiredLong("stableCheckpointSeq"),
            )
            "event" -> {
                val workspaceSeq = objectValue.requiredLong("workspaceSeq")
                val wire = SyncNetworkJson.decodeFromJsonElement<EventUploadDto>(
                    objectValue["envelope"]?.jsonObject
                        ?: throw IllegalArgumentException("Realtime event omitted envelope"),
                )
                RealtimeWorkspaceMessage.Event(api.parseRealtimeEvent(workspaceSeq, wire))
            }
            "checkpointAvailable" -> RealtimeWorkspaceMessage.CheckpointAvailable(
                CheckpointAnnouncement(
                    checkpointId = objectValue.requiredString("checkpointId"),
                    throughWorkspaceSeq = objectValue.optionalLong("throughWorkspaceSeq")
                        ?: objectValue.requiredLong("throughSeq"),
                    keyEpoch = objectValue.requiredLong("keyEpoch").toIntExact("keyEpoch"),
                    ciphertextSha256Base64Url = objectValue.requiredString("ciphertextSha256"),
                    uploaderDeviceId = objectValue.requiredString("uploaderDeviceId"),
                    createdAtMillis = objectValue.requiredLong("createdAt"),
                    previousStableCheckpointId = objectValue.optionalString("previousStableCheckpointId"),
                    previousStableThroughWorkspaceSeq =
                        objectValue.optionalLong("previousStableThroughWorkspaceSeq") ?: 0,
                    previousStableCiphertextSha256Base64Url =
                        objectValue.optionalString("previousStableCheckpointHash"),
                    status = objectValue.requiredString("status").toCheckpointStatus(),
                ),
            )
            "resyncRequired" -> RealtimeWorkspaceMessage.ResyncRequired(
                stableCheckpointSeq = objectValue.requiredLong("stableCheckpointSeq"),
                headSeq = objectValue.requiredLong("headSeq"),
            )
            "reauthRequired" -> RealtimeWorkspaceMessage.ReauthRequired
            else -> throw IllegalArgumentException("Unsupported realtime message type")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Realtime message omitted $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.long ?: throw IllegalArgumentException("Realtime message omitted $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.content?.takeUnless { it == "null" }

    private fun JsonObject.optionalLong(name: String): Long? = this[name]?.jsonPrimitive?.long

    private fun Long.toIntExact(name: String): Int {
        if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("Realtime $name is out of range")
        }
        return toInt()
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 256 * 1024
    }
}

/** Converts the persisted REST origin to the explicit WebSocket origin required by Ktor. */
internal fun websocketEndpointForSync(endpoint: String): String {
    val normalized = endpoint.trim().trimEnd('/')
    require(isAllowedSyncEndpoint(normalized)) {
        "Sync endpoint must use HTTPS (HTTP is only allowed for local tests)"
    }
    // Ktor's webSocketSession builder defaults to `ws://`, and its `url(...)` block accepts the
    // scheme supplied by the string. The REST endpoint is intentionally stored as https/http,
    // but a WebSocket upgrade must use wss/ws explicitly; otherwise Darwin can issue a normal
    // HTTP request and repeatedly tear down the socket before any messages.
    return when {
        normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
        normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
        else -> throw IllegalArgumentException("Unsupported sync endpoint scheme")
    }
}
