package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.TranslationBackup

/** What backups need from the translation feature. */
public interface BackupTranslations {
    /** Glossaries and per-work settings, plus the stored translations [state] asks for; null when there is nothing. */
    public suspend fun export(state: BackupState): TranslationBackup?

    public suspend fun restore(backup: TranslationBackup)
}
