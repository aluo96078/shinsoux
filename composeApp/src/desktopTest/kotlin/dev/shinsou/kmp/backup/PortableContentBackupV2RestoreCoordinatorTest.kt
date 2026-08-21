package dev.shinsou.kmp.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentAliasMutation
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.content.ContentPortableAuxiliaryState
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentTransactionException
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsConstraint
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.AcquisitionPatchV2
import dev.shinsou.kmp.sync.v2.BlobReferencePresenceSetV2
import dev.shinsou.kmp.sync.v2.CloudflareSnapshotReplacementGuard
import dev.shinsou.kmp.sync.v2.ContentAnnotationPatchV2
import dev.shinsou.kmp.sync.v2.ContentManifestPatchV2
import dev.shinsou.kmp.sync.v2.ContentSyncFields
import dev.shinsou.kmp.sync.v2.EntityPresenceSet
import dev.shinsou.kmp.sync.v2.InMemorySyncSessionStore
import dev.shinsou.kmp.sync.v2.PublicationPatchV2
import dev.shinsou.kmp.sync.v2.PublicationUnitPatchV2
import dev.shinsou.kmp.sync.v2.PersistentLocalSyncStore
import dev.shinsou.kmp.sync.v2.RepositorySyncBridge
import dev.shinsou.kmp.sync.v2.SyncPortableIdGenerator
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncWorkspaceDeparture
import dev.shinsou.kmp.sync.persistence.SqlDriverSyncStatePersistence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class PortableContentBackupV2RestoreCoordinatorTest {
    @Test
    fun missingOfflineStoreGrantDeniesBeforeBodyPublishOrWorkspaceDeparture() = runTest {
        val archive = archiveFixture(
            allowedOperations = DEFAULT_ALLOWED_OPERATIONS - ContentOperation.OFFLINE_STORE,
        )
        withRuntime("backup-v2-offline-denied") { _, foundation ->
            val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
            var departed = false
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = ShinsouRepository(),
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { departed = true },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            assertFailsWith<ContentOperationDeniedException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertFalse(departed)
            assertFalse(foundation.blobStore.contains(archive.blobReference))
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.rightsGrants.all().isEmpty())
        }
    }

    @Test
    fun maxOfflineBytesDeniesTheExactArchiveBodyBeforePublication() = runTest {
        val bodySize = "hello portable coordinator".encodeToByteArray().size.toLong()
        val archive = archiveFixture(
            constraints = setOf(RightsConstraint.MaxOfflineBytes(bodySize - 1)),
        )
        withRuntime("backup-v2-offline-byte-limit") { _, foundation ->
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = ShinsouRepository(),
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            assertFailsWith<ContentOperationDeniedException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertFalse(foundation.blobStore.contains(archive.blobReference))
            assertTrue(foundation.publications.all().isEmpty())
        }
    }

    @Test
    fun bodyPublishIsWrappedByTheExactManifestScopeAndDescriptorByteCount() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-exact-offline-scope") { _, foundation ->
            val authorizer = RecordingOfflineStoreAuthorizer(offlineStoreAuthorizer(foundation))
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = ShinsouRepository(),
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = authorizer,
            )

            coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            val preflight = authorizer.preflightRequests.single()
            assertEquals(listOf(preflight), authorizer.executionRequests)
            assertEquals(archive.body.size.toLong(), preflight.byteCount)
            assertEquals(PUBLICATION_ID, preflight.scope.publicationId.value)
            assertEquals(ACQUISITION_ID, preflight.scope.acquisitionId)
            assertEquals(UNIT_ID, preflight.scope.unitId?.value)
            assertEquals(MANIFEST_ID, preflight.scope.manifestId)
            assertEquals(1L, preflight.scope.contentRevision)
        }
    }

    @Test
    fun freshStoreRestoreCommitsPortableProjectionAnnotationsRightsAndBody() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-fresh") { _, foundation ->
            val repository = ShinsouRepository()
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            val result = coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            assertEquals(archive.state.legacySnapshot.mangas, repository.currentSnapshot.mangas)
            assertEquals(archive.state.legacySnapshot.chapters, repository.currentSnapshot.chapters)
            assertEquals(archive.state.publications, foundation.publications.all())
            assertEquals(archive.state.annotations, foundation.annotations.list(includeTombstones = true))
            assertEquals(archive.state.rightsGrants, foundation.rightsGrants.all())
            assertEquals(archive.state.auxiliary, foundation.portableAuxiliaryState())
            assertContentEquals(archive.body, foundation.blobStore.read(archive.blobReference))
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty())
            assertEquals(1, result.publicationCount)
            assertEquals(1, result.annotationCount)
            assertEquals(1, result.contentBlobCount)
        }
    }

    @Test
    fun randomAccessSourceRestoreKeepsTheEncodedArchiveChunked() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-random-access-source") { _, foundation ->
            val repository = ShinsouRepository()
            val source = RecordingArchiveSource(archive.encoded)
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            val result = coordinator.restore(
                expectedInspection = archive.inspection,
                archiveSource = source,
                target = SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            assertTrue(source.readSizes.size > 1, "Restore must not materialize the whole archive in one read")
            assertTrue(source.readSizes.all { it in 1..RESTORE_SOURCE_MAX_READ_BYTES })
            assertEquals(1, result.contentBlobCount)
            assertEquals(archive.state.publications, foundation.publications.all())
            assertContentEquals(archive.body, foundation.blobStore.read(archive.blobReference))
        }
    }

    @Test
    fun metadataOnlyRestoreKeepsManifestRefsWithoutCommittingDanglingLocalBodies() = runTest {
        val archive = archiveFixture(includeContentBlobs = false)
        withRuntime("backup-v2-metadata-only") { _, foundation ->
            val authorizer = RecordingOfflineStoreAuthorizer(offlineStoreAuthorizer(foundation))
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = ShinsouRepository(),
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = authorizer,
            )

            val result = coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            val restored = foundation.publications.all().single()
            val acquisition = restored.acquisitions.single()
            val manifest = acquisition.units.single().manifestRevisions.single()
            assertEquals(AcquisitionAvailability.PARTIAL, acquisition.availability)
            assertEquals(listOf(archive.blobReference), manifest.referencedBlobs)
            assertFalse(foundation.blobStore.contains(archive.blobReference))
            assertNull(
                foundation.blobStore.attached(
                    ContentManifestOwner(restored.key, acquisition.id, acquisition.units.single().key),
                    manifest,
                ),
            )
            assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty())
            assertTrue(authorizer.preflightRequests.isEmpty())
            assertTrue(authorizer.executionRequests.isEmpty())
            assertEquals(0, result.contentBlobCount)
        }
    }

    @Test
    fun restoreAtomicallyReplacesAConflictingPortableGraph() = runTest {
        val first = archiveFixture(publicationTitle = "Before replacement")
        val replacement = archiveFixture(publicationTitle = "After replacement")
        withRuntime("backup-v2-conflicting-replacement") { _, foundation ->
            val repository = ShinsouRepository()
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            coordinator.restore(first.inspection, first.encoded, SnapshotRestoreTarget.THIS_DEVICE_ONLY)
            coordinator.restore(
                replacement.inspection,
                replacement.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            assertEquals(listOf("After replacement"), foundation.publications.all().map(Publication::title))
            assertEquals(replacement.state.rightsGrants, foundation.rightsGrants.all())
            assertContentEquals(
                replacement.body,
                foundation.blobStore.read(replacement.blobReference),
            )
            assertEquals(
                replacement.state.legacySnapshot.mangas,
                repository.currentSnapshot.mangas,
            )
        }
    }

    @Test
    fun conflictingAuxiliaryAuthorityRollsBackThePortableGraphReplacement() = runTest {
        val first = archiveFixture(
            publicationTitle = "Before auxiliary conflict",
            auxiliaryCategoryName = "Novel",
        )
        val conflicting = archiveFixture(
            publicationTitle = "Must not become visible",
            auxiliaryCategoryName = "Conflicting category",
        )
        withRuntime("backup-v2-auxiliary-conflict") { _, foundation ->
            val repository = ShinsouRepository()
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = InMemorySyncSessionStore(),
                workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )
            coordinator.restore(first.inspection, first.encoded, SnapshotRestoreTarget.THIS_DEVICE_ONLY)

            assertFailsWith<ContentTransactionException.CommitConflict> {
                coordinator.restore(
                    conflicting.inspection,
                    conflicting.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertEquals(listOf("Before auxiliary conflict"), foundation.publications.all().map(Publication::title))
            assertEquals(first.state.legacySnapshot.mangas, repository.currentSnapshot.mangas)
            assertEquals(first.state.auxiliary, foundation.portableAuxiliaryState())
        }
    }

    @Test
    fun readyCloudflareRestoreWritesCompleteSchemaV2OutboxAndBlobJob() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-ready-sync") { driver, foundation ->
            val repository = ShinsouRepository()
            val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
            createJournalTable(driver)
            repository.configureSyncMutationBoundary(
                observer = journalObserver(driver, archive.inspection.envelope.manifestSha256),
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { error("Synchronized restore must not leave") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
            )

            val mutations = foundation.transactions.pendingOutbox().flatMap { it.event.mutations }
            assertTrue(mutations.any { it is PublicationPatchV2 })
            assertTrue(mutations.any { it is AcquisitionPatchV2 })
            assertTrue(mutations.any { it is PublicationUnitPatchV2 })
            assertTrue(mutations.any { it is ContentManifestPatchV2 })
            assertTrue(mutations.any { it is ContentAnnotationPatchV2 })
            assertTrue(mutations.filterIsInstance<AcquisitionPatchV2>().any {
                ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_SHA256 in it.fields
            })
            assertTrue(mutations.filterIsInstance<ContentManifestPatchV2>().any {
                ContentSyncFields.Manifest.DOCUMENT_SHA256 in it.fields
            })
            assertEquals(1, foundation.transactions.pendingBlobSyncJobs().size)
            assertEquals(1, scalarLong(driver, "SELECT COUNT(*) FROM test_sync_journal"))
        }
    }

    @Test
    fun synchronizedReplacementTombstonesEveryRemovedPortableEntityAndBlobReference() = runTest {
        val original = archiveFixture()
        val emptyArchive = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(),
            operationGate = AllowGate,
            createdAtEpochMillis = 20,
        ).let { created ->
            val encoded = BackupV2BinaryCodec.encode(created.archive)
            PortableContentBackupV2Service.inspect(created.archive) to encoded
        }

        withRuntime("backup-v2-replacement-tombstones") { driver, foundation ->
            val repository = ShinsouRepository()
            val sessions = InMemorySyncSessionStore()
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { error("Synchronized restore must not leave") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )
            coordinator.restore(
                original.inspection,
                original.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )
            sessions.save(session(SyncSessionStatus.READY))
            createJournalTable(driver)
            repository.configureSyncMutationBoundary(
                observer = journalObserver(driver, emptyArchive.first.envelope.manifestSha256),
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )

            coordinator.restore(
                emptyArchive.first,
                emptyArchive.second,
                SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
            )

            val mutations = foundation.transactions.pendingOutbox().flatMap { it.event.mutations }
            val removedEntityTypes = mutations.filterIsInstance<EntityPresenceSet>()
                .filter { !it.present }
                .mapTo(hashSetOf()) { it.key.entityType }
            assertTrue(dev.shinsou.kmp.sync.v2.SyncEntityType.PUBLICATION in removedEntityTypes)
            assertTrue(dev.shinsou.kmp.sync.v2.SyncEntityType.ACQUISITION in removedEntityTypes)
            assertTrue(dev.shinsou.kmp.sync.v2.SyncEntityType.PUBLICATION_UNIT in removedEntityTypes)
            assertTrue(dev.shinsou.kmp.sync.v2.SyncEntityType.CONTENT_MANIFEST in removedEntityTypes)
            assertEquals(
                listOf(BlobReferencePresenceSetV2(original.blobReference.blobId, false)),
                mutations.filterIsInstance<BlobReferencePresenceSetV2>(),
            )
            val annotationTombstone = mutations.filterIsInstance<ContentAnnotationPatchV2>()
                .let { patches ->
                    dev.shinsou.kmp.sync.v2.SyncReducer.reduce(
                        dev.shinsou.kmp.sync.v2.SyncState(),
                        dev.shinsou.kmp.sync.v2.SyncEvent(
                            opId = "replacement-tombstone-test",
                            hlc = dev.shinsou.kmp.sync.v2.HlcTimestamp(1, 0, "device"),
                            mutations = patches,
                        ),
                    ).contentAnnotations[ANNOTATION_ID]?.annotation?.value
                }
            assertEquals(ContentAnnotationState.TOMBSTONE, annotationTombstone?.state)
            assertNull(annotationTombstone?.body)
            assertNull(annotationTombstone?.range?.quote)
            assertTrue(foundation.publications.all().isEmpty())
        }
    }

    @Test
    fun configuredButUnreadyCloudflareFailsBeforePublishingOrCommitting() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-unready-sync") { _, foundation ->
            val repository = ShinsouRepository()
            val before = repository.currentSnapshot
            val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.LINKING))
            var departed = false
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { departed = true },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            assertFailsWith<IllegalArgumentException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
                )
            }

            assertEquals(before, repository.currentSnapshot)
            assertFalse(departed)
            assertFalse(foundation.blobStore.contains(archive.blobReference))
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.annotations.list(includeTombstones = true).isEmpty())
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
        }
    }

    @Test
    fun bodyStagingFailureAfterDurablePublishKeepsWorkspaceAndReceiptForRetry() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-stage-before-departure") { _, foundation ->
            val repository = ShinsouRepository()
            val activeSession = session(SyncSessionStatus.READY)
            val sessions = InMemorySyncSessionStore(activeSession)
            repository.configureSyncMutationBoundary(
                observer = null,
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            var departureCalls = 0
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture {
                    departureCalls += 1
                    sessions.clear()
                },
                offlineStoreAuthorizer = FailOnceAfterBodyPublishAuthorizer(
                    offlineStoreAuthorizer(foundation),
                ),
            )
            val before = repository.currentSnapshot

            assertFailsWith<InjectedBodyStagingFailure> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertEquals(activeSession, sessions.load())
            assertEquals(0, departureCalls)
            assertEquals(before, repository.currentSnapshot)
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.annotations.list(includeTombstones = true).isEmpty())
            assertTrue(foundation.rightsGrants.all().isEmpty())
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            assertTrue(foundation.blobStore.contains(archive.blobReference))
            assertEquals(1, foundation.blobStore.pendingReceiptCount)
            val stagedGeneration = foundation.blobStore.currentGeneration

            coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            assertNull(sessions.load())
            assertEquals(1, departureCalls)
            assertEquals(stagedGeneration, foundation.blobStore.currentGeneration)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(archive.state.publications, foundation.publications.all())
            assertEquals(archive.state.annotations, foundation.annotations.list(includeTombstones = true))
        }
    }

    @Test
    fun workspaceDepartureFailurePreservesSessionDataAndStagedReceiptForRetry() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-departure-failure") { _, foundation ->
            val repository = ShinsouRepository()
            val activeSession = session(SyncSessionStatus.READY)
            val sessions = InMemorySyncSessionStore(activeSession)
            repository.configureSyncMutationBoundary(
                observer = null,
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            val sentinel = archive.state.annotations.single().copy(
                annotationId = SENTINEL_ANNOTATION_ID,
                body = "departure sentinel",
            )
            foundation.annotations.put(sentinel)
            var failDeparture = true
            var departureCalls = 0
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture {
                    departureCalls += 1
                    assertTrue(foundation.blobStore.contains(archive.blobReference))
                    assertEquals(1, foundation.blobStore.pendingReceiptCount)
                    if (failDeparture) throw InjectedWorkspaceDepartureFailure()
                    sessions.clear()
                },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )
            val before = repository.currentSnapshot

            assertFailsWith<InjectedWorkspaceDepartureFailure> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertEquals(activeSession, sessions.load())
            assertEquals(before, repository.currentSnapshot)
            assertEquals(listOf(sentinel), foundation.annotations.list(includeTombstones = true))
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.rightsGrants.all().isEmpty())
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            assertEquals(1, foundation.blobStore.pendingReceiptCount)
            val stagedGeneration = foundation.blobStore.currentGeneration

            failDeparture = false
            coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.THIS_DEVICE_ONLY,
            )

            assertNull(sessions.load())
            assertEquals(2, departureCalls)
            assertEquals(stagedGeneration, foundation.blobStore.currentGeneration)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(archive.state.publications, foundation.publications.all())
            assertEquals(archive.state.annotations, foundation.annotations.list(includeTombstones = true))
        }
    }

    @Test
    fun departureThatLeavesCloudflareActiveCannotBypassTheSyncBoundary() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-departure-postcondition") { _, foundation ->
            val repository = ShinsouRepository()
            val activeSession = session(SyncSessionStatus.READY)
            val sessions = InMemorySyncSessionStore(activeSession)
            repository.configureSyncMutationBoundary(
                observer = null,
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { /* Deliberately violates contract. */ },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )
            val before = repository.currentSnapshot

            val failure = assertFailsWith<IllegalStateException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertTrue(failure.message.orEmpty().contains("did not clear"))
            assertEquals(activeSession, sessions.load())
            assertEquals(before, repository.currentSnapshot)
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.annotations.list(includeTombstones = true).isEmpty())
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            assertEquals(1, foundation.blobStore.pendingReceiptCount)
        }
    }

    @Test
    fun postDepartureAnnotationAndContentFailuresAreTypedRecoverableAndRetryCleanly() = runTest {
        val archive = archiveFixture()
        FAILURE_TRIGGERS.filter { it.name == "annotation" || it.name == "content" }.forEach { failure ->
            withRuntime("backup-v2-device-only-${failure.name}") { driver, foundation ->
                val repository = ShinsouRepository()
                val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
                repository.configureSyncMutationBoundary(
                    observer = null,
                    guard = CloudflareSnapshotReplacementGuard(sessions),
                )
                val sentinel = archive.state.annotations.single().copy(
                    annotationId = SENTINEL_ANNOTATION_ID,
                    body = "pre-departure ${failure.name} sentinel",
                )
                foundation.annotations.put(sentinel)
                var departureCalls = 0
                val coordinator = PortableContentBackupV2RestoreCoordinator(
                    repository = repository,
                    foundation = foundation,
                    sessionStore = sessions,
                    workspaceDeparture = SyncWorkspaceDeparture {
                        departureCalls += 1
                        sessions.clear()
                    },
                    offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
                )
                val before = repository.currentSnapshot
                driver.execute(null, failure.createSql, 0).value

                val recoverable = assertFailsWith<PortableContentBackupV2RecoverableRestoreException> {
                    coordinator.restore(
                        archive.inspection,
                        archive.encoded,
                        SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                    )
                }

                assertEquals(
                    PortableContentBackupV2RestoreFailurePhase
                        .LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
                    recoverable.phase,
                    failure.name,
                )
                assertEquals(
                    PortableContentBackupV2RestoreRecoveryStatus.RETRY_SAME_VERIFIED_ARCHIVE,
                    recoverable.recoveryStatus,
                    failure.name,
                )
                assertEquals(
                    archive.inspection.envelope.manifestSha256,
                    recoverable.archiveManifestSha256,
                    failure.name,
                )
                assertEquals(1, recoverable.stagedContentBlobCount, failure.name)
                assertNotNull(recoverable.cause, failure.name)
                assertNull(sessions.load(), failure.name)
                assertEquals(1, departureCalls, failure.name)
                assertEquals(before, repository.currentSnapshot, failure.name)
                assertEquals(
                    listOf(sentinel),
                    foundation.annotations.list(includeTombstones = true),
                    failure.name,
                )
                assertTrue(foundation.publications.all().isEmpty(), failure.name)
                assertTrue(foundation.rightsGrants.all().isEmpty(), failure.name)
                assertTrue(foundation.transactions.pendingOutbox().isEmpty(), failure.name)
                assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty(), failure.name)
                assertTrue(foundation.blobStore.contains(archive.blobReference), failure.name)
                assertEquals(1, foundation.blobStore.pendingReceiptCount, failure.name)
                val stagedGeneration = foundation.blobStore.currentGeneration

                driver.execute(null, "DROP TRIGGER ${failure.triggerName}", 0).value
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )

                assertEquals(1, departureCalls, failure.name)
                assertEquals(stagedGeneration, foundation.blobStore.currentGeneration, failure.name)
                assertEquals(0, foundation.blobStore.pendingReceiptCount, failure.name)
                assertEquals(archive.state.legacySnapshot.mangas, repository.currentSnapshot.mangas)
                assertEquals(archive.state.publications, foundation.publications.all(), failure.name)
                assertEquals(
                    archive.state.annotations,
                    foundation.annotations.list(includeTombstones = true),
                    failure.name,
                )
            }
        }
    }

    @Test
    fun cancellationAfterWorkspaceDepartureReportsTheTypedRetryBoundary() = runTest {
        val archive = archiveFixture()
        listOf(2, 3).forEach { cancellationLoadCall ->
        withRuntime("backup-v2-device-only-cancelled-$cancellationLoadCall") { _, foundation ->
            val backingSessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
            var loadCalls = 0
            var departed = false
            val sessions = object : SyncSessionStore {
                override suspend fun load(): SyncSession? {
                    loadCalls += 1
                    if (departed && loadCalls >= cancellationLoadCall) {
                        throw CancellationException("cancelled after workspace departure")
                    }
                    return backingSessions.load()
                }

                override suspend fun save(session: SyncSession) = backingSessions.save(session)
                override suspend fun clear() = backingSessions.clear()
            }
            val repository = ShinsouRepository()
            repository.configureSyncMutationBoundary(
                observer = null,
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture {
                    backingSessions.clear()
                    departed = true
                },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            val recoverable = assertFailsWith<PortableContentBackupV2RecoverableRestoreException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertEquals(
                PortableContentBackupV2RestoreFailurePhase.LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
                recoverable.phase,
            )
            assertEquals(
                PortableContentBackupV2RestoreRecoveryStatus.RETRY_SAME_VERIFIED_ARCHIVE,
                recoverable.recoveryStatus,
            )
            assertTrue(recoverable.cause is CancellationException)
            assertNull(backingSessions.load())
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.blobStore.contains(archive.blobReference))
        }
        }
    }

    @Test
    fun cancellationDeliveredByDepartureAfterSessionClearIsTypedRecoverable() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-device-only-departure-cancelled") { _, foundation ->
            val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = ShinsouRepository(),
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture {
                    sessions.clear()
                    throw CancellationException("departure completed before cancellation delivery")
                },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )

            val recoverable = assertFailsWith<PortableContentBackupV2RecoverableRestoreException> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                )
            }

            assertEquals(
                PortableContentBackupV2RestoreFailurePhase.LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
                recoverable.phase,
            )
            assertTrue(recoverable.cause is CancellationException)
            assertNull(sessions.load())
            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.blobStore.contains(archive.blobReference))
        }
    }

    @Test
    fun auxiliaryAnnotationContentAndOutboxFailuresRollbackEveryDurableParticipantAndRetryCleanly() = runTest {
        val archive = archiveFixture()
        FAILURE_TRIGGERS.forEach { failure ->
            withRuntime("backup-v2-${failure.name}") { driver, foundation ->
                val repository = ShinsouRepository()
                val before = repository.currentSnapshot
                val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
                val sentinel = archive.state.annotations.single().copy(
                    annotationId = SENTINEL_ANNOTATION_ID,
                    body = "pre-existing annotation",
                )
                foundation.annotations.put(sentinel)
                createJournalTable(driver)
                repository.configureSyncMutationBoundary(
                    observer = journalObserver(driver, archive.inspection.envelope.manifestSha256),
                    guard = CloudflareSnapshotReplacementGuard(sessions),
                )
                val coordinator = PortableContentBackupV2RestoreCoordinator(
                    repository = repository,
                    foundation = foundation,
                    sessionStore = sessions,
                    workspaceDeparture = SyncWorkspaceDeparture { error("Synchronized restore must not leave") },
                    offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
                )
                driver.execute(null, failure.createSql, 0).value

                assertFailsWith<Throwable> {
                    coordinator.restore(
                        archive.inspection,
                        archive.encoded,
                        SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
                    )
                }

                assertEquals(before, repository.currentSnapshot, failure.name)
                assertEquals(listOf(sentinel), foundation.annotations.list(includeTombstones = true), failure.name)
                assertTrue(foundation.publications.all().isEmpty(), failure.name)
                assertTrue(foundation.rightsGrants.all().isEmpty(), failure.name)
                assertEquals(ContentPortableAuxiliaryState(), foundation.portableAuxiliaryState(), failure.name)
                assertTrue(foundation.transactions.pendingOutbox().isEmpty(), failure.name)
                assertTrue(foundation.transactions.pendingBlobSyncJobs().isEmpty(), failure.name)
                assertEquals(0, scalarLong(driver, "SELECT COUNT(*) FROM test_sync_journal"), failure.name)
                val grant = archive.state.rightsGrants.single()
                val scope = archive.bodyScope()
                assertNull(
                    foundation.rightsAuthority.resolve(grant.grantId, scope, nowEpochMillis = 0),
                    failure.name,
                )
                val publishedGeneration = foundation.blobStore.currentGeneration

                driver.execute(null, "DROP TRIGGER ${failure.triggerName}", 0).value
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
                )

                assertEquals(archive.state.legacySnapshot.mangas, repository.currentSnapshot.mangas, failure.name)
                assertEquals(archive.state.publications, foundation.publications.all(), failure.name)
                assertEquals(archive.state.annotations, foundation.annotations.list(includeTombstones = true), failure.name)
                assertEquals(archive.state.rightsGrants, foundation.rightsGrants.all(), failure.name)
                assertEquals(archive.state.auxiliary, foundation.portableAuxiliaryState(), failure.name)
                assertEquals(publishedGeneration, foundation.blobStore.currentGeneration, failure.name)
                assertNotNull(
                    foundation.rightsAuthority.resolve(grant.grantId, scope, nowEpochMillis = 0),
                    failure.name,
                )
                assertEquals(1, scalarLong(driver, "SELECT COUNT(*) FROM test_sync_journal"), failure.name)
                assertEquals(
                    archive.inspection.envelope.manifestSha256,
                    scalarString(driver, "SELECT journal_id FROM test_sync_journal"),
                    failure.name,
                )
            }
        }
    }

    @Test
    fun processRestartClaimsAnAlreadyPublishedBodyBeforeRetryingMetadataCommit() = runTest {
        val archive = archiveFixture()
        val database = Files.createTempFile("backup-v2-restart-retry", ".sqlite")
        val failure = FailureTrigger(
            name = "restart-content",
            triggerName = "fail_backup_restart_content",
            table = "content_publications",
        )
        var publishedGeneration = -1L
        try {
            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val foundation = ContentFoundationRuntime(driver)
                try {
                    driver.execute(null, failure.createSql, 0).value
                    val repository = ShinsouRepository()
                    val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
                    repository.configureSyncMutationBoundary(
                        observer = null,
                        guard = CloudflareSnapshotReplacementGuard(sessions),
                    )
                    val coordinator = PortableContentBackupV2RestoreCoordinator(
                        repository = repository,
                        foundation = foundation,
                        sessionStore = sessions,
                        workspaceDeparture = SyncWorkspaceDeparture { sessions.clear() },
                        offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
                    )
                    val recoverable = assertFailsWith<PortableContentBackupV2RecoverableRestoreException> {
                        coordinator.restore(
                            archive.inspection,
                            archive.encoded,
                            SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                        )
                    }
                    assertEquals(
                        PortableContentBackupV2RestoreRecoveryStatus.RETRY_SAME_VERIFIED_ARCHIVE,
                        recoverable.recoveryStatus,
                    )
                    assertNull(sessions.load())
                    assertTrue(foundation.blobStore.contains(archive.blobReference))
                    assertTrue(foundation.publications.all().isEmpty())
                    assertEquals(1, foundation.blobStore.pendingReceiptCount)
                    publishedGeneration = foundation.blobStore.currentGeneration
                } finally {
                    foundation.close()
                    driver.close()
                }
            }

            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val foundation = ContentFoundationRuntime(driver)
                try {
                    driver.execute(null, "DROP TRIGGER ${failure.triggerName}", 0).value
                    driver.execute(
                        null,
                        "CREATE TRIGGER fail_backup_payload_rewrite BEFORE UPDATE OF payload ON content_blobs " +
                            "BEGIN SELECT RAISE(ABORT, 'payload rewrite'); END",
                        0,
                    ).value
                    val coordinator = PortableContentBackupV2RestoreCoordinator(
                        repository = ShinsouRepository(),
                        foundation = foundation,
                        sessionStore = InMemorySyncSessionStore(),
                        workspaceDeparture = SyncWorkspaceDeparture { error("No workspace should be left") },
                        offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
                    )
                    coordinator.restore(
                        archive.inspection,
                        archive.encoded,
                        SnapshotRestoreTarget.THIS_DEVICE_ONLY,
                    )

                    assertEquals(publishedGeneration + 1, foundation.blobStore.currentGeneration)
                    assertEquals(1, foundation.blobStore.count)
                    assertEquals(0, foundation.blobStore.pendingReceiptCount)
                    assertEquals(archive.state.publications, foundation.publications.all())
                    assertContentEquals(archive.body, foundation.blobStore.read(archive.blobReference))
                } finally {
                    foundation.close()
                    driver.close()
                }
            }
        } finally {
            deleteSqliteFiles(database)
        }
    }

    @Test
    fun productionSyncJournalReconcilesItsMemoryAfterOuterContentRollback() = runTest {
        val archive = archiveFixture()
        withRuntime("backup-v2-production-journal-rollback") { driver, foundation ->
            val repository = ShinsouRepository()
            val sessions = InMemorySyncSessionStore(session(SyncSessionStatus.READY))
            val persistence = SqlDriverSyncStatePersistence(driver, ownsDriver = false)
            val localStore = PersistentLocalSyncStore.open(persistence)
            var nextId = 0
            repository.configureSyncMutationBoundary(
                observer = RepositorySyncBridge(
                    localStore = localStore,
                    sessionStore = sessions,
                    idGenerator = SyncPortableIdGenerator { "backup-rollback-${++nextId}" },
                    nowMillis = { 1_000 },
                ),
                guard = CloudflareSnapshotReplacementGuard(sessions),
            )
            val coordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = sessions,
                workspaceDeparture = SyncWorkspaceDeparture { error("Synchronized restore must not leave") },
                offlineStoreAuthorizer = offlineStoreAuthorizer(foundation),
            )
            val failure = FailureTrigger(
                "production-content",
                "fail_backup_production_content",
                "content_publications",
            )
            driver.execute(null, failure.createSql, 0).value

            assertFailsWith<Throwable> {
                coordinator.restore(
                    archive.inspection,
                    archive.encoded,
                    SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
                )
            }

            assertTrue(localStore.readState().drafts.isEmpty())
            assertTrue(localStore.readState().replica.entities.isEmpty())
            assertTrue(repository.currentSnapshot.mangas.isEmpty())

            driver.execute(null, "DROP TRIGGER ${failure.triggerName}", 0).value
            coordinator.restore(
                archive.inspection,
                archive.encoded,
                SnapshotRestoreTarget.ALL_SYNCED_DEVICES,
            )

            assertTrue(localStore.readState().drafts.isNotEmpty())
            assertEquals(archive.state.legacySnapshot.mangas, repository.currentSnapshot.mangas)
        }
    }

    private suspend fun withRuntime(
        name: String,
        block: suspend (JdbcSqliteDriver, ContentFoundationRuntime) -> Unit,
    ) {
        val database = Files.createTempFile(name, ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
        val foundation = ContentFoundationRuntime(
            driver = driver,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        try {
            block(driver, foundation)
        } finally {
            foundation.close()
            driver.close()
            deleteSqliteFiles(database)
        }
    }

    private fun archiveFixture(
        allowedOperations: Set<ContentOperation> = DEFAULT_ALLOWED_OPERATIONS,
        constraints: Set<RightsConstraint> = emptySet(),
        publicationTitle: String = "Portable coordinator fixture",
        includeContentBlobs: Boolean = true,
        auxiliaryCategoryName: String = "Novel",
    ): ArchiveFixture {
        val body = "hello portable coordinator".encodeToByteArray()
        val source = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024)
        val receipt = source.put(body, "text/plain")
        val publicationKey = PublicationKey(PUBLICATION_ID)
        val unitKey = UnitKey(publicationKey, UNIT_ID)
        val manifest = ContentManifest(
            manifestId = MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 1,
            representations = listOf(
                ContentRepresentation.PlainText(
                    representationId = REPRESENTATION_ID,
                    resource = ResourceRef("body", receipt.reference),
                    canonicalUtf16Length = body.decodeToString().length,
                    sourceEncoding = "UTF-8",
                    blocks = listOf(TextBlock("paragraph-0", 0, body.decodeToString().length)),
                ),
            ),
            declaredSizeBytes = receipt.reference.byteSize,
        )
        val grantReference = RightsGrantRef(GRANT_ID)
        val publication = Publication(
            key = publicationKey,
            title = publicationTitle,
            acquisitions = listOf(
                Acquisition(
                    id = ACQUISITION_ID,
                    origin = AcquisitionOrigin.LocalText,
                    units = listOf(
                        PublicationUnit(
                            key = unitKey,
                            title = "Chapter",
                            manifestRevisions = listOf(manifest),
                            ordinal = 0,
                        ),
                    ),
                    contentRevision = 1,
                    rightsGrantRef = grantReference,
                ),
            ),
        )
        val rightsScope = RightsScope(publicationKey, ACQUISITION_ID)
        val grant = RightsGrant(
            schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
            grantId = grantReference,
            scope = rightsScope,
            provenance = RightsProvenance.HostPolicy("portable-test"),
            protectionScheme = ProtectionScheme.None,
            validFromEpochMillis = 0,
            validUntilEpochMillis = null,
            allowedOperations = allowedOperations,
            constraints = constraints,
        )
        val attachment = ManifestAttachment(
            owner = ContentManifestOwner(publicationKey, ACQUISITION_ID, unitKey),
            manifest = manifest,
        )
        val readingScope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publicationKey,
            acquisitionId = ACQUISITION_ID,
            unitId = unitKey,
            contentRevision = 1,
        )
        val quote = TextQuote(exact = "portable", prefix = "hello ", suffix = " coordinator")
        val annotation = ContentAnnotation(
            schemaVersion = ContentAnnotation.CURRENT_SCHEMA_VERSION,
            annotationId = ANNOTATION_ID,
            kind = ContentAnnotationKind.NOTE,
            range = ReadingRange(
                start = ReadingLocator.Text(
                    schemaVersion = readingScope.schemaVersion,
                    scope = readingScope,
                    resourceId = "body",
                    blockId = "paragraph-0",
                    offset = 6,
                    quote = quote,
                ),
                end = ReadingLocator.Text(
                    schemaVersion = readingScope.schemaVersion,
                    scope = readingScope,
                    resourceId = "body",
                    blockId = "paragraph-0",
                    offset = 14,
                ),
                quote = quote,
            ),
            body = "portable note",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        val state = BackupV2PortableState(
            legacySnapshot = AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 7, url = "/fixture", title = publicationTitle)),
                chapters = listOf(Chapter(id = 2, mangaId = 1, url = "/chapter", name = "Chapter")),
            ),
            publications = listOf(publication),
            annotations = listOf(annotation),
            rightsGrants = listOf(grant),
            auxiliary = ContentPortableAuxiliaryState(
                metadata = listOf(
                    ContentMetadataMutation(
                        "migration.shuyue.category.fixture",
                        auxiliaryCategoryName,
                    ),
                ),
                aliases = listOf(
                    ContentAliasMutation("shuyue-v1-book:fixture", PUBLICATION_ID),
                ),
                migrations = listOf(
                    ContentMigrationLedgerMutation(
                        namespace = "shuyue.backup.v1",
                        sourceDigestSha256 = "1".repeat(64),
                        resultFingerprintSha256 = "2".repeat(64),
                    ),
                    ContentMigrationLedgerMutation(
                        namespace = "legacy-app-snapshot-v1",
                        sourceDigestSha256 = "3".repeat(64),
                        resultFingerprintSha256 = "4".repeat(64),
                    ),
                ),
            ),
        )
        val created = PortableContentBackupV2Service.create(
            state = state,
            candidates = listOf(
                BackupV2AttachmentCandidate(
                    attachment = attachment,
                    access = ContentAccessRequest(grantReference, rightsScope.copy(
                        unitId = unitKey,
                        manifestId = MANIFEST_ID,
                        contentRevision = 1,
                    )),
                ),
            ),
            blobStore = source,
            operationGate = AllowGate,
            createdAtEpochMillis = 10,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = includeContentBlobs),
        )
        // `create` canonicalizes every set-like authority list before checksumming it. Expectations
        // must use the exact inspected archive state, not the caller's intentionally unsorted
        // construction input (notably multi-ledger migration authority).
        val inspection = PortableContentBackupV2Service.inspect(created.archive)
        return ArchiveFixture(
            state = inspection.portableState,
            inspection = inspection,
            encoded = BackupV2BinaryCodec.encode(created.archive),
            body = body,
            blobReference = receipt.reference,
        )
    }

    private fun offlineStoreAuthorizer(foundation: ContentFoundationRuntime) =
        HostContentBodyOfflineStoreAuthorizer(
            authority = foundation.rightsAuthority,
            durableGrant = foundation.rightsGrants::find,
            nowEpochMillis = { 0 },
        )

    private class RecordingOfflineStoreAuthorizer(
        private val delegate: ContentBodyOfflineStoreAuthorizer,
    ) : ContentBodyOfflineStoreAuthorizer {
        val preflightRequests = mutableListOf<PendingContentBodyStoreRequest>()
        val executionRequests = mutableListOf<PendingContentBodyStoreRequest>()

        override fun requireAllowed(request: PendingContentBodyStoreRequest) {
            preflightRequests += request
            delegate.requireAllowed(request)
        }

        override fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T {
            executionRequests += request
            return delegate.execute(request, block)
        }
    }

    /** Simulates a platform failure after put returned but before the coordinator cached it. */
    private class FailOnceAfterBodyPublishAuthorizer(
        private val delegate: ContentBodyOfflineStoreAuthorizer,
    ) : ContentBodyOfflineStoreAuthorizer {
        private var shouldFail = true

        override fun requireAllowed(request: PendingContentBodyStoreRequest) {
            delegate.requireAllowed(request)
        }

        override fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T {
            val result = delegate.execute(request, block)
            if (shouldFail) {
                shouldFail = false
                throw InjectedBodyStagingFailure()
            }
            return result
        }
    }

    private class InjectedBodyStagingFailure : IllegalStateException("injected body staging failure")

    private class InjectedWorkspaceDepartureFailure :
        IllegalStateException("injected workspace departure failure")

    private class RecordingArchiveSource(private val bytes: ByteArray) : BackupV2ArchiveSource {
        val readSizes = mutableListOf<Int>()
        override val byteSize: Long get() = bytes.size.toLong()

        override fun read(offset: Long, byteCount: Int): ByteArray {
            require(offset >= 0 && byteCount >= 0 && byteCount.toLong() <= byteSize - offset)
            readSizes += byteCount
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        }
    }

    private fun session(status: SyncSessionStatus) = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Desktop",
        platform = "desktop",
        status = status,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
        provider = SyncProvider.CLOUDFLARE_V2,
    )

    private fun createJournalTable(driver: SqlDriver) {
        driver.execute(
            null,
            "CREATE TABLE test_sync_journal(journal_id TEXT NOT NULL PRIMARY KEY)",
            0,
        ).value
    }

    private fun journalObserver(driver: SqlDriver, journalId: String) = SnapshotMutationObserver { _, _ ->
        driver.execute(
            null,
            "INSERT OR IGNORE INTO test_sync_journal(journal_id) VALUES (?)",
            1,
        ) { bindString(0, journalId) }.value
    }

    private fun scalarLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value

    private fun scalarString(driver: SqlDriver, sql: String): String = driver.executeQuery(
        null,
        sql,
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getString(0)))
        },
        parameters = 0,
    ).value

    private fun deleteSqliteFiles(database: Path) {
        Files.deleteIfExists(database)
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
        Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
    }

    private data class ArchiveFixture(
        val state: BackupV2PortableState,
        val inspection: BackupV2Inspection,
        val encoded: ByteArray,
        val body: ByteArray,
        val blobReference: dev.shinsou.kmp.content.BlobRef,
    ) {
        fun bodyScope(): RightsScope {
            val publication = state.publications.single()
            val acquisition = publication.acquisitions.single()
            val unit = acquisition.units.single()
            val manifest = unit.manifestRevisions.single()
            return RightsScope(
                publicationId = publication.key,
                acquisitionId = acquisition.id,
                unitId = unit.key,
                manifestId = manifest.manifestId,
                contentRevision = manifest.contentRevision,
            )
        }
    }

    private data class FailureTrigger(
        val name: String,
        val triggerName: String,
        val table: String,
    ) {
        val createSql: String
            get() = "CREATE TRIGGER $triggerName BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, '$name failure'); END"
    }

    private object AllowGate : ContentOperationGate {
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

    private companion object {
        const val RESTORE_SOURCE_MAX_READ_BYTES = 64 * 1024
        const val PUBLICATION_ID = "10000000-0000-5000-8000-000000000001"
        const val ACQUISITION_ID = "10000000-0000-5000-8000-000000000002"
        const val UNIT_ID = "10000000-0000-5000-8000-000000000003"
        const val MANIFEST_ID = "10000000-0000-5000-8000-000000000004"
        const val REPRESENTATION_ID = "10000000-0000-5000-8000-000000000005"
        const val GRANT_ID = "10000000-0000-5000-8000-000000000006"
        const val ANNOTATION_ID = "10000000-0000-5000-8000-000000000007"
        const val SENTINEL_ANNOTATION_ID = "10000000-0000-5000-8000-000000000008"

        val DEFAULT_ALLOWED_OPERATIONS = setOf(
            ContentOperation.DISPLAY,
            ContentOperation.OFFLINE_STORE,
            ContentOperation.EXPORT,
            ContentOperation.SYNC_BLOB,
            ContentOperation.ANNOTATE,
        )

        val FAILURE_TRIGGERS = listOf(
            FailureTrigger("metadata", "fail_backup_metadata", "content_transaction_metadata"),
            FailureTrigger("alias", "fail_backup_alias", "content_transaction_aliases"),
            FailureTrigger("migration", "fail_backup_migration", "content_transaction_migrations"),
            FailureTrigger("annotation", "fail_backup_annotation", "content_annotations"),
            FailureTrigger("content", "fail_backup_content", "content_publications"),
            FailureTrigger("outbox", "fail_backup_outbox", "content_transaction_outbox"),
        )
    }
}
