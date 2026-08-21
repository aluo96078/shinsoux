package dev.shinsou.kmp.sync.v2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.AnnotationConflictException
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.sync.persistence.SqlDriverSyncStatePersistence
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentAnnotationSyncReconciliationRestartTest {
    @Test
    fun sqliteAnnotationScanIsBoundedIdentityOrderedAndCanIncludeTombstones() {
        val directory = Files.createTempDirectory("annotation-sync-page")
        val database = directory.resolve("content.sqlite")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val foundation = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            val laterId = annotation()
            val earlierTombstone = laterId.copy(
                annotationId = EARLIER_ANNOTATION_ID,
                body = null,
                state = ContentAnnotationState.TOMBSTONE,
                tombstoneReason = "deleted",
            )
            foundation.annotations.put(laterId)
            foundation.annotations.put(earlierTombstone)

            val first = foundation.annotations.listPage(null, limit = 1, includeTombstones = true)
            val second = foundation.annotations.listPage(
                first.single().annotationId,
                limit = 1,
                includeTombstones = true,
            )

            assertEquals(listOf(earlierTombstone), first)
            assertEquals(listOf(laterId), second)
            assertEquals(listOf(laterId), foundation.annotations.listPage(
                afterAnnotationIdExclusive = null,
                limit = 2,
                includeTombstones = false,
            ))

            val replicaWinner = laterId.copy(
                body = "replica winner",
                createdAtEpochMillis = 5,
                updatedAtEpochMillis = 30,
            )
            assertFailsWith<AnnotationConflictException> {
                foundation.annotations.put(replicaWinner, expectedUpdatedAtEpochMillis = 20)
            }
            assertFailsWith<AnnotationConflictException> {
                foundation.annotations.putFromVerifiedReplica(
                    replicaWinner,
                    expectedUpdatedAtEpochMillis = 19,
                )
            }
            foundation.annotations.putFromVerifiedReplica(
                replicaWinner,
                expectedUpdatedAtEpochMillis = 20,
            )
            assertEquals(replicaWinner, foundation.annotations.find(laterId.annotationId))
            driver.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun sqliteAnnotationOutboxAndReplicaSurviveEveryReconciliationCrashBoundary() = runTest {
        val directory = Files.createTempDirectory("annotation-sync-reconcile")
        val database = directory.resolve("content.sqlite")
        val annotation = annotation()
        val dispatcher = StandardTestDispatcher(testScheduler)
        try {
            var driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            var foundation = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            var persistence = SqlDriverSyncStatePersistence(driver, ownsDriver = false)
            var localSync = PersistentLocalSyncStore.open(persistence)
            foundation.annotations.put(annotation)

            val first = ContentAnnotationSyncReconciliationBridge(
                foundation.annotations,
                foundation.transactions,
                localSync,
                dispatcher,
            ).reconcileSlice()
            assertEquals(1, first.localAnnotationsStaged)
            val durableDraftIds = foundation.transactions.pendingOutbox().map(SyncDraft::draftId)
            assertTrue(durableDraftIds.isNotEmpty())
            driver.close()

            // Crash before draining: the deterministic content commit must replay after reopen
            // without appending a second copy of any draft.
            driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            foundation = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            persistence = SqlDriverSyncStatePersistence(driver, ownsDriver = false)
            localSync = PersistentLocalSyncStore.open(persistence)
            assertEquals(annotation, foundation.annotations.find(annotation.annotationId))
            val replay = ContentAnnotationSyncReconciliationBridge(
                foundation.annotations,
                foundation.transactions,
                localSync,
                dispatcher,
            ).reconcileSlice()
            assertEquals(1, replay.replayedCommits)
            assertEquals(durableDraftIds, foundation.transactions.pendingOutbox().map(SyncDraft::draftId))

            ContentSyncOutboxDrainBridge(foundation.transactions, localSync, DEVICE_ID) { 100 }
                .drain(maxDrafts = 32)
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            driver.close()

            // Crash after LocalSyncStore commit and content acknowledgement: both authorities
            // reopen converged and no reconciliation draft is recreated.
            driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            foundation = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.V2_ACTIVE },
            )
            persistence = SqlDriverSyncStatePersistence(driver, ownsDriver = false)
            localSync = PersistentLocalSyncStore.open(persistence)
            val converged = ContentAnnotationSyncReconciliationBridge(
                foundation.annotations,
                foundation.transactions,
                localSync,
                dispatcher,
            ).reconcileSlice()
            assertEquals(1, converged.converged)
            assertEquals(annotation, localSync.readState().replica.contentAnnotations
                .getValue(annotation.annotationId).annotation?.value)
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            driver.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun annotation(): ContentAnnotation {
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
            body = "restart safe",
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
    }

    private companion object {
        const val DEVICE_ID = "60000000-0000-4000-8000-000000000001"
        const val PUBLICATION_ID = "60000000-0000-4000-8000-000000000002"
        const val ACQUISITION_ID = "60000000-0000-4000-8000-000000000003"
        const val UNIT_ID = "60000000-0000-4000-8000-000000000004"
        const val ANNOTATION_ID = "60000000-0000-4000-8000-000000000005"
        const val EARLIER_ANNOTATION_ID = "60000000-0000-4000-8000-000000000000"
    }
}
