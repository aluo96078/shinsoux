package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.files.AppFileSystem
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A recoverable, app-private automatic backup exposed to the common Backup UI. */
public data class AutoBackupEntry(
    val fileName: String,
    val createdAt: Long,
    val appVersion: String = "",
    val sizeBytes: Long = 0,
    val recoverable: Boolean = true,
    val errorMessage: String? = null,
)

/** Result used by foreground loops and OS-managed background workers. */
public sealed interface AutoBackupRunResult {
    public data object Disabled : AutoBackupRunResult

    public data class NotDue(val nextEligibleAt: Long) : AutoBackupRunResult

    public data class Created(val backup: AutoBackupEntry) : AutoBackupRunResult

    public data class Failed(val message: String) : AutoBackupRunResult
}

public class AutoBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Creates and manages app-private [BackupEnvelope] files.
 *
 * Every public mutation is serialized. This makes foreground lifecycle callbacks and platform
 * background workers safe to call concurrently without producing duplicate due backups.
 */
public class AutoBackupService(
    private val repository: ShinsouRepository,
    private val fileSystem: AppFileSystem,
    private val appVersion: String = dev.shinsou.kmp.app.SHINSOU_APP_VERSION,
    private val deviceId: String? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Translation data for backups (ADR-0004 §4.2), set once the app's translation feature
     * exists. A background run that has none writes a backup without it.
     */
    @kotlin.concurrent.Volatile
    public var translations: BackupTranslations? = null

    /** Creates a backup only when automatic backup is enabled and its interval has elapsed. */
    public suspend fun runIfDue(): AutoBackupRunResult = AUTO_BACKUP_OPERATION_MUTEX.withLock {
        val state = repository.currentSnapshot.backupState
        if (!state.automaticEnabled) return@withLock AutoBackupRunResult.Disabled
        val currentTime = now().coerceAtLeast(0)
        val nextEligibleAt = nextAutoBackupAt(state)
        if (nextEligibleAt != null && currentTime < nextEligibleAt) {
            return@withLock AutoBackupRunResult.NotDue(nextEligibleAt)
        }
        createLocked(currentTime)
    }

    /** Creates an app-private backup immediately, independently of the automatic schedule toggle. */
    public suspend fun createNow(): AutoBackupEntry = AUTO_BACKUP_OPERATION_MUTEX.withLock {
        when (val result = createLocked(now().coerceAtLeast(0))) {
            is AutoBackupRunResult.Created -> result.backup
            is AutoBackupRunResult.Failed -> throw AutoBackupException(result.message)
            AutoBackupRunResult.Disabled,
            is AutoBackupRunResult.NotDue,
            -> error("Forced backup returned a schedule-only result")
        }
    }

    /** Lists valid and damaged automatic backups, newest first, so damaged files can be deleted. */
    public suspend fun listBackups(): List<AutoBackupEntry> = AUTO_BACKUP_OPERATION_MUTEX.withLock { listBackupsLocked() }

    /** Applies the current retention setting immediately and returns the remaining backups. */
    public suspend fun enforceRetention(): List<AutoBackupEntry> = AUTO_BACKUP_OPERATION_MUTEX.withLock {
        val retainedCount = repository.currentSnapshot.backupState.retainedBackupCount.coerceAtLeast(1)
        pruneLocked(retainedCount)
        listBackupsLocked()
    }

    /** Restores every domain from one app-private automatic backup. */
    public suspend fun restore(
        fileName: String,
        selection: RestoreSelection = RestoreSelection.All,
    ): RestoreResult = AUTO_BACKUP_OPERATION_MUTEX.withLock {
        restoreLocked(fileName, selection)
    }

    private suspend fun restoreLocked(
        fileName: String,
        selection: RestoreSelection,
    ): RestoreResult {
        val safeName = validatedAutoBackupFileName(fileName)
        val started = repository.currentSnapshot.backupState
        repository.setBackupState(started.copy(status = BackupStatus.RESTORING, errorMessage = null))
        return try {
            val bytes = fileSystem.read("$AUTO_BACKUP_DIRECTORY/$safeName")
                ?: throw AutoBackupException("Automatic backup no longer exists: $safeName")
            val envelope = try {
                SnapshotBackupService.decode(bytes.decodeToString())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw AutoBackupException("Automatic backup is damaged or unsupported: $safeName", error)
            }
            val restoredAt = now().coerceAtLeast(0)
            val result = repository.restoreBackup(envelope, selection, restoredAt)
            envelope.translations?.let { backup -> translations?.restore(backup) }
            repository.setBackupState(
                repository.currentSnapshot.backupState.copy(
                    status = BackupStatus.COMPLETED,
                    lastRestoreAt = restoredAt,
                    lastFileName = safeName,
                    errorMessage = null,
                ),
            )
            result.copy(snapshot = repository.currentSnapshot)
        } catch (cancelled: CancellationException) {
            markFailureNonCancellable("Automatic backup restore was interrupted.")
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: "Unable to restore automatic backup."
            markFailure(message)
            if (error is AutoBackupException) throw error
            throw AutoBackupException(message, error)
        }
    }

    public suspend fun delete(fileName: String): Boolean = AUTO_BACKUP_OPERATION_MUTEX.withLock {
        val safeName = validatedAutoBackupFileName(fileName)
        fileSystem.delete("$AUTO_BACKUP_DIRECTORY/$safeName")
    }

    private suspend fun createLocked(createdAt: Long): AutoBackupRunResult {
        repository.setBackupState(
            repository.currentSnapshot.backupState.copy(
                status = BackupStatus.CREATING,
                errorMessage = null,
            ),
        )
        return try {
            val envelope = repository.createBackupEnvelope(
                createdAt = createdAt,
                appVersion = appVersion,
                deviceId = deviceId,
            ).withTranslations(translations, repository.currentSnapshot.backupState)
            val fileName = autoBackupFileName(createdAt, envelope.snapshot.revision)
            val bytes = SnapshotBackupService.encode(envelope).encodeToByteArray()
            fileSystem.writeAtomically("$AUTO_BACKUP_DIRECTORY/$fileName", bytes)

            // Old snapshots may contain zero. Keep the newly-created backup recoverable while the
            // current UI only offers positive retention values.
            val retainedCount = repository.currentSnapshot.backupState.retainedBackupCount.coerceAtLeast(1)
            pruneLocked(retainedCount)

            repository.setBackupState(
                repository.currentSnapshot.backupState.copy(
                    status = BackupStatus.COMPLETED,
                    destination = AUTO_BACKUP_DIRECTORY,
                    lastBackupAt = createdAt,
                    lastFileName = fileName,
                    errorMessage = null,
                ),
            )
            AutoBackupRunResult.Created(
                AutoBackupEntry(
                    fileName = fileName,
                    createdAt = createdAt,
                    appVersion = appVersion,
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        } catch (cancelled: CancellationException) {
            markFailureNonCancellable("Automatic backup was interrupted.")
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: "Unable to create automatic backup."
            markFailure(message)
            AutoBackupRunResult.Failed(message)
        }
    }

    private suspend fun pruneLocked(retainedCount: Int) {
        listBackupsLocked()
            .drop(retainedCount.coerceAtLeast(0))
            .forEach { fileSystem.delete("$AUTO_BACKUP_DIRECTORY/${it.fileName}") }
    }

    private suspend fun listBackupsLocked(): List<AutoBackupEntry> = fileSystem.list(AUTO_BACKUP_DIRECTORY)
        .mapNotNull { path ->
            val normalized = path.replace('\\', '/').trim('/')
            if (normalized.substringBeforeLast('/', "") != AUTO_BACKUP_DIRECTORY) return@mapNotNull null
            val fileName = normalized.substringAfterLast('/')
            val fallbackCreatedAt = autoBackupTimestamp(fileName) ?: return@mapNotNull null
            val bytes = fileSystem.read(normalized)
                ?: return@mapNotNull AutoBackupEntry(
                    fileName = fileName,
                    createdAt = fallbackCreatedAt,
                    recoverable = false,
                    errorMessage = "Backup file is unavailable.",
                )
            try {
                val envelope = SnapshotBackupService.decode(bytes.decodeToString())
                AutoBackupEntry(
                    fileName = fileName,
                    createdAt = envelope.createdAt,
                    appVersion = envelope.appVersion,
                    sizeBytes = bytes.size.toLong(),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AutoBackupEntry(
                    fileName = fileName,
                    createdAt = fallbackCreatedAt,
                    sizeBytes = bytes.size.toLong(),
                    recoverable = false,
                    errorMessage = error.message ?: "Backup is damaged.",
                )
            }
        }
        .sortedWith(compareByDescending<AutoBackupEntry> { it.createdAt }.thenByDescending { it.fileName })

    private suspend fun markFailure(message: String) {
        runCatching {
            repository.setBackupState(
                repository.currentSnapshot.backupState.copy(
                    status = BackupStatus.FAILED,
                    errorMessage = message,
                ),
            )
        }
    }

    private suspend fun markFailureNonCancellable(message: String) {
        withContext(NonCancellable) { markFailure(message) }
    }
}

/** Returns null when there has never been a successful backup. */
public fun nextAutoBackupAt(state: BackupState): Long? {
    val lastBackupAt = state.lastBackupAt ?: return null
    val intervalMillis = state.intervalHours.coerceAtLeast(1).toLong() * MILLIS_PER_HOUR
    return if (lastBackupAt > Long.MAX_VALUE - intervalMillis) Long.MAX_VALUE else lastBackupAt + intervalMillis
}

private fun autoBackupFileName(createdAt: Long, revision: Long): String =
    "auto-$createdAt-r${revision.coerceAtLeast(0)}.$AUTO_BACKUP_EXTENSION"

private fun autoBackupTimestamp(fileName: String): Long? = AUTO_BACKUP_FILE_PATTERN.matchEntire(fileName)
    ?.groupValues
    ?.getOrNull(1)
    ?.toLongOrNull()

private fun validatedAutoBackupFileName(fileName: String): String {
    require(AUTO_BACKUP_FILE_PATTERN.matches(fileName)) { "Invalid automatic backup name: $fileName" }
    return fileName
}

public const val AUTO_BACKUP_DIRECTORY: String = "backups"
public const val AUTO_BACKUP_EXTENSION: String = "shinsoubackup"
private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
private val AUTO_BACKUP_FILE_PATTERN = Regex("^auto-([0-9]{1,19})-r([0-9]{1,19})\\.$AUTO_BACKUP_EXTENSION$")
private val AUTO_BACKUP_OPERATION_MUTEX = Mutex()


/** What backups need from the translation feature. */
public interface BackupTranslations {
    /** Glossaries and per-work settings, plus the stored translations [state] asks for; null when there is nothing. */
    public suspend fun export(state: dev.shinsou.kmp.domain.model.BackupState): dev.shinsou.kmp.translation.TranslationBackup?

    public suspend fun restore(backup: dev.shinsou.kmp.translation.TranslationBackup)
}

/** [ReaderTranslation] as a backup source. */
public class ReaderTranslationBackups(private val translation: dev.shinsou.kmp.translation.ReaderTranslation) : BackupTranslations {
    override suspend fun export(state: dev.shinsou.kmp.domain.model.BackupState): dev.shinsou.kmp.translation.TranslationBackup? =
        translation.exportBackup(state.includePaidTranslations, state.includeFreeTranslations).takeUnless { it.isEmpty }

    override suspend fun restore(backup: dev.shinsou.kmp.translation.TranslationBackup): Unit = translation.importBackup(backup)
}

/** The translation memory as a backup source, for a scheduler that runs without the app's UI. */
public class TranslationMemoryBackups(private val memory: dev.shinsou.kmp.translation.TranslationMemoryStore) : BackupTranslations {
    override suspend fun export(state: dev.shinsou.kmp.domain.model.BackupState): dev.shinsou.kmp.translation.TranslationBackup? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            memory.exportBackup(
                state.includePaidTranslations,
                state.includeFreeTranslations,
                dev.shinsou.kmp.translation.ReaderTranslation.BACKUP_SEGMENT_BUDGET_BYTES,
            ).takeUnless { it.isEmpty }
        }

    override suspend fun restore(backup: dev.shinsou.kmp.translation.TranslationBackup): Unit =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            memory.importBackup(backup, kotlin.time.Clock.System.now().toEpochMilliseconds())
        }
}

/** Adds translation data to an envelope; a failure to read it must not cost the user the backup. */
public suspend fun BackupEnvelope.withTranslations(
    translations: BackupTranslations?,
    state: dev.shinsou.kmp.domain.model.BackupState,
): BackupEnvelope {
    val backup = try {
        translations?.export(state)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    return if (backup == null) this else copy(translations = backup)
}
