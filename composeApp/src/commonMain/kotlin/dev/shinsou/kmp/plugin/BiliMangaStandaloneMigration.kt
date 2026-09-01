package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.typedLocalChapterUnitKey
import dev.shinsou.kmp.migration.shuyue.shuyueCompatibilityProjectionHandoffMarker
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl

/** Result of the restart-safe reviewed-to-standalone BiliManga library conversion. */
public data class BiliMangaStandaloneLibraryMigrationResult(
    val mangaCount: Int,
    val chapterCount: Int,
)

/**
 * Converts old app-local reviewed BiliManga favorites into ordinary numeric-source manga rows.
 *
 * IDs and relationship tables are untouched: only Manga source/url/initialization and recoverable
 * typed chapter URLs change. Consequently history, page progress, categories, updates, downloads,
 * trackers and bookmarks retain their existing foreign keys. The typed graph stays intact as a
 * read-only migration source and can continue to preserve already materialized bodies.
 */
public class BiliMangaStandaloneLibraryMigration(
    private val repository: ShinsouRepository,
    private val foundation: ContentFoundationRuntime,
) {
    public suspend fun migrate(): BiliMangaStandaloneLibraryMigrationResult {
        while (true) {
            val current = repository.currentSnapshot
            val migrated = migrateBiliMangaStandaloneSnapshot(current, foundation.publications.all())
            if (migrated.snapshot === current) return migrated.result
            if (
                repository.replaceSnapshotIfRevision(current.revision, migrated.snapshot) != null
            ) {
                return migrated.result
            }
        }
    }
}

internal data class BiliMangaStandaloneSnapshotMigration(
    val snapshot: AppSnapshot,
    val result: BiliMangaStandaloneLibraryMigrationResult,
)

internal fun migrateBiliMangaStandaloneSnapshot(
    snapshot: AppSnapshot,
    typedPublications: List<Publication>,
): BiliMangaStandaloneSnapshotMigration {
    val typedByKey = typedPublications.associateBy(Publication::key)
    val candidates = snapshot.mangas.mapNotNull { manga ->
        if (manga.source != LOCAL_SOURCE_ID) return@mapNotNull null
        val binding = decodeExtensionLibraryPublicationUrl(manga.url)
            ?.takeIf { it.sourceKey == LEGACY_BILIMANGA_MANGA_SOURCE_KEY }
            ?: return@mapNotNull null
        val typedChapters = snapshot.chapters.mapNotNull { chapter ->
            if (chapter.mangaId != manga.id) null
            else typedLocalChapterUnitKey(chapter.url)?.let { chapter.id to it }
        }
        val publication = typedByKey[binding.publicationKey]
        val remoteUrlByUnitKey = publication?.acquisitions
            .orEmpty()
            .asSequence()
            .flatMap { it.units.asSequence() }
            .mapNotNull { unit ->
                unit.sourceBinding
                    ?.takeIf { it.sourceKey == LEGACY_BILIMANGA_MANGA_SOURCE_KEY }
                    ?.let { sourceBinding ->
                        (sourceBinding.canonicalUrl ?: sourceBinding.remoteId)
                            .takeIf(String::isNotBlank)
                            ?.let { unit.key to it }
                    }
            }
            .toMap()
        // Keep an old row intact if a typed chapter cannot be restored yet. A later launch can
        // retry after the corresponding typed graph has been recovered from sync/backup.
        if (typedChapters.any { (_, unitKey) -> unitKey !in remoteUrlByUnitKey }) return@mapNotNull null
        BiliMangaLibraryCandidate(
            manga = manga,
            publicationKey = binding.publicationKey,
            remotePublicationId = binding.remotePublicationId,
            remoteChapterUrlById = typedChapters.associate { (chapterId, unitKey) ->
                chapterId to requireNotNull(remoteUrlByUnitKey[unitKey])
            },
        )
    }
    if (candidates.isEmpty()) {
        return BiliMangaStandaloneSnapshotMigration(
            snapshot,
            BiliMangaStandaloneLibraryMigrationResult(0, 0),
        )
    }
    val candidateByMangaId = candidates.associateBy { it.manga.id }
    val migratedMangas = snapshot.mangas.map { manga ->
        val candidate = candidateByMangaId[manga.id] ?: return@map manga
        manga.copy(
            source = BILIMANGA_MANGA_SOURCE_ID,
            url = candidate.remotePublicationId,
            initialized = false,
        )
    }
    val migratedChapters = snapshot.chapters.map { chapter ->
        candidateByMangaId[chapter.mangaId]
            ?.remoteChapterUrlById
            ?.get(chapter.id)
            ?.let { chapter.copy(url = it) }
            ?: chapter
    }
    return BiliMangaStandaloneSnapshotMigration(
        snapshot.copy(
            mangas = migratedMangas,
            chapters = migratedChapters,
            contentAuthorityProjectionMarkers = snapshot.contentAuthorityProjectionMarkers +
                candidates.map { shuyueCompatibilityProjectionHandoffMarker(it.publicationKey) },
        ),
        BiliMangaStandaloneLibraryMigrationResult(
            mangaCount = candidates.size,
            chapterCount = candidates.sumOf { it.remoteChapterUrlById.size },
        ),
    )
}

private data class BiliMangaLibraryCandidate(
    val manga: Manga,
    val publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    val remotePublicationId: String,
    val remoteChapterUrlById: Map<Long, String>,
)

public val LEGACY_BILIMANGA_MANGA_SOURCE_KEY: SourceKey = SourceKey(
    contractVersion = 2,
    packageId = "zh.bilimanga",
    sourceId = "zh.bilimanga.manga",
    legacyLongId = BILIMANGA_MANGA_SOURCE_ID,
)
