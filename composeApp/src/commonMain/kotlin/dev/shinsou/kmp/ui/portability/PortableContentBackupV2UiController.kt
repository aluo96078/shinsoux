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
import dev.shinsou.kmp.backup.PortableContentBackupV2RecoverableRestoreException
import dev.shinsou.kmp.backup.SnapshotRestoreTarget
import dev.shinsou.kmp.ui.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.DEFAULT_BINARY_DOCUMENT_BYTE_ARRAY_COMPATIBILITY_BYTES
import dev.shinsou.kmp.ui.copyToByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

public data class PortableContentBackupV2RestoreResult(
    val publicationCount: Int,
    val annotationCount: Int,
    val contentBlobCount: Int,
    val synchronizedTarget: SnapshotRestoreTarget,
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
     * Implementations must publish bodies and commit portable state, attachments, annotations,
     * and the selected target's sync outbox through one host-owned transaction.
     */
    public suspend fun restore(
        inspection: BackupV2Inspection,
        archiveSource: BackupV2ArchiveSource,
        target: SnapshotRestoreTarget,
    ): PortableContentBackupV2RestoreResult
}

public interface PortableContentBackupV2UiController {
    public val state: StateFlow<PortableContentBackupV2UiState>

    public suspend fun createExport(includeContentBlobs: Boolean): PortableContentBackupV2ExportArtifact?
    public suspend fun inspectForRestore(encoded: ByteArray) {
        inspectForRestore(ByteArrayBackupV2ArchiveSource(encoded))
    }
    public suspend fun inspectForRestore(source: BackupV2ArchiveSource)
    public suspend fun restore(target: SnapshotRestoreTarget)
    public fun discardRestoreReview()
}

/**
 * Bounded decode and checksum validation happen before the host restore action becomes callable.
 * There is intentionally no direct AppSnapshot replacement fallback at this boundary.
 */
public class DefaultPortableContentBackupV2UiController(
    private val createAction: PortableContentBackupV2CreateAction? = null,
    private val restoreAction: PortableContentBackupV2RestoreAction? = null,
) : PortableContentBackupV2UiController {
    private val initialState = PortableContentBackupV2UiState(
        exportAvailable = createAction != null,
        restoreAvailable = restoreAction != null,
    )
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<PortableContentBackupV2UiState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
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
            val created = action.create(includeContentBlobs)
            val (inspection, encodedSize) = withContext(Dispatchers.Default) {
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
        } catch (_: Throwable) {
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

    override suspend fun inspectForRestore(source: BackupV2ArchiveSource): Unit = operationMutex.withLock {
        val before = mutableState.value
        check(!before.busy) { "Another portable backup operation is in progress" }
        pendingArchiveSource = null
        pendingInspection = null
        mutableState.value = before.copy(
            phase = PortableContentBackupV2UiPhase.INSPECTING,
            restoreReview = null,
            restoreResult = null,
            failure = null,
        )
        try {
            if (source.byteSize > DEFAULT_MAX_ARCHIVE_BYTES) {
                throw BackupFormatException("Content backup is too large")
            }
            // Validate and retain the same immutable platform source between review and commit.
            val decoded = withContext(Dispatchers.Default) {
                BackupV2BinaryCodec.decodeInspected(source)
            }
            val inspection = decoded.inspection
            pendingArchiveSource = source
            pendingInspection = inspection
            mutableState.value = mutableState.value.copy(
                phase = PortableContentBackupV2UiPhase.REVIEW,
                restoreReview = inspection.toUiSummary(source.byteSize),
                failure = null,
            )
        } catch (cancelled: CancellationException) {
            mutableState.value = before
            throw cancelled
        } catch (_: BackupFormatException) {
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                failure = PortabilityUiFailure(
                    code = "backup_v2_invalid_archive",
                    message = "The selected content archive failed format or checksum validation.",
                ),
            )
        } catch (_: Throwable) {
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.IDLE,
                failure = PortabilityUiFailure(
                    code = "backup_v2_inspection_failed",
                    message = "The selected content archive could not be inspected safely.",
                ),
            )
        }
    }

    override suspend fun restore(target: SnapshotRestoreTarget): Unit = operationMutex.withLock {
        val action = checkNotNull(restoreAction) { "Sync-aware content restore is unavailable" }
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
        try {
            val result = action.restore(inspection, source, target)
            check(result.synchronizedTarget == target) {
                "Portable restore result reported a different synchronization target"
            }
            pendingArchiveSource = null
            pendingInspection = null
            mutableState.value = mutableState.value.copy(
                phase = PortableContentBackupV2UiPhase.COMPLETE,
                restoreReview = null,
                restoreResult = result,
                failure = null,
            )
        } catch (cancelled: CancellationException) {
            mutableState.value = before
            throw cancelled
        } catch (_: PortableContentBackupV2RecoverableRestoreException) {
            // Keep the exact verified source and inspection so the only safe recovery can be
            // retried without asking the user to select or re-verify a different archive.
            mutableState.value = before.copy(
                phase = PortableContentBackupV2UiPhase.REVIEW,
                failure = PortabilityUiFailure(
                    code = "backup_v2_restore_retry_same_verified_archive",
                    message = "The sync workspace was left, but the device-local restore rolled back. " +
                        "Retry the same verified archive.",
                ),
            )
        } catch (_: Throwable) {
            mutableState.value = before.copy(
                failure = PortabilityUiFailure(
                    code = "backup_v2_restore_failed",
                    message = "The sync-aware content restore did not commit.",
                ),
            )
        }
    }

    override fun discardRestoreReview() {
        if (mutableState.value.busy) return
        pendingArchiveSource = null
        pendingInspection = null
        mutableState.value = initialState.copy(lastExport = mutableState.value.lastExport)
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
