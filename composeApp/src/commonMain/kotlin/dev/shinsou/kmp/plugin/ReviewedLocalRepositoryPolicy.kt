package dev.shinsou.kmp.plugin

/** Desktop-only reviewed development repository origin. */
public const val REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL: String =
    "http://127.0.0.1:18081"

/** Physical-iPhone Debug reviewed development repository origin. */
public const val REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL: String =
    "http://192.168.50.193:18081"

/**
 * Immutable, process-start admission for one exact reviewed development repository.
 *
 * Each enabled case owns one literal origin. Host aliases, alternate address spellings, other
 * ports, base paths, credentials, fragments, and unrecognized query strings never inherit it.
 */
public enum class ReviewedLocalRepositoryPolicy(internal val baseUrl: String?) {
    DISABLED(null),
    EXACT_LOOPBACK_18081(REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL),
    EXACT_IOS_LAN_192_168_50_193_18081(REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL),
    EXACT_ANDROID_LAN_192_168_50_193_18081(REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL),
    ;

    /** [normalizedBaseUrl] must already have passed the repository client's strict URL parser. */
    public fun admits(normalizedBaseUrl: String): Boolean =
        baseUrl != null && normalizedBaseUrl == baseUrl

    /** Defense in depth around the dedicated platform transport's independent URL allowlist. */
    internal fun admitsRequestUrl(url: String): Boolean {
        val admittedBase = baseUrl ?: return false
        if (url.length > MAX_LOCAL_REPOSITORY_URL_CHARS || !url.startsWith("$admittedBase/")) return false
        return REVIEWED_LOCAL_REPOSITORY_ROUTE.matches(url.removePrefix("$admittedBase/"))
    }

    private companion object {
        const val MAX_LOCAL_REPOSITORY_URL_CHARS: Int = 1_024

        /**
         * Only repository metadata and app-reviewed Shinsou script/sidecar filename shapes are
         * addressable. Exact package identity and bytes are checked by the compiled catalogue.
         */
        val REVIEWED_LOCAL_REPOSITORY_ROUTE: Regex = Regex(
            "(?:repo\\.json|index(?:\\.min)?\\.json|" +
                "plugins/[a-z0-9][a-z0-9._-]*\\.js|sidecars/[a-z0-9][a-z0-9._-]*\\.json)" +
                "(?:\\?_t=[0-9]{1,19})?",
        )
    }
}

/** Known local provenance remains fail-closed even when its matching process policy is disabled. */
internal fun isKnownReviewedLocalRepositoryBaseUrl(value: String): Boolean =
    value == REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL ||
        value == REVIEWED_IOS_LAN_SHINSOU_REPOSITORY_BASE_URL
