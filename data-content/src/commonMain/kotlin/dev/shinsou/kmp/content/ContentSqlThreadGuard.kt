package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.isMainThread
import dev.shinsou.kmp.logging.Log
import kotlin.concurrent.Volatile

/**
 * R3-4 (docs/architecture/0006): content SQL must not run on the platform's UI thread, where a
 * slow query stalls frames. A debug build fails at once with [MainThreadContentSqlException], so
 * the caller shows up in development; a release build logs the first occurrence, with its stack,
 * and carries on.
 *
 * Opening the stores is the one exception. Every platform now opens them off the UI thread (an
 * I/O dispatcher while the startup screen shows), so [checkWhileOpening] should never fire; it only
 * reports, so that a future caller opening a store on the UI thread is noticed without the app
 * failing to start.
 */
object ContentSqlThreadGuard {
    /**
     * Whether main-thread use fails. Defaults to [contentDebugBuild]; Android, which can only tell
     * a debuggable app from its context, sets it when it opens the database. Tests set it directly.
     */
    @Volatile
    var failOnMainThread: Boolean = contentDebugBuild()

    @Volatile
    private var reported = false

    /** Fails (debug) or reports once (release) when called on the main thread. */
    fun check() {
        if (!isMainThread()) return
        val error = MainThreadContentSqlException()
        if (failOnMainThread) throw error
        report(error)
    }

    /** [check] for the reads made while the stores are opened: reported, never failed. */
    fun checkWhileOpening() {
        if (isMainThread()) report(MainThreadContentSqlException())
    }

    private fun report(error: MainThreadContentSqlException) {
        if (reported) return
        reported = true
        Log.warn(LOG_TAG, error) { "Content database used on the main thread" }
    }

    private const val LOG_TAG = "ContentStore"
}

/** Content SQL was run on the UI thread of a debug build ([ContentSqlThreadGuard]). */
internal class MainThreadContentSqlException :
    IllegalStateException("Content SQL must not run on the main thread; switch to an I/O dispatcher")

/**
 * Whether this is a debug build: on the desktop, the `shinsou.debug` system property, else whether
 * JVM assertions are on (as in tests); false on Android, whose infrastructure sets
 * [ContentSqlThreadGuard.failOnMainThread] from the app's debuggable flag; false on iOS, whose
 * released IPA is a Debug-configuration build and so cannot be told from a developer's.
 */
internal expect fun contentDebugBuild(): Boolean
