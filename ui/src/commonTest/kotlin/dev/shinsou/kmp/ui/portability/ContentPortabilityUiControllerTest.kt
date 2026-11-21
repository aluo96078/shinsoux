package dev.shinsou.kmp.ui.portability

import dev.shinsou.kmp.backup.BackupV2CreatePolicy
import dev.shinsou.kmp.backup.BackupV2BinaryCodec
import dev.shinsou.kmp.backup.BackupV2PortableState
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.backup.shuyue.ShuYueImportSelection
import dev.shinsou.kmp.backup.shuyue.ShuYueSecretImportConsent
import dev.shinsou.kmp.backup.shuyue.ShuYueSecretImportResult
import dev.shinsou.kmp.backup.shuyue.ShuYueTransactionalImportResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreResult

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
    fun contentBackupControllerValidatesBeforeCallingRestore() = runTest {
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
            operationGate = AllowGate,
            createdAtEpochMillis = 123,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = true),
        )
        var restored = false
        var restoredBytes = 0
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction { created },
            restoreAction = PortableContentBackupV2RestoreAction { inspection, source ->
                restored = true
                restoredBytes = source.byteSize.toInt()
                PortableContentBackupV2RestoreResult(
                    publicationCount = inspection.portableState.publications.size,
                    annotationCount = inspection.portableState.annotations.size,
                    contentBlobCount = inspection.envelope.manifest.entries.count {
                        it.kind.name == "CONTENT_BLOB"
                    },
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
        assertFalse(restored)

        controller.restore()

        assertTrue(restored)
        assertEquals(artifact.byteSize, restoredBytes)
        assertEquals(PortableContentBackupV2UiPhase.COMPLETE, controller.state.value.phase)
    }

    @Test
    fun contentBackupActionsRunOnTheWorkDispatcher() = runTest {
        val work = StandardTestDispatcher(testScheduler, name = "work")
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
            operationGate = AllowGate,
            createdAtEpochMillis = 123,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = false),
        )
        val ranOn = mutableListOf<ContinuationInterceptor?>()
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction {
                ranOn += currentCoroutineContext()[ContinuationInterceptor]
                created
            },
            restoreAction = PortableContentBackupV2RestoreAction { _, _ ->
                ranOn += currentCoroutineContext()[ContinuationInterceptor]
                PortableContentBackupV2RestoreResult(0, 0, 0)
            },
            workDispatcher = work,
        )

        val artifact = assertNotNull(controller.createExport(includeContentBlobs = false))
        controller.inspectForRestore(artifact.copyEncoded())
        controller.restore()

        assertEquals(listOf<ContinuationInterceptor?>(work, work), ranOn)
    }

    @Test
    fun aRestoreWhoseScreenWentAwayStillCompletesAndSaysSo() = runTest {
        val created = PortableContentBackupV2Service.create(
            state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
            candidates = emptyList(),
            blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
            operationGate = AllowGate,
            createdAtEpochMillis = 123,
            appVersion = "test",
            policy = BackupV2CreatePolicy(includeContentBlobs = false),
        )
        val committing = CompletableDeferred<Unit>()
        var finished = false
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction { created },
            restoreAction = PortableContentBackupV2RestoreAction { _, _ ->
                committing.await()
                finished = true
                PortableContentBackupV2RestoreResult(1, 0, 0)
            },
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        val artifact = assertNotNull(controller.createExport(includeContentBlobs = false))
        controller.inspectForRestore(artifact.copyEncoded())

        val screen = launch { controller.restore() }
        runCurrent()
        screen.cancel()
        committing.complete(Unit)
        advanceUntilIdle()

        assertTrue(finished, "the restore ran to the end")
        assertEquals(PortableContentBackupV2UiPhase.COMPLETE, controller.state.value.phase)
        assertNotNull(controller.state.value.restoreResult)
    }

    @Test
    fun aFailedRestoreWhoseScreenWentAwayStillSaysItFailed() = runTest {
        val created = emptyBackup()
        val committing = CompletableDeferred<Unit>()
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction { created },
            restoreAction = PortableContentBackupV2RestoreAction { _, _ ->
                committing.await()
                error("commit failed")
            },
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.inspectForRestore(assertNotNull(controller.createExport(includeContentBlobs = false)).copyEncoded())

        val screen = launch { controller.restore() }
        runCurrent()
        screen.cancel()
        committing.complete(Unit)
        advanceUntilIdle()

        assertEquals(PortableContentBackupV2UiPhase.REVIEW, controller.state.value.phase)
        assertEquals("backup_v2_restore_failed", controller.state.value.failure?.code)
    }

    @Test
    fun closingWaitsForAConfirmedRestore() = runTest {
        val created = emptyBackup()
        val committing = CompletableDeferred<Unit>()
        val controller = DefaultPortableContentBackupV2UiController(
            createAction = PortableContentBackupV2CreateAction { created },
            restoreAction = PortableContentBackupV2RestoreAction { _, _ ->
                committing.await()
                PortableContentBackupV2RestoreResult(0, 0, 0)
            },
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.inspectForRestore(assertNotNull(controller.createExport(includeContentBlobs = false)).copyEncoded())
        launch { controller.restore() }
        runCurrent()

        var closed = false
        launch {
            controller.awaitRestore()
            closed = true
        }
        runCurrent()
        val closedWhileRunning = closed
        // Let the restore end before asserting: it cannot be cancelled, so a failed assertion
        // with the restore still waiting would leave runTest waiting for it forever.
        committing.complete(Unit)
        advanceUntilIdle()
        assertFalse(closedWhileRunning, "the stores stay open while the restore runs")
        assertTrue(closed)
    }

    private fun emptyBackup() = PortableContentBackupV2Service.create(
        state = BackupV2PortableState(legacySnapshot = AppSnapshot()),
        candidates = emptyList(),
        blobStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1_024),
        operationGate = AllowGate,
        createdAtEpochMillis = 123,
        appVersion = "test",
        policy = BackupV2CreatePolicy(includeContentBlobs = false),
    )

    @Test
    fun invalidContentArchiveFailsBeforeRestoreAction() = runTest {
        var restoreCalls = 0
        val controller = DefaultPortableContentBackupV2UiController(
            restoreAction = PortableContentBackupV2RestoreAction { _, _ ->
                restoreCalls += 1
                PortableContentBackupV2RestoreResult(0, 0, 0)
            },
        )

        controller.inspectForRestore("not-an-archive".encodeToByteArray())

        assertEquals(PortableContentBackupV2UiPhase.IDLE, controller.state.value.phase)
        assertEquals("backup_v2_invalid_archive", controller.state.value.failure?.code)
        assertEquals(0, restoreCalls)
    }

    /**
     * A ShuYue v1 backup as the file holds it: one book with one chapter, one credential and one
     * cookie. The v1 models are internal to :feature-library, so the fixture is the wire format.
     */
    private fun shuYueFixture(): ByteArray = """
        {
            "version": 1,
            "createdAt": 100,
            "books": [
                {
                    "id": "book-one",
                    "title": "Book one",
                    "author": null,
                    "description": null,
                    "coverImage": null,
                    "origin": "LocalTxt",
                    "sourceId": null,
                    "originalUri": null,
                    "chapters": [
                        {
                            "id": "chapter-one",
                            "bookId": "book-one",
                            "title": "Chapter one",
                            "index": 0,
                            "href": null,
                            "text": "chapter-body-sentinel",
                            "wordCount": 1
                        }
                    ],
                    "addedAt": 0,
                    "updatedAt": 0,
                    "category": "Default"
                }
            ],
            "progress": [],
            "readerSettings": {
                "language": "System",
                "fontSizeSp": 18.0,
                "lineHeightPercent": 165,
                "pageChars": 800,
                "theme": "System",
                "accentColor": "Blue",
                "volumeKeysEnabled": true,
                "volumeUpAction": "Previous",
                "volumeDownAction": "Next",
                "keepScreenOn": false,
                "syncOnLaunch": false,
                "appLockEnabled": false,
                "secureScreen": false,
                "incognitoMode": false,
                "showNsfwSources": false,
                "imageParsingEnabled": true,
                "showPluginErrors": false
            },
            "installedPlugins": [],
            "pluginInstallations": [],
            "pluginRepositories": [],
            "selectedPluginRepositoryUrl": null,
            "pluginCredentials": [
                {
                    "sourceId": "source-one",
                    "username": "credential-user-sentinel",
                    "password": "credential-password-sentinel",
                    "updatedAt": 100
                }
            ],
            "pluginCookies": [
                {
                    "sourceId": "source-one",
                    "name": "session",
                    "value": "cookie-value-sentinel",
                    "domain": "example.com",
                    "path": "/",
                    "expiresAt": null
                }
            ],
            "pluginPreferences": {},
            "pluginImageParsingPolicies": {}
        }
    """.trimIndent().encodeToByteArray()

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
}
