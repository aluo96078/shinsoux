package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.plugin.SourceWebChallengeRequest
import dev.shinsou.kmp.plugin.SourceWebChallengeCapability
import dev.shinsou.kmp.contract.WebChallengeEmbeddedPolicy
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

/** True when the reviewed challenge allows top-level redirects to a third-party sign-in origin. */
internal fun SourceWebChallengeRequest.hasThirdPartyLoginOrigins(): Boolean =
    allowsEmbeddedWebChallenge() &&
        webChallengeOrigin(url)?.let { sourceOrigin ->
            allowedNavigationOrigins.any { it != sourceOrigin }
        } == true

/**
 * Strips Android WebView-specific markers (`; wv` and `Version/x.y` before `Chrome/`) from the
 * engine's genuine default User-Agent while preserving the real Android OS, device, and Chromium
 * versions so identity providers such as Google do not reject the browser for engine/UA mismatch
 * or embedded WebView tokens.
 */
internal fun sanitizeAndroidWebChallengeUserAgent(defaultUserAgent: String): String {
    val trimmed = defaultUserAgent.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed
        .replace(ANDROID_WEBVIEW_TOKEN_REGEX, "")
        .replace(ANDROID_WEBVIEW_VERSION_REGEX, "")
        .replace(REPEATED_WHITESPACE_REGEX, " ")
        .trim()
}

/**
 * Derives a matching Mobile Safari User-Agent from iOS WKWebView's genuine default UA when a
 * challenge visits third-party sign-in origins. Desktop/iPad UAs (no `Mobile/` token) and UAs
 * already identifying Safari return null so `customUserAgent` stays unset.
 */
internal fun sanitizeIosWebChallengeUserAgent(defaultUserAgent: String?): String? {
    val trimmed = defaultUserAgent?.trim().orEmpty()
    val mobileIndex = trimmed.indexOf("Mobile/")
    if (trimmed.isEmpty() || trimmed.contains("Safari/", ignoreCase = true) || mobileIndex < 0) {
        return null
    }
    val versionPrefix = if (trimmed.contains("Version/", ignoreCase = true)) {
        ""
    } else {
        IOS_OS_VERSION_REGEX.find(trimmed)?.let { match ->
            val major = match.groupValues[1]
            val minor = match.groupValues[2].ifEmpty { "0" }
            "Version/$major.$minor "
        }.orEmpty()
    }
    return buildString {
        append(trimmed, 0, mobileIndex)
        append(versionPrefix)
        append(trimmed, mobileIndex, trimmed.length)
        append(" Safari/604.1")
    }
}

/**
 * Redirect cancellations (`NSURLErrorCancelled`) and policy-interrupted frame loads
 * (`WebKitErrorDomain` 102) happen during normal OAuth redirects and blocked third-party subframes.
 */
internal fun isIgnoredAppleWebChallengeNavigationError(domain: String?, code: Long): Boolean =
    (domain == "NSURLErrorDomain" && code == -999L) ||
        (domain == "WebKitErrorDomain" && code == 102L)

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
 *
 * WebKit's content-blocker regex dialect rejects disjunctions (`|`), non-terminal `$`, and
 * non-capturing groups; one invalid filter makes the whole list fail to compile. Keep every
 * filter in the supported subset (mirrors the macOS helper in `ShinsouWebChallenge/main.swift`).
 */
internal fun SourceWebChallengeRequest.appleWebChallengeContentRuleList(): String? {
    if (!allowsEmbeddedWebChallenge()) return null
    val rules = buildList {
        add(
            mapOf(
                "trigger" to mapOf("url-filter" to WEB_CHALLENGE_BLOCK_ALL_NETWORK_FILTER),
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
    // A parse probe: an address that is not a plain https origin answers null.
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

/** Any `scheme://` URL; `data:`/`blob:`/`about:` have no authority and stay unaffected. */
internal const val WEB_CHALLENGE_BLOCK_ALL_NETWORK_FILTER = "^[A-Za-z][A-Za-z0-9+.-]*://"

/**
 * WebKit normalizes URLs before matching (default port dropped, empty path becomes `/`), so the
 * authority is always followed by `/`, `?` or `#`.
 */
internal fun webChallengeOriginUrlFilter(origin: String): String {
    val parsed = Url(origin)
    val escapedHost = buildString {
        parsed.host.lowercase().trimEnd('.').forEach { character ->
            if (character in "\\.^$|?*+()[]{}") append('\\')
            append(character)
        }
    }
    val authority = if (parsed.port == 443) escapedHost else "$escapedHost:${parsed.port}"
    return "^https://$authority[/?#]"
}

private val WEB_CHALLENGE_CONTRACT_JSON = Json { encodeDefaults = true }
private val ANDROID_WEBVIEW_TOKEN_REGEX = Regex(""";\s*wv(?=\s*[;)])""", RegexOption.IGNORE_CASE)
private val ANDROID_WEBVIEW_VERSION_REGEX = Regex("""\bVersion/\d+(?:\.\d+)*\s+(?=Chrome/)""")
private val REPEATED_WHITESPACE_REGEX = Regex("""\s{2,}""")
private val IOS_OS_VERSION_REGEX = Regex("""\bOS\s+(\d+)(?:_(\d+))?""")

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
