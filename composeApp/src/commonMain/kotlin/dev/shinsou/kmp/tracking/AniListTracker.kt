package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerIds
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

@Serializable
data class AniListTrackerConfig(
    val clientId: String,
    val redirectUri: String? = null,
    val apiUrl: String = "https://graphql.anilist.co",
    val authorizationUrl: String = "https://anilist.co/api/v2/oauth/authorize",
) {
    init {
        require(clientId.isNotBlank()) { "AniList clientId must be supplied by configuration" }
    }
}

class AniListTracker(
    private val client: HttpClient,
    private val tokenStore: TokenStore,
    private val config: AniListTrackerConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TrackerAdapter {
    override val descriptor = TrackerDescriptor(
        id = TrackerIds.ANI_LIST,
        name = "AniList",
        scoreFormat = TrackerScoreFormat.POINT_10_DECIMAL,
        supportsReadingDates = true,
        supportsPrivateTracking = true,
    )

    private val tokenKey = trackerTokenKey(descriptor.id)

    override suspend fun isAuthenticated(): Boolean = tokenStore.read(tokenKey)
        ?.isExpired(Clock.System.now().toEpochMilliseconds()) == false

    fun authorizationUrl(state: String? = null): String = URLBuilder(config.authorizationUrl).apply {
        parameters.append("client_id", config.clientId)
        parameters.append("response_type", "token")
        config.redirectUri?.let { parameters.append("redirect_uri", it) }
        state?.let { parameters.append("state", it) }
    }.buildString()

    suspend fun storeAccessToken(
        accessToken: String,
        expiresAt: Long? = null,
        scopes: Set<String> = emptySet(),
    ) {
        storeToken(OAuthToken(accessToken, expiresAt = expiresAt, scopes = scopes))
    }

    suspend fun storeToken(token: OAuthToken) = tokenStore.write(tokenKey, token)

    suspend fun logout() = tokenStore.clear(tokenKey)

    override suspend fun search(query: String, limit: Int): List<TrackSearch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val data = execute(
            query = SEARCH_QUERY,
            variables = buildJsonObject {
                put("search", query)
                put("perPage", limit.coerceIn(1, 50))
            },
            authenticated = false,
        )
        val media = data["Page"]?.jsonObject?.get("media")?.jsonArray.orEmpty()
        return media.mapNotNull(::parseSearch)
    }

    override suspend fun bind(mangaId: Long, remote: TrackSearch): Track {
        val initial = Track(
            mangaId = mangaId,
            trackerId = descriptor.id,
            remoteId = remote.id,
            title = remote.title,
            totalChapters = remote.totalChapters,
            status = TrackStatus.PLAN_TO_READ.rawValue,
            remoteUrl = "https://anilist.co/manga/${remote.id}",
        )
        return update(initial, TrackUpdate(status = TrackStatus.PLAN_TO_READ))
    }

    override suspend fun refresh(track: Track): Track {
        val data = execute(
            query = REFRESH_QUERY,
            variables = buildJsonObject { put("mediaId", track.remoteId) },
            authenticated = true,
        )
        val entry = data["MediaList"]?.takeUnless { it is JsonNull }?.jsonObject
            ?: throw TrackerHttpException(404, "AniList media list entry ${track.remoteId} was not found")
        return parseTrack(track, entry)
    }

    override suspend fun update(track: Track, update: TrackUpdate): Track {
        if (track.remoteId <= 0) throw IllegalArgumentException("AniList remoteId must be positive")
        val variables = buildJsonObject {
            put("mediaId", track.remoteId)
            update.progress?.let { put("progress", it.coerceAtLeast(0.0).toInt()) }
            update.status?.let { put("status", aniListStatus(it)) }
            update.score?.let { put("score", it.coerceIn(0.0, 10.0)) }
            update.startDate?.let { put("startedAt", fuzzyDateInput(it)) }
            update.finishDate?.let { put("completedAt", fuzzyDateInput(it)) }
        }
        val data = execute(UPDATE_MUTATION, variables, authenticated = true)
        val entry = data["SaveMediaListEntry"]?.jsonObject
            ?: throw TrackerHttpException(500, "AniList update returned no media list entry")
        return parseTrack(update.applyTo(track), entry)
    }

    private suspend fun execute(query: String, variables: JsonObject, authenticated: Boolean): JsonObject {
        val response = client.post(config.apiUrl) {
            contentType(ContentType.Application.Json)
            if (authenticated) {
                val token = requireToken()
                header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            }
            setBody(buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw TrackerHttpException(response.status.value, body)
        val root = json.parseToJsonElement(body).jsonObject
        val errors = root["errors"]?.jsonArray
        if (!errors.isNullOrEmpty()) {
            val message = errors.joinToString("; ") { it.jsonObject["message"]?.jsonPrimitive?.content ?: it.toString() }
            throw TrackerHttpException(response.status.value, message)
        }
        return root["data"]?.jsonObject ?: throw TrackerHttpException(response.status.value, "AniList response has no data")
    }

    private suspend fun requireToken(): OAuthToken {
        val token = tokenStore.read(tokenKey)
            ?: throw TrackerAuthenticationException("AniList authentication is required")
        if (token.isExpired(Clock.System.now().toEpochMilliseconds())) {
            tokenStore.clear(tokenKey)
            throw TrackerAuthenticationException("AniList authorization expired; sign in again")
        }
        return token
    }

    private fun parseSearch(element: JsonElement): TrackSearch? {
        val media = element.jsonObject
        val id = media["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val titleObject = media["title"]?.jsonObject
        val title = titleObject?.preferredTitle() ?: return null
        return TrackSearch(
            id = id,
            title = title,
            totalChapters = media["chapters"]?.jsonPrimitive?.intOrNull ?: 0,
            coverUrl = media["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull.orEmpty(),
            summary = media["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishingStatus = media["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishingType = media["format"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startDate = formatFuzzyDate(media["startDate"]),
        )
    }

    private fun parseTrack(base: Track, entry: JsonObject): Track {
        val media = entry["media"]?.jsonObject
        val title = media?.get("title")?.jsonObject?.preferredTitle() ?: base.title
        return base.copy(
            remoteId = entry["mediaId"]?.jsonPrimitive?.content?.toLongOrNull() ?: base.remoteId,
            title = title,
            lastChapterRead = entry["progress"]?.jsonPrimitive?.doubleOrNull ?: base.lastChapterRead,
            totalChapters = media?.get("chapters")?.jsonPrimitive?.intOrNull ?: base.totalChapters,
            status = trackStatus(entry["status"]?.jsonPrimitive?.contentOrNull)?.rawValue ?: base.status,
            score = entry["score"]?.jsonPrimitive?.doubleOrNull ?: base.score,
            remoteUrl = media?.get("siteUrl")?.jsonPrimitive?.contentOrNull ?: base.remoteUrl,
            startDate = fuzzyDateMillis(entry["startedAt"]) ?: base.startDate,
            finishDate = fuzzyDateMillis(entry["completedAt"]) ?: base.finishDate,
        )
    }

    private fun JsonObject.preferredTitle(): String? =
        this["userPreferred"]?.jsonPrimitive?.contentOrNull
            ?: this["english"]?.jsonPrimitive?.contentOrNull
            ?: this["romaji"]?.jsonPrimitive?.contentOrNull
            ?: this["native"]?.jsonPrimitive?.contentOrNull

    private fun fuzzyDateInput(epochMillis: Long): JsonElement {
        if (epochMillis <= 0) return JsonNull
        val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date
        return buildJsonObject {
            put("year", date.year)
            put("month", date.monthNumber)
            put("day", date.dayOfMonth)
        }
    }

    private fun fuzzyDateMillis(element: JsonElement?): Long? {
        val date = element?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        val year = date["year"]?.jsonPrimitive?.intOrNull ?: return null
        val month = date["month"]?.jsonPrimitive?.intOrNull ?: return null
        val day = date["day"]?.jsonPrimitive?.intOrNull ?: return null
        return runCatching { LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
    }

    private fun formatFuzzyDate(element: JsonElement?): String {
        val date = element?.takeUnless { it is JsonNull }?.jsonObject ?: return ""
        val year = date["year"]?.jsonPrimitive?.intOrNull ?: return ""
        val month = date["month"]?.jsonPrimitive?.intOrNull ?: return year.toString()
        val day = date["day"]?.jsonPrimitive?.intOrNull ?: return "$year-${month.twoDigits()}"
        return "$year-${month.twoDigits()}-${day.twoDigits()}"
    }

    private fun aniListStatus(status: TrackStatus): String = when (status) {
        TrackStatus.READING -> "CURRENT"
        TrackStatus.COMPLETED -> "COMPLETED"
        TrackStatus.ON_HOLD -> "PAUSED"
        TrackStatus.DROPPED -> "DROPPED"
        TrackStatus.PLAN_TO_READ -> "PLANNING"
        TrackStatus.REREADING -> "REPEATING"
    }

    private fun trackStatus(status: String?): TrackStatus? = when (status) {
        "CURRENT" -> TrackStatus.READING
        "COMPLETED" -> TrackStatus.COMPLETED
        "PAUSED" -> TrackStatus.ON_HOLD
        "DROPPED" -> TrackStatus.DROPPED
        "PLANNING" -> TrackStatus.PLAN_TO_READ
        "REPEATING" -> TrackStatus.REREADING
        else -> null
    }

    companion object {
        private const val MEDIA_FIELDS = """
            id
            title { userPreferred romaji english native }
            chapters
            coverImage { large }
            description
            status
            format
            startDate { year month day }
            siteUrl
        """

        private val SEARCH_QUERY = """
            query SearchManga(${DOLLAR}search: String!, ${DOLLAR}perPage: Int!) {
              Page(page: 1, perPage: ${DOLLAR}perPage) {
                media(search: ${DOLLAR}search, type: MANGA, sort: SEARCH_MATCH) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()

        private val REFRESH_QUERY = """
            query RefreshManga(${DOLLAR}mediaId: Int!) {
              MediaList(mediaId: ${DOLLAR}mediaId, type: MANGA) {
                id mediaId status progress score(format: POINT_10_DECIMAL)
                startedAt { year month day }
                completedAt { year month day }
                media { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()

        private val UPDATE_MUTATION = """
            mutation UpdateManga(
              ${DOLLAR}mediaId: Int!, ${DOLLAR}status: MediaListStatus, ${DOLLAR}score: Float,
              ${DOLLAR}progress: Int, ${DOLLAR}startedAt: FuzzyDateInput, ${DOLLAR}completedAt: FuzzyDateInput
            ) {
              SaveMediaListEntry(
                mediaId: ${DOLLAR}mediaId, status: ${DOLLAR}status, score: ${DOLLAR}score,
                progress: ${DOLLAR}progress, startedAt: ${DOLLAR}startedAt, completedAt: ${DOLLAR}completedAt
              ) {
                id mediaId status progress score(format: POINT_10_DECIMAL)
                startedAt { year month day }
                completedAt { year month day }
                media { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()

        private const val DOLLAR = '$'
    }
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
