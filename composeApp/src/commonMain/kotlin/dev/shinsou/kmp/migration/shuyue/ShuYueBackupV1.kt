package dev.shinsou.kmp.migration.shuyue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire enums used by the ShuYue v1 backup format.
 *
 * These are intentionally separate from application/domain enums.  A backup is an external input
 * and must remain decodable even while the host application's settings or plugin model evolves.
 */
@Serializable
internal enum class ShuYueV1BookOrigin {
    @SerialName("LocalTxt")
    LOCAL_TXT,

    @SerialName("LocalEpub")
    LOCAL_EPUB,

    @SerialName("RemotePlugin")
    REMOTE_PLUGIN,
}

@Serializable
internal enum class ShuYueV1AppLanguage {
    @SerialName("System")
    SYSTEM,

    @SerialName("English")
    ENGLISH,

    @SerialName("TraditionalChinese")
    TRADITIONAL_CHINESE,

    @SerialName("SimplifiedChinese")
    SIMPLIFIED_CHINESE,

    @SerialName("Japanese")
    JAPANESE,
}

@Serializable
internal enum class ShuYueV1ReaderTheme {
    @SerialName("System")
    SYSTEM,

    @SerialName("Light")
    LIGHT,

    @SerialName("Dark")
    DARK,

    @SerialName("Oled")
    OLED,

    @SerialName("Paper")
    PAPER,
}

@Serializable
internal enum class ShuYueV1AccentColor {
    @SerialName("Blue")
    BLUE,

    @SerialName("Indigo")
    INDIGO,

    @SerialName("Purple")
    PURPLE,

    @SerialName("Pink")
    PINK,

    @SerialName("Red")
    RED,

    @SerialName("Orange")
    ORANGE,

    @SerialName("Yellow")
    YELLOW,

    @SerialName("Green")
    GREEN,

    @SerialName("Teal")
    TEAL,

    @SerialName("Cyan")
    CYAN,
}

@Serializable
internal enum class ShuYueV1PageTurnAction {
    @SerialName("Next")
    NEXT,

    @SerialName("Previous")
    PREVIOUS,
}

@Serializable
internal enum class ShuYueV1ReaderInput {
    @SerialName("NextPage")
    NEXT_PAGE,

    @SerialName("PreviousPage")
    PREVIOUS_PAGE,

    @SerialName("VolumeUp")
    VOLUME_UP,

    @SerialName("VolumeDown")
    VOLUME_DOWN,
}

@Serializable
internal enum class ShuYueV1PluginImageParsingPolicy {
    @SerialName("FollowDefault")
    FOLLOW_DEFAULT,

    @SerialName("Allow")
    ALLOW,

    @SerialName("Deny")
    DENY,
}

@Serializable
internal data class ShuYueV1Chapter(
    @SerialName("id")
    val id: String,
    @SerialName("bookId")
    val bookId: String,
    @SerialName("title")
    val title: String,
    @SerialName("index")
    val index: Int,
    @SerialName("href")
    val href: String? = null,
    @SerialName("text")
    val text: String = "",
    @SerialName("wordCount")
    val wordCount: Int = text.length,
) {
    /** Raw wire records are not safe to interpolate into logs. */
    override fun toString(): String = "ShuYueV1Chapter(<redacted>)"
}

@Serializable
internal data class ShuYueV1Book(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("author")
    val author: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("coverImage")
    val coverImage: String? = null,
    @SerialName("origin")
    val origin: ShuYueV1BookOrigin = ShuYueV1BookOrigin.LOCAL_TXT,
    @SerialName("sourceId")
    val sourceId: String? = null,
    @SerialName("originalUri")
    val originalUri: String? = null,
    @SerialName("chapters")
    val chapters: List<ShuYueV1Chapter> = emptyList(),
    @SerialName("addedAt")
    val addedAt: Long = 0L,
    @SerialName("updatedAt")
    val updatedAt: Long = addedAt,
    @SerialName("category")
    val category: String = "Default",
) {
    /** Raw wire records are not safe to interpolate into logs. */
    override fun toString(): String = "ShuYueV1Book(<redacted>)"
}

