package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.SyncLinkAction
import dev.shinsou.kmp.ui.SyncLinkPayload
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload

/** Parses the URL contract already published by the native Shinsou application. */
object DeepLinkParser {
    fun parse(rawValue: String): ShinsouDeepLink? {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("shinsou://", ignoreCase = true)) return null
        if (trimmed.length > MAX_DEEP_LINK_LENGTH || '#' in trimmed) return null
        val routeValue = trimmed.substringAfter("://")
        val route = routeValue.substringBefore('?').trim('/').split('/')
        return when (route.firstOrNull()?.lowercase()) {
            "library" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Library)
            "updates" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Updates)
            "history" -> ShinsouDeepLink.OpenSection(DeepLinkSection.History)
            "browse" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Browse)
            "more" -> ShinsouDeepLink.OpenSection(DeepLinkSection.More)
            "settings" -> ShinsouDeepLink.OpenSettings
            "manga" -> route.getOrNull(1)?.toLongOrNull()?.let(ShinsouDeepLink::OpenManga)
            "chapter" -> {
                val chapterId = route.getOrNull(1)?.toLongOrNull() ?: return null
                val mangaId = route.getOrNull(2)?.toLongOrNull() ?: -1L
                ShinsouDeepLink.OpenChapter(mangaId = mangaId, chapterId = chapterId)
            }
            "sync" -> parseSyncLink(route, routeValue.substringAfter('?', missingDelimiterValue = ""))
            else -> null
        }
    }

    private fun parseSyncLink(route: List<String>, rawQuery: String): ShinsouDeepLink? {
        val action = when (route.getOrNull(1)?.lowercase()) {
            "setup" -> SyncLinkAction.SETUP
            "invite" -> SyncLinkAction.INVITE
            "pair" -> SyncLinkAction.PAIR
            "recovery" -> SyncLinkAction.RECOVERY
            "emergency-reset" -> SyncLinkAction.EMERGENCY_RESET
            else -> return null
        }
        val query = parseQuery(rawQuery) ?: return null
        if (!query.keys.all { it in SYNC_QUERY_FIELDS }) return null
        val endpoint = query["endpoint"]?.let(::normalizeSyncEndpoint) ?: return null
        val secret = query["secret"]?.takeIf(::isSafeOpaqueValue)
        val sessionId = query["session"]?.takeIf(::isSafeOpaqueValue)
        val instanceId = query["instance"]?.takeIf { UUID_PATTERN.matches(it) }
        val userId = query["user"]?.takeIf { UUID_PATTERN.matches(it) }
        val workspaceId = query["workspace"]?.takeIf { UUID_PATTERN.matches(it) }
        if (query["instance"] != null && instanceId == null) return null
        if (action != SyncLinkAction.SETUP && secret == null) return null
        if (action == SyncLinkAction.PAIR && sessionId == null) return null
        if (action == SyncLinkAction.EMERGENCY_RESET &&
            (instanceId == null || sessionId?.let(UUID_PATTERN::matches) != true ||
                userId == null || workspaceId == null)
        ) return null
        if (action != SyncLinkAction.EMERGENCY_RESET && (userId != null || workspaceId != null)) return null
        return ShinsouDeepLink.OpenSyncLink(
            SyncLinkPayload(
                action = action,
                endpoint = endpoint,
                oneTimeSecret = secret?.let(::EphemeralSyncPayload),
                sessionId = sessionId,
                instanceId = instanceId,
                userId = userId,
                workspaceId = workspaceId,
            ),
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String>? {
        if (rawQuery.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { field ->
            if (field.isEmpty()) return@forEach
            val rawKey = field.substringBefore('=')
            val rawValue = field.substringAfter('=', missingDelimiterValue = "")
            val key = percentDecode(rawKey)?.lowercase() ?: return null
            val value = percentDecode(rawValue) ?: return null
            if (key.isBlank() || result.put(key, value) != null) return null
        }
        return result
    }

    private fun percentDecode(value: String): String? {
        val output = ByteArray(value.length)
        var outputIndex = 0
        var index = 0
        while (index < value.length) {
            when (val character = value[index]) {
                '%' -> {
                    if (index + 2 >= value.length) return null
                    val high = value[index + 1].digitToIntOrNull(16) ?: return null
                    val low = value[index + 2].digitToIntOrNull(16) ?: return null
                    output[outputIndex++] = ((high shl 4) or low).toByte()
                    index += 3
                }
                '+' -> {
                    output[outputIndex++] = ' '.code.toByte()
                    index++
                }
                else -> {
                    if (character.code > 0x7f) return null
                    output[outputIndex++] = character.code.toByte()
                    index++
                }
            }
        }
        return runCatching { output.copyOf(outputIndex).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private fun normalizeSyncEndpoint(value: String): String? {
        val endpoint = value.trim().trimEnd('/')
        if (endpoint.length !in 1..MAX_ENDPOINT_LENGTH || '?' in endpoint || '#' in endpoint) return null
        val secure = endpoint.startsWith("https://", ignoreCase = true)
        val local = endpoint.startsWith("http://", ignoreCase = true) && endpoint
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase() in LOCAL_SYNC_HOSTS
        if (!secure && !local) return null
        val authority = endpoint.substringAfter("://").substringBefore('/')
        if (authority.isBlank() || '@' in authority || authority.any(Char::isWhitespace)) return null
        return endpoint
    }

    private fun isSafeOpaqueValue(value: String): Boolean =
        value.length in MIN_OPAQUE_LENGTH..MAX_OPAQUE_LENGTH &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }

    private const val MAX_DEEP_LINK_LENGTH = 4_096
    private const val MAX_ENDPOINT_LENGTH = 2_048
    private const val MIN_OPAQUE_LENGTH = 8
    private const val MAX_OPAQUE_LENGTH = 1_024
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val LOCAL_SYNC_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    private val SYNC_QUERY_FIELDS = setOf("endpoint", "secret", "session", "instance", "user", "workspace")
}
