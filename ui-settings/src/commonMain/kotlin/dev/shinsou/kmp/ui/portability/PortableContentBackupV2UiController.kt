package dev.shinsou.kmp.ui.portability

import dev.shinsou.kmp.backup.BackupFormatException
import dev.shinsou.kmp.backup.BackupV2BinaryCodec
import dev.shinsou.kmp.backup.BackupV2ArchiveSource
import dev.shinsou.kmp.backup.ByteArrayBackupV2ArchiveSource
import dev.shinsou.kmp.backup.BackupV2CreateResult
import dev.shinsou.kmp.backup.BackupV2EntryKind
import dev.shinsou.kmp.backup.BackupV2Inspection
import dev.shinsou.kmp.backup.BackupV2OmissionReason
import dev.shinsou.kmp.backup.DEFAULT_MAX_ARCHIVE_BYTES
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.settings.DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES
import dev.shinsou.kmp.ui.settings.copyToByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreResult

public enum class PortableContentBackupV2UiPhase {
    IDLE,
    EXPORTING,
    INSPECTING,
    REVIEW,
    RESTORING,
    COMPLETE,
}

public data class PortableContentBackupV2Summary(
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val archiveBytes: Long,
    val entryCount: Int,
    val contentBlobCount: Int,
    val attachmentCount: Int,
    val omittedAttachmentCount: Int,
    val omittedByReason: Map<BackupV2OmissionReason, Int>,
    val publicationCount: Int,
    val annotationCount: Int,
)

public data class PortableContentBackupV2UiState(
    val phase: PortableContentBackupV2UiPhase = PortableContentBackupV2UiPhase.IDLE,
    val exportAvailable: Boolean = false,
    val restoreAvailable: Boolean = false,
    val restoreReview: PortableContentBackupV2Summary? = null,
    val lastExport: PortableContentBackupV2Summary? = null,
    val restoreResult: PortableContentBackupV2RestoreResult? = null,
    val failure: PortabilityUiFailure? = null,
) {
    public val busy: Boolean
        get() = phase == PortableContentBackupV2UiPhase.EXPORTING ||
            phase == PortableContentBackupV2UiPhase.INSPECTING ||
            phase == PortableContentBackupV2UiPhase.RESTORING
}

/** Repeatable archive writer passed straight to the platform exporter without a whole-file copy. */
public class PortableContentBackupV2ExportArtifact internal constructor(
    public val suggestedFileName: String,
    private val archive: dev.shinsou.kmp.backup.BackupV2Archive,
    public val byteSize: Int,
    public val summary: PortableContentBackupV2Summary,
) : BinaryDocumentExportSource {
    override val expectedByteSize: Long get() = byteSize.toLong()

    override fun writeTo(sink: BinaryDocumentExportSink): Long = BackupV2BinaryCodec.encodeTo(
        archive = archive,
        sink = dev.shinsou.kmp.backup.BackupV2BinarySink { chunk -> sink.write(chunk) },
    )

    /** Explicit, bounded compatibility path for tests and legacy small-document hosts. */
    public fun copyEncoded(
        maximumBytes: Long = DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES,
    ): ByteArray = copyToByteArray(maximumBytes)

    override fun toString(): String =
        "PortableContentBackupV2ExportArtifact(fileName=$suggestedFileName, byteSize=$byteSize, " +
            "summary=$summary, source=<opaque>)"
}

public fun interface PortableContentBackupV2CreateAction {
    /** The action must apply rights checks when [includeContentBlobs] is true. */
    public suspend fun create(includeContentBlobs: Boolean): BackupV2CreateResult
}

public fun interface PortableContentBackupV2RestoreAction {
    /**
     * Implementations must publish bodies and commit portable state, attachments and annotations
     * through one host-owned transaction.
     */
    public suspend fun restore(
        inspection: BackupV2Inspection,
        archiveSource: BackupV2ArchiveSource,
    ): PortableContentBackupV2RestoreResult
}

public interface PortableContentBackupV2UiController {
    public val state: StateFlow<PortableContentBackupV2UiState>

