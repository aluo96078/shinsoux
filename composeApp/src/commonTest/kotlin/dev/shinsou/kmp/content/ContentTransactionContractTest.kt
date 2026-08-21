package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class TestOutboxDraft(val draftId: String, val requiresV2: Boolean = false)

private object TestOutboxAdapter : ContentOutboxAdapter<TestOutboxDraft> {
    override fun validate(draft: TestOutboxDraft) {
        require(draft.draftId.isNotBlank())
    }

    override fun id(draft: TestOutboxDraft): String = draft.draftId

    override fun fingerprint(draft: TestOutboxDraft): ByteArray =
        "${draft.draftId.length}:${draft.draftId}:${draft.requiresV2}".encodeToByteArray()

    override fun isRepresentableByCurrentV1(draft: TestOutboxDraft): Boolean = !draft.requiresV2
}

class ContentTransactionContractTest {
    private val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
    private val acquisitionId = "22222222-2222-4222-8222-222222222222"

    private fun owner(number: Int): ContentManifestOwner {
        val value = number.toString().padStart(12, '0')
        return ContentManifestOwner(
            publicationKey = publication,
            acquisitionId = acquisitionId,
            unitKey = UnitKey(publication, "00000000-0000-4000-8000-$value"),
        )
    }

    private fun transactions(
        store: InMemoryContentBlobStore,
        syncMode: ContentSyncMode = ContentSyncMode.INACTIVE,
    ) = InMemorySharedContentTransactionStore(store, TestOutboxAdapter) { syncMode }

    @Test
    fun multiResourceManifestDerivesExactRefsAndConsumesAllReceiptsAtomically() {
        val store = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "store-a")
        val first = store.put("first".encodeToByteArray(), "image/png")
        val second = store.put("second".encodeToByteArray(), "image/png")
        val attachment = ManifestAttachment(owner(1), imageManifest(first.reference, second.reference))
        assertEquals(listOf(first.reference, second.reference), attachment.blobs)

        val transactions = transactions(store)
        val result = transactions.commit(
            ContentCommitBatch<TestOutboxDraft>(
                commitId = "commit-a",
                receipts = listOf(first, second),
                attachments = listOf(attachment),
                metadata = listOf(ContentMetadataMutation("unit-a/title", "A")),
                aliases = listOf(ContentAliasMutation("legacy:a", "unit-a")),
                outbox = listOf(TestOutboxDraft("draft-a")),
            ),
        )

