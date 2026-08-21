package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentAnnotationSyncDraftFactoryTest {
    @Test
    fun maximumUtf16AnnotationIsChunkedCommittedAndEventSizeSafe() {
        val annotation = annotation("\uD83D\uDE00".repeat(8_192))
        val plan = ContentAnnotationSyncDraftFactory.build(
            annotations = listOf(annotation),
            operationNamespace = "maximum-annotation",
            createdAtMillis = 10,
        )

        assertTrue(plan.drafts.size > 1)
        assertTrue(plan.drafts.all(::isProtocolSizeSafe))
        var state = SyncState()
        plan.drafts.forEach { draft -> state = SyncReducer.reduce(state, draft.event) }

        assertEquals(annotation, state.contentAnnotations.getValue(ANNOTATION_ID).annotation?.value)
        assertTrue(state.contentAnnotations.getValue(ANNOTATION_ID).isPresent)
    }

    @Test
    fun missingAnnotationChunkNeverPublishesCommittedAnnotation() {
        val annotation = annotation("\uD83D\uDE00".repeat(8_192))
        val plan = ContentAnnotationSyncDraftFactory.build(
            annotations = listOf(annotation),
            operationNamespace = "missing-annotation-chunk",
            createdAtMillis = 0,
        )
        var removed = false
        var state = SyncState()
        plan.drafts.forEachIndexed { index, draft ->
            val retained = draft.event.mutations.filterNot { mutation ->
                val patch = mutation as? ContentAnnotationPatchV2 ?: return@filterNot false
                val isChunk = patch.fields.keys.any {
                    it.startsWith(ContentSyncFields.Annotation.DOCUMENT_CHUNK_PREFIX)
                }
                if (isChunk && !removed) {
                    removed = true
                    true
                } else {
                    false
                }
            }
            if (retained.isNotEmpty()) {
                state = SyncReducer.reduce(
                    state,
                    draft.event.copy(opId = "missing-$index", mutations = retained),
                )
            }
        }

        assertTrue(removed)
        assertTrue(state.contentAnnotations.isEmpty())
        assertTrue(SyncEntityKey.annotation(ANNOTATION_ID) in state.entities)
    }

    private fun annotation(body: String): ContentAnnotation {
        val publication = PublicationKey(PUBLICATION_ID)
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = ACQUISITION_ID,
            unitId = UnitKey(publication, UNIT_ID),
            contentRevision = 1,
        )
        val locator = ReadingLocator.Image(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            scope = scope,
            pageResourceId = "page-1",
            pageIndexHint = 0,
        )
        return ContentAnnotation(
            schemaVersion = ContentAnnotation.CURRENT_SCHEMA_VERSION,
            annotationId = ANNOTATION_ID,
            kind = ContentAnnotationKind.NOTE,
            range = ReadingRange(locator, locator),
            body = body,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 10,
        )
    }

    private fun isProtocolSizeSafe(draft: SyncDraft): Boolean {
        val plaintext = CanonicalSyncDraftPacker.encodedSize(draft.event)
        return plaintext <= CanonicalSyncDraftPacker.MAX_EVENT_PLAINTEXT_BYTES &&
            plaintext + CanonicalSyncDraftPacker.AEAD_TAG_BYTES <=
            CanonicalSyncDraftPacker.MAX_EVENT_CIPHERTEXT_BYTES
    }

    private companion object {
        const val PUBLICATION_ID = "40000000-0000-4000-8000-000000000001"
        const val ACQUISITION_ID = "40000000-0000-4000-8000-000000000002"
        const val UNIT_ID = "40000000-0000-4000-8000-000000000003"
        const val ANNOTATION_ID = "40000000-0000-4000-8000-000000000004"
    }
}
