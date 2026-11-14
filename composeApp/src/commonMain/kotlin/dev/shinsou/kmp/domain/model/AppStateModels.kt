package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val general: GeneralSettings = GeneralSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val reader: ReaderSettings = ReaderSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val tracking: TrackingSettings = TrackingSettings(),
    val sync: SyncSettings = SyncSettings(),
    val browse: BrowseSettings = BrowseSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    /** Device-local diagnostics; intentionally absent from the portable sync settings. */
    val developer: DeveloperSettings = DeveloperSettings(),
)

@Serializable
data class GeneralSettings(
    val languagePreference: String? = null,
    val dateFormat: String = "MM/dd/yyyy",
    val confirmBeforeClosing: Boolean = true,
    val defaultStartingScreen: MainSection = MainSection.LIBRARY,
)

@Serializable
enum class MainSection {
    LIBRARY,
    UPDATES,
    HISTORY,
    BROWSE,
    MORE,
}

@Serializable
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val amoledDark: Boolean = false,
    val tintColor: String = "system",
    val timestampFormat: String = "short",
    val relativeTimestamps: Boolean = false,
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
data class LibrarySettings(
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.COMPACT_GRID,
    val sort: LibrarySort = LibrarySort(),
    val filter: LibraryFilter = LibraryFilter(),
    /** A category ID, or [ALWAYS_ASK_CATEGORY_ID] to open the multi-category picker. */
    val defaultCategoryId: Long = 0,
    val categoryUpdateBehaviour: String = "all",
    val globalUpdateRestrictions: Set<String> = emptySet(),
    val autoRefreshMetadata: Boolean = false,
    val downloadOnly: Boolean = false,
    val portraitColumns: Int = 3,
    val landscapeColumns: Int = 5,
)

@Serializable
data class ReaderSettings(
    val readingMode: ReadingMode = ReadingMode.PAGER_LTR,
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    /** Reflowable novel typography; image readers intentionally ignore these values. */
    val novelFontSizeSp: Float = 20f,
    val novelLineHeightMultiplier: Float = 1.72f,
    /** Maximum measured text width on large windows; phones naturally use their viewport. */
    val novelMaxWidthDp: Float = 760f,
    val doubleTapToZoom: Boolean = true,
    val animatePageTransitions: Boolean = true,
    val showPageNumber: Boolean = true,
    val keepScreenOn: Boolean = true,
    val fullscreen: Boolean = true,
    val skipFilteredChapters: Boolean = false,
    val skipReadChapters: Boolean = false,
    val skipDuplicateChapters: Boolean = false,
    val colorFilter: ReaderColorFilter = ReaderColorFilter(),
    val splitTallImages: Boolean = false,
    val webtoonSidePadding: Double = 0.0,
    val volumeKeys: Boolean = false,
)

@Serializable
enum class ReadingMode {
    PAGER_LTR,
    PAGER_RTL,
    PAGER_VERTICAL,
    WEBTOON,
    CONTINUOUS_VERTICAL,
}

@Serializable
enum class ReaderOrientation {
    FREE,
    PORTRAIT,
    LANDSCAPE,
    SENSOR_PORTRAIT,
    SENSOR_LANDSCAPE,
}

@Serializable
data class ReaderColorFilter(
    val enabled: Boolean = false,
    val brightness: Float = 0f,
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val alpha: Float = 0f,
    val grayscale: Boolean = false,
    val inverted: Boolean = false,
)

@Serializable
data class DownloadSettings(
    val location: String = "",
    val autoDownloadNewChapters: Boolean = false,
    val deleteAfterReading: Boolean = false,
    val wifiOnly: Boolean = true,
    val parallelDownloads: Int = 3,
    val parallelPages: Int = 5,
    val removeAfterMarkedRead: Boolean = false,
    /** iOS: play silent audio in the background so downloads go on where iOS grants no task time. */
    val keepAliveWithSilentAudio: Boolean = false,
)

@Serializable
data class TrackingSettings(
    val autoSyncAfterRead: Boolean = true,
    val updateProgressAfterRead: Boolean = true,
)

