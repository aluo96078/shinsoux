package dev.shinsou.kmp.tracking

import kotlinx.serialization.Serializable

@Serializable
internal enum class SelfHostedTrackerKind {
    KOMGA,
    KAVITA,
    SUWAYOMI,
}

@Serializable
internal data class SelfHostedTrackerConfig(
    val kind: SelfHostedTrackerKind,
    val baseUrl: String,
    val accountId: String? = null,
) {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Self-hosted tracker base URL must use HTTP or HTTPS"
        }
    }
}

/**
 * Integration boundary for Komga, Kavita and Suwayomi. Concrete adapters own endpoint discovery;
 * common code intentionally does not guess server-specific paths or authentication schemes.
 */
internal interface SelfHostedTrackerAdapter : TrackerAdapter {
    val server: SelfHostedTrackerConfig

    suspend fun healthCheck(): SelfHostedTrackerHealth
}

@Serializable
internal data class SelfHostedTrackerHealth(
    val reachable: Boolean,
    val serverVersion: String? = null,
    val message: String? = null,
)

internal fun interface SelfHostedTrackerFactory {
    fun create(config: SelfHostedTrackerConfig, tokenStore: TokenStore): SelfHostedTrackerAdapter
}