@Serializable
internal data class ShuYueV1ReaderProgress(
    @SerialName("bookId")
    val bookId: String,
    @SerialName("chapterId")
    val chapterId: String,
    @SerialName("charOffset")
    val charOffset: Int = 0,
    @SerialName("progress")
    val progress: Float = 0f,
    @SerialName("updatedAt")
    val updatedAt: Long = 0L,
)

@Serializable
internal data class ShuYueV1ReaderSettings(
    @SerialName("language")
    val language: ShuYueV1AppLanguage = ShuYueV1AppLanguage.SYSTEM,
    @SerialName("fontSizeSp")
    val fontSizeSp: Float = 18f,
    @SerialName("lineHeightPercent")
    val lineHeightPercent: Int = 165,
    @SerialName("pageChars")
    val pageChars: Int = 800,
    @SerialName("theme")
    val theme: ShuYueV1ReaderTheme = ShuYueV1ReaderTheme.SYSTEM,
    @SerialName("accentColor")
    val accentColor: ShuYueV1AccentColor = ShuYueV1AccentColor.BLUE,
    @SerialName("volumeKeysEnabled")
    val volumeKeysEnabled: Boolean = true,
    @SerialName("volumeUpAction")
    val volumeUpAction: ShuYueV1PageTurnAction = ShuYueV1PageTurnAction.PREVIOUS,
    @SerialName("volumeDownAction")
    val volumeDownAction: ShuYueV1PageTurnAction = ShuYueV1PageTurnAction.NEXT,
    @SerialName("keepScreenOn")
    val keepScreenOn: Boolean = false,
    @SerialName("syncOnLaunch")
    val syncOnLaunch: Boolean = false,
    @SerialName("appLockEnabled")
    val appLockEnabled: Boolean = false,
    @SerialName("secureScreen")
    val secureScreen: Boolean = false,
    @SerialName("incognitoMode")
    val incognitoMode: Boolean = false,
    @SerialName("showNsfwSources")
    val showNsfwSources: Boolean = false,
    @SerialName("imageParsingEnabled")
    val imageParsingEnabled: Boolean = true,
    @SerialName("showPluginErrors")
    val showPluginErrors: Boolean = false,
)

@Serializable
internal data class ShuYueV1PluginSourceDescriptor(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("lang")
    val lang: String,
    @SerialName("baseUrl")
    val baseUrl: String,
    @SerialName("supportsLogin")
    val supportsLogin: Boolean = false,
    @SerialName("supportsLatest")
    val supportsLatest: Boolean = false,
    @SerialName("supportsFavorites")
    val supportsFavorites: Boolean = false,
)

@Serializable
internal data class ShuYueV1PluginIndexEntry(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("version")
    val version: String,
    @SerialName("versionCode")
    val versionCode: Int,
    @SerialName("lang")
    val lang: String,
    @SerialName("nsfw")
    val nsfw: Int = 0,
    @SerialName("scriptUrl")
    val scriptUrl: String,
    @SerialName("iconUrl")
    val iconUrl: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("sources")
    val sources: List<ShuYueV1PluginSourceDescriptor> = emptyList(),
)

@Serializable
internal data class ShuYueV1PluginRepositoryMeta(
    @SerialName("name")
    val name: String,
    @SerialName("website")
    val website: String? = null,
    @SerialName("signingKeyFingerprint")
    val signingKeyFingerprint: String? = null,
)

@Serializable
internal data class ShuYueV1PluginRepositoryManifest(
    @SerialName("meta")
    val meta: ShuYueV1PluginRepositoryMeta,
)

@Serializable
internal data class ShuYueV1PluginManifest(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("version")
    val version: String,
    @SerialName("versionCode")
    val versionCode: Int,
    @SerialName("lang")
    val lang: String,
    @SerialName("nsfw")
    val nsfw: Int = 0,
    @SerialName("script")
    val script: String,
    @SerialName("signature")
    val signature: String? = null,
    @SerialName("minRuntimeVersion")
    val minRuntimeVersion: String? = null,
    @SerialName("sources")
    val sources: List<ShuYueV1PluginSourceDescriptor> = emptyList(),
    @SerialName("repository")
    val repository: String? = null,
) {
    /** Raw wire records are not safe to interpolate into logs. */
    override fun toString(): String = "ShuYueV1PluginManifest(<redacted>)"
}

