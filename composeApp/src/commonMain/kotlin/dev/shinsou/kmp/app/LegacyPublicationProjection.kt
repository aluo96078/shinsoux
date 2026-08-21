package dev.shinsou.kmp.app

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.LegacyCategoryRecordV1
import dev.shinsou.kmp.domain.model.LegacyChapterRecordV1
import dev.shinsou.kmp.domain.model.LegacyMangaAggregateV1
import dev.shinsou.kmp.domain.model.LegacyMangaCategoryLinkV1
import dev.shinsou.kmp.domain.model.LegacyMangaMapper
import dev.shinsou.kmp.domain.model.LegacyMangaRecordV1
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.PortableAliasResolver

/**
 * Read-only compatibility seam from the v1 AppSnapshot authority to publication-domain records.
 *
 * It installs no repository observer and writes no sync draft. UI, backup and v1 decoding remain
 * unchanged; callers opt into a projection when staging a migration or reading the new domain.
 */
public class LegacyPublicationProjection(
    private val aliases: PortableAliasResolver,
    private val namespace: MigrationNamespaceId = MigrationNamespaceId.LEGACY_MANGA_V1,
) {
    public fun project(snapshot: AppSnapshot): List<LegacyPublicationBundle> {
        snapshot.validate()
        // Keep the AppSnapshot-wide positions before grouping. A per-Manga mapIndexed would make
        // interleaved Chapter/MangaCategory lists impossible to materialize without reordering.
        val chaptersByManga = snapshot.chapters
            .mapIndexed { index, chapter ->
                chapter.mangaId to LegacyChapterRecordV1.fromChapter(
                    chapter,
                    legacyListOrdinal = index,
                )
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val linksByManga = snapshot.mangaCategories
            .mapIndexed { index, link ->
                link.mangaId to LegacyMangaCategoryLinkV1.fromMangaCategory(
                    link,
                    legacyListOrdinal = index,
                )
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        // Every aggregate carries the complete catalog. Restricting this to linked categories
        // would silently discard empty categories when the projection is materialized again.
        val categories = snapshot.categories.mapIndexed { index, category ->
            LegacyCategoryRecordV1.fromCategory(category, legacyListOrdinal = index)
        }
        return snapshot.mangas.mapIndexed { mangaIndex, manga ->
            val aggregate = LegacyMangaAggregateV1(
                record = LegacyMangaRecordV1.fromManga(manga, legacyListOrdinal = mangaIndex),
                chapters = chaptersByManga[manga.id].orEmpty(),
                categories = categories,
                links = linksByManga[manga.id].orEmpty(),
            )
            LegacyMangaMapper.toPublication(aggregate, aliases, namespace)
        }
    }

    /**
     * Rebuilds the four publication-owned v1 lists while retaining every unrelated compatibility
     * field from [baseSnapshot]. Global ordinals must be a complete 0-based sequence, so a missing,
     * duplicated, or independently assembled bundle fails closed instead of silently reordering
     * legacy state.
     *
     * When [bundles] is empty there is no bundle capable of carrying the category catalog. In that
     * case [baseSnapshot]'s catalog is retained; callers materializing an empty-library projection
     * should therefore pass the decoded compatibility snapshot as the base.
     */
    public fun materialize(
        bundles: List<LegacyPublicationBundle>,
        baseSnapshot: AppSnapshot = AppSnapshot(),
    ): AppSnapshot {
        require(bundles.all { it.namespace == namespace }) {
            "Legacy publication bundles use another migration namespace"
        }
        val aggregates = bundles.map(LegacyMangaMapper::toLegacy)
        val mangas = restoreGlobalOrder(
            records = aggregates.map(LegacyMangaAggregateV1::record),
            label = "Manga",
            ordinal = LegacyMangaRecordV1::legacyListOrdinal,
        ).map(LegacyMangaRecordV1::toManga)
        val chapters = restoreGlobalOrder(
            records = aggregates.flatMap(LegacyMangaAggregateV1::chapters),
            label = "Chapter",
            ordinal = LegacyChapterRecordV1::legacyListOrdinal,
        ).map(LegacyChapterRecordV1::toChapter)
        val links = restoreGlobalOrder(
            records = aggregates.flatMap(LegacyMangaAggregateV1::links),
            label = "MangaCategory",
            ordinal = LegacyMangaCategoryLinkV1::legacyListOrdinal,
        ).map(LegacyMangaCategoryLinkV1::toMangaCategory)
        val categoryRecords = if (aggregates.isEmpty()) {
            baseSnapshot.categories.mapIndexed { index, category ->
                LegacyCategoryRecordV1.fromCategory(category, legacyListOrdinal = index)
            }
        } else {
            val catalog = aggregates.first().categories
            require(aggregates.all { it.categories == catalog }) {
                "Legacy publication bundles contain inconsistent category catalogs"
            }
            catalog
        }
        val categories = restoreGlobalOrder(
            records = categoryRecords,
            label = "Category",
            ordinal = LegacyCategoryRecordV1::legacyListOrdinal,
        ).map(LegacyCategoryRecordV1::toCategory)

        return baseSnapshot.copy(
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = links,
        ).validate()
    }
}

private fun <T> restoreGlobalOrder(
    records: List<T>,
    label: String,
    ordinal: (T) -> Int,
): List<T> {
    val ordered = records.sortedBy(ordinal)
    ordered.forEachIndexed { expected, record ->
        require(ordinal(record) == expected) {
            "Legacy $label list ordinals must be unique and contiguous"
        }
    }
    return ordered
}
