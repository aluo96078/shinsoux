package dev.shinsou.kmp.plugin

/** Bika's imported token authenticates its signed API independently of a WebView cookie jar. */
internal fun bikaPinnedSessionRequest(
    sourceOrigin: String,
    allowedOrigins: Set<String>,
    request: PluginHttpRequest,
    resolution: PluginHostResolution,
    userAgent: String,
): PluginHttpRequest {
    val prepared = preparePluginBrowserSessionRequest(sourceOrigin, allowedOrigins, request)
    require(prepared.sourceOrigin == "https://manhuabika.com" &&
        prepared.targetOrigin == "https://picaapi.go2778.com" &&
        resolution.host == "picaapi.go2778.com"
    ) { "Native session transport is unavailable for this source" }
    require(userAgent.isNotBlank() && userAgent.length <= 512 && userAgent.none(Char::isISOControl))
    return prepared.request.copy(headers = prepared.request.headers + mapOf(
        "Origin" to prepared.sourceOrigin,
        "Referer" to "${prepared.sourceOrigin}/",
        "User-Agent" to userAgent,
    ))
}
