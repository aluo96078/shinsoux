package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.navigation.NavigationState
import dev.shinsou.kmp.ui.library.ALL_LIBRARY_CATEGORY_ID
import dev.shinsou.kmp.ui.settings.MoreDestination
import dev.shinsou.kmp.ui.browse.extensionUnitIdentity
import dev.shinsou.kmp.ui.library.LibrarySelection

/** The reader session a restored app reopens: identities and a page number only. */
internal data class ReaderSavedSession(
    val mangaId: Long,
    val chapterId: Long,
    /** The page the page reader last reported; null when it reported none (typed content). */
    val pageIndex: Int?,
)

/**
 * R4-3 (docs/architecture/0006): what of the app shell survives process death and configuration
 * changes. Only local row ids, enum names and a page number are kept: never a URL, a source
 * title's request data, headers, cookies or tokens. A source title still being resolved, a legacy
 * favorite being repaired and one-shot Back requests are not kept; they are started anew by the
 * user. Restoring goes through the same entry points as a fresh open ([restoreShellState]).
 */
internal data class ShellSavedState(
    val section: MainSection,
    val moreDestination: MoreDestination? = null,
    /** The title whose detail page was open. */
    val detailMangaId: Long? = null,
    /** The library row of the extension publication that was open from the local library. */
    val localExtensionMangaId: Long? = null,
    /**
     * The chapter its reader showed, as [extensionUnitIdentity] (a hash: the source's own unit id
     * can be a path or URL, which is never saved).
     */
    val localExtensionUnit: Long? = null,
    val reader: ReaderSavedSession? = null,
    val libraryIds: Set<Long> = emptySet(),
    val chapterIds: Set<Long> = emptySet(),
    val categoryId: Long = ALL_LIBRARY_CATEGORY_ID,
    /**
     * What the saved rows were, as hashes of their stable keys ([titleIdentity], [chapterIdentity]):
     * a backup restore or a re-import can give other rows the same numeric ids, and those are
     * not reopened. Null when not known (a state saved before they were kept).
     */
    val identities: SavedRowIdentities? = null,
) {
    /**
     * Encodes this as a list of strings, longs and long arrays, which every platform's saved-state
     * store accepts (an Android Bundle included).
     */
    fun encode(): List<Any> = listOf(
        FORMAT_VERSION,
        section.name,
        moreDestination?.name.orEmpty(),
        detailMangaId ?: NONE,
        localExtensionMangaId ?: NONE,
        reader?.mangaId ?: NONE,
        reader?.chapterId ?: NONE,
        reader?.pageIndex?.toLong() ?: NONE,
        libraryIds.toLongArray(),
        chapterIds.toLongArray(),
        categoryId,
        localExtensionUnit ?: NONE,
        identities?.detail ?: NONE,
        identities?.localExtension ?: NONE,
        identities?.reader ?: NONE,
        identities?.librarySelection ?: NONE,
        identities?.chapterSelection ?: NONE,
    )

    /** This state with the identities of the rows it names, as they are in [snapshot]. */
    fun withIdentitiesFrom(snapshot: AppSnapshot): ShellSavedState {
        fun title(id: Long?) = id?.let { mangaId -> snapshot.mangas.firstOrNull { it.id == mangaId } }
        val readerIdentity = reader?.let { saved ->
            val manga = title(saved.mangaId)
            val chapter = snapshot.chapters.firstOrNull { it.id == saved.chapterId && it.mangaId == saved.mangaId }
            if (manga != null && chapter != null) chapterIdentity(manga, chapter) else null
        }
        return copy(
            identities = SavedRowIdentities(
                detail = title(detailMangaId)?.let(::titleIdentity),
                localExtension = title(localExtensionMangaId)?.let(::localExtensionIdentity),
                reader = readerIdentity,
                librarySelection = librarySelectionIdentity(libraryIds, snapshot),
                chapterSelection = chapterSelectionIdentity(detailMangaId, chapterIds, snapshot),
            ),
        )
    }

    /**
     * This state as the platform's saved-state Bundle may hold it: Android sends it through a
     * Binder transaction of about 1 MiB shared with the rest of the activity, so a selection of
     * more than [MAX_BUNDLED_SELECTION] rows (select all in a large library) is not kept.
     */
    fun withinBundleLimits(): ShellSavedState {
        val library = libraryIds.size <= MAX_BUNDLED_SELECTION
        val chapters = chapterIds.size <= MAX_BUNDLED_SELECTION
        return if (library && chapters) {
            this
        } else {
            copy(
                libraryIds = if (library) libraryIds else emptySet(),
                chapterIds = if (chapters) chapterIds else emptySet(),
                identities = identities?.copy(
                    librarySelection = identities.librarySelection.takeIf { library },
                    chapterSelection = identities.chapterSelection.takeIf { chapters },
                ),
            )
        }
    }

    /** [encode] as one line of text, for a store that keeps strings only ([ShellStateStore]). */
    fun encodeText(): String = encode().joinToString(TEXT_FIELD_SEPARATOR) { field ->
        when (field) {
            is String -> "$TEXT_STRING$field"
            is Long -> "$TEXT_LONG$field"
            is LongArray -> TEXT_LONGS + field.joinToString(TEXT_LIST_SEPARATOR)
            else -> error("Unexpected saved field ${field::class.simpleName}")
        }
    }

    /**
     * This state with whatever no longer exists in [snapshot] dropped: a removed title or chapter
     * is simply not reopened, and selections keep only rows that are still there (the library
     * selection, only titles still in the library).
     */
    fun validatedAgainst(snapshot: AppSnapshot): ShellSavedState {
        val mangas = snapshot.mangas.associateBy { it.id }
        // An unfavorited title keeps its row but leaves the library (and so the library selection).
        val libraryMangaIds = snapshot.mangas.mapNotNullTo(hashSetOf()) { manga -> manga.id.takeIf { manga.favorite } }
        // The row with the id must also be the same title (or chapter) when its identity is known.
        fun sameTitle(id: Long?, identity: Long?): Long? =
            id?.takeIf { mangas[it]?.let { manga -> identity == null || titleIdentity(manga) == identity } == true }
        val detail = sameTitle(detailMangaId, identities?.detail)
        val reader = reader?.takeIf { saved ->
            val manga = mangas[saved.mangaId]
            val chapter = snapshot.chapters.firstOrNull { it.id == saved.chapterId && it.mangaId == saved.mangaId }
            val expected = identities?.reader
            manga != null && chapter != null && (expected == null || chapterIdentity(manga, chapter) == expected)
        }
        val localExtension = localExtensionMangaId?.takeIf { id -> stillTheLocalExtension(mangas[id]) }
        // A selection is kept only when every selected row is still the one selected: bulk
        // actions on it must never reach other rows that reused the ids.
        val libraryVerified = identities?.librarySelection == null ||
            librarySelectionIdentity(libraryIds, snapshot) == identities.librarySelection
        val chaptersVerified = identities?.chapterSelection == null ||
            chapterSelectionIdentity(detail, chapterIds, snapshot) == identities.chapterSelection
        return copy(
            detailMangaId = detail,
            localExtensionMangaId = localExtension,
            localExtensionUnit = localExtensionUnit?.takeIf { localExtension != null },
            reader = reader,
            libraryIds = if (libraryVerified) libraryIds.filterTo(linkedSetOf()) { it in libraryMangaIds } else emptySet(),
            chapterIds = if (detail == null || !chaptersVerified || chapterIds.isEmpty()) {
                emptySet()
            } else {
                // One pass over the library's chapters, not one per selected chapter.
                val detailChapterIds = snapshot.chapters.mapNotNullTo(hashSetOf()) { it.id.takeIf { _ -> it.mangaId == detail } }
                chapterIds.filterTo(linkedSetOf()) { it in detailChapterIds }
            },
        )
    }

    /**
     * Whether [manga] is still the extension title whose library page was saved. A library favorite
     * that left the library is not reopened; one opened from History (saved as not a favorite) is.
     */
    private fun stillTheLocalExtension(manga: Manga?): Boolean {
        val expected = identities?.localExtension
        return manga != null && (expected == null || expected == titleIdentity(manga) || expected == localExtensionIdentity(manga))
    }

    companion object {
        /** About 16 KiB of ids per selection: far below the Binder limit. */
        const val MAX_BUNDLED_SELECTION = 2_000

        private const val FORMAT_VERSION = 3L
        private const val NONE = Long.MIN_VALUE
        private const val FIELD_COUNT = 17

        /** The second format, without [identities]: still read after an update. */
        private const val FORMAT_VERSION_2 = 2L
        private const val FIELD_COUNT_2 = 12

        /** The first format, without [localExtensionUnit]: still read after an update. */
        private const val FORMAT_VERSION_1 = 1L
        private const val FIELD_COUNT_1 = 11

        /** Reads what [encode] wrote; null for anything else (an older format or a damaged value). */
        fun decode(encoded: Any?): ShellSavedState? {
            val fields = (encoded as? List<*>)?.takeIf {
                it.size == FIELD_COUNT && it[0] == FORMAT_VERSION ||
                    it.size == FIELD_COUNT_2 && it[0] == FORMAT_VERSION_2 ||
                    it.size == FIELD_COUNT_1 && it[0] == FORMAT_VERSION_1
            }
            val section = (fields?.get(1) as? String)?.let { name -> MainSection.entries.firstOrNull { it.name == name } }
            return if (fields == null || section == null) null else decodeFields(fields, section)
        }

        private fun decodeFields(fields: List<*>, section: MainSection): ShellSavedState {
            val readerManga = fields.id(5)
            val readerChapter = fields.id(6)
            return ShellSavedState(
                section = section,
                moreDestination = (fields[2] as? String)?.let { name -> MoreDestination.entries.firstOrNull { it.name == name } },
                detailMangaId = fields.id(3),
                localExtensionMangaId = fields.id(4),
                reader = if (readerManga != null && readerChapter != null) {
                    ReaderSavedSession(readerManga, readerChapter, fields.id(7)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt())
                } else {
                    null
                },
                libraryIds = (fields[8] as? LongArray)?.toSet().orEmpty(),
                chapterIds = (fields[9] as? LongArray)?.toSet().orEmpty(),
                categoryId = fields[10] as? Long ?: ALL_LIBRARY_CATEGORY_ID,
                localExtensionUnit = if (fields.size > FIELD_COUNT_1) fields.id(11) else null,
                identities = if (fields.size > FIELD_COUNT_2) {
                    SavedRowIdentities(
                        detail = fields.id(12),
                        localExtension = fields.id(13),
                        reader = fields.id(14),
                        librarySelection = fields.id(15),
                        chapterSelection = fields.id(16),
                    ).takeUnless { it == SavedRowIdentities() }
                } else {
                    null
                },
            )
        }

        /** Reads what [encodeText] wrote; null for anything else. */
        fun decodeText(text: String): ShellSavedState? {
            if (text.length > MAX_TEXT_LENGTH) return null
            val fields = text.split(TEXT_FIELD_SEPARATOR).map(::decodeTextField)
            return if (null in fields) null else decode(fields)
        }

        private fun decodeTextField(token: String): Any? {
            val value = token.drop(1)
            return when (token.firstOrNull()) {
                TEXT_STRING -> value
                TEXT_LONG -> value.toLongOrNull()
                TEXT_LONGS -> if (value.isEmpty()) {
                    LongArray(0)
                } else {
                    value.split(TEXT_LIST_SEPARATOR).map { it.toLongOrNull() ?: return null }.toLongArray()
                }
                else -> null
            }
        }

        private const val TEXT_FIELD_SEPARATOR = "|"
        private const val TEXT_LIST_SEPARATOR = ","
        private const val TEXT_STRING = 's'
        private const val TEXT_LONG = 'l'
        private const val TEXT_LONGS = 'a'

        /** Far beyond any real selection: a larger value is damaged and not read. */
        private const val MAX_TEXT_LENGTH = 4 * 1_024 * 1_024

        private fun List<*>.id(index: Int): Long? = (this[index] as? Long)?.takeIf { it != NONE }
    }
}

