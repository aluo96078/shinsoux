package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.AppSettings

/**
 * One settings change as the screen asked for it: the field it sets and the value, applied to the
 * settings as stored when the write runs. Writes are queued behind the store, and a screen can call
 * back several times before it is drawn again; a whole settings value made from the drawn ones
 * would undo an earlier change still queued, or lose a change back to the drawn value.
 */
internal typealias SettingsEdit<T> = (T) -> T

/** Queues [edit] off the UI thread; it runs on the stored settings, after the edits before it. */
internal fun UserActions.editSettings(repository: ShinsouRepository, edit: SettingsEdit<AppSettings>) =
    inStores { repository.updateSettings(edit) }
