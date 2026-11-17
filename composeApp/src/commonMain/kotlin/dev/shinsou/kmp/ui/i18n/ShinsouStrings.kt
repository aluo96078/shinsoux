package dev.shinsou.kmp.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * Small, dependency-free runtime string table for the common UI.
 * English is the fallback for every long-tail string while the navigation and
 * high-frequency actions are localized in all supported languages.
 */
@Immutable
data class ShinsouStrings(
    val library: String = "Library",
    val updates: String = "Updates",
    val history: String = "History",
    val browse: String = "Browse",
    val more: String = "More",
    val sources: String = "Sources",
    val extensions: String = "Extensions",
    val migration: String = "Migration",
    val downloads: String = "Downloads",
    val statistics: String = "Statistics",
    val settings: String = "Settings",
    val backup: String = "Backup & restore",
    val about: String = "About",
    val search: String = "Search",
    val searchLibrary: String = "Search your library",
    val refresh: String = "Refresh",
    val filter: String = "Filter",
    val sort: String = "Sort",
    val all: String = "All",
    val done: String = "Done",
    val cancel: String = "Cancel",
    val save: String = "Save",
    val close: String = "Close",
    val delete: String = "Delete",
    val remove: String = "Remove",
    val retry: String = "Retry",
    val share: String = "Share",
    val selectAll: String = "Select all",
    val selected: String = "selected",
    val markRead: String = "Mark read",
    val markUnread: String = "Mark unread",
    val moveToCategory: String = "Move to category",
    val continueReading: String = "Continue reading",
    val favorite: String = "Add to library",
    val unfavorite: String = "In library",
    /** Account-owned collection exposed by a remote source (for example Wenku8 bookcase). */
    val myLibrary: String = "My library",
    val download: String = "Download",
    val install: String = "Install",
    val uninstall: String = "Uninstall",
    val enable: String = "Enable",
    val disable: String = "Disable",
    val chapters: String = "Chapters",
    val readerSettings: String = "Reader settings",
    val previousChapter: String = "Previous chapter",
    val nextChapter: String = "Next chapter",
    val page: String = "Page",
    val readerModeLeftToRight: String = "Paged · left to right",
    val readerModeRightToLeft: String = "Paged · right to left",
    val readerModeVertical: String = "Vertical paging",
    val readerModeWebtoon: String = "Webtoon",
    val readerModeContinuousVertical: String = "Continuous vertical",
    val pageTurnAnimation: String = "Page turn animation",
    val pageTurnAnimationDescription: String = "Animate transitions when changing pages",
    val clearFilters: String = "Clear filters",
    val noMatches: String = "No matches",
    val libraryEmpty: String = "Your library is empty",
    val noUpdates: String = "No recent updates",
    val noHistory: String = "No reading history",
    val createBackup: String = "Create backup",
    val restoreBackup: String = "Restore backup",
    /** Long-tail strings that are not part of the high-frequency typed fields. */
    val translations: Map<String, String> = emptyMap(),
)

val LocalShinsouStrings = staticCompositionLocalOf { EnglishStrings }

/** Resolve a long-tail string and replace optional `{0}`, `{1}`, ... tokens. */
fun ShinsouStrings.text(key: String, vararg values: Any?): String {
    // A few screens use the strongly typed, high-frequency fields while
    // others use the long-tail lookup.  Resolve both through the same entry
    // point so a localized core label never falls back to English just
    // because the caller used `text("Settings")` instead of `settings`.
    var result = translations[key] ?: coreTranslation(key) ?: key
    values.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
    return result
}

private fun ShinsouStrings.coreTranslation(key: String): String? = when (key) {
    "Library" -> library
    "Updates" -> updates
    "History" -> history
    "Browse" -> browse
    "More" -> more
    "Sources" -> sources
    "Extensions" -> extensions
    "Migration" -> migration
    "Downloads" -> downloads
    "Statistics" -> statistics
    "Settings" -> settings
    "Backup & restore" -> backup
    "About" -> about
    "Search" -> search
    "Search your library" -> searchLibrary
    "Refresh" -> refresh
    "Filter" -> filter
    "Sort" -> sort
    "All" -> all
    "Done" -> done
    "Cancel" -> cancel
    "Save" -> save
    "Close" -> close
    "Delete" -> delete
    "Remove" -> remove
    "Retry" -> retry
    "Share" -> share
    "Select all" -> selectAll
    "selected" -> selected
    "Mark read" -> markRead
    "Mark unread" -> markUnread
    "Move to category" -> moveToCategory
    "Continue reading" -> continueReading
    "Add to library" -> favorite
    "In library" -> unfavorite
    "My library" -> myLibrary
    "Download" -> download
    "Install" -> install
    "Uninstall" -> uninstall
    "Enable" -> enable
    "Disable" -> disable
    "Chapters" -> chapters
    "Reader settings" -> readerSettings
    "Previous chapter" -> previousChapter
    "Next chapter" -> nextChapter
    "Page" -> page
    "Pager ltr", "Left to right" -> readerModeLeftToRight
    "Pager rtl", "Right to left" -> readerModeRightToLeft
    "Pager vertical", "Vertical", "Vertical paging" -> readerModeVertical
    "Webtoon" -> readerModeWebtoon
    "Continuous vertical" -> readerModeContinuousVertical
    "Page turn animation" -> pageTurnAnimation
    "Animate transitions when changing pages" -> pageTurnAnimationDescription
    "Clear filters" -> clearFilters
    "No matches" -> noMatches
    "Your library is empty" -> libraryEmpty
    "No recent updates" -> noUpdates
    "No reading history" -> noHistory
    "Create backup" -> createBackup
    "Restore backup" -> restoreBackup
    else -> null
}