@Serializable
internal data class ShuYueV1InstalledPlugin(
    @SerialName("manifest")
    val manifest: ShuYueV1PluginManifest,
    @SerialName("installedAt")
    val installedAt: Long,
    @SerialName("enabled")
    val enabled: Boolean = true,
    @SerialName("trustedSigningKeyFingerprint")
    val trustedSigningKeyFingerprint: String? = null,
) {
    override fun toString(): String = "ShuYueV1InstalledPlugin(<redacted>)"
}

@Serializable
internal data class ShuYueV1PersistedPluginInstall(
    @SerialName("plugin")
    val plugin: ShuYueV1PluginManifest,
    @SerialName("script")
    val script: String,
) {
    override fun toString(): String = "ShuYueV1PersistedPluginInstall(<redacted>)"
}

@Serializable
internal data class ShuYueV1PersistedPluginRepository(
    @SerialName("baseUrl")
    val baseUrl: String,
    @SerialName("manifest")
    val manifest: ShuYueV1PluginRepositoryManifest,
    @SerialName("entries")
    val entries: List<ShuYueV1PluginIndexEntry> = emptyList(),
    @SerialName("lastLoadedAt")
    val lastLoadedAt: Long,
)

@Serializable
internal data class ShuYueV1PluginCredential(
    @SerialName("sourceId")
    val sourceId: String,
    @SerialName("username")
    val username: String,
    @SerialName("password")
    val password: String,
    @SerialName("updatedAt")
    val updatedAt: Long,
) {
    override fun toString(): String = "ShuYueV1PluginCredential(<redacted>)"
}

@Serializable
internal data class ShuYueV1PluginCookie(
    @SerialName("sourceId")
    val sourceId: String,
    @SerialName("name")
    val name: String,
    @SerialName("value")
    val value: String,
    @SerialName("domain")
    val domain: String,
    @SerialName("path")
    val path: String = "/",
    @SerialName("expiresAt")
    val expiresAt: Long? = null,
) {
    override fun toString(): String = "ShuYueV1PluginCookie(<redacted>)"
}

/** The exact top-level payload emitted by ShuYue v1.  [version] and [createdAt] are mandatory. */
@Serializable
internal data class ShuYueBackupV1(
    @SerialName("version")
    val version: Int,
    @SerialName("createdAt")
    val createdAt: Long,
    @SerialName("books")
    val books: List<ShuYueV1Book> = emptyList(),
    @SerialName("progress")
    val progress: List<ShuYueV1ReaderProgress> = emptyList(),
    @SerialName("readerSettings")
    val readerSettings: ShuYueV1ReaderSettings = ShuYueV1ReaderSettings(),
    @SerialName("installedPlugins")
    val installedPlugins: List<ShuYueV1InstalledPlugin> = emptyList(),
    @SerialName("pluginInstallations")
    val pluginInstallations: List<ShuYueV1PersistedPluginInstall> = emptyList(),
    @SerialName("pluginRepositories")
    val pluginRepositories: List<ShuYueV1PersistedPluginRepository> = emptyList(),
    @SerialName("selectedPluginRepositoryUrl")
    val selectedPluginRepositoryUrl: String? = null,
    @SerialName("pluginCredentials")
    val pluginCredentials: List<ShuYueV1PluginCredential> = emptyList(),
    @SerialName("pluginCookies")
    val pluginCookies: List<ShuYueV1PluginCookie> = emptyList(),
    @SerialName("pluginPreferences")
    val pluginPreferences: Map<String, String> = emptyMap(),
    @SerialName("pluginImageParsingPolicies")
    val pluginImageParsingPolicies: Map<String, ShuYueV1PluginImageParsingPolicy> = emptyMap(),
) {
    /** Raw wire records are not safe to interpolate into logs. */
    override fun toString(): String = "ShuYueBackupV1(<redacted>)"
}
