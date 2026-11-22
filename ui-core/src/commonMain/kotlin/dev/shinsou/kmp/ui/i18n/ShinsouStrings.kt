package dev.shinsou.kmp.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/**
 * The UI strings of one language: [table] holds its entries, and a key without one shows
 * [StringKey.english]. The frequently used labels are also exposed as properties.
 */
@Immutable
public class ShinsouStrings internal constructor(internal val table: Map<StringKey, String> = emptyMap()) {
    public val library: String get() = text(StringKey.LIBRARY)
    public val updates: String get() = text(StringKey.UPDATES)
    public val history: String get() = text(StringKey.HISTORY)
    public val browse: String get() = text(StringKey.BROWSE)
    public val more: String get() = text(StringKey.MORE)
    public val sources: String get() = text(StringKey.SOURCES)
    public val extensions: String get() = text(StringKey.EXTENSIONS)
    public val migration: String get() = text(StringKey.MIGRATION)
    public val downloads: String get() = text(StringKey.DOWNLOADS)
    public val statistics: String get() = text(StringKey.STATISTICS)
    public val settings: String get() = text(StringKey.SETTINGS)
    public val backup: String get() = text(StringKey.BACKUP_RESTORE)
    public val about: String get() = text(StringKey.ABOUT)
    public val search: String get() = text(StringKey.SEARCH)
    public val searchLibrary: String get() = text(StringKey.SEARCH_YOUR_LIBRARY)
    public val refresh: String get() = text(StringKey.REFRESH)
    public val filter: String get() = text(StringKey.FILTER)
    public val sort: String get() = text(StringKey.SORT)
    public val all: String get() = text(StringKey.ALL)
    public val done: String get() = text(StringKey.DONE)
    public val cancel: String get() = text(StringKey.CANCEL)
    public val save: String get() = text(StringKey.SAVE)
    public val close: String get() = text(StringKey.CLOSE)
    public val delete: String get() = text(StringKey.DELETE)
    public val remove: String get() = text(StringKey.REMOVE)
    public val retry: String get() = text(StringKey.RETRY)
    public val share: String get() = text(StringKey.SHARE)
    public val selectAll: String get() = text(StringKey.SELECT_ALL)
    public val selected: String get() = text(StringKey.SELECTED_2)
    public val markRead: String get() = text(StringKey.MARK_READ)
    public val markUnread: String get() = text(StringKey.MARK_UNREAD)
    public val moveToCategory: String get() = text(StringKey.MOVE_TO_CATEGORY)
    public val continueReading: String get() = text(StringKey.CONTINUE_READING)
    public val favorite: String get() = text(StringKey.ADD_TO_LIBRARY)
    public val unfavorite: String get() = text(StringKey.IN_LIBRARY)
    public val myLibrary: String get() = text(StringKey.MY_LIBRARY)
    public val download: String get() = text(StringKey.DOWNLOAD)
    public val install: String get() = text(StringKey.INSTALL)
    public val uninstall: String get() = text(StringKey.UNINSTALL)
    public val chapters: String get() = text(StringKey.CHAPTERS)
    public val readerSettings: String get() = text(StringKey.READER_SETTINGS)
    public val previousChapter: String get() = text(StringKey.PREVIOUS_CHAPTER)
    public val nextChapter: String get() = text(StringKey.NEXT_CHAPTER)
    public val pageTurnAnimation: String get() = text(StringKey.PAGE_TURN_ANIMATION)
    public val pageTurnAnimationDescription: String get() = text(StringKey.ANIMATE_TRANSITIONS_WHEN_CHANGING_PAGES)
    public val clearFilters: String get() = text(StringKey.CLEAR_FILTERS)
    public val noMatches: String get() = text(StringKey.NO_MATCHES)
    public val libraryEmpty: String get() = text(StringKey.YOUR_LIBRARY_IS_EMPTY)
    public val noUpdates: String get() = text(StringKey.NO_RECENT_UPDATES)
    public val noHistory: String get() = text(StringKey.NO_READING_HISTORY)
    public val createBackup: String get() = text(StringKey.CREATE_BACKUP)
    public val restoreBackup: String get() = text(StringKey.RESTORE_BACKUP)
}

public val LocalShinsouStrings: ProvidableCompositionLocal<ShinsouStrings> = staticCompositionLocalOf { EnglishStrings }

/** Resolve [key] in this language and replace optional `{0}`, `{1}`, ... tokens with [values]. */
public fun ShinsouStrings.text(key: StringKey, vararg values: Any?): String {
    var result = table[key] ?: key.english
    values.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
    return result
}

/** The localized text of an English message authored by our own code, or null for any other text. */
public fun ShinsouStrings.authoredMessage(message: String): String? = authoredMessageKey(message)?.let { text(it) }

/**
 * Text for display: a message authored by our own code is localized; anything else (plugin,
 * server or website text) is external and shown untranslated.
 */
public fun ShinsouStrings.authoredOrExternal(message: String): String = authoredMessage(message) ?: message

/** The language the app is shown in (a BCP-47 tag): the one chosen in Settings, else the system's. */
public val LocalAppLanguage: ProvidableCompositionLocal<String> = staticCompositionLocalOf { "en" }

@Composable
public fun ProvideShinsouStrings(
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

public fun shinsouStringsFor(language: String): ShinsouStrings {
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
