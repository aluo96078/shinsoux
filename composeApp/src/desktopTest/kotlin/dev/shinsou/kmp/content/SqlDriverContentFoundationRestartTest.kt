package dev.shinsou.kmp.content

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.shinsou.kmp.acquisition.EpubAcquisitionRequest
import dev.shinsou.kmp.acquisition.EpubAcquisitionService
import dev.shinsou.kmp.acquisition.EpubArchiveEntry
import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.LegacyAliasKey
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.PortableAliasException
import dev.shinsou.kmp.domain.model.PortableAliasRequest
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
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.sync.v2.SyncDraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class RestartDraft(val id: String)

private object RestartDraftAdapter : ContentOutboxPersistenceAdapter<RestartDraft> {
    private val json = Json
    override fun validate(draft: RestartDraft) = require(draft.id.isNotBlank())
    override fun id(draft: RestartDraft): String = draft.id
    override fun fingerprint(draft: RestartDraft): ByteArray = json.encodeToString(draft).encodeToByteArray()
    override fun isRepresentableByCurrentV1(draft: RestartDraft): Boolean = true
    override fun encode(draft: RestartDraft): String = json.encodeToString(draft)
    override fun decode(payload: String): RestartDraft = json.decodeFromString(payload)
}

class SqlDriverContentFoundationRestartTest {
    @Test
    fun resumableMigrationDigestMatchesCanonicalSha256AcrossChunkBoundaries() {
        val body = ByteArray(160 * 1024 + 37) { index -> (index * 31).toByte() }
        val chunks = body.asList().chunked(16 * 1024).map { values ->
            ByteArray(values.size) { index -> values[index] }
        }
        assertEquals(Sha256.hex("abc".encodeToByteArray()),
            resumableContentBlobSha256ForTesting(listOf("abc".encodeToByteArray())))
        assertEquals(Sha256.hex(body), resumableContentBlobSha256ForTesting(listOf(body)))
        assertEquals(Sha256.hex(body), resumableContentBlobSha256ForTesting(chunks))
    }

    @Test
    fun contentFoundationColdStartLoadsOnlyBlobMetadataAndReadsBodiesOnDemand() {
        withDatabase("content-foundation-lazy-blobs") { database ->
            val bodies = listOf(
                "first durable body".encodeToByteArray(),
                "second durable body".encodeToByteArray(),
            )
            val blobIds = listOf(BLOB_ID, ORPHAN_BLOB_ID).iterator()
            val commitTokens = listOf("lazy-first-token", "lazy-second-token").iterator()
            val firstDriver = driver(database)
            val firstBlobs = SqlDriverContentBlobStore(
                firstDriver,
                blobIdFactory = { blobIds.next() },
                commitTokenFactory = { commitTokens.next() },
                storeInstanceIdFactory = { "lazy-restart-safe-store" },
                clock = { 100L },
            )
            val receipts = bodies.map { body -> firstBlobs.put(body, "text/plain") }
            val attachment = AuxiliaryBlobAttachment(
                ownerId = "cold-start-bodies",
                purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                blobs = receipts.map(BlobPublishReceipt::reference),
            )
            transactionStore(firstDriver, firstBlobs).commit(
                ContentCommitBatch(
                    commitId = "cold-start-bodies",
                    receipts = receipts,
                    auxiliaryAttachments = listOf(attachment),
                ),
            )
            val durableGeneration = firstBlobs.currentGeneration
            firstDriver.close()

            val reopenedDriver = driver(database)
            val recordingDriver = RecordingSqlDriver(reopenedDriver)
            val runtime = ContentFoundationRuntime(
                recordingDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )

            assertEquals(2, runtime.blobStore.count)
            assertEquals(durableGeneration, runtime.blobStore.currentGeneration)
            assertEquals(0, runtime.blobStore.pendingReceiptCount)
            val recovery = runtime.blobStore.planRecovery(
                RecoveryBoundary(durableGeneration, nowEpochMillis = 10_000L, minimumAgeMillis = 0L),
            )
            assertTrue(recovery.candidates.isEmpty())
            receipts.forEach { receipt ->
                assertEquals(
                    BlobRecoveryProtection.ATTACHED,
                    recovery.protectedBlobs[receipt.reference.blobId],
                )
            }
            assertEquals(0, recordingDriver.contentBlobPayloadQueryCount)

            val lease = assertNotNull(runtime.blobStore.openRead(receipts[0].reference))
            assertEquals(1, recordingDriver.contentBlobPayloadQueryCount)
            assertTrue(lease.isPinned)
            assertContentEquals(bodies[0], lease.readChunk())
            assertNull(lease.readChunk())
            lease.close()
            assertTrue(lease.isClosed)
            assertEquals(1, recordingDriver.contentBlobPayloadQueryCount)

            assertContentEquals(bodies[1], runtime.blobStore.read(receipts[1].reference))
            assertEquals(2, recordingDriver.contentBlobPayloadQueryCount)
            assertEquals(0, runtime.blobStore.sweepRecovery(recovery))
            assertEquals(2, runtime.blobStore.count)
            reopenedDriver.close()
        }
    }

