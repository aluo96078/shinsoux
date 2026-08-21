package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.annotation.InMemoryContentAnnotationStore
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentAnnotationSyncReconciliationBridgeTest {
    @Test
    fun localWinnerIsDurablyStagedAndRestartReplayDoesNotDuplicateOutbox() = runTest {
        val annotation = annotation(ANNOTATION_A, body = "local", updatedAt = 10)
        val annotations = InMemoryContentAnnotationStore().also { it.put(annotation) }
        val content = contentStore()
        val localSync = InMemoryLocalSyncStore()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val first = bridge(annotations, content, localSync, dispatcher).reconcileSlice()

        assertEquals(1, first.localAnnotationsStaged)
        assertTrue(first.outboxDraftsStaged > 0)
        val pendingAfterFirstRun = content.pendingOutbox()
        assertEquals(first.outboxDraftsStaged, pendingAfterFirstRun.size)
        assertTrue(pendingAfterFirstRun.all { draft ->
            CanonicalSyncDraftPacker.encodedSize(draft.event) <=
                CanonicalSyncDraftPacker.MAX_EVENT_PLAINTEXT_BYTES
        })

        // A new bridge instance models a process restart before the content outbox was drained.
        val replay = bridge(annotations, content, localSync, dispatcher).reconcileSlice()
        assertEquals(1, replay.localAnnotationsStaged)
        assertEquals(1, replay.replayedCommits)
        assertEquals(pendingAfterFirstRun.map(SyncDraft::draftId), content.pendingOutbox().map(SyncDraft::draftId))

        ContentSyncOutboxDrainBridge(content, localSync, "device-a") { 20 }.drain()
        val converged = bridge(annotations, content, localSync, dispatcher).reconcileSlice()
        assertEquals(1, converged.converged)
        assertEquals(annotation, localSync.readState().replica.contentAnnotations
            .getValue(annotation.annotationId).annotation?.value)
        assertTrue(content.pendingOutbox().isEmpty())
    }

    @Test
    fun completeRemoteWinnerMaterializesIncludingTombstone() = runTest {
        val active = annotation(ANNOTATION_A, body = "remote", updatedAt = 20)
        val tombstone = annotation(ANNOTATION_B, body = null, updatedAt = 30).copy(
            state = ContentAnnotationState.TOMBSTONE,
            tombstoneReason = "deleted remotely",
        )
        val remote = SyncState(
            contentAnnotations = mapOf(
                active.annotationId to remoteRecord(active, HlcTimestamp(40, 0, "remote")),
                tombstone.annotationId to remoteRecord(tombstone, HlcTimestamp(41, 0, "remote")),
            ),
        )
        val annotations = InMemoryContentAnnotationStore()
        val result = bridge(
            annotations,
            contentStore(),
            InMemoryLocalSyncStore(LocalSyncStoreState(replica = remote)),
            StandardTestDispatcher(testScheduler),
        ).reconcileSlice()

        assertEquals(2, result.remoteAnnotationsMaterialized)
        assertEquals(active, annotations.find(active.annotationId))
        assertEquals(tombstone, annotations.find(tombstone.annotationId))
        assertTrue(annotations.list().none { it.annotationId == tombstone.annotationId })
        assertEquals(tombstone, annotations.list(includeTombstones = true)
            .single { it.annotationId == tombstone.annotationId })
    }

    @Test
    fun canonicalTieBreakConvergesAndTombstoneCannotBeResurrected() = runTest {
        val first = annotation(ANNOTATION_A, body = "alpha", updatedAt = 50)
        val second = annotation(ANNOTATION_A, body = "omega", updatedAt = 50, createdAt = 2)
        val firstStore = InMemoryContentAnnotationStore().also { it.put(first) }
        val secondStore = InMemoryContentAnnotationStore().also { it.put(second) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstResult = bridge(
            firstStore,
            contentStore(),
            syncStoreWith(second, HlcTimestamp(60, 0, "device-b")),
            dispatcher,
        ).reconcileSlice()
        val secondResult = bridge(
            secondStore,
            contentStore(),
            syncStoreWith(first, HlcTimestamp(60, 0, "device-a")),
            dispatcher,
        ).reconcileSlice()

        assertEquals(firstStore.find(ANNOTATION_A), secondStore.find(ANNOTATION_A))
        assertEquals(1, firstResult.localAnnotationsStaged + secondResult.localAnnotationsStaged)
        assertEquals(1, firstResult.remoteAnnotationsMaterialized + secondResult.remoteAnnotationsMaterialized)

        val active = annotation(ANNOTATION_B, body = "newer active", updatedAt = 500)
        val tombstone = annotation(ANNOTATION_B, body = null, updatedAt = 10).copy(
            state = ContentAnnotationState.TOMBSTONE,
            tombstoneReason = "deleted",
        )
        val activeStore = InMemoryContentAnnotationStore().also { it.put(active) }
        val promotedContent = contentStore()
        val activeReplica = syncStoreWith(tombstone, HlcTimestamp(1, 0, "remote"))
        val tombstoneResult = bridge(
            activeStore,
            promotedContent,
            activeReplica,
            dispatcher,
        ).reconcileSlice()

        assertEquals(1, tombstoneResult.remoteAnnotationsMaterialized)
        assertEquals(ContentAnnotationState.TOMBSTONE, activeStore.find(ANNOTATION_B)?.state)
        // The local CAS timestamp promotion is itself published on the next slice, so another
        // device cannot later reintroduce the higher-timestamp active value.
        val publishPromotion = bridge(
            activeStore,
            promotedContent,
            activeReplica,
            dispatcher,
        ).reconcileSlice()
        assertEquals(1, publishPromotion.localAnnotationsStaged)
        ContentSyncOutboxDrainBridge(
            promotedContent,
            activeReplica,
            "promotion-device",
        ) { 1_000 }.drain()
        val promoted = assertNotNull(activeStore.find(ANNOTATION_B))
        assertEquals(
            promoted,
            activeReplica.readState().replica.contentAnnotations.getValue(ANNOTATION_B).annotation?.value,
        )
        assertEquals(1, bridge(
            activeStore,
            promotedContent,
            activeReplica,
            dispatcher,
        ).reconcileSlice().converged)

        val localTombstoneStore = InMemoryContentAnnotationStore().also { it.put(tombstone) }
        val noResurrection = bridge(
            localTombstoneStore,
            contentStore(),
            syncStoreWith(active, HlcTimestamp(999, 0, "remote")),
            dispatcher,
        ).reconcileSlice()
        assertEquals(1, noResurrection.localAnnotationsStaged)
        assertEquals(ContentAnnotationState.TOMBSTONE, localTombstoneStore.find(ANNOTATION_B)?.state)
    }

    @Test
    fun incompleteRemoteRecordFailsClosedInsteadOfMaterializingOrRevivingLocalValue() = runTest {
        val local = annotation(ANNOTATION_A, body = "must remain local", updatedAt = 10)
        val annotations = InMemoryContentAnnotationStore().also { it.put(local) }
        val incomplete = SyncedAnnotationRecord(
            annotationId = ANNOTATION_A,
            annotation = null,
            presence = LwwRegister(false, HlcTimestamp(20, 0, "remote")),
        )
        val sync = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = SyncState(contentAnnotations = mapOf(ANNOTATION_A to incomplete)),
            ),
        )
        val content = contentStore()

        val result = bridge(
            annotations,
            content,
            sync,
            StandardTestDispatcher(testScheduler),
        ).reconcileSlice()

        assertEquals(1, result.invalidRemoteRecords)
        assertEquals(0, result.remoteAnnotationsMaterialized)
        assertEquals(0, result.localAnnotationsStaged)
        assertEquals(local, annotations.find(ANNOTATION_A))
        assertTrue(content.pendingOutbox().isEmpty())
    }

    @Test
    fun orderedCursorKeepsEverySliceBounded() = runTest {
        val annotations = InMemoryContentAnnotationStore().also { store ->
            store.put(annotation(ANNOTATION_A, body = "a", updatedAt = 1))
            store.put(annotation(ANNOTATION_B, body = "b", updatedAt = 2))
        }
        val content = contentStore()
        val bridge = bridge(
            annotations,
            content,
            InMemoryLocalSyncStore(),
            StandardTestDispatcher(testScheduler),
        )

        val first = bridge.reconcileSlice(maxAnnotations = 1)
        val cursor = assertNotNull(first.nextAfterAnnotationId)
        val second = bridge.reconcileSlice(maxAnnotations = 1, afterAnnotationId = cursor)

        assertEquals(1, first.examined)
        assertEquals(1, second.examined)
        assertEquals(null, second.nextAfterAnnotationId)
        assertEquals(2, first.localAnnotationsStaged + second.localAnnotationsStaged)
    }

    private fun bridge(
        annotations: InMemoryContentAnnotationStore,
        content: InMemorySharedContentTransactionStore<SyncDraft>,
        sync: LocalSyncStore,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): ContentAnnotationSyncReconciliationBridge = ContentAnnotationSyncReconciliationBridge(
        annotationStore = annotations,
        contentStore = content,
        localStore = sync,
        workDispatcher = dispatcher,
    )

    private fun contentStore(): InMemorySharedContentTransactionStore<SyncDraft> =
        InMemorySharedContentTransactionStore(
            blobStore = InMemoryContentBlobStore(),
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )

    private fun syncStoreWith(
        annotation: ContentAnnotation,
        hlc: HlcTimestamp,
    ): InMemoryLocalSyncStore = InMemoryLocalSyncStore(
        LocalSyncStoreState(
            replica = SyncState(
                contentAnnotations = mapOf(annotation.annotationId to remoteRecord(annotation, hlc)),
            ),
        ),
    )

    private fun remoteRecord(
        annotation: ContentAnnotation,
        hlc: HlcTimestamp,
    ): SyncedAnnotationRecord = SyncedAnnotationRecord(
        annotationId = annotation.annotationId,
        annotation = LwwRegister(annotation, hlc),
        presence = LwwRegister(annotation.state == ContentAnnotationState.ACTIVE, hlc),
    )

    private fun annotation(
        id: String,
        body: String?,
        updatedAt: Long,
        createdAt: Long = 1,
    ): ContentAnnotation {
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
            annotationId = id,
            kind = if (body == null) ContentAnnotationKind.BOOKMARK else ContentAnnotationKind.NOTE,
            range = ReadingRange(locator, locator),
            body = body,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private companion object {
        const val PUBLICATION_ID = "50000000-0000-4000-8000-000000000001"
        const val ACQUISITION_ID = "50000000-0000-4000-8000-000000000002"
        const val UNIT_ID = "50000000-0000-4000-8000-000000000003"
        const val ANNOTATION_A = "50000000-0000-4000-8000-000000000004"
        const val ANNOTATION_B = "50000000-0000-4000-8000-000000000005"
    }
}