/**
 * [state] as a [ShellStateStore] keeps it between launches, with the Starting screen preference the
 * saving launch began with ([readStoredShellState]).
 */
internal fun storedShellStateText(state: ShellSavedState, startingScreen: MainSection): String =
    "${startingScreen.name}$STORED_STARTING_SCREEN_SEPARATOR${state.encodeText()}"

/**
 * What [storedShellStateText] kept, or null when it cannot be read or the Starting screen is no
 * longer the one its launch began with (the user changed it): the preference then decides where
 * this launch opens.
 */
internal fun readStoredShellState(text: String, startingScreen: MainSection): ShellSavedState? {
    val savedUnder = text.substringBefore(STORED_STARTING_SCREEN_SEPARATOR, missingDelimiterValue = "")
    return if (savedUnder != startingScreen.name) {
        null
    } else {
        ShellSavedState.decodeText(text.substringAfter(STORED_STARTING_SCREEN_SEPARATOR))
    }
}

private const val STORED_STARTING_SCREEN_SEPARATOR = "\n"

/**
 * Hashes of the stable keys of the rows a [ShellSavedState] names (null: that row is not named,
 * or the selection is empty). A selection's hash covers every selected row.
 */
internal data class SavedRowIdentities(
    val detail: Long? = null,
    val localExtension: Long? = null,
    val reader: Long? = null,
    val librarySelection: Long? = null,
    val chapterSelection: Long? = null,
)

