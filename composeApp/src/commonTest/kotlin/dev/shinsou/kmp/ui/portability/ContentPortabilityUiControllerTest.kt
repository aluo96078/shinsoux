package dev.shinsou.kmp.ui.portability

import dev.shinsou.kmp.backup.BackupV2CreatePolicy
import dev.shinsou.kmp.backup.BackupV2BinaryCodec
import dev.shinsou.kmp.backup.BackupV2PortableState
import dev.shinsou.kmp.backup.PortableContentBackupV2RecoverableRestoreException
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreFailurePhase
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreRecoveryStatus
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.backup.SnapshotRestoreTarget
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.migration.shuyue.ShuYueBackupV1
import dev.shinsou.kmp.migration.shuyue.ShuYueImportSelection
import dev.shinsou.kmp.migration.shuyue.ShuYueSecretImportConsent
import dev.shinsou.kmp.migration.shuyue.ShuYueSecretImportResult
import dev.shinsou.kmp.migration.shuyue.ShuYueTransactionalImportResult
import dev.shinsou.kmp.migration.shuyue.ShuYueV1Book
import dev.shinsou.kmp.migration.shuyue.ShuYueV1Chapter
import dev.shinsou.kmp.migration.shuyue.ShuYueV1PluginCookie
import dev.shinsou.kmp.migration.shuyue.ShuYueV1PluginCredential
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.ui.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.writeCheckedTo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ContentPortabilityUiControllerTest {
    @Test
    fun shuyueReviewKeepsSecretsOpaqueAndRequiresSeparateConfirmation() = runTest {
        var importedSelection: ShuYueImportSelection? = null
        var importedConsent: ShuYueSecretImportConsent? = null
        val controller = DefaultShuYueMigrationUiController(
            contentImportAction = ShuYueContentImportAction { _, selection ->
                importedSelection = selection
                transactionalResult()
            },
            secretImportAction = ShuYueSecretImportAction { _, consent ->
                importedConsent = consent
                ShuYueSecretImportResult(
                    credentialCount = consent.credentialSourceIds.size,
                    cookieCount = consent.cookieSourceIds.size,
                )
            },
        )

        controller.inspect(shuYueFixture())

        assertEquals(ShuYueMigrationUiPhase.REVIEW, controller.state.value.phase)
        assertEquals(1L, controller.state.value.preview?.counts?.books)
        assertTrue(controller.state.value.secretImportAvailable)
        assertFalse(controller.state.value.toString().contains("credential-password-sentinel"))
        assertFalse(controller.state.value.toString().contains("cookie-value-sentinel"))
        assertFalse(controller.state.value.toString().contains("chapter-body-sentinel"))
        assertFalse(controller.state.value.includeContentBodySync)

        controller.setBookSelected("book-one", selected = false)
        controller.setIncludeReaderSettings(false)
        controller.setIncludeContentBodySync(true)
        controller.setIncludeCredentials(true)
        controller.setIncludeCookies(true)
        controller.importContent()

        assertEquals(emptySet(), importedSelection?.selectedBookIds)
        assertFalse(requireNotNull(importedSelection).includeReaderSettings)
        assertTrue(requireNotNull(importedSelection).includeContentBodySync)
        assertEquals(ShuYueMigrationUiPhase.CONTENT_IMPORTED, controller.state.value.phase)
        assertNull(importedConsent)

        controller.importSelectedSecrets(confirmedAtEpochMillis = 500)

        assertEquals(setOf("source-one"), importedConsent?.credentialSourceIds)
        assertEquals(setOf("source-one"), importedConsent?.cookieSourceIds)
        assertEquals(500, importedConsent?.confirmedAtEpochMillis)
        assertEquals(ShuYueMigrationUiPhase.COMPLETE, controller.state.value.phase)
    }

    @Test
    fun rejectedShuyueInputNeverExposesAnImportAction() = runTest {
        var imports = 0
        val controller = DefaultShuYueMigrationUiController(
            contentImportAction = ShuYueContentImportAction { _, _ ->
                imports += 1
                transactionalResult()
            },
        )

        controller.inspect("not-json".encodeToByteArray())

        assertEquals(ShuYueMigrationUiPhase.REJECTED, controller.state.value.phase)
        assertFalse(controller.state.value.acceptedForReview)
        assertEquals(0, imports)
    }

    @Test
    fun contentBackupControllerValidatesBeforeCallingTargetedRestore() = runTest {
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
            operationGate = AllowGate,
            createdAtEpochMillis = 123,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = true),
        )
        var restoredTarget: SnapshotRestoreTarget? = null
        var restoredBytes = 0
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction { created },
            restoreAction = PortableContentBackupV2RestoreAction { inspection, source, target ->
                restoredTarget = target
                restoredBytes = source.byteSize.toInt()
                PortableContentBackupV2RestoreResult(
                    publicationCount = inspection.portableState.publications.size,
                    annotationCount = inspection.portableState.annotations.size,
                    contentBlobCount = inspection.envelope.manifest.entries.count {
                        it.kind.name == "CONTENT_BLOB"
                    },
                    synchronizedTarget = target,
                )
            },
        )

        val artifact = assertNotNull(controller.createExport(includeContentBlobs = true))
        assertEquals(123, artifact.summary.createdAtEpochMillis)
        assertTrue(artifact.byteSize > 0)
        assertFalse(artifact.toString().contains("SHINSOU2"))

        val chunks = mutableListOf<ByteArray>()
        var maximumChunkSize = 0
        val streamedSize = artifact.writeCheckedTo(BinaryDocumentExportSink { chunk ->
            maximumChunkSize = maxOf(maximumChunkSize, chunk.size)
            chunks += chunk.copyOf()
        })
        val streamed = ByteArray(streamedSize.toInt())
        var streamedOffset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(streamed, destinationOffset = streamedOffset)
            streamedOffset += chunk.size
        }
        assertEquals(artifact.byteSize.toLong(), streamedSize)
        assertTrue(maximumChunkSize <= 64 * 1024)
        assertContentEquals(artifact.copyEncoded(), streamed)

        controller.inspectForRestore(artifact.copyEncoded())
        assertEquals(PortableContentBackupV2UiPhase.REVIEW, controller.state.value.phase)
        assertEquals(123, controller.state.value.restoreReview?.createdAtEpochMillis)
        assertNull(restoredTarget)

        controller.restore(SnapshotRestoreTarget.ALL_SYNCED_DEVICES)

        assertEquals(SnapshotRestoreTarget.ALL_SYNCED_DEVICES, restoredTarget)
        assertEquals(artifact.byteSize, restoredBytes)
        assertEquals(PortableContentBackupV2UiPhase.COMPLETE, controller.state.value.phase)
    }

    @Test
    fun recoverableRestoreKeepsVerifiedReviewAndRetriesSameArchive() = runTest {
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
            operationGate = AllowGate,
            createdAtEpochMillis = 321,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = true),
        )
        var restoreCalls = 0
        val controller = DefaultPortableContentBackupV2UiController(
            restoreAction = PortableContentBackupV2RestoreAction { inspection, _, target ->
                restoreCalls += 1
                if (restoreCalls == 1) {
                    throw PortableContentBackupV2RecoverableRestoreException(
                        phase = PortableContentBackupV2RestoreFailurePhase
                            .LOCAL_COMMIT_AFTER_WORKSPACE_DEPARTURE,
                        recoveryStatus = PortableContentBackupV2RestoreRecoveryStatus
                            .RETRY_SAME_VERIFIED_ARCHIVE,
                        archiveManifestSha256 = inspection.envelope.manifestSha256,
                        stagedContentBlobCount = 0,
                        cause = IllegalStateException("injected local transaction failure"),
                    )
                }
                PortableContentBackupV2RestoreResult(0, 0, 0, target)
            },
        )
        controller.inspectForRestore(BackupV2BinaryCodec.encode(created.archive))
        val verifiedReview = assertNotNull(controller.state.value.restoreReview)

        controller.restore(SnapshotRestoreTarget.THIS_DEVICE_ONLY)

        assertEquals(PortableContentBackupV2UiPhase.REVIEW, controller.state.value.phase)
        assertEquals(verifiedReview, controller.state.value.restoreReview)
        assertEquals(
            "backup_v2_restore_retry_same_verified_archive",
            controller.state.value.failure?.code,
        )
        assertTrue(controller.state.value.failure?.message?.contains("same verified archive") == true)

        controller.restore(SnapshotRestoreTarget.THIS_DEVICE_ONLY)

        assertEquals(2, restoreCalls)
        assertEquals(PortableContentBackupV2UiPhase.COMPLETE, controller.state.value.phase)
        assertNull(controller.state.value.failure)
    }

    @Test
    fun invalidContentArchiveFailsBeforeRestoreAction() = runTest {
        var restoreCalls = 0
        val controller = DefaultPortableContentBackupV2UiController(
            restoreAction = PortableContentBackupV2RestoreAction { _, _, target ->
                restoreCalls += 1
                PortableContentBackupV2RestoreResult(0, 0, 0, target)
            },
        )

        controller.inspectForRestore("not-an-archive".encodeToByteArray())

        assertEquals(PortableContentBackupV2UiPhase.IDLE, controller.state.value.phase)
        assertEquals("backup_v2_invalid_archive", controller.state.value.failure?.code)
        assertEquals(0, restoreCalls)
    }

    private fun shuYueFixture(): ByteArray = ShuYueTestJson.encodeToString(
        ShuYueBackupV1(
            version = 1,
            createdAt = 100,
            books = listOf(
                ShuYueV1Book(
                    id = "book-one",
                    title = "Book one",
                    chapters = listOf(
                        ShuYueV1Chapter(
                            id = "chapter-one",
                            bookId = "book-one",
                            title = "Chapter one",
                            index = 0,
                            text = "chapter-body-sentinel",
                            wordCount = 1,
                        ),
                    ),
                ),
            ),
            pluginCredentials = listOf(
                ShuYueV1PluginCredential(
                    sourceId = "source-one",
                    username = "credential-user-sentinel",
                    password = "credential-password-sentinel",
                    updatedAt = 100,
                ),
            ),
            pluginCookies = listOf(
                ShuYueV1PluginCookie(
                    sourceId = "source-one",
                    name = "session",
                    value = "cookie-value-sentinel",
                    domain = "example.com",
                ),
            ),
        ),
    ).encodeToByteArray()

    private fun transactionalResult(): ShuYueTransactionalImportResult = ShuYueTransactionalImportResult(
        commit = ContentCommitResult(
            commitId = "test-import",
            replayed = false,
            deferred = false,
            committedGeneration = 1,
            attachedOwnerIds = emptyList(),
            outboxDraftIds = emptyList(),
        ),
        publicationCount = 0,
        unitCount = 0,
        contentBlobCount = 0,
        quarantineCount = 0,
        categoryCount = 0,
        progressCount = 0,
    )

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
        val ShuYueTestJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