@Serializable
data class SyncSettings(
    val enabled: Boolean = false,
    val syncOnForeground: Boolean = true,
    /** Which kinds of data this device sends to and applies from the account. */
    val kinds: SyncKindSettings = SyncKindSettings(),
)

/**
 * Per-device switches for what is synced; all on by default. A kind that is off is neither sent
 * nor applied on this device: what the account already holds stays stored here and is applied when
 * the switch is turned back on. Device-local on purpose (never part of the portable settings), so
 * one device can opt out without changing the others.
 */
@Serializable
data class SyncKindSettings(
    /** Library titles (the `library` records). */
    val library: Boolean = true,
    /** Reading progress and history (the `unit`, `progress` and `history` records). */
    val readingProgress: Boolean = true,
    val categories: Boolean = true,
    /** Repository suggestions (the `repository` records). */
    val repositories: Boolean = true,
    /** Portable settings (the `setting` records). */
    val settings: Boolean = true,
    /** Installed packages (the `package` records). */
    val packages: Boolean = true,
)

@Serializable
data class BrowseSettings(
    val checkExtensionUpdates: Boolean = true,
    // The original source list does not hide the official catalogue on first launch. The
    // official repository marks its manga sources as NSFW, so false here would make Browse
    // appear empty in a fresh installation.
    val showNsfwSources: Boolean = true,
    /** Empty means all languages, matching the original app's first-launch behaviour. */
    val enabledLanguages: Set<String> = emptySet(),
    val pinnedSourceIds: Set<Long> = emptySet(),
    /**
     * Stable identities for v2 sources that can be pinned in the browse catalogue.
     *
     * Legacy sources continue to use [pinnedSourceIds] for backwards compatibility.  A v2
     * source's numeric row id is deliberately process-local, so persisting it would make a pin
     * move to another source after the next refresh or app launch.
     */
    val pinnedSourceKeys: Set<String> = emptySet(),
)

@Serializable
data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val lockAfterSeconds: Int = 0,
    val secureScreen: Boolean = false,
    val incognitoMode: Boolean = false,
)

@Serializable
data class AdvancedSettings(
    val dnsOverHttps: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyWorkerUrl: String = "",
    val proxyApiKey: String = "",
    val customUserAgent: String = "",
)

/**
 * App-level developer mode (unrelated to the plugin developer mode). The sub-switches only take
 * effect while [enabled] is on.
 */
@Serializable
data class DeveloperSettings(
    val enabled: Boolean = false,
    val showLoadingElapsed: Boolean = false,
    val showImageRequests: Boolean = false,
)

@Serializable
data class BackupState(
    val status: BackupStatus = BackupStatus.IDLE,
    val automaticEnabled: Boolean = false,
    val intervalHours: Int = 24,
    val retainedBackupCount: Int = 5,
    val destination: String = "",
    val lastBackupAt: Long? = null,
    val lastRestoreAt: Long? = null,
    val lastFileName: String? = null,
    val errorMessage: String? = null,
    /** Whether a backup carries stored translations that were paid for (ADR-0004 §4.2). */
    val includePaidTranslations: Boolean = true,
    /** Whether a backup carries stored translations the device can make again for free. */
    val includeFreeTranslations: Boolean = false,
)

@Serializable
enum class BackupStatus {
    IDLE,
    CREATING,
    RESTORING,
    COMPLETED,
    FAILED,
}

@Serializable
data class DownloadQueueItem(
    val id: String,
    val mangaId: Long,
    val chapterId: Long,
    val state: DownloadState = DownloadState.QUEUED,
    val progress: Double = 0.0,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val position: Int = 0,
    val queuedAt: Long = 0,
    val updatedAt: Long = queuedAt,
    val destinationPath: String? = null,
    val errorMessage: String? = null,
    /** False after the user clears completed queue rows; downloaded-file state remains local. */
    val visibleInQueue: Boolean = true,
) {
    companion object {
        fun id(mangaId: Long, chapterId: Long): String = "${mangaId}_${chapterId}"
    }
}

@Serializable
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    ERROR,
}

@Serializable
data class TrackerAccountState(
    val trackerId: Int,
    val loggedIn: Boolean = false,
    val username: String? = null,
    val displayName: String? = null,
    val lastSyncAt: Long? = null,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
)
