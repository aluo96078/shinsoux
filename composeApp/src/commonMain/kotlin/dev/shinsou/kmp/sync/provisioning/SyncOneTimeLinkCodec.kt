package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import dev.shinsou.kmp.ui.SyncLinkAction
import dev.shinsou.kmp.ui.SyncLinkPayload

/** Strict codec for QR links and the copy/paste manual-code fallback. */
object SyncOneTimeLinkCodec {
    fun encodeLink(payload: ParsedSyncOneTimePayload): EphemeralSyncPayload {
        validate(payload)
        val query = buildList {
            add("endpoint=${percentEncode(payload.endpoint)}")
            payload.instanceId?.let { add("instance=${percentEncode(it)}") }
            payload.sessionId?.let { add("session=${percentEncode(it)}") }
            payload.userId?.let { add("user=${percentEncode(it)}") }
            payload.workspaceId?.let { add("workspace=${percentEncode(it)}") }
            payload.secret?.use { add("secret=${percentEncode(it)}") }
        }.joinToString("&")
        return EphemeralSyncPayload("shinsou://sync/${payload.action.wireName}?$query")
    }

    /** Encodes the same one-time link as an opaque manual fallback. It is still a secret. */
    fun encodeManualCode(payload: ParsedSyncOneTimePayload): EphemeralSyncPayload =
        encodeLink(payload).use { link ->
            EphemeralSyncPayload(MANUAL_PREFIX + encodeBase64Url(link.encodeToByteArray()))
        }

    fun parse(value: String): ParsedSyncOneTimePayload {
        val trimmed = value.trim()
        if (trimmed.length !in 1..MAX_PAYLOAD_CHARS) throw SyncProvisioningException("invalid_one_time_payload")
        val link = if (trimmed.startsWith(MANUAL_PREFIX, ignoreCase = true)) {
            val encoded = trimmed.substring(MANUAL_PREFIX.length)
            val bytes = runCatching { decodeBase64Url(encoded) }
                .getOrElse { throw SyncProvisioningException("invalid_manual_code") }
            if (bytes.size !in 1..MAX_LINK_BYTES) throw SyncProvisioningException("invalid_manual_code")
            runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
                .getOrElse { throw SyncProvisioningException("invalid_manual_code") }
        } else {
            trimmed
        }
        return parseLink(link)
    }

    private fun parseLink(link: String): ParsedSyncOneTimePayload {
        if (!link.startsWith("shinsou://sync/", ignoreCase = true) || '#' in link) {
            throw SyncProvisioningException("invalid_one_time_link")
        }
        val routeAndQuery = link.substringAfter("shinsou://sync/", "")
        val route = routeAndQuery.substringBefore('?').lowercase()
        val action = SyncOneTimeAction.entries.firstOrNull { it.wireName == route }
            ?: throw SyncProvisioningException("unsupported_one_time_action")
        val query = parseQuery(routeAndQuery.substringAfter('?', ""))
        if (!query.keys.all { it in ALLOWED_QUERY_FIELDS }) {
            throw SyncProvisioningException("unexpected_one_time_link_field")
        }
        val payload = ParsedSyncOneTimePayload(
            action = action,
            endpoint = query["endpoint"] ?: throw SyncProvisioningException("missing_sync_endpoint"),
            instanceId = query["instance"],
            sessionId = query["session"],
            secret = query["secret"]?.let(::EphemeralSyncPayload),
            userId = query["user"],
            workspaceId = query["workspace"],
        )
        validate(payload)
        return payload
    }

