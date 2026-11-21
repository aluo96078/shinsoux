package dev.shinsou.kmp.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogTest {
    private val records = mutableListOf<Triple<LogLevel, String, Throwable?>>()
    private lateinit var previousLogger: Logger
    private lateinit var previousLevel: LogLevel

    @BeforeTest
    fun capture() {
        previousLogger = Log.logger
        previousLevel = Log.minimumLevel
        Log.logger = Logger { level, tag, message, error -> records += Triple(level, "$tag: $message", error) }
    }

    @AfterTest
    fun restore() {
        Log.logger = previousLogger
        Log.minimumLevel = previousLevel
    }

    @Test
    fun recordsBelowTheMinimumAreNotBuilt() {
        Log.minimumLevel = LogLevel.WARN
        var built = false
        Log.debug("t") { built = true; "x" }
        Log.info("t") { built = true; "x" }
        assertFalse(built)
        assertEquals(emptyList(), records)
    }

    @Test
    fun warningsAndErrorsReachTheLoggerWithTheirCause() {
        Log.minimumLevel = LogLevel.WARN
        val cause = IllegalStateException("boom")
        Log.warn("Queue", cause) { "page failed" }
        Log.error("Store") { "write failed" }
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), records.map { it.first })
        assertEquals("Queue: page failed", records[0].second)
        assertSame(cause, records[0].third)
    }

    @Test
    fun aFailingLoggerDoesNotThrow() {
        Log.logger = Logger { _, _, _, _ -> error("logger is broken") }
        Log.error("t") { "x" }
    }

    @Test
    fun theDefaultIsWarnings() {
        assertEquals(LogLevel.WARN, previousLevel)
    }

    @Test
    fun decodingFailuresAreLoggedWithoutTheInputTheyQuote() {
        Log.minimumLevel = LogLevel.WARN
        val decoding = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>("{\"cookie\": \"secret-value\"")
        }.exceptionOrNull()
        requireNotNull(decoding)
        Log.warn("t", IllegalStateException("wrapped", decoding)) { "x" }
        val logged = records.single().third
        assertIs<RedactedLogException>(logged)
        val rendered = logged.toString()
        assertFalse("secret-value" in rendered, rendered)
        assertFalse("JSON input" in rendered, rendered)
        assertTrue("IllegalStateException" in rendered && "JsonDecodingException" in rendered, rendered)
    }

    @Test
    fun networkFailuresAreLoggedWithoutTheUrlTheyQuote() {
        Log.minimumLevel = LogLevel.WARN
        val redirect = IllegalStateException("Redirect limit exceeded: https://cdn.test/p.jpg?token=secret-value")
        Log.warn("t", RuntimeException("wrapped", redirect)) { "x" }
        val rendered = records.single().third.toString()
        assertIs<RedactedLogException>(records.single().third)
        assertFalse("secret-value" in rendered, rendered)
        assertFalse("cdn.test" in rendered, rendered)
        assertTrue("RuntimeException" in rendered && "IllegalStateException" in rendered, rendered)
    }

    @Test
    fun suppressedFailuresAreSearchedToo() {
        Log.minimumLevel = LogLevel.WARN
        val failure = IllegalStateException("closing failed")
        failure.addSuppressed(IllegalArgumentException("https://cdn.test/p.jpg?token=secret-value"))
        Log.warn("t", failure) { "x" }
        // Platform loggers print suppressed failures with the stack trace.
        val logged = records.single().third
        assertIs<RedactedLogException>(logged)
        assertFalse("secret-value" in logged.stackTraceToString(), logged.stackTraceToString())
    }

    @Test
    fun aFailureTooDeepToSearchIsWithheld() {
        Log.minimumLevel = LogLevel.WARN
        var failure: Throwable = IllegalStateException("https://cdn.test/?token=secret-value")
        repeat(20) { failure = RuntimeException("wrapped $it", failure) }
        Log.warn("t", failure) { "x" }
        assertIs<RedactedLogException>(records.single().third)
    }

    @Test
    fun networkFailuresAreWithheldEvenWithoutAUrl() {
        Log.minimumLevel = LogLevel.WARN
        Log.warn("t", java.net.UnknownHostException("private-library-host.test")) { "x" }
        val rendered = records.single().third.toString()
        assertFalse("private-library-host" in rendered, rendered)
        assertTrue("UnknownHostException" in rendered, rendered)
    }

    @Test
    fun failuresQuotingAServerAreLoggedByTheirSummary() {
        Log.minimumLevel = LogLevel.WARN
        val server = object : Exception("token secret-value is invalid"), QuotesExternalText {
            override val loggableSummary = "Server(status=401)"
        }
        Log.warn("t", server) { "x" }
        val rendered = records.single().third.toString()
        assertFalse("secret-value" in rendered, rendered)
        assertTrue("Server(status=401)" in rendered, rendered)
    }

    @Test
    fun aFailureReadingTheUsersFilesIsLoggedByItsKindOnly() {
        Log.warnKindOnly("Backup", IllegalStateException("Cannot read “Holiday photos 2024.zip”")) { "Restoring failed" }
        val (level, text, error) = records.single()
        assertEquals(LogLevel.WARN, level)
        assertEquals("Backup: Restoring failed (IllegalStateException)", text)
        assertEquals(null, error)
    }

    @Test
    fun aWithheldFailureKeepsWhereItHappened() {
        Log.minimumLevel = LogLevel.WARN
        val failure = java.io.IOException("No space left on /Users/someone/library")
        Log.warn("t", failure) { "x" }
        val logged = records.single().third
        assertIs<RedactedLogException>(logged)
        assertTrue(logged.stackTrace.contentEquals(failure.stackTrace))
    }

    @Test
    fun thePrintedRecordOfAWithheldFailureQuotesNothingOfIt() {
        Log.minimumLevel = LogLevel.WARN
        val failure = IllegalStateException("closing failed")
        failure.addSuppressed(IllegalArgumentException("https://cdn.test/p.jpg?token=secret-value"))
        Log.warn("t", failure) { "x" }
        val printed = java.io.StringWriter().also { records.single().third?.printStackTrace(java.io.PrintWriter(it)) }.toString()
        assertFalse("secret-value" in printed, printed)
        assertFalse("closing failed" in printed, printed)
    }

    @Test
    fun otherFailuresAreLoggedAsThrown() {
        Log.minimumLevel = LogLevel.WARN
        val failure = IllegalStateException("plain")
        Log.warn("t", failure) { "x" }
        assertSame(failure, records.single().third)
    }
}
