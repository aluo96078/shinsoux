package dev.shinsou.kmp.contract

import dev.shinsou.kmp.plugin.isBlockedPluginAddress
import io.ktor.http.Url

// R4-1 (docs/architecture/0006): source login, secret and web-challenge contracts.

/** A non-blocking login prompt requested by an executable source. */
data class SourceLoginRequest(
    val sourceId: Long,
    val sourceName: String,
    val reason: String? = null,
    val eventId: String? = null,
    /** Host-bound authority for system-event prompts; absent for legacy imperative requests. */
    val exactTarget: dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget? = null,
)

/** Stable failure boundary for a host-managed source login; no raw exception text is exposed. */
enum class SourceLoginFailureStage {
    PREPARE_SOURCE,
    READ_CREDENTIALS,
    WRITE_CREDENTIALS,
    AUTHENTICATE,
    RESTORE_CREDENTIALS,
    REFRESH_SOURCE_STATE,
    UNKNOWN,
}

/** Result of a host-managed source login, including an optional safe source error. */
data class SourceLoginResult(
    val succeeded: Boolean,
    val errorMessage: String? = null,
    val failureStage: SourceLoginFailureStage? = null,
)

/** Secret source settings loaded only while an explicit account/settings surface is open. */
data class SourceSecrets(
    val credential: SourceCredential? = null,
    val cookies: List<SourceCookie> = emptyList(),
)

/** Typed secure-storage result; raw platform/runtime exceptions never cross into the UI. */
data class SourceSecretsResult(
    val secrets: SourceSecrets = SourceSecrets(),
    val failureStage: SourceLoginFailureStage? = null,
) {
    val succeeded: Boolean get() = failureStage == null
}


data class SourceCredential(
    val username: String,
    val password: String,
)

data class SourceCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    /** Host-only cookies must never be sent to subdomains. */
    val hostOnly: Boolean = !domain.startsWith('.'),
)

/**
 * Host-issued capability for an embedded browser challenge. A source declaration alone must
 * never enable this mode: only host-reviewed, immutable origin metadata may select
 * [ALLOW_REVIEWED_ORIGINS].
 */
enum class WebChallengeEmbeddedPolicy {
    DENY,
    ALLOW_REVIEWED_ORIGINS,
}

/**
 * Process-local, one-shot authority returned by the host with a browser challenge request.
 *
 * It intentionally exposes no identifier or serializable value. Equality remains object identity,
 * and the internal constructor prevents extensions or remote data from minting import authority.
 */
class SourceWebChallengeCapability internal constructor() {
    override fun toString(): String = "SourceWebChallengeCapability(opaque)"
}

data class SourceWebChallengeRequest(
    val capability: SourceWebChallengeCapability,
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val userAgent: String,
    val cookies: List<SourceCookie> = emptyList(),
    /** Cookie name that proves the requested anti-bot challenge completed, when one is required. */
    val requiredCookieName: String? = null,
    /** Exact same-origin localStorage allowlist. Values are never included in this request. */
    val localStorageKeys: List<String> = emptyList(),
    /** Allowlisted keys that must contain a non-empty value before the session can be imported. */
    val requiredLocalStorageKeys: Set<String> = emptySet(),
    /** Credentials are supplied only for an explicit, source-scoped browser login request. */
    val username: String? = null,
    val password: String? = null,
    /** Defaults closed so older/generic request producers cannot silently gain an embedded WebView. */
    val embeddedPolicy: WebChallengeEmbeddedPolicy = WebChallengeEmbeddedPolicy.DENY,
    /** Canonical HTTPS origins to which a top-level document may navigate. */
    val allowedNavigationOrigins: Set<String> = emptySet(),
    /** Canonical HTTPS origins from which frames, scripts, XHR/fetch, images, and other resources may load. */
    val allowedSubresourceOrigins: Set<String> = emptySet(),
) {
    init {
        require(allowedNavigationOrigins.size <= MAX_WEB_CHALLENGE_NAVIGATION_ORIGINS) {
            "Too many Web challenge navigation origins"
        }
        require(allowedSubresourceOrigins.size <= MAX_WEB_CHALLENGE_SUBRESOURCE_ORIGINS) {
            "Too many Web challenge subresource origins"
        }
        require(allowedNavigationOrigins.all(::isCanonicalWebChallengeOrigin)) {
            "Web challenge navigation origins must be canonical HTTPS origins"
        }
        require(allowedSubresourceOrigins.all(::isCanonicalWebChallengeOrigin)) {
            "Web challenge subresource origins must be canonical HTTPS origins"
        }
        when (embeddedPolicy) {
            WebChallengeEmbeddedPolicy.DENY -> require(
                allowedNavigationOrigins.isEmpty() && allowedSubresourceOrigins.isEmpty(),
            ) { "A denied Web challenge cannot carry embedded-browser origin grants" }

            WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS -> {
                require(allowedNavigationOrigins.isNotEmpty()) {
                    "An embedded Web challenge needs a reviewed navigation origin"
                }
                require(allowedNavigationOrigins.all(allowedSubresourceOrigins::contains)) {
                    "Web challenge navigation origins must also be allowed as subresource origins"
                }
                val requestOrigin = canonicalWebChallengeOrigin(url)
                require(requestOrigin != null && requestOrigin in allowedNavigationOrigins) {
                    "The Web challenge URL must use a reviewed navigation origin"
                }
            }
        }
    }

    /** Prevent accidental logging of URLs, cookies, browser fingerprints, or credentials. */
    override fun toString(): String =
        "SourceWebChallengeRequest(sourceId=$sourceId, sourceName=$sourceName, " +
            "cookieCount=${cookies.size}, requiresChallengeCookie=${requiredCookieName != null}, " +
            "localStorageKeyCount=${localStorageKeys.size}, " +
            "requiredLocalStorageKeyCount=${requiredLocalStorageKeys.size}, " +
            "embeddedPolicy=$embeddedPolicy, navigationOriginCount=${allowedNavigationOrigins.size}, " +
            "subresourceOriginCount=${allowedSubresourceOrigins.size}, " +
            "hasCredentials=${!username.isNullOrBlank() && !password.isNullOrEmpty()})"
}

private fun isCanonicalWebChallengeOrigin(value: String): Boolean =
    runCatching {
        val parsed = Url(value)
        require(parsed.encodedPath in setOf("", "/") && parsed.parameters.isEmpty() && parsed.fragment.isEmpty())
        canonicalWebChallengeOrigin(value) == value
    }.getOrDefault(false)

/** Derives an exact origin from an arbitrary HTTPS challenge URL, including paths and queries. */
private fun canonicalWebChallengeOrigin(value: String): String? = runCatching {
    val parsed = Url(value)
    require(parsed.protocol.name.equals("https", ignoreCase = true) && parsed.host.isNotBlank())
    require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
    require(!isBlockedPluginAddress(parsed.host))
    buildString {
        append("https://").append(parsed.host.lowercase().trimEnd('.'))
        if (parsed.port != 443) append(':').append(parsed.port)
    }
}.getOrNull()

private const val MAX_WEB_CHALLENGE_NAVIGATION_ORIGINS = 8
private const val MAX_WEB_CHALLENGE_SUBRESOURCE_ORIGINS = 32

data class SourcePreference(
    val key: String,
    val title: String,
    val summary: String? = null,
    val value: String = "",
    val choices: List<String> = emptyList(),
    val choiceValues: List<String> = choices,
    val kind: SourcePreferenceKind = SourcePreferenceKind.Text,
)

enum class SourcePreferenceKind {
    Text,
    Toggle,
    Choice,
    MultiChoice,
}
