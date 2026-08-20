package dev.shinsou.kmp.app

import androidx.compose.runtime.Composable
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.sync.v2.ReaderProgressReporter
import dev.shinsou.kmp.ui.ShinsouApp
import dev.shinsou.kmp.ui.ShinsouAppServices

/** Convenience composition function used by Android, iOS, and desktop entry points. */
@Composable
fun App(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    autoBackupService: AutoBackupService? = null,
    readerProgressReporter: ReaderProgressReporter? = null,
    interactionReady: Boolean = true,
) {
    ShinsouApp(
        repository = repository,
        appServices = appServices,
        autoBackupService = autoBackupService,
        readerProgressReporter = readerProgressReporter,
        interactionReady = interactionReady,
    )
}
