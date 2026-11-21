package dev.shinsou.kmp.contract

// R4-1 (docs/architecture/0006): source login, secret and web-challenge contracts. The web-challenge
// capability and request live in :feature-plugin (dev.shinsou.kmp.plugin), next to the only code that
// may mint a capability.

/** A non-blocking login prompt requested by an executable source. */
public data class SourceLoginRequest(
    val sourceId: Long,
    val sourceName: String,
    val reason: String? = null,
    val eventId: String? = null,
    /** Host-bound authority for system-event prompts; absent for legacy imperative requests. */
    val exactTarget: dev.shinsou.kmp.domain.model.ExactPluginSourceTarget? = null,
)

/** Stable failure boundary for a host-managed source login; no raw exception text is exposed. */
public enum class SourceLoginFailureStage {
    PREPARE_SOURCE,
    READ_CREDENTIALS,
    WRITE_CREDENTIALS,
    AUTHENTICATE,
    RESTORE_CREDENTIALS,
    REFRESH_SOURCE_STATE,
    UNKNOWN,
}

/** Result of a host-managed source login, including an optional safe source error. */
public data class SourceLoginResult(
    val succeeded: Boolean,
    val errorMessage: String? = null,
    val failureStage: SourceLoginFailureStage? = null,
)

/** Secret source settings loaded only while an explicit account/settings surface is open. */
public data class SourceSecrets(
    val credential: SourceCredential? = null,
    val cookies: List<SourceCookie> = emptyList(),
)

/** Typed secure-storage result; raw platform/runtime exceptions never cross into the UI. */
public data class SourceSecretsResult(
    val secrets: SourceSecrets = SourceSecrets(),
    val failureStage: SourceLoginFailureStage? = null,
) {
    val succeeded: Boolean get() = failureStage == null
}


public data class SourceCredential(
    val username: String,
    val password: String,
)

public data class SourceCookie(
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
public enum class WebChallengeEmbeddedPolicy {
    DENY,
    ALLOW_REVIEWED_ORIGINS,
}

public data class SourcePreference(
    val key: String,
    val title: String,
    val summary: String? = null,
    val value: String = "",
    val choices: List<String> = emptyList(),
    val choiceValues: List<String> = choices,
    val kind: SourcePreferenceKind = SourcePreferenceKind.Text,
)

public enum class SourcePreferenceKind {
    Text,
    Toggle,
    Choice,
    MultiChoice,
}
