package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.plugin.Sha256

public data class ContentAnnotationSyncDraftPlan(
    val drafts: List<SyncDraft>,
)

/** Digest-bound, event-size-safe schema-v2 annotation staging. */
public object ContentAnnotationSyncDraftFactory {
    public fun build(
        annotations: List<ContentAnnotation>,
        operationNamespace: String,
        createdAtMillis: Long,
    ): ContentAnnotationSyncDraftPlan {
        require(operationNamespace.isNotBlank() && operationNamespace.length <= 4_096) {
            "Annotation sync operation namespace is invalid"
        }
        require(createdAtMillis >= 0) { "Annotation sync draft time cannot be negative" }
        annotations.forEach(ContentAnnotation::validate)
        require(annotations.map(ContentAnnotation::annotationId).distinct().size == annotations.size) {
            "Content annotation ids must be unique"
        }
        if (annotations.isEmpty()) return ContentAnnotationSyncDraftPlan(emptyList())

        val mutations = annotations.sortedBy(ContentAnnotation::annotationId).flatMap(::mutations)
        val namespaceHash = Sha256.hex(operationNamespace.encodeToByteArray())
        val drafts = CanonicalSyncDraftPacker.pack(
            mutations = mutations,
            createdAtMillis = createdAtMillis,
            draftId = { index ->
                "content-annotation-v2:$namespaceHash:${index.toString().padStart(6, '0')}"
            },
        )
        return ContentAnnotationSyncDraftPlan(drafts)
    }

    private fun mutations(annotation: ContentAnnotation): List<SyncMutation> {
        val key = SyncEntityKey.annotation(annotation.annotationId)
        val document = ContentSyncDocumentCodec.encodeAnnotation(annotation)
        return buildList {
            add(ContentAnnotationPatchV2(
                key = key,
                fields = ContentSyncDocumentCodec.headerFields(
                    document,
                    ContentSyncFields.Annotation.DOCUMENT_SHA256,
                    ContentSyncFields.Annotation.DOCUMENT_CHUNK_COUNT,
                ),
            ))
            ContentSyncDocumentCodec.chunkFields(
                document,
                ContentSyncFields.Annotation.DOCUMENT_CHUNK_PREFIX,
            ).forEach { fields -> add(ContentAnnotationPatchV2(key, fields)) }
            add(ContentAnnotationPatchV2(
                key = key,
                fields = mapOf(
                    ContentSyncFields.Annotation.COMMITTED_SHA256 to
                        SyncValue.StringValue(document.sha256),
                ),
            ))
        }
    }
}