/** The selected titles' identities, combined; null when nothing is selected. */
private fun librarySelectionIdentity(ids: Set<Long>, snapshot: AppSnapshot): Long? {
    if (ids.isEmpty()) return null
    val mangas = snapshot.mangas.associateBy { it.id }
    return combinedIdentity(ids.sorted().map { id -> "$id=${mangas[id]?.let(::titleIdentity)}" })
}

/** The selected chapters' identities (on the detail page of [mangaId]), combined; null when none. */
private fun chapterSelectionIdentity(mangaId: Long?, ids: Set<Long>, snapshot: AppSnapshot): Long? {
    if (ids.isEmpty() || mangaId == null) return null
    val manga = snapshot.mangas.firstOrNull { it.id == mangaId }
    val chapters = snapshot.chapters.associateBy { it.id }
    return combinedIdentity(
        ids.sorted().map { id ->
            val chapter = chapters[id]?.takeIf { it.mangaId == mangaId }
            "$id=${if (manga != null && chapter != null) chapterIdentity(manga, chapter) else null}"
        },
    )
}

private fun combinedIdentity(parts: List<String>): Long = extensionUnitIdentity(parts.joinToString(","))

/** A title's identity across libraries: its stable key, hashed (the URL is never saved). */
internal fun titleIdentity(manga: Manga): Long = extensionUnitIdentity(titleKey(manga))

