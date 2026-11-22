package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.ui.core.SettingsEdit

/** Queues [edit] off the UI thread; it runs on the stored settings, after the edits before it. */
internal fun UserActions.editSettings(repository: ShinsouRepository, edit: SettingsEdit<AppSettings>) =
    inStores { repository.updateSettings(edit) }
