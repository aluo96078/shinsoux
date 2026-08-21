package dev.shinsou.kmp.content

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class SqlDraft(val draftId: String, val requiresV2: Boolean = false)

private object SqlDraftAdapter : ContentOutboxPersistenceAdapter<SqlDraft> {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override fun validate(draft: SqlDraft) = require(draft.draftId.isNotBlank())
    override fun id(draft: SqlDraft): String = draft.draftId
    override fun fingerprint(draft: SqlDraft): ByteArray = json.encodeToString(draft).encodeToByteArray()
    override fun isRepresentableByCurrentV1(draft: SqlDraft): Boolean = !draft.requiresV2
    override fun encode(draft: SqlDraft): String = json.encodeToString(draft)
    override fun decode(payload: String): SqlDraft = json.decodeFromString(payload)
}

class SqlDriverContentTransactionStoreTest {
    private val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
    private val acquisition = "22222222-2222-4222-8222-222222222222"

    @Test
    fun metadataMigrationOutboxAndAttachmentRowsSurviveReopen() {
        withDatabase("content-reopen") { database ->
            val blobStore = InMemoryContentBlobStore(configuredStoreInstanceId = "sql-test")
            val first = blobStore.put("hello".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(1), textManifest(first.reference))
            val migration = migration("a")
            val batch = ContentCommitBatch(
                commitId = "${migration.commitId}",
                receipts = listOf(first),
                attachments = listOf(attachment),
                metadata = listOf(ContentMetadataMutation("title", "Hello")),
                aliases = listOf(ContentAliasMutation("legacy:hello", "publication:hello")),
                outbox = listOf(SqlDraft("draft-1")),
                migrations = listOf(migration),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                assertFalse(store.commit(batch).replayed)
                store.close()
            }

            // Reopening the SQL driver reads all ledgers from disk.  A fresh republish creates a
            // different one-use receipt object, but the durable migration ledger still permits a
            // semantic replay.
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                assertEquals(mapOf("title" to "Hello"), reopened.state.metadata)
                assertEquals(mapOf("legacy:hello" to "publication:hello"), reopened.state.aliases)
                assertEquals(mapOf(migration.migrationKey to migration), reopened.state.migrations)
                assertEquals(listOf(SqlDraft("draft-1")), reopened.state.outbox)
                assertEquals(listOf(attachment), reopened.state.attachments)
                assertEquals(setOf(batch.commitId), reopened.state.committedIds)
                val beforeReplay = reopened.state
                val republished = blobStore.put(first.reference, "hello".encodeToByteArray())
                assertTrue(reopened.commit(batch.copy(receipts = listOf(republished))).replayed)
                assertEquals(beforeReplay, reopened.state)
                assertEquals(0, blobStore.pendingReceiptCount)
                reopened.close()
            }
        }
    }

    @Test
    fun sameMigrationDigestReplaysAfterReopenAndChangedResultConflicts() {
        withDatabase("content-migration-replay") { database ->
            val migration = migration("b")
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = migration.commitId,
                metadata = listOf(ContentMetadataMutation("publication/title", "Book")),
                migrations = listOf(migration),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(),
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { it.commit(batch).also { result -> assertFalse(result.replayed) }; it.close() }

            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(),
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                assertTrue(reopened.commit(batch).replayed)
                val changed = migration.copy(resultFingerprintSha256 = "c".repeat(64))
                assertFailsWith<ContentTransactionException.CommitConflict> {
                    reopened.commit(batch.copy(migrations = listOf(changed)))
                }
                reopened.close()
            }
        }
    }

    @Test
    fun migrationReplayAfterReopenRetiresFreshReceiptWithoutRewritingRows() {
        withDatabase("content-semantic-replay") { database ->
            val blobStore = InMemoryContentBlobStore(configuredStoreInstanceId = "semantic-sql")
            val first = blobStore.put("hello".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(6), textManifest(first.reference))
            val migration = migration("e")
            val batch = ContentCommitBatch(
                commitId = migration.commitId,
                receipts = listOf(first),
                attachments = listOf(attachment),
                metadata = listOf(ContentMetadataMutation("publication/title", "Book")),
                aliases = listOf(ContentAliasMutation("legacy:book", "publication:book")),
                outbox = listOf(SqlDraft("semantic-draft")),
                migrations = listOf(migration),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                assertFalse(store.commit(batch).replayed)
                store.close()
            }

            // Publishing the same immutable bytes creates a new local capability.  Reopening the
            // SQL store must replay from the durable migration row without requiring the old
            // receipt object or inserting duplicate attachment/outbox rows.
            val republished = blobStore.put(first.reference, "hello".encodeToByteArray())
            assertEquals(1, blobStore.pendingReceiptCount)
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                val beforeReplay = reopened.state
                assertEquals(
                    ContentMigrationLookupStatus.REPLAY,
                    reopened.lookupMigrationLedger(
                        migration.namespace,
                        migration.sourceDigestSha256,
                        migration.resultFingerprintSha256,
                    ).status,
                )
                assertTrue(reopened.commit(batch.copy(receipts = listOf(republished))).replayed)
                assertEquals(beforeReplay, reopened.state)
                assertEquals(0, blobStore.pendingReceiptCount)
                assertEquals(attachment, reopened.state.attachments.single())

                val changed = migration.copy(resultFingerprintSha256 = "c".repeat(64))
                assertEquals(
                    ContentMigrationLookupStatus.CONFLICT,
                    reopened.lookupMigrationLedger(
                        changed.namespace,
                        changed.sourceDigestSha256,
                        changed.resultFingerprintSha256,
                    ).status,
                )
                assertFailsWith<ContentTransactionException.CommitConflict> {
                    reopened.commit(batch.copy(receipts = emptyList(), migrations = listOf(changed)))
                }
                reopened.close()
            }
        }
    }

    @Test
    fun migrationReplayDoesNotConsumeReceiptForUnrelatedNewAttachment() {
        withDatabase("content-semantic-negative") { database ->
            val blobStore = InMemoryContentBlobStore(configuredStoreInstanceId = "semantic-sql-negative")
            val first = blobStore.put("hello".encodeToByteArray(), "text/plain")
            val durableAttachment = ManifestAttachment(owner(8), textManifest(first.reference))
            val migration = migration("f")
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = migration.commitId,
                receipts = listOf(first),
                attachments = listOf(durableAttachment),
                migrations = listOf(migration),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                assertFalse(store.commit(batch).replayed)
                store.close()
            }

            val republishedFirst = blobStore.put(first.reference, "hello".encodeToByteArray())
            val newBlob = blobStore.put("unrelated".encodeToByteArray(), "text/plain")
            val newAttachment = ManifestAttachment(
                owner(9),
                textManifest(newBlob.reference, manifestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                val beforeReplay = reopened.state
                assertTrue(
                    reopened.commit(
                        batch.copy(
                            receipts = listOf(republishedFirst, newBlob),
                            attachments = listOf(durableAttachment, newAttachment),
                        ),
                    ).replayed,
                )
                assertEquals(beforeReplay, reopened.state)
                assertEquals(listOf(durableAttachment), reopened.state.attachments)
                assertEquals(null, blobStore.attached(owner(9), newAttachment.manifest))
                assertEquals(
                    listOf(newBlob.reference),
                    blobStore.pendingReceipts().map(BlobPublishReceipt::reference),
                )
                reopened.close()
            }
        }
    }

    @Test
    fun replayHydratesFreshBlobParticipantAndProtectsSqlAttachmentFromRecoveryGc() {
        withDatabase("content-fresh-participant") { database ->
            val originalStore = InMemoryContentBlobStore(configuredStoreInstanceId = "fresh-original")
            val first = originalStore.put("hello".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(10), textManifest(first.reference))
            val migration = migration("9")
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = migration.commitId,
                receipts = listOf(first),
                attachments = listOf(attachment),
                migrations = listOf(migration),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                originalStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                assertFalse(store.commit(batch).replayed)
                store.close()
            }

            // A reopened process has a new in-memory participant.  Republish the durable bytes
            // into it, then replay the migration so SQL's attachment row is hydrated before any
            // crash recovery/GC decision is made.
            val freshStore = InMemoryContentBlobStore(configuredStoreInstanceId = "fresh-participant")
            val republished = freshStore.put(first.reference, "hello".encodeToByteArray())
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                freshStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                assertTrue(reopened.commit(batch.copy(receipts = listOf(republished))).replayed)
                assertEquals(0, freshStore.pendingReceiptCount)

                freshStore.simulateProcessCrashAndRecover()
                val boundary = RecoveryBoundary(
                    safetyCutoffGeneration = freshStore.currentGeneration,
                    nowEpochMillis = 1_000L,
                    minimumAgeMillis = 0,
                )
                val plan = freshStore.planRecovery(boundary)
                assertTrue(plan.candidateReferences.isEmpty())
                assertEquals(
                    BlobRecoveryProtection.ATTACHED,
                    plan.protectedBlobs[first.reference.blobId],
                )
                assertEquals(0, freshStore.sweepRecovery(plan))
                assertTrue(freshStore.verify(first.reference))
                reopened.close()
            }
        }
    }

    @Test
    fun normalCommitHydratesFreshBlobParticipantAndProtectsSqlAttachmentFromRecoveryGc() {
        withDatabase("content-fresh-normal") { database ->
            val originalStore = InMemoryContentBlobStore(configuredStoreInstanceId = "normal-original")
            val first = originalStore.put("hello".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(11), textManifest(first.reference))
            val initialBatch = ContentCommitBatch<SqlDraft>(
                commitId = "normal-original-commit",
                receipts = listOf(first),
                attachments = listOf(attachment),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                originalStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                assertFalse(store.commit(initialBatch).replayed)
                store.close()
            }

            val freshStore = InMemoryContentBlobStore(configuredStoreInstanceId = "normal-fresh")
            val republished = freshStore.put(first.reference, "hello".encodeToByteArray())
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                freshStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                // A different commit id takes the ordinary existing-SQL-row path rather than
                // the migration replay fast path.
                assertFalse(
                    reopened.commit(
                        ContentCommitBatch(
                            commitId = "normal-fresh-retry",
                            receipts = listOf(republished),
                            attachments = listOf(attachment),
                        ),
                    ).replayed,
                )
                assertEquals(0, freshStore.pendingReceiptCount)
                assertEquals(attachment.asBlobAttachment(), freshStore.attached(owner(11), attachment.manifest))

                freshStore.simulateProcessCrashAndRecover()
                val boundary = RecoveryBoundary(
                    safetyCutoffGeneration = freshStore.currentGeneration,
                    nowEpochMillis = 1_000L,
                    minimumAgeMillis = 0,
                )
                val plan = freshStore.planRecovery(boundary)
                assertTrue(plan.candidateReferences.isEmpty())
                assertEquals(
                    BlobRecoveryProtection.ATTACHED,
                    plan.protectedBlobs[first.reference.blobId],
                )
                assertEquals(0, freshStore.sweepRecovery(plan))
                assertTrue(freshStore.verify(first.reference))
                reopened.close()
            }
        }
    }

    @Test
    fun equalMigrationAndOutboxRowsCanBeReusedByDifferentCommits() {
        withDatabase("content-shared-ledgers") { database ->
            val migration = migration("d")
            val first = ContentCommitBatch(
                commitId = migration.commitId,
                migrations = listOf(migration),
                outbox = listOf(SqlDraft("shared-draft")),
            )
            // Migration ledger keys intentionally use a deterministic source-digest commit id;
            // a later independent transaction can still reuse the durable outbox row.
            val second = ContentCommitBatch<SqlDraft>(
                commitId = "second-ledger-commit",
                outbox = listOf(SqlDraft("shared-draft")),
            )
            val store = SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(),
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            assertFalse(store.commit(first).replayed)
            assertFalse(store.commit(second).replayed)
            assertEquals(mapOf(migration.migrationKey to migration), store.state.migrations)
            assertEquals(listOf(SqlDraft("shared-draft")), store.state.outbox)
            assertEquals(setOf(first.commitId, second.commitId), store.state.committedIds)
            store.close()
        }
    }

    @Test
    fun multiResourceAttachmentReopenComparesBlobRefsByIdentityNotSortOrder() {
        withDatabase("content-multi-resource") { database ->
            val ids = ArrayDeque(
                listOf(
                    "ffffffff-ffff-4fff-8fff-fffffffffff1",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            val blobStore = InMemoryContentBlobStore(
                blobIdFactory = { ids.removeFirst() },
                configuredStoreInstanceId = "multi-resource",
            )
            val first = blobStore.put("first".encodeToByteArray(), "image/png")
            val second = blobStore.put("second".encodeToByteArray(), "image/png")
            val attachment = ManifestAttachment(owner(4), imageManifest(first.reference, second.reference))
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = "multi-resource",
                receipts = listOf(first, second),
                attachments = listOf(attachment),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store -> store.commit(batch); store.close() }
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
            ).also { reopened ->
                assertEquals(listOf(attachment), reopened.state.attachments)
                reopened.close()
            }
        }
    }

    @Test
    fun sqlAttachmentAdmissionSnapshotsMutableManifestBeforeWriteAndRead() {
        withDatabase("content-immutable-manifest") { database ->
            val blobStore = InMemoryContentBlobStore(configuredStoreInstanceId = "immutable-sql")
            val first = blobStore.put("first".encodeToByteArray(), "image/png")
            val later = blobStore.put("later".encodeToByteArray(), "image/png")
            val pages = arrayListOf(ImagePage(ResourceRef("page-a", first.reference)))
            val mutableManifest = ContentManifest(
                manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
                contentRevision = 0,
                representations = arrayListOf(
                    ContentRepresentation.ImageSequence(
                        representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                        pages = pages,
                    ),
                ),
            )
            val attachment = ManifestAttachment(owner(7), mutableManifest)
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = "sql-immutable-manifest",
                receipts = listOf(first),
                attachments = listOf(attachment),
            )
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { store ->
                store.commit(batch)
                pages.clear()
                pages += ImagePage(ResourceRef("page-b", later.reference))
                assertEquals(listOf(first.reference), batch.attachments.single().blobs)
                assertEquals(listOf(first.reference), store.state.attachments.single().blobs)
                store.close()
            }

            // The SQL row contains the admitted snapshot, not the caller's later mutation.
            SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(configuredStoreInstanceId = "immutable-sql-reopened"),
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).also { reopened ->
                val stored = reopened.state.attachments.single()
                assertEquals(listOf(first.reference), stored.blobs)
                assertFails {
                    @Suppress("UNCHECKED_CAST")
                    val mutablePages = stored.manifest.imageSequences.single().pages as MutableList<ImagePage>
                    mutablePages.clear()
                }
                reopened.close()
            }
        }
    }

    @Test
    fun corruptAttachmentRefLedgerFailsClosedBeforeASecondCommit() {
        withDatabase("content-corrupt-refs") { database ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val blobStore = InMemoryContentBlobStore()
            val receipt = blobStore.put("body".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(5), textManifest(receipt.reference))
            val store = SqlDriverContentTransactionStore(
                driver,
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            store.commit(
                ContentCommitBatch(
                    commitId = "corrupt-ref-base",
                    receipts = listOf(receipt),
                    attachments = listOf(attachment),
                ),
            )
            driver.execute(
                null,
                "DELETE FROM content_transaction_attachment_refs WHERE attachment_key = ?",
                1,
            ) { bindString(0, attachment.attachmentKey) }.value
            assertFailsWith<IllegalStateException> {
                store.commit(
                    ContentCommitBatch<SqlDraft>(
                        commitId = "corrupt-ref-follow-up",
                        attachments = listOf(attachment),
                    ),
                )
            }
            store.close()
        }
    }

    @Test
    fun concurrentCommitIsRejectedAndCanBeRetriedAfterTheFirstCompletes() {
        withDatabase("content-concurrent") { database ->
            val enteredEncoder = CountDownLatch(1)
            val releaseEncoder = CountDownLatch(1)
            val slowAdapter = object : ContentOutboxPersistenceAdapter<SqlDraft> {
                override fun validate(draft: SqlDraft) = SqlDraftAdapter.validate(draft)
                override fun id(draft: SqlDraft): String = SqlDraftAdapter.id(draft)
                override fun fingerprint(draft: SqlDraft): ByteArray = SqlDraftAdapter.fingerprint(draft)
                override fun isRepresentableByCurrentV1(draft: SqlDraft): Boolean =
                    SqlDraftAdapter.isRepresentableByCurrentV1(draft)

                override fun encode(draft: SqlDraft): String {
                    enteredEncoder.countDown()
                    check(releaseEncoder.await(5, TimeUnit.SECONDS)) { "test encoder timed out" }
                    return SqlDraftAdapter.encode(draft)
                }

                override fun decode(payload: String): SqlDraft = SqlDraftAdapter.decode(payload)
            }
            val store = SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(),
                slowAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val firstResult = AtomicReference<ContentCommitResult?>()
            val firstError = AtomicReference<Throwable?>()
            val first = thread(start = true) {
                try {
                    firstResult.set(store.commit(ContentCommitBatch("concurrent-first", outbox = listOf(SqlDraft("first")))))
                } catch (error: Throwable) {
                    firstError.set(error)
                }
            }
            assertTrue(enteredEncoder.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> {
                store.commit(ContentCommitBatch("concurrent-second", metadata = listOf(ContentMetadataMutation("key", "value"))))
            }
            releaseEncoder.countDown()
            first.join()
            assertEquals(null, firstError.get())
            assertFalse(requireNotNull(firstResult.get()).replayed)
            // The rejected second operation must not have written its metadata.
            assertTrue(store.state.metadata.isEmpty())
            store.close()
        }
    }

    @Test
    fun sqlFailureRollsBackRowsBlobAttachmentAndReceiptConsumption() {
        withDatabase("content-rollback") { database ->
            val blobStore = InMemoryContentBlobStore(configuredStoreInstanceId = "rollback")
            val receipt = blobStore.put("body".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(2), textManifest(receipt.reference))
            val store = SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = "rollback",
                receipts = listOf(receipt),
                attachments = listOf(attachment),
                metadata = listOf(ContentMetadataMutation("key", "value")),
                aliases = listOf(ContentAliasMutation("alias", "target")),
                outbox = listOf(SqlDraft("draft")),
            )
            store.failureInjection = ContentTransactionFailurePoint.AFTER_METADATA_WRITE
            assertFailsWith<IllegalStateException> { store.commit(batch) }
            assertEquals(1, blobStore.pendingReceiptCount)
            assertEquals(null, blobStore.attached(owner(2), attachment.manifest))
            assertEquals(emptyMap(), store.state.metadata)
            assertEquals(emptyMap(), store.state.aliases)
            assertTrue(store.state.outbox.isEmpty())
            assertTrue(store.state.attachments.isEmpty())
            assertTrue(store.state.committedIds.isEmpty())

            assertFalse(store.commit(batch).replayed)
            assertEquals(0, blobStore.pendingReceiptCount)
            store.close()
        }
    }

    @Test
    fun v1RejectsAndDefersContentDraftsBeforeAnySqlOrBlobMutation() {
        withDatabase("content-v1") { database ->
            val blobStore = InMemoryContentBlobStore()
            val receipt = blobStore.put("body".encodeToByteArray(), "text/plain")
            val batch = ContentCommitBatch<SqlDraft>(
                commitId = "v1-reject",
                receipts = listOf(receipt),
                attachments = listOf(ManifestAttachment(owner(3), textManifest(receipt.reference))),
                outbox = listOf(SqlDraft("v2", requiresV2 = true)),
            )
            val store = SqlDriverContentTransactionStore(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                blobStore,
                SqlDraftAdapter,
            )
            assertFailsWith<ContentTransactionException.V1SyncCannotRepresent> { store.commit(batch) }
            assertEquals(1, blobStore.pendingReceiptCount)
            assertTrue(store.state.committedIds.isEmpty())

            val deferred = store.commit(
                batch.copy(
                    commitId = "v1-defer",
                    unrepresentableDraftPolicy = UnrepresentableDraftPolicy.DEFER,
                ),
            )
            assertTrue(deferred.deferred)
            assertEquals(1, blobStore.pendingReceiptCount)
            assertTrue(store.state.committedIds.isEmpty())
            store.close()
        }
    }

    @Test
    fun defaultV1AuthorityRejectsMetadataAndAliasesAndCanDeferThem() {
        withDatabase("content-v1-metadata") { database ->
            val store = SqlDriverContentTransactionStore<SqlDraft>(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
                InMemoryContentBlobStore(),
                SqlDraftAdapter,
            )
            assertFailsWith<ContentTransactionException.V1SyncCannotRepresent> {
                store.commit(
                    ContentCommitBatch(
                        commitId = "v1-metadata-only",
                        metadata = listOf(ContentMetadataMutation("title", "Book")),
                    ),
                )
            }
            assertFailsWith<ContentTransactionException.V1SyncCannotRepresent> {
                store.commit(
                    ContentCommitBatch(
                        commitId = "v1-alias-only",
                        aliases = listOf(ContentAliasMutation("legacy:book", "publication:book")),
                    ),
                )
            }
            val deferred = store.commit(
                ContentCommitBatch(
                    commitId = "v1-metadata-deferred",
                    metadata = listOf(ContentMetadataMutation("title", "Book")),
                    unrepresentableDraftPolicy = UnrepresentableDraftPolicy.DEFER,
                ),
            )
            assertTrue(deferred.deferred)
            assertTrue(store.state.metadata.isEmpty())
            assertTrue(store.state.aliases.isEmpty())
            assertTrue(store.state.committedIds.isEmpty())
            store.close()
        }
    }

    private fun migration(suffix: String) = ContentMigrationLedgerMutation(
        namespace = "test.backup.v1",
        sourceDigestSha256 = suffix.padEnd(64, 'a').take(64),
        resultFingerprintSha256 = "b".repeat(64),
    )

    private fun owner(number: Int) = ContentManifestOwner(
        publicationKey = publication,
        acquisitionId = acquisition,
        unitKey = UnitKey(publication, "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"),
    )

    private fun textManifest(
        blob: BlobRef,
        manifestId: String = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    ) = ContentManifest(
        manifestId = manifestId,
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = 0,
        representations = listOf(
            ContentRepresentation.PlainText(
                representationId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                resource = ResourceRef("text-body", blob),
                canonicalUtf16Length = 5,
                blocks = listOf(TextBlock("body", 0, 5)),
            ),
        ),
    )

    private fun imageManifest(first: BlobRef, second: BlobRef) = ContentManifest(
        manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = 0,
        representations = listOf(
            ContentRepresentation.ImageSequence(
                representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                pages = listOf(
                    ImagePage(ResourceRef("page-a", first)),
                    ImagePage(ResourceRef("page-b", second)),
                ),
            ),
        ),
    )

    private fun withDatabase(prefix: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        val database = directory.resolve("content.sqlite")
        try {
            block(database)
        } finally {
            database.deleteIfExists()
            directory.deleteIfExists()
        }
    }
}
