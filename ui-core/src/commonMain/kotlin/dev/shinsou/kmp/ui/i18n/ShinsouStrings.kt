package dev.shinsou.kmp.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * The UI strings of one language: [table] holds its entries, and a key without one shows
 * [StringKey.english]. The frequently used labels are also exposed as properties.
 */
@Immutable
class ShinsouStrings internal constructor(internal val table: Map<StringKey, String> = emptyMap()) {
    val library: String get() = text(StringKey.LIBRARY)
    val updates: String get() = text(StringKey.UPDATES)
    val history: String get() = text(StringKey.HISTORY)
    val browse: String get() = text(StringKey.BROWSE)
    val more: String get() = text(StringKey.MORE)
    val sources: String get() = text(StringKey.SOURCES)
    val extensions: String get() = text(StringKey.EXTENSIONS)
    val migration: String get() = text(StringKey.MIGRATION)
    val downloads: String get() = text(StringKey.DOWNLOADS)
    val statistics: String get() = text(StringKey.STATISTICS)
    val settings: String get() = text(StringKey.SETTINGS)
    val backup: String get() = text(StringKey.BACKUP_RESTORE)
    val about: String get() = text(StringKey.ABOUT)
    val search: String get() = text(StringKey.SEARCH)
    val searchLibrary: String get() = text(StringKey.SEARCH_YOUR_LIBRARY)
    val refresh: String get() = text(StringKey.REFRESH)
    val filter: String get() = text(StringKey.FILTER)
    val sort: String get() = text(StringKey.SORT)
    val all: String get() = text(StringKey.ALL)
    val done: String get() = text(StringKey.DONE)
    val cancel: String get() = text(StringKey.CANCEL)
    val save: String get() = text(StringKey.SAVE)
    val close: String get() = text(StringKey.CLOSE)
    val delete: String get() = text(StringKey.DELETE)
    val remove: String get() = text(StringKey.REMOVE)
    val retry: String get() = text(StringKey.RETRY)
    val share: String get() = text(StringKey.SHARE)
    val selectAll: String get() = text(StringKey.SELECT_ALL)
    val selected: String get() = text(StringKey.SELECTED_2)
    val markRead: String get() = text(StringKey.MARK_READ)
    val markUnread: String get() = text(StringKey.MARK_UNREAD)
    val moveToCategory: String get() = text(StringKey.MOVE_TO_CATEGORY)
    val continueReading: String get() = text(StringKey.CONTINUE_READING)
    val favorite: String get() = text(StringKey.ADD_TO_LIBRARY)
    val unfavorite: String get() = text(StringKey.IN_LIBRARY)
    val myLibrary: String get() = text(StringKey.MY_LIBRARY)
    val download: String get() = text(StringKey.DOWNLOAD)
    val install: String get() = text(StringKey.INSTALL)
    val uninstall: String get() = text(StringKey.UNINSTALL)
    val chapters: String get() = text(StringKey.CHAPTERS)
    val readerSettings: String get() = text(StringKey.READER_SETTINGS)
    val previousChapter: String get() = text(StringKey.PREVIOUS_CHAPTER)
    val nextChapter: String get() = text(StringKey.NEXT_CHAPTER)
    val pageTurnAnimation: String get() = text(StringKey.PAGE_TURN_ANIMATION)
    val pageTurnAnimationDescription: String get() = text(StringKey.ANIMATE_TRANSITIONS_WHEN_CHANGING_PAGES)
    val clearFilters: String get() = text(StringKey.CLEAR_FILTERS)
    val noMatches: String get() = text(StringKey.NO_MATCHES)
    val libraryEmpty: String get() = text(StringKey.YOUR_LIBRARY_IS_EMPTY)
    val noUpdates: String get() = text(StringKey.NO_RECENT_UPDATES)
    val noHistory: String get() = text(StringKey.NO_READING_HISTORY)
    val createBackup: String get() = text(StringKey.CREATE_BACKUP)
    val restoreBackup: String get() = text(StringKey.RESTORE_BACKUP)
}

val LocalShinsouStrings = staticCompositionLocalOf { EnglishStrings }

/** Resolve [key] in this language and replace optional `{0}`, `{1}`, ... tokens with [values]. */
fun ShinsouStrings.text(key: StringKey, vararg values: Any?): String {
    var result = table[key] ?: key.english
    values.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
    return result
}

/** The localized text of an English message authored by our own code, or null for any other text. */
fun ShinsouStrings.authoredMessage(message: String): String? = authoredMessageKey(message)?.let { text(it) }

/**
 * Text for display: a message authored by our own code is localized; anything else (plugin,
 * server or website text) is external and shown untranslated.
 */
fun ShinsouStrings.authoredOrExternal(message: String): String = authoredMessage(message) ?: message

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

internal val EnglishStrings = ShinsouStrings()
internal val TraditionalChineseStrings = ShinsouStrings(TraditionalChineseTable)
internal val SimplifiedChineseStrings = ShinsouStrings(SimplifiedChineseTable)
internal val JapaneseStrings = ShinsouStrings(JapaneseTable)
internal val KoreanStrings = ShinsouStrings(KoreanTable)
internal val FrenchStrings = ShinsouStrings(FrenchTable)
internal val GermanStrings = ShinsouStrings(GermanTable)
internal val SpanishStrings = ShinsouStrings(SpanishTable)
internal val PortugueseStrings = ShinsouStrings(PortugueseTable)