        assertFalse(result.replayed)
        assertEquals(listOf(owner(1).scopeKey), result.attachedOwnerIds)
        assertEquals(0, store.pendingReceiptCount)
        assertEquals(listOf(first.reference, second.reference), store.attached(owner(1), attachment.manifest)?.blobs)
        assertEquals(mapOf("unit-a/title" to "A"), transactions.state.metadata)
        assertEquals(mapOf("legacy:a" to "unit-a"), transactions.state.aliases)
        assertEquals(listOf("draft-a"), transactions.state.outbox.map(TestOutboxDraft::draftId))
    }

    @Test
    fun bloblessMetadataAndOutboxCommitIsAtomic() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val transactions = transactions(store)
        val result = transactions.commit(
            ContentCommitBatch<TestOutboxDraft>(
                commitId = "metadata-only",
                metadata = listOf(ContentMetadataMutation("title", "A")),
                outbox = listOf(TestOutboxDraft("draft")),
            ),
        )
        assertEquals(null, result.committedGeneration)
        assertEquals(mapOf("title" to "A"), transactions.state.metadata)
        assertEquals(listOf("draft"), transactions.state.outbox.map(TestOutboxDraft::draftId))
    }

    @Test
    fun typedPublicationAvailabilitySeparatesPortableMetadataFromLocalBodyDurability() {
        val absentBlob = BlobRef(
            blobId = "abababab-abab-4bab-8bab-abababababab",
            schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
            digestAlgorithm = BlobRef.SHA_256,
            plaintextDigest = "a".repeat(64),
            byteSize = 5,
            mediaType = "text/plain",
        )
        val absentManifest = textManifest(absentBlob)

        listOf(
            AcquisitionAvailability.PARTIAL,
            AcquisitionAvailability.UNAVAILABLE,
        ).forEach { availability ->
            val store = InMemoryContentBlobStore(clock = { 100L })
            val transactions = transactions(store)
            val portable = typedPublication(absentManifest, availability)
            val result = transactions.commit(
                ContentCommitBatch<TestOutboxDraft>(
                    commitId = "metadata-${availability.name.lowercase()}",
                    publications = listOf(ContentPublicationMutation(portable)),
                ),
            )

            assertEquals(listOf(publication.value), result.publicationIds)
            assertEquals(portable, transactions.state.publications[publication])
            assertEquals(null, store.attached(owner(18), absentManifest))
        }

        val availableStore = InMemoryContentBlobStore(clock = { 100L })
        val availableTransactions = transactions(availableStore)
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            availableTransactions.commit(
                ContentCommitBatch<TestOutboxDraft>(
                    commitId = "available-without-body",
                    publications = listOf(
                        ContentPublicationMutation(
                            typedPublication(absentManifest, AcquisitionAvailability.AVAILABLE),
                        ),
                    ),
                ),
            )
        }
        assertTrue(availableTransactions.state.publications.isEmpty())
    }

    @Test
    fun typedPublicationAttachmentsRemainExactAndReceiptBoundForPartialMetadata() {
        val missingReceiptStore = InMemoryContentBlobStore(clock = { 100L })
        val pending = missingReceiptStore.put("hello".encodeToByteArray(), "text/plain")
        val manifest = textManifest(pending.reference)
        val attachment = ManifestAttachment(owner(18), manifest)
        val partial = typedPublication(manifest, AcquisitionAvailability.PARTIAL)
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            transactions(missingReceiptStore).commit(
                ContentCommitBatch<TestOutboxDraft>(
                    commitId = "partial-missing-receipt",
                    attachments = listOf(attachment),
                    publications = listOf(ContentPublicationMutation(partial)),
                ),
            )
        }
        assertEquals(1, missingReceiptStore.pendingReceiptCount)

        val mismatchStore = InMemoryContentBlobStore(clock = { 100L })
        val expectedBody = mismatchStore.put("hello".encodeToByteArray(), "text/plain")
        val wrongBody = mismatchStore.put("other".encodeToByteArray(), "text/plain")
        val expectedManifest = textManifest(expectedBody.reference)
        val wrongAttachment = ManifestAttachment(owner(18), textManifest(wrongBody.reference))
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            transactions(mismatchStore).commit(
                ContentCommitBatch<TestOutboxDraft>(
                    commitId = "partial-wrong-attachment",
                    receipts = listOf(wrongBody),
                    attachments = listOf(wrongAttachment),
                    publications = listOf(
                        ContentPublicationMutation(
                            typedPublication(expectedManifest, AcquisitionAvailability.PARTIAL),
                        ),
                    ),
                ),
            )
        }
        assertEquals(2, mismatchStore.pendingReceiptCount)

        val availableStore = InMemoryContentBlobStore(clock = { 100L })
        val availableBody = availableStore.put("hello".encodeToByteArray(), "text/plain")
        val availableManifest = textManifest(availableBody.reference)
        val availableAttachment = ManifestAttachment(owner(18), availableManifest)
        val result = transactions(availableStore).commit(
            ContentCommitBatch<TestOutboxDraft>(
                commitId = "available-with-exact-body",
                receipts = listOf(availableBody),
                attachments = listOf(availableAttachment),
                publications = listOf(
                    ContentPublicationMutation(
                        typedPublication(availableManifest, AcquisitionAvailability.AVAILABLE),
                    ),
                ),
            ),
        )
        assertEquals(listOf(publication.value), result.publicationIds)
        assertEquals(0, availableStore.pendingReceiptCount)
    }

    @Test
    fun migrationDigestLedgerIsAtomicDeterministicAndIdempotent() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val transactions = transactions(store)
        val migration = ContentMigrationLedgerMutation(
            namespace = "shuyue.backup.v1",
            sourceDigestSha256 = "a".repeat(64),
            resultFingerprintSha256 = "b".repeat(64),
        )
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = migration.commitId,
            metadata = listOf(ContentMetadataMutation("publication/title", "Book")),
            aliases = listOf(ContentAliasMutation("shuyue:book", "publication")),
            outbox = listOf(TestOutboxDraft("migration-draft", requiresV2 = true)),
            migrations = listOf(migration),
        )

        assertEquals(
            ContentMigrationLookupStatus.MISSING,
            transactions.lookupMigrationLedger(
                migration.namespace,
                migration.sourceDigestSha256,
                migration.resultFingerprintSha256,
            ).status,
        )

        transactions.failureInjection = ContentTransactionFailurePoint.AFTER_MIGRATION_WRITE
        assertFailsWith<IllegalStateException> { transactions.commit(batch) }
        assertTrue(transactions.state.migrations.isEmpty())
        assertTrue(transactions.state.metadata.isEmpty())
        assertTrue(transactions.state.aliases.isEmpty())
        assertTrue(transactions.state.outbox.isEmpty())

        val committed = transactions.commit(batch)
        assertEquals(listOf(migration.migrationKey), committed.migrationKeys)
        assertEquals(migration, transactions.state.migrations[migration.migrationKey])
        assertEquals(
            ContentMigrationLookupStatus.REPLAY,
            transactions.lookupMigrationLedger(
                migration.namespace,
                migration.sourceDigestSha256,
                migration.resultFingerprintSha256,
            ).status,
        )
        assertTrue(transactions.commit(batch).replayed)

        val changed = migration.copy(resultFingerprintSha256 = "c".repeat(64))
        assertEquals(
            ContentMigrationLookupStatus.CONFLICT,
            transactions.lookupMigrationLedger(
                changed.namespace,
                changed.sourceDigestSha256,
                changed.resultFingerprintSha256,
            ).status,
        )
        assertFailsWith<ContentTransactionException.CommitConflict> {
            transactions.commit(batch.copy(migrations = listOf(changed)))
        }
        assertFailsWith<IllegalArgumentException> {
            batch.copy(commitId = "different-commit")
        }
    }

    @Test
    fun portableGraphReplacementMergesLocalMetadataAliasesAndMultipleMigrationLedgers() {
        val transactions = transactions(InMemoryContentBlobStore())
        val retained = ContentMigrationLedgerMutation(
            namespace = "legacy.local",
            sourceDigestSha256 = "6".repeat(64),
            resultFingerprintSha256 = "7".repeat(64),
        )
        val imported = ContentMigrationLedgerMutation(
            namespace = "portable.archive",
            sourceDigestSha256 = "8".repeat(64),
            resultFingerprintSha256 = "9".repeat(64),
        )
        transactions.commit(
            ContentCommitBatch(
                commitId = retained.commitId,
                metadata = listOf(ContentMetadataMutation("local/theme", "dark")),
                aliases = listOf(ContentAliasMutation("local:book", "publication:local")),
                migrations = listOf(retained),
            ),
        )

        // An already-present single ledger must not turn a full restore into a replay shortcut.
        val singleLedgerRestore = transactions.commit(
            ContentCommitBatch(
                commitId = "portable-restore-single-ledger",
                metadata = listOf(ContentMetadataMutation("archive/title", "Imported")),
                aliases = listOf(ContentAliasMutation("archive:book", "publication:archive")),
                migrations = listOf(retained),
                semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
            ),
        )
        assertFalse(singleLedgerRestore.replayed)
        assertEquals("Imported", transactions.state.metadata["archive/title"])

        // Restore batches own a complete portable graph, so their archive bookkeeping can carry
        // more than one ledger and does not inherit a single ledger's deterministic commit id.
        transactions.commit(
            ContentCommitBatch(
                commitId = "portable-restore-multiple-ledgers",
                migrations = listOf(retained, imported),
                semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
            ),
        )

        assertEquals(
            mapOf("local/theme" to "dark", "archive/title" to "Imported"),
            transactions.state.metadata,
        )
        assertEquals(
            mapOf(
                "local:book" to "publication:local",
                "archive:book" to "publication:archive",
            ),
            transactions.state.aliases,
        )
        assertEquals(setOf(retained.migrationKey, imported.migrationKey), transactions.state.migrations.keys)
    }

    @Test
    fun migrationLedgerReplayAcceptsFreshReceiptAndDoesNotDuplicateDurableRows() {
        val store = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "semantic")
        val first = store.put("hello".encodeToByteArray(), "text/plain")
        val attachment = ManifestAttachment(owner(13), textManifest(first.reference))
        val migration = ContentMigrationLedgerMutation(
            namespace = "shuyue.backup.v1",
            sourceDigestSha256 = "d".repeat(64),
            resultFingerprintSha256 = "e".repeat(64),
        )
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = migration.commitId,
            receipts = listOf(first),
            attachments = listOf(attachment),
            metadata = listOf(ContentMetadataMutation("publication/title", "Book")),
            aliases = listOf(ContentAliasMutation("legacy:book", "publication:book")),
            outbox = listOf(TestOutboxDraft("migration-draft")),
            migrations = listOf(migration),
        )
        val transactions = transactions(store)
        assertFalse(transactions.commit(batch).replayed)
        val beforeReplay = transactions.state

        // A retry after staging/publishing again has a different one-use receipt object.  The
        // durable migration ledger, rather than receipt identity or the commit fingerprint, is
        // the semantic idempotency authority.
        val republished = store.put(first.reference, "hello".encodeToByteArray())
        assertTrue(transactions.commit(batch.copy(receipts = listOf(republished))).replayed)
        assertEquals(0, store.pendingReceiptCount)
        assertEquals(beforeReplay, transactions.state)
        assertEquals(attachment.asBlobAttachment(), store.attached(owner(13), attachment.manifest))

        val changed = migration.copy(resultFingerprintSha256 = "f".repeat(64))
        assertFailsWith<ContentTransactionException.CommitConflict> {
            transactions.commit(batch.copy(receipts = emptyList(), migrations = listOf(changed)))
        }
    }

    @Test
    fun migrationReplayLeavesReceiptForUnrelatedNewAttachmentPending() {
        val store = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "semantic-negative")
        val first = store.put("hello".encodeToByteArray(), "text/plain")
        val durableAttachment = ManifestAttachment(owner(15), textManifest(first.reference))
        val migration = ContentMigrationLedgerMutation(
            namespace = "shuyue.backup.v1",
            sourceDigestSha256 = "1".repeat(64),
            resultFingerprintSha256 = "2".repeat(64),
        )
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = migration.commitId,
            receipts = listOf(first),
            attachments = listOf(durableAttachment),
            migrations = listOf(migration),
        )
        val transactions = transactions(store)
        assertFalse(transactions.commit(batch).replayed)
        val beforeReplay = transactions.state

        // The retry has one exact durable attachment and one unrelated/new attachment.  The
        // migration ledger authorizes replay of the former only; the latter must not be installed
        // or cause its fresh capability to be retired.
        val republishedFirst = store.put(first.reference, "hello".encodeToByteArray())
        val newBlob = store.put("unrelated".encodeToByteArray(), "text/plain")
        val newAttachment = ManifestAttachment(
            owner(16),
            textManifest(newBlob.reference, manifestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
        )
        val replay = batch.copy(
            receipts = listOf(republishedFirst, newBlob),
            attachments = listOf(durableAttachment, newAttachment),
        )

        assertTrue(transactions.commit(replay).replayed)
        assertEquals(beforeReplay, transactions.state)
        assertEquals(durableAttachment.asBlobAttachment(), store.attached(owner(15), durableAttachment.manifest))
        assertEquals(null, store.attached(owner(16), newAttachment.manifest))
        assertEquals(listOf(newBlob.reference), store.pendingReceipts().map(BlobPublishReceipt::reference))
    }

    @Test
    fun migrationReplayLeavesForeignReceiptPendingWithoutFailing() {
        val store = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "semantic-target")
        val first = store.put("hello".encodeToByteArray(), "text/plain")
        val attachment = ManifestAttachment(owner(17), textManifest(first.reference))
        val migration = ContentMigrationLedgerMutation(
            namespace = "shuyue.backup.v1",
            sourceDigestSha256 = "3".repeat(64),
            resultFingerprintSha256 = "4".repeat(64),
        )
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = migration.commitId,
            receipts = listOf(first),
            attachments = listOf(attachment),
            migrations = listOf(migration),
        )
        val transactions = transactions(store)
        assertFalse(transactions.commit(batch).replayed)
        val beforeReplay = transactions.state

        // A different participant can publish the same immutable bytes, but its capability is
        // foreign to the target participant and must remain pending during semantic replay.
        val foreignStore = InMemoryContentBlobStore(
            clock = { 100L },
            configuredStoreInstanceId = "semantic-foreign",
        )
        val foreignReceipt = foreignStore.put(first.reference, "hello".encodeToByteArray())
        assertTrue(transactions.commit(batch.copy(receipts = listOf(foreignReceipt))).replayed)
        assertEquals(beforeReplay, transactions.state)
        assertEquals(0, store.pendingReceiptCount)
        assertEquals(1, foreignStore.pendingReceiptCount)
        assertEquals(foreignReceipt, foreignStore.pendingReceipts().single())
    }

    @Test
    fun validationFailureLeavesBlobAttachmentMetadataAliasOutboxAndReceiptsUntouched() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val first = store.put("first".encodeToByteArray(), "image/png")
        val second = store.put("second".encodeToByteArray(), "image/png")
        val transactions = transactions(store)
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = "commit-failure",
            receipts = listOf(first, second),
            attachments = listOf(ManifestAttachment(owner(2), imageManifest(first.reference, second.reference))),
            metadata = listOf(ContentMetadataMutation("key", "value")),
            aliases = listOf(ContentAliasMutation("alias", "target")),
            outbox = listOf(TestOutboxDraft("draft-failure")),
        )

        transactions.failureInjection = ContentTransactionFailurePoint.AFTER_METADATA_VALIDATE
        assertFailsWith<IllegalStateException> { transactions.commit(batch) }
        assertEquals(2, store.pendingReceiptCount)
        assertEquals(null, store.attached(owner(2), batch.attachments.single().manifest))
        assertEquals(emptyMap(), transactions.state.metadata)
        assertEquals(emptyMap(), transactions.state.aliases)
        assertTrue(transactions.state.outbox.isEmpty())

        val committed = transactions.commit(batch)
        assertFalse(committed.replayed)
        assertEquals(0, store.pendingReceiptCount)
    }

    @Test
    fun postWriteFailureRollsBackEveryParticipantAndRetrySucceeds() {
        val points = listOf(
            ContentTransactionFailurePoint.AFTER_BLOB_ATTACHMENT_WRITE,
            ContentTransactionFailurePoint.AFTER_METADATA_WRITE,
            ContentTransactionFailurePoint.AFTER_ALIAS_WRITE,
            ContentTransactionFailurePoint.AFTER_OUTBOX_WRITE,
            ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE,
        )
        points.forEach { point ->
            val store = InMemoryContentBlobStore(clock = { 100L })
            val blob = store.put("body".encodeToByteArray(), "text/plain")
            val attachment = ManifestAttachment(owner(point.ordinal + 10), textManifest(blob.reference))
            val transactions = transactions(store)
            val batch = ContentCommitBatch<TestOutboxDraft>(
                commitId = "failure-${point.name}",
                receipts = listOf(blob),
                attachments = listOf(attachment),
                metadata = listOf(ContentMetadataMutation("key", "value")),
                aliases = listOf(ContentAliasMutation("alias", "target")),
                outbox = listOf(TestOutboxDraft("draft")),
            )
            transactions.failureInjection = point
            assertFailsWith<IllegalStateException> { transactions.commit(batch) }
            assertEquals(1, store.pendingReceiptCount)
            assertTrue(transactions.state.metadata.isEmpty())
            assertTrue(transactions.state.aliases.isEmpty())
            assertTrue(transactions.state.outbox.isEmpty())
            assertFalse(transactions.commit(batch).replayed)
        }
    }

    @Test
    fun commitIdReplayRequiresTheOriginalReceiptObject() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        val transactions = transactions(store)
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = "commit-replay",
            receipts = listOf(published),
            attachments = listOf(ManifestAttachment(owner(3), textManifest(published.reference))),
        )
        assertFalse(transactions.commit(batch).replayed)
        assertTrue(transactions.commit(batch).replayed)
        assertEquals(1, transactions.state.committedIds.size)

        val copied = BlobPublishReceipt(
            storeInstanceId = published.storeInstanceId,
            commitToken = published.commitToken,
            reference = published.reference,
            incarnation = published.incarnation,
            generation = published.generation,
            publishedAtEpochMillis = published.publishedAtEpochMillis,
        )
        assertFailsWith<ContentBlobStoreException.ReceiptMismatch> {
            transactions.commit(batch.copy(receipts = listOf(copied)))
        }
    }

    @Test
    fun receiptsRejectForeignMissingExtraDuplicateAndMismatchedReferences() {
        val source = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "source")
        val foreign = source.put("foreign".encodeToByteArray(), "text/plain")
        val target = InMemoryContentBlobStore(clock = { 100L }, configuredStoreInstanceId = "target")
        val transactions = transactions(target)
        assertFailsWith<ContentBlobStoreException.ForeignReceipt> {
            transactions.commit(
                ContentCommitBatch<TestOutboxDraft>(
                    "foreign",
                    receipts = listOf(foreign),
                    attachments = listOf(ManifestAttachment(owner(4), textManifest(foreign.reference))),
                ),
            )
        }

        val first = target.put("first".encodeToByteArray(), "image/png")
        val second = target.put("second".encodeToByteArray(), "image/png")
        val two = ManifestAttachment(owner(5), imageManifest(first.reference, second.reference))
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            transactions.commit(ContentCommitBatch<TestOutboxDraft>("missing", listOf(first), listOf(two)))
        }
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            transactions.commit(ContentCommitBatch<TestOutboxDraft>("extra", listOf(first, second), listOf(
                ManifestAttachment(owner(6), imageManifest(first.reference, first.reference)),
            )))
        }
        assertFailsWith<ContentBlobStoreException.InvalidStage> {
            transactions.commit(ContentCommitBatch<TestOutboxDraft>("duplicate", listOf(first, first), listOf(
                ManifestAttachment(owner(7), imageManifest(first.reference, first.reference)),
            )))
        }
        assertEquals(2, target.pendingReceiptCount)
    }

    @Test
    fun sharedReferenceCanBeCommittedOnceAndReusedAfterVerification() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val first = store.put("hello".encodeToByteArray(), "text/plain")
        val transactions = transactions(store)
        val firstAttachment = ManifestAttachment(owner(8), textManifest(first.reference))
        transactions.commit(ContentCommitBatch<TestOutboxDraft>("first", listOf(first), listOf(firstAttachment)))

        val secondAttachment = ManifestAttachment(
            owner(9),
            textManifest(first.reference, "99999999-9999-4999-8999-999999999999"),
        )
        transactions.commit(ContentCommitBatch<TestOutboxDraft>("second", attachments = listOf(secondAttachment)))
        assertEquals(first.reference, store.attached(owner(9), secondAttachment.manifest)?.blobs?.single())

        val republished = store.put(first.reference, "hello".encodeToByteArray())
        val thirdAttachment = ManifestAttachment(
            owner(10),
            textManifest(first.reference, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        )
        transactions.commit(ContentCommitBatch<TestOutboxDraft>("third", listOf(republished), listOf(thirdAttachment)))
        assertEquals(0, store.pendingReceiptCount)
    }

    @Test
    fun mutableManifestCollectionsAreSealedBeforeCommitAndRecoveryKeepsOriginalBlobPinned() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val first = store.put("first".encodeToByteArray(), "image/png")
        val later = store.put("later".encodeToByteArray(), "image/png")
        val pages = arrayListOf(
            ImagePage(ResourceRef("page-a", first.reference)),
        )
        val transformParameters = mutableMapOf<String, String>()
        val transforms = arrayListOf(
            ImageTransform(ImageTransform.CURRENT_SCHEMA_VERSION, "identity", transformParameters),
        )
        val representations = arrayListOf<ContentRepresentation>(
            ContentRepresentation.ImageSequence(
                representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                pages = pages,
                transforms = transforms,
            ),
        )
        val resources = arrayListOf<ResourceRef>()
        val manifest = ContentManifest(
            manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 0,
            representations = representations,
            resources = resources,
        )
        val attachment = ManifestAttachment(owner(14), manifest)
        val transactions = transactions(store)
        val batch = ContentCommitBatch<TestOutboxDraft>(
            commitId = "immutable-admission",
            receipts = listOf(first),
            attachments = listOf(attachment),
        )

        transactions.commit(batch)
        val admitted = batch.attachments.single()
        assertEquals(listOf(first.reference), admitted.blobs)

        // Mutate every caller-owned collection after admission.  None of these operations may
        // alter the committed manifest/ref ledger or the blob selected for GC protection.
        pages.clear()
        pages += ImagePage(ResourceRef("page-b", later.reference))
        transformParameters["caller-only"] = "later"
        transforms.clear()
        transforms += ImageTransform(ImageTransform.CURRENT_SCHEMA_VERSION, "identity", mutableMapOf())
        representations.clear()
        resources += ResourceRef("caller-only", later.reference)

        assertEquals(listOf(first.reference), admitted.blobs)
        assertEquals(emptyMap(), admitted.manifest.imageSequences.single().transforms.single().parameters)
        assertEquals(listOf(first.reference), store.attached(owner(14), manifest.manifestId, 0)?.blobs)

        assertFails {
            @Suppress("UNCHECKED_CAST")
            val mutablePages = admitted.manifest.imageSequences.single().pages as MutableList<ImagePage>
            mutablePages.clear()
        }
        assertFails {
            @Suppress("UNCHECKED_CAST")
            val mutableIterator = admitted.manifest.imageSequences.single().pages.iterator() as MutableIterator<ImagePage>
            mutableIterator.remove()
        }

        // Process recovery loses the uncommitted receipt for `later`; the committed first blob
        // remains attached even though the caller changed its original page list to point at it.
        store.simulateProcessCrashAndRecover()
        val boundary = RecoveryBoundary(
            safetyCutoffGeneration = store.currentGeneration,
            nowEpochMillis = 1_000L,
            minimumAgeMillis = 0,
        )
        val plan = store.planRecovery(boundary)
        assertEquals(listOf(later.reference), plan.candidateReferences)
        assertEquals(1, store.sweepRecovery(plan))
        assertTrue(store.verify(first.reference))
        assertEquals(1, store.count)
    }

    @Test
    fun v1SyncRejectsOrDefersUnrepresentableContentDrafts() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        val base = ContentCommitBatch<TestOutboxDraft>(
            commitId = "v1-content",
            receipts = listOf(published),
            attachments = listOf(ManifestAttachment(owner(11), textManifest(published.reference))),
            outbox = listOf(TestOutboxDraft("v2-draft", requiresV2 = true)),
        )
        val transactions = InMemorySharedContentTransactionStore(store, TestOutboxAdapter)
        assertFailsWith<ContentTransactionException.V1SyncCannotRepresent> { transactions.commit(base) }
        assertEquals(1, store.pendingReceiptCount)

        val deferred = transactions.commit(
            base.copy(commitId = "v1-content-deferred", unrepresentableDraftPolicy = UnrepresentableDraftPolicy.DEFER),
        )
        assertTrue(deferred.deferred)
        assertEquals(1, store.pendingReceiptCount)
    }

    @Test
    fun defaultV1AuthorityRejectsMetadataAndAliasesButAllowsRepresentableOutbox() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val transactions = InMemorySharedContentTransactionStore(store, TestOutboxAdapter)
        val unrepresentable = ContentCommitBatch<TestOutboxDraft>(
            commitId = "v1-metadata-alias",
            metadata = listOf(ContentMetadataMutation("publication/title", "Book")),
            aliases = listOf(ContentAliasMutation("legacy:book", "publication:book")),
        )
        val rejected = assertFailsWith<ContentTransactionException.V1SyncCannotRepresent> {
            transactions.commit(unrepresentable)
        }
        assertEquals(
            setOf("metadata:publication/title", "alias:legacy:book"),
            rejected.draftIds.toSet(),
        )
        val deferred = transactions.commit(
            unrepresentable.copy(
                commitId = "v1-metadata-alias-deferred",
                unrepresentableDraftPolicy = UnrepresentableDraftPolicy.DEFER,
            ),
        )
        assertTrue(deferred.deferred)
        assertTrue(transactions.state.metadata.isEmpty())
        assertTrue(transactions.state.aliases.isEmpty())

        assertFalse(
            transactions.commit(
                ContentCommitBatch(
                    commitId = "v1-representable-outbox",
                    outbox = listOf(TestOutboxDraft("v1-draft")),
                ),
            ).deferred,
        )
    }

    @Test
    fun readLeasePinsAndRecoveryRechecksReaderRaceAndAge() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val published = store.put("orphan".encodeToByteArray(), "text/plain")
        val boundary = RecoveryBoundary(
            safetyCutoffGeneration = published.generation,
            nowEpochMillis = 1_000L,
            minimumAgeMillis = 1L,
        )
        val lease = assertNotNull(store.openRead(published.reference))
        assertTrue(lease.isPinned)
        lease.pin()
        assertTrue(store.planRecovery(boundary).candidates.isEmpty())
        lease.close()
        lease.close()
        assertTrue(lease.isClosed)
        // The receipt still protects the blob. Simulate process recovery to model a lost local
        // capability; discovery is then separate from the subsequent aged sweep.
        store.simulateProcessCrashAndRecover()
        val plan = store.planRecovery(boundary)
        assertEquals(listOf(published.reference), plan.candidateReferences)
        assertEquals(1, store.sweepRecovery(plan))
        assertEquals(0, store.count)
    }

    @Test
    fun defaultRecoveryNeverDeletesAndReceiptIsOneUse() {
        val store = InMemoryContentBlobStore(clock = { 100L })
        val published = store.put("hello".encodeToByteArray(), "text/plain")
        assertTrue(store.recoverOrphans().isEmpty())
        assertEquals(0, store.garbageCollectOrphans())
        val attachment = textManifest(published.reference)
        store.attach(published, BlobAttachment(owner(12), attachment))
        assertFailsWith<ContentBlobStoreException.ReceiptConsumed> {
            store.attach(published, BlobAttachment(owner(13), attachment))
        }
    }

    private fun imageManifest(
        first: BlobRef,
        second: BlobRef,
        manifestId: String = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    ): ContentManifest = ContentManifest(
        manifestId = manifestId,
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = 0,
        representations = listOf(
            ContentRepresentation.ImageSequence(
                representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                pages = listOf(ImagePage(ResourceRef("page-a", first)), ImagePage(ResourceRef("page-b", second))),
            ),
        ),
    )

    private fun textManifest(
        blob: BlobRef,
        manifestId: String = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    ): ContentManifest = ContentManifest(
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

    private fun typedPublication(
        manifest: ContentManifest,
        availability: AcquisitionAvailability,
    ): Publication = Publication(
        key = publication,
        title = "Typed publication",
        acquisitions = listOf(
            Acquisition(
                id = acquisitionId,
                origin = AcquisitionOrigin.LocalText,
                units = listOf(
                    PublicationUnit(
                        key = owner(18).unitKey,
                        title = "Unit",
                        manifestRevisions = listOf(manifest),
                        ordinal = 0,
                    ),
                ),
                availability = availability,
            ),
        ),
    )
}