/**
 * The identity of an extension title's library page: [titleIdentity], told apart while the title
 * is a library favorite, so that one reopens only while it still is.
 */
internal fun localExtensionIdentity(manga: Manga): Long =
    if (manga.favorite) extensionUnitIdentity("$LIBRARY_FAVORITE_MARK${titleKey(manga)}") else titleIdentity(manga)

private const val LIBRARY_FAVORITE_MARK = "library-favorite:"

/** A chapter's identity across libraries: its title's, and its own URL, hashed. */
internal fun chapterIdentity(manga: Manga, chapter: Chapter): Long =
    extensionUnitIdentity("${titleKey(manga)}\u0000${chapter.url}")

/**
 * What stays the same for a title: a local-library extension or typed row's publication key (a
 * legacy row keeps it when its URL is rewritten to the current form), else its source and URL.
 */
private fun titleKey(manga: Manga): String {
    val publication = decodeExtensionLibraryPublicationUrl(manga.url)?.publicationKey
        ?: decodeTypedLocalPublicationUrl(manga.url)
    return publication?.let { "publication\u0000${it.value}" } ?: "${manga.source}\u0000${manga.url}"
}

/** The local-library extension title that is open, and the chapter its reader shows. */
internal data class SavedLocalExtension(val mangaId: Long, val unit: Long? = null)

/** The state to save, from the shell's holders. The reader is left out in incognito mode. */
internal fun captureShellState(
    navigation: NavigationState,
    selection: LibrarySelection,
    reader: ReaderSavedSession?,
    localExtension: SavedLocalExtension?,
    incognito: Boolean,
): ShellSavedState = ShellSavedState(
    section = navigation.section,
    moreDestination = navigation.moreDestination,
    detailMangaId = navigation.selectedMangaId,
    localExtensionMangaId = localExtension?.mangaId,
    localExtensionUnit = localExtension?.unit.takeUnless { incognito },
    reader = reader.takeUnless { incognito },
    libraryIds = selection.libraryIds,
    chapterIds = selection.chapterIds,
    categoryId = selection.categoryId,
)

