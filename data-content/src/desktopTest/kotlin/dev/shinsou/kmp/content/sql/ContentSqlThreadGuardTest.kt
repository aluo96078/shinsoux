package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.shinsou.kmp.content.ContentFoundationRuntime

/** R3-4 (docs/architecture/0006): content SQL stays off the UI thread. */
class ContentSqlThreadGuardTest {
    @Test
    fun aDebugBuildFailsOnContentSqlFromTheMainThread() {
        // Tests run with JVM assertions on, which is what a debug build means on the desktop.
        assertTrue(ContentSqlThreadGuard.failOnMainThread)
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            // Opened on the main thread, as every platform still does: reported, not failed.
            val runtime = onEventThread { ContentFoundationRuntime(driver) }.getOrThrow()

            val failure = onEventThread { runtime.publications.all() }.exceptionOrNull()

            assertIs<MainThreadContentSqlException>(failure)
            assertEquals(emptyList(), runtime.publications.all())
        }
    }

    @Test
    fun aReleaseBuildOnlyReportsContentSqlFromTheMainThread() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val runtime = ContentFoundationRuntime(driver)
            ContentSqlThreadGuard.failOnMainThread = false
            try {
                val result = onEventThread { runtime.publications.all() }
                assertNull(result.exceptionOrNull())
            } finally {
                ContentSqlThreadGuard.failOnMainThread = true
            }
        }
    }

    @Test
    fun anEnclosingTransactionRunsOnTheInjectedDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "content-io-test") }
        try {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                val runtime = ContentFoundationRuntime(driver, ioDispatcher = executor.asCoroutineDispatcher())
                val caller = Thread.currentThread()

                val thread = runtime.transaction {
                    runtime.annotations.replaceAllAtomically(emptyList())
                    Thread.currentThread()
                }

                assertEquals("content-io-test", thread.name)
                assertTrue(thread != caller)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun <T> onEventThread(block: () -> T): Result<T> {
        val result = AtomicReference<Result<T>>()
        SwingUtilities.invokeAndWait {
            check(SwingUtilities.isEventDispatchThread())
            result.set(runCatching(block))
        }
        return result.get()
    }
}
