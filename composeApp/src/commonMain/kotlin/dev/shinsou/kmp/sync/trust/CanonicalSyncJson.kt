package dev.shinsou.kmp.sync.trust

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON used by the Worker for signed control-plane manifests.
 *
 * Arrays retain their wire order, object keys are sorted by code point (all protocol keys are
 * ASCII), and numbers are restricted to integral values that JavaScript can represent exactly.
 */
internal fun canonicalSyncJson(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonPrimitive -> canonicalPrimitive(value)
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalSyncJson)
    is JsonObject -> value.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, entry) ->
            "${JsonPrimitive(key)}:${canonicalSyncJson(entry)}"
        }
}

private fun canonicalPrimitive(value: JsonPrimitive): String {
    if (value.isString) return value.toString()
    val content = value.content
    if (content == "true" || content == "false") return content
    val integer = content.toLongOrNull()
        ?: throw DeviceDirectoryTrustException.Malformed("Canonical sync JSON forbids non-integral numbers")
    if (integer !in -MAX_SAFE_JSON_INTEGER..MAX_SAFE_JSON_INTEGER) {
        throw DeviceDirectoryTrustException.Malformed("Canonical sync JSON number exceeds the safe integer range")
    }
    if (content != integer.toString()) {
        throw DeviceDirectoryTrustException.Malformed(
            "Canonical sync JSON number has a non-canonical representation",
        )
    }
    return content
}

private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
