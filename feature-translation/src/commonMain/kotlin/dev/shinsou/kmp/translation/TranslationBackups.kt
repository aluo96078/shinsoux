package dev.shinsou.kmp.translation

import dev.shinsou.kmp.contract.BackupTranslations
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.TranslationBackup

/** [ReaderTranslation] as a backup source. */
public class ReaderTranslationBackups(private val translation: ReaderTranslation) : BackupTranslations {
    override suspend fun export(state: BackupState): TranslationBackup? =
        translation.exportBackup(state.includePaidTranslations, state.includeFreeTranslations).takeUnless { it.isEmpty }

    override suspend fun restore(backup: TranslationBackup): Unit = translation.importBackup(backup)
}

/** The translation memory as a backup source, for a scheduler that runs without the app's UI. */
public class TranslationMemoryBackups(
    private val memory: TranslationMemoryStore,
    /** Where the memory store's blocking reads and writes run. */
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : BackupTranslations {
    override suspend fun export(state: BackupState): TranslationBackup? =
        kotlinx.coroutines.withContext(dispatcher) {
            memory.exportBackup(
                state.includePaidTranslations,
                state.includeFreeTranslations,
                ReaderTranslation.BACKUP_SEGMENT_BUDGET_BYTES,
            ).takeUnless { it.isEmpty }
        }

    override suspend fun restore(backup: TranslationBackup): Unit =
        kotlinx.coroutines.withContext(dispatcher) {
            memory.importBackup(backup, kotlin.time.Clock.System.now().toEpochMilliseconds())
        }
}
