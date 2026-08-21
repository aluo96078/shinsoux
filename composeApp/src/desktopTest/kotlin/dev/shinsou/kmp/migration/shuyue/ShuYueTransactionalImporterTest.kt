package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.ContentOutboxAdapter
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ContentTransactionFailurePoint
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.sync.v2.BlobReferenceCommitV2
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncEntityType
import dev.shinsou.kmp.sync.v2.SyncReducer
import dev.shinsou.kmp.sync.v2.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ShuYueTransactionalImporterTest {
    @Test
    fun offlineStoreDenialPreflightsAllWritesBeforeBodiesOrQuarantineArePublished() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
        )
        val authorizer = RecordingOfflineStoreAuthorizer(denyPreflightAt = 1)
        val importer = importer(blobStore, store, syncActive = false, authorizer = authorizer)

        assertFailsWith<ContentOperationDeniedException> { importer.import(prepared) }

        assertEquals(1, authorizer.preflightRequests.size)
        assertTrue(authorizer.executionRequests.isEmpty())
        assertEquals(0, blobStore.currentGeneration)
        assertEquals(0, blobStore.pendingReceiptCount)
        assertTrue(store.state.publications.isEmpty())
        assertTrue(store.state.quarantines.isEmpty())
    }

    @Test
    fun everyBodyPublishUsesItsExactManifestScopeAndByteCount() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
        )
        val authorizer = RecordingOfflineStoreAuthorizer()

        importer(blobStore, store, syncActive = false, authorizer = authorizer).import(prepared)

        assertEquals(3, authorizer.preflightRequests.size)
        assertEquals(authorizer.preflightRequests, authorizer.executionRequests)
        authorizer.executionRequests.forEach { request ->
            val unitId = assertNotNull(request.scope.unitId)
            val manifestId = assertNotNull(request.scope.manifestId)
            val revision = assertNotNull(request.scope.contentRevision)
            val publication = assertNotNull(store.state.publications[request.scope.publicationId])
            val acquisition = publication.acquisitions.single { it.id == request.scope.acquisitionId }
            val unit = acquisition.units.single { it.key == unitId }
            val manifest = unit.manifestRevisions.single {
                it.manifestId == manifestId && it.contentRevision == revision
            }
            val attachment = ManifestAttachment(
                ContentManifestOwner(publication.key, acquisition.id, unit.key),
                manifest,
            )
            assertEquals(attachment.blobs.single().byteSize, request.byteCount)
        }
    }

    @Test
    fun completeImportCommitsBodiesPublicationsRightsQuarantineAndLedgerAtomically() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
        )
        val importer = importer(blobStore, store, syncActive = false)

        val imported = importer.import(prepared)

        assertFalse(imported.replayed)
        assertEquals(3, imported.publicationCount)
        assertEquals(3, imported.unitCount)
        assertEquals(3, store.state.publications.size)
        assertEquals(3, store.state.rightsGrants.size)
        assertEquals(3, store.state.quarantines.size)
        assertEquals(setOf(1), store.state.quarantines.values.map { it.versionCode }.toSet())
        assertEquals(1, store.state.migrations.size)
        assertEquals(0, blobStore.pendingReceiptCount)
        assertTrue(store.state.metadata.keys.any { it.startsWith("migration.shuyue.category.") })
        assertEquals(
            imported.publicationCount,
            store.state.metadata.keys.count {
                it.startsWith(SHUYUE_CATEGORY_MEMBERSHIP_METADATA_PREFIX)
            },
        )
        assertTrue(store.state.metadata.keys.any { it.startsWith("migration.shuyue.progress.") })
        assertTrue(store.state.metadata.keys.any { it.contains("legacy-flattened") })

        val remote = store.state.publications.values.single { publication ->
            publication.acquisitions.single().origin is AcquisitionOrigin.ExtensionSource
        }
        val remoteOrigin = remote.acquisitions.single().origin as AcquisitionOrigin.ExtensionSource
        assertEquals(
            SourceKey(2, "zh.wenku8.api", "zh.wenku8.api"),
            remoteOrigin.sourceBinding.sourceKey,
        )
        assertEquals(
            remoteOrigin.sourceBinding.sourceKey,
            remote.acquisitions.single().units.single().sourceBinding?.sourceKey,
        )

        val replay = importer.import(prepared)
        assertTrue(replay.replayed)
        assertEquals(3, store.state.publications.size)
        assertEquals(3, store.state.quarantines.size)
    }

    @Test
    fun activeSyncFailsClosedWhenOutboxDoesNotCoverSelectedDomains() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val importer = importer(blobStore, store, syncActive = true)

        assertFailsWith<IllegalArgumentException> { importer.import(prepared) }
        assertTrue(store.state.publications.isEmpty())
        assertTrue(store.state.migrations.isEmpty())
        assertTrue(blobStore.pendingReceiptCount > 0)
    }

    @Test
    fun productionV2FactoryCommitsTypedDraftsMembershipsAndExactBodyJobs() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val importer = ShuYueTransactionalImporter(
            blobStore = blobStore,
            transactionStore = store,
            syncActive = { true },
            outboxFactory = ShuYueSyncV2OutboxFactory,
            offlineStoreAuthorizer = offlineStoreAuthorizer(store),
        )

        val result = importer.import(
            prepared,
            ShuYueImportSelection(includeContentBodySync = true),
        )

        assertTrue(store.state.outbox.isNotEmpty())
        assertTrue(store.state.blobSyncJobs.isNotEmpty())
        assertEquals(store.state.blobSyncJobs.keys.toList(), result.commit.blobSyncJobIds)
        assertEquals(
            store.state.blobSyncJobs.values.map { it.blob.blobId }.toSet().size,
            store.state.blobSyncJobs.size,
        )
        assertTrue(store.state.outbox.flatMap { it.event.mutations }.none { it is BlobReferenceCommitV2 })

        val replica = store.state.outbox.fold(SyncState()) { state, draft ->
            SyncReducer.reduce(state, draft.event)
        }
        assertEquals(
            result.publicationCount,
            replica.entities.keys.count { it.entityType == SyncEntityType.PUBLICATION },
        )
        assertEquals(result.publicationCount, replica.contentCategoryMemberships.size)
        assertEquals(result.progressCount, replica.contentReadingProgress.size)
        assertTrue(replica.portableSettings.isNotEmpty())
    }

    @Test
    fun productionV2FactoryDoesNotQueueBodyJobsWithoutExplicitOptIn() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val importer = ShuYueTransactionalImporter(
            blobStore = blobStore,
            transactionStore = store,
            syncActive = { true },
            outboxFactory = ShuYueSyncV2OutboxFactory,
            offlineStoreAuthorizer = offlineStoreAuthorizer(store),
        )

        val result = importer.import(prepared)

        assertTrue(store.state.outbox.isNotEmpty())
        assertTrue(store.state.blobSyncJobs.isEmpty())
        assertTrue(result.commit.blobSyncJobIds.isEmpty())
    }

    @Test
    fun injectedTransactionFailureLeavesNoMetadataOrDanglingCommittedReference() {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
        ).also { it.failureInjection = ContentTransactionFailurePoint.AFTER_QUARANTINE_WRITE }

        val authority = InMemoryRightsAuthority()
        val authorizer = RecordingOfflineStoreAuthorizer(offlineStoreAuthorizer(store, authority))
        val importer = importer(blobStore, store, syncActive = false, authorizer = authorizer)

        assertFailsWith<IllegalStateException> { importer.import(prepared) }
        assertTrue(store.state.publications.isEmpty())
        assertTrue(store.state.quarantines.isEmpty())
        assertTrue(store.state.rightsGrants.isEmpty())
        assertTrue(store.state.migrations.isEmpty())
        assertTrue(blobStore.pendingReceiptCount > 0)
        val firstGeneration = blobStore.currentGeneration
        val provisional = authorizer.executionRequests.first()
        assertNull(
            authority.resolve(
                provisional.grant.grantId,
                provisional.scope,
                nowEpochMillis = 0,
            ),
        )

        store.failureInjection = null
        val retry = importer.import(prepared)
        assertFalse(retry.replayed)
        assertEquals(firstGeneration, blobStore.currentGeneration)
        assertEquals(3, store.state.publications.size)
        assertEquals(3, store.state.quarantines.size)
    }

    @Test
    fun secretImportRequiresProtectedAtomicStore() = runTest {
        val prepared = assertNotNull(ShuYueImportPreparer.prepare(fixture()).preparedImport)
        val consent = prepared.confirmSecretImport(
            credentialSourceIds = setOf("example-source"),
            cookieSourceIds = setOf("example-source"),
            confirmedAtEpochMillis = 10,
        )
        val blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val store = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = { ContentSyncMode.INACTIVE },
        )
        var acceptedBatch: ShuYueSecretWriteBatch? = null
        val protected = object : ShuYueMigrationSecretStore {
            override val protectedAtRest: Boolean = true
            override suspend fun replaceAtomically(batch: ShuYueSecretWriteBatch) {
                acceptedBatch = batch
            }
        }
        val importer = importer(blobStore, store, syncActive = false)

        assertEquals(ShuYueSecretImportResult(1, 1), importer.importSecrets(prepared, consent, protected))
        assertEquals(1, acceptedBatch?.credentials?.size)

        val unsafe = object : ShuYueMigrationSecretStore {
            override val protectedAtRest: Boolean = false
            override suspend fun replaceAtomically(batch: ShuYueSecretWriteBatch) = Unit
        }
        assertFailsWith<IllegalArgumentException> {
            importer.importSecrets(prepared, consent, unsafe)
        }
    }

    private fun importer(
        blobStore: InMemoryContentBlobStore,
        store: InMemorySharedContentTransactionStore<String>,
        syncActive: Boolean,
        authorizer: ContentBodyOfflineStoreAuthorizer = offlineStoreAuthorizer(store),
    ): ShuYueTransactionalImporter<String> = ShuYueTransactionalImporter(
        blobStore = blobStore,
        transactionStore = store,
        syncActive = { syncActive },
        outboxFactory = ShuYueImportOutboxFactory {
            ShuYueImportOutboxBundle(emptyList(), emptySet())
        },
        offlineStoreAuthorizer = authorizer,
    )

    private fun <D : Any> offlineStoreAuthorizer(
        store: InMemorySharedContentTransactionStore<D>,
        authority: InMemoryRightsAuthority = InMemoryRightsAuthority(),
    ) = HostContentBodyOfflineStoreAuthorizer(
        authority = authority,
        durableGrant = { reference -> store.state.rightsGrants[reference] },
        nowEpochMillis = { 0 },
    )

    private class RecordingOfflineStoreAuthorizer(
        private val delegate: ContentBodyOfflineStoreAuthorizer = PassThroughOfflineStoreAuthorizer,
        private val denyPreflightAt: Int? = null,
    ) : ContentBodyOfflineStoreAuthorizer {
        val preflightRequests = mutableListOf<PendingContentBodyStoreRequest>()
        val executionRequests = mutableListOf<PendingContentBodyStoreRequest>()

        override fun requireAllowed(request: PendingContentBodyStoreRequest) {
            preflightRequests += request
            if (preflightRequests.size == denyPreflightAt) {
                throw ContentOperationDeniedException(dev.shinsou.kmp.rights.ContentOperation.OFFLINE_STORE)
            }
            delegate.requireAllowed(request)
        }

        override fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T {
            executionRequests += request
            return delegate.execute(request, block)
        }
    }

    private object PassThroughOfflineStoreAuthorizer : ContentBodyOfflineStoreAuthorizer {
        override fun requireAllowed(request: PendingContentBodyStoreRequest) = Unit
        override fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T = block()
    }

    private fun fixture(): ByteArray {
        val path = "shuyue-migration/valid-v1.json"
        return requireNotNull(javaClass.classLoader.getResourceAsStream(path)).use { it.readBytes() }
    }

    private object StringOutboxAdapter : ContentOutboxAdapter<String> {
        override fun validate(draft: String) = require(draft.isNotBlank())
        override fun id(draft: String): String = draft.substringBefore(':')
        override fun fingerprint(draft: String): ByteArray = draft.encodeToByteArray()
        override fun isRepresentableByCurrentV1(draft: String): Boolean = false
    }
}
