package dev.shinsou.kmp.plugin

// Only fixed host-authored guidance may escape exception redaction. Accept a bounded engine
// filename/line wrapper, never arbitrary suffixes, URLs, HTML, or substring matches.
private val SOURCE_FAILURE_WRAPPER = Regex(
    "^(?:Error:\\s*)?(SHINSOU_SOURCE_(?:HTTP_(?:CHALLENGE|BLOCKED|FORBIDDEN|UNAVAILABLE)|QUOTA_EXCEEDED))" +
        "(?:\\s*\\([A-Za-z0-9._/-]{1,160}(?:#[0-9]+|:[0-9]+(?::[0-9]+)?)\\))?$",
)

internal fun sourceFailureMarker(message: String): String? {
    if (message.length > 512) return null
    return SOURCE_FAILURE_WRAPPER.matchEntire(message.trim())?.groupValues?.get(1)
}

/** The same bounded, host-owned classification drives both guidance and recovery actions. */
internal fun Throwable.sourceFailureMarker(): String? {
    var error: Throwable? = this
    repeat(8) {
        val current = error ?: return null
        current.message?.let(::sourceFailureMarker)?.let { return it }
        error = current.cause?.takeUnless { it === current }
    }
    return null
}
