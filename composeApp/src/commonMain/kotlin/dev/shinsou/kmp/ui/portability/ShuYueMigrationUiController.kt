package dev.shinsou.kmp.ui.portability

import dev.shinsou.kmp.migration.shuyue.ShuYueBackupV1ErrorCode
import dev.shinsou.kmp.migration.shuyue.ShuYueImportPreparer
import dev.shinsou.kmp.migration.shuyue.ShuYueImportSelection
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationConflictException
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationPreview
import dev.shinsou.kmp.migration.shuyue.ShuYuePreparedImport
import dev.shinsou.kmp.migration.shuyue.ShuYueSecretImportConsent
import dev.shinsou.kmp.migration.shuyue.ShuYueSecretImportResult
import dev.shinsou.kmp.migration.shuyue.ShuYueTransactionalImportResult
import dev.shinsou.kmp.migration.shuyue.ShuYueTransactionalImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public enum class ShuYueMigrationUiPhase {
    IDLE,
    INSPECTING,
    REJECTED,
    REVIEW,
    IMPORTING_CONTENT,
    CONTENT_IMPORTED,
    IMPORTING_SECRETS,
    COMPLETE,
}

public data class PortabilityUiFailure(
    val code: String,
    val message: String,
)

public typealias ShuYueMigrationUiFailure = PortabilityUiFailure

public data class ShuYueContentImportSummary(
    val replayed: Boolean,
    val publicationCount: Int,
    val unitCount: Int,
    val contentBlobCount: Int,
    val quarantineCount: Int,
    val categoryCount: Int,
    val progressCount: Int,
)

/**
 * UI state contains only the redacted preview and selection identifiers. The prepared capability,
 * chapter bodies, scripts, cookies, and credential values remain private to the controller.
 */
public data class ShuYueMigrationUiState(
    val phase: ShuYueMigrationUiPhase = ShuYueMigrationUiPhase.IDLE,
    val preview: ShuYueMigrationPreview? = null,
    /** Null is the importer's explicit representation for every staged book. */
    val selectedBookIds: Set<String>? = null,
    /** Null means every staged executable payload remains selected for quarantine. */
    val selectedPluginDigests: Set<String>? = null,
    val includeProgress: Boolean = true,
    val includeReaderSettings: Boolean = true,
    /** Defaults off: importing a book is not consent to upload its body bytes. */
    val includeContentBodySync: Boolean = false,
    val includeCredentials: Boolean = false,
    val includeCookies: Boolean = false,
    val secretImportAvailable: Boolean = false,
    val contentImport: ShuYueContentImportSummary? = null,
    val secretImport: ShuYueSecretImportResult? = null,
    val failure: ShuYueMigrationUiFailure? = null,
) {
    public val busy: Boolean
        get() = phase == ShuYueMigrationUiPhase.INSPECTING ||
            phase == ShuYueMigrationUiPhase.IMPORTING_CONTENT ||
            phase == ShuYueMigrationUiPhase.IMPORTING_SECRETS

    public val acceptedForReview: Boolean
        get() = preview != null && phase !in setOf(
            ShuYueMigrationUiPhase.IDLE,
            ShuYueMigrationUiPhase.INSPECTING,
            ShuYueMigrationUiPhase.REJECTED,
        )

    override fun toString(): String =
        "ShuYueMigrationUiState(phase=$phase, preview=$preview, " +
            "selectedBooks=${selectedBookIds?.size ?: "all"}, " +
            "selectedPlugins=${selectedPluginDigests?.size ?: "all"}, " +
            "includeProgress=$includeProgress, includeReaderSettings=$includeReaderSettings, " +
            "includeContentBodySync=$includeContentBodySync, " +
            "includeCredentials=$includeCredentials, includeCookies=$includeCookies, " +
            "contentImport=$contentImport, secretImport=$secretImport, failure=$failure)"
}

public fun interface ShuYueContentImportAction {
    public suspend fun import(
        prepared: ShuYuePreparedImport,
        selection: ShuYueImportSelection,
    ): ShuYueTransactionalImportResult
}

public fun interface ShuYueSecretImportAction {
    public suspend fun import(
        prepared: ShuYuePreparedImport,
        consent: ShuYueSecretImportConsent,
    ): ShuYueSecretImportResult
}

public interface ShuYueMigrationUiController {
    public val state: StateFlow<ShuYueMigrationUiState>

    public suspend fun inspect(encoded: ByteArray)
    public fun selectAllBooks(selected: Boolean)
    public fun setBookSelected(bookId: String, selected: Boolean)
    public fun selectAllQuarantinedPlugins(selected: Boolean)
    public fun setQuarantinedPluginSelected(sha256: String, selected: Boolean)
    public fun setIncludeProgress(include: Boolean)
    public fun setIncludeReaderSettings(include: Boolean)
    public fun setIncludeContentBodySync(include: Boolean)
    public fun setIncludeCredentials(include: Boolean)
    public fun setIncludeCookies(include: Boolean)
    public suspend fun importContent()
    public suspend fun importSelectedSecrets(confirmedAtEpochMillis: Long)
    public fun reset()
}

