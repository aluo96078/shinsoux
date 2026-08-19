package dev.shinsou.kmp.app

import androidx.compose.runtime.Composable
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.ui.ShinsouApp
import dev.shinsou.kmp.ui.ShinsouAppServices

/** Convenience composition function used by Android, iOS, and desktop entry points. */
@Composable
fun App(
    repository: ShinsouRepository,
    appServices: ShinsouAppServices = ShinsouAppServices.None,
    autoBackupService: AutoBackupService? = null,
) {
    ShinsouApp(
        repository = repository,
        appServices = appServices,
        autoBackupService = autoBackupService,
    )
}
