package dev.shinsou.kmp.ui.core

/**
 * One settings change as the screen asked for it: the field it sets and the value, applied to the
 * settings as stored when the write runs. Writes are queued behind the store, and a screen can call
 * back several times before it is drawn again; a whole settings value made from the drawn ones
 * would undo an earlier change still queued, or lose a change back to the drawn value.
 */
public typealias SettingsEdit<T> = (T) -> T
