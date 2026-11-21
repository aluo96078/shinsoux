package dev.shinsou.kmp

import dev.shinsou.kmp.logging.Log
import java.io.File
import java.io.IOException

/**
 * The links still waiting when the activity saved its state, kept in the app's own no-backup
 * storage rather than in the saved-state Bundle: the Bundle leaves the process (to the system, and
 * into task snapshots) and a link can carry a token, so it holds only their hashes. After process
 * death, the activity recreated with its original launch intent reads back the links whose hashes
 * its Bundle names; one that arrived later through onNewIntent is otherwise lost.
 */
internal class PendingDeepLinkFile(private val file: File) {
    /**
     * Replaces what is kept with [links]. An empty list is kept too: the file then says that
     * nothing waits, which [take] tells apart from a file that could not be written.
     */
    fun keep(links: List<String>) {
        try {
            val partial = File(file.parentFile, "${file.name}.partial")
            partial.writeText(links.joinToString(LINE_SEPARATOR))
            if (!partial.renameTo(file)) throw IOException("The pending links could not be replaced")
        } catch (error: IOException) {
            Log.warnKindOnly(TAG, error) { "The pending links could not be kept" }
        }
    }

    /** [url] was handled: it is no longer replayed after process death. */
    fun forget(url: String) {
        val links = read() ?: return
        val index = links.indexOf(url)
        if (index >= 0) keep(links.filterIndexed { i, _ -> i != index })
    }

    /**
     * The kept links whose hashes are in [waiting], in arrival order; null when there was no file
     * to read (it could not be written). They stay kept until [forget] (handled) or the next
     * [keep]: the process can die again before the user answers them.
     */
    fun take(waiting: IntArray): List<String>? {
        val links = read()?.filter { it.hashCode() in waiting } ?: return null
        keep(links)
        return links
    }

    private fun read(): List<String>? = try {
        if (file.isFile) file.readText().split(LINE_SEPARATOR).filter { it.isNotEmpty() } else null
    } catch (error: IOException) {
        Log.warnKindOnly(TAG, error) { "The pending links could not be read" }
        null
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val TAG = "PendingDeepLinks"
        private const val LINE_SEPARATOR = "\n"
        private const val PREFIX = "pending-deep-links-"
        private const val SUFFIX = ".txt"

        /**
         * The links of activity [activityId] in task [taskId]: each activity restores its own, so
         * another (a new launch, or one beside it in the same task) cannot take or remove them.
         */
        fun forActivity(directory: File, taskId: Int, activityId: String) =
            PendingDeepLinkFile(File(directory, "$PREFIX$taskId-${activityId.filter { it.isLetterOrDigit() || it == '-' }}$SUFFIX"))

        /** Removes the kept links of tasks that no longer exist (the user cleared them from Recents). */
        fun removeAllBut(directory: File, liveTaskIds: Set<Int>) {
            directory.listFiles()?.forEach { file ->
                val taskId = file.name.removePrefix(PREFIX).substringBefore('-').substringBefore('.').toIntOrNull()
                if (file.name.startsWith(PREFIX) && (taskId == null || taskId !in liveTaskIds)) file.delete()
            }
        }
    }
}