/** The language the app is shown in (a BCP-47 tag): the one chosen in Settings, else the system's. */
val LocalAppLanguage = androidx.compose.runtime.staticCompositionLocalOf { "en" }

@Composable
fun ProvideShinsouStrings(
    languagePreference: String?,
    content: @Composable () -> Unit,
) {
    // Keep the complete BCP-47 tag.  Reading only `language` turns a
    // Traditional Chinese device into plain `zh`, which used to be treated
    // as Simplified Chinese by the matcher below.
    val systemLanguage = Locale.current.toLanguageTag()
    val language = languagePreference
        ?.takeUnless { it.equals("system", ignoreCase = true) }
        ?: systemLanguage
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalShinsouStrings provides shinsouStringsFor(language),
        content = content,
    )
}

fun shinsouStringsFor(language: String): ShinsouStrings {
    val normalized = language.trim().lowercase().replace('_', '-')
    val parts = normalized.split('-').filter { it.isNotBlank() }
    val languageCode = parts.firstOrNull().orEmpty()
    val subtags = parts.drop(1)
    val script = subtags.firstOrNull { it.length == 4 }
    val region = subtags.firstOrNull { it.length == 2 || it.length == 3 }
    return when {
        languageCode == "zh" && (
            script.equals("hant", ignoreCase = true) ||
                region.equals("tw", ignoreCase = true) ||
                region.equals("hk", ignoreCase = true) ||
                region.equals("mo", ignoreCase = true)
            ) -> TraditionalChineseStrings
        languageCode == "zh" && (
            script.equals("hans", ignoreCase = true) ||
                region.equals("cn", ignoreCase = true) ||
                region.equals("sg", ignoreCase = true) ||
                region.equals("my", ignoreCase = true)
            ) -> SimplifiedChineseStrings
        // Bare `zh` has no script information.  Keep the app's existing
        // Traditional Chinese default instead of silently forcing Simplified.
        languageCode == "zh" -> TraditionalChineseStrings
        languageCode == "ja" -> JapaneseStrings
        languageCode == "ko" -> KoreanStrings
        languageCode == "fr" -> FrenchStrings
        languageCode == "de" -> GermanStrings
        languageCode == "es" -> SpanishStrings
        languageCode == "pt" -> PortugueseStrings
        else -> EnglishStrings
    }
}

internal val EnglishStrings = ShinsouStrings(
    translations = mapOf(
        "Manga" to "Manga",
        "Novel" to "Novel",
        "Mixed" to "Mixed",
        "Unknown type" to "Unknown",
        "Review host permissions" to "Review host permissions",
        "Host event and runtime permissions remain blocked until you approve this exact version and digest." to
            "Host event and runtime permissions remain blocked until you approve this exact version and digest.",
        "Approve" to "Approve",
        "Request login UI" to "Request login UI",
        "Request source refresh" to "Request source refresh",
        "Request logout" to "Request logout",
        "Report diagnostics" to "Report diagnostics",
        "Show user messages" to "Show user messages",
        "Request browser challenge" to "Request browser challenge",
        "Preparing installation…" to "Preparing installation…",
        "Installing…" to "Installing…",
        "Updating…" to "Updating…",
        "Removing…" to "Removing…",
        "Applying permissions…" to "Applying permissions…",
        "Updating trust…" to "Updating trust…",
        "This extension changed. Review its permissions again." to "This extension changed. Review its permissions again.",
        "This plugin can't run: it isn't from a publisher you trust, or this device has no plugin sandbox." to "This plugin can't run: it isn't from a publisher you trust, or this device has no plugin sandbox.",
    ),
)

internal val LibraryContentTypeTranslations = mapOf(
    "zh-TW" to mapOf("Manga" to "漫畫", "Novel" to "小說", "Mixed" to "混合", "Unknown type" to "未知"),
    "zh-CN" to mapOf("Manga" to "漫画", "Novel" to "小说", "Mixed" to "混合", "Unknown type" to "未知"),
    "ja" to mapOf("Manga" to "マンガ", "Novel" to "小説", "Mixed" to "混合", "Unknown type" to "不明"),
    "ko" to mapOf("Manga" to "만화", "Novel" to "소설", "Mixed" to "혼합", "Unknown type" to "알 수 없음"),
    "fr" to mapOf("Manga" to "Manga", "Novel" to "Roman", "Mixed" to "Mixte", "Unknown type" to "Type inconnu"),
    "de" to mapOf("Manga" to "Manga", "Novel" to "Roman", "Mixed" to "Gemischt", "Unknown type" to "Unbekannt"),
    "es" to mapOf("Manga" to "Manga", "Novel" to "Novela", "Mixed" to "Mixto", "Unknown type" to "Desconocido"),
    "pt" to mapOf("Manga" to "Mangá", "Novel" to "Romance", "Mixed" to "Misto", "Unknown type" to "Desconhecido"),
)
