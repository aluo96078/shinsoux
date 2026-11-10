package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.local.typedLocalChapterUnitKey
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.reader.ReadingScope

/** The cross-device identity of a title. Local ids (Manga.id, Chapter.id) never appear in it. */
internal sealed interface SyncedWork {
    /** Key of the `library` / `progress` / `history` record. */
    val recordKey: String

    /** A title that has a source: shared by every device through its [WorkKey]. */
    data class Sourced(val key: WorkKey) : SyncedWork {
        override val recordKey: String get() = key.hash
    }

    /** A local import: private to this account, identified by the local publication uuid. */
    data class Local(val publicationUuid: String) : SyncedWork {
        override val recordKey: String get() = "local:$publicationUuid"
    }
}

/**
 * Maps local titles and chapters to portable sync identities and back. Everything here is
 * synchronous so the mutation observer can call it; the only async input is the legacy-source
 * lookup, served from a cache refreshed by the sync service.
 */
internal class SyncIdentityResolver(
    private val publications: (PublicationKey) -> Publication?,
    private val legacySourceKey: (Long) -> SourceKey?,
) {
    private val publicationCache = HashMap<PublicationKey, Publication?>()

    /** Publication lookups are cached for the duration of one batch; call between batches. */
    fun resetCache() {
        publicationCache.clear()
    }

    private fun publication(key: PublicationKey): Publication? =
        if (publicationCache.containsKey(key)) {
            publicationCache[key]
        } else {
            runCatching { publications(key) }.getOrNull().also { publicationCache[key] = it }
        }

    /** The title's portable identity, or null when it cannot be synced (unresolved legacy source). */
    fun workOf(manga: Manga): SyncedWork? {
        decodeExtensionLibraryPublicationUrl(manga.url)?.let { binding ->
            return sourced(binding.sourceKey.packageId, binding.sourceKey.sourceId, binding.remotePublicationId)
        }
        if (manga.source == LOCAL_SOURCE_ID) {
            val publicationKey = decodeTypedLocalPublicationUrl(manga.url)
                ?: typedLocalChapterUnitKeyOfAnyChapterUrl(manga.url)
                ?: return null
            val binding = publication(publicationKey)?.acquisitions
                ?.firstNotNullOfOrNull { (it.origin as? AcquisitionOrigin.ExtensionSource)?.sourceBinding }
            if (binding != null) {
                return sourced(binding.sourceKey.packageId, binding.sourceKey.sourceId, binding.remoteEntityKey.canonicalId)
            }
            return SyncedWork.Local(publicationKey.value)
        }
        val sourceKey = legacySourceKey(manga.source) ?: return null
        if (manga.url.isBlank()) return null
        return sourced(sourceKey.packageId, sourceKey.sourceId, manga.url)
    }

    private fun typedLocalChapterUnitKeyOfAnyChapterUrl(url: String): PublicationKey? =
        typedLocalChapterUnitKey(url)?.publicationKey

    private fun sourced(packageId: String, sourceId: String, remoteId: String): SyncedWork.Sourced? =
        runCatching { WorkKey(WorkKey.CURRENT_KEY_VERSION, packageId, sourceId, remoteId) }.getOrNull()
            ?.let(SyncedWork::Sourced)

    /** The chapter's id inside its work: the source's own id, or the unit uuid for local imports. */
    fun unitRemoteId(manga: Manga, work: SyncedWork, chapter: Chapter): String? {
        val typed = typedLocalChapterUnitKey(chapter.url)
        return when (work) {
            is SyncedWork.Local -> typed?.value
            is SyncedWork.Sourced -> if (typed != null) {
                publication(typed.publicationKey)?.acquisitions
                    ?.firstNotNullOfOrNull { acquisition -> acquisition.units.firstOrNull { it.key == typed } }
                    ?.sourceBinding?.remoteEntityKey?.canonicalId
            } else if (manga.source != LOCAL_SOURCE_ID && decodeExtensionLibraryPublicationUrl(manga.url) == null) {
                chapter.url.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }?.takeIf { value -> runCatching { requireValidUnitId(value) }.isSuccess }
    }

    private fun requireValidUnitId(value: String) {
        // Reuses WorkKey's component rules through the unit hash computation.
        WorkKey(1, "p", "s", "r").unitHash(value)
    }

    /** Rebuilds the local reading scope for a chapter; null for chapters without typed content. */
    fun scopeFor(chapter: Chapter): ReadingScope? {
        val unit = typedLocalChapterUnitKey(chapter.url) ?: return null
        val publication = publication(unit.publicationKey) ?: return null
        val acquisition = publication.acquisitions.firstOrNull { acq -> acq.units.any { it.key == unit } } ?: return null
        val publicationUnit = acquisition.units.first { it.key == unit }
        val revision = publicationUnit.latestManifest?.contentRevision ?: acquisition.contentRevision
        return runCatching {
            ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = unit.publicationKey,
                acquisitionId = acquisition.id,
                unitId = unit,
                contentRevision = revision,
            )
        }.getOrNull()
    }

    /** `manga` or `novel`; defaults to manga when the content is not materialised yet. */
    fun contentKindOf(manga: Manga, work: SyncedWork): String {
        val publicationKey = when (work) {
            is SyncedWork.Local -> PublicationKey(work.publicationUuid)
            is SyncedWork.Sourced -> decodeExtensionLibraryPublicationUrl(manga.url)?.publicationKey
                ?: decodeTypedLocalPublicationUrl(manga.url)
        } ?: return "manga"
        val kinds = publication(publicationKey)?.acquisitions.orEmpty()
            .flatMap { acq -> acq.units.flatMap { unit -> unit.manifestRevisions.flatMap { it.kinds } } }
            .toSet()
        val novel = ContentKind.PLAIN_TEXT in kinds || ContentKind.EPUB_SPINE in kinds
        return if (novel && ContentKind.IMAGE_SEQUENCE !in kinds) "novel" else "manga"
    }

    /** The work descriptor sent with every work-keyed record. */
    fun describe(manga: Manga, work: SyncedWork.Sourced): WireWork {
        val key = work.key
        return WireWork(
            keyVersion = key.keyVersion,
            packageId = key.packageId,
            sourceId = key.sourceId,
            remoteId = key.remoteId,
            hash = key.hash,
            title = manga.title.trim().ifEmpty { "Untitled" }.truncated(MAX_TITLE_CHARS),
            author = manga.author?.trim()?.takeIf { it.isNotEmpty() }?.truncated(MAX_TITLE_CHARS),
            coverUrl = manga.thumbnailUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_URL_CHARS },
            contentKind = contentKindOf(manga, work),
        )
    }

    companion object {
        const val MAX_TITLE_CHARS: Int = 512
        const val MAX_URL_CHARS: Int = 2048
    }
}

internal fun String.truncated(maxChars: Int): String {
    if (length <= maxChars) return this
    var end = maxChars
    if (this[end - 1].isHighSurrogate()) end--
    return substring(0, end)
}