    @Test
    fun blobAttachmentTypedPublicationAndRightsGrantSurviveReopenAndStayGcProtected() {
        withDatabase("content-foundation-restart") { database ->
            val body = "durable body".encodeToByteArray()
            val firstDriver = driver(database)
            val firstBlobs = SqlDriverContentBlobStore(
                firstDriver,
                blobIdFactory = { BLOB_ID },
                commitTokenFactory = { "first-token" },
                storeInstanceIdFactory = { "restart-safe-store" },
                clock = { 100L },
            )
            val receipt = firstBlobs.put(body, "text/plain")
            val fixture = publicationFixture(receipt.reference)
            val syncJob = ContentBlobSyncJobMutation(
                jobId = "blob-upload:$BLOB_ID",
                blob = receipt.reference,
                owner = fixture.attachment.owner,
                manifestId = fixture.attachment.manifestId,
                contentRevision = fixture.attachment.contentRevision,
                grantReference = fixture.grant.grantId,
            )
            val firstStore = transactionStore(firstDriver, firstBlobs)
            val result = firstStore.commit(
                ContentCommitBatch(
                    commitId = "typed-publication-with-rights",
                    receipts = listOf(receipt),
                    attachments = listOf(fixture.attachment),
                    publications = listOf(ContentPublicationMutation(fixture.publication)),
                    rightsGrants = listOf(ContentRightsGrantMutation(fixture.grant)),
                    blobSyncJobs = listOf(syncJob),
                ),
            )
            assertEquals(listOf(PUBLICATION_ID), result.publicationIds)
            assertEquals(listOf(GRANT_ID), result.rightsGrantIds)
            assertEquals(listOf(syncJob.jobId), result.blobSyncJobIds)
            val originalStoreId = firstBlobs.storeInstanceId
            val originalGeneration = firstBlobs.currentGeneration
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedBlobs = SqlDriverContentBlobStore(
                reopenedDriver,
                storeInstanceIdFactory = { "must-not-replace-durable-id" },
                clock = { 500L },
            )
            val reopened = transactionStore(reopenedDriver, reopenedBlobs)
            assertEquals(originalStoreId, reopenedBlobs.storeInstanceId)
            assertEquals(originalGeneration, reopenedBlobs.currentGeneration)
            assertContentEquals(body, reopenedBlobs.read(receipt.reference))
            assertEquals(fixture.publication, reopened.state.publications[fixture.publication.key])
            assertEquals(fixture.grant, reopened.state.rightsGrants[fixture.grant.grantId])
            assertEquals(mapOf(syncJob.jobId to syncJob), reopened.state.blobSyncJobs)
            assertEquals(ContentTransactionSchema.VERSION, scalarLong(reopenedDriver,
                "SELECT format_version FROM content_transaction_schema WHERE singleton_id = 1"))

            val plan = reopenedBlobs.planRecovery(
                RecoveryBoundary(reopenedBlobs.currentGeneration, nowEpochMillis = 10_000L, minimumAgeMillis = 0),
            )
            assertTrue(plan.candidates.isEmpty())
            assertEquals(BlobRecoveryProtection.ATTACHED, plan.protectedBlobs[receipt.reference.blobId])
            assertEquals(0, reopenedBlobs.sweepRecovery(plan))

            val runtime = ContentFoundationRuntime(
                reopenedDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            assertEquals(fixture.grant, runtime.rightsGrants.find(fixture.grant.grantId))
            val admitted = assertNotNull(
                runtime.rightsAuthority.resolve(fixture.grant.grantId, fixture.grant.scope, 0L),
            )
            assertTrue(admitted.allows(ContentOperation.DISPLAY, 0L))
            runtime.rightsAuthority.revoke(fixture.grant.grantId)
            assertFalse(admitted.allows(ContentOperation.DISPLAY, 0L))
            assertNull(runtime.rightsAuthority.resolve(fixture.grant.grantId, fixture.grant.scope, 0L))

            val updatedGrant = fixture.grant.copy(allowedOperations = setOf(ContentOperation.EXPORT))
            runtime.rightsAuthority.admit(updatedGrant)
            val updatedAdmission = assertNotNull(
                runtime.rightsAuthority.resolve(updatedGrant.grantId, updatedGrant.scope, 0L),
            )
            assertFalse(updatedAdmission.allows(ContentOperation.DISPLAY, 0L))
            assertTrue(updatedAdmission.allows(ContentOperation.EXPORT, 0L))

            // The normalized child rows are checked against the canonical publication body.
            reopenedDriver.execute(
                null,
                "DELETE FROM content_units WHERE unit_id = ?",
                1,
            ) { bindString(0, UNIT_ID) }.value
            assertFailsWith<IllegalStateException> { reopened.state }
            reopenedDriver.close()
        }
    }

    @Test
    fun epubOpfNavigationNcxAndCssGraphSurvivesSqlRestart() {
        withDatabase("content-epub-package-restart") { database ->
            var blobNumber = 0
            var tokenNumber = 0
            val firstDriver = driver(database)
            val firstBlobs = SqlDriverContentBlobStore(
                firstDriver,
                blobIdFactory = {
                    blobNumber++
                    "90000000-0000-4000-8000-${blobNumber.toString().padStart(12, '0')}"
                },
                commitTokenFactory = { "epub-restart-token-${++tokenNumber}" },
                storeInstanceIdFactory = { "epub-restart-store" },
                clock = { 100L },
            )
            val target = LocalAcquisitionTarget(
                publicationKey = PublicationKey("91919191-9191-4191-8191-919191919191"),
                publicationTitle = "Restart EPUB",
                stableImportId = "restart-contract/epub-package",
                contentRevision = 4,
            )
            val acquired = EpubAcquisitionService(
                blobStore = firstBlobs,
                archiveExtractor = EpubArchiveExtractor { _, _ -> epubRestartEntries() },
            ).acquire(EpubAcquisitionRequest(target, "durable epub archive".encodeToByteArray()))
            val owner = ContentManifestOwner(
                publicationKey = acquired.publicationDraft.key,
                acquisitionId = acquired.acquisition.id,
                unitKey = acquired.unit.key,
            )
            val attachment = ManifestAttachment(owner, acquired.manifest)
            transactionStore(firstDriver, firstBlobs).commit(
                ContentCommitBatch(
                    commitId = "epub-package-restart-contract",
                    receipts = acquired.publishedBlobs,
                    attachments = listOf(attachment),
                    publications = listOf(ContentPublicationMutation(acquired.publicationDraft)),
                ),
            )
            val expectedGraph = (acquired.representation as ContentRepresentation.EpubSpine).packageGraph
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedBlobs = SqlDriverContentBlobStore(
                reopenedDriver,
                storeInstanceIdFactory = { "must-not-replace-epub-store" },
                clock = { 500L },
            )
            val reopened = transactionStore(reopenedDriver, reopenedBlobs)
            val publication = assertNotNull(reopened.findPublicationDirect(target.publicationKey))
            val representation = publication.acquisitions.single().units.single()
                .manifestRevisions.single().representations.single() as ContentRepresentation.EpubSpine
            val graph = representation.packageGraph

            assertEquals(expectedGraph, graph)
            assertEquals("fallback", graph.manifest.single { it.manifestIdRef == "chapter" }.fallbackIdRef)
            assertEquals("overlay", graph.manifest.single { it.manifestIdRef == "chapter" }.mediaOverlayIdRef)
            assertEquals("ncx", graph.spineTocManifestIdRef)
            assertEquals("rtl", graph.packageMetadata?.pageProgressionDirection)
            assertEquals("reflowable", graph.renditions.single().layout)
            assertEquals(
                setOf(EpubPackageNavigationKind.EPUB3_NAV, EpubPackageNavigationKind.NCX),
                graph.navigation.mapTo(linkedSetOf()) { it.kind },
            )
            assertEquals(
                "OPS/Styles/theme.css",
                graph.cssDependencies.single { it.stylesheetHref == "OPS/Styles/main.css" }
                    .references.single().resolvedHref,
            )
            assertEquals(
                setOf("rendition:layout-pre-paginated", "page-spread-left"),
                representation.documents.single().properties,
            )
            assertEquals("pre-paginated", representation.documents.single().rendition?.layout)
            assertContentEquals(
                "durable epub archive".encodeToByteArray(),
                reopenedBlobs.read(graph.archive),
            )
            reopenedDriver.close()
        }
    }

    @Test
    fun receiptLessPublishIsDiscoveredThenAgesAcrossAnotherReopenBeforeSweep() {
        withDatabase("content-orphan-restart") { database ->
            val bytes = "unattached".encodeToByteArray()
            val firstDriver = driver(database)
            val firstBlobs = SqlDriverContentBlobStore(
                firstDriver,
                blobIdFactory = { ORPHAN_BLOB_ID },
                commitTokenFactory = { "lost-on-crash" },
                storeInstanceIdFactory = { "orphan-store" },
                clock = { 100L },
            )
            val receipt = firstBlobs.put(bytes, "text/plain")
            val storeId = firstBlobs.storeInstanceId
            firstDriver.close() // models process death before any attachment transaction

            val discoveryDriver = driver(database)
            val discoveryBlobs = SqlDriverContentBlobStore(discoveryDriver, clock = { 200L })
            assertEquals(storeId, discoveryBlobs.storeInstanceId)
            assertEquals(receipt.generation, discoveryBlobs.currentGeneration)
            val discovery = discoveryBlobs.planRecovery(
                RecoveryBoundary(receipt.generation, nowEpochMillis = 200L, minimumAgeMillis = 50L),
            )
            assertTrue(discovery.candidates.isEmpty())
            assertEquals(BlobRecoveryProtection.DISCOVERED_ORPHAN,
                discovery.protectedBlobs[receipt.reference.blobId])
            assertEquals(receipt.incarnation, discovery.discoveredOrphans.single().incarnation)
            discoveryDriver.close()

            val sweepDriver = driver(database)
            val sweepBlobs = SqlDriverContentBlobStore(sweepDriver, clock = { 300L })
            val aged = sweepBlobs.planRecovery(
                RecoveryBoundary(receipt.generation, nowEpochMillis = 300L, minimumAgeMillis = 50L),
            )
            assertEquals(listOf(receipt.reference), aged.candidateReferences)
            assertEquals(receipt.incarnation, aged.candidates.single().incarnation)
            assertEquals(1, sweepBlobs.sweepRecovery(aged))
            assertFalse(sweepBlobs.contains(receipt.reference))
            sweepDriver.close()
        }
    }

    @Test
    fun productionRecoveryEntryPointDiscoversBeforeSweepAndRetainsCommittedAttachments() {
        withDatabase("content-runtime-orphan-recovery") { database ->
            val firstDriver = driver(database)
            val first = ContentFoundationRuntime(
                firstDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val orphan = first.blobStore.put("orphaned after publish".encodeToByteArray(), "text/plain")
            val attached = first.blobStore.put("committed attachment".encodeToByteArray(), "text/plain")
            first.transactions.commit(
                ContentCommitBatch<SyncDraft>(
                    commitId = "runtime-recovery-attached",
                    receipts = listOf(attached),
                    auxiliaryAttachments = listOf(
                        AuxiliaryBlobAttachment(
                            ownerId = "runtime-recovery-fixture",
                            purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                            blobs = listOf(attached.reference),
                        ),
                    ),
                ),
            )
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopened = ContentFoundationRuntime(
                reopenedDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val discovery = reopened.recoverOrphanedBlobs(
                nowEpochMillis = 10_000L,
                minimumAgeMillis = 0L,
            )
            assertEquals(1, discovery.discoveredCount)
            assertEquals(0, discovery.eligibleCount)
            assertEquals(0, discovery.removedCount)
            assertEquals(
                BlobLifecycleState.DISCOVERED_ORPHAN,
                reopened.blobStore.lifecycleState(orphan.reference),
            )
            assertTrue(reopened.blobStore.contains(attached.reference))

            val sweep = reopened.recoverOrphanedBlobs(
                nowEpochMillis = 10_000L,
                minimumAgeMillis = 0L,
            )
            assertEquals(1, sweep.discoveredCount)
            assertEquals(1, sweep.eligibleCount)
            assertEquals(1, sweep.removedCount)
            assertEquals(null, reopened.blobStore.lifecycleState(orphan.reference))
            assertTrue(reopened.blobStore.contains(attached.reference))
            reopenedDriver.close()
        }
    }

    @Test
    fun quarantineAuxiliaryAttachmentSurvivesReopenAndProtectsScriptBytes() {
        withDatabase("content-quarantine-restart") { database ->
            val script = "export default { id: 'legacy' }".encodeToByteArray()
            val firstDriver = driver(database)
            val firstBlobs = SqlDriverContentBlobStore(firstDriver, clock = { 10L })
            val receipt = firstBlobs.put(script, ContentQuarantineMutation.QUARANTINED_SCRIPT_MEDIA_TYPE)
            val quarantine = ContentQuarantineMutation(
                quarantineId = "legacy-script-0",
                packageId = "legacy.pkg",
                version = "1",
                sourceIds = listOf("source-1"),
                origin = "shuyue-v1",
                ordinal = 0,
                scriptBlob = receipt.reference,
            )
            val auxiliary = AuxiliaryBlobAttachment(
                ownerId = quarantine.auxiliaryOwnerId,
                purpose = AuxiliaryBlobPurpose.PLUGIN_QUARANTINE,
                blobs = listOf(receipt.reference),
            )
            transactionStore(firstDriver, firstBlobs).commit(
                ContentCommitBatch(
                    commitId = "quarantine-commit",
                    receipts = listOf(receipt),
                    auxiliaryAttachments = listOf(auxiliary),
                    quarantines = listOf(quarantine),
                ),
            )
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedBlobs = SqlDriverContentBlobStore(reopenedDriver, clock = { 1_000L })
            val reopened = transactionStore(reopenedDriver, reopenedBlobs)
            assertEquals(listOf(auxiliary), reopened.state.auxiliaryAttachments)
            assertEquals(mapOf(quarantine.quarantineId to quarantine), reopened.state.quarantines)
            assertContentEquals(script, reopenedBlobs.read(receipt.reference))
            val plan = reopenedBlobs.planRecovery(
                RecoveryBoundary(reopenedBlobs.currentGeneration, 1_000L, 0L),
            )
            assertEquals(BlobRecoveryProtection.ATTACHED, plan.protectedBlobs[receipt.reference.blobId])
            assertTrue(plan.candidates.isEmpty())
            reopenedDriver.close()
        }
    }

    @Test
    fun runtimeAdmitsNewlyCommittedGrantButDoesNotUndoHostRevocationOnRowReuse() {
        withDatabase("content-runtime-rights") { database ->
            val sqlDriver = driver(database)
            val runtime = ContentFoundationRuntime(
                sqlDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val receipt = runtime.blobStore.put("durable body".encodeToByteArray(), "text/plain")
            val fixture = publicationFixture(receipt.reference)
            assertNull(runtime.rightsAuthority.resolve(fixture.grant.grantId, fixture.grant.scope, 0L))

            runtime.transactions.commit(
                ContentCommitBatch<SyncDraft>(
                    commitId = "runtime-rights-first",
                    receipts = listOf(receipt),
                    attachments = listOf(fixture.attachment),
                    publications = listOf(ContentPublicationMutation(fixture.publication)),
                    rightsGrants = listOf(ContentRightsGrantMutation(fixture.grant)),
                ),
            )
            assertNotNull(runtime.rightsAuthority.resolve(fixture.grant.grantId, fixture.grant.scope, 0L))

            runtime.rightsAuthority.revoke(fixture.grant.grantId)
            runtime.transactions.commit(
                ContentCommitBatch(
                    commitId = "runtime-rights-existing-row",
                    attachments = listOf(fixture.attachment),
                    publications = listOf(ContentPublicationMutation(fixture.publication)),
                    rightsGrants = listOf(ContentRightsGrantMutation(fixture.grant)),
                ),
            )
            assertNull(runtime.rightsAuthority.resolve(fixture.grant.grantId, fixture.grant.scope, 0L))
            sqlDriver.close()
        }
    }

    @Test
    fun portableAliasBindingSurvivesRestartAndChangedBindingConflicts() {
        withDatabase("content-alias-restart") { database ->
            val namespace = MigrationNamespaceId.LEGACY_MANGA_V1
            val alias = LegacyAliasKey.Manga(id = 7, source = 9)
            val request = PortableAliasRequest(namespace, alias, PUBLICATION_ID)
            val firstDriver = driver(database)
            val first = SqlDriverPortableAliasResolver(firstDriver).resolveOrBindAll(listOf(request)).single()
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopened = SqlDriverPortableAliasResolver(reopenedDriver)
            assertEquals(first, reopened.resolve(namespace, alias))
            assertFailsWith<PortableAliasException.ChangedBinding> {
                reopened.resolveOrBindAll(
                    listOf(PortableAliasRequest(namespace, alias, "99999999-9999-5999-8999-999999999999")),
                )
            }
            assertEquals(first, reopened.resolve(namespace, alias))
            reopenedDriver.close()
        }
    }

    @Test
    fun productionBodyDirectoryPublishesImmutableFileAndKeepsSQLiteMetadataOnly() {
        withDatabase("content-file-backed-body") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val body = "file-backed immutable body".encodeToByteArray()
            val firstDriver = driver(database)
            val first = SqlDriverContentBlobStore(
                driver = firstDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
                blobIdFactory = { BLOB_ID },
                commitTokenFactory = { "file-backed-token" },
                storeInstanceIdFactory = { "file-backed-store" },
                clock = { 100L },
            )
            val receipt = first.put(body, "text/plain")
            val publishedPath = blobRoot.resolve("objects/v1-$BLOB_ID.blob")

            assertTrue(Files.isRegularFile(publishedPath))
            assertContentEquals(body, Files.readAllBytes(publishedPath))
            assertEquals(0L, scalarLong(firstDriver,
                "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'"))
            assertEquals(1L, scalarLong(firstDriver,
                "SELECT count(*) FROM content_blobs WHERE opaque_name = 'v1-$BLOB_ID.blob'"))
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopened = SqlDriverContentBlobStore(
                driver = reopenedDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            assertContentEquals(body, reopened.read(receipt.reference))
            assertEquals("file-backed-store", reopened.storeInstanceId)
            reopenedDriver.close()
        }
    }

    @Test
    fun legacyInlineBodyMigratesInBoundedRestartSafeSlicesAndClearsPayloadLast() {
        withDatabase("content-inline-body-migration") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val body = ByteArray(160 * 1024 + 37) { index -> (index * 31).toByte() }
            val legacyDriver = driver(database)
            val legacy = SqlDriverContentBlobStore(
                driver = legacyDriver,
                blobIdFactory = { BLOB_ID },
                commitTokenFactory = { "legacy-inline-token" },
                storeInstanceIdFactory = { "legacy-inline-store" },
                clock = { 100L },
            )
            val receipt = legacy.put(body, "application/octet-stream")
            assertEquals(body.size.toLong(), scalarLong(legacyDriver,
                "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'"))
            legacyDriver.close()

            val budget = 16 * 1024
            var completed = false
            var slices = 0
            while (!completed && slices < 64) {
                val sliceDriver = driver(database)
                val reopened = SqlDriverContentBlobStore(
                    driver = sliceDriver,
                    blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
                )
                // Opening the production store must not enumerate/delete staging or load payload.
                val result = reopened.runStorageMaintenanceSlice(
                    ContentBlobStorageMaintenanceRequest(
                        nowEpochMillis = 1_000L + slices,
                        minimumAgeMillis = 0L,
                        maximumBytes = budget,
                        maximumFiles = 1,
                    ),
                )
                assertTrue(result.migratedInlineBytes in 0..budget)
                completed = result.completedInlineMigrations == 1
                val inlineLength = scalarLong(sliceDriver,
                    "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'")
                if (completed) assertEquals(0L, inlineLength) else assertEquals(body.size.toLong(), inlineLength)
                val stagedName = if (!completed && slices == 0) {
                    scalarString(sliceDriver,
                        "SELECT staged_name FROM content_blob_file_migrations WHERE blob_id = '$BLOB_ID'")
                } else {
                    null
                }
                sliceDriver.close() // every slice is interruptible at a durable restart boundary
                if (stagedName != null) {
                    // Models process death after filesystem append but before the matching offset
                    // transaction. The next slice must truncate to the durable offset and resume.
                    Files.write(
                        blobRoot.resolve("staging").resolve(stagedName),
                        "uncommitted-tail".encodeToByteArray(),
                        StandardOpenOption.APPEND,
                    )
                }
                slices++
            }

            assertTrue(completed)
            assertTrue(slices > 2)
            val objectPath = blobRoot.resolve("objects/v1-$BLOB_ID.blob")
            assertTrue(Files.isRegularFile(objectPath))
            assertContentEquals(body, Files.readAllBytes(objectPath))

            val finalDriver = driver(database)
            val finalStore = SqlDriverContentBlobStore(
                driver = finalDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            assertEquals("legacy-inline-store", finalStore.storeInstanceId)
            assertContentEquals(body, finalStore.read(receipt.reference))
            val idempotent = finalStore.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(10_000L, 0L, budget, 1),
            )
            assertEquals(0, idempotent.completedInlineMigrations)
            assertEquals(0L, scalarLong(finalDriver,
                "SELECT count(*) FROM content_blob_file_migrations"))
            assertEquals(0L, scalarLong(finalDriver,
                "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'"))
            finalDriver.close()
        }
    }

    @Test
    fun atomicMigrationFileIsProtectedWhileInlineRowStillOwnsRecovery() {
        withDatabase("content-inline-body-atomic-window") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val body = "legacy body survives the move/update crash window".encodeToByteArray()
            val legacyDriver = driver(database)
            val legacy = SqlDriverContentBlobStore(
                driver = legacyDriver,
                blobIdFactory = { BLOB_ID },
                storeInstanceIdFactory = { "atomic-window-store" },
            )
            val receipt = legacy.put(body, "text/plain")
            legacyDriver.close()

            val moveDriver = driver(database)
            val moveStore = SqlDriverContentBlobStore(
                driver = moveDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            val moved = moveStore.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(100L, 0L, 64 * 1024, 8),
            )
            assertEquals(body.size, moved.migratedInlineBytes)
            assertEquals(0, moved.completedInlineMigrations)
            assertTrue(Files.isRegularFile(blobRoot.resolve("objects/v1-$BLOB_ID.blob")))
            assertEquals(body.size.toLong(), scalarLong(moveDriver,
                "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'"))
            moveDriver.close() // crash after durable atomic move, before inline payload deletion

            val verifyDriver = driver(database)
            val verifyStore = SqlDriverContentBlobStore(
                driver = verifyDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            val verified = verifyStore.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(200L, 0L, 64 * 1024, 8),
            )
            assertEquals(1, verified.completedInlineMigrations)
            assertEquals(0L, scalarLong(verifyDriver,
                "SELECT length(payload) FROM content_blobs WHERE blob_id = '$BLOB_ID'"))
            assertContentEquals(body, verifyStore.read(receipt.reference))
            verifyDriver.close()
        }
    }

    @Test
    fun largeInlineReadLeaseSurvivesRepresentationMigrationWithoutMaterializingTheBody() {
        withDatabase("content-inline-reader-migration") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val body = ByteArray(96 * 1024 + 11) { index -> (index * 17).toByte() }
            val legacyDriver = driver(database)
            val legacy = SqlDriverContentBlobStore(
                driver = legacyDriver,
                blobIdFactory = { BLOB_ID },
            )
            val receipt = legacy.put(body, "application/octet-stream")
            legacyDriver.close()

            val sqlDriver = driver(database)
            val store = SqlDriverContentBlobStore(
                driver = sqlDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            val lease = assertNotNull(store.openRead(receipt.reference))
            store.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(100L, 0L, body.size, 1),
            )
            val completed = store.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(101L, 0L, body.size, 1),
            )
            assertEquals(1, completed.completedInlineMigrations)

            val chunks = mutableListOf<ByteArray>()
            while (true) chunks += lease.readChunk(7 * 1024) ?: break
            lease.close()
            val restored = ByteArray(chunks.sumOf(ByteArray::size))
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(restored, offset); offset += chunk.size }
            assertContentEquals(body, restored)
            sqlDriver.close()
        }
    }

    @Test
    fun unknownPublishedAndStagingCrashFilesRequirePersistedDiscoveryBeforeBoundedGc() {
        withDatabase("content-unknown-file-recovery") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val firstDriver = driver(database)
            SqlDriverContentBlobStore(
                driver = firstDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
            )
            firstDriver.close()

            val unknownObject = blobRoot.resolve("objects/v1-$UNKNOWN_BLOB_ID.blob")
            val abandonedStage = blobRoot.resolve("staging/stage-$STAGE_BLOB_ID.tmp")
            Files.write(unknownObject, "moved before metadata commit".encodeToByteArray())
            Files.write(abandonedStage, "process died before seal".encodeToByteArray())

            fun slice(now: Long): ContentBlobStorageMaintenanceResult {
                val sqlDriver = driver(database)
                val store = SqlDriverContentBlobStore(
                    driver = sqlDriver,
                    blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
                )
                // Construction itself must leave both crash artifacts untouched.
                assertTrue(Files.exists(unknownObject) || Files.exists(abandonedStage))
                val result = store.runStorageMaintenanceSlice(
                    ContentBlobStorageMaintenanceRequest(now, 10L, 1, 1),
                )
                sqlDriver.close()
                return result
            }

            val objectDiscovery = slice(100L)
            assertEquals(1, objectDiscovery.scannedFiles)
            assertEquals(1, objectDiscovery.discoveredUnknownFiles)
            assertTrue(Files.exists(unknownObject))
            val stageDiscovery = slice(100L)
            assertEquals(1, stageDiscovery.discoveredUnknownFiles)
            assertTrue(Files.exists(abandonedStage))

            val objectSweep = slice(110L)
            assertEquals(1, objectSweep.removedUnknownFiles)
            assertFalse(Files.exists(unknownObject))
            val stageSweep = slice(110L)
            assertEquals(1, stageSweep.removedUnknownFiles)
            assertFalse(Files.exists(abandonedStage))
        }
    }

    @Test
    fun backgroundCrashScanNeverDeletesAnActiveStage() {
        withDatabase("content-active-stage-recovery") { database ->
            val blobRoot = database.parent.resolve("private-bodies")
            val sqlDriver = driver(database)
            val store = SqlDriverContentBlobStore(
                driver = sqlDriver,
                blobDirectoryPath = blobRoot.toAbsolutePath().toString(),
                blobIdFactory = { BLOB_ID },
            )
            val body = "foreground stage remains authoritative".encodeToByteArray()
            val stage = store.beginStage(body.size.toLong(), "text/plain")
            stage.append(body)

            // First page visits objects; second visits staging and must recognize the live handle.
            store.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(100L, 0L, 1, 8),
            )
            val scan = store.runStorageMaintenanceSlice(
                ContentBlobStorageMaintenanceRequest(100L, 0L, 1, 8),
            )
            assertEquals(0, scan.discoveredUnknownFiles)
            assertEquals(0, scan.removedUnknownFiles)

            val receipt = store.publish(stage.seal())
            assertContentEquals(body, store.read(receipt.reference))
            sqlDriver.close()
        }
    }

    private fun epubRestartEntries(): List<EpubArchiveEntry> {
        fun entry(path: String, text: String): EpubArchiveEntry {
            val bytes = text.trimIndent().encodeToByteArray()
            return EpubArchiveEntry(path, bytes, bytes.size.toLong().coerceAtLeast(1))
        }
        return listOf(
            entry("mimetype", "application/epub+zip"),
            entry(
                "META-INF/container.xml",
                """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OPS/package.opf"
                        media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                """,
            ),
            entry(
                "OPS/package.opf",
                """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0"
                      unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">urn:uuid:restart-epub</dc:identifier>
                        <dc:title>Restart EPUB</dc:title><dc:language>en</dc:language>
                        <meta property="rendition:layout">reflowable</meta>
                      </metadata>
                      <manifest>
                        <item id="chapter" href="Text/chapter.xhtml" media-type="application/xhtml+xml"
                          fallback="fallback" media-overlay="overlay"/>
                        <item id="fallback" href="Text/fallback.xhtml" media-type="application/xhtml+xml"/>
                        <item id="overlay" href="Audio/chapter.smil" media-type="application/smil+xml"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="css" href="Styles/main.css" media-type="text/css"/>
                        <item id="theme" href="Styles/theme.css" media-type="text/css"/>
                      </manifest>
                      <spine toc="ncx" page-progression-direction="rtl">
                        <itemref idref="chapter"
                          properties="rendition:layout-pre-paginated page-spread-left"/>
                      </spine>
                    </package>
                """,
            ),
            entry("OPS/Text/chapter.xhtml", "<html><body><p>Chapter</p></body></html>"),
            entry("OPS/Text/fallback.xhtml", "<html><body><p>Fallback</p></body></html>"),
            entry(
                "OPS/nav.xhtml",
                """
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <nav epub:type="toc"><a href="Text/chapter.xhtml#start">Start</a></nav>
                    </body></html>
                """,
            ),
            entry(
                "OPS/toc.ncx",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                      "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap><navPoint id="start"><navLabel><text>NCX Start</text></navLabel>
                        <content src="Text/chapter.xhtml#start"/></navPoint></navMap>
                    </ncx>
                """,
            ),
            entry("OPS/Audio/chapter.smil", "<smil><body><seq/></body></smil>"),
            entry("OPS/Styles/main.css", "@import url('theme.css');"),
            entry("OPS/Styles/theme.css", "body { line-height: 1.4; }"),
        )
    }

    private fun transactionStore(driver: JdbcSqliteDriver, blobs: ContentBlobStore) =
        SqlDriverContentTransactionStore(
            driver,
            blobs,
            RestartDraftAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
            ownsDriver = false,
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
                    canonicalUtf16Length = 12,
                    blocks = listOf(TextBlock("body", 0, 12)),
                ),
            ),
        )
        val grantRef = RightsGrantRef(GRANT_ID)
        val publication = Publication(
            key = publicationKey,
            title = "Durable publication",
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

    private fun driver(database: Path): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:$database")

    private fun scalarLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        mapper = { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value

    private fun scalarString(driver: JdbcSqliteDriver, sql: String): String = driver.executeQuery(
        null,
        sql,
        mapper = { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getString(0)))
        },
        parameters = 0,
    ).value

    private fun withDatabase(prefix: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory.resolve("content.sqlite"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class RecordingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        private val queriedSql = mutableListOf<String>()

        val contentBlobPayloadQueryCount: Int
            get() = queriedSql.count { sql ->
                val normalized = sql.lowercase().replace(Regex("\\s+"), " ").trim()
                val projection = normalized.substringBefore(" from content_blobs", missingDelimiterValue = "")
                projection.startsWith("select ") &&
                    (projection.contains('*') || Regex("\\bpayload\\b").containsMatchIn(projection))
            }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            queriedSql += sql
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private data class PublicationFixture(
        val publication: Publication,
        val attachment: ManifestAttachment,
        val grant: RightsGrant,
    )

    private companion object {
        const val PUBLICATION_ID = "11111111-1111-4111-8111-111111111111"
        const val ACQUISITION_ID = "22222222-2222-4222-8222-222222222222"
        const val UNIT_ID = "33333333-3333-4333-8333-333333333333"
        const val MANIFEST_ID = "44444444-4444-4444-8444-444444444444"
        const val REPRESENTATION_ID = "55555555-5555-4555-8555-555555555555"
        const val GRANT_ID = "66666666-6666-4666-8666-666666666666"
        const val BLOB_ID = "77777777-7777-4777-8777-777777777777"
        const val ORPHAN_BLOB_ID = "88888888-8888-4888-8888-888888888888"
        const val UNKNOWN_BLOB_ID = "99999999-9999-4999-8999-999999999999"
        const val STAGE_BLOB_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