    private fun validate(payload: ParsedSyncOneTimePayload) {
        validateEndpoint(payload.endpoint)
        payload.instanceId?.let {
            if (!UUID.matches(it)) throw SyncProvisioningException("invalid_instance_id")
        }
        payload.sessionId?.let {
            if (!UUID.matches(it)) throw SyncProvisioningException("invalid_session_id")
        }
        payload.userId?.let {
            if (!UUID.matches(it)) throw SyncProvisioningException("invalid_user_id")
        }
        payload.workspaceId?.let {
            if (!UUID.matches(it)) throw SyncProvisioningException("invalid_workspace_id")
        }
        when (payload.action) {
            SyncOneTimeAction.SETUP -> {
                if (payload.sessionId != null || payload.userId != null || payload.workspaceId != null) {
                    throw SyncProvisioningException("invalid_setup_link")
                }
            }
            SyncOneTimeAction.INVITE -> {
                if (payload.secret == null || payload.sessionId != null ||
                    payload.userId != null || payload.workspaceId != null
                ) {
                    throw SyncProvisioningException("invalid_invite_link")
                }
            }
            SyncOneTimeAction.PAIR -> {
                if (payload.secret == null || payload.sessionId == null ||
                    payload.userId != null || payload.workspaceId != null
                ) {
                    throw SyncProvisioningException("invalid_pairing_link")
                }
            }
            SyncOneTimeAction.EMERGENCY_RESET -> {
                if (payload.secret == null || payload.instanceId == null || payload.sessionId == null ||
                    payload.userId == null || payload.workspaceId == null
                ) {
                    throw SyncProvisioningException("invalid_emergency_reset_link")
                }
            }
        }
    }

    private fun validateEndpoint(endpoint: String) {
        if (endpoint.length !in 1..MAX_ENDPOINT_CHARS || !isAllowedSyncEndpoint(endpoint)) {
            throw SyncProvisioningException("invalid_sync_endpoint")
        }
    }

    private fun parseQuery(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        val output = linkedMapOf<String, String>()
        value.split('&').forEach { field ->
            if (field.isBlank()) throw SyncProvisioningException("invalid_one_time_link")
            val key = percentDecode(field.substringBefore('='))?.lowercase()
                ?: throw SyncProvisioningException("invalid_one_time_link")
            val entry = percentDecode(field.substringAfter('=', ""))
                ?: throw SyncProvisioningException("invalid_one_time_link")
            if (key.isBlank() || output.put(key, entry) != null) {
                throw SyncProvisioningException("duplicate_one_time_link_field")
            }
        }
        return output
    }

    private fun percentEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (character.isAsciiUnreserved()) {
                append(character)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun percentDecode(value: String): String? {
        val bytes = ByteArray(value.length)
        var input = 0
        var output = 0
        while (input < value.length) {
            when (val character = value[input]) {
                '%' -> {
                    if (input + 2 >= value.length) return null
                    val high = value[input + 1].digitToIntOrNull(16) ?: return null
                    val low = value[input + 2].digitToIntOrNull(16) ?: return null
                    bytes[output++] = ((high shl 4) or low).toByte()
                    input += 3
                }
                else -> {
                    if (character.code > 0x7f) return null
                    bytes[output++] = character.code.toByte()
                    input++
                }
            }
        }
        return runCatching { bytes.copyOf(output).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private fun Char.isAsciiUnreserved(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_' || this == '.' || this == '~'

    private const val MANUAL_PREFIX = "SX1."
    private const val MAX_PAYLOAD_CHARS = 8 * 1024
    private const val MAX_LINK_BYTES = 6 * 1024
    private const val MAX_ENDPOINT_CHARS = 2_048
    private const val HEX = "0123456789ABCDEF"
    private val ALLOWED_QUERY_FIELDS = setOf("endpoint", "instance", "session", "secret", "user", "workspace")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
}

/** Safe bridge used by ShinsouApp; the caller must consume the returned wrapper immediately. */
fun SyncLinkPayload.asProvisioningControllerInput(): EphemeralSyncPayload {
    val provisioningAction = when (action) {
        SyncLinkAction.SETUP -> SyncOneTimeAction.SETUP
        SyncLinkAction.INVITE -> SyncOneTimeAction.INVITE
        SyncLinkAction.PAIR -> SyncOneTimeAction.PAIR
        SyncLinkAction.RECOVERY -> throw SyncProvisioningException("recovery_link_requires_recovery_controller")
        SyncLinkAction.EMERGENCY_RESET -> SyncOneTimeAction.EMERGENCY_RESET
    }
    return SyncOneTimeLinkCodec.encodeLink(
        ParsedSyncOneTimePayload(
            action = provisioningAction,
            endpoint = endpoint,
            instanceId = instanceId,
            sessionId = sessionId,
            secret = oneTimeSecret,
            userId = userId,
            workspaceId = workspaceId,
        ),
    )
}
