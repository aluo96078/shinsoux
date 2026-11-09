package dev.shinsou.kmp.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.PortableSettingPatch
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ContentOutboxDiscarderTest {
    @Test
    fun discardsEveryPendingDraftAndBlobSyncJobAcrossPagesAndReopens() = runTest {
        withDatabase { database ->
            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val foundation = ContentFoundationRuntime(driver)
                try {
                    val body = foundation.blobStore.put("discarded body".encodeToByteArray(), "text/plain")
                    val fixture = publicationFixture(body.reference)
                    foundation.transactions.commit(
                        ContentCommitBatch(
                            commitId = "publication-with-body-job",
                            receipts = listOf(body),
                            attachments = listOf(fixture.attachment),
                            publications = listOf(ContentPublicationMutation(fixture.publication)),
                            rightsGrants = listOf(ContentRightsGrantMutation(fixture.grant)),
                            outbox = listOf(draft("publication", 0)),
                            blobSyncJobs = listOf(
                                ContentBlobSyncJobMutation(
                                    jobId = "blob-upload:${body.reference.blobId}",
                                    blob = body.reference,
                                    owner = fixture.attachment.owner,
                                    manifestId = fixture.attachment.manifestId,
                                    contentRevision = fixture.attachment.contentRevision,
                                    grantReference = fixture.grant.grantId,
                                ),
                            ),
                        ),
                    )
                    repeat(COMMITS) { commit ->
                        foundation.transactions.commit(
                            ContentCommitBatch(
                                commitId = "settings-$commit",
                                outbox = List(DRAFTS_PER_COMMIT) { index -> draft("settings-$commit", index) },
                            ),
                        )
                    }
                    val expectedDrafts = 1 + COMMITS * DRAFTS_PER_COMMIT
                    assertEquals(expectedDrafts, foundation.transactions.pendingOutboxCount())
                    assertEquals(1, foundation.transactions.pendingBlobSyncJobs().size)

                    // A page smaller than the outbox proves the loop walks every page.
                    val result = ContentOutboxDiscarder(foundation.transactions, pageSize = 7).discardPending()

                    assertEquals(ContentOutboxDiscardResult(outboxDrafts = expectedDrafts, blobSyncJobs = 1), result)
                    assertEquals(0, foundation.transactions.pendingOutboxCount())
                    assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty())
                    // The content itself is untouched.
                    assertEquals(listOf(fixture.publication), foundation.publications.all())
                    assertEquals(fixture.grant, foundation.rightsGrants.find(fixture.grant.grantId))
                    assertTrue(foundation.blobStore.contains(body.reference))
                    // Nothing left: a second pass is a no-op.
                    assertEquals(
                        ContentOutboxDiscardResult(outboxDrafts = 0, blobSyncJobs = 0),
                        ContentOutboxDiscarder(foundation.transactions).discardPending(),
                    )
                } finally {
                    foundation.close()
                    driver.close()
                }
            }
            // The discard is durable.
            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val foundation = ContentFoundationRuntime(driver)
                try {
                    assertEquals(0, foundation.transactions.pendingOutboxCount())
                    assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty())
                } finally {
                    foundation.close()
                    driver.close()
                }
            }
        }
    }

    @Test
    fun aRetriedCommitStillMatchesItsJournalAfterTheDiscard() = runTest {
        withDatabase { database ->
            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val foundation = ContentFoundationRuntime(driver)
                try {
                    // Producers keep writing drafts, so a retried commit id carries the same outbox
                    // it was journalled with and passes the commit-journal fingerprint check.
                    val batch = ContentCommitBatch(commitId = "retried", outbox = listOf(draft("retried", 0)))
                    foundation.transactions.commit(batch)
                    ContentOutboxDiscarder(foundation.transactions).discardPending()

                    foundation.transactions.commit(batch)

                    assertEquals(0, foundation.transactions.pendingOutboxCount())
                } finally {
                    foundation.close()
                    driver.close()
                }
            }
        }
    }

    private fun draft(commit: String, index: Int): SyncDraft {
        val id = "$commit:$index"
        return SyncDraft(
            draftId = id,
            event = SyncEvent(
                opId = id,
                hlc = HlcTimestamp(0, 0, "pending-import"),
                mutations = listOf(
                    PortableSettingPatch(mapOf("general.dateFormat" to SyncValue.StringValue("yyyy-MM-dd"))),
                ),
            ),
            createdAtMillis = 1_000L + index,
        )
    }

    private data class PublicationFixture(
        val publication: Publication,
        val attachment: ManifestAttachment,
        val grant: RightsGrant,
    )

    private fun publicationFixture(blob: BlobRef): PublicationFixture {
        val publicationKey = PublicationKey(PUBLICATION_ID)
        val unitKey = UnitKey(publicationKey, UNIT_ID)
        val manifest = ContentManifest(
            manifestId = MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 1,
            representations = listOf(
                ContentRepresentation.PlainText(
                    representationId = REPRESENTATION_ID,
                    resource = ResourceRef("body", blob),
                    canonicalUtf16Length = 14,
                    blocks = listOf(TextBlock("body", 0, 14)),
                ),
            ),
        )
        val grantRef = RightsGrantRef(GRANT_ID)
        val publication = Publication(
            key = publicationKey,
            title = "Discard fixture",
            acquisitions = listOf(
                Acquisition(
                    id = ACQUISITION_ID,
                    origin = AcquisitionOrigin.LocalText,
                    units = listOf(PublicationUnit(unitKey, "Unit", listOf(manifest), ordinal = 0)),
                    contentRevision = 1,
                    rightsGrantRef = grantRef,
                ),
            ),
        )
        val grant = RightsGrant(
            schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
            grantId = grantRef,
            scope = RightsScope(publicationKey, ACQUISITION_ID),
            provenance = RightsProvenance.HostPolicy("local-import"),
            protectionScheme = ProtectionScheme.None,
            validFromEpochMillis = 0,
            validUntilEpochMillis = null,
            allowedOperations = setOf(
                ContentOperation.DISPLAY,
                ContentOperation.OFFLINE_STORE,
                ContentOperation.SYNC_BLOB,
            ),
        )
        return PublicationFixture(
            publication,
            ManifestAttachment(ContentManifestOwner(publicationKey, ACQUISITION_ID, unitKey), manifest),
            grant,
        )
    }

    private suspend fun withDatabase(block: suspend (Path) -> Unit) {
        val database = Files.createTempFile("content-outbox-discard", ".sqlite")
        try {
            block(database)
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                Files.deleteIfExists(Path.of("$database$suffix"))
            }
        }
    }

    private companion object {
        const val COMMITS = 5
        const val DRAFTS_PER_COMMIT = 6
        const val PUBLICATION_ID = "11111111-1111-4111-8111-111111111111"
        const val ACQUISITION_ID = "22222222-2222-4222-8222-222222222222"
        const val UNIT_ID = "33333333-3333-4333-8333-333333333333"
        const val MANIFEST_ID = "44444444-4444-4444-8444-444444444444"
        const val REPRESENTATION_ID = "55555555-5555-4555-8555-555555555555"
        const val GRANT_ID = "66666666-6666-4666-8666-666666666666"
    }
}
