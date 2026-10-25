package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.SourceWebChallengeCapability
import dev.shinsou.kmp.ui.WebChallengeEmbeddedPolicy
import io.ktor.http.Url
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class WebChallengeCapture(
    val capability: SourceWebChallengeCapability,
    val cookies: List<SourceCookie>,
    val userAgent: String,
    val localStorage: Map<String, String> = emptyMap(),
) {
    /** Browser storage may contain tokens; never render captured values in diagnostics. */
    override fun toString(): String =
        "WebChallengeCapture(capability=opaque, cookieCount=${cookies.size}, hasUserAgent=${userAgent.isNotBlank()}, " +
            "localStorageKeyCount=${localStorage.size})"
}

/** Defense in depth at the platform boundary; request construction already validates this grant. */
internal fun SourceWebChallengeRequest.allowsEmbeddedWebChallenge(): Boolean =
    embeddedPolicy == WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS &&
        allowedNavigationOrigins.isNotEmpty() &&
        allowedNavigationOrigins.all(allowedSubresourceOrigins::contains) &&
        webChallengeOrigin(url) in allowedNavigationOrigins

internal fun SourceWebChallengeRequest.allowsWebChallengeNavigation(url: String?): Boolean =
    allowsEmbeddedWebChallenge() && webChallengeOrigin(url) in allowedNavigationOrigins

/** Session storage must belong to the requested source, even after an allowed login redirect. */
internal fun SourceWebChallengeRequest.allowsWebChallengeCapture(documentUrl: String?): Boolean =
    allowsWebChallengeNavigation(documentUrl) && webChallengeOrigin(documentUrl) == webChallengeOrigin(url)

internal fun SourceWebChallengeRequest.allowsWebChallengeSubresource(url: String?): Boolean =
    allowsEmbeddedWebChallenge() && webChallengeOrigin(url) in allowedSubresourceOrigins

/**
 * Narrow non-network schemes needed internally by challenge engines. Main-frame and popup
 * navigation never use these. Blob URLs must carry an allowlisted creator origin; data URLs are
 * accepted only as non-navigation resources and remain subject to the page's network policy.
 */
internal fun SourceWebChallengeRequest.allowsWebChallengeInternalResource(
    url: String?,
    isMainFrame: Boolean,
): Boolean {
    if (!allowsEmbeddedWebChallenge() || url == null) return false
    val normalized = url.trim()
    return when {
        normalized.equals("about:blank", ignoreCase = true) ||
            normalized.startsWith("about:blank#", ignoreCase = true) ||
            normalized.equals("about:srcdoc", ignoreCase = true) -> !isMainFrame
        normalized.startsWith("blob:", ignoreCase = true) ->
            !isMainFrame && webChallengeOrigin(normalized.substringAfter(':')) in allowedSubresourceOrigins
        normalized.startsWith("data:", ignoreCase = true) -> !isMainFrame
        else -> false
    }
}

/**
 * WKWebView has no general HTTP(S) request interceptor. Install this native content-blocker list
 * before the first load: block every network URL, then narrowly undo that rule for reviewed exact
 * origins. Navigation delegates remain a separate top-level/popup boundary.
 */
internal fun SourceWebChallengeRequest.appleWebChallengeContentRuleList(): String? {
    if (!allowsEmbeddedWebChallenge()) return null
    val rules = buildList {
        add(
            mapOf(
                "trigger" to mapOf("url-filter" to "^(?:https?|wss?|ftp)://"),
                "action" to mapOf("type" to "block"),
            ),
        )
        allowedSubresourceOrigins.sorted().forEach { origin ->
            add(
                mapOf(
                    "trigger" to mapOf("url-filter" to webChallengeOriginUrlFilter(origin)),
                    "action" to mapOf("type" to "ignore-previous-rules"),
                ),
            )
        }
    }
    return WEB_CHALLENGE_CONTRACT_JSON.encodeToString(rules)
}

private fun webChallengeOrigin(value: String?): String? = value?.let { raw ->
    runCatching {
        val parsed = Url(raw)
        require(parsed.protocol.name.equals("https", ignoreCase = true) && parsed.host.isNotBlank())
        require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
        buildString {
            append("https://").append(parsed.host.lowercase().trimEnd('.'))
            if (parsed.port != 443) append(':').append(parsed.port)
        }
    }.getOrNull()
}

private fun webChallengeOriginUrlFilter(origin: String): String {
    val parsed = Url(origin)
    val escapedHost = buildString {
        parsed.host.lowercase().trimEnd('.').forEach { character ->
            if (character in "\\.^$|?*+()[]{}-") append('\\')
            append(character)
        }
    }
    val authority = if (parsed.port == 443) "$escapedHost(?::443)?" else "$escapedHost:${parsed.port}"
    return "^https://$authority(?:[/?#]|$)"
}

private val WEB_CHALLENGE_CONTRACT_JSON = Json { encodeDefaults = true }

/** Platform WebView surface backed by an isolated, non-persistent browser session. */
@Composable
internal expect fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onSessionCaptured: (WebChallengeCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