/** The shell's ordinary entry points, which a restoration goes through. */
internal interface ShellRestoreTarget {
    fun selectSection(section: MainSection)

    fun openMore(destination: MoreDestination)

    /**
     * Opens a title as a tap would: its routing, permission review and detail page.
     * [onSettled] runs once, when the open ends (which may be after this returns: typed local
     * titles are routed off the UI thread), [TitleOpenOutcome.ABANDONED] when the user moved on
     * before the routing finished.
     */
    fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit)

    fun openReader(saved: ReaderSavedSession)

    /** Reopens the chapter [identity] in the reader of the local-library extension title [mangaId] just opened. */
    fun reopenExtensionUnit(mangaId: Long, identity: Long) {}

    fun restoreSelection(libraryIds: Set<Long>, categoryId: Long)

    /** Selects [chapterIds] on the detail page of [mangaId], only while that page is open. */
    fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>)
}

/**
 * This target, doing nothing once [stopped] (a link the user followed decided where the app is):
 * a restoration still under way then neither navigates nor reopens the reader over it.
 */
internal fun ShellRestoreTarget.until(stopped: () -> Boolean): ShellRestoreTarget {
    val target = this
    return object : ShellRestoreTarget {
        override fun selectSection(section: MainSection) {
            if (!stopped()) target.selectSection(section)
        }

        override fun openMore(destination: MoreDestination) {
            if (!stopped()) target.openMore(destination)
        }

        override fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit) {
            if (stopped()) onSettled(TitleOpenOutcome.ABANDONED) else target.openManga(mangaId, onSettled)
        }

        override fun openReader(saved: ReaderSavedSession) {
            if (!stopped()) target.openReader(saved)
        }

        override fun reopenExtensionUnit(mangaId: Long, identity: Long) {
            if (!stopped()) target.reopenExtensionUnit(mangaId, identity)
        }

        override fun restoreSelection(libraryIds: Set<Long>, categoryId: Long) {
            if (!stopped()) target.restoreSelection(libraryIds, categoryId)
        }

        override fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>) {
            if (!stopped()) target.restoreChapterSelection(mangaId, chapterIds)
        }
    }
}

/**
 * Reopens what [saved] (already [ShellSavedState.validatedAgainst] the current library) describes,
 * in the order a user would: the section, then a page of More or a title, then the reader, then
 * the selections (which opening a page clears). [onFinished] runs once the restoration is over,
 * including when it was given up.
 *
 * A typed local title opens asynchronously, and opening the reader changes the visible page,
 * which would make that open stale. So everything after the title waits for it to open; if the
 * user moved on meanwhile, the rest is not restored either.
 */
internal fun restoreShellState(saved: ShellSavedState, target: ShellRestoreTarget, onFinished: () -> Unit = {}) {
    target.selectSection(saved.section)
    saved.moreDestination?.let(target::openMore)
    val restoreRest = { openedMangaId: Long?, detailShown: Boolean ->
        // Chapter selections belong to the detail page: applied once it is open, not before.
        if (openedMangaId != null && detailShown && saved.chapterIds.isNotEmpty()) {
            target.restoreChapterSelection(openedMangaId, saved.chapterIds)
        }
        saved.reader?.let(target::openReader)
        if (openedMangaId != null && openedMangaId == saved.localExtensionMangaId && !detailShown) {
            saved.localExtensionUnit?.let { identity -> target.reopenExtensionUnit(openedMangaId, identity) }
        }
        target.restoreSelection(saved.libraryIds, saved.categoryId)
    }
    val mangaId = saved.localExtensionMangaId ?: saved.detailMangaId
    if (mangaId == null) {
        restoreRest(null, false)
        onFinished()
    } else {
        target.openManga(mangaId) { outcome ->
            if (outcome != TitleOpenOutcome.ABANDONED) restoreRest(mangaId, outcome == TitleOpenOutcome.DETAIL)
            onFinished()
        }
    }
}
