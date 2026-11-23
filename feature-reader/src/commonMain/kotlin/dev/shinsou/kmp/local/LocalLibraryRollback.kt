package dev.shinsou.kmp.local

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.contract.LocalImportResult
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.files.AppFileSystem

/** How many snapshot replacements in a row a library change waits out before it gives up. */
private const val MAX_REPLACEMENT_RETRIES = 3

/** Where the files of a legacy local chapter are kept. */
internal fun localChapterDirectory(mangaId: Long, chapterId: Long): String = "local/$mangaId/$chapterId"

/**
 * The library rows an import takes out of the library for the titles that replace them, and the
 * undoing of every row a failed import wrote ([LocalContentManager]).
 */
internal class LocalLibraryRollback(
    private val repository: ShinsouRepository,
    private val fileSystem: AppFileSystem,
    private val now: () -> Long,
) {
    /**
     * Takes the title of [chapterUrl] out of the library, for [replacement], the title this import
     * wrote in its place; nothing when it is not in it. A backup restore since [replacement] was
     * written can have brought the old title back without the new one, so the old one leaves only
     * while the snapshot is still the one [replacement] was written to and still has it in the
     * library: the repository checks both and changes the row under one lock. The row is changed
     * only while it is the one read here, and its category links are kept for [undo] (category
     * edits run on the same store queue as this write, so they cannot fall in between).
     * [replacement] takes the links over, so the corrected reading stays on the user's shelves.
     */
    suspend fun removeFromLibrary(chapterUrl: String, replacement: LibraryChange): List<LibraryChange> {
        val row = repository.manga(LOCAL_SOURCE_ID, chapterUrl.substringBeforeLast('/'))?.takeIf(Manga::favorite) ?: return emptyList()
        val categoryIds = repository.categoryIdsOfManga(row.id)
        val replacementLinks = repository.categoryIdsOfManga(replacement.after.id)
        // A replacement already in the library before this import keeps all its shelves.
        val wasInLibrary = replacement.before?.favorite == true
        var replacements = replacement.replacementCount
        var removed = unfavoriteFor(row, replacement, replacements, wasInLibrary)
        // A snapshot replacement since (a sync pull) refuses it by its count alone. When neither
        // title changed in it, nothing it did concerns them: the old title leaves under the count
        // now. The count is read before the rows are, so the rows checked are those of that count;
        // one more replacement in between refuses that try too, and the rows are read again.
        var tries = 0
        while (removed == null && tries++ < MAX_REPLACEMENT_RETRIES) {
            val current = repository.snapshotReplacementCount
            if (current == replacements || !unchanged(row, replacement)) break
            replacements = current
            removed = unfavoriteFor(row, replacement, replacements, wasInLibrary)
        }
        return listOfNotNull(
            removed?.let { left ->
                LibraryChange(
                    before = row,
                    after = left,
                    replacementCount = replacements,
                    links = LibraryChangeLinks(
                        categoryIds = categoryIds,
                        carried = CarriedCategoryLinks(
                            mangaId = replacement.after.id,
                            before = replacementLinks,
                            after = repository.categoryIdsOfManga(replacement.after.id),
                        ),
                    ),
                )
            },
        )
    }

    private suspend fun unfavoriteFor(row: Manga, replacement: LibraryChange, replacements: Long, wasInLibrary: Boolean): Manga? =
        repository.unfavoriteMangaIf(row, replacements, now(), replacedBy = replacement.after, replacedByWasInLibrary = wasInLibrary)

    /** Whether the old title and its replacement are still exactly the rows this import read and wrote. */
    private fun unchanged(row: Manga, replacement: LibraryChange): Boolean =
        repository.manga(row.id) == row && repository.manga(replacement.after.id) == replacement.after

    /**
     * Undoes one change of a failed import. Other library writes can run between the files, so
     * the row is touched only while it is exactly what the import left: a backup restore can give
     * its id and URL to a title of its own, and a user edit is kept. The repository checks that and
     * changes the row under one lock; files are removed only for a row this did delete.
     */
    suspend fun undo(change: LibraryChange) {
        val before = change.before
        if (before != null) {
            runCatchingCancellable {
                // Only the links this change left are put back: a shelf edit made since is kept.
                change.links.carried?.let { carried ->
                    repository.replaceCategoryLinksIf(carried.mangaId, change.replacementCount, carried.after, carried.before)
                }
                repository.replaceMangaIf(
                    change.after,
                    change.replacementCount,
                    before,
                    change.links.categoryIds,
                    expectedCategoryIds = change.linksLeft,
                )
            }
            return
        }
        if (change.content != null) {
            // Its publication stays committed, and startup repair would bring a deleted row back
            // into the library: the row stays, out of it, which repair leaves alone.
            runCatchingCancellable { repository.unfavoriteMangaIf(change.after, change.replacementCount, now()) }
            return
        }
        val deleted = runCatchingCancellable { repository.deleteMangaIf(change.after, change.replacementCount) }
            .getOrDefault(false)
        if (deleted) change.result?.let { fileSystem.deleteTree(localChapterDirectory(it.mangaId, it.chapterId)) }
    }
}

/** One library row an import wrote, and what undoing it needs. */
internal class LibraryChange(
    /** The row before the import changed it; null when the import created it. */
    val before: Manga?,
    /** The row as the import left it. */
    val after: Manga,
    /** Snapshot replacements when the row was written ([ShinsouRepository.snapshotReplacementCount]). */
    val replacementCount: Long,
    /** The title this change imported; null for a title only taken out of the library. */
    val result: LocalImportResult? = null,
    /** The typed content of [result]; null for a legacy image import or a title only taken out. */
    val content: LocalTypedImport? = null,
    /** The category links the change dropped or added to, and those it carried to another title. */
    val links: LibraryChangeLinks = LibraryChangeLinks(),
) {
    /**
     * The links of [after] as this change left them: none for a title it took out of the library,
     * [LibraryChangeLinks.categoryIds] for one it only wrote again (a re-import keeps them).
     */
    val linksLeft: List<Long> get() = if (after.favorite) links.categoryIds else emptyList()

    /** The typed chapter URL of [result]. */
    val chapterUrl: String? get() = content?.chapterUrl

    /** Library chapters of the same TXT in another encoding, which [result] replaces. */
    val supersededChapterUrls: List<String> get() = content?.supersededChapterUrls.orEmpty()
}


/** The category links of title [mangaId] before and after it took over another title's. */
internal class CarriedCategoryLinks(val mangaId: Long, val before: List<Long>, val after: List<Long>)

/**
 * The category links of [LibraryChange.before] that the change dropped or added to, and the links
 * of the title that took its place and took them over ([carried]).
 */
internal class LibraryChangeLinks(
    val categoryIds: List<Long> = emptyList(),
    val carried: CarriedCategoryLinks? = null,
)
