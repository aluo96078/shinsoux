package dev.shinsou.kmp.sync.v3

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Wire shapes of `shinsou_sync_service/api/openapi.yaml` (hand-written; unknown keys are ignored). */
internal val SyncWireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

@Serializable
internal data class WireErrorEnvelope(val error: WireErrorBody)

@Serializable
internal data class WireErrorBody(val code: String = "", val message: String = "", val retryAfterSeconds: Int? = null)

@Serializable
public data class WireApiVersions(val min: Int = 1, val max: Int = 1)

@Serializable
public data class WireFeatures(val sharing: Boolean = false, val reviews: Boolean = false)

@Serializable
public data class WireInstance(
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val apiVersions: WireApiVersions = WireApiVersions(),
    val registration: String = "closed",
    val emailVerification: String = "disabled",
    val termsUrl: String? = null,
    val privacyUrl: String? = null,
    val contact: String? = null,
    val features: WireFeatures = WireFeatures(),
)

@Serializable
internal data class WireDeviceInfo(val name: String, val platform: String, val appVersion: String? = null)

@Serializable
internal data class WireRegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val inviteCode: String? = null,
    val setupCode: String? = null,
    val device: WireDeviceInfo,
)

@Serializable
internal data class WireLoginRequest(val username: String, val password: String, val device: WireDeviceInfo)

@Serializable
internal data class WireRefreshRequest(val refreshToken: String)

@Serializable
internal data class WireTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
)

@Serializable
public data class WireUser(
    val id: String,
    val username: String,
    val handle: String? = null,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val role: String = "user",
    val createdAt: Long = 0,
)

@Serializable
public data class WireDevice(
    val id: String,
    val syncDeviceId: String,
    val name: String,
    val platform: String = "",
    val appVersion: String? = null,
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
    val current: Boolean = false,
)

@Serializable
internal data class WireSession(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val user: WireUser,
    val device: WireDevice,
)

@Serializable
internal data class WireDevices(val devices: List<WireDevice>)

@Serializable
internal data class WireRename(val name: String)

@Serializable
public data class WireWork(
    val keyVersion: Int = 1,
    val packageId: String,
    val sourceId: String,
    val remoteId: String,
    val hash: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val contentKind: String = "manga",
    val language: String? = null,
    val sourceName: String? = null,
    val repositoryUrl: String? = null,
)

@Serializable
public data class WireDeleted(val value: Boolean, val clock: String)

@Serializable
internal data class WirePushRecord(
    val kind: String,
    val key: String,
    val work: String? = null,
    val unitRemoteId: String? = null,
    val fields: JsonObject? = null,
    val clocks: Map<String, String>? = null,
    val deleted: WireDeleted? = null,
)

@Serializable
internal data class WirePushRequest(
    val batchId: String,
    val since: Long,
    val works: List<WireWork>,
    val records: List<WirePushRecord>,
)

@Serializable
public data class WireRecord(
    val kind: String,
    val key: String,
    val seq: Long = 0,
    val work: WireWork? = null,
    val unitRemoteId: String? = null,
    val fields: JsonObject = JsonObject(emptyMap()),
    val clocks: Map<String, String> = emptyMap(),
    val deleted: WireDeleted? = null,
    val cleared: String? = null,
) {
    internal fun toRecordState(): RecordState = RecordState(
        fields = fields.mapValues { (name, value) ->
            FieldState(value, clocks[name] ?: error("Server record $kind/$key has no clock for $name"))
        },
        deleted = deleted?.let { DeletedRegister(it.value, it.clock) },
        cleared = cleared,
    )
}

@Serializable
internal data class WirePushResult(val kind: String, val key: String, val result: String, val record: WireRecord? = null)

@Serializable
internal data class WirePushResponse(val results: List<WirePushResult>, val seq: Long = 0)

@Serializable
public data class WireChanges(val records: List<WireRecord>, val nextSince: Long, val hasMore: Boolean)

@Serializable
internal data class WireStreamMessage(val seq: Long = 0)

// ---- sharing (P4) -------------------------------------------------------------------------------

@Serializable
public data class WireUserSummary(
    val id: String,
    val username: String,
    val following: Boolean = false,
    val followsYou: Boolean = false,
    val blocked: Boolean = false,
    val canShare: Boolean = false,
    val since: Long? = null,
)

@Serializable
internal data class WireUserList(val users: List<WireUserSummary> = emptyList())

@Serializable
public data class WireUserPage(
    val users: List<WireUserSummary> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
internal data class WireSharingPreferences(val receiveFrom: String = "everyone")

@Serializable
internal data class WireShareRequest(val recipientId: String, val work: String, val message: String? = null)

@Serializable
public data class WireShareParty(val id: String, val username: String)

@Serializable
public data class WireShare(
    val id: String,
    val sender: WireShareParty,
    val recipient: WireShareParty,
    val work: WireWork,
    val message: String = "",
    val status: String = "pending",
    val createdAt: Long = 0,
    val respondedAt: Long? = null,
    val inLibrary: Boolean = false,
)

@Serializable
public data class WireSharePage(
    val shares: List<WireShare> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val pending: Int = 0,
)

@Serializable
internal data class WireShareSummary(val pending: Int = 0)

// ---- reviews (P5) -------------------------------------------------------------------------------

@Serializable
public data class WireReview(
    val id: String,
    val author: WireShareParty,
    val rating: Int? = null,
    val body: String = "",
    val spoiler: Boolean = false,
    /** `visible` or `hidden`; hidden reviews are only returned to their author. */
    val status: String = "visible",
    val hiddenReason: String? = null,
    val helpful: Int = 0,
    val votedHelpful: Boolean = false,
    val replyCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val edited: Boolean = false,
    val mine: Boolean = false,
)

@Serializable
public data class WireReviewPage(
    val reviews: List<WireReview> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
public data class WireRating(val average: Double? = null, val count: Int = 0)

@Serializable
public data class WireWorkPage(
    val work: WireWork,
    val rating: WireRating = WireRating(),
    val reviewCount: Int = 0,
    val myReview: WireReview? = null,
    val inLibrary: Boolean = false,
)

@Serializable
public data class WireReply(
    val id: String,
    val reviewId: String,
    val author: WireShareParty,
    val body: String = "",
    val status: String = "visible",
    val hiddenReason: String? = null,
    val createdAt: Long = 0,
    val mine: Boolean = false,
)

@Serializable
public data class WireReplyPage(
    val replies: List<WireReply> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
public data class WireVote(val helpful: Int = 0, val votedHelpful: Boolean = false)

@Serializable
public data class WireReport(
    val id: String,
    val reason: String = "",
    val note: String = "",
    val status: String = "open",
    val createdAt: Long = 0,
)

@Serializable
internal data class WireReviewInput(val rating: Int? = null, val body: String = "", val spoiler: Boolean = false)

@Serializable
internal data class WireReplyInput(val body: String)

@Serializable
internal data class WireReportInput(val reason: String, val note: String? = null)

/** An API failure with the server's stable error [code] (empty for transport failures). */
public class SyncApiException(
    public val status: Int,
    public val code: String,
    message: String,
    public val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public val isUnauthorized: Boolean get() = status == 401
    public val isResyncRequired: Boolean get() = status == 410 || code == "resync_required"

    /** Network failures, timeouts, rate limits and server errors: the same request may work later. */
    public val isTransient: Boolean get() = status == 0 || status == 408 || status == 429 || status >= 500
}