/**
 * Host-owned actions keep file access, SQL/outbox wiring, and the platform secret store outside
 * Compose while this controller enforces the preparation/selection/consent sequence.
 */
public class DefaultShuYueMigrationUiController(
    private val contentImportAction: ShuYueContentImportAction,
    private val secretImportAction: ShuYueSecretImportAction? = null,
) : ShuYueMigrationUiController {
    private val mutableState = MutableStateFlow(ShuYueMigrationUiState())
    override val state: StateFlow<ShuYueMigrationUiState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var preparedImport: ShuYuePreparedImport? = null

    override suspend fun inspect(encoded: ByteArray): Unit = operationMutex.withLock {
        mutableState.value = ShuYueMigrationUiState(phase = ShuYueMigrationUiPhase.INSPECTING)
        preparedImport = null
        try {
            val result = withContext(Dispatchers.Default) { ShuYueImportPreparer.prepare(encoded) }
            val prepared = result.preparedImport
            if (prepared == null) {
                mutableState.value = ShuYueMigrationUiState(
                    phase = ShuYueMigrationUiPhase.REJECTED,
                    preview = result.inspection.preview,
                    secretImportAvailable = false,
                    failure = inspectionFailure(result.inspection.errorCode),
                )
            } else {
                preparedImport = prepared
                mutableState.value = ShuYueMigrationUiState(
                    phase = ShuYueMigrationUiPhase.REVIEW,
                    preview = result.inspection.preview,
                    secretImportAvailable = secretImportAction != null,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = ShuYueMigrationUiState()
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = ShuYueMigrationUiState(
                phase = ShuYueMigrationUiPhase.REJECTED,
                failure = ShuYueMigrationUiFailure(
                    code = "inspection_failed",
                    message = "The ShuYue backup could not be inspected safely.",
                ),
            )
        }
    }

    override fun selectAllBooks(selected: Boolean) = updateReviewSelection {
        copy(selectedBookIds = if (selected) null else emptySet())
    }

    override fun setBookSelected(bookId: String, selected: Boolean) = updateReviewSelection { current ->
        val preview = requireNotNull(current.preview)
        require(preview.bookSummaries.any { it.id == bookId }) { "Unknown ShuYue preview book" }
        require(!preview.bookSummariesTruncated) {
            "Individual book selection is unavailable for a truncated preview"
        }
        val available = preview.bookSummaries.mapTo(linkedSetOf()) { it.id }
        val selectedIds = current.selectedBookIds?.toMutableSet() ?: available
        if (selected) selectedIds += bookId else selectedIds -= bookId
        current.copy(selectedBookIds = selectedIds.toSet())
    }

    override fun selectAllQuarantinedPlugins(selected: Boolean) = updateReviewSelection {
        copy(selectedPluginDigests = if (selected) null else emptySet())
    }

    override fun setQuarantinedPluginSelected(sha256: String, selected: Boolean) =
        updateReviewSelection { current ->
            val preview = requireNotNull(current.preview)
            require(preview.quarantinedPlugins.any { it.sha256 == sha256 }) {
                "Unknown quarantined ShuYue plugin"
            }
            require(!preview.quarantinedPluginsTruncated) {
                "Individual plugin selection is unavailable for a truncated preview"
            }
            val available = preview.quarantinedPlugins.mapTo(linkedSetOf()) { it.sha256 }
            val selectedDigests = current.selectedPluginDigests?.toMutableSet() ?: available
            if (selected) selectedDigests += sha256 else selectedDigests -= sha256
            current.copy(selectedPluginDigests = selectedDigests.toSet())
        }

    override fun setIncludeProgress(include: Boolean) = updateReviewSelection {
        copy(includeProgress = include)
    }

    override fun setIncludeReaderSettings(include: Boolean) = updateReviewSelection {
        copy(includeReaderSettings = include)
    }

    override fun setIncludeContentBodySync(include: Boolean) = updateReviewSelection {
        copy(includeContentBodySync = include)
    }

    override fun setIncludeCredentials(include: Boolean) = updateReviewSelection {
        copy(includeCredentials = include)
    }

    override fun setIncludeCookies(include: Boolean) = updateReviewSelection {
        copy(includeCookies = include)
    }

    override suspend fun importContent(): Unit = operationMutex.withLock {
        val prepared = checkNotNull(preparedImport) { "No reviewed ShuYue backup is available" }
        val before = mutableState.value
        check(before.phase == ShuYueMigrationUiPhase.REVIEW) {
            "ShuYue content import is not ready"
        }
        mutableState.value = before.copy(
            phase = ShuYueMigrationUiPhase.IMPORTING_CONTENT,
            failure = null,
        )
        val selection = ShuYueImportSelection(
            selectedBookIds = before.selectedBookIds,
            includeProgress = before.includeProgress,
            includeReaderSettings = before.includeReaderSettings,
            includeContentBodySync = before.includeContentBodySync,
            quarantinedPluginDigests = before.selectedPluginDigests,
        )
        try {
            val result = contentImportAction.import(prepared, selection)
            mutableState.value = mutableState.value.copy(
                phase = ShuYueMigrationUiPhase.CONTENT_IMPORTED,
                contentImport = result.toUiSummary(),
                failure = null,
            )
        } catch (cancelled: CancellationException) {
            mutableState.value = before
            throw cancelled
        } catch (conflict: ShuYueMigrationConflictException) {
            mutableState.value = before.copy(
                failure = ShuYueMigrationUiFailure(
                    code = "migration_conflict",
                    message = "This backup was already imported with a different selection.",
                ),
            )
        } catch (_: Throwable) {
            mutableState.value = before.copy(
                failure = ShuYueMigrationUiFailure(
                    code = "content_import_failed",
                    message = "The transactional ShuYue import did not complete.",
                ),
            )
        }
    }

    override suspend fun importSelectedSecrets(confirmedAtEpochMillis: Long): Unit =
        operationMutex.withLock {
            val prepared = checkNotNull(preparedImport) { "No reviewed ShuYue backup is available" }
            val action = checkNotNull(secretImportAction) { "Protected ShuYue secret storage is unavailable" }
            val before = mutableState.value
            check(before.phase == ShuYueMigrationUiPhase.CONTENT_IMPORTED) {
                "Import content before importing ShuYue secrets"
            }
            check(before.includeCredentials || before.includeCookies) {
                "No ShuYue secret kind was selected"
            }
            val consent = prepared.confirmSecretImport(
                credentialSourceIds = if (before.includeCredentials) {
                    prepared.availableCredentialSourceIds()
                } else {
                    emptySet()
                },
                cookieSourceIds = if (before.includeCookies) {
                    prepared.availableCookieSourceIds()
                } else {
                    emptySet()
                },
                confirmedAtEpochMillis = confirmedAtEpochMillis,
            )
            mutableState.value = before.copy(
                phase = ShuYueMigrationUiPhase.IMPORTING_SECRETS,
                failure = null,
            )
            try {
                val result = action.import(prepared, consent)
                mutableState.value = mutableState.value.copy(
                    phase = ShuYueMigrationUiPhase.COMPLETE,
                    secretImport = result,
                    failure = null,
                )
            } catch (cancelled: CancellationException) {
                mutableState.value = before
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = before.copy(
                    failure = ShuYueMigrationUiFailure(
                        code = "secret_import_failed",
                        message = "No ShuYue secrets were replaced because protected storage failed.",
                    ),
                )
            }
        }

    override fun reset() {
        if (mutableState.value.busy) return
        preparedImport = null
        mutableState.value = ShuYueMigrationUiState()
    }

    private inline fun updateReviewSelection(
        transform: ShuYueMigrationUiState.(ShuYueMigrationUiState) -> ShuYueMigrationUiState,
    ) {
        val current = mutableState.value
        if (current.phase != ShuYueMigrationUiPhase.REVIEW) return
        mutableState.value = current.transform(current)
    }
}

/** Production adapter retaining the importer's sync-coverage checks and strict secret-store gate. */
public fun <D : Any> createShuYueMigrationUiController(
    importer: ShuYueTransactionalImporter<D>,
    secretStore: ShuYueMigrationSecretStore?,
): ShuYueMigrationUiController = DefaultShuYueMigrationUiController(
    contentImportAction = ShuYueContentImportAction { prepared, selection ->
        importer.import(prepared, selection)
    },
    secretImportAction = secretStore?.let { protectedStore ->
        ShuYueSecretImportAction { prepared, consent ->
            importer.importSecrets(prepared, consent, protectedStore)
        }
    },
)

private fun inspectionFailure(code: ShuYueBackupV1ErrorCode?): ShuYueMigrationUiFailure =
    ShuYueMigrationUiFailure(
        code = code?.name?.lowercase() ?: "validation_failed",
        message = "The selected file is not an importable ShuYue v1 backup.",
    )

private fun ShuYueTransactionalImportResult.toUiSummary(): ShuYueContentImportSummary =
    ShuYueContentImportSummary(
        replayed = replayed,
        publicationCount = publicationCount,
        unitCount = unitCount,
        contentBlobCount = contentBlobCount,
        quarantineCount = quarantineCount,
        categoryCount = categoryCount,
        progressCount = progressCount,
    )
