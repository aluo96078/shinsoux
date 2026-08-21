package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.reader.ReadingLocator

public data class ContentPortableGraphReplacementSyncDraftPlan(
    val drafts: List<SyncDraft>,
    val tombstonedAnnotationIds: List<String>,
)

/**
 * Computes the negative half of an authoritative portable-graph replacement.
 *
 * Publishing only the replacement documents is insufficient: LWW entities that disappeared from
 * the archive remain present on every other device and can later resurrect their blob references.
 * This factory emits deterministic child-first presence tombstones plus redacted annotation
 * tombstones. It never carries removed body bytes or quote text.
 */
public object ContentPortableGraphReplacementSyncDraftFactory {
    public fun build(
        previousPublications: List<Publication>,
        replacementPublications: List<Publication>,
        previousAnnotations: List<ContentAnnotation>,
        replacementAnnotations: List<ContentAnnotation>,
        operationNamespace: String,
        createdAtMillis: Long,
    ): ContentPortableGraphReplacementSyncDraftPlan {
        require(operationNamespace.isNotBlank() && operationNamespace.length <= 4_096) {
            "Content replacement operation namespace is invalid"
        }
        require(createdAtMillis >= 0) { "Content replacement draft time cannot be negative" }
        previousPublications.forEach(Publication::validate)
        replacementPublications.forEach(Publication::validate)
        previousAnnotations.forEach(ContentAnnotation::validate)
        replacementAnnotations.forEach(ContentAnnotation::validate)

        val previousKeys = previousPublications.graphKeys()
        val replacementKeys = replacementPublications.graphKeys()
        val removedKeys = (previousKeys - replacementKeys).sortedWith(
            compareBy<SyncEntityKey> { deletionPriority(it.entityType) }.thenBy { it },
        )
        val previousBlobIds = previousPublications.blobIds()
        val replacementBlobIds = replacementPublications.blobIds()
        val removedBlobIds = (previousBlobIds - replacementBlobIds).sorted()

        val replacementAnnotationIds = replacementAnnotations.mapTo(hashSetOf()) {
            it.annotationId
        }
        val tombstones = previousAnnotations
            .filter { it.annotationId !in replacementAnnotationIds }
            .sortedBy(ContentAnnotation::annotationId)
            .map { annotation -> annotation.asReplacementTombstone() }

        val presenceMutations = buildList<SyncMutation> {
            removedBlobIds.forEach { blobId -> add(BlobReferencePresenceSetV2(blobId, false)) }
            removedKeys.forEach { key -> add(EntityPresenceSet(key, false)) }
        }
        val namespaceHash = Sha256.hex(operationNamespace.encodeToByteArray())
        val presenceDrafts = if (presenceMutations.isEmpty()) {
            emptyList()
        } else {
            CanonicalSyncDraftPacker.pack(
                mutations = presenceMutations,
                createdAtMillis = createdAtMillis,
                draftId = { index ->
                    "content-replacement-v2:$namespaceHash:${index.toString().padStart(6, '0')}"
                },
            )
        }
        val annotationDrafts = ContentAnnotationSyncDraftFactory.build(
            annotations = tombstones,
            operationNamespace = "$operationNamespace:annotation-tombstones",
            createdAtMillis = createdAtMillis,
        ).drafts
        val drafts = presenceDrafts + annotationDrafts
        require(drafts.map(SyncDraft::draftId).distinct().size == drafts.size) {
            "Content replacement tombstone draft ids collide"
        }
        return ContentPortableGraphReplacementSyncDraftPlan(
            drafts = drafts,
            tombstonedAnnotationIds = tombstones.map(ContentAnnotation::annotationId),
        )
    }

    private fun List<Publication>.graphKeys(): Set<SyncEntityKey> {
        val keys = flatMap { publication ->
            buildList {
                add(SyncEntityKey.publication(publication.key.value))
                publication.acquisitions.forEach { acquisition ->
                    add(SyncEntityKey.acquisition(acquisition.id))
                    acquisition.units.forEach { unit ->
                        add(SyncEntityKey.publicationUnit(unit.key.value))
                        unit.manifestRevisions.forEach { manifest ->
                            add(SyncEntityKey.contentManifest(manifest.manifestId))
                        }
                    }
                }
            }
        }
        require(keys.distinct().size == keys.size) {
            "Portable content graph reuses a globally scoped sync identity"
        }
        return keys.toSet()
    }

    private fun List<Publication>.blobIds(): Set<String> = flatMap { publication ->
        publication.acquisitions.flatMap { acquisition ->
            acquisition.units.flatMap { unit ->
                unit.manifestRevisions.flatMap { manifest ->
                    manifest.referencedBlobs.map { blob -> blob.blobId }
                }
            }
        }
    }.toSet()

    private fun ContentAnnotation.asReplacementTombstone(): ContentAnnotation = copy(
        range = range.copy(
            start = range.start.withoutQuote(),
            end = range.end.withoutQuote(),
            quote = null,
        ),
        body = null,
        colorArgb = null,
        state = ContentAnnotationState.TOMBSTONE,
        tombstoneReason = REPLACED_BY_BACKUP_REASON,
        updatedAtEpochMillis = maxOf(createdAtEpochMillis, updatedAtEpochMillis),
    )

    private fun ReadingLocator.withoutQuote(): ReadingLocator = when (this) {
        is ReadingLocator.Image -> this
        is ReadingLocator.Text -> copy(quote = null)
        is ReadingLocator.Epub -> copy(quote = null)
    }

    private fun deletionPriority(type: SyncEntityType): Int = when (type) {
        SyncEntityType.CONTENT_MANIFEST -> 0
        SyncEntityType.PUBLICATION_UNIT -> 1
        SyncEntityType.ACQUISITION -> 2
        SyncEntityType.PUBLICATION -> 3
        else -> 4
    }

    private const val REPLACED_BY_BACKUP_REASON = "replaced-by-portable-backup"
}
