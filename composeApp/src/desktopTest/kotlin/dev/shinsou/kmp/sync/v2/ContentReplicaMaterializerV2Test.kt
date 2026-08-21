package dev.shinsou.kmp.sync.v2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobRemovalReason
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentReplicaAuthority
import dev.shinsou.kmp.content.ContentPublicationReplicaReplacement
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsAdmissionLease
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ContentTransactionException
import dev.shinsou.kmp.content.ContentTransactionFailurePoint
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.SqlDriverContentBlobStore
import dev.shinsou.kmp.content.SqlDriverContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.crypto.SodiumBlobBodyCryptoV2
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ContentReplicaMaterializerV2Test {
    @Test
    fun verifiedRemoteRightsAreAdmittedBeforeTheProductionDownloadGate() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote)
        val authority = InMemoryRightsAuthority()
        val gate = HostContentOperationGate(authority) { 2_000L }
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val materializer = fixture.materializer(
            blobStore = destination,
            transactions = transactions,
            gate = gate,
            acquireValidatedRights = { grants ->
                grants.forEach(authority::admit)
                ContentRightsAdmissionLease { grants.forEach { authority.revoke(it.grantId) } }
            },
        )

        assertEquals(
            ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB,
            materializer.drainSlice(SESSION, CAPABILITY).status,
        )
        assertEquals(1, remote.downloadedBlobIds.size)
        fixture.grants.forEach { grant ->
            assertNull(authority.resolve(grant.grantId, grant.scope, 2_000L))
        }
    }

    @Test
    fun multiBlobTxtAndEpubMaterializeOneBlobPerSliceAcrossRestartAndReplayAfterCommit() = runTest {
        withDatabase("content-replica-materializer") { database ->
            val remote = MultiBlobBodyApi()
            val fixture = buildFixture(remote)
            var driver = driver(database)
            var runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            var materializer = fixture.productionMaterializer(runtime)

            assertEquals(0, remote.downloadedBlobIds.size)
            assertTrue(runtime.publications.all().isEmpty())
            val expectedBlobCount = fixture.bodies.size
            repeat(expectedBlobCount) { slice ->
                val downloadsBefore = remote.downloadedBlobIds.size
                val result = materializer.drainSlice(SESSION, CAPABILITY)
                assertEquals(ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB, result.status)
                assertEquals(downloadsBefore + 1, remote.downloadedBlobIds.size)
                assertEquals(1, remote.downloadedBlobIds.drop(downloadsBefore).distinct().size)
                assertNull(runtime.publications.find(fixture.publication.key))
                fixture.grants.forEach { grant ->
                    assertNull(runtime.rightsAuthority.resolve(grant.grantId, grant.scope, 2_000L))
                }

                if (slice == 1) {
                    driver.close()
                    driver = driver(database)
                    runtime = ContentFoundationRuntime(
                        driver,
                        syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                    )
                    materializer = fixture.productionMaterializer(runtime)
                }
            }
            assertEquals(expectedBlobCount, remote.downloadedBlobIds.distinct().size)

            val downloadsBeforeCommit = remote.downloadedBlobIds.size
            val committed = materializer.drainSlice(SESSION, CAPABILITY)
            assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, committed.status)
            assertFalse(committed.replayed)
            assertEquals(downloadsBeforeCommit, remote.downloadedBlobIds.size)
            assertEquals(fixture.publication, runtime.publications.find(fixture.publication.key))
            assertEquals(fixture.grants.toSet(), runtime.rightsGrants.all().toSet())
            fixture.grants.forEach { grant ->
                val admitted = assertNotNull(
                    runtime.rightsAuthority.resolve(grant.grantId, grant.scope, 2_000L),
                )
                assertTrue(admitted.allows(ContentOperation.SYNC_BLOB, 2_000L))
            }
            fixture.attachments.forEach { (owner, manifest) ->
                assertEquals(manifest, runtime.blobStore.attached(owner, manifest)?.manifest)
            }
            fixture.bodies.forEach { (blob, bytes) ->
                assertContentEquals(bytes, runtime.blobStore.read(blob))
            }

            // The migration ledger is the durable replay boundary. A new process must neither
            // claim/consume fresh receipts nor download any body for an already committed graph.
            driver.close()
            driver = driver(database)
            runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            materializer = fixture.productionMaterializer(runtime)
            val downloadsBeforeReplay = remote.downloadedBlobIds.size
            val replay = materializer.drainSlice(SESSION, CAPABILITY)
            assertEquals(ContentReplicaMaterializationStatusV2.IDLE, replay.status)
            assertEquals(downloadsBeforeReplay, remote.downloadedBlobIds.size)
            assertEquals(fixture.publication, runtime.publications.find(fixture.publication.key))
            driver.close()
        }
    }

    @Test
    fun newerGraphAndTombstoneReplaceExactlyOnePublicationAndPersistLifecycleWork() = runTest {
        withDatabase("content-replica-replacement") { database ->
            val remote = MultiBlobBodyApi()
            val original = buildFixture(remote).withWorkspaceSequence(10)
            var driver = driver(database)
            var runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )

            val originalMaterializer = original.productionMaterializer(runtime)
            repeat(original.bodies.size) {
                assertEquals(
                    ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB,
                    originalMaterializer.drainSlice(SESSION, CAPABILITY).status,
                )
            }
            assertEquals(
                ContentReplicaMaterializationStatusV2.COMMITTED,
                originalMaterializer.drainSlice(SESSION, CAPABILITY).status,
            )
            val originalCursor = assertNotNull(
                runtime.transactions.publicationReplicaCursor(original.publication.key),
            )
            assertEquals(10, originalCursor.throughWorkspaceSeq)
            assertTrue(originalCursor.present)
            assertTrue(runtime.transactions.pendingBlobRemovalIntents().isEmpty())

            val retainedAcquisition = original.publication.acquisitions.first()
            val retainedGrant = original.grants.first()
            val retainedAttachment = original.attachments.first()
            val removedAttachment = original.attachments.last()
            val replacementPublication = original.publication.copy(
                title = "TXT only after replica replacement",
                acquisitions = listOf(retainedAcquisition),
            )
            val replacement = original.withGraph(
                publication = replacementPublication,
                grants = listOf(retainedGrant),
                attachments = listOf(retainedAttachment),
                throughWorkspaceSeq = 20,
            )
            val downloadsBeforeReplacement = remote.downloadedBlobIds.size

            val replaced = replacement.productionMaterializer(runtime).drainSlice(SESSION, CAPABILITY)

            assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, replaced.status)
            assertFalse(replaced.replayed)
            assertEquals(downloadsBeforeReplacement, remote.downloadedBlobIds.size)
            assertEquals(replacementPublication, runtime.publications.find(original.publication.key))
            assertEquals(listOf(retainedGrant), runtime.rightsGrants.all())
            assertNotNull(runtime.rightsAuthority.resolve(retainedGrant.grantId, retainedGrant.scope, 2_000L))
            val removedGrant = original.grants.last()
            assertNull(runtime.rightsAuthority.resolve(removedGrant.grantId, removedGrant.scope, 2_000L))
            assertEquals(
                retainedAttachment.second,
                runtime.blobStore.attached(retainedAttachment.first, retainedAttachment.second)?.manifest,
            )
            assertNull(runtime.blobStore.attached(removedAttachment.first, removedAttachment.second))

            val replacementCursor = assertNotNull(
                runtime.transactions.publicationReplicaCursor(original.publication.key),
            )
            assertEquals(20, replacementCursor.throughWorkspaceSeq)
            assertTrue(replacementCursor.present)
            assertTrue(replacementCursor.graphFingerprintSha256 != originalCursor.graphFingerprintSha256)
            val replacementIntent = runtime.transactions.pendingBlobRemovalIntents().single()
            assertEquals(ContentBlobRemovalReason.REPLICA_REPLACED, replacementIntent.reason)
            assertEquals(replacementCursor, replacementIntent.sourceCursor)
            assertEquals(
                removedAttachment.second.referencedBlobs.sortedBy(BlobRef::blobId),
                replacementIntent.removedBlobs,
            )

            driver.close()
            driver = driver(database)
            runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            assertEquals(replacementCursor,
                runtime.transactions.publicationReplicaCursor(original.publication.key))
            assertEquals(listOf(replacementIntent), runtime.transactions.pendingBlobRemovalIntents())
            assertEquals(replacementPublication, runtime.publications.find(original.publication.key))

            val tombstone = replacement.tombstoned(throughWorkspaceSeq = 30)
            val downloadsBeforeTombstone = remote.downloadedBlobIds.size
            val removed = tombstone.productionMaterializer(runtime).drainSlice(SESSION, CAPABILITY)

            assertEquals(ContentReplicaMaterializationStatusV2.REMOVED, removed.status)
            assertEquals(downloadsBeforeTombstone, remote.downloadedBlobIds.size)
            assertNull(runtime.publications.find(original.publication.key))
            assertTrue(runtime.rightsGrants.all().isEmpty())
            assertNull(runtime.rightsAuthority.resolve(retainedGrant.grantId, retainedGrant.scope, 2_000L))
            assertNull(runtime.blobStore.attached(retainedAttachment.first, retainedAttachment.second))
            val tombstoneCursor = assertNotNull(
                runtime.transactions.publicationReplicaCursor(original.publication.key),
            )
            assertEquals(30, tombstoneCursor.throughWorkspaceSeq)
            assertFalse(tombstoneCursor.present)
            val pending = runtime.transactions.pendingBlobRemovalIntents()
            assertEquals(2, pending.size)
            val tombstoneIntent = pending.single { it.reason == ContentBlobRemovalReason.REPLICA_TOMBSTONED }
            assertEquals(tombstoneCursor, tombstoneIntent.sourceCursor)
            assertEquals(
                retainedAttachment.second.referencedBlobs.sortedBy(BlobRef::blobId),
                tombstoneIntent.removedBlobs,
            )

            driver.close()
            driver = driver(database)
            runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            assertEquals(tombstoneCursor,
                runtime.transactions.publicationReplicaCursor(original.publication.key))
            assertEquals(pending, runtime.transactions.pendingBlobRemovalIntents())
            assertEquals(
                ContentReplicaMaterializationStatusV2.IDLE,
                tombstone.productionMaterializer(runtime).drainSlice(SESSION, CAPABILITY).status,
            )
            assertEquals(pending, runtime.transactions.pendingBlobRemovalIntents())
            assertEquals(
                pending.size,
                runtime.transactions.acknowledgeBlobRemovalIntents(pending.map { it.intentId }.toSet()),
            )
            driver.close()

            driver = driver(database)
            runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            assertTrue(runtime.transactions.pendingBlobRemovalIntents().isEmpty())
            assertEquals(tombstoneCursor,
                runtime.transactions.publicationReplicaCursor(original.publication.key))
            driver.close()
        }
    }

    @Test
    fun staleAndEqualSequenceConflictsFailClosedBeforeBlobMutation() = runTest {
        val remote = MultiBlobBodyApi()
        val original = buildFixture(remote).withWorkspaceSequence(10)
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val first = original.materializer(destination, transactions)
        repeat(original.bodies.size) { first.drainSlice(SESSION, CAPABILITY) }
        assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED,
            first.drainSlice(SESSION, CAPABILITY).status)
        assertEquals(0, destination.pendingReceiptCount)
        val downloadsBefore = remote.downloadedBlobIds.size

        val stale = original.withWorkspaceSequence(9).materializer(destination, transactions)
            .drainSlice(SESSION, CAPABILITY)
        assertEquals(ContentReplicaMaterializationStatusV2.BLOCKED_STALE_REPLICA, stale.status)
        assertEquals(downloadsBefore, remote.downloadedBlobIds.size)
        assertEquals(0, destination.pendingReceiptCount)

        val conflictingPublication = original.publication.copy(title = "conflicting same-sequence graph")
        val conflicting = original.withGraph(
            publication = conflictingPublication,
            grants = original.grants,
            attachments = original.attachments,
            throughWorkspaceSeq = 10,
        )
        assertFailsWith<SyncInvariantViolation> {
            conflicting.materializer(destination, transactions).drainSlice(SESSION, CAPABILITY)
        }
        assertEquals(downloadsBefore, remote.downloadedBlobIds.size)
        assertEquals(0, destination.pendingReceiptCount)
        assertEquals(original.publication, transactions.state.publications[original.publication.key])

        assertFailsWith<SyncInvariantViolation> {
            original.materializer(destination, transactions).drainSlice(
                SESSION.copy(instanceId = "10000000-0000-4000-8000-000000000099"),
                CAPABILITY,
            )
        }
        assertEquals(downloadsBefore, remote.downloadedBlobIds.size)
        assertEquals(0, destination.pendingReceiptCount)
    }

    @Test
    fun cursorAndRemovalIntentFailurePointsRollbackReplacementAndKeepReceiptsRetryable() = runTest {
        listOf(
            ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE,
            ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE,
        ).forEach { failurePoint ->
            val remote = MultiBlobBodyApi()
            val original = buildFixture(remote).withWorkspaceSequence(10)
            val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
            val transactions = InMemorySharedContentTransactionStore(
                blobStore = destination,
                outboxAdapter = SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val originalMaterializer = original.materializer(destination, transactions)
            repeat(original.bodies.size) { originalMaterializer.drainSlice(SESSION, CAPABILITY) }
            assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED,
                originalMaterializer.drainSlice(SESSION, CAPABILITY).status)
            val originalCursor = assertNotNull(
                transactions.publicationReplicaCursor(original.publication.key),
            )
            val replacement = original.withGraph(
                publication = original.publication.copy(
                    title = "rollback replacement",
                    acquisitions = listOf(original.publication.acquisitions.first()),
                ),
                grants = listOf(original.grants.first()),
                attachments = listOf(original.attachments.first()),
                throughWorkspaceSeq = 20,
            )
            val materializer = replacement.materializer(destination, transactions)
            transactions.failureInjection = failurePoint

            assertFailsWith<IllegalStateException> {
                materializer.drainSlice(SESSION, CAPABILITY)
            }

            assertEquals(originalCursor,
                transactions.publicationReplicaCursor(original.publication.key))
            assertEquals(original.publication, transactions.state.publications[original.publication.key])
            assertEquals(original.grants.toSet(), transactions.state.rightsGrants.values.toSet())
            assertTrue(transactions.pendingBlobRemovalIntents().isEmpty())
            original.attachments.forEach { (owner, manifest) ->
                assertEquals(manifest, destination.attached(owner, manifest)?.manifest)
            }
            assertEquals(1, destination.pendingReceiptCount)

            val retry = materializer.drainSlice(SESSION, CAPABILITY)
            assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, retry.status)
            assertEquals(0, destination.pendingReceiptCount)
            assertEquals(20,
                transactions.publicationReplicaCursor(original.publication.key)?.throughWorkspaceSeq)
            assertEquals(1, transactions.pendingBlobRemovalIntents().size)
        }
    }

    @Test
    fun scopedCasRaceReturnsStaleButUnrelatedCommitConflictRemainsVisible() = runTest {
        val remote = MultiBlobBodyApi()
        val tombstone = buildFixture(remote).tombstoned(throughWorkspaceSeq = 10)
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val base = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val raced = object : dev.shinsou.kmp.content.SharedContentTransactionStore<SyncDraft> by base {
            override fun commit(batch: ContentCommitBatch<SyncDraft>) =
                throw ContentTransactionException.CommitConflict(
                    requireNotNull(batch.replicaReplacement).conflictId,
                )
        }

        val result = tombstone.materializer(destination, raced).drainSlice(SESSION, CAPABILITY)

        assertEquals(ContentReplicaMaterializationStatusV2.BLOCKED_STALE_REPLICA, result.status)
        assertTrue(base.state.publications.isEmpty())
        assertNull(base.publicationReplicaCursor(tombstone.publication.key))
        assertTrue(remote.downloadedBlobIds.isEmpty())

        val unrelated = object : dev.shinsou.kmp.content.SharedContentTransactionStore<SyncDraft> by base {
            override fun commit(batch: ContentCommitBatch<SyncDraft>) =
                throw ContentTransactionException.CommitConflict("unrelated-content-commit")
        }
        val conflict = assertFailsWith<ContentTransactionException.CommitConflict> {
            tombstone.materializer(destination, unrelated).drainSlice(SESSION, CAPABILITY)
        }
        assertEquals("unrelated-content-commit", conflict.conflictingId)
        assertNull(base.publicationReplicaCursor(tombstone.publication.key))
        assertTrue(remote.downloadedBlobIds.isEmpty())
    }

    @Test
    fun sqlCursorAndIntentFailurePointsRollbackAcrossRestart() = runTest {
        listOf(
            ContentTransactionFailurePoint.AFTER_REPLICA_CURSOR_WRITE,
            ContentTransactionFailurePoint.AFTER_BLOB_REMOVAL_INTENT_WRITE,
        ).forEach { failurePoint ->
            withDatabase("content-replica-sql-${failurePoint.name.lowercase()}") { database ->
                val remote = MultiBlobBodyApi()
                val original = buildFixture(remote).withWorkspaceSequence(10)
                var driver = driver(database)
                var blobs = SqlDriverContentBlobStore(driver)
                var transactions = SqlDriverContentTransactionStore(
                    driver,
                    blobs,
                    SyncDraftContentOutboxAdapter,
                    syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                    ownsDriver = false,
                )
                val originalMaterializer = original.materializer(blobs, transactions)
                repeat(original.bodies.size) { originalMaterializer.drainSlice(SESSION, CAPABILITY) }
                assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED,
                    originalMaterializer.drainSlice(SESSION, CAPABILITY).status)
                val originalCursor = assertNotNull(
                    transactions.publicationReplicaCursor(original.publication.key),
                )
                val replacement = original.withGraph(
                    publication = original.publication.copy(
                        title = "SQL rollback replacement",
                        acquisitions = listOf(original.publication.acquisitions.first()),
                    ),
                    grants = listOf(original.grants.first()),
                    attachments = listOf(original.attachments.first()),
                    throughWorkspaceSeq = 20,
                )
                transactions.failureInjection = failurePoint

                assertFailsWith<IllegalStateException> {
                    replacement.materializer(blobs, transactions).drainSlice(SESSION, CAPABILITY)
                }
                assertEquals(originalCursor,
                    transactions.publicationReplicaCursor(original.publication.key))
                assertEquals(original.publication, transactions.state.publications[original.publication.key])
                assertTrue(transactions.pendingBlobRemovalIntents().isEmpty())
                assertEquals(1, blobs.pendingReceiptCount)
                original.attachments.forEach { (owner, manifest) ->
                    assertEquals(manifest, blobs.attached(owner, manifest)?.manifest)
                }
                driver.close()

                driver = driver(database)
                blobs = SqlDriverContentBlobStore(driver)
                transactions = SqlDriverContentTransactionStore(
                    driver,
                    blobs,
                    SyncDraftContentOutboxAdapter,
                    syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                    ownsDriver = false,
                )
                assertEquals(originalCursor,
                    transactions.publicationReplicaCursor(original.publication.key))
                assertEquals(original.publication, transactions.state.publications[original.publication.key])
                assertTrue(transactions.pendingBlobRemovalIntents().isEmpty())
                original.attachments.forEach { (owner, manifest) ->
                    assertEquals(manifest, blobs.attached(owner, manifest)?.manifest)
                }

                val retry = replacement.materializer(blobs, transactions).drainSlice(SESSION, CAPABILITY)
                assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, retry.status)
                assertEquals(20,
                    transactions.publicationReplicaCursor(original.publication.key)?.throughWorkspaceSeq)
                assertEquals(1, transactions.pendingBlobRemovalIntents().size)
                driver.close()
            }
        }
    }

    @Test
    fun portableGraphReplacementInvalidatesReplicaCursorAndJournalBeforeRematerialization() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote).withWorkspaceSequence(10)
        val memoryBlobs = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val memoryTransactions = InMemorySharedContentTransactionStore(
            blobStore = memoryBlobs,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val memoryMaterializer = fixture.materializer(memoryBlobs, memoryTransactions)
        repeat(fixture.bodies.size) { memoryMaterializer.drainSlice(SESSION, CAPABILITY) }
        assertEquals(
            ContentReplicaMaterializationStatusV2.COMMITTED,
            memoryMaterializer.drainSlice(SESSION, CAPABILITY).status,
        )
        val memoryCursor = assertNotNull(
            memoryTransactions.publicationReplicaCursor(fixture.publication.key),
        )
        val replicaCommitId = ContentPublicationReplicaReplacement(
            expected = null,
            replacement = memoryCursor,
        ).commitId
        assertTrue(replicaCommitId in memoryTransactions.state.committedIds)

        memoryTransactions.commit(
            ContentCommitBatch(
                commitId = "portable-reset-memory",
                semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
            ),
        )

        assertNull(memoryTransactions.publicationReplicaCursor(fixture.publication.key))
        assertFalse(replicaCommitId in memoryTransactions.state.committedIds)
        val memoryRematerialized = fixture.materializer(memoryBlobs, memoryTransactions)
            .drainSlice(SESSION, CAPABILITY)
        assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, memoryRematerialized.status)
        assertFalse(memoryRematerialized.replayed)
        assertEquals(memoryCursor,
            memoryTransactions.publicationReplicaCursor(fixture.publication.key))
        assertTrue(replicaCommitId in memoryTransactions.state.committedIds)

        withDatabase("content-replica-portable-reset") { database ->
            var driver = driver(database)
            var blobs = SqlDriverContentBlobStore(driver)
            var transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            val materializer = fixture.materializer(blobs, transactions)
            repeat(fixture.bodies.size) { materializer.drainSlice(SESSION, CAPABILITY) }
            assertEquals(
                ContentReplicaMaterializationStatusV2.COMMITTED,
                materializer.drainSlice(SESSION, CAPABILITY).status,
            )
            val sqlCursor = assertNotNull(
                transactions.publicationReplicaCursor(fixture.publication.key),
            )
            val sqlReplicaCommitId = ContentPublicationReplicaReplacement(
                expected = null,
                replacement = sqlCursor,
            ).commitId
            assertTrue(sqlReplicaCommitId in transactions.state.committedIds)

            transactions.commit(
                ContentCommitBatch(
                    commitId = "portable-reset-sql",
                    semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                ),
            )
            assertNull(transactions.publicationReplicaCursor(fixture.publication.key))
            assertFalse(sqlReplicaCommitId in transactions.state.committedIds)
            driver.close()

            driver = driver(database)
            blobs = SqlDriverContentBlobStore(driver)
            transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            assertNull(transactions.publicationReplicaCursor(fixture.publication.key))
            assertFalse(sqlReplicaCommitId in transactions.state.committedIds)
            val rematerialized = fixture.materializer(blobs, transactions)
                .drainSlice(SESSION, CAPABILITY)
            assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, rematerialized.status)
            assertFalse(rematerialized.replayed)
            assertEquals(sqlCursor, transactions.publicationReplicaCursor(fixture.publication.key))
            assertTrue(sqlReplicaCommitId in transactions.state.committedIds)
            driver.close()
        }
    }

    @Test
    fun workspaceAuthorityDepartureAtomicallyRetiresReplicaMetadataAndKeepsLocalGraphAcrossRestart() = runTest {
        val remote = MultiBlobBodyApi()
        val original = buildFixture(remote).withWorkspaceSequence(10)
        val retainedAcquisition = original.publication.acquisitions.first()
        val retainedGrant = original.grants.first()
        val retainedAttachment = original.attachments.first()
        val replacementPublication = original.publication.copy(
            title = "Retained locally after workspace departure",
            acquisitions = listOf(retainedAcquisition),
        )
        val replacement = original.withGraph(
            publication = replacementPublication,
            grants = listOf(retainedGrant),
            attachments = listOf(retainedAttachment),
            throughWorkspaceSeq = 20,
        )
        val authority = ContentReplicaAuthority(INSTANCE_ID, WORKSPACE_ID)

        val memoryBlobs = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val memoryTransactions = InMemorySharedContentTransactionStore(
            blobStore = memoryBlobs,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val memoryOriginalMaterializer = original.materializer(memoryBlobs, memoryTransactions)
        repeat(original.bodies.size) {
            memoryOriginalMaterializer.drainSlice(SESSION, CAPABILITY)
        }
        memoryOriginalMaterializer.drainSlice(SESSION, CAPABILITY)
        assertEquals(
            ContentReplicaMaterializationStatusV2.COMMITTED,
            replacement.materializer(memoryBlobs, memoryTransactions)
                .drainSlice(SESSION, CAPABILITY).status,
        )
        val memoryCursor = assertNotNull(
            memoryTransactions.publicationReplicaCursor(original.publication.key),
        )
        val memoryCommitIds = memoryTransactions.state.committedIds
            .filter { it.startsWith("replica:${original.publication.key.value}:") }
            .toSet()
        assertEquals(2, memoryCommitIds.size)
        assertEquals(1, memoryTransactions.pendingBlobRemovalIntents().size)

        memoryTransactions.failureInjection =
            ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE
        assertFailsWith<IllegalStateException> {
            memoryTransactions.detachReplicaAuthority(authority)
        }
        assertEquals(memoryCursor,
            memoryTransactions.publicationReplicaCursor(original.publication.key))
        assertTrue(memoryTransactions.state.committedIds.containsAll(memoryCommitIds))
        assertEquals(1, memoryTransactions.pendingBlobRemovalIntents().size)
        assertEquals(replacementPublication,
            memoryTransactions.state.publications[original.publication.key])

        val memoryDeparture = memoryTransactions.detachReplicaAuthority(authority)
        assertEquals(1, memoryDeparture.removedCursorCount)
        assertEquals(2, memoryDeparture.removedCommitCount)
        assertEquals(1, memoryDeparture.removedBlobRemovalIntentCount)
        assertNull(memoryTransactions.publicationReplicaCursor(original.publication.key))
        assertTrue(memoryTransactions.pendingBlobRemovalIntents().isEmpty())
        assertTrue(memoryTransactions.state.committedIds.intersect(memoryCommitIds).isEmpty())
        assertEquals(replacementPublication,
            memoryTransactions.state.publications[original.publication.key])
        assertEquals(
            retainedAttachment.second,
            memoryBlobs.attached(retainedAttachment.first, retainedAttachment.second)?.manifest,
        )

        withDatabase("content-replica-authority-departure") { database ->
            var driver = driver(database)
            var blobs = SqlDriverContentBlobStore(driver)
            var transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            val originalMaterializer = original.materializer(blobs, transactions)
            repeat(original.bodies.size) {
                originalMaterializer.drainSlice(SESSION, CAPABILITY)
            }
            originalMaterializer.drainSlice(SESSION, CAPABILITY)
            replacement.materializer(blobs, transactions).drainSlice(SESSION, CAPABILITY)
            val sqlCursor = assertNotNull(
                transactions.publicationReplicaCursor(original.publication.key),
            )
            val sqlCommitIds = transactions.state.committedIds
                .filter { it.startsWith("replica:${original.publication.key.value}:") }
                .toSet()
            assertEquals(2, sqlCommitIds.size)
            assertEquals(1, transactions.pendingBlobRemovalIntents().size)

            transactions.failureInjection =
                ContentTransactionFailurePoint.AFTER_REPLICA_AUTHORITY_METADATA_DELETE
            assertFailsWith<IllegalStateException> {
                transactions.detachReplicaAuthority(authority)
            }
            assertEquals(sqlCursor,
                transactions.publicationReplicaCursor(original.publication.key))
            assertTrue(transactions.state.committedIds.containsAll(sqlCommitIds))
            assertEquals(1, transactions.pendingBlobRemovalIntents().size)

            val sqlDeparture = transactions.detachReplicaAuthority(authority)
            assertEquals(1, sqlDeparture.removedCursorCount)
            assertEquals(2, sqlDeparture.removedCommitCount)
            assertEquals(1, sqlDeparture.removedBlobRemovalIntentCount)
            assertEquals(replacementPublication,
                transactions.state.publications[original.publication.key])
            driver.close()

            driver = driver(database)
            blobs = SqlDriverContentBlobStore(driver)
            transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            assertNull(transactions.publicationReplicaCursor(original.publication.key))
            assertTrue(transactions.pendingBlobRemovalIntents().isEmpty())
            assertTrue(transactions.state.committedIds.intersect(sqlCommitIds).isEmpty())
            assertEquals(replacementPublication,
                transactions.state.publications[original.publication.key])
            assertEquals(
                retainedAttachment.second,
                blobs.attached(retainedAttachment.first, retainedAttachment.second)?.manifest,
            )
            val idempotent = transactions.detachReplicaAuthority(authority)
            assertEquals(0, idempotent.removedCursorCount)
            assertEquals(0, idempotent.removedCommitCount)
            assertEquals(0, idempotent.removedBlobRemovalIntentCount)
            driver.close()
        }
    }

    @Test
    fun failedPortableGraphReplacementRestoresReplicaCursorAndJournal() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote).withWorkspaceSequence(10)
        val memoryBlobs = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val memoryTransactions = InMemorySharedContentTransactionStore(
            blobStore = memoryBlobs,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val memoryMaterializer = fixture.materializer(memoryBlobs, memoryTransactions)
        repeat(fixture.bodies.size) { memoryMaterializer.drainSlice(SESSION, CAPABILITY) }
        memoryMaterializer.drainSlice(SESSION, CAPABILITY)
        val memoryCursor = assertNotNull(
            memoryTransactions.publicationReplicaCursor(fixture.publication.key),
        )
        val memoryReplicaCommitId = ContentPublicationReplicaReplacement(
            expected = null,
            replacement = memoryCursor,
        ).commitId
        memoryTransactions.failureInjection = ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE

        assertFailsWith<IllegalStateException> {
            memoryTransactions.commit(
                ContentCommitBatch(
                    commitId = "portable-reset-memory-rollback",
                    semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                ),
            )
        }

        assertEquals(memoryCursor,
            memoryTransactions.publicationReplicaCursor(fixture.publication.key))
        assertTrue(memoryReplicaCommitId in memoryTransactions.state.committedIds)
        assertEquals(fixture.publication,
            memoryTransactions.state.publications[fixture.publication.key])
        fixture.attachments.forEach { (owner, manifest) ->
            assertEquals(manifest, memoryBlobs.attached(owner, manifest)?.manifest)
        }

        withDatabase("content-replica-portable-reset-rollback") { database ->
            var driver = driver(database)
            var blobs = SqlDriverContentBlobStore(driver)
            var transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            val materializer = fixture.materializer(blobs, transactions)
            repeat(fixture.bodies.size) { materializer.drainSlice(SESSION, CAPABILITY) }
            materializer.drainSlice(SESSION, CAPABILITY)
            val sqlCursor = assertNotNull(
                transactions.publicationReplicaCursor(fixture.publication.key),
            )
            val sqlReplicaCommitId = ContentPublicationReplicaReplacement(
                expected = null,
                replacement = sqlCursor,
            ).commitId
            transactions.failureInjection = ContentTransactionFailurePoint.AFTER_BLOB_VALIDATE

            assertFailsWith<IllegalStateException> {
                transactions.commit(
                    ContentCommitBatch(
                        commitId = "portable-reset-sql-rollback",
                        semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                    ),
                )
            }
            assertEquals(sqlCursor, transactions.publicationReplicaCursor(fixture.publication.key))
            assertTrue(sqlReplicaCommitId in transactions.state.committedIds)
            driver.close()

            driver = driver(database)
            blobs = SqlDriverContentBlobStore(driver)
            transactions = SqlDriverContentTransactionStore(
                driver,
                blobs,
                SyncDraftContentOutboxAdapter,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
                ownsDriver = false,
            )
            assertEquals(sqlCursor, transactions.publicationReplicaCursor(fixture.publication.key))
            assertTrue(sqlReplicaCommitId in transactions.state.committedIds)
            assertEquals(fixture.publication, transactions.state.publications[fixture.publication.key])
            fixture.attachments.forEach { (owner, manifest) ->
                assertEquals(manifest, blobs.attached(owner, manifest)?.manifest)
            }
            driver.close()
        }
    }

    @Test
    fun inMemoryReplicaReplayRejectsExtraAttachmentAndTombstoneResidue() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote).withWorkspaceSequence(10)
        val blobs = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = blobs,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val materializer = fixture.materializer(blobs, transactions)
        repeat(fixture.bodies.size) { materializer.drainSlice(SESSION, CAPABILITY) }
        materializer.drainSlice(SESSION, CAPABILITY)
        val presentCursor = assertNotNull(
            transactions.publicationReplicaCursor(fixture.publication.key),
        )
        val extraAttachment = ManifestAttachment(
            owner = ContentManifestOwner(
                fixture.publication.key,
                EXTRA_ACQUISITION_ID,
                UnitKey(fixture.publication.key, EXTRA_UNIT_ID),
            ),
            manifest = fixture.attachments.first().second,
        )
        val extraReceipt = assertNotNull(
            blobs.claimExistingVerified(extraAttachment.blobs.single()),
        )
        transactions.commit(
            ContentCommitBatch(
                commitId = "inject-extra-present-attachment",
                receipts = listOf(extraReceipt),
                attachments = listOf(extraAttachment),
            ),
        )
        val presentReplacement = ContentPublicationReplicaReplacement(
            expected = null,
            replacement = presentCursor,
        )

        val presentConflict = assertFailsWith<ContentTransactionException.CommitConflict> {
            transactions.commit(
                ContentCommitBatch(
                    commitId = presentReplacement.commitId,
                    attachments = fixture.attachments.map { (owner, manifest) ->
                        ManifestAttachment(owner, manifest)
                    },
                    publications = listOf(ContentPublicationMutation(fixture.publication)),
                    rightsGrants = fixture.grants.map(::ContentRightsGrantMutation),
                    replicaReplacement = presentReplacement,
                    semantics = ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA,
                ),
            )
        }
        assertEquals("replica-state:${fixture.publication.key.value}", presentConflict.conflictingId)

        val tombstone = fixture.tombstoned(throughWorkspaceSeq = 20)
        assertEquals(
            ContentReplicaMaterializationStatusV2.REMOVED,
            tombstone.materializer(blobs, transactions).drainSlice(SESSION, CAPABILITY).status,
        )
        val tombstoneCursor = assertNotNull(
            transactions.publicationReplicaCursor(fixture.publication.key),
        )
        val residueReceipt = assertNotNull(
            blobs.claimExistingVerified(extraAttachment.blobs.single()),
        )
        transactions.commit(
            ContentCommitBatch(
                commitId = "inject-extra-tombstone-attachment",
                receipts = listOf(residueReceipt),
                attachments = listOf(extraAttachment),
            ),
        )
        val tombstoneReplacement = ContentPublicationReplicaReplacement(
            expected = null,
            replacement = tombstoneCursor,
        )

        val tombstoneConflict = assertFailsWith<ContentTransactionException.CommitConflict> {
            transactions.commit(
                ContentCommitBatch(
                    commitId = tombstoneReplacement.commitId,
                    replicaReplacement = tombstoneReplacement,
                    semantics = ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA,
                ),
            )
        }
        assertEquals(
            "replica-tombstone:${fixture.publication.key.value}",
            tombstoneConflict.conflictingId,
        )
    }

    @Test
    fun conflictingDurableGrantFailsBeforeDownloadAndDoesNotOverwriteHostAuthority() = runTest {
        withDatabase("content-replica-rights-conflict") { database ->
            val remote = MultiBlobBodyApi()
            val fixture = buildFixture(remote)
            val driver = driver(database)
            val runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val remoteGrant = fixture.grants.first()
            val durableGrant = remoteGrant.copy(allowedOperations = setOf(ContentOperation.DISPLAY))
            val receipts = fixture.bodies.map { (reference, bytes) ->
                runtime.blobStore.put(reference, bytes)
            }
            runtime.transactions.commit(
                ContentCommitBatch(
                    commitId = "durable-conflicting-rights",
                    receipts = receipts,
                    attachments = fixture.attachments.map { (owner, manifest) ->
                        ManifestAttachment(owner, manifest)
                    },
                    publications = listOf(ContentPublicationMutation(fixture.publication)),
                    rightsGrants = listOf(ContentRightsGrantMutation(durableGrant)) +
                        fixture.grants.drop(1).map(::ContentRightsGrantMutation),
                ),
            )
            val before = remote.downloadedBlobIds.size

            assertFails {
                fixture.productionMaterializer(runtime).drainSlice(SESSION, CAPABILITY)
            }

            assertEquals(before, remote.downloadedBlobIds.size)
            assertEquals(durableGrant, runtime.rightsGrants.find(durableGrant.grantId))
            val admitted = assertNotNull(
                runtime.rightsAuthority.resolve(durableGrant.grantId, durableGrant.scope, 2_000L),
            )
            assertTrue(admitted.allows(ContentOperation.DISPLAY, 2_000L))
            assertFalse(admitted.allows(ContentOperation.SYNC_BLOB, 2_000L))
            driver.close()
        }
    }

    @Test
    fun failedDownloadReleasesEveryProvisionalProductionGrant() = runTest {
        withDatabase("content-replica-rights-failure") { database ->
            val remote = MultiBlobBodyApi()
            val fixture = buildFixture(remote)
            val firstBlob = fixture.bodies.keys.minBy(BlobRef::blobId)
            remote.tamperNextChunkFor = firstBlob.blobId
            val driver = driver(database)
            val runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )

            assertFails {
                fixture.productionMaterializer(runtime).drainSlice(SESSION, CAPABILITY)
            }

            assertEquals(listOf(firstBlob.blobId), remote.downloadedBlobIds)
            assertTrue(runtime.rightsGrants.all().isEmpty())
            fixture.grants.forEach { grant ->
                assertNull(runtime.rightsAuthority.resolve(grant.grantId, grant.scope, 2_000L))
            }
            driver.close()
        }
    }

    @Test
    fun tamperedDocumentAndBrokenParentEdgeBlockBeforeAnyBodyDownload() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote)
        val manifestKey = SyncEntityKey.contentManifest(fixture.attachments.first().second.manifestId)
        val manifestRecord = fixture.replica.entities.getValue(manifestKey)
        val chunkField = manifestRecord.fields.keys.first {
            it.startsWith(ContentSyncFields.Manifest.DOCUMENT_CHUNK_PREFIX)
        }
        val tamperedDocument = fixture.replica.replaceField(
            manifestKey,
            chunkField,
            SyncValue.StringValue("AAAA"),
        )
        assertBlockedWithoutDownload(fixture, remote, tamperedDocument)

        val acquisition = fixture.publication.acquisitions.first()
        val acquisitionKey = SyncEntityKey.acquisition(acquisition.id)
        val brokenEdge = fixture.replica.replaceField(
            acquisitionKey,
            ContentSyncFields.Acquisition.PUBLICATION_KEY,
            SyncValue.EntityKeyValue(
                SyncEntityKey.publication("10000000-0000-4000-8000-000000000011"),
            ),
        )
        assertBlockedWithoutDownload(fixture, remote, brokenEdge)
    }

    @Test
    fun incompleteEarlierPublicationDoesNotStarveLaterCompleteGraph() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote)
        val incompleteKey = SyncEntityKey.publication("10000000-0000-4000-8000-000000000010")
        val incomplete = SyncEntityRecord(
            key = incompleteKey,
            presence = LwwRegister(true, HlcTimestamp(5_000L, 0, DEVICE_ID)),
        )
        val replica = fixture.replica.copy(
            entities = fixture.replica.entities + (incompleteKey to incomplete),
        )
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )

        val result = fixture.copy(replica = replica).materializer(destination, transactions)
            .drainSlice(SESSION, CAPABILITY)

        assertEquals(ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB, result.status)
        assertEquals(PUBLICATION_ID, result.publicationId)
        assertEquals(1, remote.downloadedBlobIds.size)
    }

    @Test
    fun transactionFailureRollsBackGraphAndOriginalReceiptsRemainRetryable() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote)
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val materializer = fixture.materializer(destination, transactions)

        repeat(fixture.bodies.size) {
            assertEquals(
                ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB,
                materializer.drainSlice(SESSION, CAPABILITY).status,
            )
        }
        assertEquals(fixture.bodies.size, destination.pendingReceiptCount)
        transactions.failureInjection = ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE
        assertFails { materializer.drainSlice(SESSION, CAPABILITY) }

        assertTrue(transactions.state.publications.isEmpty())
        assertTrue(transactions.state.rightsGrants.isEmpty())
        assertTrue(transactions.state.migrations.isEmpty())
        fixture.attachments.forEach { (owner, manifest) ->
            assertNull(destination.attached(owner, manifest))
        }
        assertEquals(fixture.bodies.size, destination.pendingReceiptCount)

        val retried = materializer.drainSlice(SESSION, CAPABILITY)
        assertEquals(ContentReplicaMaterializationStatusV2.COMMITTED, retried.status)
        assertEquals(fixture.publication, transactions.state.publications[fixture.publication.key])
        assertEquals(fixture.grants.toSet(), transactions.state.rightsGrants.values.toSet())
        assertEquals(0, destination.pendingReceiptCount)
    }

    @Test
    fun tamperedCiphertextAbortsStageWithoutPublishingOrCommitting() = runTest {
        val remote = MultiBlobBodyApi()
        val fixture = buildFixture(remote)
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val firstBlob = fixture.bodies.keys.minBy(BlobRef::blobId)
        remote.tamperNextChunkFor = firstBlob.blobId
        val materializer = fixture.materializer(destination, transactions)

        assertFails { materializer.drainSlice(SESSION, CAPABILITY) }
        assertEquals(listOf(firstBlob.blobId), remote.downloadedBlobIds)
        assertEquals(0, destination.count)
        assertEquals(0, destination.pendingReceiptCount)
        assertTrue(transactions.state.publications.isEmpty())
        assertTrue(transactions.state.migrations.isEmpty())
    }

    private suspend fun assertBlockedWithoutDownload(
        fixture: Fixture,
        remote: MultiBlobBodyApi,
        replica: SyncState,
    ) {
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = destination,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val before = remote.downloadedBlobIds.size
        val result = fixture.copy(replica = replica).materializer(destination, transactions)
            .drainSlice(SESSION, CAPABILITY)
        assertEquals(ContentReplicaMaterializationStatusV2.BLOCKED_INCOMPLETE_GRAPH, result.status)
        assertNotNull(result.reason)
        assertEquals(before, remote.downloadedBlobIds.size)
        assertEquals(0, destination.count)
        assertTrue(transactions.state.publications.isEmpty())
    }

    private suspend fun buildFixture(remote: MultiBlobBodyApi): Fixture {
        val secretStore = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { index -> (index + 1).toByte() }.asList()),
            )
        }
        val crypto = SodiumBlobBodyCryptoV2(secretStore)
        val source = InMemoryContentBlobStore(maximumBlobSizeBytes = 4 * 1024 * 1024)
        val textBytes = "Shinsou X synchronized text\n".encodeToByteArray()
        val archiveBytes = "PK\u0003\u0004epub-archive".encodeToByteArray()
        val opfBytes = "<package version=\"3.0\"/>".encodeToByteArray()
        val xhtmlBytes = "<html><body>chapter</body></html>".encodeToByteArray()
        val cssBytes = "body { color: #222; }".encodeToByteArray()
        val text = source.put(textBytes, "text/plain").reference
        val archive = source.put(archiveBytes, "application/epub+zip").reference
        val opf = source.put(opfBytes, "application/oebps-package+xml").reference
        val xhtml = source.put(xhtmlBytes, "application/xhtml+xml").reference
        val css = source.put(cssBytes, "text/css").reference

        val publicationKey = PublicationKey(PUBLICATION_ID)
        val textUnitKey = UnitKey(publicationKey, TEXT_UNIT_ID)
        val epubUnitKey = UnitKey(publicationKey, EPUB_UNIT_ID)
        val textManifest = ContentManifest(
            manifestId = TEXT_MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 1,
            representations = listOf(
                ContentRepresentation.PlainText(
                    representationId = TEXT_REPRESENTATION_ID,
                    resource = ResourceRef("text-body", text),
                    canonicalUtf16Length = textBytes.decodeToString().length,
                    sourceEncoding = "UTF-8",
                    blocks = listOf(TextBlock("text-block", 0, textBytes.decodeToString().length)),
                ),
            ),
            declaredSizeBytes = text.byteSize,
        )
        val epubManifest = ContentManifest(
            manifestId = EPUB_MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 3,
            representations = listOf(
                ContentRepresentation.EpubSpine(
                    representationId = EPUB_REPRESENTATION_ID,
                    packageGraph = EpubPackage(
                        archive = archive,
                        packageDocumentId = "package-opf",
                        resources = listOf(
                            EpubResource(
                                id = "package-opf",
                                href = "OEBPS/package.opf",
                                resource = ResourceRef("package-opf", opf),
                            ),
                            EpubResource(
                                id = "chapter-xhtml",
                                href = "OEBPS/chapter.xhtml",
                                resource = ResourceRef("chapter-xhtml", xhtml),
                            ),
                            EpubResource(
                                id = "theme-css",
                                href = "OEBPS/theme.css",
                                resource = ResourceRef("theme-css", css),
                            ),
                        ),
                    ),
                    documents = listOf(
                        EpubSpineDocument(
                            id = "spine-chapter",
                            href = "OEBPS/chapter.xhtml",
                            resourceId = "chapter-xhtml",
                        ),
                    ),
                ),
            ),
            declaredSizeBytes = archive.byteSize + opf.byteSize + xhtml.byteSize + css.byteSize,
        )
        val textGrant = grant(TEXT_GRANT_ID, publicationKey, TEXT_ACQUISITION_ID)
        val epubGrant = grant(EPUB_GRANT_ID, publicationKey, EPUB_ACQUISITION_ID)
        val textAcquisition = Acquisition(
            id = TEXT_ACQUISITION_ID,
            origin = AcquisitionOrigin.LocalText,
            units = listOf(PublicationUnit(textUnitKey, "Text", listOf(textManifest), ordinal = 0)),
            contentRevision = 1,
            rightsGrantRef = textGrant.grantId,
        )
        val epubAcquisition = Acquisition(
            id = EPUB_ACQUISITION_ID,
            origin = AcquisitionOrigin.LocalEpub,
            units = listOf(PublicationUnit(epubUnitKey, "EPUB", listOf(epubManifest), ordinal = 1)),
            contentRevision = 3,
            rightsGrantRef = epubGrant.grantId,
        )
        val publication = Publication(
            key = publicationKey,
            title = "TXT and EPUB",
            acquisitions = listOf(textAcquisition, epubAcquisition),
            description = "M6 destination graph",
            authors = listOf("Shinsou"),
        )
        val uploader = EncryptedBlobUploaderV2(
            blobStore = source,
            bodyApi = remote,
            crypto = crypto,
            journal = InMemoryBlobTransferJournalV2(),
            operationGate = AllowingGate,
            nowEpochMillis = { 1_000L },
        )
        val grantByBlob = buildMap {
            textManifest.referencedBlobs.forEach { put(it, textGrant) }
            epubManifest.referencedBlobs.forEach { put(it, epubGrant) }
        }
        val uploads = grantByBlob.keys.sortedBy(BlobRef::blobId).map { blob ->
            val grant = grantByBlob.getValue(blob)
            uploader.upload(
                session = SESSION,
                capability = CAPABILITY,
                blob = blob,
                access = ContentAccessRequest(grant.grantId, grant.scope),
                chunkSizeBytes = 64 * 1024,
            )
        }
        var replica = SyncState()
        ContentPublicationSyncDraftFactory.build(
            publication = publication,
            rightsGrants = listOf(textGrant, epubGrant),
            operationNamespace = "m6-destination-e2e",
            createdAtMillis = 1_000L,
        ).drafts.forEach { draft -> replica = SyncReducer.reduce(replica, draft.event) }
        uploads.forEachIndexed { index, upload ->
            replica = SyncReducer.reduce(
                replica,
                SyncEvent(
                    opId = "m6-blob-${index.toString().padStart(3, '0')}",
                    hlc = HlcTimestamp(2_000L + index, 0, DEVICE_ID),
                    mutations = listOf(
                        BlobReferenceCommitV2(upload.blob, upload.remoteManifest, upload.dekEnvelope),
                    ),
                ),
            )
        }
        return Fixture(
            publication = publication,
            grants = listOf(textGrant, epubGrant),
            attachments = listOf(
                ContentManifestOwner(publicationKey, TEXT_ACQUISITION_ID, textUnitKey) to textManifest,
                ContentManifestOwner(publicationKey, EPUB_ACQUISITION_ID, epubUnitKey) to epubManifest,
            ),
            bodies = linkedMapOf(
                text to textBytes,
                archive to archiveBytes,
                opf to opfBytes,
                xhtml to xhtmlBytes,
                css to cssBytes,
            ),
            replica = replica,
            remote = remote,
            crypto = crypto,
        )
    }

    private fun grant(id: String, publication: PublicationKey, acquisitionId: String): RightsGrant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = RightsGrantRef(id),
        scope = RightsScope(publication, acquisitionId),
        provenance = RightsProvenance.HostPolicy("m6-local-content"),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = ContentOperation.entries.toSet(),
    )

    private data class Fixture(
        val publication: Publication,
        val grants: List<RightsGrant>,
        val attachments: List<Pair<ContentManifestOwner, ContentManifest>>,
        val bodies: Map<BlobRef, ByteArray>,
        val replica: SyncState,
        val remote: MultiBlobBodyApi,
        val crypto: SodiumBlobBodyCryptoV2,
    ) {
        fun withWorkspaceSequence(throughWorkspaceSeq: Long): Fixture = copy(
            replica = replica.copy(throughWorkspaceSeq = throughWorkspaceSeq),
        )

        fun withGraph(
            publication: Publication,
            grants: List<RightsGrant>,
            attachments: List<Pair<ContentManifestOwner, ContentManifest>>,
            throughWorkspaceSeq: Long,
        ): Fixture {
            var materialized = SyncState()
            ContentPublicationSyncDraftFactory.build(
                publication = publication,
                rightsGrants = grants,
                operationNamespace = "m6-destination-replacement",
                createdAtMillis = 3_000L,
            ).drafts.forEach { draft ->
                materialized = SyncReducer.reduce(materialized, draft.event)
            }
            materialized = materialized.copy(
                throughWorkspaceSeq = throughWorkspaceSeq,
                blobReferences = replica.blobReferences,
            )
            return copy(
                publication = publication,
                grants = grants,
                attachments = attachments,
                replica = materialized,
            )
        }

        fun tombstoned(throughWorkspaceSeq: Long): Fixture {
            val key = SyncEntityKey.publication(publication.key.value)
            val record = replica.entities.getValue(key)
            return copy(
                replica = replica.copy(
                    throughWorkspaceSeq = throughWorkspaceSeq,
                    entities = replica.entities + (key to record.copy(
                        presence = LwwRegister(
                            value = false,
                            hlc = HlcTimestamp(9_000L, 0, DEVICE_ID),
                        ),
                    )),
                ),
            )
        }

        fun materializer(
            blobStore: dev.shinsou.kmp.content.ContentBlobStore,
            transactions: dev.shinsou.kmp.content.SharedContentTransactionStore<SyncDraft>,
            gate: ContentOperationGate = AllowingGate,
            acquireValidatedRights: (List<RightsGrant>) -> ContentRightsAdmissionLease = {
                ContentRightsAdmissionLease {}
            },
        ): ContentReplicaMaterializerV2 = ContentReplicaMaterializerV2(
            localStore = InMemoryLocalSyncStore(LocalSyncStoreState(replica = replica)),
            blobStore = blobStore,
            contentStore = transactions,
            downloader = EncryptedBlobDownloaderV2(blobStore, remote, crypto, gate),
            acquireValidatedRights = acquireValidatedRights,
        )

        fun productionMaterializer(runtime: ContentFoundationRuntime): ContentReplicaMaterializerV2 =
            materializer(
                blobStore = runtime.blobStore,
                transactions = runtime.transactions,
                gate = HostContentOperationGate(runtime.rightsAuthority) { 2_000L },
                acquireValidatedRights = runtime::acquireProvisionalRightsAdmission,
            )
    }

    private class MultiBlobBodyApi : CloudflareBlobBodyApiV2 {
        private data class StoredUpload(
            val request: BlobUploadReservationRequestV2,
            val sessionId: String,
            val receiptId: String,
            val chunks: MutableMap<Int, EncryptedBlobChunkV2> = linkedMapOf(),
            var committed: CommittedEncryptedBlobManifestV2? = null,
        )

        private val uploads = linkedMapOf<String, StoredUpload>()
        private var nextIdentity = 1
        val downloadedBlobIds = mutableListOf<String>()
        var tamperNextChunkFor: String? = null

        override suspend fun reserveUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobUploadReservationRequestV2,
        ): BlobUploadSessionV2 {
            val stored = uploads[request.blobId] ?: StoredUpload(
                request = request,
                sessionId = nextUuid("9"),
                receiptId = nextUuid("a"),
            ).also { uploads[request.blobId] = it }
            require(stored.request == request)
            return stored.session()
        }

        override suspend fun uploadStatus(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
        ): BlobUploadSessionV2 = bySession(uploadSessionId).session()

        override suspend fun uploadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            chunk: EncryptedBlobChunkV2,
        ): BlobChunkReceiptV2 {
            val stored = bySession(uploadSessionId)
            val previous = stored.chunks[chunk.plan.index]
            require(previous == null || previous.plan == chunk.plan)
            stored.chunks[chunk.plan.index] = chunk
            return chunk.plan.receipt()
        }

        override suspend fun commitUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            request: BlobManifestCommitRequestV2,
        ): BlobBodyCommitReceiptV2 {
            val stored = bySession(uploadSessionId)
            stored.committed?.let { committed ->
                return BlobBodyCommitReceiptV2(stored.receiptId, stored.sessionId, committed.remote)
            }
            require(stored.chunks.keys.sorted() == stored.request.chunks.indices.toList())
            val remote = RemoteBlobBodyManifestRefV2(
                manifestId = stored.request.manifestId,
                blobId = stored.request.blobId,
                manifestCiphertextSha256Base64Url =
                    request.encryptedPrivateManifest.ciphertextSha256Base64Url,
                manifestCiphertextByteSize = request.encryptedPrivateManifest.ciphertextByteSize.toLong(),
                bodyCiphertextByteSize = stored.request.expectedBodyCiphertextBytes,
                chunkCount = stored.request.chunks.size,
                chunkSizeBytes = stored.request.chunkSizeBytes,
                committedAtEpochMillis = 2_000L,
                commitReceiptId = stored.receiptId,
            )
            stored.committed = CommittedEncryptedBlobManifestV2(
                remote = remote,
                chunks = stored.request.chunks,
                encryptedPrivateManifest = request.encryptedPrivateManifest,
                dekEnvelopes = listOf(stored.request.initialDekEnvelope),
            )
            return BlobBodyCommitReceiptV2(stored.receiptId, stored.sessionId, remote)
        }

        override suspend fun downloadManifest(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
        ): CommittedEncryptedBlobManifestV2 {
            downloadedBlobIds += blobId
            return requireNotNull(uploads[blobId]?.committed)
        }

        override suspend fun downloadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            chunk: EncryptedBlobChunkPlanV2,
        ): BinaryData {
            val bytes = requireNotNull(uploads[blobId]?.chunks?.get(chunk.index)).ciphertext.copyBytes()
            if (tamperNextChunkFor == blobId) {
                tamperNextChunkFor = null
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
            }
            return BinaryData.copyOf(bytes)
        }

        override suspend fun rewrapEnvelope(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobEnvelopeRewrapRequestV2,
        ): BlobDekEnvelopeV2 = request.envelope

        override suspend fun createTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobReferenceTombstoneRequestV2,
        ): BlobTombstoneCreationResultV2 = BlobTombstoneCreationResultV2(
            handle = BlobTombstoneHandleV2(
                instanceId = session.instanceId,
                workspaceId = session.workspaceId,
                tombstoneId = request.tombstoneId,
                blobId = request.blobId,
                manifestId = request.manifestId,
                referenceThroughWorkspaceSeq = request.throughWorkspaceSeq,
                requestedCreatedAtEpochMillis = request.createdAtEpochMillis,
                executeAfterEpochMillis = request.createdAtEpochMillis + 1_000,
            ),
            disposition = BlobTombstoneDispositionV2.ACTIVE,
        )

        override suspend fun reviveBlobReference(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobReferenceRevivalRequestV2,
        ): BlobReferenceRevivalResultV2 = BlobReferenceRevivalResultV2(
            tombstoneId = request.tombstoneId,
            blobId = request.blobId,
            manifestId = request.manifestId,
            disposition = BlobTombstoneDispositionV2.CANCELLED,
            cancelledAtEpochMillis = 2_000,
        )

        override suspend fun acknowledgeTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobTombstoneAckRequestV2,
        ) = Unit

        override suspend fun garbageCollect(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobGcRequestV2,
        ): BlobGcReceiptV2 = BlobGcReceiptV2(nextUuid("b"), request.blobId, 0, 0, 0)

        private fun bySession(sessionId: String): StoredUpload =
            requireNotNull(uploads.values.singleOrNull { it.sessionId == sessionId })

        private fun StoredUpload.session(): BlobUploadSessionV2 = BlobUploadSessionV2(
            sessionId = sessionId,
            blobId = request.blobId,
            manifestId = request.manifestId,
            keyEpoch = request.keyEpoch,
            expiresAtEpochMillis = Long.MAX_VALUE,
            reservedBytes = request.totalReservedBytes,
            receivedChunks = chunks.values.sortedBy { it.plan.index }.map { it.plan.receipt() },
        )

        private fun EncryptedBlobChunkPlanV2.receipt(): BlobChunkReceiptV2 = BlobChunkReceiptV2(
            index,
            ciphertextByteSize,
            ciphertextSha256Base64Url,
        )

        private fun nextUuid(prefix: String): String {
            val suffix = nextIdentity++.toString(16).padStart(12, '0')
            return "${prefix}0000000-0000-4000-8000-$suffix"
        }
    }

    private object AllowingGate : ContentOperationGate {
        override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision =
            RightsDecision.ALLOW

        override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) = Unit

        override fun <T> execute(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: () -> T,
        ): T = block()

        override suspend fun <T> executeSuspending(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: suspend () -> T,
        ): T = block()
    }

    private fun SyncState.replaceField(
        key: SyncEntityKey,
        field: String,
        value: SyncValue,
    ): SyncState {
        val record = entities.getValue(key)
        val register = record.fields.getValue(field)
        return copy(
            entities = entities + (key to record.copy(
                fields = record.fields + (field to register.copy(value = value)),
            )),
        )
    }

    private fun driver(database: Path): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:$database")

    private suspend fun withDatabase(prefix: String, block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory.resolve("content.sqlite"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        private const val INSTANCE_ID = "10000000-0000-4000-8000-000000000001"
        private const val USER_ID = "10000000-0000-4000-8000-000000000002"
        private const val WORKSPACE_ID = "10000000-0000-4000-8000-000000000003"
        private const val DEVICE_ID = "10000000-0000-4000-8000-000000000004"
        private const val PUBLICATION_ID = "20000000-0000-4000-8000-000000000001"
        private const val TEXT_ACQUISITION_ID = "20000000-0000-4000-8000-000000000002"
        private const val EPUB_ACQUISITION_ID = "20000000-0000-4000-8000-000000000003"
        private const val TEXT_UNIT_ID = "20000000-0000-4000-8000-000000000004"
        private const val EPUB_UNIT_ID = "20000000-0000-4000-8000-000000000005"
        private const val TEXT_MANIFEST_ID = "20000000-0000-4000-8000-000000000006"
        private const val EPUB_MANIFEST_ID = "20000000-0000-4000-8000-000000000007"
        private const val TEXT_REPRESENTATION_ID = "20000000-0000-4000-8000-000000000008"
        private const val EPUB_REPRESENTATION_ID = "20000000-0000-4000-8000-000000000009"
        private const val TEXT_GRANT_ID = "20000000-0000-4000-8000-00000000000a"
        private const val EPUB_GRANT_ID = "20000000-0000-4000-8000-00000000000b"
        private const val EXTRA_ACQUISITION_ID = "20000000-0000-4000-8000-00000000000c"
        private const val EXTRA_UNIT_ID = "20000000-0000-4000-8000-00000000000d"

        private val SESSION = SyncSession(
            endpoint = "https://sync.example.test",
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            workspaceId = WORKSPACE_ID,
            deviceId = DEVICE_ID,
            deviceDisplayName = "M6 desktop test",
            platform = "desktop",
            status = SyncSessionStatus.READY,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            activeKeyEpoch = 1,
        )

        private val CAPABILITY = WorkspaceCapability(
            token = SecretMaterial("m6-capability".encodeToByteArray().asList()),
            binding = CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, 1, Long.MAX_VALUE),
        )
    }
}