    public suspend fun createExport(includeContentBlobs: Boolean): PortableContentBackupV2ExportArtifact?
    public suspend fun inspectForRestore(encoded: ByteArray) {
        inspectForRestore(ByteArrayBackupV2ArchiveSource(encoded))
    }
    public suspend fun inspectForRestore(source: BackupV2ArchiveSource)
    public suspend fun restore()
    public fun discardRestoreReview()

    /**
     * Returns once no confirmed restore is running. A restore runs to the end even when its screen
     * goes away, so the app waits for it before it closes the stores the restore commits to.
     */
    public suspend fun awaitRestore() {}
}

/**
 * Bounded decode and checksum validation happen before the host restore action becomes callable.
 * There is intentionally no direct AppSnapshot replacement fallback at this boundary.
 */
public class DefaultPortableContentBackupV2UiController(
    private val createAction: PortableContentBackupV2CreateAction? = null,
    private val restoreAction: PortableContentBackupV2RestoreAction? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PortableContentBackupV2UiController {
    private val initialState = PortableContentBackupV2UiState(
        exportAvailable = createAction != null,
        restoreAvailable = restoreAction != null,
    )
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<PortableContentBackupV2UiState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private val restoreInFlight = Mutex()
    private var pendingArchiveSource: BackupV2ArchiveSource? = null
    private var pendingInspection: BackupV2Inspection? = null

    override suspend fun createExport(
        includeContentBlobs: Boolean,
    ): PortableContentBackupV2ExportArtifact? = operationMutex.withLock {
        val action = checkNotNull(createAction) { "Portable content backup export is unavailable" }
        val before = mutableState.value
        check(!before.busy) { "Another portable backup operation is in progress" }
        mutableState.value = before.copy(
            phase = PortableContentBackupV2UiPhase.EXPORTING,
            failure = null,
        )
        try {
            // The action reads the content store: never on the UI thread (R3-4).
            val created = withContext(workDispatcher) { action.create(includeContentBlobs) }
            val (inspection, encodedSize) = withContext(workDispatcher) {
                val verified = PortableContentBackupV2Service.inspect(created.archive)
                verified to BackupV2BinaryCodec.encodedSizeOfInspectedArchive(created.archive)
            }
            check(encodedSize <= Int.MAX_VALUE.toLong()) { "Content backup is too large" }
            val summary = inspection.toUiSummary(encodedSize)
            val artifact: PortableContentBackupV2ExportArtifact = PortableContentBackupV2ExportArtifact(
                suggestedFileName = "shinsou-content-${summary.createdAtEpochMillis}.shinsou2",
                archive = created.archive,
                byteSize = encodedSize.toInt(),
                summary = summary,
            )
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                lastExport = summary,
                failure = null,
            )
            artifact
        } catch (cancelled: CancellationException) {
            mutableState.value = before
            throw cancelled
        } catch (logged: Throwable) {
            Log.warnKindOnly("Backup", logged) { "Creating the content backup archive failed" }
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                failure = PortabilityUiFailure(
                    code = "backup_v2_export_failed",
                    message = "The checksummed content archive could not be created.",
                ),
            )
            null
        }
    }

    override suspend fun inspectForRestore(source: BackupV2ArchiveSource) {
        // The controller owns [source] from the call on, even while it waits for another operation
        // (and is cancelled there): it stays open for review and a retried restore, and is closed
        // when it is rejected, restored, replaced or discarded.
        var retained = false
        try {
            operationMutex.withLock { retained = inspectLocked(source) }
        } finally {
            if (!retained) source.close()
        }
    }

    /** Inspects [source] under [operationMutex]; true when it is kept for review. */
    private suspend fun inspectLocked(source: BackupV2ArchiveSource): Boolean {
        val before = mutableState.value
        check(!before.busy) { "Another portable backup operation is in progress" }
        releasePendingArchive()
        var retained = false
        mutableState.value = before.copy(
            phase = PortableContentBackupV2UiPhase.INSPECTING,
            restoreReview = null,
            restoreResult = null,
            failure = null,
        )
        // Cancelled, the inspection leaves the state as it found it (see finally).
        var settled = false
        try {
            if (source.byteSize > DEFAULT_MAX_ARCHIVE_BYTES) {
                throw BackupFormatException("Content backup is too large")
            }
            // Validate and retain the same immutable platform source between review and commit.
            val decoded = withContext(workDispatcher) {
                BackupV2BinaryCodec.decodeInspected(source)
            }
            val inspection = decoded.inspection
            pendingArchiveSource = source
            pendingInspection = inspection
            retained = true
            settled = true
            mutableState.value = mutableState.value.copy(
                phase = PortableContentBackupV2UiPhase.REVIEW,
                restoreReview = inspection.toUiSummary(source.byteSize),
                failure = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BackupFormatException) {
            settled = true
            // A rejected archive is a normal user-input outcome, not an app failure.
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                failure = PortabilityUiFailure(
                    code = "backup_v2_invalid_archive",
                    message = "The selected content archive failed format or checksum validation.",
                ),
            )
        } catch (logged: Throwable) {
            settled = true
            Log.warnKindOnly("Backup", logged) { "Inspecting the content backup failed" }
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                failure = PortabilityUiFailure(
                    code = "backup_v2_inspection_failed",
                    message = "The selected content archive could not be inspected safely.",
                ),
            )
        } finally {
            if (!settled) mutableState.value = before
        }
        return retained
    }

    // The restore runs under NonCancellable: no cancellation of the caller reaches its catch, and a
    // CancellationException thrown from inside is a failed restore, which the state must show.
    @Suppress("SuspendFunSwallowedCancellation")
    override suspend fun restore(): Unit = operationMutex.withLock {
        val action = checkNotNull(restoreAction) { "Content restore is unavailable" }
        val inspection = checkNotNull(pendingInspection) { "No verified content archive is ready" }
        val source = checkNotNull(pendingArchiveSource) { "No verified content archive is ready" }
        val before = mutableState.value
        check(before.phase == PortableContentBackupV2UiPhase.REVIEW) {
            "Portable content restore is not ready"
        }
        mutableState.value = before.copy(
            phase = PortableContentBackupV2UiPhase.RESTORING,
            failure = null,
        )
        // Only restore takes this lock, and only under operationMutex: it is always free here.
        check(restoreInFlight.tryLock()) { "A content restore is already running" }
        // A confirmed restore runs to the end even if the screen that asked for it goes away: once
        // it commits, the archive is restored, and the state must say so. Its outcome, success or
        // failure, is published inside, because returning to a cancelled caller throws.
        withContext(NonCancellable + workDispatcher) {
            try {
                val result = action.restore(inspection, source)
                releasePendingArchive()
                mutableState.value = mutableState.value.copy(
                    phase = PortableContentBackupV2UiPhase.COMPLETE,
                    restoreReview = null,
                    restoreResult = result,
                    failure = null,
                )
            } catch (logged: Throwable) {
                // Nothing cancels this block (NonCancellable): every throwable is a failed restore.
                Log.warnKindOnly("Backup", logged) { "Restoring the content backup failed" }
                mutableState.value = before.copy(
                    failure = PortabilityUiFailure(
                        code = "backup_v2_restore_failed",
                        message = "The sync-aware content restore did not commit.",
                    ),
                )
            } finally {
                restoreInFlight.unlock()
            }
        }
    }

    override suspend fun awaitRestore() {
        restoreInFlight.withLock { }
    }

    override fun discardRestoreReview() {
        if (mutableState.value.busy) return
        releasePendingArchive()
        mutableState.value = initialState.copy(lastExport = mutableState.value.lastExport)
    }

    private fun releasePendingArchive() {
        pendingArchiveSource?.close()
        pendingArchiveSource = null
        pendingInspection = null
    }
}

private fun BackupV2Inspection.toUiSummary(archiveBytes: Long): PortableContentBackupV2Summary {
    val manifest = envelope.manifest
    return PortableContentBackupV2Summary(
        createdAtEpochMillis = manifest.createdAtEpochMillis,
        appVersion = manifest.appVersion,
        archiveBytes = archiveBytes,
        entryCount = manifest.entries.size,
        contentBlobCount = manifest.entries.count { it.kind == BackupV2EntryKind.CONTENT_BLOB },
        attachmentCount = manifest.attachments.size,
        omittedAttachmentCount = manifest.omittedAttachments.size,
        omittedByReason = manifest.omittedAttachments
            .groupingBy { it.reason }
            .eachCount()
            .toMap(),
        publicationCount = portableState.publications.size,
        annotationCount = portableState.annotations.size,
    )
}
