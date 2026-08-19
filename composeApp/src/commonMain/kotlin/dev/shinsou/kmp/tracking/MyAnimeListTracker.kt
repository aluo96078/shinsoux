package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerIds
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class MyAnimeListTrackerConfig(
    val clientId: String,
    val redirectUri: String? = null,
    val apiUrl: String = "https://api.myanimelist.net",
    val authorizationUrl: String = "https://myanimelist.net/v1/oauth2/authorize",
    val tokenUrl: String = "https://myanimelist.net/v1/oauth2/token",
) {
    init {
        require(clientId.isNotBlank()) {
            "MyAnimeList clientId must be supplied by app configuration; no placeholder client ID is allowed"
        }
    }
}

class MyAnimeListTracker(
    private val client: HttpClient,
    private val tokenStore: TokenStore,
    private val config: MyAnimeListTrackerConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TrackerAdapter {
    override val descriptor = TrackerDescriptor(
        id = TrackerIds.MY_ANIME_LIST,
        name = "MyAnimeList",
        scoreFormat = TrackerScoreFormat.POINT_10,
        supportsReadingDates = true,
    )

    private val tokenKey = trackerTokenKey(descriptor.id)

    override suspend fun isAuthenticated(): Boolean = tokenStore.read(tokenKey) != null

    fun authorizationUrl(codeChallenge: String, state: String? = null): String {
        require(codeChallenge.isNotBlank()) { "MyAnimeList PKCE code challenge cannot be blank" }
        return URLBuilder(config.authorizationUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "plain")
            config.redirectUri?.let { parameters.append("redirect_uri", it) }
            state?.let { parameters.append("state", it) }
        }.buildString()
    }

    suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String, nowMillis: Long): OAuthToken {
        require(code.isNotBlank()) { "Authorization code cannot be blank" }
        require(codeVerifier.isNotBlank()) { "PKCE verifier cannot be blank" }
        val parameters = Parameters.build {
            append("client_id", config.clientId)
            append("grant_type", "authorization_code")
            append("code", code)
            append("code_verifier", codeVerifier)
            config.redirectUri?.let { append("redirect_uri", it) }
        }
        return requestToken(parameters, nowMillis, previousRefreshToken = null)
    }

    suspend fun refreshAccessToken(nowMillis: Long): OAuthToken {
        val existing = tokenStore.read(tokenKey)
            ?: throw TrackerAuthenticationException("MyAnimeList authentication is required")
        val refreshToken = existing.refreshToken
            ?: throw TrackerAuthenticationException("MyAnimeList refresh token is unavailable")
        val parameters = Parameters.build {
            append("client_id", config.clientId)
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
        }
        return requestToken(parameters, nowMillis, refreshToken)
    }

    suspend fun logout() = tokenStore.clear(tokenKey)

    override suspend fun search(query: String, limit: Int): List<TrackSearch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val token = tokenStore.read(tokenKey)
        val response = client.get("${config.apiUrl}/v2/manga") {
            header("X-MAL-CLIENT-ID", config.clientId)
            token?.let { header(HttpHeaders.Authorization, "${it.tokenType} ${it.accessToken}") }
            parameter("q", query)
            parameter("limit", limit.coerceIn(1, 100))
            parameter("fields", SEARCH_FIELDS)
        }
        val body = response.bodyAsText()
        ensureSuccess(response.status.value, response.status.isSuccess(), body)
        val root = json.parseToJsonElement(body).jsonObject
        return root["data"]?.jsonArray.orEmpty().mapNotNull { row ->
            row.jsonObject["node"]?.let(::parseSearch)
        }
    }

    override suspend fun bind(mangaId: Long, remote: TrackSearch): Track {
        val initial = Track(
            mangaId = mangaId,
            trackerId = descriptor.id,
            remoteId = remote.id,
            title = remote.title,
            totalChapters = remote.totalChapters,
            status = TrackStatus.PLAN_TO_READ.rawValue,
            remoteUrl = "https://myanimelist.net/manga/${remote.id}",
        )
        return update(initial, TrackUpdate(status = TrackStatus.PLAN_TO_READ))
    }

    override suspend fun refresh(track: Track): Track {
        val token = requireToken()
        val response = client.get("${config.apiUrl}/v2/manga/${track.remoteId}") {
            header("X-MAL-CLIENT-ID", config.clientId)
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            parameter("fields", DETAIL_FIELDS)
        }
        val body = response.bodyAsText()
        ensureSuccess(response.status.value, response.status.isSuccess(), body)
        return parseTrack(track, json.parseToJsonElement(body).jsonObject)
    }

    override suspend fun update(track: Track, update: TrackUpdate): Track {
        if (track.remoteId <= 0) throw IllegalArgumentException("MyAnimeList remoteId must be positive")
        if (update.isEmpty) return refresh(track)
        val token = requireToken()
        val parameters = Parameters.build {
            update.status?.let { status ->
                append("status", malStatus(status))
                if (status == TrackStatus.REREADING) append("is_rereading", "true")
            }
            update.progress?.let { append("num_chapters_read", it.coerceAtLeast(0.0).toInt().toString()) }
            update.score?.let { append("score", it.coerceIn(0.0, 10.0).toInt().toString()) }
            update.startDate?.let { append("start_date", malDate(it)) }
            update.finishDate?.let { append("finish_date", malDate(it)) }
        }
        val response = client.patch("${config.apiUrl}/v2/manga/${track.remoteId}/my_list_status") {
            header("X-MAL-CLIENT-ID", config.clientId)
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            setBody(FormDataContent(parameters))
        }
        val body = response.bodyAsText()
        ensureSuccess(response.status.value, response.status.isSuccess(), body)
        val combined = update.applyTo(track)
        return parseListStatus(combined, json.parseToJsonElement(body).jsonObject)
    }

    private suspend fun requestToken(
        parameters: Parameters,
        nowMillis: Long,
        previousRefreshToken: String?,
    ): OAuthToken {
        val response = client.post(config.tokenUrl) { setBody(FormDataContent(parameters)) }
        val body = response.bodyAsText()
        ensureSuccess(response.status.value, response.status.isSuccess(), body)
        val tokenJson = json.parseToJsonElement(body).jsonObject
        val accessToken = tokenJson["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw TrackerHttpException(response.status.value, "MyAnimeList token response has no access_token")
        val expiresInSeconds = tokenJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
        val token = OAuthToken(
            accessToken = accessToken,
            tokenType = tokenJson["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
            refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previousRefreshToken,
            expiresAt = expiresInSeconds?.let { nowMillis + it * 1_000 },
            scopes = tokenJson["scope"]?.jsonPrimitive?.contentOrNull
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty(),
        )
        tokenStore.write(tokenKey, token)
        return token
    }

    private suspend fun requireToken(): OAuthToken = tokenStore.read(tokenKey)
        ?: throw TrackerAuthenticationException("MyAnimeList authentication is required")

    private fun parseSearch(element: JsonElement): TrackSearch? {
        val manga = element.jsonObject
        val id = manga["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val title = manga["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val picture = manga["main_picture"]?.takeUnless { it is JsonNull }?.jsonObject
        return TrackSearch(
            id = id,
            title = title,
            totalChapters = manga["num_chapters"]?.jsonPrimitive?.intOrNull ?: 0,
            coverUrl = picture?.get("large")?.jsonPrimitive?.contentOrNull
                ?: picture?.get("medium")?.jsonPrimitive?.contentOrNull.orEmpty(),
            summary = manga["synopsis"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishingStatus = manga["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishingType = manga["media_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startDate = manga["start_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun parseTrack(base: Track, manga: JsonObject): Track {
        val listStatus = manga["my_list_status"]?.takeUnless { it is JsonNull }?.jsonObject
        val withDetails = base.copy(
            remoteId = manga["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: base.remoteId,
            title = manga["title"]?.jsonPrimitive?.contentOrNull ?: base.title,
            totalChapters = manga["num_chapters"]?.jsonPrimitive?.intOrNull ?: base.totalChapters,
            remoteUrl = "https://myanimelist.net/manga/${manga["id"]?.jsonPrimitive?.contentOrNull ?: base.remoteId}",
        )
        return if (listStatus == null) withDetails else parseListStatus(withDetails, listStatus)
    }

    private fun parseListStatus(base: Track, status: JsonObject): Track = base.copy(
        lastChapterRead = status["num_chapters_read"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: base.lastChapterRead,
        status = trackStatus(
            status["status"]?.jsonPrimitive?.contentOrNull,
            status["is_rereading"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true,
        )?.rawValue ?: base.status,
        score = status["score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: base.score,
        startDate = parseMalDate(status["start_date"]?.jsonPrimitive?.contentOrNull) ?: base.startDate,
        finishDate = parseMalDate(status["finish_date"]?.jsonPrimitive?.contentOrNull) ?: base.finishDate,
    )

    private fun malStatus(status: TrackStatus): String = when (status) {
        TrackStatus.READING, TrackStatus.REREADING -> "reading"
        TrackStatus.COMPLETED -> "completed"
        TrackStatus.ON_HOLD -> "on_hold"
        TrackStatus.DROPPED -> "dropped"
        TrackStatus.PLAN_TO_READ -> "plan_to_read"
    }

    private fun trackStatus(status: String?, rereading: Boolean): TrackStatus? = when {
        rereading -> TrackStatus.REREADING
        status == "reading" -> TrackStatus.READING
        status == "completed" -> TrackStatus.COMPLETED
        status == "on_hold" -> TrackStatus.ON_HOLD
        status == "dropped" -> TrackStatus.DROPPED
        status == "plan_to_read" -> TrackStatus.PLAN_TO_READ
        else -> null
    }

    private fun malDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        return Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date.toString()
    }

    private fun parseMalDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
    }

    private fun ensureSuccess(statusCode: Int, success: Boolean, body: String) {
        if (!success) throw TrackerHttpException(statusCode, body)
    }

    companion object {
        private const val SEARCH_FIELDS =
            "id,title,main_picture,alternative_titles,start_date,synopsis,media_type,status,num_chapters"
        private const val DETAIL_FIELDS =
            "id,title,main_picture,start_date,synopsis,media_type,status,num_chapters,my_list_status"
    }
}
