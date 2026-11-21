package dev.shinsou.kmp

import dev.shinsou.kmp.ui.ShellStateStore
import platform.Foundation.NSUserDefaults

/**
 * Keeps the shell's state in the app's own defaults, so a launch after iOS ended the backgrounded
 * process reopens what was shown. It holds row ids and enum names only ([dev.shinsou.kmp.ui.ShellSavedState]).
 */
internal object IosShellStateStore : ShellStateStore {
    private const val KEY = "dev.shinsou.shell-state"

    override fun read(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

    override fun write(state: String) {
        NSUserDefaults.standardUserDefaults.setObject(state, forKey = KEY)
    }
}
