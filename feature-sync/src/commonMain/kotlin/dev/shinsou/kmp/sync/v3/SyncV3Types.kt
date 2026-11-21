package dev.shinsou.kmp.sync.v3

/** Name, platform and version this device reports to the server. */
public data class SyncDeviceInfo(val defaultName: String, val platform: String, val appVersion: String)

/** What a background sync run tells the platform scheduler. */
public enum class SyncBackgroundResult {
    /** Synced, or nothing to do (signed out). */
    Completed,

    /** Network trouble, rate limit, server error or out of time: an early retry can succeed. */
    RetryLater,

    /** Needs the user or a fix (expired session, rejected request): wait for the next period. */
    Failed,
}

/** What a "Sync now" press did (R4-7, docs/architecture/0006). */
public enum class ManualSyncResult { Synced, Failed, InProgress }

/** Everything the Settings page shows about sync. */
public data class SyncUiState(
    val signedIn: Boolean = false,
    /** True after the server rejected our refresh token; the user has to sign in again. */
    val sessionExpired: Boolean = false,
    val serverUrl: String? = null,
    val insecureLan: Boolean = false,
    val username: String? = null,
    val deviceName: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Local edits not pushed yet. */
    val pendingCount: Int = 0,
    /** Server records that cannot be applied here yet (unknown title, chapter or source). */
    val pendingRemoteCount: Int = 0,
    /** Library titles without a portable identity (for example a legacy source that is not installed). */
    val unsyncableCount: Int = 0,
    val rejectedCount: Int = 0,
    val streamConnected: Boolean = false,
    val lastError: String? = null,
    /** The server lets accounts share works with each other (last known answer). */
    val sharingEnabled: Boolean = false,
    /** The server offers work reviews (last known answer). */
    val reviewsEnabled: Boolean = false,
    /**
     * Sources the account's titles come from that are not installed here, by package. Those titles
     * join the library on their own once the package is installed.
     */
    val missingSources: List<SyncMissingSource> = emptyList(),
)

/** A package this device needs for some of the account's titles. */
public data class SyncMissingSource(
    val packageId: String,
    /** The source's name as the device that added the titles knew it; the package id otherwise. */
    val name: String,
    val language: String?,
    /** The repository that published the package, when the adding device knew it. */
    val repositoryUrl: String?,
    val titleCount: Int,
    /** The waiting titles' names, sorted, so the library can say which titles will appear. */
    val titles: List<String> = emptyList(),
)

/** The three lists of people an account keeps. */
public enum class SyncRelation(internal val path: String) {
    Following("following"),
    Followers("followers"),
    Blocks("blocks"),
}

internal fun SyncApiException.userMessage(): String = when {
    isVersionIncompatible -> "The sync server needs a different app version; update Shinsou X or the server"
    code == "network" -> "Network error: $message"
    code == "rate_limited" -> "Rate limited, retrying later"
    else -> "$code: $message"
}
