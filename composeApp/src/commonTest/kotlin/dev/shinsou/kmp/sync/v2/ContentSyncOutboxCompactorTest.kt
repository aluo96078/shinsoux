package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentSyncOutboxCompactorTest {
    private val publication = SyncEntityKey.publication("11111111-1111-4111-8111-111111111111")
    private val acquisition = SyncEntityKey.acquisition("22222222-2222-4222-8222-222222222222")
    private val otherPublication = SyncEntityKey.publication("33333333-3333-4333-8333-333333333333")

    private fun unit(index: Int) =
        SyncEntityKey.publicationUnit("44444444-4444-4444-8444-${index.toString().padStart(12, '0')}")

    private fun manifest(index: Int) =
        SyncEntityKey.contentManifest("55555555-5555-4555-8555-${index.toString().padStart(12, '0')}")

    @Test
    fun repeatedTitleResendsCollapseToTheSameReducedState() {
        val store = store()
        // Twenty chapter downloads, each re-sending the whole title as it then was.
        repeat(20) { download ->
            store.commit(ContentCommitBatch(commitId = "commit-$download", outbox = titleDrafts(download)))
        }
        val before = store.pendingOutbox()

        val result = ContentSyncOutboxCompactor(store, pageSize = 7).compact()

        val after = store.pendingOutbox()
        assertEquals(before.size, result.inspected)
        assertEquals(before.size, result.removed)
        assertEquals(1, result.publicationsCompacted)
        assertTrue(after.size < before.size / 10, "expected a large reduction, got ${after.size} of ${before.size}")
        assertEquals(reducedEntities(before), reducedEntities(after))
        // Compacted drafts keep the newest commit's id prefix, so a title purge still finds them.
        val newestPrefix = before.maxWith(compareBy({ it.createdAtMillis }, { it.draftId })).draftId.substringBeforeLast(':')
        assertTrue(after.all { it.draftId.startsWith("$newestPrefix:c") })
        assertTrue(after.none { it.draftId in before.map(SyncDraft::draftId) })
    }

    @Test
    fun aTitleMentionedByAnotherKindOfMutationIsLeftAlone() {
        val store = store()
        repeat(5) { download ->
            store.commit(ContentCommitBatch(commitId = "commit-$download", outbox = titleDrafts(download)))
        }
        val removal = SyncDraft(
            draftId = "presence-op",
            event = SyncEvent(
                opId = "presence-op",
                hlc = PLACEHOLDER_HLC,
                mutations = listOf(EntityPresenceSet(publication, present = false)),
            ),
            createdAtMillis = 2_500,
        )
        store.commit(ContentCommitBatch(commitId = "presence", outbox = listOf(removal)))
        val before = store.pendingOutbox().map(SyncDraft::draftId).toSet()

        val result = ContentSyncOutboxCompactor(store).compact()

        assertEquals(0, result.publicationsCompacted)
        assertEquals(before, store.pendingOutbox().map(SyncDraft::draftId).toSet())
    }

    @Test
    fun separateTitlesCompactIndependentlyAndSmallOutboxesAreSkipped() {
        val store = store()
        repeat(4) { download ->
            store.commit(ContentCommitBatch(commitId = "a-$download", outbox = titleDrafts(download)))
            store.commit(
                ContentCommitBatch(
                    commitId = "b-$download",
                    outbox = listOf(publicationOnly(otherPublication, "b-$download", "Other $download", 10L * download + 5)),
                ),
            )
        }
        assertNull(ContentSyncOutboxCompactor(store).compactIfLarge(threshold = 10_000))
        val before = store.pendingOutbox()

        val result = ContentSyncOutboxCompactor(store).compact()

        assertEquals(2, result.publicationsCompacted)
        assertEquals(reducedEntities(before), reducedEntities(store.pendingOutbox()))
    }

    private fun titleDrafts(download: Int): List<SyncDraft> {
        val prefix = "content-v2:${download.toString().padStart(4, '0')}:"
        val mutations = buildList<SyncMutation> {
            add(
                PublicationPatchV2(
                    publication,
                    mapOf(
                        "title" to SyncValue.StringValue("Title rev $download"),
                        "documentSha256" to SyncValue.StringValue("sha-$download"),
                    ),
                ),
            )
            add(AcquisitionPatchV2(acquisition, publication, mapOf("rev" to SyncValue.StringValue("a$download"))))
            // Units appear as chapters are discovered; every download re-sends all of them.
            repeat(10 + download) { index ->
                add(
                    PublicationUnitPatchV2(
                        unit(index),
                        acquisition,
                        mapOf("title" to SyncValue.StringValue("Chapter $index"), "seen" to SyncValue.StringValue("d$download")),
                    ),
                )
            }
            repeat(download + 1) { index ->
                add(
                    ContentManifestPatchV2(
                        manifest(index),
                        unit(index),
                        mapOf("body" to SyncValue.StringValue("x".repeat(400) + download)),
                    ),
                )
            }
        }
        return CanonicalSyncDraftPacker.pack(
            mutations = mutations,
            createdAtMillis = 100L * download,
            draftId = { index -> "$prefix${index.toString().padStart(6, '0')}" },
            maxPlaintextBytes = 2_048,
        )
    }

    private fun publicationOnly(key: SyncEntityKey, id: String, title: String, createdAt: Long): SyncDraft {
        val opId = "content-v2:$id:000000"
        return SyncDraft(
            draftId = opId,
            event = SyncEvent(
                opId = opId,
                hlc = PLACEHOLDER_HLC,
                mutations = listOf(PublicationPatchV2(key, mapOf("title" to SyncValue.StringValue(title)))),
            ),
            createdAtMillis = createdAt,
        )
    }

    /** Field values and presence after draining [drafts] in drain order; clocks are ignored. */
    private fun reducedEntities(drafts: List<SyncDraft>): Map<SyncEntityKey, Pair<Map<String, SyncValue>, Boolean>> {
        var state = SyncState()
        drafts.sortedWith(compareBy({ it.createdAtMillis }, { it.draftId })).forEachIndexed { index, draft ->
            state = SyncReducer.reduce(state, draft.event.copy(hlc = HlcTimestamp(1_000L + index, 0, "device")))
        }
        return state.entities.mapValues { (_, record) ->
            record.fields.mapValues { it.value.value } to record.isPresent
        }
    }

    private fun store() = InMemorySharedContentTransactionStore(
        blobStore = InMemoryContentBlobStore(),
        outboxAdapter = SyncDraftContentOutboxAdapter,
        syncModeProvider = { ContentSyncMode.V2_ACTIVE },
    )

    private companion object {
        val PLACEHOLDER_HLC = HlcTimestamp(0, 0, "pending-import")
    }
}
